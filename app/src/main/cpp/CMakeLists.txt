cmake_minimum_required(VERSION 3.22.1)

project(fieldlink_ftx LANGUAGES C CXX)

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
