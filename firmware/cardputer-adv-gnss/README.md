# LapSight Cardputer ADV GNSS bridge

First hardware-validation firmware for Cardputer ADV with the M5Stack Cap
LoRa-1262/ATGM336H. It leaves LoRa disabled, reads the GNSS UART, shows local
diagnostics, and exposes selected NMEA 0183 sentences over BLE Nordic UART.

The Cardputer display applies a presentation-only stationary deadband: raw
speeds below 3 km/h are shown as 0.0 km/h. BLE continues forwarding the
original NMEA bytes unchanged so recording and later analysis never receive
clamped data.

## Hardware

- Cardputer ADV (ESP32-S3)
- Cap LoRa-1262 with ATGM336H-6N GNSS
- Cap installed on the EXT 2.54-14P connector
- USB-C data cable

The Cap's GNSS antenna is internal. The external rubber antenna is for LoRa;
this firmware never initializes the SX1262.

## Protocol

- BLE name: `LapSight-Cardputer`
- Nordic UART service: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- Phone writes: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- Cardputer notifies: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`
- GNSS UART: RX GPIO 15, TX GPIO 13, `115200 8N1`
- Forwarded NMEA: RMC, GGA, GNS, VTG, GST, PQTMEPE

NMEA lines are fragmented into 20-byte BLE notifications. LapSight's shared
parser accepts fragmented streams and reassembles complete lines.

## Build and upload

Run from the repository root with the project's local PlatformIO installation:

```sh
PLATFORMIO_CORE_DIR=.toolchains/platformio-core \
  .toolchains/platformio/bin/pio run \
  --project-dir firmware/cardputer-adv-gnss

PLATFORMIO_CORE_DIR=.toolchains/platformio-core \
  .toolchains/platformio/bin/pio run \
  --project-dir firmware/cardputer-adv-gnss \
  --target upload --upload-port /dev/cu.usbmodem101
```

Enter download mode before upload: switch the Cardputer OFF, hold G0, connect
USB-C, then release G0. After upload, switch ON or press reset to run.

## Validation notes

An indoor unit may receive NMEA but remain in `GPS: SEARCH`. Take it outdoors
with the Cap's ceramic antenna facing the sky for a meaningful first fix. Do
not use this prototype for public-road racing or glance at the display while
driving.
