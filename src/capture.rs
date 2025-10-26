use std::fmt::{Debug, Display};

use anyhow::Error;
use futures::StreamExt;
use futures::stream::FusedStream;
use pktmon::filter::{PktMonFilter, TransportProtocol};
use pktmon::{Capture, Packet};

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

pub struct PacketCapture {
    stream: Box<dyn FusedStream<Item = Packet> + Unpin + Send>,
}

impl PacketCapture {
    pub fn new() -> Result<Self> {
        let mut capture = Capture::new().map_err(|e| CaptureError::Capture {
            has_captured: false,
            error: e.into(),
        })?;

        let filter = PktMonFilter {
            name: "UDP Filter".to_string(),
            transport_protocol: Some(TransportProtocol::UDP),
            port: PORT_RANGE.0.into(),
            ..PktMonFilter::default()
        };

        capture
            .add_filter(filter)
            .map_err(|e| CaptureError::Filter(e.into()))?;

        let filter = PktMonFilter {
            name: "UDP Filter".to_string(),
            transport_protocol: Some(TransportProtocol::UDP),
            port: PORT_RANGE.1.into(),
            ..PktMonFilter::default()
        };

        capture
            .add_filter(filter)
            .map_err(|e| CaptureError::Filter(e.into()))?;

        Ok(Self {
            stream: Box::new(capture.stream().unwrap().boxed().fuse()),
        })
    }

    pub async fn next_packet(&mut self) -> Result<Vec<u8>> {
        futures::select! {
            packet = self.stream.select_next_some() => {
                Ok(packet.payload.to_vec().clone())
            },
            complete => Err(CaptureError::CaptureClosed),
        }
    }
}
