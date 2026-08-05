# FieldLink RX

FieldLink RX is an offline-only Android receiver for amateur-radio audio. It never transmits. The
app records live audio from the phone microphone or a manually selected Android audio input,
displays a 0–3 kHz waterfall and feeds the samples to independent decoder modules.

## Target

- Android 14 (API 34) or newer
- 48 kHz, mono, 16-bit PCM input
- German and English user interface
- foreground microphone service for reception with the screen off
- no audio recordings, network transport, accounts or persistent message history
- GPL-3.0-or-later

The core Android code is architecture-neutral. Native weak-signal decoder modules are built only
for ARM64 when enabled.

## Decoder status

| Mode | Status in 0.1.0 |
| --- | --- |
| FieldLink Fast/Wide | Protocol-compatible decoder core; live synchronizer under validation |
| CW/Morse | Live adaptive decoder, results include confidence |
| RTTY | Live 45.45 baud / 170 Hz Baudot decoder, experimental |
| PSK31/PSK63 | Decoder interface and signal classifier, experimental |
| FT8/FT4 | Native `ft8_lib` integration is the next milestone |
| JS8Call | Planned after the FT8/FT4 native integration |

This table is intentionally strict: a mode is not marked complete until it decodes reference audio
and over-the-air audio on an Android phone.

## Build

Open the repository in a current Android Studio installation, or run the GitHub Actions workflow.
The workflow installs Gradle 9.5 and Android SDK 37, runs unit tests and lint, then uploads an
installable debug APK as `FieldLink-RX-Android-14`.

## Privacy

The group password is held only in process memory and cleared when reception stops. Decoded
messages are not written to disk. Sharing a message or opening coordinates in Google Maps is an
explicit user action and leaves FieldLink RX.

## Safety and legal use

FieldLink RX is receive-only. The encrypted FieldLink laboratory mode must not be transmitted over
amateur-radio frequencies where encryption is prohibited. Users remain responsible for local band
plans and regulations.

