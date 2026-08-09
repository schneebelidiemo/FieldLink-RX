#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <vector>

extern "C" {
#include "rtl-sdr.h"

RTLSDR_API int rtlsdr_open2(rtlsdr_dev_t** out_dev, int fd, const char* device_path);
}

namespace {

thread_local int lastOpenError = 0;

struct SdrHandle {
    rtlsdr_dev_t* device = nullptr;
};

struct CallbackContext {
    JNIEnv* env;
    jobject listener;
    jmethodID callback;
    rtlsdr_dev_t* device;
    bool failed = false;
};

SdrHandle* fromHandle(jlong handle) {
    return reinterpret_cast<SdrHandle*>(static_cast<intptr_t>(handle));
}

int setGainPercent(rtlsdr_dev_t* device, int percent) {
    const int count = rtlsdr_get_tuner_gains(device, nullptr);
    if (count <= 0) return count;
    std::vector<int> gains(static_cast<size_t>(count));
    const int result = rtlsdr_get_tuner_gains(device, gains.data());
    if (result < 0) return result;
    const int bounded = std::clamp(percent, 0, 100);
    const size_t index = static_cast<size_t>(bounded) * (gains.size() - 1) / 100;
    return rtlsdr_set_tuner_gain(device, gains[index]);
}

void iqCallback(unsigned char* buffer, uint32_t length, void* opaque) {
    auto* context = static_cast<CallbackContext*>(opaque);
    if (context->failed || length == 0) return;
    JNIEnv* env = context->env;
    jbyteArray data = env->NewByteArray(static_cast<jsize>(length));
    if (data == nullptr) {
        context->failed = true;
        rtlsdr_cancel_async(context->device);
        return;
    }
    env->SetByteArrayRegion(
        data,
        0,
        static_cast<jsize>(length),
        reinterpret_cast<const jbyte*>(buffer)
    );
    env->CallVoidMethod(context->listener, context->callback, data);
    env->DeleteLocalRef(data);
    if (env->ExceptionCheck()) {
        context->failed = true;
        rtlsdr_cancel_async(context->device);
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ch_fieldlink_rx_sdr_NativeRtlSdrBridge_open(
    JNIEnv* env,
    jobject,
    jint fileDescriptor,
    jstring devicePath
) {
    lastOpenError = 0;
    const char* path = env->GetStringUTFChars(devicePath, nullptr);
    if (path == nullptr) {
        lastOpenError = -1;
        return 0;
    }
    auto* handle = new SdrHandle();
    const int result = rtlsdr_open2(&handle->device, fileDescriptor, path);
    env->ReleaseStringUTFChars(devicePath, path);
    if (result < 0 || handle->device == nullptr) {
        lastOpenError = result == 0 ? -1 : result;
        if (handle->device != nullptr) rtlsdr_close(handle->device);
        delete handle;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_ch_fieldlink_rx_sdr_NativeRtlSdrBridge_lastOpenError(JNIEnv*, jobject) {
    return lastOpenError;
}

extern "C" JNIEXPORT jint JNICALL
Java_ch_fieldlink_rx_sdr_NativeRtlSdrBridge_configure(
    JNIEnv*,
    jobject,
    jlong nativeHandle,
    jlong frequencyHz,
    jint sampleRateHz,
    jboolean automaticGain,
    jint manualGainPercent,
    jint ppmCorrection
) {
    SdrHandle* handle = fromHandle(nativeHandle);
    if (handle == nullptr || handle->device == nullptr) return -1;
    rtlsdr_dev_t* device = handle->device;
    int result = rtlsdr_set_bias_tee(device, 0);
    if (result < 0) return result;
    result = rtlsdr_set_sample_rate(device, static_cast<uint32_t>(sampleRateHz));
    if (result < 0) return result;
    if (ppmCorrection != 0) {
        result = rtlsdr_set_freq_correction(device, ppmCorrection);
        if (result < 0) return result;
    }
    result = rtlsdr_set_center_freq(device, static_cast<uint32_t>(frequencyHz));
    if (result < 0) return result;
    result = rtlsdr_set_tuner_gain_mode(device, automaticGain ? 0 : 1);
    if (result < 0) return result;
    if (!automaticGain) {
        result = setGainPercent(device, manualGainPercent);
        if (result < 0) return result;
    }
    return rtlsdr_reset_buffer(device);
}

extern "C" JNIEXPORT jint JNICALL
Java_ch_fieldlink_rx_sdr_NativeRtlSdrBridge_run(
    JNIEnv* env,
    jobject,
    jlong nativeHandle,
    jobject listener
) {
    SdrHandle* handle = fromHandle(nativeHandle);
    if (handle == nullptr || handle->device == nullptr || listener == nullptr) return -1;
    jclass listenerClass = env->GetObjectClass(listener);
    if (listenerClass == nullptr) return -1;
    jmethodID callback = env->GetMethodID(listenerClass, "onIqSamples", "([B)V");
    env->DeleteLocalRef(listenerClass);
    if (callback == nullptr) return -1;
    CallbackContext context{env, listener, callback, handle->device, false};
    const int result = rtlsdr_read_async(handle->device, iqCallback, &context, 8, 65'536);
    return context.failed ? -1 : result;
}

extern "C" JNIEXPORT jint JNICALL
Java_ch_fieldlink_rx_sdr_NativeRtlSdrBridge_setFrequency(
    JNIEnv*, jobject, jlong nativeHandle, jlong frequencyHz
) {
    SdrHandle* handle = fromHandle(nativeHandle);
    if (handle == nullptr || handle->device == nullptr) return -1;
    return rtlsdr_set_center_freq(handle->device, static_cast<uint32_t>(frequencyHz));
}

extern "C" JNIEXPORT jint JNICALL
Java_ch_fieldlink_rx_sdr_NativeRtlSdrBridge_setGain(
    JNIEnv*, jobject, jlong nativeHandle, jboolean automaticGain, jint manualGainPercent
) {
    SdrHandle* handle = fromHandle(nativeHandle);
    if (handle == nullptr || handle->device == nullptr) return -1;
    int result = rtlsdr_set_tuner_gain_mode(handle->device, automaticGain ? 0 : 1);
    if (result < 0 || automaticGain) return result;
    return setGainPercent(handle->device, manualGainPercent);
}

extern "C" JNIEXPORT jint JNICALL
Java_ch_fieldlink_rx_sdr_NativeRtlSdrBridge_setPpm(
    JNIEnv*, jobject, jlong nativeHandle, jint ppmCorrection
) {
    SdrHandle* handle = fromHandle(nativeHandle);
    if (handle == nullptr || handle->device == nullptr) return -1;
    return rtlsdr_set_freq_correction(handle->device, ppmCorrection);
}

extern "C" JNIEXPORT void JNICALL
Java_ch_fieldlink_rx_sdr_NativeRtlSdrBridge_cancel(
    JNIEnv*,
    jobject,
    jlong nativeHandle
) {
    SdrHandle* handle = fromHandle(nativeHandle);
    if (handle != nullptr && handle->device != nullptr) rtlsdr_cancel_async(handle->device);
}

extern "C" JNIEXPORT void JNICALL
Java_ch_fieldlink_rx_sdr_NativeRtlSdrBridge_close(
    JNIEnv*,
    jobject,
    jlong nativeHandle
) {
    SdrHandle* handle = fromHandle(nativeHandle);
    if (handle == nullptr) return;
    if (handle->device != nullptr) {
        rtlsdr_close(handle->device);
        handle->device = nullptr;
    }
    delete handle;
}
