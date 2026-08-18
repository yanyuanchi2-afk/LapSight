# LapSight for Cardputer ADV UserDemo

This is a clean integration of LapSight's GNSS-to-iPhone bridge into the
factory-style Cardputer ADV UserDemo. The stock launcher and bundled apps stay
available; `LapSight` appears as an additional launcher app.

## Hardware

- M5Stack Cardputer ADV (ESP32-S3)
- M5Stack Cap LoRa-1262 with ATGM336H GNSS, installed on the EXT 2.54-14P connector
- USB-C data cable for building and flashing

The Cap's ceramic antenna is used for GNSS. The removable rubber antenna is for
LoRa and is not used by the LapSight app.

## Behaviour

- Opening `LapSight` initializes only the GNSS side of Cap LoRa-1262. LoRa
  transmit is not enabled by this app.
- The app advertises `LapSight-Cardputer` over Bluetooth Low Energy using the
  Nordic UART Service UUIDs expected by the LapSight iOS app.
- Selected NMEA 0183 sentences (`RMC`, `GGA`, `GNS`, `VTG`, `GST`, and
  `PQTMEPE`) are forwarded without changing their values.
- Speeds below 3 km/h are shown as 0 on the Cardputer display only. Raw NMEA
  speed continues to reach the iPhone.
- Pressing the Cardputer home button exits through a controlled restart. This
  matches the stock BLE keyboard app's cleanup policy and returns all BLE
  controller resources before the factory launcher starts again.

## Use with iPhone

1. Start the LapSight iOS app and allow Bluetooth access when prompted.
2. On Cardputer, select `LapSight` in the launcher and press Enter.
3. In LapSight's GPS source controls, select the external GNSS receiver.
4. Wait for the Cardputer screen to show `BLE:CONNECTED`. `GPS:SEARCH` is
   expected indoors; take the unit outdoors with a clear view of the sky for a
   meaningful first fix.
5. Press the Cardputer home button to leave LapSight and return to the stock
   launcher through a controlled restart.

## Upstream

- Repository: <https://github.com/m5stack/M5Cardputer-UserDemo>
- Branch: `CardputerADV`
- Pinned snapshot: `b549eac0a3c65bc108186c276b8fac0a214aaa4e`
- Upstream toolchain: ESP-IDF 5.4.2

The upstream source and LapSight additions are MIT licensed. See `LICENSE`.

## Build

Install and activate ESP-IDF 5.4.2, then run from this directory:

```bash
python3 ./fetch_repos.py
idf.py build
```

## Flash

Connect Cardputer ADV by USB and run:

```bash
idf.py -p /dev/cu.usbmodem101 flash monitor
```

The serial port may have a different number on another Mac. The complete
pre-integration device backup is deliberately stored outside Git under
`/.device-backups/`.

## Restore a full device backup

If a complete 8 MB backup was created before flashing, it can restore the
previous flash contents:

```bash
esptool.py --chip esp32s3 --port /dev/cu.usbmodem101 write_flash 0x0 cardputer-backup.bin
```

Keep device backups private and outside Git. Verify the backup file size and
SHA-256 digest before relying on it.

## Verified configuration

- Firmware integration version: `0.2.2-userdemo`
- ESP-IDF project version: `2.0.0-lapsight.3`
- Hardware: Cardputer ADV, 8 MB flash, Cap LoRa-1262/ATGM336H
- BLE link: iPhone connection, MTU negotiation, notification subscription, and
  sustained fragmented NMEA delivery verified on physical hardware
- GNSS parsing: supports the GPS/GNSS and BeiDou talker IDs emitted by the
  ATGM336H multi-constellation receiver
- LoRa: not initialized by the LapSight app

## Regenerating the launcher icon

```bash
python3 tools/generate_lapsight_icons.py
```

## Upstream acknowledgments

The factory UserDemo references M5GFX, M5Unified, Mooncake, Mooncake Log,
Smooth UI Toolkit, PikaPython, RadioLib, TinyGPSPlus, Adafruit TCA8418, raylib,
and Espressif ESP-IDF components. Git dependencies are pinned to exact commits
in `repos.json`; ESP-IDF-managed dependencies are pinned by `dependencies.lock`.
