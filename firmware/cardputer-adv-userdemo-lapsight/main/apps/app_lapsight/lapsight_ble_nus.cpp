/*
 * SPDX-FileCopyrightText: 2026 LapSight contributors
 *
 * SPDX-License-Identifier: MIT
 */
#include "lapsight_ble_nus.h"

#include <algorithm>
#include <cstring>

#include <esp_bt.h>
#include <esp_err.h>
#include <esp_log.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <host/ble_gap.h>
#include <host/ble_gatt.h>
#include <host/ble_hs.h>
#include <host/ble_hs_mbuf.h>
#include <host/ble_uuid.h>
#include <host/util/util.h>
#include <nimble/ble.h>
#include <nimble/nimble_port.h>
#include <nimble/nimble_port_freertos.h>
#include <os/os_mbuf.h>
#include <services/gap/ble_svc_gap.h>
#include <services/gatt/ble_svc_gatt.h>

namespace {

constexpr char kTag[] = "LapSightNUS";
constexpr char kDeviceName[] = "LapSight-Cardputer";

// Nordic UART Service UUIDs, encoded least-significant byte first as required
// by NimBLE's BLE_UUID128_INIT macro.
const ble_uuid128_t kNusServiceUuid = BLE_UUID128_INIT(
    0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0,
    0x93, 0xf3, 0xa3, 0xb5, 0x01, 0x00, 0x40, 0x6e
);
const ble_uuid128_t kNusRxUuid = BLE_UUID128_INIT(
    0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0,
    0x93, 0xf3, 0xa3, 0xb5, 0x02, 0x00, 0x40, 0x6e
);
const ble_uuid128_t kNusTxUuid = BLE_UUID128_INIT(
    0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0,
    0x93, 0xf3, 0xa3, 0xb5, 0x03, 0x00, 0x40, 0x6e
);

std::atomic_bool s_started{false};
std::atomic_bool s_connected{false};
std::atomic_bool s_subscribed{false};
std::atomic<uint16_t> s_connection_handle{BLE_HS_CONN_HANDLE_NONE};
std::atomic<uint32_t> s_notification_count{0};
std::atomic<uint32_t> s_dropped_notification_count{0};
uint16_t s_tx_value_handle = 0;
uint8_t s_own_address_type = BLE_OWN_ADDR_PUBLIC;

void advertise();

int onTxAccess(
    uint16_t,
    uint16_t,
    ble_gatt_access_ctxt*,
    void*
) {
    // The TX value is server-originated and notify-only. NimBLE still requires
    // every characteristic definition to provide an access callback.
    return BLE_ATT_ERR_UNLIKELY;
}

int onRxAccess(
    uint16_t connection_handle,
    uint16_t attribute_handle,
    ble_gatt_access_ctxt* context,
    void*
) {
    if (context->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    const uint16_t length = OS_MBUF_PKTLEN(context->om);
    uint8_t buffer[96];
    const uint16_t copied = std::min<uint16_t>(length, sizeof(buffer));
    if (copied > 0 && os_mbuf_copydata(context->om, 0, copied, buffer) != 0) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    ESP_LOGI(
        kTag,
        "RX write connection=%u attribute=%u bytes=%u",
        connection_handle,
        attribute_handle,
        length
    );
    return 0;
}

ble_gatt_chr_def s_characteristics[] = {
    {
        .uuid = &kNusTxUuid.u,
        .access_cb = onTxAccess,
        .arg = nullptr,
        .descriptors = nullptr,
        .flags = BLE_GATT_CHR_F_NOTIFY,
        .min_key_size = 0,
        .val_handle = &s_tx_value_handle,
        .cpfd = nullptr,
    },
    {
        .uuid = &kNusRxUuid.u,
        .access_cb = onRxAccess,
        .arg = nullptr,
        .descriptors = nullptr,
        .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
        .min_key_size = 0,
        .val_handle = nullptr,
        .cpfd = nullptr,
    },
    {},
};

ble_gatt_svc_def s_services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &kNusServiceUuid.u,
        .includes = nullptr,
        .characteristics = s_characteristics,
    },
    {},
};

