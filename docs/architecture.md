# Architecture

1. `MainActivity` collects an optional FieldLink password and runtime permissions. Other modes do
   not receive or use a password.
2. `ReceiverService` owns `AudioRecord` and remains visible through an Android microphone
   foreground-service notification.
3. `DecoderCoordinator` computes one shared spectrum and sends PCM blocks only to the single
   decoder selected on the start screen.
4. `ReceiverRuntime` exposes immutable state to the Compose UI and keeps message history in memory.
5. Message actions use Android intents for copy, share and Google Maps.
6. `FtxLiveDecoder` downsamples to 12 kHz, evaluates overlapping FT8/FT4 receive windows on a
   dedicated worker, and forwards at most three strongest valid decodes per cycle.
7. `Js8LiveDecoder` keeps a 30-second 12 kHz live window, automatically evaluates all five JS8
   submodes on a dedicated worker and reassembles First/Last-framed text for at most three signal
   streams. Its JNI target links only the upstream receive decoder and protocol unpackers.

The audio thread never performs UI work. Decoder errors are isolated so a failed mode cannot stop
the remaining decoders. Spectrum rendering is throttled independently from audio processing.

## Release gates

- A decoder needs reproducible reference-vector tests.
- A decoder needs noisy and frequency-offset audio tests.
- FieldLink must remain byte-compatible with protocol draft 0.1.
- Background capture must survive screen lock on at least two Android 14+ devices.
- No release APK may contain a transmission permission or audio-output modem path.
- The JS8Call gate additionally requires reference captures for all five submodes and real-device
  CPU/memory measurements before its status can be promoted from experimental.
