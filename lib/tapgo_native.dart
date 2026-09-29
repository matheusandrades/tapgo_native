library tapgo_native;

import 'dart:async';

import 'package:flutter/services.dart';

/// Ponte Dart para o plugin nativo TapGo (BLE + Serviço de Acessibilidade).
///
/// Toda a lógica de segundo plano (conexão BLE e execução do toque) vive no
/// serviço de acessibilidade nativo, então estes métodos apenas configuram e
/// controlam esse serviço. Disponível apenas no Android.
class TapGoNative {
  TapGoNative._();

  static const MethodChannel _methods =
      MethodChannel('com.tapgo.tapgo_native/methods');
  static const EventChannel _eventsChannel =
      EventChannel('com.tapgo.tapgo_native/events');

  static Stream<Map<String, dynamic>>? _events;

  /// Fluxo de eventos vindos do serviço nativo.
  ///
  /// Cada evento é um mapa com as chaves:
  ///  - `type`: `service` | `ble` | `button` | `tap`
  ///  - `state`: ex. `scanning`, `connecting`, `connected`, `disconnected`,
  ///    `ready`, `permission_needed`, `bluetooth_off`, `accept`, `reject`,
  ///    `completed`, `no_position` ...
  ///  - `detail` (opcional): texto auxiliar.
  static Stream<Map<String, dynamic>> get events {
    _events ??= _eventsChannel.receiveBroadcastStream().map(
          (dynamic e) => Map<String, dynamic>.from(e as Map),
        );
    return _events!;
  }

  /// `true` se o serviço de acessibilidade do TapGo estiver ativado.
  static Future<bool> isAccessibilityEnabled() async =>
      (await _methods.invokeMethod<bool>('isAccessibilityEnabled')) ?? false;

  /// Abre a tela de Acessibilidade do Android para o usuário ativar o serviço.
  static Future<void> openAccessibilitySettings() =>
      _methods.invokeMethod<void>('openAccessibilitySettings');

  /// Solicita as permissões de Bluetooth necessárias (varia por versão do
  /// Android). Retorna `true` se todas foram concedidas.
  static Future<bool> requestBlePermissions() async =>
      (await _methods.invokeMethod<bool>('requestBlePermissions')) ?? false;

  /// Define o dispositivo alvo e os UUIDs do serviço/característica BLE.
  static Future<void> configure({
    required String deviceName,
    required String serviceUuid,
    required String notifyCharUuid,
  }) =>
      _methods.invokeMethod<void>('configure', <String, dynamic>{
        'deviceName': deviceName,
        'serviceUuid': serviceUuid,
        'notifyCharUuid': notifyCharUuid,
      });

  /// Salva a posição (em PIXELS FÍSICOS da tela) para um botão.
  /// [button] deve ser `accept` ou `reject`.
  static Future<void> setPosition({
    required String button,
    required double x,
    required double y,
  }) =>
      _methods.invokeMethod<void>('setPosition', <String, dynamic>{
        'button': button,
        'x': x,
        'y': y,
      });

  /// Lê as posições salvas. Chaves: `acceptX`, `acceptY`, `rejectX`, `rejectY`
  /// (valor `-1` significa não mapeado).
  static Future<Map<String, dynamic>> getPositions() async =>
      Map<String, dynamic>.from(
        (await _methods.invokeMethod<Map<dynamic, dynamic>>('getPositions')) ??
            <dynamic, dynamic>{},
      );

  /// Inicia o BLE (scan + conexão + assinatura das notificações) no serviço de
  /// acessibilidade. Retorna `true` se o serviço estava ativo para iniciar.
  static Future<bool> startService() async =>
      (await _methods.invokeMethod<bool>('startService')) ?? false;

  /// Encerra a conexão BLE e desliga a reconexão automática.
  static Future<void> stopService() =>
      _methods.invokeMethod<void>('stopService');

  /// Estado atual da conexão: `service_off`, `disconnected`, `scanning`,
  /// `connecting`, `connected`.
  static Future<String> getConnectionState() async =>
      (await _methods.invokeMethod<String>('getConnectionState')) ??
      'service_off';

  /// Executa um toque de teste na posição informada (pixels físicos).
  static Future<bool> dispatchTap({
    required double x,
    required double y,
  }) async =>
      (await _methods.invokeMethod<bool>('dispatchTap', <String, dynamic>{
        'x': x,
        'y': y,
      })) ??
      false;
}
