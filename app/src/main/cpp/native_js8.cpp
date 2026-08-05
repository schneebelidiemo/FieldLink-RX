#include <jni.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <exception>
#include <string>
#include <unordered_set>
#include <variant>
#include <vector>

#include <android/log.h>

#include "js8core/decoder.hpp"
#include "js8core/protocol/varicode.hpp"

namespace {

constexpr char kSeparator = '\x1f';
constexpr int kSampleRate = 12'000;
constexpr int kCaptureSeconds = 30;
constexpr int kCaptureSamples = kSampleRate * kCaptureSeconds;
constexpr int kDecoderBufferSamples = js8core::kJs8NtMax * js8core::kJs8RxSampleRate;

bool is_callsign_like(std::string const& token) {
    if (token.size() < 3 || token.size() > 12 || token.front() == '@') return false;
    bool has_digit = false;
    for (char c : token) {
        auto const value = static_cast<unsigned char>(c);
        if (std::isdigit(value)) {
            has_digit = true;
        } else if (!std::isalpha(value) && c != '/') {
            return false;
        }
    }
    return has_digit;
}

std::string maybe_insert_callsign_prefix(std::string const& text) {
    auto const separator = text.find_first_of(" \t\r\n");
    if (separator == std::string::npos) return text;
    auto const first = text.substr(0, separator);
    if (first.find(':') != std::string::npos) return text;

    auto const second_start = text.find_first_not_of(" \t\r\n", separator);
    if (second_start == std::string::npos) return text;
    auto const second_end = text.find_first_of(" \t\r\n", second_start);
    auto const second = text.substr(second_start, second_end - second_start);
    if (!is_callsign_like(first) || !is_callsign_like(second)) return text;
    return first + ": " + text.substr(second_start);
}

std::string render_decoded_text(js8core::events::Decoded const& decoded) {
    using namespace js8core::protocol::varicode;
    auto const& frame = decoded.data;
    if (frame.size() < 12 || frame.find(' ') != std::string::npos) return frame;

    try {
        if ((decoded.type & 0b100) != 0) {
            auto const data = unpack_fast_data_message(frame);
            return data.empty() ? frame : maybe_insert_callsign_prefix(data);
        }

        if (auto const data = unpack_data_message(frame); !data.empty()) {
            return maybe_insert_callsign_prefix(data);
        }

        std::uint8_t heartbeat_type = 0;
        bool alternate = false;
        std::uint8_t bits3 = 0;
        auto const heartbeat = unpack_heartbeat_message(
            frame,
            &heartbeat_type,
            &alternate,
            &bits3
        );
        if (!heartbeat.empty()) {
            auto const first = heartbeat.size() > 0 ? heartbeat[0] : std::string{};
            auto const second = heartbeat.size() > 1 ? heartbeat[1] : std::string{};
            auto const grid = heartbeat.size() > 2 ? heartbeat[2] : std::string{};
            auto callsign = first;
            if (!second.empty()) callsign += (callsign.empty() ? "" : "/") + second;
            static std::vector<std::string> const cq_strings = {
                "CQ CQ CQ", "CQ DX", "CQ QRP", "CQ CONTEST",
                "CQ FIELD", "CQ FD", "CQ CQ", "CQ",
            };
            auto text = callsign.empty() ? std::string{} : callsign + ": ";
            if (alternate) {
                auto const cq = bits3 < cq_strings.size() ? cq_strings[bits3] : "CQ";
                text += "@ALLCALL " + cq;
            } else {
                text += "@HB HEARTBEAT";
            }
            if (!grid.empty()) text += " " + grid;
            return text;
        }

        std::uint8_t compound_type = 0;
        std::uint16_t number = 0;
        std::uint8_t compound_bits = 0;
        auto const compound = unpack_compound_message(
            frame,
            &compound_type,
            &number,
            &compound_bits
        );
        if (!compound.empty()) {
            std::string text;
            for (auto part : compound) {
                part.erase(0, part.find_first_not_of(' '));
                if (part.empty()) continue;
                if (!text.empty()) text += " ";
                text += part;
            }
            if (!text.empty()) return text;
        }

        std::uint8_t directed_type = 0;
        auto const directed = unpack_directed_message(frame, &directed_type);
        if (!directed.empty()) {
            std::vector<std::string> parts;
            for (auto part : directed) {
                part.erase(0, part.find_first_not_of(' '));
                if (!part.empty()) parts.push_back(std::move(part));
            }
            if (!parts.empty()) {
                auto text = parts.front();
                if (parts.size() > 1) text += ": " + parts[1];
                for (std::size_t index = 2; index < parts.size(); ++index) {
                    text += " " + parts[index];
                }
                return text;
            }
        }
    } catch (std::exception const& error) {
        __android_log_print(ANDROID_LOG_WARN, "FieldLinkJS8", "Frame rendering failed: %s", error.what());
    }
    return frame;
}

struct Result {
    std::string text;
    float frequency = 0.0f;
    int snr = 0;
    float quality = 0.0f;
    int mode = 0;
    int type = 0;
    float time_offset = 0.0f;
};

std::string serialize(Result const& result) {
    return result.text + kSeparator +
        std::to_string(result.frequency) + kSeparator +
        std::to_string(result.snr) + kSeparator +
        std::to_string(result.quality) + kSeparator +
        std::to_string(result.mode) + kSeparator +
        std::to_string(result.type) + kSeparator +
        std::to_string(result.time_offset);
}

jobjectArray empty_array(JNIEnv* env) {
    auto const string_class = env->FindClass("java/lang/String");
    return env->NewObjectArray(0, string_class, nullptr);
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ch_fieldlink_rx_decoder_NativeJs8Bridge_decodeNative(
    JNIEnv* env,
    jobject,
    jfloatArray input,
    jint maximum_messages
) {
    if (input == nullptr || env->GetArrayLength(input) < kCaptureSamples) return empty_array(env);

    try {
        js8core::DecodeState state;
        state.samples.assign(kDecoderBufferSamples, 0);
        auto const samples = env->GetFloatArrayElements(input, nullptr);
        if (samples == nullptr) return empty_array(env);
        for (int index = 0; index < kCaptureSamples; ++index) {
            auto const scaled = std::round(std::clamp(samples[index], -1.0f, 1.0f) * 32767.0f);
            state.samples[index] = static_cast<std::int16_t>(scaled);
        }
        env->ReleaseFloatArrayElements(input, samples, JNI_ABORT);

        state.params.utc = 0;
        state.params.nfqso = 1500;
        state.params.newdat = true;
        state.params.nfa = 200;
        state.params.nfb = 3000;
        state.params.syncStats = false;
        state.params.kin = kCaptureSamples;
        state.params.kposE = 0;
        state.params.kszE = 30 * kSampleRate;
        state.params.kposA = 15 * kSampleRate;
        state.params.kszA = 15 * kSampleRate;
        state.params.kposB = 20 * kSampleRate;
        state.params.kszB = 10 * kSampleRate;
        state.params.kposC = 24 * kSampleRate;
        state.params.kszC = 6 * kSampleRate;
        state.params.kposI = 26 * kSampleRate;
        state.params.kszI = 4 * kSampleRate;
        state.params.nsubmodes = 0x1f;

        std::vector<Result> results;
        js8core::legacy_decode(state, [&](js8core::events::Variant const& event) {
            auto const decoded = std::get_if<js8core::events::Decoded>(&event);
            if (decoded == nullptr) return;
            results.push_back(Result{
                render_decoded_text(*decoded),
                decoded->frequency,
                decoded->snr,
                decoded->quality,
                decoded->mode,
                decoded->type,
                decoded->xdt,
            });
        });

        std::sort(results.begin(), results.end(), [](Result const& left, Result const& right) {
            if (left.quality != right.quality) return left.quality > right.quality;
            return left.snr > right.snr;
        });
        std::unordered_set<std::string> identities;
        std::vector<Result> unique;
        auto const limit = std::clamp(static_cast<int>(maximum_messages), 1, 3);
        for (auto const& result : results) {
            auto const identity = result.text + ":" + std::to_string(result.mode) + ":" +
                std::to_string(static_cast<int>(std::round(result.frequency / 2.0f)));
            if (identities.insert(identity).second) unique.push_back(result);
            if (static_cast<int>(unique.size()) >= limit) break;
        }

        auto const string_class = env->FindClass("java/lang/String");
        auto const output = env->NewObjectArray(static_cast<jsize>(unique.size()), string_class, nullptr);
        for (std::size_t index = 0; index < unique.size(); ++index) {
            auto const record = serialize(unique[index]);
            auto const value = env->NewStringUTF(record.c_str());
            env->SetObjectArrayElement(output, static_cast<jsize>(index), value);
            env->DeleteLocalRef(value);
        }
        return output;
    } catch (std::exception const& error) {
        __android_log_print(ANDROID_LOG_ERROR, "FieldLinkJS8", "Decode failed: %s", error.what());
        return empty_array(env);
    }
}
