package com.tapgo.tapgo_native;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

public class TapGoNativePlugin
        implements FlutterPlugin,
        ActivityAware,
        MethodChannel.MethodCallHandler,
        EventChannel.StreamHandler,
        PluginRegistry.RequestPermissionsResultListener {

    private static final String METHOD_CHANNEL = "com.tapgo.tapgo_native/methods";
    private static final String EVENT_CHANNEL = "com.tapgo.tapgo_native/events";
    private static final int REQ_BLE = 4211;

    private MethodChannel methodChannel;
    private EventChannel eventChannel;
    private Context appContext;

    private Activity activity;
    private ActivityPluginBinding activityBinding;
    private MethodChannel.Result pendingPermissionResult;

    private static EventChannel.EventSink eventSink;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Chamado pelo serviço de acessibilidade para enviar eventos à UI. */
    static void sendEvent(final Map<String, Object> event) {
        final EventChannel.EventSink sink = eventSink;
        if (sink == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    sink.success(event);
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------------ Engine

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        appContext = binding.getApplicationContext();
        methodChannel = new MethodChannel(binding.getBinaryMessenger(), METHOD_CHANNEL);
        methodChannel.setMethodCallHandler(this);
        eventChannel = new EventChannel(binding.getBinaryMessenger(), EVENT_CHANNEL);
        eventChannel.setStreamHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        methodChannel.setMethodCallHandler(null);
        methodChannel = null;
        eventChannel.setStreamHandler(null);
        eventChannel = null;
    }

    // ------------------------------------------------------------------ Events

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        eventSink = events;
    }

    @Override
    public void onCancel(Object arguments) {
        eventSink = null;
    }

    // ---------------------------------------------------------------- Activity

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        bindActivity(binding);
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        bindActivity(binding);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        unbindActivity();
    }

    @Override
    public void onDetachedFromActivity() {
        unbindActivity();
    }

    private void bindActivity(ActivityPluginBinding binding) {
        activityBinding = binding;
        activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(this);
    }

    private void unbindActivity() {
        if (activityBinding != null) {
            activityBinding.removeRequestPermissionsResultListener(this);
        }
        activityBinding = null;
        activity = null;
    }

    // ------------------------------------------------------------------ Methods

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "isAccessibilityEnabled":
                result.success(isAccessibilityEnabled());
                break;

            case "openAccessibilitySettings": {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);
                result.success(null);
                break;
            }

            case "requestBlePermissions":
                requestBlePermissions(result);
                break;

            case "configure": {
                Prefs.get(appContext).edit()
                        .putString(Prefs.DEVICE_NAME, (String) call.argument("deviceName"))
                        .putString(Prefs.SERVICE_UUID, (String) call.argument("serviceUuid"))
                        .putString(Prefs.NOTIFY_UUID, (String) call.argument("notifyCharUuid"))
                        .apply();
                TapGoAccessibilityService service = TapGoAccessibilityService.getInstance();
                if (service != null) {
                    service.reloadConfig();
                }
                result.success(null);
                break;
            }

            case "setPosition": {
                String button = call.argument("button");
                Double x = call.argument("x");
                Double y = call.argument("y");
                if (button == null || x == null || y == null) {
                    result.error("bad_args", "button, x, y são obrigatórios", null);
                    break;
                }
                SharedPreferences.Editor editor = Prefs.get(appContext).edit();
                if ("accept".equals(button)) {
                    editor.putFloat(Prefs.ACCEPT_X, x.floatValue())
                            .putFloat(Prefs.ACCEPT_Y, y.floatValue());
                } else {
                    editor.putFloat(Prefs.REJECT_X, x.floatValue())
                            .putFloat(Prefs.REJECT_Y, y.floatValue());
                }
                editor.apply();
                result.success(null);
                break;
            }

            case "getPositions": {
                SharedPreferences p = Prefs.get(appContext);
                Map<String, Object> map = new HashMap<>();
                map.put("acceptX", (double) p.getFloat(Prefs.ACCEPT_X, -1f));
                map.put("acceptY", (double) p.getFloat(Prefs.ACCEPT_Y, -1f));
                map.put("rejectX", (double) p.getFloat(Prefs.REJECT_X, -1f));
                map.put("rejectY", (double) p.getFloat(Prefs.REJECT_Y, -1f));
                result.success(map);
                break;
            }

            case "startService": {
                Prefs.get(appContext).edit().putBoolean(Prefs.AUTOSTART, true).apply();
                TapGoAccessibilityService service = TapGoAccessibilityService.getInstance();
                if (service != null) {
                    service.reloadConfig();
                    service.startBle();
                    result.success(true);
                } else {
                    result.success(false);
                }
                break;
            }

            case "stopService": {
                Prefs.get(appContext).edit().putBoolean(Prefs.AUTOSTART, false).apply();
                TapGoAccessibilityService service = TapGoAccessibilityService.getInstance();
                if (service != null) {
                    service.stopBle();
                }
                result.success(null);
                break;
            }

            case "getConnectionState": {
                TapGoAccessibilityService service = TapGoAccessibilityService.getInstance();
                result.success(service != null ? service.getConnectionState() : "service_off");
                break;
            }

            case "dispatchTap": {
                Double x = call.argument("x");
                Double y = call.argument("y");
                TapGoAccessibilityService service = TapGoAccessibilityService.getInstance();
                if (service != null && x != null && y != null) {
                    result.success(service.dispatchTapAt(x.floatValue(), y.floatValue()));
                } else {
                    result.success(false);
                }
                break;
            }

            default:
                result.notImplemented();
        }
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                appContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        String className = TapGoAccessibilityService.class.getName();
        String full = appContext.getPackageName() + "/" + className;
        return enabled.contains(full) || enabled.contains(className);
    }

    private void requestBlePermissions(MethodChannel.Result result) {
        if (activity == null) {
            result.success(false);
            return;
        }
        List<String> required = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add("android.permission.BLUETOOTH_SCAN");
            required.add("android.permission.BLUETOOTH_CONNECT");
        } else {
            required.add("android.permission.ACCESS_FINE_LOCATION");
        }
        List<String> missing = new ArrayList<>();
        for (String permission : required) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            result.success(true);
            return;
        }
        pendingPermissionResult = result;
        activity.requestPermissions(missing.toArray(new String[0]), REQ_BLE);
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_BLE) {
            return false;
        }
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                granted = false;
            }
        }
        if (pendingPermissionResult != null) {
            pendingPermissionResult.success(granted);
            pendingPermissionResult = null;
        }
        return true;
    }
}
