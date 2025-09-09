use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;

use anime_game_data::AnimeGameData;
use anyhow::{Result, anyhow};
use auto_artifactarium::{
    GamePacket, GameSniffer, matches_achievement_packet, matches_avatar_packet, matches_item_packet,
};
use base64::prelude::*;
use tokio::sync::{mpsc, oneshot, watch};
use tokio_util::sync::CancellationToken;

use crate::capture::PacketCapture;
use crate::player_data::{ExportSettings, PlayerData};
use crate::{APP_ID, AppState, ConfirmationType, DataUpdated, Message, State};

pub struct Monitor {
    egui_context: egui::Context,
    app_state: AppState,
    state_tx: watch::Sender<AppState>,
    ui_message_rx: mpsc::UnboundedReceiver<Message>,
}

impl Monitor {
    pub fn new(
        state_tx: watch::Sender<AppState>,
        ui_message_rx: mpsc::UnboundedReceiver<Message>,
        egui_context: egui::Context,
    ) -> Self {
        let app_state = state_tx.borrow().clone();
        Self {
            egui_context,
            app_state,
            state_tx,
            ui_message_rx,
        }
    }

    pub fn update_app_state(&mut self, state: State) {
        self.app_state.state = state;
        let _ = self.state_tx.send(self.app_state.clone());
        self.egui_context.request_repaint();
    }

    pub fn update_capturing_state(&mut self, capturing: bool) {
        self.app_state.capturing = capturing;
        let _ = self.state_tx.send(self.app_state.clone());
        self.egui_context.request_repaint();
    }

    pub fn update_data_updated_state(&mut self, data_updated: DataUpdated) {
        self.app_state.updated = data_updated;
        let _ = self.state_tx.send(self.app_state.clone());
        self.egui_context.request_repaint();
    }

    pub async fn run(mut self) {
        let game_data = Arc::new(self.get_database().await.unwrap());

        self.update_app_state(State::Main);

        loop {
            // Wait for request to start capture.
            self.update_capturing_state(false);
            if !matches!(self.ui_message_rx.recv().await, Some(Message::StartCapture)) {
                continue;
            }

            // Spawn capture task.
            self.app_state.updated = Default::default();
            self.update_capturing_state(true);
            let cancel_token = CancellationToken::new();
            let (data_updated_tx, mut data_updated_rx) = mpsc::unbounded_channel();
            let (export_request_tx, export_request_rx) = mpsc::unbounded_channel();
            let mut capture_join_handle = tokio::spawn(capture_task(
                cancel_token.clone(),
                export_request_rx,
                data_updated_tx,
                game_data.clone(),
            ));

            let ret = loop {
                #[rustfmt::skip]
                tokio::select! {
                    // If capture task exits, continue to non-capturing state.
                    ret = &mut capture_join_handle => {
                        break ret;
                    },

                    // Forward data updated state to app state.
                    Some(data_updated) = data_updated_rx.recv() => {
                        self.update_data_updated_state(data_updated);
                    }

                    // On request to stop capture, send cancel request to capture task.
                    Some(msg) = self.ui_message_rx.recv() => {
                        match msg {
                            Message::StopCapture => cancel_token.cancel(),
                            Message::ExportGenshinOptimizer(settings, reply_tx) => {
                                let _ = export_request_tx.send((settings, reply_tx));
                            }
                            _ => (),
                        }
                    }
                }
            };

            let ret = match ret {
                Ok(ret) => ret,
                Err(e) => {
                    tracing::error!("Join error on capture task: {e}");
                    continue;
                }
            };

            if let Err(e) = ret {
                tracing::error!("Capture task terminated with error: {e}");
            }
        }
    }

    async fn get_database(&mut self) -> Result<AnimeGameData> {
        self.update_app_state(State::CheckingForData);

        let mut storage_dir = eframe::storage_dir(APP_ID).unwrap();
        storage_dir.push("data_cache.json");

        let mut db = anime_game_data::AnimeGameData::new_with_cache(&storage_dir).unwrap();
        if db.needs_update().await.unwrap() {
            let confirmation_type = if db.has_data() {
                ConfirmationType::Update
            } else {
                ConfirmationType::Initial
            };
            self.update_app_state(State::WaitingForDownloadConfirmation(confirmation_type));

            while let Some(msg) = self.ui_message_rx.recv().await {
                if matches!(msg, Message::DownloadAcknowledged) {
                    self.update_app_state(State::Downloading);
                    db.update().await.unwrap();
                    break;
                }
            }
        }

        Ok(db)
    }
}

async fn capture_task(
    cancel_token: CancellationToken,
    mut export_request_rx: mpsc::UnboundedReceiver<(
        ExportSettings,
        oneshot::Sender<Result<String>>,
    )>,
    data_updated_tx: mpsc::UnboundedSender<DataUpdated>,
    game_data: Arc<AnimeGameData>,
) -> Result<()> {
    let mut player_data = PlayerData::new(&game_data);
    let mut updated = DataUpdated::new();

    let mut capture =
        PacketCapture::new().map_err(|e| anyhow!("Error creating packet capture: {e}"))?;
    let keys = load_keys()?;
    let mut sniffer = GameSniffer::new().set_initial_keys(keys);

    tracing::info!("starting capture");
    loop {
        let packet = tokio::select!(
            packet = capture.next_packet() => packet,
            Some((settings, reply_tx)) = export_request_rx.recv() => {
                let _ = reply_tx.send(player_data.export_genshin_optimizer(&settings));
                continue;
            }
            _ = cancel_token.cancelled() => break,
        );
        let packet = match packet {
            Ok(packet) => packet,
            Err(e) => {
                tracing::error!("Error receiving packet: {e}");
                continue;
            }
        };

        // TODO: Why does sniffer.receive_packet not take a reference to the packet?
        let Some(GamePacket::Commands(commands)) =
            sniffer.receive_packet(packet.payload.to_vec().clone())
        else {
            continue;
        };

        let mut has_new_data = false;
        for command in commands {
            let span = tracing::info_span!("packet id {}", command.command_id);
            let _trace = span.enter();

            if let Some(items) = matches_item_packet(&command) {
                tracing::info!("Found item packet with {} items", items.len());
                player_data.process_items(&items);
                updated.items_updated = Some(Instant::now());
                has_new_data = true;
            } else if let Some(avatars) = matches_avatar_packet(&command) {
                tracing::info!("Found avatar packet with {} avatars", avatars.len());
                player_data.process_characters(&avatars);
                updated.characters_updated = Some(Instant::now());
                has_new_data = true;
            } else if let Some(achievements) = matches_achievement_packet(&command) {
                tracing::info!(
                    "Found achievement packet with {} achievements",
                    achievements.len()
                );
                player_data.process_achievements(&achievements);
                updated.achievements_updated = Some(Instant::now());
                has_new_data = true;
            }
        }

        if has_new_data {
            if let Err(e) = data_updated_tx.send(updated.clone()) {
                tracing::error!("Error sending data updated status: {e}");
            }
        }
    }
    tracing::info!("ending capture");
    Ok(())
}

fn load_keys() -> Result<HashMap<u16, Vec<u8>>> {
    let keys: HashMap<u16, String> = serde_json::from_slice(include_bytes!("../keys/gi.json"))?;

    keys.iter()
        .map(|(key, value)| -> Result<_, _> { Ok((*key, BASE64_STANDARD.decode(value)?)) })
        .collect::<Result<HashMap<_, _>>>()
}
