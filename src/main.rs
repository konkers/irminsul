#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")] // hide console window on Windows in release

use std::time::Instant;

use anyhow::Result;
use clap::{Parser, command};
use tokio::sync::oneshot;

use crate::player_data::ExportSettings;

mod admin;
mod app;
mod capture;
mod good;
mod monitor;
mod player_data;
mod update;

const APP_ID: &str = "Irminsul";

#[derive(Clone, Copy, Debug)]
pub enum ConfirmationType {
    Initial,
    Update,
}

#[derive(Clone, Debug)]
pub enum State {
    Starting,
    CheckingForUpdate,
    WaitingForUpdateConfirmation(String),
    Updating,
    Updated,
    CheckingForData,
    WaitingForDownloadConfirmation(ConfirmationType),
    Downloading,
    Main,
}

#[derive(Debug)]
pub enum Message {
    UpdateAcknowledged,
    UpdateCanceled,
    DownloadAcknowledged,
    StartCapture,
    StopCapture,
    ExportGenshinOptimizer(ExportSettings, oneshot::Sender<Result<String>>),
}

#[derive(Clone, Debug)]
pub struct DataUpdated {
    achievements_updated: Option<Instant>,
    characters_updated: Option<Instant>,
    items_updated: Option<Instant>,
}

impl DataUpdated {
    pub fn new() -> Self {
        Self {
            achievements_updated: None,
            characters_updated: None,
            items_updated: None,
        }
    }
}

impl Default for DataUpdated {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Clone, Debug)]
pub struct AppState {
    state: State,
    capturing: bool,
    updated: DataUpdated,
}

impl AppState {
    fn new() -> Self {
        AppState {
            state: State::Starting,
            capturing: false,
            updated: DataUpdated::new(),
        }
    }
}

#[derive(Parser, Debug)]
#[command(version, about, long_about = None)]
struct Args {
    #[arg(long, default_value_t = false)]
    no_admin: bool,
}
fn main() -> eframe::Result {
    let _guard = tracing_init().unwrap();

    let args = Args::parse();

    if !args.no_admin {
        #[cfg(windows)]
        admin::ensure_admin();
    }

    let background_image_size = [1600., 1000.];

    let native_options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size(background_image_size.map(|v| v * 0.5))
            .with_resizable(false)
            .with_decorations(false)
            .with_icon(
                // NOTE: Adding an icon is optional
                eframe::icon_data::from_png_bytes(&include_bytes!("../assets/icon-256.png")[..])
                    .expect("Failed to load icon"),
            ),
        persist_window: false,
        ..Default::default()
    };
    eframe::run_native(
        "Irminsul",
        native_options,
        Box::new(|cc| Ok(Box::new(app::IrminsulApp::new(cc)))),
    )
}

fn tracing_init() -> anyhow::Result<tracing_appender::non_blocking::WorkerGuard> {
    let mut storage_dir =
        anyhow::Context::context(eframe::storage_dir(APP_ID), "Storage dir not found")?;
    storage_dir.push("log");

    let appender = tracing_appender::rolling::daily(storage_dir, "log");
    let (non_blocking_appender, guard) = tracing_appender::non_blocking(appender);

    tracing_subscriber::fmt()
        .with_writer(non_blocking_appender)
        .with_env_filter("warn,irminsul=info")
        .with_ansi(false)
        .init();
    tracing::info!("Tracing initialized and logging to file.");

    Ok(guard)
}
