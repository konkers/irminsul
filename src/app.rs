use std::thread;
use std::time::Instant;

use anyhow::Result;
use egui::{
    Button, Color32, Context, DragValue, Id, Modal, PointerButton, RichText, Sense, ViewportCommand,
};
use serde::{Deserialize, Serialize};
use tokio::sync::{mpsc, oneshot, watch};

use crate::monitor::Monitor;
use crate::player_data::ExportSettings;
use crate::{AppState, ConfirmationType, Message, State};

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct SavedAppState {
    export_settings: ExportSettings,
    #[serde(default)]
    auto_start_capture: bool,
}

impl Default for SavedAppState {
    fn default() -> Self {
        Self {
            export_settings: ExportSettings {
                include_characters: true,
                include_artifacts: true,
                include_weapons: true,
                include_materials: true,
                min_character_level: 1,
                min_character_ascension: 0,
                min_character_constellation: 0,
                min_artifact_level: 0,
                min_artifact_rarity: 5,
                min_weapon_level: 1,
                min_weapon_refinement: 0,
                min_weapon_ascension: 0,
                min_weapon_rarity: 3,
            },
            auto_start_capture: false,
        }
    }
}

pub struct IrminsulApp {
    ui_message_tx: mpsc::UnboundedSender<Message>,
    state_rx: watch::Receiver<AppState>,

    capture_settings_open: bool,

    optimizer_settings_open: bool,
    optimizer_export_open: bool,
    optimizer_export_rx: Option<oneshot::Receiver<Result<String>>>,
    optimizer_export_result: Option<Result<String>>,

    saved_state: SavedAppState,
}

pub fn start_async_runtime(
    egui_ctx: Context,
) -> (mpsc::UnboundedSender<Message>, watch::Receiver<AppState>) {
    tracing::info!("starting tokio async");
    let (ui_message_tx, ui_message_rx) = mpsc::unbounded_channel::<Message>();

    let monitor = Monitor::new(ui_message_rx, egui_ctx.clone());
    let state_rx = monitor.subscribe();
    thread::spawn(|| {
        let rt = tokio::runtime::Runtime::new().unwrap();
        tracing::info!("Starting monitor");

        rt.block_on(async {
            // Hack: request a repaint every second so the "time since update" refreshes.
            tokio::spawn(async move {
                loop {
                    tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
                    egui_ctx.request_repaint();
                }
            });
            monitor.run().await;
        });
    });
    tracing::info!("started tokio");
    (ui_message_tx, state_rx)
}

impl IrminsulApp {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        egui_extras::install_image_loaders(&cc.egui_ctx);
        egui_material_icons::initialize(&cc.egui_ctx);

        let saved_state: SavedAppState = if let Some(storage) = cc.storage {
            eframe::get_value(storage, eframe::APP_KEY).unwrap_or_default()
        } else {
            Default::default()
        };

        let (ui_message_tx, state_rx) = start_async_runtime(cc.egui_ctx.clone());

        if saved_state.auto_start_capture {
            if let Err(e) = ui_message_tx.send(Message::StartCapture) {
                tracing::error!("Failed to send auto start message: {e}");
            }
        }

        Self {
            saved_state,
            ui_message_tx,
            capture_settings_open: false,
            optimizer_settings_open: false,
            optimizer_export_open: false,
            optimizer_export_rx: None,
            optimizer_export_result: None,
            state_rx,
        }
    }
}

impl eframe::App for IrminsulApp {
    /// Called by the framework to save state before shutdown.
    fn save(&mut self, storage: &mut dyn eframe::Storage) {
        eframe::set_value(storage, eframe::APP_KEY, &self.saved_state);
    }

    /// Called each time the UI needs repainting, which may be many times per second.
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.with_layout(egui::Layout::top_down(egui::Align::LEFT), |ui| {
                egui::Image::new(egui::include_image!("../assets/background.webp"))
                    .paint_at(ui, ui.ctx().screen_rect());
            });

