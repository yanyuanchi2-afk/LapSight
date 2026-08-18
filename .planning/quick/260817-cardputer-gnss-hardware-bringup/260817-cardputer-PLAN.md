# Cardputer ADV GNSS hardware bring-up

## Goal

Deliver the first observable Cardputer ADV hardware slice for LapSight: read
NMEA 0183 from the Cap LoRa-1262 GNSS UART, show fix/speed diagnostics on the
Cardputer display, and forward supported NMEA sentences over the same Nordic
UART BLE service already expected by the app.

## Scope

1. Add a reproducible PlatformIO firmware project for Cardputer ADV.
2. Use the official Cap wiring: GPIO 15 as GPS RX, GPIO 13 as GPS TX, UART
   115200 8N1.
3. Advertise as `LapSight-Cardputer` with Nordic UART Service UUIDs.
4. Stream RMC/GGA/GNS/VTG/GST/PQTMEPE NMEA lines without altering their bytes.
5. Display GNSS fix, satellite count, coordinates, GPS speed, BLE state, and
   received-line diagnostics locally.
6. Compile, flash, and inspect serial output on the physical device.
7. Follow with an iOS CoreBluetooth client so the same stream can drive the
   existing shared NMEA parser on iPhone.

## Explicit non-goals for this slice

- Do not initialize or transmit with the SX1262 LoRa radio.
- Do not perform lap timing on Cardputer yet.
- Do not configure GNSS update rate until the factory UART stream is validated.
- Do not claim track accuracy from an indoor or stationary test.

## Acceptance

- PlatformIO build succeeds for ESP32-S3.
- Upload succeeds through the Cardputer USB JTAG/serial port.
- Serial log reports firmware startup and incoming valid NMEA lines.
- Cardputer display distinguishes searching/fixed and BLE waiting/connected.
- BLE advertising uses the app-compatible device name and NUS UUIDs.

## Final integration

The standalone bring-up firmware proved the hardware path. The user-facing
implementation now lives in `firmware/cardputer-adv-userdemo-lapsight`, based
on the pinned official Cardputer ADV UserDemo. It keeps every stock launcher
app and adds LapSight as a separate GNSS-only BLE bridge.
