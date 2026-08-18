/*
 * SPDX-FileCopyrightText: 2026 LapSight contributors
 *
 * SPDX-License-Identifier: MIT
 */
#include "app_lapsight.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

#include "assets/lapsight_big.h"
#include "assets/lapsight_small.h"
#include <apps/utils/audio/audio.h>
#include <apps/utils/common.h>
#include <apps/utils/theme.h>
#include <esp_system.h>
#include <hal.h>
#include <mooncake_log.h>

namespace {

constexpr char kFirmwareVersion[] = "0.2.2-userdemo";
constexpr double kStationaryDisplayThresholdKmph = 3.0;

bool endsWithSentenceType(const char* line, const char* type) {
    const char* comma = std::strchr(line, ',');
    if (!comma || comma - line < 4) {
        return false;
    }
    return std::strncmp(comma - 3, type, 3) == 0;
}

bool shouldForwardNmea(const char* line) {
    if (std::strncmp(line, "$PQTMEPE,", 9) == 0) {
        return true;
    }
    return endsWithSentenceType(line, "RMC") ||
           endsWithSentenceType(line, "GGA") ||
           endsWithSentenceType(line, "GNS") ||
           endsWithSentenceType(line, "VTG") ||
           endsWithSentenceType(line, "GST");
}

}  // namespace

AppLapSight::AppLapSight() {
    setAppInfo().name = "LapSight";
    setAppInfo().userData = new AppIcon_t(image_data_lapsight_big, image_data_lapsight_small);
}

AppLapSight::~AppLapSight() {
    delete static_cast<AppIcon_t*>(getAppInfo().userData);
}

void AppLapSight::onOpen() {
    mclog::tagInfo(getAppInfo().name, "on open");

    _nmea_line_length = 0;
    _collecting_nmea = false;
    _valid_nmea_lines.store(0);
    _forwarded_nmea_lines.store(0);
    _dropped_nmea_lines.store(0);
    _last_display_millis = 0;
    _last_diagnostic_millis = 0;

    GetHAL().canvas.setBaseColor(THEME_COLOR_BG);
    GetHAL().canvas.setFont(FONT_REPL);
    GetHAL().canvas.setTextSize(1);
    GetHAL().canvas.fillScreen(THEME_COLOR_BG);
    GetHAL().canvas.setCursor(4, 4);
    GetHAL().canvas.setTextColor(TFT_ORANGE, THEME_COLOR_BG);
    GetHAL().canvas.print("Starting LapSight GNSS...");
    GetHAL().pushCanvas();

    _gps_nmea_slot_id = GetHAL().capLora868.onGpsNmea.connect(
        [this](const std::string& chunk) { handleGpsChunk(chunk); }
    );
    _gps_ready = GetHAL().capLora868.initGpsOnly();
    _ble_ready = _ble.start();

    mclog::tagInfo(
        getAppInfo().name,
        "started gps={} ble={} version={}",
        _gps_ready,
        _ble_ready,
        kFirmwareVersion
    );
    renderStatus();
}

void AppLapSight::onRunning() {
    const uint32_t now = GetHAL().millis();
    if (now - _last_display_millis >= kDisplayRefreshMillis) {
        _last_display_millis = now;
        renderStatus();
    }
    if (now - _last_diagnostic_millis >= kDiagnosticLogMillis) {
        _last_diagnostic_millis = now;
        logGpsDiagnostics();
    }

    if (GetHAL().homeButton.wasClicked()) {
        audio::play_random_tone();
        close();
    }
}

void AppLapSight::onClose() {
    mclog::tagInfo(getAppInfo().name, "on close");

    if (_gps_nmea_slot_id >= 0) {
        GetHAL().capLora868.onGpsNmea.disconnect(_gps_nmea_slot_id);
        _gps_nmea_slot_id = -1;
    }

    // NimBLE owns controller memory and callbacks after startup. The stock BLE
    // keyboard app uses the same restart-on-exit policy; restarting is the only
    // deterministic way to return every BLE resource to the factory menu.
    esp_restart();
    __builtin_unreachable();
}

void AppLapSight::handleGpsChunk(const std::string& chunk) {
    for (const char byte : chunk) {
        acceptGpsByte(byte);
    }
}

