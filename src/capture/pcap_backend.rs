use anyhow::anyhow;
use async_trait::async_trait;
use pcap::{Active, Capture, ConnectionStatus, Device};
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};

use crate::capture::{CaptureBackend, CaptureError, PORT_RANGE, Result};

pub struct PcapBackend {
    packet_rx: UnboundedReceiver<Result<Vec<u8>>>,
}

impl PcapBackend {
    fn get_device_identifier(device: &Device) -> String {
        format!(
            "{} (desc {})",
            device.name,
            device.desc.as_deref().unwrap_or("None")
        )
    }

    fn should_capture_on_device(device: &Device) -> bool {
        device.flags.connection_status == ConnectionStatus::Connected
    }

    pub fn new() -> Result<Self> {
        // 1. Find all devices
        let devices = Device::list().map_err(|e| CaptureError::Capture {
            has_captured: false,
            error: e.into(),
        })?;

        tracing::info!("Found {} available devices", devices.len());
        for (i, device) in devices.iter().enumerate() {
            tracing::info!(
                "Available device {}/{}: {}, details: {:?}",
                i + 1,
                devices.len(),
                PcapBackend::get_device_identifier(device),
                device
            );
        }

        // 2. Try to set up capture on all of them (we expect some of them to fail)
        let mut successful_captures = Vec::new();
        let filter_expression = format!("udp and portrange {}-{}", PORT_RANGE.0, PORT_RANGE.1);

        for device in devices {
            if !Self::should_capture_on_device(&device) {
                tracing::info!(
                    "Excluded device {} from capture",
                    PcapBackend::get_device_identifier(&device)
                );
                continue;
            }

            match Self::setup_device_capture(device, &filter_expression) {
                Ok(capture) => {
                    successful_captures.push(capture);
                }
                Err(_) => {
                    // Ignore; we probably shouldn't have captured on that device anyways
                }
            }
        }

        // 3. Handle capture results
        if successful_captures.is_empty() {
            return Err(CaptureError::Capture {
                has_captured: false,
                error: anyhow!("No capture device available"),
            });
        }

        tracing::info!("Capturing on {} devices:", successful_captures.len());
        for (i, (device_identifier, _)) in successful_captures.iter().enumerate() {
            tracing::info!(
                "Capture device {}/{}: {}",
                i + 1,
                successful_captures.len(),
                device_identifier
            );
        }

        // 4. Set up packet loops for each successful capture
        let (packet_tx, packet_rx) = mpsc::unbounded_channel();

        for (device_identifier, capture) in successful_captures {
            let packet_tx = packet_tx.clone();
            std::thread::spawn(move || Self::packet_loop(capture, packet_tx, device_identifier));
        }

        Ok(Self { packet_rx })
    }

    fn setup_device_capture(
        device: Device,
        filter_expression: &str,
    ) -> Result<(String, Capture<Active>)> {
        let device_identifier = Self::get_device_identifier(&device);

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

        capture
            .filter(filter_expression, true)
            .map_err(|e| CaptureError::Filter(e.into()))?;

        Ok((device_identifier, capture))
    }

    fn packet_loop(
        mut capture: Capture<Active>,
        packet_tx: UnboundedSender<Result<Vec<u8>>>,
        device_identifier: String,
    ) {
        let mut has_captured = false;
        loop {
            match capture.next_packet() {
                Ok(packet) => {
                    has_captured = true;
                    if packet_tx.send(Ok(packet.data.to_vec())).is_err() {
                        // If the `PcapBackend` is dropped, the receiver side will be dropped, and
                        // `send` will return an error.  This is a signal to terminate this thread.
                        tracing::info!(
                            "Packet loop for device {} ending (has_captured: {}): channel closed",
                            device_identifier,
                            has_captured
                        );
                        break;
                    }
                }
                Err(err) => {
                    tracing::info!(
                        "Packet loop for device {} ending (has_captured: {}): capture error: {}",
                        device_identifier,
                        has_captured,
                        err
                    );
                    let _ = packet_tx.send(Err(CaptureError::Capture {
                        has_captured,
                        error: err.into(),
                    }));
                    break;
                }
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
