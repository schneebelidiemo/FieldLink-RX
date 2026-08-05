#include "ftx_engine.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <string>
#include <vector>

extern "C" {
#include <ft8/constants.h>
#include <ft8/encode.h>
#include <ft8/message.h>
}

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kGfskConstant = 5.336446f;
constexpr int kSampleRate = 12000;

std::vector<float> synthesize(
    const std::string& text,
    FieldLinkFtxProtocol protocol,
    float frequency_hz = 1000.0f
) {
    ftx_message_t message{};
    if (ftx_message_encode(&message, nullptr, text.c_str()) != FTX_MESSAGE_RC_OK) {
        return {};
    }

    const bool ft4 = protocol == FieldLinkFtxProtocol::FT4;
    const int symbol_count = ft4 ? FT4_NN : FT8_NN;
    const float symbol_period = ft4 ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
    const float slot_period = ft4 ? FT4_SLOT_TIME : FT8_SLOT_TIME;
    const float symbol_bt = ft4 ? 1.0f : 2.0f;
    std::vector<uint8_t> tones(symbol_count);
    if (ft4) {
        ft4_encode(message.payload, tones.data());
    } else {
        ft8_encode(message.payload, tones.data());
    }

    const int samples_per_symbol = static_cast<int>(0.5f + kSampleRate * symbol_period);
    const int wave_samples = symbol_count * samples_per_symbol;
    const int slot_samples = static_cast<int>(slot_period * kSampleRate);
    const int leading_silence = (slot_samples - wave_samples) / 2;
    std::vector<float> output(slot_samples, 0.0f);
    std::vector<float> pulse(3 * samples_per_symbol);
    for (int index = 0; index < static_cast<int>(pulse.size()); ++index) {
        const float time = index / static_cast<float>(samples_per_symbol) - 1.5f;
        const float first = kGfskConstant * symbol_bt * (time + 0.5f);
        const float second = kGfskConstant * symbol_bt * (time - 0.5f);
        pulse[index] = (std::erf(first) - std::erf(second)) / 2.0f;
    }

    std::vector<float> phase_steps(
        wave_samples + 2 * samples_per_symbol,
        2.0f * kPi * frequency_hz / kSampleRate
    );
    const float peak_step = 2.0f * kPi / samples_per_symbol;
    for (int symbol = 0; symbol < symbol_count; ++symbol) {
        for (int index = 0; index < static_cast<int>(pulse.size()); ++index) {
            phase_steps[symbol * samples_per_symbol + index] += peak_step * tones[symbol] * pulse[index];
        }
    }
    for (int index = 0; index < 2 * samples_per_symbol; ++index) {
        phase_steps[index] += peak_step * pulse[index + samples_per_symbol] * tones.front();
        phase_steps[index + wave_samples] += peak_step * pulse[index] * tones.back();
    }

    float phase = 0.0f;
    for (int index = 0; index < wave_samples; ++index) {
        output[leading_silence + index] = std::sin(phase);
        phase = std::fmod(phase + phase_steps[index + samples_per_symbol], 2.0f * kPi);
    }
    return output;
}

bool verify(FieldLinkFtxProtocol protocol) {
    const std::string expected = "CQ K7IHZ DM43";
    const auto samples = synthesize(expected, protocol);
    const auto results = fieldlink_decode_ftx(samples.data(), static_cast<int>(samples.size()), protocol, 3);
    std::cout << (protocol == FieldLinkFtxProtocol::FT8 ? "FT8" : "FT4")
              << " decoded " << results.size() << " messages\n";
    for (const auto& result : results) {
        std::cout << "  " << result.text << " @ " << result.frequency_hz
                  << " Hz, score " << result.score << "\n";
    }
    return std::any_of(results.begin(), results.end(), [&](const auto& result) {
        return result.text == expected && std::abs(result.frequency_hz - 1000.0f) < 10.0f;
    });
}

bool verify_three_simultaneous(FieldLinkFtxProtocol protocol) {
    const std::vector<std::pair<std::string, float>> expected = {
        {"CQ K7IHZ DM43", 700.0f},
        {"CQ DL1ABC JO62", 1500.0f},
        {"CQ HB9XYZ JN47", 2300.0f},
    };
    std::vector<float> mixed;
    for (const auto& [text, frequency] : expected) {
        const auto signal = synthesize(text, protocol, frequency);
        if (signal.empty()) return false;
        if (mixed.empty()) mixed.assign(signal.size(), 0.0f);
        for (std::size_t index = 0; index < signal.size(); ++index) {
            mixed[index] += signal[index] / expected.size();
        }
    }

    const auto results = fieldlink_decode_ftx(
        mixed.data(),
        static_cast<int>(mixed.size()),
        protocol,
        3
    );
    std::cout << (protocol == FieldLinkFtxProtocol::FT8 ? "FT8" : "FT4")
              << " simultaneous decoded " << results.size() << " messages\n";
    return results.size() == expected.size() && std::all_of(
        expected.begin(),
        expected.end(),
        [&](const auto& wanted) {
            return std::any_of(results.begin(), results.end(), [&](const auto& result) {
                return result.text == wanted.first &&
                    std::abs(result.frequency_hz - wanted.second) < 10.0f;
            });
        }
    );
}
}  // namespace

int main() {
    if (!verify(FieldLinkFtxProtocol::FT8)) {
        std::cerr << "FT8 reference decode failed\n";
        return 1;
    }
    if (!verify(FieldLinkFtxProtocol::FT4)) {
        std::cerr << "FT4 reference decode failed\n";
        return 2;
    }
    if (!verify_three_simultaneous(FieldLinkFtxProtocol::FT8)) {
        std::cerr << "FT8 simultaneous reference decode failed\n";
        return 3;
    }
    if (!verify_three_simultaneous(FieldLinkFtxProtocol::FT4)) {
        std::cerr << "FT4 simultaneous reference decode failed\n";
        return 4;
    }
    std::cout << "FT8 and FT4 reference decodes passed\n";
    return 0;
}
