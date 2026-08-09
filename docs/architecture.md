# Architecture

1. `MainActivity` collects an optional FieldLink password and runtime permissions. Other modes do
   not receive or use a password.
2. `ReceiverService` owns either `AudioRecord` or `RtlSdrInput` and remains visible through an
   Android microphone or connected-device foreground-service notification. The user selects the
   source before reception. Microphone processing choices do not apply to the RTL-SDR path.
3. `DecoderCoordinator` computes one shared spectrum and sends PCM blocks only to the single
   decoder selected on the start screen.
4. `ReceiverRuntime` exposes immutable state to the Compose UI and keeps message history in memory.
   Starting or ending a decoder session clears that volatile history so results from different
   manually selected modes cannot be confused.
5. Message actions use Android intents for copy, share and Google Maps.
6. `FtxLiveDecoder` downsamples to 12 kHz, evaluates overlapping FT8/FT4 receive windows on a
   dedicated worker, and forwards at most three strongest valid decodes per cycle.
7. `Js8LiveDecoder` keeps a 30-second 12 kHz live window, automatically evaluates all five JS8
   submodes on a dedicated worker and reassembles First/Last-framed text for at most three signal
   streams. Its JNI target links only the upstream receive decoder and protocol unpackers.
8. `RtlSdrInput` receives unsigned 8-bit I/Q at 2.4 MS/s from a directly opened Android USB file
   descriptor. It avoids the tuner DC center, creates a 256-bin RF waterfall, demodulates the
   manually selected USB/LSB/CW/AM/NFM/WFM mode and converts it to the same 48 kHz mono stream used
   by the microphone path. Frequency, tuner gain and PPM changes briefly stop the asynchronous USB
   stream, apply the synchronous hardware control outside libusb's transfer callback, then restart
   reception. No TCP server, socket, recording or Internet permission is present.

The receiver thread never performs UI work. Decoder errors are isolated from Android lifecycle
management. RF and audio spectrum rendering are throttled independently from decoding.

## Release gates

- A decoder needs reproducible reference-vector tests.
- A decoder needs noisy and frequency-offset audio tests.
- FieldLink must remain byte-compatible with protocol draft 0.2 (Medium/Wide).
- Background capture must survive screen lock on at least two Android 14+ devices.
- No release APK may contain a radio transmission path. RTL-SDR audio monitoring is receive-only,
  muted by default and never routed back to a transmitter.
- RTL-SDR USB, tuning, all six modulation modes and detach handling require a real Pixel 8a/V4 test
  before the SDR integration can be promoted from a test build.
- The JS8Call gate additionally requires reference captures for all five submodes and real-device
  CPU/memory measurements before its status can be promoted from experimental.
