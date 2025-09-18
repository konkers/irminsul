use std::fmt::Display;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::PathBuf;
use std::thread;
use std::time::Instant;

use anyhow::{Context as _, Result, anyhow};
use egui::{
    Button, Color32, Context, DragValue, Id, Key, KeyboardShortcut, Modal, Modifiers, OpenUrl,
    PointerButton, RichText, Sense, ViewportCommand,
};
use egui_file_dialog::FileDialog;
use egui_notify::Toasts;
use serde::{Deserialize, Serialize};
use tokio::sync::{mpsc, oneshot, watch};

use crate::monitor::Monitor;
use crate::player_data::ExportSettings;
use crate::update::check_for_app_update;
use crate::{AppState, ConfirmationType, Message, State, open_log_dir};

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct SavedAppState {
    export_settings: ExportSettings,
    #[serde(default)]
    auto_start_capture: bool,
    log_raw_packets: bool,
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
            log_raw_packets: false,
        }
    }
}

#[derive(Clone, Debug)]
enum OptimizerExportTarget {
    None,
    Clipboard,
    File,
}

pub struct IrminsulApp {
    ui_message_tx: mpsc::UnboundedSender<Message>,
    state_rx: watch::Receiver<AppState>,
    log_packets_tx: watch::Sender<bool>,

    toasts: Toasts,

    power_tools_open: bool,
    bug_report_open: bool,

    capture_settings_open: bool,

    optimizer_settings_open: bool,
    optimizer_export_rx: Option<oneshot::Receiver<Result<String>>>,
    optimizer_save_dialog: FileDialog,
    optimizer_save_path: Option<PathBuf>,
    optimizer_export_target: OptimizerExportTarget,

    restarting: bool,

    saved_state: SavedAppState,
}

trait ToastError<T> {
    fn toast_error(self, app: &mut IrminsulApp) -> Option<T>;
}

impl<T, E: Display> ToastError<T> for std::result::Result<T, E> {
    fn toast_error(self, app: &mut IrminsulApp) -> Option<T> {
        match self {
            Ok(val) => Some(val),
            Err(e) => {
                tracing::error!("{e}");
                app.toasts.error(e.to_string());
                None
            }
        }
    }
}

