# Phase 6: External GNSS Hardware-Risk Register

**Status:** Open — no real receiver hardware has been acquired or tested.
**Purpose:** Track exactly what real-hardware behavior remains unvalidated
after Phase 6's protocol-first implementation, so this list cannot be
mistaken for "done" and can drive a future validation pass or be resolved by
user field feedback (`docs/EXTERNAL-GNSS.md`, Section 5).

This register does not block Phase 6 closeout. Per `.planning/phases/06-external-gnss-and-sensor-ingestion/06-CONTEXT.md`
decision D-01/D-02, Phase 6 completion is explicitly defined as "protocol
compatibility preview" — parsers, replay, Android UX, and provenance
implemented and tested, with hardware validation deliberately deferred.

---

## 1. Why no hardware was used

The product owner decided not to purchase a RaceBox or other external GNSS
receiver for this phase (hardware cost review, 2026-07-07). Every automated
test in Phase 6 (06-01 through 06-04) runs against replay fixtures: public
NMEA logs, generated NMEA sentences, and a synthetic RaceBox frame builder
authored in this repository from publicly described field semantics — never
against a physical receiver or a real BLE radio link.

## 2. Risk register

| # | Risk | Why it's unvalidated | Impact if wrong | Mitigation today | Future validation check |
|---|------|----------------------|------------------|-------------------|--------------------------|
| R-01 | RaceBox real BLE GATT service/characteristic UUIDs differ from the assumed Nordic UART Service convention | RaceBox's exact UUIDs are only published on request; `AndroidExternalGnssBleClient` defaults to the common convention | Scan/connect never finds or subscribes to the real service; connection state sticks at Scanning/Failed | UUIDs are constructor-overridable; connection state machine surfaces Failed instead of silently hanging | Connect one real RaceBox Mini/Mini S/Micro, capture its advertised GATT services via a BLE scanner tool, and override the client with the confirmed UUIDs if they differ |
| R-02 | RaceBox binary frame layout (field offsets, scaling factors, message class/id values) may not exactly match real firmware output | Parser was implemented clean-room from public protocol-documentation summaries and compatibility-signal projects, not RaceBox's official protocol PDF (only available on request) or a real byte capture | Decoded lat/lon/speed/heading/accuracy could be silently wrong, or valid frames could be rejected as malformed | `RaceBoxFrameParser` treats short/invalid payloads and checksum mismatches as typed `Rejected` results, never a crash; length/checksum are always checked before field decode | Capture a raw byte log from a real RaceBox device (e.g. via the BLE client's notify callback before parsing) and diff the field layout against `RaceBoxFrameParser`/`RaceBoxFrameBuilder`; update the parser and add the captured bytes as a new fixture if any field disagrees |
| R-03 | RaceBox Mini vs. Mini S vs. Micro firmware/protocol differences | Treated as one protocol family per `06-RESEARCH.md` (Mini/Mini S share the same real-time GNSS class; Micro additionally supports NMEA over BLE); no real unit from any of the three has been tested | A device-specific quirk (e.g. Micro's dual protocol support, different update-rate defaults) could behave differently than modeled | Update-rate and dual-frequency flags are already decoded per-frame, not assumed constant | Test at least one unit from each of Mini, Mini S, and Micro if/when available; record any per-model deviation as a new decision, not a silent code branch inside `LapEngine` |
| R-04 | Android BLE permission, scan, connect, and reconnect behavior on real devices/OEM skins | `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` runtime permission flow and the Scanning → Connecting → Connected → Reconnecting → Failed state machine are implemented and unit-tested with a fake byte-stream double, never exercised against a real Android BLE stack | Real-device BLE stacks (especially OEM-customized ones) may drop notifications, delay connection callbacks, or require different permission timing than the fake double models | Connection phases are exposed as an explicit `ExternalGnssConnectionState` the UI can show instead of assuming instant success; the provider is BLE-agnostic behind `ExternalGnssByteStreamClient` so faulty transport swaps out cleanly | Field-test on at least 2 different Android OEM devices with a real receiver; watch specifically for silent notification drops during a long drive session |
| R-05 | Antenna placement and real-world positional accuracy vs. phone GPS | No physical receiver, so no on-track accuracy comparison exists; accuracy/HDOP fields are decoded and passed through, never independently verified against ground truth | Any claim that external GNSS is "more accurate" than phone GPS would be unsupported | LapSight makes no accuracy-improvement claim anywhere in UI or docs; `docs/EXTERNAL-GNSS.md` explicitly avoids this claim | Run a real receiver and phone GPS simultaneously on a closed course with a known reference (survey point or repeated stationary fix) and compare recorded horizontal accuracy/track precision |
| R-06 | RaceBox basic telemetry field semantics (accel/gyro axis convention, sign, units-in-practice) on real hardware | Basic telemetry decode (acceleration, gyro, vehicle speed) is modeled from the same clean-room field-semantics research as the GNSS fix, with no real IMU capture to confirm axis orientation or sign convention | Telemetry values could be numerically decoded but semantically wrong (e.g., swapped axes) | Telemetry is explicitly out of the lap-timing path (D-09) — a wrong sign/axis cannot affect lap count, lap time, or ghost delta, only a future analysis feature that is not shipped yet | Capture real basic-telemetry frames during a known maneuver (e.g. hard braking) and confirm axis/sign convention before any telemetry-consuming UI is built |
| R-07 | NMEA-over-BLE/TCP transport reliability on a real device (vs. text-mode replay) | The NMEA parser itself is well-covered by fragmented/burst replay tests, but the transport carrying NMEA bytes to the phone (BLE UART-style notify, or TCP for network-attached receivers) has only been exercised with the fake byte-stream double, matching R-04 | A slow or lossy real transport could deliver NMEA sentences split at different byte boundaries than any fixture models, though the parser's line-buffering is designed to be robust to this | `Nmea0183Parser.accept()` buffers across calls and only emits complete sentences (`Nmea0183ParserTest.fragmentedInputMatchesWholeSentenceInput` covers reads split at three different boundaries) | Confirm no real-world fragmentation pattern breaks buffering by field-testing over BLE with a real NMEA-capable receiver |

## 3. What is explicitly NOT a hardware risk

To avoid over-scoping this register, the following are already resolved by
design and are not tracked here as open risk:

- **Lap-engine correctness for external samples** — proven by replay
  (`ExternalGnssTimingPipelineTest`) against the same fixture geometry used
  by phone/simulated timing tests; this is a software correctness concern,
  not a hardware-dependent one, because `LapEngine` never branches on sample
  source (D-04).
- **Session/review/export provenance** — `LocationSource.ExternalGnss` is
  recorded end-to-end and is independent of which physical receiver produced
  the bytes (D-05).
- **GPL/licensing exposure** — the RaceBox parser and frame builder are
  clean-room; no GPL or unlicensed third-party RaceBox code was copied into
  this repository (D-07).

## 4. Future validation checklist

When a real RaceBox Mini/Mini S/Micro or NMEA receiver becomes available
(purchase, loaner, or user-contributed field evidence per `docs/EXTERNAL-GNSS.md`
Section 5), close this register by working through, in order:

1. Capture a raw byte log from the real device's notify characteristic before
   any parsing (proves/disproves R-02 and R-06 field-layout risk directly).
2. Confirm the real device's advertised GATT service/characteristic UUIDs
   against `AndroidExternalGnssBleClient`'s defaults (R-01).
3. Run a full drive session over BLE end-to-end on at least 2 Android devices
   and watch connection-state transitions for unexpected drops (R-04, R-07).
4. Run a real receiver alongside phone GPS on a closed course and compare
   recorded accuracy without making a superiority claim either way (R-05).
5. If any byte-layout or connection assumption is wrong, fix the parser/client
   and add the captured real bytes as a new committed fixture — do not
   hand-tune existing synthetic fixtures to match, since that would hide the
   discrepancy instead of fixing it.
6. Update `ExternalGnssHardwareValidationStatus` from `Unverified` to
   `Verified` only for the specific protocol/receiver combination that was
   actually tested; do not flip it globally from one successful device test.

---

*Phase: 06-external-gnss-and-sensor-ingestion*
*This register is expected to remain open at Phase 6 closeout. It should be
revisited, not deleted, once real hardware or user field evidence exists.*