            ui.vertical(|ui| {
                self.title_bar(ui);
                ui.add_space(25.);
                ui.horizontal(|ui| {
                    ui.add_space(525.);
                    let state = self.state_rx.borrow_and_update().clone();
                    ui.vertical(|ui| match state.state {
                        State::Starting => (),
                        State::CheckingForData => self.checking_for_data_ui(ui),
                        State::WaitingForDownloadConfirmation(confirmation_type) => {
                            self.waiting_for_download_confirmation_ui(ui, confirmation_type)
                        }
                        State::Downloading => self.load_data_ui(ui),
                        State::Main => self.main_ui(ui, &state),
                    });
                });
            });

            ui.with_layout(egui::Layout::bottom_up(egui::Align::RIGHT), |ui| {
                egui::warn_if_debug_build(ui);
            });
        });
    }
}

impl IrminsulApp {
    fn title_bar(&self, ui: &mut egui::Ui) {
        let (_, button_width) = egui::Sides::new().show(
            ui,
            |_ui| {},
            |ui| {
                let button = ui.add(
                    Button::new(RichText::new(egui_material_icons::icons::ICON_CLOSE).size(24.))
                        .frame(false),
                );
                if button.clicked() {
                    ui.ctx().send_viewport_cmd(egui::ViewportCommand::Close);
                }
                button.rect.width()
            },
        );

        let app_rect = ui.max_rect();

        let title_bar_height = 32.0;
        let title_bar_rect = {
            let mut rect = app_rect;
            rect.max.y = rect.min.y + title_bar_height;
            rect.max.x -= button_width;
            rect
        };

        let response = ui.interact(
            title_bar_rect,
            Id::new("title_bar"),
            Sense::click_and_drag(),
        );

        if response.drag_started_by(PointerButton::Primary) {
            ui.ctx().send_viewport_cmd(ViewportCommand::StartDrag);
        }
    }

