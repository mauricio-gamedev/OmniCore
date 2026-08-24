#include <jni.h>
#include <android/native_window_jni.h>
#include <memory>
#include <mutex>
#include <string>

#include "libretro_host.h"

namespace {
std::mutex gSessionMutex;
std::unique_ptr<LibretroSession> gSession;

std::string toString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::int16_t clampAxis(jint value) {
    if (value < -32768) value = -32768;
    if (value > 32767) value = 32767;
    return static_cast<std::int16_t>(value);
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeRuntimeVersion(
        JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF("OmniCore Native Runtime 0.9.4 / libretro host v8 / PS1 disk control / Modern Control Engine v2 / EGL-GLES presenter");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeHasPs1Core(
        JNIEnv* /* env */, jobject /* thiz */) {
    return probeLibretroCore("libpcsx_rearmed_libretro.so") ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeStartPs1(
        JNIEnv* env,
        jobject /* thiz */,
        jstring gamePath,
        jstring gameKey,
        jstring systemDir,
        jstring saveDir,
        jstring stateDir,
        jobject surface,
        jint performancePolicy,
        jint audioBufferBursts,
        jboolean tryExclusiveAudio,
        jboolean preferPowerEfficiency,
        jboolean aggressiveFramePacing,
        jstring coreOptions,
        jboolean dualShock) {
    if (!surface) return JNI_FALSE;

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;

    std::unique_ptr<LibretroSession> old;
    {
        std::lock_guard<std::mutex> lock(gSessionMutex);
        old = std::move(gSession);
    }
    if (old) old->stop();

    auto session = std::make_unique<LibretroSession>(
        "libpcsx_rearmed_libretro.so",
        toString(env, gamePath),
        toString(env, gameKey),
        toString(env, systemDir),
        toString(env, saveDir),
        toString(env, stateDir),
        window,
        RuntimePerformanceConfig {
            .policy = static_cast<int>(performancePolicy),
            .audioBufferBursts = static_cast<int>(audioBufferBursts),
            .tryExclusiveAudio = tryExclusiveAudio == JNI_TRUE,
            .preferPowerEfficiency = preferPowerEfficiency == JNI_TRUE,
            .aggressiveFramePacing = aggressiveFramePacing == JNI_TRUE
        },
        toString(env, coreOptions),
        dualShock == JNI_TRUE
    );

    // LibretroSession owns the ANativeWindow reference from this point onward.
    if (!session->start()) return JNI_FALSE;

    {
        std::lock_guard<std::mutex> lock(gSessionMutex);
        gSession = std::move(session);
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeStop(
        JNIEnv* /* env */, jobject /* thiz */) {
    std::unique_ptr<LibretroSession> session;
    {
        std::lock_guard<std::mutex> lock(gSessionMutex);
        session = std::move(gSession);
    }
    if (session) session->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeUpdatePerformancePolicy(
        JNIEnv* /* env */, jobject /* thiz */, jint performancePolicy, jint audioBufferBursts,
        jboolean tryExclusiveAudio, jboolean preferPowerEfficiency, jboolean aggressiveFramePacing) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (!gSession) return;
    gSession->updatePerformanceConfig(RuntimePerformanceConfig {
        .policy = static_cast<int>(performancePolicy),
        .audioBufferBursts = static_cast<int>(audioBufferBursts),
        .tryExclusiveAudio = tryExclusiveAudio == JNI_TRUE,
        .preferPowerEfficiency = preferPowerEfficiency == JNI_TRUE,
        .aggressiveFramePacing = aggressiveFramePacing == JNI_TRUE
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeIsRunning(
        JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    return (gSession && gSession->running()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeSetButton(
        JNIEnv* /* env */, jobject /* thiz */, jint id, jboolean pressed) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession && id >= 0 && id < 16) {
        gSession->setButton(static_cast<unsigned>(id), pressed == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeSetAnalog(
        JNIEnv* /* env */, jobject /* thiz */, jint stick, jint x, jint y) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (!gSession || stick < 0 || stick > 1) return;
    gSession->setAnalog(static_cast<unsigned>(stick), clampAxis(x), clampAxis(y));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeSetModernTankIntent(
        JNIEnv* /* env */, jobject /* thiz */, jint x, jint y, jboolean active) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (!gSession) return JNI_FALSE;
    return gSession->setModernTankIntent(clampAxis(x), clampAxis(y), active == JNI_TRUE)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeSaveState(
        JNIEnv* /* env */, jobject /* thiz */, jint slot) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->requestSaveState(slot);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeLoadState(
        JNIEnv* /* env */, jobject /* thiz */, jint slot) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->requestLoadState(slot);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeResetCheats(
        JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->requestCheatReset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeSetCheat(
        JNIEnv* env, jobject /* thiz */, jint index, jboolean enabled, jstring code) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (!gSession || index < 0 || index > 127 || !code) return;
    gSession->requestCheatSet(static_cast<unsigned>(index), enabled == JNI_TRUE, toString(env, code));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeDiskCount(
        JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    return gSession ? static_cast<jint>(gSession->diskCount()) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeDiskIndex(
        JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    return gSession ? static_cast<jint>(gSession->diskIndex()) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeDiskEjected(
        JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    return (gSession && gSession->diskEjected()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeSetDiskIndex(
        JNIEnv* /* env */, jobject /* thiz */, jint index) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (!gSession || index < 0) return JNI_FALSE;
    return gSession->requestDiskIndex(static_cast<int>(index)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeLastMessage(
        JNIEnv* env, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    const std::string value = gSession ? gSession->status() : "Parado";
    return env->NewStringUTF(value.c_str());
}
