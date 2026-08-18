/*
 * SPDX-FileCopyrightText: 2026 LapSight contributors
 *
 * SPDX-License-Identifier: MIT
 */
#pragma once

#include "lapsight_ble_nus.h"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mooncake.h>
#include <string>

class AppLapSight : public mooncake::AppAbility {
public:
    AppLapSight();
    ~AppLapSight();

    void onOpen() override;
    void onRunning() override;
    void onClose() override;

private:
    static constexpr std::size_t kMaxNmeaLineBytes = 192;
    static constexpr uint32_t kDisplayRefreshMillis = 250;

    LapSightBleNus _ble;
    std::array<char, kMaxNmeaLineBytes> _nmea_line{};
    std::size_t _nmea_line_length = 0;
    bool _collecting_nmea = false;
    bool _gps_ready = false;
    bool _ble_ready = false;
    int _gps_nmea_slot_id = -1;
    uint32_t _last_display_millis = 0;
    std::atomic<uint32_t> _valid_nmea_lines{0};
    std::atomic<uint32_t> _forwarded_nmea_lines{0};
    std::atomic<uint32_t> _dropped_nmea_lines{0};

    void handleGpsChunk(const std::string& chunk);
    void acceptGpsByte(char byte);
    void forwardCompletedNmeaLine();
    void renderStatus();
};