void AppLapSight::acceptGpsByte(char byte) {
    if (byte == '$') {
        _collecting_nmea = true;
        _nmea_line_length = 0;
    }
    if (!_collecting_nmea) {
        return;
    }

    if (_nmea_line_length >= _nmea_line.size() - 1) {
        _collecting_nmea = false;
        _nmea_line_length = 0;
        _dropped_nmea_lines.fetch_add(1);
        return;
    }

    _nmea_line[_nmea_line_length++] = byte;
    if (byte == '\n') {
        forwardCompletedNmeaLine();
        _collecting_nmea = false;
        _nmea_line_length = 0;
    }
}

void AppLapSight::forwardCompletedNmeaLine() {
    if (_nmea_line_length < 7 || _nmea_line[0] != '$') {
        return;
    }

    _nmea_line[_nmea_line_length] = '\0';
    _valid_nmea_lines.fetch_add(1);
    if (!shouldForwardNmea(_nmea_line.data())) {
        return;
    }

    if (_ble.notify(
            reinterpret_cast<const uint8_t*>(_nmea_line.data()),
            _nmea_line_length
        )) {
        _forwarded_nmea_lines.fetch_add(1);
    }
}

void AppLapSight::renderStatus() {
    auto& canvas = GetHAL().canvas;
    canvas.fillScreen(THEME_COLOR_BG);
    canvas.setFont(FONT_REPL);
    canvas.setTextDatum(textdatum_t::top_left);
    canvas.setTextSize(1);

    const bool ble_linked = _ble.isConnected();
    const bool ble_streaming = _ble.isSubscribed();
    canvas.setCursor(6, 8);
    canvas.setTextColor(
        ble_streaming ? TFT_GREEN : (ble_linked ? TFT_YELLOW : TFT_ORANGE),
        THEME_COLOR_BG
    );
    canvas.printf(
        "BLE:%s",
        !_ble_ready ? "ERROR" : (ble_streaming ? "CONNECTED" : (ble_linked ? "LINKED" : "WAITING"))
    );

    TinyGPSPlus* gps = GetHAL().capLora868.borrowGPS();
    if (!gps) {
        canvas.setCursor(6, 30);
        canvas.setTextColor(TFT_RED, THEME_COLOR_BG);
        canvas.print(_gps_ready ? "GPS:UNAVAILABLE" : "GPS:ERROR");
        GetHAL().pushCanvas();
        return;
    }

    const bool fixed = gps->location.isValid() && gps->location.age() < 3000;
    canvas.setCursor(6, 30);
    canvas.setTextColor(fixed ? TFT_GREEN : TFT_ORANGE, THEME_COLOR_BG);
    canvas.printf("GPS:%s", fixed ? "FIX" : "SEARCH");

    char speed_text[24];
    if (gps->speed.isValid() && gps->speed.age() < 3000) {
        const double raw_speed_kmph = gps->speed.kmph();
        const double display_speed_kmph = raw_speed_kmph < kStationaryDisplayThresholdKmph
            ? 0.0
            : raw_speed_kmph;
        std::snprintf(speed_text, sizeof(speed_text), "%.1f km/h", display_speed_kmph);
    } else {
        std::snprintf(speed_text, sizeof(speed_text), "--.- km/h");
    }

    canvas.setTextSize(2);
    canvas.setTextDatum(textdatum_t::middle_center);
    canvas.setTextColor(TFT_WHITE, THEME_COLOR_BG);
    canvas.drawString(speed_text, canvas.width() / 2, 76);

    GetHAL().capLora868.returnGPS();

    GetHAL().pushCanvas();
}

void AppLapSight::logGpsDiagnostics() {
    TinyGPSPlus* gps = GetHAL().capLora868.borrowGPS();
    if (!gps) {
        return;
    }

    const uint32_t chars = gps->charsProcessed();
    const uint32_t checksum_ok = gps->passedChecksum();
    const uint32_t checksum_bad = gps->failedChecksum();
    const uint32_t fixed_sentences = gps->sentencesWithFix();
    const bool location_valid = gps->location.isValid();
    const uint32_t location_age = gps->location.age();
    const uint32_t satellites = gps->satellites.isValid()
        ? gps->satellites.value()
        : 0;
    GetHAL().capLora868.returnGPS();

    // Avoid logging raw NMEA because it contains the user's coordinates.
    mclog::tagInfo(
        getAppInfo().name,
        "gps chars={} checksum_ok={} checksum_bad={} fixed_sentences={} location_valid={} age_ms={} sats={}",
        chars,
        checksum_ok,
        checksum_bad,
        fixed_sentences,
        location_valid,
        location_age,
        satellites
    );
}
