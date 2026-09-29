/*
 * TapGo — Firmware ESP32 (dois botões físicos: Aceitar / Recusar)
 * -----------------------------------------------------------------
 * Usa a biblioteca BLE nativa do core "arduino-esp32" (BLEDevice).
 *
 * Contrato BLE (precisa casar com o plugin tapgo_native):
 *   - Nome anunciado.....: "TapGo"
 *   - Service UUID.......: 0000ff10-0000-1000-8000-00805f9b34fb
 *   - Characteristic.....: 0000ff11-0000-1000-8000-00805f9b34fb  (NOTIFY)
 *       * envia 1 byte:  0x01 = Aceitar   |   0x02 = Recusar
 *
 * Ligações sugeridas (botões para o GND, usando pull-up interno):
 *   - Botão ACEITAR  -> GPIO 25  (e GND)
 *   - Botão RECUSAR  -> GPIO 26  (e GND)
 *
 * Ajuste os pinos conforme sua placa.
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define DEVICE_NAME       "TapGo"
#define SERVICE_UUID      "0000ff10-0000-1000-8000-00805f9b34fb"
#define CHAR_UUID         "0000ff11-0000-1000-8000-00805f9b34fb"

#define PIN_ACCEPT        25
#define PIN_REJECT        26
#define DEBOUNCE_MS       200

BLECharacteristic *buttonChar = nullptr;
bool deviceConnected = false;

int lastAccept = HIGH;
int lastReject = HIGH;
unsigned long lastAcceptMs = 0;
unsigned long lastRejectMs = 0;

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    deviceConnected = true;
  }
  void onDisconnect(BLEServer *server) override {
    deviceConnected = false;
    // Volta a anunciar para permitir reconexão automática do app.
    BLEDevice::startAdvertising();
  }
};

void notifyButton(uint8_t code) {
  if (!deviceConnected || buttonChar == nullptr) return;
  buttonChar->setValue(&code, 1);
  buttonChar->notify();
}

void setup() {
  Serial.begin(115200);

  pinMode(PIN_ACCEPT, INPUT_PULLUP);
  pinMode(PIN_REJECT, INPUT_PULLUP);

  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);
  buttonChar = service->createCharacteristic(
      CHAR_UUID,
      BLECharacteristic::PROPERTY_NOTIFY);
  buttonChar->addDescriptor(new BLE2902());
  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();

  Serial.println("TapGo BLE pronto e anunciando.");
}

void loop() {
  unsigned long now = millis();

  int accept = digitalRead(PIN_ACCEPT);
  if (accept == LOW && lastAccept == HIGH && (now - lastAcceptMs) > DEBOUNCE_MS) {
    lastAcceptMs = now;
    Serial.println("Botao: ACEITAR");
    notifyButton(0x01);
  }
  lastAccept = accept;

  int reject = digitalRead(PIN_REJECT);
  if (reject == LOW && lastReject == HIGH && (now - lastRejectMs) > DEBOUNCE_MS) {
    lastRejectMs = now;
    Serial.println("Botao: RECUSAR");
    notifyButton(0x02);
  }
  lastReject = reject;

  delay(10);
}
