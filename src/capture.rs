use std::error::Error;
use std::fmt::{Debug, Display};

use futures::StreamExt;
use futures::stream::FusedStream;
use pktmon::Capture;
pub use pktmon::Packet;
use pktmon::filter::{PktMonFilter, TransportProtocol};

pub const PORT_RANGE: (u16, u16) = (22101, 22102);

#[derive(Debug)]
#[allow(dead_code)]
pub enum CaptureError {
    Filter(Box<dyn Error>),
    Capture {
        has_captured: bool,
        error: Box<dyn Error>,
    },
    CaptureClosed,
    ChannelClosed,
}

impl Display for CaptureError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        if matches!(self, CaptureError::ChannelClosed) {
            write!(f, "Channel closed")
        } else {
            write!(f, "{:?}", self.source())
        }
    }
}

impl Error for CaptureError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            CaptureError::Filter(e) => Some(e.as_ref()),
            _ => None,
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
            error: Box::new(e),
        })?;

        let filter = PktMonFilter {
            name: "UDP Filter".to_string(),
            transport_protocol: Some(TransportProtocol::UDP),
            port: PORT_RANGE.0.into(),
            ..PktMonFilter::default()
        };

        capture
            .add_filter(filter)
            .map_err(|e| CaptureError::Filter(Box::new(e)))?;

        let filter = PktMonFilter {
            name: "UDP Filter".to_string(),
            transport_protocol: Some(TransportProtocol::UDP),
            port: PORT_RANGE.1.into(),
            ..PktMonFilter::default()
        };

        capture
            .add_filter(filter)
            .map_err(|e| CaptureError::Filter(Box::new(e)))?;

        Ok(Self {
            stream: Box::new(capture.stream().unwrap().boxed().fuse()),
        })
    }

    pub async fn next_packet(&mut self) -> Result<Packet> {
        futures::select! {
            packet = self.stream.select_next_some() => {
                Ok(packet)
            },
            complete => Err(CaptureError::CaptureClosed),
        }
    }
}