fn start_async_runtime(
    egui_ctx: Context,
    log_packets_rx: watch::Receiver<bool>,
) -> (mpsc::UnboundedSender<Message>, watch::Receiver<AppState>) {
    tracing::info!("starting tokio async");
    let (ui_message_tx, mut ui_message_rx) = mpsc::unbounded_channel::<Message>();

    let (state_tx, state_rx) = watch::channel(AppState::new());
    let mut updater_state_rx = state_rx.clone();
    let updater_ctx = egui_ctx.clone();
    thread::spawn(|| {
        let rt = tokio::runtime::Runtime::new().unwrap();

        rt.block_on(async {
            // Before starting the monitor, check for updates if not in debug mode
            tracing::info!("Checking for update");
            if let Err(e) = check_for_app_update(&state_tx, &mut ui_message_rx).await {
                tracing::error!("error checking for update: {e}");
            }

            // Notify egui of state changes.
            tokio::spawn(async move {
                loop {
                    let _ = updater_state_rx.changed().await;
                    updater_ctx.request_repaint();
                }
            });
            tracing::info!("Starting monitor");
            let monitor = Monitor::new(state_tx, ui_message_rx, log_packets_rx, egui_ctx);
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

        let (log_packets_tx, log_packets_rx) = watch::channel(saved_state.log_raw_packets);
        let (ui_message_tx, state_rx) = start_async_runtime(cc.egui_ctx.clone(), log_packets_rx);

        if saved_state.auto_start_capture {
            if let Err(e) = ui_message_tx.send(Message::StartCapture) {
                tracing::error!("Failed to send auto start message: {e}");
            }
        }

        let optimizer_save_dialog = FileDialog::new()
            .add_file_filter_extensions("JSON files", vec!["json"])
            .default_file_name("genshin_export.json");

        let toasts = Toasts::default().with_anchor(egui_notify::Anchor::BottomLeft);

        Self {
            saved_state,
            ui_message_tx,
            log_packets_tx,
            toasts,
            power_tools_open: false,
            bug_report_open: false,
            capture_settings_open: false,
            optimizer_settings_open: false,
            optimizer_export_rx: None,
            optimizer_save_dialog,
            optimizer_save_path: None,
            optimizer_export_target: OptimizerExportTarget::None,
            restarting: false,
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
        self.toasts.show(ctx);
        self.optimizer_save_dialog.update(ctx);
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.with_layout(egui::Layout::top_down(egui::Align::LEFT), |ui| {
                egui::Image::new(egui::include_image!("../assets/background.webp"))
                    .paint_at(ui, ui.ctx().screen_rect());
            });

            ui.vertical(|ui| {
                self.title_bar(ui);
                ui.add_space(25.);

                // Handle power tools here instead of main UI to allow it to be opened
                // in other app states.
                let power_tools_shortcut = KeyboardShortcut {
                    modifiers: Modifiers {
                        command: true,
                        shift: true,
                        ..Default::default()
                    },
                    logical_key: Key::P,
                };
                ui.ctx().input_mut(|i| {
                    if i.consume_shortcut(&power_tools_shortcut) {
                        self.power_tools_open = true;
                    }
                });

                if self.power_tools_open {
                    let modal = Modal::new(Id::new("Power Tools")).show(ui.ctx(), |ui| {
                        self.power_tools_modal(ui);
                    });
                    if modal.should_close() {
                        self.power_tools_open = false;
                    }
                }

                if self.bug_report_open {
                    let modal = Modal::new(Id::new("Bug Report")).show(ui.ctx(), |ui| {
                        self.bug_report_modal(ui);
                    });
                    if modal.should_close() {
                        self.bug_report_open = false;
                    }
                }

                ui.horizontal(|ui| {
                    ui.add_space(525.);
                    let state = self.state_rx.borrow_and_update().clone();
                    ui.vertical(|ui| match state.state {
                        State::Starting => (),
                        State::CheckingForUpdate => self.checking_for_update_ui(ui),
                        State::WaitingForUpdateConfirmation(status) => {
                            self.waiting_for_update_confirmation_ui(ui, status)
                        }
                        State::Updating => self.updating_ui(ui),
                        State::Updated => self.updated_ui(ui),
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
                ui.horizontal(|ui| {
                    let button = ui.add(
                        Button::new(
                            RichText::new(egui_material_icons::icons::ICON_BUG_REPORT).size(16.),
                        )
                        .frame(false),
                    );
                    if button.clicked() {
                        self.bug_report_open = true;
                    }
                    ui.label(env!("CARGO_PKG_VERSION").to_string());
                    egui::warn_if_debug_build(ui);
                });
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

    fn checking_for_update_ui(&self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.label("Checking for Irminsul updates".to_string());
        });
    }

    fn waiting_for_update_confirmation_ui(&self, ui: &mut egui::Ui, version: String) {
        ui.label(format!(
            "Update {} available.  Download and install?",
            version
        ));

        ui.horizontal(|ui| {
            if ui.add(egui::Button::new("Yes")).clicked() {
                if let Err(e) = self.ui_message_tx.send(Message::UpdateAcknowledged) {
                    tracing::error!("Unable to send UI message: {e}");
                }
            }
            if ui.add(egui::Button::new("No")).clicked() {
                if let Err(e) = self.ui_message_tx.send(Message::UpdateCanceled) {
                    tracing::error!("Unable to send UI message: {e}");
                }
            }
        });
    }

    fn updating_ui(&self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.label("Downloading and updating...".to_string());
            ui.spinner();
        });
    }

    fn updated_ui(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.label("Updated. Restarting...".to_string());
        });
        if !self.restarting {
            let program_name = std::env::args().next().unwrap();
            let _ = std::process::Command::new(program_name).spawn();
            ui.ctx().send_viewport_cmd(egui::ViewportCommand::Close);
            self.restarting = true;
        }
    }

    fn checking_for_data_ui(&self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.label("Checking for game data updates".to_string());
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
        self.optimizer_handle_export().toast_error(self);

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
                            && app_state.updated.items_updated.is_some()
                            && self.optimizer_export_rx.is_none(),
                        |ui| {
                            if ui
                                .button(egui_material_icons::icons::ICON_DOWNLOAD)
                                .clicked()
                            {
                                self.optimizer_save_dialog.save_file();
                            }

                            if let Some(path) = self.optimizer_save_dialog.take_picked() {
                                self.optimizer_save_path = Some(path);
                                self.genshin_optimizer_request_export(OptimizerExportTarget::File);
                            }

                            if ui
                                .button(egui_material_icons::icons::ICON_CONTENT_PASTE_GO)
                                .clicked()
                            {
                                self.genshin_optimizer_request_export(
                                    OptimizerExportTarget::Clipboard,
                                );
                            }
                        },
                    );
                },
            );
        });
    }

    fn genshin_optimizer_request_export(&mut self, target: OptimizerExportTarget) {
        let (tx, rx) = oneshot::channel();
        let _ = self.ui_message_tx.send(Message::ExportGenshinOptimizer(
            self.saved_state.export_settings.clone(),
            tx,
        ));
        self.optimizer_export_target = target;
        self.optimizer_export_rx = Some(rx);
    }

    fn power_tools_modal(&mut self, ui: &mut egui::Ui) {
        ui.set_width(300.0);
        ui.heading("Power Tools");
        ui.separator();
        if ui
            .checkbox(&mut self.saved_state.log_raw_packets, "Log raw packets")
            .changed()
        {
            let _ = self.log_packets_tx.send(self.saved_state.log_raw_packets);
        };
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

    fn bug_report_modal(&mut self, ui: &mut egui::Ui) {
        ui.set_width(300.0);
        ui.heading("Bug Report");
        ui.separator();
        ui.label("When filing a bug, please include the latest log file:");
        if ui.button("Open log directory").clicked() {
            thread::spawn(|| {
                let _ = open_log_dir();
            });
        }
        ui.separator();
        egui::Sides::new().show(
            ui,
            |_ui| {},
            |ui| {
                if ui.button("New GitHub Issue").clicked() {
                    ui.ctx().open_url(OpenUrl::new_tab(
                        "https://github.com/konkers/irminsul/issues/new",
                    ));
                    ui.close()
                }
                if ui.button("Cancel").clicked() {
                    ui.close()
                }
            },
        );
    }

    fn capture_settings_modal(&mut self, ui: &mut egui::Ui) {
        ui.set_width(300.0);
        ui.heading("Genshin Optimizer Settings");
        ui.separator();
        ui.checkbox(
            &mut self.saved_state.auto_start_capture,
            "Start capture on Irminsul launch",
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

    fn optimizer_handle_export(&mut self) -> Result<()> {
        let Some(rx) = self.optimizer_export_rx.take() else {
            return Ok(());
        };

        let json = rx.blocking_recv()??;

        match self.optimizer_export_target {
            OptimizerExportTarget::None => {
                tracing::warn!("Unexpected json export");
            }
            OptimizerExportTarget::Clipboard => {
                self.optimizer_save_to_clipboard(json)?;
            }
            OptimizerExportTarget::File => {
                self.optimizer_save_to_file(json)?;
            }
        }

        self.optimizer_export_target = OptimizerExportTarget::None;
        Ok(())
    }

    fn optimizer_save_to_clipboard(&mut self, json: String) -> Result<()> {
        arboard::Clipboard::new()
            .and_then(|mut c| c.set_text(json.clone()))
            .context("Error copying data to clipboard")?;
        self.toasts
            .info("Genshin Optimizer data copied to clipboard");
        Ok(())
    }

    fn optimizer_save_to_file(&mut self, json: String) -> Result<()> {
        let path = self
            .optimizer_save_path
            .take()
            .ok_or_else(|| anyhow!("No save file path set"))?;

        let file = File::create(&path).with_context(|| format!("Unable to open file {path:?}"))?;
        let mut writer = BufWriter::new(file);
        writer.write_all(json.as_bytes())?;

        self.toasts.info("Genshin Optimizer data saved to file");
        Ok(())
    }

    fn achievement_ui(&self, ui: &mut egui::Ui, _app_state: &AppState) {
        Self::section_header(ui, "Achievement Export");
        ui.label("coming soon".to_string());
    }

    fn section_header(ui: &mut egui::Ui, name: &str) {
        ui.label(RichText::new(name).size(18.));
    }
}
