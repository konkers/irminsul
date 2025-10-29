mod pcap_backend;
#[cfg(windows)]
mod pktmon_backend;

use std::fmt::{Debug, Display};

use anyhow::Error;
use async_trait::async_trait;
use clap::ValueEnum;

pub const PORT_RANGE: (u16, u16) = (22101, 22102);

#[derive(Debug)]
#[allow(dead_code)]
pub enum CaptureError {
    Filter(Error),
    Capture { has_captured: bool, error: Error },
    CaptureClosed,
    ChannelClosed,
}

impl Display for CaptureError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CaptureError::Filter(e) => write!(f, "Filter error: {}", e),
            CaptureError::Capture {
                has_captured,
                error,
            } => write!(
                f,
                "Capture error (has_captured = {}): {}",
                has_captured, error
            ),
            CaptureError::CaptureClosed => write!(f, "Capture closed"),
            CaptureError::ChannelClosed => write!(f, "Channel closed"),
        }
    }
}

pub type Result<T> = std::result::Result<T, CaptureError>;

#[async_trait]
pub trait CaptureBackend: Send {
    async fn next_packet(&mut self) -> Result<Vec<u8>>;
}

#[derive(Copy, Clone, Debug, Eq, PartialEq, ValueEnum)]
#[allow(unused)]
pub enum BackendType {
    #[cfg(windows)]
    Pktmon,
    Pcap,
}

#[cfg(windows)]
pub const DEFAULT_CAPTURE_BACKEND_TYPE: BackendType = BackendType::Pktmon;
#[cfg(not(windows))]
pub const DEFAULT_CAPTURE_BACKEND_TYPE: BackendType = BackendType::Pcap;

pub fn create_capture(backend: BackendType) -> Result<Box<dyn CaptureBackend>> {
    match backend {
        BackendType::Pktmon => {
            if cfg!(windows) {
                Ok(Box::new(pktmon_backend::PktmonBackend::new()?))
            } else {
                Err(CaptureError::Capture {
                    has_captured: false,
                    error: anyhow::anyhow!("Pktmon capture not supported on this operating system"),
                })
            }
        }
        BackendType::Pcap => Ok(Box::new(pcap_backend::PcapBackend::new()?)),
    }
}
