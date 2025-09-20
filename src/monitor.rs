use std::collections::HashMap;
use std::fs;
use std::io::{BufWriter, Write};
use std::time::Instant;

use anime_game_data::AnimeGameData;
use anyhow::{Context, Result, anyhow};
use auto_artifactarium::{
    GameCommand, GamePacket, GameSniffer, matches_achievement_packet, matches_avatar_packet,
    matches_item_packet,
};
use base64::prelude::*;
use chrono::prelude::*;
use pktmon::Packet;
use tokio::sync::{mpsc, watch};
use tokio_util::sync::CancellationToken;

use crate::capture::PacketCapture;
use crate::player_data::PlayerData;
use crate::{APP_ID, AppState, ConfirmationType, DataUpdated, Message, State};

struct AppStateManager {
    app_state: AppState,
    state_tx: watch::Sender<AppState>,
}

impl AppStateManager {
    fn new(app_state: AppState, state_tx: watch::Sender<AppState>) -> Self {
        Self {
            app_state,
            state_tx,
        }
    }

    pub fn update_app_state(&mut self, state: State) {
        self.app_state.state = state;
        let _ = self.state_tx.send(self.app_state.clone());
    }

    pub fn update_capturing_state(&mut self, capturing: bool) {
        self.app_state.capturing = capturing;
        let _ = self.state_tx.send(self.app_state.clone());
    }

    pub fn update_timestamps(&mut self, updated: DataUpdated) {
        self.app_state.updated = updated;
        let _ = self.state_tx.send(self.app_state.clone());
    }
}

pub struct Monitor {
    app_state: AppStateManager,
    ui_message_rx: mpsc::UnboundedReceiver<Message>,
    log_packet_rx: watch::Receiver<bool>,
    player_data: PlayerData,
    sniffer: GameSniffer,
    capture_cancel_token: Option<CancellationToken>,
    packet_tx: mpsc::UnboundedSender<Packet>,
    packet_rx: mpsc::UnboundedReceiver<Packet>,
}

impl Monitor {
    pub async fn new(
        state_tx: watch::Sender<AppState>,
        mut ui_message_rx: mpsc::UnboundedReceiver<Message>,
        log_packet_rx: watch::Receiver<bool>,
    ) -> Result<Self> {
        let mut app_state = AppStateManager::new(state_tx.borrow().clone(), state_tx.clone());
        let game_data = get_database(&mut app_state, &mut ui_message_rx).await?;
        let player_data = PlayerData::new(game_data);
        let keys = load_keys()?;
        let sniffer = GameSniffer::new().set_initial_keys(keys);
        let (packet_tx, packet_rx) = mpsc::unbounded_channel();

        Ok(Self {
            app_state,
            player_data,
            ui_message_rx,
            log_packet_rx,
            sniffer,
            capture_cancel_token: None,
            packet_tx,
            packet_rx,
        })
    }

    pub async fn run(mut self) {
        self.app_state.update_app_state(State::Main);

        loop {
            #[rustfmt::skip]
                tokio::select! {
                    Some(packet) = self.packet_rx.recv() => self.handle_packet(packet),
                    Some(msg) = self.ui_message_rx.recv() => self.handle_ui_msg(msg),
                }
        }
    }

    fn handle_ui_msg(&mut self, msg: Message) {
        match msg {
            Message::StartCapture => {
                if self.capture_cancel_token.is_some() {
                    tracing::warn!("Capture start request with an existing cancel token");
                }

                // Spawn capture task.
                let cancel_token = CancellationToken::new();
                tokio::spawn(capture_task(cancel_token.clone(), self.packet_tx.clone()));
                self.capture_cancel_token = Some(cancel_token);
                self.app_state.update_capturing_state(true);
            }
            Message::StopCapture => {
                let Some(cancel_token) = self.capture_cancel_token.take() else {
                    tracing::warn!("Capture stop request with no current cancel token");
                    return;
                };
                cancel_token.cancel();
                self.app_state.update_capturing_state(false);
            }
            Message::ExportGenshinOptimizer(settings, reply_tx) => {
                let _ = reply_tx.send(self.player_data.export_genshin_optimizer(&settings));
            }
            _ => (),
        }
    }

