#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <M5Cardputer.h>
#include <TinyGPSPlus.h>

#include <cstring>
#include <string>

namespace {

constexpr char kFirmwareVersion[] = "0.1.1";
constexpr char kBleDeviceName[] = "LapSight-Cardputer";
constexpr char kNusServiceUuid[] = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char kNusRxUuid[] = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char kNusTxUuid[] = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";

// Official Cap LoRa-1262/Cardputer-Adv pin map. The ATGM336H UART defaults
// to NMEA 0183 at 115200 baud, 8 data bits, no parity, one stop bit.
constexpr int kGnssRxPin = 15;
constexpr int kGnssTxPin = 13;
constexpr uint32_t kGnssBaud = 115200;

// Twenty payload bytes are valid even before a central negotiates a larger
// ATT MTU. The app's NMEA parser deliberately accepts fragmented byte chunks.
constexpr size_t kBleChunkBytes = 20;
constexpr size_t kMaxNmeaLineBytes = 192;
constexpr uint32_t kDisplayRefreshMillis = 250;
constexpr uint32_t kDiagnosticLogMillis = 2000;
constexpr double kStationaryDisplayThresholdKmph = 3.0;

HardwareSerial gnssSerial(1);
TinyGPSPlus gps;

BLEServer* bleServer = nullptr;
BLECharacteristic* bleTx = nullptr;
volatile bool bleConnected = false;

char nmeaLine[kMaxNmeaLineBytes];
size_t nmeaLineLength = 0;
bool collectingNmea = false;
uint32_t validNmeaLines = 0;
uint32_t forwardedNmeaLines = 0;
uint32_t droppedNmeaLines = 0;
uint32_t lastNmeaMillis = 0;
uint32_t lastDisplayMillis = 0;
uint32_t lastDiagnosticMillis = 0;

bool endsWithSentenceType(const char* line, const char* type) {
    const char* comma = std::strchr(line, ',');
    if (comma == nullptr || comma - line < 4) return false;
    return std::strncmp(comma - 3, type, 3) == 0;
}

bool shouldForwardNmea(const char* line) {
    if (std::strncmp(line, "$PQTMEPE,", 9) == 0) return true;
    return endsWithSentenceType(line, "RMC") ||
           endsWithSentenceType(line, "GGA") ||
           endsWithSentenceType(line, "GNS") ||
           endsWithSentenceType(line, "VTG") ||
           endsWithSentenceType(line, "GST");
}

void notifyBytes(const uint8_t* bytes, size_t length) {
    if (!bleConnected || bleTx == nullptr || length == 0) return;

    for (size_t offset = 0; offset < length; offset += kBleChunkBytes) {
        const size_t chunkLength = min(kBleChunkBytes, length - offset);
        bleTx->setValue(const_cast<uint8_t*>(bytes + offset), chunkLength);
        bleTx->notify();
        // Leave the BLE host time to drain notifications and keep the UART
        // reader responsive enough for the receiver's factory NMEA stream.
        delay(2);
    }
}

void forwardCompletedNmeaLine() {
    if (nmeaLineLength < 7 || nmeaLine[0] != '$') return;
    nmeaLine[nmeaLineLength] = '\0';
    validNmeaLines += 1;
    lastNmeaMillis = millis();

    Serial.write(reinterpret_cast<const uint8_t*>(nmeaLine), nmeaLineLength);

    if (!shouldForwardNmea(nmeaLine)) return;
    notifyBytes(reinterpret_cast<const uint8_t*>(nmeaLine), nmeaLineLength);
    if (bleConnected) forwardedNmeaLines += 1;
}

void acceptGnssByte(char byte) {
    gps.encode(byte);

    if (byte == '$') {
        collectingNmea = true;
        nmeaLineLength = 0;
    }
    if (!collectingNmea) return;

    if (nmeaLineLength >= kMaxNmeaLineBytes - 1) {
        collectingNmea = false;
        nmeaLineLength = 0;
        droppedNmeaLines += 1;
        return;
    }

    nmeaLine[nmeaLineLength++] = byte;
    if (byte == '\n') {
        forwardCompletedNmeaLine();
        collectingNmea = false;
        nmeaLineLength = 0;
    }
}

class ServerCallbacks final : public BLEServerCallbacks {
    void onConnect(BLEServer*) override {
        bleConnected = true;
        Serial.println("[BLE] iPhone/phone connected");
    }

    void onDisconnect(BLEServer*) override {
        bleConnected = false;
        Serial.println("[BLE] disconnected; advertising restarted");
        BLEDevice::startAdvertising();
    }
};

class RxCallbacks final : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* characteristic) override {
        const std::string value = characteristic->getValue();
        if (value.empty()) return;

        Serial.printf("[BLE RX] %u bytes: ", static_cast<unsigned>(value.size()));
        Serial.write(reinterpret_cast<const uint8_t*>(value.data()), value.size());
        if (value.back() != '\n') Serial.println();

        // Version 0.1 is a GNSS byte bridge. Command writes are intentionally
        // observable in diagnostics but do not yet control a HUD session.
    }
};

