![Screenshot](docs/src/images/main-window.webp)

# Resources

- [Docs](https://konkers.github.io/irminsul)
- [Discord](https://discord.gg/2BkvBKbt)

# Introduction

Irminsul is a utility to extract data from Genshin Impact and export it for use with [Genshin Optimizer](https://frzyc.github.io/genshin-optimizer/) and web sites, applications, and utilities that use the [GOOD](https://frzyc.github.io/genshin-optimizer/#/doc) data format.

Irminsul utilizes packet capture instead of the common optical character recognition (OCR) that other [scanners](https://frzyc.github.io/genshin-optimizer/#/scanner) use. This allows it to be much quicker in exchange for 1. needing to run with admin privaleges (for the packet capture) and 2. needing to be run when genshin starts to observe the handshake with the server.

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
