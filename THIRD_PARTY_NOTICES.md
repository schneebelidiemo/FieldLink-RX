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
