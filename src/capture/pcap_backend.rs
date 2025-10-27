use anyhow::anyhow;
use async_trait::async_trait;
use pcap::{Active, Capture, Device};
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};

use crate::capture::{CaptureBackend, CaptureError, PORT_RANGE, Result};

pub struct PcapBackend {
    packet_rx: UnboundedReceiver<Result<Vec<u8>>>,
}

impl PcapBackend {
    pub fn new() -> Result<Self> {
        let device = match Device::lookup() {
            Ok(Some(device)) => device,
            Ok(None) | Err(_) => Device::list()
                .map_err(|e| CaptureError::Capture {
                    has_captured: false,
                    error: e.into(),
                })?
                .into_iter()
                .next()
                .ok_or_else(|| CaptureError::Capture {
                    has_captured: false,
                    error: anyhow!("No capture device available"),
                })?,
        };
        tracing::info!("Capturing from device {}", device.name);

        let mut capture = Capture::from_device(device)
            .map_err(|e| CaptureError::Capture {
                has_captured: false,
                error: e.into(),
            })?
            .immediate_mode(true)
            .open()
            .map_err(|e| CaptureError::Capture {
                has_captured: false,
                error: e.into(),
            })?;

        let filter_expression = format!("udp and portrange {}-{}", PORT_RANGE.0, PORT_RANGE.1);
        capture
            .filter(&filter_expression, true)
            .map_err(|e| CaptureError::Filter(e.into()))?;

        let (packet_tx, packet_rx) = mpsc::unbounded_channel();
        std::thread::spawn(move || packet_loop(capture, packet_tx));

        Ok(Self { packet_rx })
    }
}

fn packet_loop(mut capture: Capture<Active>, packet_tx: UnboundedSender<Result<Vec<u8>>>) {
    let mut has_captured = false;
    loop {
        match capture.next_packet() {
            Ok(packet) => {
                has_captured = true;
                if packet_tx.send(Ok(packet.data.to_vec())).is_err() {
                    // If the `PcapBackend` is dropped, the receiver side will be dropped, and
                    // `send` will return an error.  This is a signal to terminate this thread.
                    break;
                }
            }
            Err(err) => {
                let _ = packet_tx.send(Err(CaptureError::Capture {
                    has_captured,
                    error: err.into(),
                }));
                break;
            }
        }
    }
}

#[async_trait]
impl CaptureBackend for PcapBackend {
    async fn next_packet(&mut self) -> Result<Vec<u8>> {
        match self.packet_rx.recv().await {
            Some(Ok(packet)) => Ok(packet),
            Some(Err(err)) => Err(err),
            None => Err(CaptureError::CaptureClosed),
        }
    }
}
