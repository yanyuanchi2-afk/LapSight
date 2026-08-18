/*
 * SPDX-FileCopyrightText: 2026 LapSight contributors
 *
 * SPDX-License-Identifier: MIT
 */
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

class LapSightBleNus {
public:
    bool start();
    bool notify(const uint8_t* data, std::size_t length);

    bool isStarted() const;
    bool isConnected() const;
    bool isSubscribed() const;
    uint32_t notificationCount() const;
    uint32_t droppedNotificationCount() const;

private:
    static constexpr std::size_t kSafeNotificationBytes = 20;
};
