# Quickstart

## Download Irminsul

The latest Irminsul release can always be found on the [Irminsul GitHub Released Page](https://github.com/konkers/irminsul/releases). Grab the file for your platform, not either of the "Source code" archives:

- Windows: `irminsul-windows-x64.exe`
- Linux: `irminsul-linux-x64`

Downloads don't keep their executable bit, so on Linux mark the file executable before running it:

```
chmod +x irminsul-linux-x64
```

## Install Pcap library (optional)

If you plan on using the `pcap` capture backend:

- On Windows: Install Npcap (https://npcap.com/#download). The older WinPcap should work too, but we didn't test it.
- On Linux: Nothing to install. The released binary has libpcap linked into it. You only need libpcap from your distro's package manager if you build Irminsul yourself.

## Launch Irminsul and grant it packet capture privileges

Irminsul needs to be running and capturing packets before you enter the door into the main game. The simplest way to accomplish this is to launch Irminsul before launching Genshin

Irminsul needs admin/root privaleges to observe Genshin's network traffic and won't work without it.

On Windows, accept the admin prompt that appears when Irminsul starts.

On Linux, you can either grant Irminsul permission to capture packets:

```
sudo setcap cap_net_raw=ep ./irminsul-linux-x64
```

or run it as root every time:

```
sudo ./irminsul-linux-x64
```

`setcap` grants the permission to that particular copy of the file, so it has to be re-run after every update. Irminsul tells you when this is needed and shows you the command to use.

## Start packet capture

Click on the play button in the "Packet Capture" section. This will start Irminsul capturing packets.

![Start Capture](images/start-capture.webp)

## Start Genshin and enter the door

Once packet capture is running, enter the door in Genshin

![Door](images/door.webp)

Once Irminsul detects the various data it needs, you'll green checkmarks appear in the "Packet Capture" section.

![Checkmarks](images/checkmark.webp)

## Export data

![Genshin Optimizer Export](images/export.webp)

Once the data has been captured, you can export:

- To the clipboard by clicking ont the clipboard with the arrow icon.
- To a file by clicking on the download icon.

Which data gets exported can be controlled by clicking on the settings icon.

## Command line options

Irminsul also supports a couple of command line flags when launching from a terminal:

- `--capture-backend <pktmon|pcap>` (or `-b`): on Windows you can choose between the `pktmon` backend (default) and the cross-platform `pcap` backend. On other platforms only `pcap` is available.
- `--no-admin`: skip the automatic elevation prompt if you prefer to launch without requesting admin/root rights.
