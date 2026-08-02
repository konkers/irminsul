# Irminsul

[![Rust CI](https://github.com/konkers/irminsul/actions/workflows/rust.yml/badge.svg)](https://github.com/konkers/irminsul/actions/workflows/rust.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-konkers.github.io-blue)](https://konkers.github.io/irminsul)
[![Discord](https://img.shields.io/badge/discord-join-5865F2?logo=discord&logoColor=white)](https://discord.gg/aQqdZPHEpP)
[![Issues](https://img.shields.io/github/issues/konkers/irminsul)](https://github.com/konkers/irminsul/issues)

![Screenshot](docs/src/images/main-window.webp)

Irminsul is a desktop utility that extracts data from Genshin Impact — artifacts, weapons, materials, and characters — and exports it in the [GOOD](https://frzyc.github.io/genshin-optimizer/#/doc) format for use with [Genshin Optimizer](https://frzyc.github.io/genshin-optimizer/) and other GOOD-compatible tools.

## Why Irminsul

Most [scanners](https://frzyc.github.io/genshin-optimizer/#/scanner) rely on optical character recognition (OCR) of the game's UI. Irminsul instead sniffs the network handshake between the game client and server, which makes it dramatically faster.

Current capabilities:

- Incredibly fast capture of all Genshin Optimizer supported data:
  - Artifacts, including "unactivated" rolls and initial roll values
  - Weapons
  - Materials
  - Characters
- Simple, clean UI
- Export settings to filter which data gets exported
- Export to the clipboard or to a file

Planned:

- Achievement export
- Wish history export
- Real-time data updates while the game is running

## Getting Started

### Download

Grab the latest release for your platform from the [Releases page](https://github.com/konkers/irminsul/releases):

- **Windows**: `irminsul.exe` (uses [`pktmon`](https://github.com/emmachase/pktmon), no extra drivers needed)
- **Linux**: build from source (see below); requires `libpcap`

### Dependencies

If you're using the `pcap` capture backend, install a pcap library first:

- **Windows**: [Npcap](https://npcap.com/#download) (WinPcap likely works but is untested)
- **Linux**: `libpcap` from your distro's package manager

### Building from source

Irminsul is a Rust project (nightly toolchain, pinned in `rust-toolchain.toml`).

```sh
# Windows (pktmon backend)
cargo build --release

# Linux/macOS (pcap backend)
cargo build --release --features pcap

# Linux: statically link libpcap instead of relying on the system library
cargo build --release --features pcap,static-libpcap
```

The binary is written to `target/release/irminsul` (Linux/macOS) or `target/release/irminsul.exe` (Windows).

### Usage

1. Launch Irminsul **before** starting Genshin Impact.
   - Windows: run `target\release\irminsul.exe`; it will prompt for admin elevation automatically (UAC).
   - Linux/macOS: run `sudo ./target/release/irminsul` (root is required for packet capture); if launched without root, Irminsul shows a dialog with the exact `sudo <path>` command to use.
2. Click the play button in the "Packet Capture" section to start capturing.
3. Launch Genshin Impact and enter through the loading-screen "door" so Irminsul can observe the handshake.
4. Watch for green checkmarks as artifacts, weapons, materials, and characters are captured.
5. Export your data via the clipboard icon or the save-to-file icon. Use the settings icon to control which data categories and thresholds get exported.

Command line options:

- `--capture-backend <pktmon|pcap>` (`-b`): selects the capture backend. Both `pktmon` (default) and `pcap` are available on Windows; other platforms support `pcap` only.
- `--no-admin`: skips the automatic elevation prompt.

## Maintainers & Contributing

Irminsul is created and maintained by [Erik Gilling (Konkers)](https://github.com/konkers). Issues and pull requests are welcome on [GitHub](https://github.com/konkers/irminsul).

Distributed under the [MIT License](LICENSE).

### Thanks

Irminsul is built upon the work of many others:

- [PJK136](https://github.com/PJK136), whose work on a [fork of `stardb-exporter`](https://github.com/PJK136/stardb-exporter) provided the main inspiration for Irminsul's development.
- [juliuskreutz](https://github.com/juliuskreutz), whose [`stardb-exporter`](https://github.com/juliuskreutz/stardb-exporter) provided the foundation for PJK136's work as well as examples for wrangling [`egui`](https://github.com/emilk/egui).
- [hashblen](https://github.com/hashblen), whose [`auto-artifactarium`](https://github.com/hashblen/auto-artifactarium) is used to interpret network packets from Genshin.
- [IceDynamix](https://github.com/IceDynamix/), whose work on Honkai Star Rail network scanning is at the root of many Genshin and HSR network scanning utilities.
- [emmachase](https://github.com/emmachase), who wrote the packet capture library [`pktmon`](https://github.com/emmachase/pktmon) that Irminsul uses to capture packets without installing an npcap driver, as well as contributions to some of the above projects.
- [Genshin Optimizer](https://frzyc.github.io/genshin-optimizer/), without which there would be no point in exporting data.
- [Inventory Kamera](https://github.com/Andrewthe13th/Inventory_Kamera), the original introduction to artifact and character scanning, whose Discord provided the collaboration environment that spawned Irminsul.
