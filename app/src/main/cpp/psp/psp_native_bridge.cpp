#include "psp_libretro_host.h"

#include <android/native_window_jni.h>
#include <jni.h>

#include <string>

namespace {
std::string fromJString(JNIEnv* env, jstring value) {
    if (!env || !value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_psp_PSPNativeBridge_nativeHasCore(JNIEnv*, jobject) {
    return omnicore::psp::LibretroHost::instance().hasCore() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_psp_PSPNativeBridge_nativeRuntimeInfo(JNIEnv* env, jobject) {
    const std::string value = std::string("OmniCore PSP Runtime • PPSSPP libretro v1.20.4 • ") +
        (omnicore::psp::LibretroHost::instance().hasCore() ? "core ready" : "core missing");
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_psp_PSPNativeBridge_nativeStart(
    JNIEnv* env,
    jobject,
    jobject surface,
    jstring romPath,
    jstring systemDir,
    jstring saveDir) {
    if (!surface) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;

    omnicore::psp::RuntimeConfig config;
    config.romPath = fromJString(env, romPath);
    config.systemDir = fromJString(env, systemDir);
    config.saveDir = fromJString(env, saveDir);
    const bool valid = !config.romPath.empty() && !config.systemDir.empty() && !config.saveDir.empty();
    const bool started = valid && omnicore::psp::LibretroHost::instance().start(window, std::move(config));
    ANativeWindow_release(window);
    return started ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_psp_PSPNativeBridge_nativeStop(JNIEnv*, jobject) {
    omnicore::psp::LibretroHost::instance().stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_psp_PSPNativeBridge_nativeSetPaused(JNIEnv*, jobject, jboolean paused) {
    omnicore::psp::LibretroHost::instance().setPaused(paused == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_psp_PSPNativeBridge_nativeIsRunning(JNIEnv*, jobject) {
    return omnicore::psp::LibretroHost::instance().running() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_psp_PSPNativeBridge_nativeLastMessage(JNIEnv* env, jobject) {
    const std::string message = omnicore::psp::LibretroHost::instance().lastMessage();
    return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_psp_PSPNativeBridge_nativeSetButton(
    JNIEnv*, jobject, jint button, jboolean pressed) {
    if (button >= 0) {
        omnicore::psp::LibretroHost::instance().setButton(
            static_cast<unsigned>(button), pressed == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_psp_PSPNativeBridge_nativeSetAnalog(
    JNIEnv*, jobject, jfloat x, jfloat y) {
    omnicore::psp::LibretroHost::instance().setAnalog(x, y);
}