    fn checking_for_data_ui(&self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.label("Checking for data and updates".to_string());
        });
    }

    fn waiting_for_download_confirmation_ui(
        &self,
        ui: &mut egui::Ui,
        confirmation_type: ConfirmationType,
    ) {
        let label = match confirmation_type {
            ConfirmationType::Initial => "Irminsul needs to download initial data",
            ConfirmationType::Update => "New data available",
        };
        ui.label(label.to_string());
        if ui.add(egui::Button::new("Download")).clicked() {
            if let Err(e) = self.ui_message_tx.send(Message::DownloadAcknowledged) {
                tracing::error!("Unable to send UI message{e}");
            }
        }
    }

    fn load_data_ui(&self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.label("Downloading Data".to_string());
            ui.spinner();
        });
    }

    fn main_ui(&mut self, ui: &mut egui::Ui, app_state: &AppState) {
        if self.capture_settings_open {
            let modal = Modal::new(Id::new("Capture Settings")).show(ui.ctx(), |ui| {
                self.capture_settings_modal(ui);
            });
            if modal.should_close() {
                self.capture_settings_open = false;
            }
        }

        if self.optimizer_settings_open {
            let modal = Modal::new(Id::new("Optimizer Settings")).show(ui.ctx(), |ui| {
                self.optimizer_settings_modal(ui);
            });
            if modal.should_close() {
                self.optimizer_settings_open = false;
            }
        }
        if self.optimizer_export_open {
            let modal = Modal::new(Id::new("Optimizer Export")).show(ui.ctx(), |ui| {
                self.optimizer_export_modal(ui);
            });
            if modal.should_close() {
                self.optimizer_export_open = false;
            }
        }
        self.capture_ui(ui, app_state);
        ui.separator();
        self.genshin_optimizer_ui(ui, app_state);
        ui.separator();
        self.achievement_ui(ui, app_state);
    }

    fn capture_ui(&mut self, ui: &mut egui::Ui, app_state: &AppState) {
        ui.vertical(|ui| {
            egui::Sides::new().show(
                ui,
                |ui| {
                    Self::section_header(ui, "Packet Capture");
                },
                |ui| {
                    if ui
                        .button(egui_material_icons::icons::ICON_SETTINGS)
                        .clicked()
                    {
                        self.capture_settings_open = true;
                    }

                    if app_state.capturing {
                        if ui.button(egui_material_icons::icons::ICON_PAUSE).clicked() {
                            let _ = self.ui_message_tx.send(Message::StopCapture);
                        }
                    } else if ui
                        .button(egui_material_icons::icons::ICON_PLAY_ARROW)
                        .clicked()
                    {
                        let _ = self.ui_message_tx.send(Message::StartCapture);
                    }
                },
            );
        });
        egui::Grid::new("capture_stats")
            .striped(false)
            .num_columns(2)
            .min_col_width(0.)
            .show(ui, |ui| {
                Self::data_state(ui, "Items", app_state.updated.items_updated);
                Self::data_state(ui, "Characters", app_state.updated.characters_updated);
                Self::data_state(ui, "Achievements", app_state.updated.achievements_updated);
            });
    }

    fn data_state(ui: &mut egui::Ui, source: &str, last_updated: Option<Instant>) {
        let updated_icon = match last_updated {
            Some(_) => RichText::new(egui_material_icons::icons::ICON_CHECK_CIRCLE)
                .color(Color32::from_hex("#00ab3f").unwrap()),
            None => RichText::new(egui_material_icons::icons::ICON_CHECK_INDETERMINATE_SMALL),
        };
        ui.label(updated_icon);
        ui.label(source);
        ui.end_row();
    }

    fn genshin_optimizer_ui(&mut self, ui: &mut egui::Ui, app_state: &AppState) {
        ui.vertical(|ui| {
            egui::Sides::new().show(
                ui,
                |ui| {
                    Self::section_header(ui, "Genshin Optimizer");
                },
                |ui| {
                    if ui
                        .button(egui_material_icons::icons::ICON_SETTINGS)
                        .clicked()
                    {
                        self.optimizer_settings_open = true;
                    }

                    ui.add_enabled_ui(
                        app_state.updated.characters_updated.is_some()
                            && app_state.updated.items_updated.is_some(),
                        |ui| {
                            if ui
                                .button(egui_material_icons::icons::ICON_CONTENT_PASTE_GO)
                                .clicked()
                            {
                                let (tx, rx) = oneshot::channel();
                                let _ = self.ui_message_tx.send(Message::ExportGenshinOptimizer(
                                    self.saved_state.export_settings.clone(),
                                    tx,
                                ));
                                self.optimizer_export_open = true;
                                self.optimizer_export_rx = Some(rx);
                            }
                        },
                    );
                },
            );
        });
    }

    fn capture_settings_modal(&mut self, ui: &mut egui::Ui) {
        ui.set_width(300.0);
        ui.heading("Genshin Optimizer Settings");
        ui.separator();
        ui.checkbox(
            &mut self.saved_state.auto_start_capture,
            "Start capture on app launch",
        );
        ui.separator();
        egui::Sides::new().show(
            ui,
            |_ui| {},
            |ui| {
                if ui.button("Ok").clicked() {
                    ui.close()
                }
            },
        );
    }

    fn optimizer_settings_modal(&mut self, ui: &mut egui::Ui) {
        ui.set_width(300.0);
        ui.heading("Genshin Optimizer Settings");
        ui.separator();
        ui.checkbox(
            &mut self.saved_state.export_settings.include_characters,
            "Characters",
        );
        ui.horizontal(|ui| {
            ui.add_space(20.);
            egui::Grid::new("char_options")
                .striped(true)
                .show(ui, |ui| {
                    ui.label("Min level".to_string());
                    ui.add(
                        DragValue::new(&mut self.saved_state.export_settings.min_character_level)
                            .range(1..=90),
                    );
                    ui.end_row();
                    ui.label("Min ascension".to_string());
                    ui.add(
                        DragValue::new(
                            &mut self.saved_state.export_settings.min_character_ascension,
                        )
                        .range(0..=6),
                    );
                    ui.end_row();
                    ui.label("Min constellation".to_string());
                    ui.add(
                        DragValue::new(
                            &mut self.saved_state.export_settings.min_character_constellation,
                        )
                        .range(0..=6),
                    );
                    ui.end_row();
                });
        });
        ui.checkbox(
            &mut self.saved_state.export_settings.include_artifacts,
            "Artifacts",
        );
        ui.horizontal(|ui| {
            ui.add_space(20.);
            egui::Grid::new("artifact_options")
                .striped(true)
                .show(ui, |ui| {
                    ui.label("Min level".to_string());
                    ui.add(
                        DragValue::new(&mut self.saved_state.export_settings.min_artifact_level)
                            .range(0..=20),
                    );
                    ui.end_row();
                    ui.label("Min rarity".to_string());
                    ui.add(
                        DragValue::new(&mut self.saved_state.export_settings.min_artifact_rarity)
                            .range(0..=6),
                    );
                    ui.end_row();
                });
        });
        ui.checkbox(
            &mut self.saved_state.export_settings.include_weapons,
            "Weapons",
        );
        ui.horizontal(|ui| {
            ui.add_space(20.);
            egui::Grid::new("weapon_options")
                .striped(true)
                .show(ui, |ui| {
                    ui.label("Min level".to_string());
                    ui.add(
                        DragValue::new(&mut self.saved_state.export_settings.min_weapon_level)
                            .range(1..=90),
                    );
                    ui.end_row();

                    ui.label("Min refinement".to_string());
                    ui.add(
                        DragValue::new(&mut self.saved_state.export_settings.min_weapon_refinement)
                            .range(1..=5),
                    );
                    ui.end_row();

                    ui.label("Min ascension".to_string());
                    ui.add(
                        DragValue::new(&mut self.saved_state.export_settings.min_weapon_ascension)
                            .range(0..=6),
                    );
                    ui.end_row();

                    ui.label("Min rarity".to_string());
                    ui.add(
                        DragValue::new(&mut self.saved_state.export_settings.min_weapon_rarity)
                            .range(1..=5),
                    );
                    ui.end_row();
                });
        });
        ui.checkbox(
            &mut self.saved_state.export_settings.include_materials,
            "Materials",
        );
        ui.separator();
        egui::Sides::new().show(
            ui,
            |_ui| {},
            |ui| {
                if ui.button("Ok").clicked() {
                    ui.close()
                }
            },
        );
    }
    fn optimizer_export_modal(&mut self, ui: &mut egui::Ui) {
        if let Some(rx) = self.optimizer_export_rx.take()
            && let Ok(json) = rx.blocking_recv()
        {
            self.optimizer_export_result = Some(json);
        }

        ui.set_width(300.0);
        ui.heading("Genshin Optimizer export");
        ui.separator();

        let json = match &self.optimizer_export_result {
            Some(Ok(json)) => {
                ui.label("Export generated".to_string());
                Some(json)
            }
            Some(Err(e)) => {
                ui.label(format!("Error generating export: {e}"));
                None
            }
            None => {
                ui.label("Generating export...".to_string());
                None
            }
        };

        ui.separator();
        egui::Sides::new().show(
            ui,
            |_ui| {},
            |ui| {
                if ui.button("Close").clicked() {
                    ui.close()
                }

                if let Some(json) = json {
                    if ui.button("Copy to Clipboard").clicked() {
                        if let Err(e) =
                            arboard::Clipboard::new().and_then(|mut c| c.set_text(json.clone()))
                        {
                            tracing::error!("Error setting clipboard: {e}");
                        }
                        ui.close()
                    }
                }
            },
        );
    }

    fn achievement_ui(&self, ui: &mut egui::Ui, _app_state: &AppState) {
        Self::section_header(ui, "Achievement Export");
        ui.label("coming soon".to_string());
    }

    fn section_header(ui: &mut egui::Ui, name: &str) {
        ui.label(RichText::new(name).size(18.));
    }
}
