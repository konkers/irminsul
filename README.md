![Screenshot](docs/src/images/main-window.webp)

# Resources

- [Docs](https://konkers.github.io/irminsul)
- [Discord](https://discord.gg/aQqdZPHEpP)

# Introduction

Irminsul is a utility to extract data from Genshin Impact and export it for use with [Genshin Optimizer](https://frzyc.github.io/genshin-optimizer/) and web sites, applications, and utilities that use the [GOOD](https://frzyc.github.io/genshin-optimizer/#/doc) data format.

Irminsul utilizes packet capture instead of the common optical character recognition (OCR) that other [scanners](https://frzyc.github.io/genshin-optimizer/#/scanner) use. This allows it to be much quicker in exchange for 1. needing to run with admin privaleges (for the packet capture) and 2. needing to be run when genshin starts to observe the handshake with the server.

## Command line options

Irminsul accepts a handful of command line options for advanced use cases:

- `--capture-backend <pktmon|pcap>` (shorthand `-b`): chooses which capture backend to use. On Windows both `pktmon` (default) and `pcap` are available. On other platforms only `pcap` is available.
- `--no-admin`: skips the automatic elevation prompt. This can be useful when you prefer to launch the application without requesting higher privileges up front.

## Features

In it's current state Irminsul supports:

- Incredibly fast capture of all Genshin Optimizer supported data
  - Artifacts including "unactivated" rolls and reporting of initial values for rolls
  - Weapons
  - Materials
  - Characters
- Simple, clean UI
- Export settings to filter which data gets exported
- Exports data either to the clipboard or saved to a file

Planned features include:

- Achievement export
- Wish history export
- Real time data updates while game is running

## Thanks

Irmunsil is built upon the work of many others.

- [PJK136](https://github.com/PJK136) whose work on a [fork of `stardb-exporter`](https://github.com/PJK136/stardb-exporter) provided the main inspiration for Irminsul's development.
- [juliuskreutz](https://github.com/juliuskreutz) whose [`stardb-exporter`](https://github.com/juliuskreutz/stardb-exporter) provided the foundation for PJK136's work as well as providing some examples for how to wrangle [`egui`](https://github.com/emilk/egui).
- [hashblen](https://github.com/hashblen) whose [`auto-artifactarioum`](https://github.com/hashblen/auto-artifactarium) is used to interpret the network packets from Genshin.
- [IceDynamix](https://github.com/IceDynamix/) whose work on Honkai Star Rail network scanning is at the root of many of the Genshin and HSR network scanning utilities.
- [emmachase](https://github.com/emmachase) who wrote the packet capture library [`pktmon`](https://github.com/emmachase/pktmon) which Irminsul uses to allow packet capture without having to install a npcap driver as well as their contributions to some of the above projects.
- [Genshin Optimizer](https://frzyc.github.io/genshin-optimizer/) without which there would be no point in exporting data.
- [Inventory Kamera](https://github.com/Andrewthe13th/Inventory_Kamera) which was my introduction into artifact and character scanning and whose discord provided a collaboration environment that spawned Irminsul.