int onGapEvent(ble_gap_event* event, void*) {
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status == 0) {
                s_connection_handle.store(event->connect.conn_handle);
                s_connected.store(true);
                s_subscribed.store(false);
                ESP_LOGI(kTag, "iPhone/phone connected");
            } else {
                ESP_LOGW(kTag, "connection failed status=%d", event->connect.status);
                advertise();
            }
            return 0;

        case BLE_GAP_EVENT_DISCONNECT:
            ESP_LOGI(kTag, "disconnected reason=%d", event->disconnect.reason);
            s_connection_handle.store(BLE_HS_CONN_HANDLE_NONE);
            s_connected.store(false);
            s_subscribed.store(false);
            advertise();
            return 0;

        case BLE_GAP_EVENT_SUBSCRIBE:
            if (event->subscribe.attr_handle == s_tx_value_handle) {
                s_subscribed.store(event->subscribe.cur_notify != 0);
                ESP_LOGI(kTag, "NMEA notifications %s", s_subscribed.load() ? "enabled" : "disabled");
            }
            return 0;

        case BLE_GAP_EVENT_ADV_COMPLETE:
            if (!s_connected.load()) {
                advertise();
            }
            return 0;

        case BLE_GAP_EVENT_MTU:
            ESP_LOGI(kTag, "MTU=%u", event->mtu.value);
            return 0;

        default:
            return 0;
    }
}

void advertise() {
    ble_hs_adv_fields fields{};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids128 = const_cast<ble_uuid128_t*>(&kNusServiceUuid);
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;

    int result = ble_gap_adv_set_fields(&fields);
    if (result != 0) {
        ESP_LOGE(kTag, "failed to set advertising fields rc=%d", result);
        return;
    }

    ble_hs_adv_fields response_fields{};
    const char* name = ble_svc_gap_device_name();
    response_fields.name = reinterpret_cast<uint8_t*>(const_cast<char*>(name));
    response_fields.name_len = std::strlen(name);
    response_fields.name_is_complete = 1;
    result = ble_gap_adv_rsp_set_fields(&response_fields);
    if (result != 0) {
        ESP_LOGE(kTag, "failed to set scan response rc=%d", result);
        return;
    }

    ble_gap_adv_params parameters{};
    parameters.conn_mode = BLE_GAP_CONN_MODE_UND;
    parameters.disc_mode = BLE_GAP_DISC_MODE_GEN;
    parameters.itvl_min = BLE_GAP_ADV_ITVL_MS(30);
    parameters.itvl_max = BLE_GAP_ADV_ITVL_MS(50);
    result = ble_gap_adv_start(
        s_own_address_type,
        nullptr,
        BLE_HS_FOREVER,
        &parameters,
        onGapEvent,
        nullptr
    );
    if (result != 0) {
        ESP_LOGE(kTag, "failed to start advertising rc=%d", result);
        return;
    }

    ESP_LOGI(kTag, "advertising as %s", kDeviceName);
}

void onHostReset(int reason) {
    ESP_LOGE(kTag, "NimBLE host reset reason=%d", reason);
    s_connection_handle.store(BLE_HS_CONN_HANDLE_NONE);
    s_connected.store(false);
    s_subscribed.store(false);
}

void onHostSync() {
    int result = ble_hs_util_ensure_addr(0);
    if (result != 0) {
        ESP_LOGE(kTag, "no usable BLE address rc=%d", result);
        return;
    }

    result = ble_hs_id_infer_auto(0, &s_own_address_type);
    if (result != 0) {
        ESP_LOGE(kTag, "failed to infer BLE address rc=%d", result);
        return;
    }

    advertise();
}

void hostTask(void*) {
    ESP_LOGI(kTag, "NimBLE host task started");
    nimble_port_run();
    nimble_port_freertos_deinit();
}

