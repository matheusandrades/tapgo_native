package com.tapgo.tapgo_native;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Serviço de acessibilidade do TapGo.
 *
 * Responsabilidades:
 *  - Manter (no processo do app, mas vivo em segundo plano) a conexão BLE com
 *    o dispositivo de botões.
 *  - Ao receber a notificação de um botão, executar um toque na tela via
 *    {@link #dispatchGesture} na posição previamente mapeada.
 *
 * A configuração (nome/UUIDs e coordenadas) é lida de SharedPreferences,
 * gravada pela camada Flutter através do {@link TapGoNativePlugin}.
 */
public class TapGoAccessibilityService extends AccessibilityService {

    private static final String TAG = "TapGoService";
    private static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long RECONNECT_DELAY_MS = 3000L;

    private static TapGoAccessibilityService instance;

    public static TapGoAccessibilityService getInstance() {
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private ScanCallback scanCallback;
    private Runnable reconnectRunnable;

    private boolean scanning = false;
    private String connectionState = "disconnected";

    private String deviceName;
    private UUID serviceUuid;
    private UUID notifyUuid;

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        reloadConfig();

        BluetoothManager manager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) {
            adapter = manager.getAdapter();
        }

        emit("service", "connected", null);

        if (Prefs.get(this).getBoolean(Prefs.AUTOSTART, false)) {
            startBle();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Não lemos a tela; o serviço existe apenas para poder executar gestos.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopBle();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopBle();
        instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------- config

    void reloadConfig() {
        SharedPreferences p = Prefs.get(this);
        deviceName = p.getString(Prefs.DEVICE_NAME, Prefs.DEFAULT_NAME);
        serviceUuid = safeUuid(p.getString(Prefs.SERVICE_UUID, Prefs.DEFAULT_SERVICE),
                Prefs.DEFAULT_SERVICE);
        notifyUuid = safeUuid(p.getString(Prefs.NOTIFY_UUID, Prefs.DEFAULT_NOTIFY),
                Prefs.DEFAULT_NOTIFY);
    }

    private UUID safeUuid(String value, String fallback) {
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return UUID.fromString(fallback);
        }
    }

    String getConnectionState() {
        return connectionState;
    }

    // ------------------------------------------------------------- BLE

    private boolean hasBlePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission("android.permission.BLUETOOTH_SCAN")
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    void startBle() {
        if (adapter == null || !adapter.isEnabled()) {
            emit("ble", "bluetooth_off", null);
            return;
        }
        if (!hasBlePermission()) {
            emit("ble", "permission_needed", null);
            return;
        }
        if (scanning || gatt != null) {
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            emit("ble", "bluetooth_off", null);
            return;
        }

        connectionState = "scanning";
        emit("ble", "scanning", null);
        scanning = true;

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (result == null || result.getDevice() == null) {
                    return;
                }
                if (matches(result)) {
                    stopScan();
                    connect(result.getDevice());
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                scanning = false;
                connectionState = "disconnected";
                emit("ble", "scan_failed", "code=" + errorCode);
                scheduleReconnect();
            }
        };

        try {
            scanner.startScan(scanCallback);
        } catch (Exception e) {
            scanning = false;
            connectionState = "disconnected";
            emit("ble", "scan_failed", e.getMessage());
            scheduleReconnect();
        }
    }

    @SuppressLint("MissingPermission")
    private boolean matches(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record != null && record.getServiceUuids() != null) {
            for (ParcelUuid uuid : record.getServiceUuids()) {
                if (uuid.getUuid().equals(serviceUuid)) {
                    return true;
                }
            }
        }
        String name = null;
        try {
            name = result.getDevice().getName();
        } catch (SecurityException ignored) {
        }
        if (name == null && record != null) {
            name = record.getDeviceName();
        }
        return deviceName != null && deviceName.equals(name);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanner != null && scanCallback != null && scanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
    }

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {
        connectionState = "connecting";
        emit("ble", "connecting", null);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(this, false, gattCallback,
                        BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(this, false, gattCallback);
            }
        } catch (Exception e) {
            emit("ble", "error", e.getMessage());
            scheduleReconnect();
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionState = "connected";
                emit("ble", "connected", null);
                try {
                    g.discoverServices();
                } catch (Exception ignored) {
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState = "disconnected";
                emit("ble", "disconnected", null);
                closeGatt();
                scheduleReconnect();
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                scheduleReconnect();
                return;
            }
            BluetoothGattService service = g.getService(serviceUuid);
            if (service == null) {
                emit("ble", "service_not_found", null);
                return;
            }
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(notifyUuid);
            if (characteristic == null) {
                emit("ble", "char_not_found", null);
                return;
            }
            g.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD);
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                }
            }
            emit("ble", "ready", null);
        }

        // Android 13 (API 33) e superior.
        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            handleButton(value);
        }

        // Android 12 (API 32) e inferior.
        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic characteristic) {
            handleButton(characteristic.getValue());
        }
    };

    private void handleButton(byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        int code = value[0];
        final String button;
        if (code == 1) {
            button = "accept";
        } else if (code == 2) {
            button = "reject";
        } else {
            return;
        }
        emit("button", button, null);

        SharedPreferences p = Prefs.get(this);
        float x;
        float y;
        if ("accept".equals(button)) {
            x = p.getFloat(Prefs.ACCEPT_X, -1f);
            y = p.getFloat(Prefs.ACCEPT_Y, -1f);
        } else {
            x = p.getFloat(Prefs.REJECT_X, -1f);
            y = p.getFloat(Prefs.REJECT_Y, -1f);
        }
        if (x < 0 || y < 0) {
            emit("tap", "no_position", button);
            return;
        }
        dispatchTapAt(x, y);
    }

    boolean dispatchTapAt(final float x, final float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            emit("tap", "unsupported_os", null);
            return false;
        }
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 80));
            return dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    emit("tap", "completed", ((int) x) + "," + ((int) y));
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    emit("tap", "cancelled", ((int) x) + "," + ((int) y));
                }
            }, null);
        } catch (Exception e) {
            Log.e(TAG, "dispatchTapAt: " + e.getMessage());
            emit("tap", "error", e.getMessage());
            return false;
        }
    }

    private void scheduleReconnect() {
        if (!Prefs.get(this).getBoolean(Prefs.AUTOSTART, false)) {
            return;
        }
        if (reconnectRunnable != null) {
            main.removeCallbacks(reconnectRunnable);
        }
        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                boolean autostart =
                        Prefs.get(TapGoAccessibilityService.this)
                                .getBoolean(Prefs.AUTOSTART, false);
                if (autostart && gatt == null && !scanning) {
                    startBle();
                }
            }
        };
        main.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }

    @SuppressLint("MissingPermission")
    void stopBle() {
        if (reconnectRunnable != null) {
            main.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        stopScan();
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {
            }
        }
        closeGatt();
        connectionState = "disconnected";
        emit("ble", "stopped", null);
    }

    // ------------------------------------------------------------- events

    private void emit(String type, String state, String detail) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("state", state);
        if (detail != null) {
            event.put("detail", detail);
        }
        TapGoNativePlugin.sendEvent(event);
    }
}
