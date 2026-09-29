package com.tapgo.tapgo_native;

import android.content.Context;
import android.content.SharedPreferences;

/** Chaves e helpers de SharedPreferences compartilhados entre o plugin
 *  (processo da UI) e o serviço de acessibilidade (mesmo processo). */
final class Prefs {
    private Prefs() {}

    static final String FILE = "tapgo_native_prefs";

    static final String DEVICE_NAME = "deviceName";
    static final String SERVICE_UUID = "serviceUuid";
    static final String NOTIFY_UUID = "notifyCharUuid";
    static final String AUTOSTART = "autostart";

    static final String ACCEPT_X = "acceptX";
    static final String ACCEPT_Y = "acceptY";
    static final String REJECT_X = "rejectX";
    static final String REJECT_Y = "rejectY";

    // Contrato BLE padrão (deve casar com o firmware do ESP32).
    static final String DEFAULT_SERVICE = "0000ff10-0000-1000-8000-00805f9b34fb";
    static final String DEFAULT_NOTIFY = "0000ff11-0000-1000-8000-00805f9b34fb";
    static final String DEFAULT_NAME = "TapGo";

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
