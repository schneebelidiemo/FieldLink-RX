# FieldLink RX

FieldLink RX is an offline-only Android receiver for amateur-radio audio. It never transmits. The
app receives live audio from the phone microphone, an Android audio input, or a directly connected
RTL-SDR Blog V4. It displays the appropriate waterfall and feeds 48 kHz mono samples to the selected
decoder module.

## Target

- Android 14 (API 34) or newer
- 48 kHz, mono, 16-bit PCM input
- German and English user interface
- foreground microphone or connected-device service for reception with the screen off
- no audio recordings, network transport, accounts or persistent message history
- GPL-3.0-or-later

The Android interface and FieldLink modem are architecture-neutral. The FT8/FT4 and JS8Call
weak-signal decoders are native code, so the current APK is deliberately built for ARM64
(`arm64-v8a`).

## Decoder status

| Mode | Status in 0.5.3 |
| --- | --- |
| FieldLink Medium/Wide | Protocol-compatible decoder core; live synchronizer under validation |
| CW/Morse | Adaptive 3-track decoder with dynamic noise floor, 3–60 WPM, tone/sensitivity controls and confidence |
| RTTY | Live 45.45 baud / 170 Hz Baudot decoder, experimental |
| PSK31/PSK63 | Decoder interface and signal classifier, experimental |
| FT8/FT4 | Native ARM64 live decoder with overlapping windows and reference-vector tests |
| JS8Call | Official native RX core integrated for Normal, Fast, Turbo/40, Slow and Ultra/60; reference and over-the-air validation pending |

This table is intentionally strict: a mode is not marked complete until it decodes reference audio
and over-the-air audio on an Android phone.

Exactly one decoder is selected manually before reception. FieldLink Medium and FieldLink Wide are
separate choices, as are CW, RTTY, PSK31, PSK63, FT8, FT4 and JS8Call. This prevents unrelated
decoders from consuming CPU or influencing the selected mode's live processing. The FieldLink
preamble synchronizer checks eight timing phases and validates the unique final eight-tone sync word
separately from the alternating lead-in. The UI reports both values. Encrypted, unencrypted,
missing-password and simulated speaker-to-microphone round-trips are covered by Android unit tests.
The password field is shown only for FieldLink Medium/Wide and may be left empty for cleartext
reception; all other decoders always receive without a password. Android microphone processing is
manually selectable between standard microphone, voice-recognition path, automatic and unprocessed
capture where supported.

CW reception follows up to three signals separated by at least 40 Hz. Automatic tone search covers
300–1000 Hz; manual tuning covers 200–1500 Hz. Speed, sensitivity and the 1–15 second message gap
are adjustable on the waterfall screen and persist across starts. Normal text becomes visible after
three valid characters. Shorter fragments are stored as uncertain with a trailing `?`, but do not
create a background notification. Amateur-radio prosigns `<AR>`, `<SK>`, `<BT>` and `<KN>` are
recognized.

Medium replaces the earlier experimental Fast profile. It uses 8-MFSK, 50 Hz tone spacing and
20 ms symbols, giving about 400 Hz occupied width and 14.7 seconds per packet. The longer symbols
are intended to survive an SSB radio chain while remaining substantially faster than Wide.

## RTL-SDR V4

Select `RTL-SDR – V4` under **Audio source**, grant Android USB access, and start reception. The SDR
page provides a 2.4 MS/s RF waterfall, manual frequency entry with 100 Hz steps, USB/LSB/CW/AM/NFM/WFM
demodulation, automatic or manual bandwidth and gain, PPM correction, squelch, and a muted-by-default
audio monitor. The supported hardware tuning range is 500 kHz to 1.766 GHz.
FieldLink Medium preamble acquisition corrects a common carrier error of roughly ±300 Hz before
decoding the packet, while the full 3 kHz USB/LSB audio passband preserves all eight modem tones.

The implementation opens the Android USB file descriptor directly. It does not use rtl_tcp, does not
request Internet access, and does not record I/Q or audio. SDR settings are intentionally reset when
the app process starts. The V4-compatible Android driver is pinned as source and compiled by CMake.

## Build

Clone the repository with submodules and open it in a current Android Studio installation, or run
the GitHub Actions workflow:

```shell
git clone --recurse-submodules https://github.com/schneebelidiemo/FieldLink-RX.git
```

The workflow installs Gradle 9.5, Android SDK 36, NDK 28.2 and CMake 3.22. It runs native FT8/FT4
reference vectors, Kotlin unit tests and Android lint, builds the receive-only JS8 native bridge,
then uploads an ARM64 debug APK as `FieldLink-RX-Android-14`.

## Privacy

An entered FieldLink group password is held only in process memory and cleared when reception stops. Decoded
messages are not written to disk. Sharing a message or opening coordinates in Google Maps is an
explicit user action and leaves FieldLink RX.

## Safety and legal use

FieldLink RX is receive-only. The encrypted FieldLink laboratory mode must not be transmitted over
amateur-radio frequencies where encryption is prohibited. Users remain responsible for local band
plans and regulations.
