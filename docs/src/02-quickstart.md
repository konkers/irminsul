# Quickstart

## Download Irminsul

The latest Irminsul release can always be found on the [Irminsul GitHub Released Page](https://github.com/konkers/irminsul/releases).

- **Windows:** download `irminsul.exe`
- **Linux:** download `irminsul`

Do not download either of the "Source code" archives.

## Launch Irminsul

Irminsul needs to be running and capturing packets before you enter the door into the main game. The simplest way to accomplish this is to launch Irminsul before launching Genshin.

### Windows

Irminsul needs admin privileges to observe Genshin's network traffic and won't work without it. Accept the UAC prompt when launching.

### Linux

Irminsul requires the `cap_net_raw` capability to capture packets. Run this once after downloading:

```sh
sudo setcap cap_net_raw+ep ./irminsul
```

Then launch Irminsul normally (no `sudo` needed):

```sh
./irminsul
```

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
