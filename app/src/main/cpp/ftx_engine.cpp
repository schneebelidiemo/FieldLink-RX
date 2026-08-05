#include "ftx_engine.h"

#include <algorithm>
#include <array>
#include <cstring>

extern "C" {
#include <common/monitor.h>
#include <ft8/decode.h>
#include <ft8/message.h>
}

namespace {
constexpr int kSampleRate = 12000;
constexpr int kMinimumScore = 10;
constexpr int kMaximumCandidates = 140;
constexpr int kLdpcIterations = 25;
constexpr int kTimeOversampling = 2;
constexpr int kFrequencyOversampling = 2;

bool same_message(const ftx_message_t& left, const ftx_message_t& right) {
    return left.hash == right.hash &&
        std::memcmp(left.payload, right.payload, sizeof(left.payload)) == 0;
}
}  // namespace

std::vector<FieldLinkFtxResult> fieldlink_decode_ftx(
    const float* samples,
    int sample_count,
    FieldLinkFtxProtocol protocol,
    int max_messages
) {
    if (samples == nullptr || sample_count <= 0 || max_messages <= 0) {
        return {};
    }

    const ftx_protocol_t native_protocol = protocol == FieldLinkFtxProtocol::FT4
        ? FTX_PROTOCOL_FT4
        : FTX_PROTOCOL_FT8;
    monitor_config_t config{};
    config.f_min = 200.0f;
    config.f_max = 3000.0f;
    config.sample_rate = kSampleRate;
    config.time_osr = kTimeOversampling;
    config.freq_osr = kFrequencyOversampling;
    config.protocol = native_protocol;

    monitor_t monitor{};
    monitor_init(&monitor, &config);
    for (int position = 0; position + monitor.block_size <= sample_count; position += monitor.block_size) {
        monitor_process(&monitor, samples + position);
    }

    std::array<ftx_candidate_t, kMaximumCandidates> candidates{};
    const int candidate_count = ftx_find_candidates(
        &monitor.wf,
        static_cast<int>(candidates.size()),
        candidates.data(),
        kMinimumScore
    );

    std::vector<ftx_message_t> decoded_messages;
    std::vector<FieldLinkFtxResult> results;
    decoded_messages.reserve(max_messages * 2);
    results.reserve(max_messages * 2);

    for (int index = 0; index < candidate_count; ++index) {
        const ftx_candidate_t& candidate = candidates[index];
        ftx_message_t message{};
        ftx_decode_status_t status{};
        if (!ftx_decode_candidate(&monitor.wf, &candidate, kLdpcIterations, &message, &status)) {
            continue;
        }
        if (std::any_of(decoded_messages.begin(), decoded_messages.end(), [&](const auto& previous) {
                return same_message(previous, message);
            })) {
            continue;
        }

        char text[FTX_MAX_MESSAGE_LENGTH]{};
        ftx_message_offsets_t offsets{};
        if (ftx_message_decode(&message, nullptr, text, &offsets) != FTX_MESSAGE_RC_OK) {
            continue;
        }

        const float frequency = (
            monitor.min_bin + candidate.freq_offset +
            static_cast<float>(candidate.freq_sub) / monitor.wf.freq_osr
        ) / monitor.symbol_period;
        const float time = (
            candidate.time_offset + static_cast<float>(candidate.time_sub) / monitor.wf.time_osr
        ) * monitor.symbol_period;

        decoded_messages.push_back(message);
        results.push_back({text, frequency, time, candidate.score});
    }

    monitor_free(&monitor);
    std::stable_sort(results.begin(), results.end(), [](const auto& left, const auto& right) {
        return left.score > right.score;
    });
    if (results.size() > static_cast<std::size_t>(max_messages)) {
        results.resize(max_messages);
    }
    return results;
}
