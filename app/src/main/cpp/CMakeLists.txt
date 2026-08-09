cmake_minimum_required(VERSION 3.22.1)

project(fieldlink_native_decoders LANGUAGES C CXX)

set(FT8_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ft8_lib)
set(FT8_SOURCES
    ${FT8_LIB_DIR}/common/monitor.c
    ${FT8_LIB_DIR}/fft/kiss_fft.c
    ${FT8_LIB_DIR}/fft/kiss_fftr.c
    ${FT8_LIB_DIR}/ft8/constants.c
    ${FT8_LIB_DIR}/ft8/crc.c
    ${FT8_LIB_DIR}/ft8/decode.c
    ${FT8_LIB_DIR}/ft8/encode.c
    ${FT8_LIB_DIR}/ft8/ldpc.c
    ${FT8_LIB_DIR}/ft8/message.c
    ${FT8_LIB_DIR}/ft8/text.c
)

add_library(
    fieldlink_ftx
    SHARED
    ftx_engine.cpp
    native_ftx.cpp
    ${FT8_SOURCES}
)

target_include_directories(fieldlink_ftx PRIVATE ${FT8_LIB_DIR} ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(fieldlink_ftx PRIVATE cxx_std_17)
target_compile_definitions(fieldlink_ftx PRIVATE _GNU_SOURCE)
target_compile_options(fieldlink_ftx PRIVATE -O3 -Wall -Wextra)
target_link_libraries(fieldlink_ftx PRIVATE log m)

# Pinned as a Git submodule. Only the receive decoder and protocol text
# unpackers are compiled; the upstream engine, transmitter, rig, networking,
# storage and Android audio adapters are deliberately excluded.
set(JS8_PORT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/js8call-android-port)
if(NOT EXISTS ${JS8_PORT_DIR}/core/src/decoder/legacy_decoder.cpp)
    message(FATAL_ERROR "JS8Call Android-port submodule is missing; checkout with submodules enabled")
endif()

add_library(fftw3f STATIC IMPORTED)
set_target_properties(
    fftw3f
    PROPERTIES IMPORTED_LOCATION
    ${JS8_PORT_DIR}/android/libs/fftw3/${ANDROID_ABI}/lib/libfftw3f.a
)

add_library(
    js8core_rx
    STATIC
    ${JS8_PORT_DIR}/core/src/decoder/legacy_decoder.cpp
    ${JS8_PORT_DIR}/core/src/protocol/submode.cpp
    ${JS8_PORT_DIR}/core/src/protocol/costas.cpp
    ${JS8_PORT_DIR}/core/src/protocol/constants.cpp
    ${JS8_PORT_DIR}/core/src/protocol/time_utils.cpp
    ${JS8_PORT_DIR}/core/src/protocol/varicode.cpp
    ${JS8_PORT_DIR}/core/src/protocol/jsc.cpp
    ${JS8_PORT_DIR}/core/src/protocol/jsc_map.cpp
    ${JS8_PORT_DIR}/core/src/protocol/jsc_list.cpp
)

set_target_properties(js8core_rx PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(
    js8core_rx
    PRIVATE
    ${JS8_PORT_DIR}
    ${JS8_PORT_DIR}/core/include
    ${JS8_PORT_DIR}/android/libs/boost
    ${JS8_PORT_DIR}/android/libs/fftw3/${ANDROID_ABI}/include
)
target_compile_features(js8core_rx PRIVATE cxx_std_20)
target_compile_options(js8core_rx PRIVATE -O3 -Wall -Wextra)
target_link_libraries(js8core_rx PRIVATE fftw3f log m)

add_library(fieldlink_js8 SHARED native_js8.cpp)
target_include_directories(fieldlink_js8 PRIVATE ${JS8_PORT_DIR}/core/include)
target_compile_features(fieldlink_js8 PRIVATE cxx_std_20)
target_compile_options(fieldlink_js8 PRIVATE -O3 -Wall -Wextra)
target_link_libraries(fieldlink_js8 PRIVATE js8core_rx fftw3f log m)

# The Android RTL-SDR source is pinned as a Git submodule. Only libusb and
# librtlsdr are compiled. The rtl_tcp server and its network code are excluded.
set(RTL_ANDROID_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rtl_tcp_andro/rtlsdr/src/main/cpp)
if(NOT EXISTS ${RTL_ANDROID_DIR}/librtlsdr/src/librtlsdr.c)
    message(FATAL_ERROR "RTL-SDR Android submodule is missing; checkout with submodules enabled")
endif()

add_subdirectory(${RTL_ANDROID_DIR}/librtlsdr ${CMAKE_CURRENT_BINARY_DIR}/rtl_sdr_android EXCLUDE_FROM_ALL)

add_library(fieldlink_sdr SHARED native_sdr.cpp)
target_include_directories(
    fieldlink_sdr
    PRIVATE
    ${RTL_ANDROID_DIR}/librtlsdr/include
)
target_compile_features(fieldlink_sdr PRIVATE cxx_std_17)
target_compile_options(fieldlink_sdr PRIVATE -O3 -Wall -Wextra)
target_link_libraries(fieldlink_sdr PRIVATE librtlsdr log m)
