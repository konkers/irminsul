use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::FusedStream;
use pktmon::filter::{PktMonFilter, TransportProtocol};
use pktmon::{Capture, Packet};

use crate::capture::{CaptureBackend, CaptureError, PORT_RANGE, Result};

pub struct PktmonBackend {
    stream: Box<dyn FusedStream<Item = Packet> + Unpin + Send>,
}

impl PktmonBackend {
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
}

#[async_trait]
impl CaptureBackend for PktmonBackend {
    async fn next_packet(&mut self) -> Result<Vec<u8>> {
        futures::select! {
            packet = self.stream.select_next_some() => {
                Ok(packet.payload.to_vec().clone())
            },
            complete => Err(CaptureError::CaptureClosed),
        }
    }
}