bool initializeControllerAndHost() {
    esp_err_t result = esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC_BT);
    if (result != ESP_OK && result != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(kTag, "failed to release Classic BT memory: %s", esp_err_to_name(result));
        return false;
    }

    esp_bt_controller_config_t controller_config = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    result = esp_bt_controller_init(&controller_config);
    if (result != ESP_OK) {
        ESP_LOGE(kTag, "controller init failed: %s", esp_err_to_name(result));
        return false;
    }

    result = esp_bt_controller_enable(ESP_BT_MODE_BLE);
    if (result != ESP_OK) {
        ESP_LOGE(kTag, "controller enable failed: %s", esp_err_to_name(result));
        return false;
    }

    result = esp_nimble_init();
    if (result != ESP_OK) {
        ESP_LOGE(kTag, "NimBLE init failed: %s", esp_err_to_name(result));
        return false;
    }

    return true;
}

}  // namespace

bool LapSightBleNus::start() {
    if (s_started.load()) {
        return true;
    }

    // NimBLE logs every notification at INFO level. At GNSS update rates that
    // becomes continuous serial traffic, so keep library output to actionable
    // warnings while retaining LapSight's own connection-state logs.
    esp_log_level_set("NimBLE", ESP_LOG_WARN);

    if (!initializeControllerAndHost()) {
        return false;
    }

    ble_hs_cfg.reset_cb = onHostReset;
    ble_hs_cfg.sync_cb = onHostSync;
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_NO_IO;
    ble_hs_cfg.sm_bonding = 0;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_sc = 0;

    ble_svc_gap_init();
    ble_svc_gatt_init();

    int result = ble_svc_gap_device_name_set(kDeviceName);
    if (result != 0) {
        ESP_LOGE(kTag, "failed to set device name rc=%d", result);
        return false;
    }

    result = ble_gatts_count_cfg(s_services);
    if (result != 0) {
        ESP_LOGE(kTag, "failed to count GATT services rc=%d", result);
        return false;
    }
    result = ble_gatts_add_svcs(s_services);
    if (result != 0) {
        ESP_LOGE(kTag, "failed to add GATT services rc=%d", result);
        return false;
    }

    result = esp_nimble_enable(reinterpret_cast<void*>(hostTask));
    if (result != ESP_OK) {
        ESP_LOGE(kTag, "failed to start NimBLE host rc=%d", result);
        return false;
    }

    s_started.store(true);
    return true;
}

bool LapSightBleNus::notify(const uint8_t* data, std::size_t length) {
    if (!data || length == 0 || !s_connected.load() || !s_subscribed.load()) {
        return false;
    }

    bool all_sent = true;
    for (std::size_t offset = 0; offset < length; offset += kSafeNotificationBytes) {
        const std::size_t chunk_size = std::min(kSafeNotificationBytes, length - offset);
        os_mbuf* packet = ble_hs_mbuf_from_flat(data + offset, chunk_size);
        if (!packet) {
            s_dropped_notification_count.fetch_add(1);
            all_sent = false;
            continue;
        }

        const int result = ble_gatts_notify_custom(s_connection_handle.load(), s_tx_value_handle, packet);
        if (result == 0) {
            s_notification_count.fetch_add(1);
        } else {
            s_dropped_notification_count.fetch_add(1);
            all_sent = false;
        }
        vTaskDelay(pdMS_TO_TICKS(2));
    }

    return all_sent;
}

bool LapSightBleNus::isStarted() const {
    return s_started.load();
}

bool LapSightBleNus::isConnected() const {
    return s_connected.load();
}

bool LapSightBleNus::isSubscribed() const {
    return s_subscribed.load();
}

uint32_t LapSightBleNus::notificationCount() const {
    return s_notification_count.load();
}

uint32_t LapSightBleNus::droppedNotificationCount() const {
    return s_dropped_notification_count.load();
}