void startBle() {
    BLEDevice::init(kBleDeviceName);
    BLEDevice::setMTU(185);

    bleServer = BLEDevice::createServer();
    bleServer->setCallbacks(new ServerCallbacks());

    BLEService* service = bleServer->createService(kNusServiceUuid);
    bleTx = service->createCharacteristic(
        kNusTxUuid,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    bleTx->addDescriptor(new BLE2902());

    BLECharacteristic* rx = service->createCharacteristic(
        kNusRxUuid,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
    );
    rx->setCallbacks(new RxCallbacks());

    service->start();
    BLEAdvertising* advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(kNusServiceUuid);
    advertising->setScanResponse(true);
    advertising->setMinPreferred(0x06);
    advertising->setMinPreferred(0x12);
    BLEDevice::startAdvertising();
    Serial.printf("[BLE] advertising as %s\n", kBleDeviceName);
}

void drawStatus() {
    M5Cardputer.Display.fillScreen(BLACK);
    M5Cardputer.Display.setTextColor(WHITE, BLACK);
    M5Cardputer.Display.setTextSize(1);
    M5Cardputer.Display.setCursor(6, 5);
    M5Cardputer.Display.printf("LapSight GNSS  v%s", kFirmwareVersion);

    M5Cardputer.Display.setCursor(6, 21);
    M5Cardputer.Display.setTextColor(bleConnected ? GREEN : YELLOW, BLACK);
    M5Cardputer.Display.printf("BLE: %s", bleConnected ? "CONNECTED" : "WAITING");

    const bool fixed = gps.location.isValid() && gps.location.age() < 3000;
    M5Cardputer.Display.setCursor(126, 21);
    M5Cardputer.Display.setTextColor(fixed ? GREEN : ORANGE, BLACK);
    M5Cardputer.Display.printf("GPS: %s", fixed ? "FIX" : "SEARCH");

    M5Cardputer.Display.setTextColor(WHITE, BLACK);
    M5Cardputer.Display.setCursor(6, 40);
    M5Cardputer.Display.setTextSize(2);
    if (gps.speed.isValid() && gps.speed.age() < 3000) {
        const double rawSpeedKmph = gps.speed.kmph();
        const double displaySpeedKmph = rawSpeedKmph < kStationaryDisplayThresholdKmph
            ? 0.0
            : rawSpeedKmph;
        M5Cardputer.Display.printf("%5.1f km/h", displaySpeedKmph);
    } else {
        M5Cardputer.Display.print("  --.- km/h");
    }

    M5Cardputer.Display.setTextSize(1);
    M5Cardputer.Display.setCursor(6, 68);
    if (gps.location.isValid()) {
        M5Cardputer.Display.printf("LAT  %.6f", gps.location.lat());
        M5Cardputer.Display.setCursor(6, 82);
        M5Cardputer.Display.printf("LON  %.6f", gps.location.lng());
    } else {
        M5Cardputer.Display.print("LAT  --");
        M5Cardputer.Display.setCursor(6, 82);
        M5Cardputer.Display.print("LON  --");
    }

    M5Cardputer.Display.setCursor(6, 101);
    M5Cardputer.Display.printf(
        "SAT %s  HDOP %s",
        gps.satellites.isValid() ? String(gps.satellites.value()).c_str() : "--",
        gps.hdop.isValid() ? String(gps.hdop.hdop(), 1).c_str() : "--"
    );
    M5Cardputer.Display.setCursor(6, 116);
    M5Cardputer.Display.setTextColor(LIGHTGREY, BLACK);
    M5Cardputer.Display.printf(
        "NMEA %lu  BLE %lu  DROP %lu",
        static_cast<unsigned long>(validNmeaLines),
        static_cast<unsigned long>(forwardedNmeaLines),
        static_cast<unsigned long>(droppedNmeaLines)
    );
}

void logDiagnostics() {
    const uint32_t nmeaAge = validNmeaLines == 0 ? UINT32_MAX : millis() - lastNmeaMillis;
    Serial.printf(
        "[STATUS] ble=%s nmea=%lu forwarded=%lu dropped=%lu age_ms=%s fix=%s sats=%s speed_kmh=%s\n",
        bleConnected ? "connected" : "waiting",
        static_cast<unsigned long>(validNmeaLines),
        static_cast<unsigned long>(forwardedNmeaLines),
        static_cast<unsigned long>(droppedNmeaLines),
        nmeaAge == UINT32_MAX ? "none" : String(nmeaAge).c_str(),
        gps.location.isValid() ? "valid" : "searching",
        gps.satellites.isValid() ? String(gps.satellites.value()).c_str() : "unknown",
        gps.speed.isValid() ? String(gps.speed.kmph(), 1).c_str() : "unknown"
    );
}

}  // namespace

void setup() {
    Serial.begin(115200);
    delay(300);
    Serial.printf("\nLapSight Cardputer GNSS bridge v%s\n", kFirmwareVersion);
    Serial.printf("GNSS UART RX=G%d TX=G%d baud=%lu\n", kGnssRxPin, kGnssTxPin,
                  static_cast<unsigned long>(kGnssBaud));
    Serial.println("LoRa radio is disabled in this firmware.");

    auto config = M5.config();
    M5Cardputer.begin(config, true);
    M5Cardputer.Display.setRotation(1);
    M5Cardputer.Display.setBrightness(80);

    gnssSerial.begin(kGnssBaud, SERIAL_8N1, kGnssRxPin, kGnssTxPin);
    startBle();
    drawStatus();
}

void loop() {
    M5Cardputer.update();
    while (gnssSerial.available() > 0) {
        acceptGnssByte(static_cast<char>(gnssSerial.read()));
    }

    const uint32_t now = millis();
    if (now - lastDisplayMillis >= kDisplayRefreshMillis) {
        lastDisplayMillis = now;
        drawStatus();
    }
    if (now - lastDiagnosticMillis >= kDiagnosticLogMillis) {
        lastDiagnosticMillis = now;
        logDiagnostics();
    }
    delay(1);
}
