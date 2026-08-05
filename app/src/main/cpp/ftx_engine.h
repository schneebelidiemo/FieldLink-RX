#pragma once

#include <string>
#include <vector>

enum class FieldLinkFtxProtocol {
    FT4 = 0,
    FT8 = 1,
};

struct FieldLinkFtxResult {
    std::string text;
    float frequency_hz;
    float time_seconds;
    int score;
};

std::vector<FieldLinkFtxResult> fieldlink_decode_ftx(
    const float* samples,
    int sample_count,
    FieldLinkFtxProtocol protocol,
    int max_messages
);

