#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")] // hide console window on Windows in release

use std::fmt::Display;
use std::path::PathBuf;
use std::time::Instant;

use anyhow::{Context, Result};
use clap::{Parser, command};
use serde::{Deserialize, Serialize};
use tokio::sync::oneshot;
use tracing_appender::rolling::Rotation;
use tracing_subscriber::prelude::*;
use tracing_subscriber::{EnvFilter, reload};

use crate::player_data::ExportSettings;

mod admin;
mod app;
mod capture;
mod good;
mod monitor;
mod player_data;
mod update;
mod wish;

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

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize, Default)]
pub enum TracingLevel {
    #[default]
    Default,
    VerboseInfo,
    VerboseDebug,
    VerboseTrace,
}

impl Display for TracingLevel {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TracingLevel::Default => write!(f, "Default"),
            TracingLevel::VerboseInfo => write!(f, "Verbose Info"),
            TracingLevel::VerboseDebug => write!(f, "Verbose Debug"),
            TracingLevel::VerboseTrace => write!(f, "Verbose Trace"),
        }
    }
}

impl TracingLevel {
    fn get_filter(&self) -> &'static str {
        match self {
            TracingLevel::Default => {
                if cfg!(debug_assertions) {
                    "info"
                } else {
                    "warn,irminsul=info"
                }
            }
            TracingLevel::VerboseInfo => "info",
            TracingLevel::VerboseDebug => "debug",
            TracingLevel::VerboseTrace => "trace",
        }
    }
}

struct ReloadHandle(reload::Handle<EnvFilter, tracing_subscriber::Registry>);

impl ReloadHandle {
    pub fn set_filter(&mut self, filter: &str) {
        if let Err(e) = self.0.reload(filter) {
            tracing::warn!("Failed to set tracing filter to \"{filter}\": {e}");
        }
        tracing::info!("Set tracing filter to \"{filter}\"");
    }
}

fn main() -> eframe::Result {
    let (_guard, reload_handle) = tracing_init().unwrap();

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
        Box::new(|cc| Ok(Box::new(app::IrminsulApp::new(cc, reload_handle)))),
    )
}

fn log_dir() -> Result<PathBuf> {
    let mut dir = eframe::storage_dir(APP_ID).context("Storage dir not found")?;
    dir.push("log");
    Ok(dir)
}

#[cfg(windows)]
fn open_log_dir() -> Result<()> {
    use std::process::Command;

    let dir = log_dir()?;

    let _ = Command::new("explorer.exe")
        .args([dir.as_os_str()])
        .output()?;

    Ok(())
}

fn tracing_init() -> Result<(tracing_appender::non_blocking::WorkerGuard, ReloadHandle)> {
    let appender = tracing_appender::rolling::Builder::new()
        .filename_prefix("log")
        .rotation(Rotation::DAILY)
        .max_log_files(7)
        .build(log_dir()?)?;
    let (non_blocking_appender, guard) = tracing_appender::non_blocking(appender);

    let filter = EnvFilter::new(TracingLevel::default().get_filter());
    let (filter, reload_handle) = reload::Layer::new(filter);
    let writer = tracing_subscriber::fmt::layer()
        .with_writer(non_blocking_appender)
        .with_ansi(false);
    tracing_subscriber::registry()
        .with(filter)
        .with(writer)
        .init();
    tracing::info!("Tracing initialized and logging to file.");

    Ok((guard, ReloadHandle(reload_handle)))
}