    fn handle_packet(&mut self, packet: Packet) {
        let Some(GamePacket::Commands(commands)) =
            self.sniffer.receive_packet(packet.payload.to_vec().clone())
        else {
            return;
        };

        let log_packets = *self.log_packet_rx.borrow_and_update();

        let mut updated = self.app_state.app_state.updated.clone();
        let mut has_new_data = false;

        for command in commands {
            let _span = tracing::info_span!("packet id {}", command.command_id);
            if log_packets {
                if let Err(e) = log_command(&command) {
                    tracing::info!("error logging command {e}");
                }
            }
            if let Some(items) = matches_item_packet(&command) {
                tracing::info!("Found item packet with {} items", items.len());
                self.player_data.process_items(&items);
                updated.items_updated = Some(Instant::now());
                has_new_data = true;
            } else if let Some(avatars) = matches_avatar_packet(&command) {
                tracing::info!("Found avatar packet with {} avatars", avatars.len());
                self.player_data.process_characters(&avatars);
                updated.characters_updated = Some(Instant::now());
                has_new_data = true;
            } else if let Some(achievements) = matches_achievement_packet(&command) {
                tracing::info!(
                    "Found achievement packet with {} achievements",
                    achievements.len()
                );
                self.player_data.process_achievements(&achievements);
                updated.achievements_updated = Some(Instant::now());
                has_new_data = true;
            }
        }

        if has_new_data {
            self.app_state.update_timestamps(updated);
        }
    }
}

async fn get_database(
    app_state: &mut AppStateManager,
    ui_message_rx: &mut mpsc::UnboundedReceiver<Message>,
) -> Result<AnimeGameData> {
    app_state.update_app_state(State::CheckingForData);

    let mut storage_dir = eframe::storage_dir(APP_ID).unwrap();
    storage_dir.push("data_cache.json");

    let mut db = anime_game_data::AnimeGameData::new_with_cache(&storage_dir).unwrap();
    if db.needs_update().await.unwrap() {
        let confirmation_type = if db.has_data() {
            ConfirmationType::Update
        } else {
            ConfirmationType::Initial
        };
        app_state.update_app_state(State::WaitingForDownloadConfirmation(confirmation_type));

        while let Some(msg) = ui_message_rx.recv().await {
            if matches!(msg, Message::DownloadAcknowledged) {
                app_state.update_app_state(State::Downloading);
                db.update().await.unwrap();
                break;
            }
        }
    }

    Ok(db)
}

async fn capture_task(
    cancel_token: CancellationToken,
    packet_tx: mpsc::UnboundedSender<Packet>,
) -> Result<()> {
    let mut capture =
        PacketCapture::new().map_err(|e| anyhow!("Error creating packet capture: {e}"))?;
    tracing::info!("starting capture");
    loop {
        let packet = tokio::select!(
            packet = capture.next_packet() => packet,
            _ = cancel_token.cancelled() => break,
        );
        let packet = match packet {
            Ok(packet) => packet,
            Err(e) => {
                tracing::error!("Error receiving packet: {e}");
                continue;
            }
        };

        if let Err(e) = packet_tx.send(packet) {
            tracing::error!("Error sending captured packet to monitor: {e}");
        }
    }
    tracing::info!("ending capture");
    Ok(())
}

fn log_command(command: &GameCommand) -> Result<()> {
    let mut packet_log_path = eframe::storage_dir(APP_ID).context("Storage dir not found")?;
    packet_log_path.push("packet_log");
    fs::create_dir_all(&packet_log_path)?;

    let now = Local::now();
    packet_log_path.push(format!(
        "{}-{}.bin",
        now.format("%Y-%m-%d_%H-%M-%S%.f"),
        command.command_id
    ));

    let file = fs::File::create(&packet_log_path)
        .with_context(|| format!("can't create file {packet_log_path:?}"))?;
    let mut writer = BufWriter::new(file);
    writer.write_all(&command.proto_data)?;

    Ok(())
}

fn load_keys() -> Result<HashMap<u16, Vec<u8>>> {
    let keys: HashMap<u16, String> = serde_json::from_slice(include_bytes!("../keys/gi.json"))?;

    keys.iter()
        .map(|(key, value)| -> Result<_, _> { Ok((*key, BASE64_STANDARD.decode(value)?)) })
        .collect::<Result<HashMap<_, _>>>()
}
