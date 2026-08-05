#include "ftx_engine.h"

#include <jni.h>
#include <sstream>

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ch_fieldlink_rx_decoder_NativeFtxBridge_decodeNative(
    JNIEnv* environment,
    jobject,
    jfloatArray samples,
    jint protocol,
    jint max_messages
) {
    jclass string_class = environment->FindClass("java/lang/String");
    if (string_class == nullptr) {
        return nullptr;
    }
    if (samples == nullptr) {
        return environment->NewObjectArray(0, string_class, nullptr);
    }

    const jsize sample_count = environment->GetArrayLength(samples);
    jfloat* sample_data = environment->GetFloatArrayElements(samples, nullptr);
    if (sample_data == nullptr) {
        return environment->NewObjectArray(0, string_class, nullptr);
    }

    const auto mode = protocol == 0 ? FieldLinkFtxProtocol::FT4 : FieldLinkFtxProtocol::FT8;
    const auto results = fieldlink_decode_ftx(sample_data, sample_count, mode, max_messages);
    environment->ReleaseFloatArrayElements(samples, sample_data, JNI_ABORT);

    jobjectArray output = environment->NewObjectArray(
        static_cast<jsize>(results.size()),
        string_class,
        nullptr
    );
    for (std::size_t index = 0; index < results.size(); ++index) {
        const auto& result = results[index];
        std::ostringstream record;
        record << result.text << '\x1f' << result.frequency_hz << '\x1f'
               << result.score << '\x1f' << result.time_seconds;
        jstring value = environment->NewStringUTF(record.str().c_str());
        environment->SetObjectArrayElement(output, static_cast<jsize>(index), value);
        environment->DeleteLocalRef(value);
    }
    return output;
}
