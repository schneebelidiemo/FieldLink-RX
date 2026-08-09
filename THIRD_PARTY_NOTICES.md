# Third-party notices

## ft8_lib

FieldLink RX vendors selected source files from `kgoba/ft8_lib` commit
`9fec6ca39886edbf96f4f5e71edc76da5074e871` for FT8 and FT4 decoding.

- Upstream: https://github.com/kgoba/ft8_lib
- Copyright: Kārlis Goba and contributors
- License: MIT

The complete MIT license text is retained at
`app/src/main/cpp/third_party/ft8_lib/LICENSE`.

FieldLink RX carries two small local portability/receiver fixes: standards-safe
`uint64_t` diagnostic formatting and a protocol-dependent candidate time range
so complete FT4 receive windows are searched instead of only their first 20
symbols.

## JS8Call Android port

FieldLink RX uses the receive decoder and protocol text unpackers from the
`JS8Call-improved/Android-port` repository at commit
`2f458cb8b0a74f522d38fa8b7aa5390804fdcef6`.

- Upstream: https://github.com/JS8Call-improved/Android-port
- Copyright: JS8Call contributors
- License: GPL-3.0

The source is pinned as `third_party/js8call-android-port`. FieldLink RX compiles
only `core/src/decoder/legacy_decoder.cpp` and the protocol decoding sources.
The upstream engine, transmit modulator, rig control, networking, storage and
Android audio adapters are excluded from the application build. The upstream
license and notices remain available inside the pinned submodule.

## RTL-SDR Android receiver source

FieldLink RX builds the Android libusb and librtlsdr receiver sources from
`martinmarinov/rtl_tcp_andro-` commit
`304dc37f1cd7a5c3a42ad6b4af497b7f93d96505`.

- Upstream: https://github.com/martinmarinov/rtl_tcp_andro-
- Copyright: Signalware Ltd, Osmocom RTL-SDR contributors, libusb contributors
- License: GPL-2.0-or-later for the Android RTL-SDR modifications; bundled
  libusb files retain their upstream license

Only the Android libusb and RTL-SDR receiver libraries are compiled. The
rtl_tcp server, sockets and network transport are not included in FieldLink RX.
