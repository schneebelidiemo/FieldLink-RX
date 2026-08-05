# Architecture

1. `MainActivity` collects the session password and runtime permissions.
2. `ReceiverService` owns `AudioRecord` and remains visible through an Android microphone
   foreground-service notification.
3. `DecoderCoordinator` computes one shared spectrum and sends PCM blocks to the enabled decoders.
4. `ReceiverRuntime` exposes immutable state to the Compose UI and keeps message history in memory.
5. Message actions use Android intents for copy, share and Google Maps.
6. `FtxLiveDecoder` downsamples to 12 kHz, evaluates overlapping FT8/FT4 receive windows on a
   dedicated worker, and forwards at most three strongest valid decodes per cycle.

The audio thread never performs UI work. Decoder errors are isolated so a failed mode cannot stop
the remaining decoders. Spectrum rendering is throttled independently from audio processing.

## Release gates

- A decoder needs reproducible reference-vector tests.
- A decoder needs noisy and frequency-offset audio tests.
- FieldLink must remain byte-compatible with protocol draft 0.1.
- Background capture must survive screen lock on at least two Android 14+ devices.
- No release APK may contain a transmission permission or audio-output modem path.
