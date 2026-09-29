# tapgo_native

Plugin Flutter (Android) que conecta o app **TapGo** a um dispositivo BLE com
botões físicos (ex.: ESP32) e executa **toques na tela** — inclusive em outros
apps — usando um **Serviço de Acessibilidade** do Android.

É pensado como tecnologia assistiva / acesso por acionador (*switch access*):
um botão físico dispara um toque numa posição fixa da tela que o usuário mapeou.

## Como funciona

```
ESP32 (botão) --BLE notify--> Serviço de Acessibilidade (nativo, em 2º plano)
                                     |
                                     v
                          dispatchGesture(x, y)  -> toque na tela
```

Toda a lógica de segundo plano vive no **serviço de acessibilidade nativo**, que
permanece ativo mesmo com o TapGo minimizado. Por isso a conexão BLE e o toque
continuam funcionando quando outro app está em primeiro plano — que é o objetivo.

O manifesto deste plugin **declara o serviço e as permissões de Bluetooth**, e o
Gradle os mescla automaticamente no `AndroidManifest.xml` do app gerado pelo
FlutterFlow. Você não precisa editar o manifesto do app.

## Contrato BLE (precisa casar com o firmware)

| Item              | Valor padrão                             |
|-------------------|------------------------------------------|
| Nome anunciado    | `TapGo`                                  |
| Service UUID      | `0000ff10-0000-1000-8000-00805f9b34fb`   |
| Characteristic    | `0000ff11-0000-1000-8000-00805f9b34fb` (NOTIFY) |
| Payload           | 1 byte: `0x01` = Aceitar, `0x02` = Recusar |

O firmware de exemplo do ESP32 está em [`esp32/tapgo_esp32.ino`](esp32/tapgo_esp32.ino).
Os UUIDs/nome podem ser trocados em tempo de execução via `TapGoNative.configure(...)`.

## API Dart

```dart
import 'package:tapgo_native/tapgo_native.dart';

await TapGoNative.isAccessibilityEnabled();      // bool
await TapGoNative.openAccessibilitySettings();   // abre Ajustes p/ o usuário ativar
await TapGoNative.requestBlePermissions();       // bool (pede permissões BLE)

await TapGoNative.configure(
  deviceName: 'TapGo',
  serviceUuid: '0000ff10-0000-1000-8000-00805f9b34fb',
  notifyCharUuid: '0000ff11-0000-1000-8000-00805f9b34fb',
);

await TapGoNative.setPosition(button: 'accept', x: 540, y: 1600); // pixels FÍSICOS
await TapGoNative.getPositions();                 // {acceptX, acceptY, rejectX, rejectY}

await TapGoNative.startService();                 // inicia scan/conexão BLE
await TapGoNative.getConnectionState();           // 'connected' | 'scanning' | ...
await TapGoNative.stopService();

await TapGoNative.dispatchTap(x: 540, y: 1600);   // toque de teste

TapGoNative.events.listen((e) {                   // {type, state, detail?}
  // type: service | ble | button | tap
});
```

> **Coordenadas em pixels físicos.** `dispatchGesture` usa pixels físicos a partir
> do canto superior esquerdo da tela. Ao mapear na UI, multiplique a posição
> lógica do toque por `MediaQuery.devicePixelRatio`.

## Passo a passo de uso no app

1. `requestBlePermissions()` — conceder Bluetooth.
2. `isAccessibilityEnabled()`; se `false`, `openAccessibilitySettings()` e pedir
   para o usuário ativar **TapGo** na lista.
3. `configure(...)` (uma vez, ou quando trocar de dispositivo/UUID).
4. Mapear os toques com a tela de mapeamento → `setPosition('accept'|'reject', x, y)`.
5. `startService()` — o serviço conecta ao ESP32 e passa a executar os toques.

## Requisitos

- Android 7.0+ (API 24) para `dispatchGesture`. O `minSdk` efetivo do app TapGo é 23;
  em API 23 o toque não é suportado, o resto funciona.
- Bluetooth LE no aparelho.

## Publicação (para o FlutterFlow conseguir baixar)

O build do FlutterFlow só baixa dependências Git de repositórios **públicos**:

```bash
cd /Users/matheusandrade/tapgo_native
git init
git add .
git commit -m "TapGo native plugin (BLE + accessibility)"
git branch -M main
# crie um repositório PÚBLICO em github.com/matheusandrades/tapgo_native e:
git remote add origin https://github.com/matheusandrades/tapgo_native.git
git push -u origin main
```

No FlutterFlow o projeto já está configurado para puxar:

```
tapgo_native:
  git:
    url: https://github.com/matheusandrades/tapgo_native.git
    ref: main
```

Recomendado: depois de publicar, fixe um commit (`ref: <sha>`) para builds
reproduzíveis.

## Aviso

Este plugin executa toques automatizados na tela via serviço de acessibilidade.
Use-o de forma responsável e apenas em dispositivos e apps que você controla.
Nenhum conteúdo da tela é lido ou coletado — o serviço só executa gestos.
