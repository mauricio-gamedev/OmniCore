#pragma once

#include "psp_libretro_abi.h"

#include <aaudio/AAudio.h>
#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>

namespace omnicore::psp {

struct RuntimeConfig {
    std::string romPath;
    std::string systemDir;
    std::string saveDir;
    unsigned internalResolution = 1;
};

class LibretroHost final {
public:
    static LibretroHost& instance();

    bool hasCore();
    bool start(ANativeWindow* window, RuntimeConfig config);
    void stop();
    void setPaused(bool paused);
    void setButton(unsigned retroPadId, bool pressed);
    void setAnalog(float x, float y);

    bool running() const { return running_.load(std::memory_order_acquire); }
    std::string lastMessage() const;

private:
    struct Api {
        void* handle = nullptr;
        unsigned (*apiVersion)() = nullptr;
        void (*setEnvironment)(retro_environment_t) = nullptr;
        void (*setVideoRefresh)(retro_video_refresh_t) = nullptr;
        void (*setAudioSample)(retro_audio_sample_t) = nullptr;
        void (*setAudioSampleBatch)(retro_audio_sample_batch_t) = nullptr;
        void (*setInputPoll)(retro_input_poll_t) = nullptr;
        void (*setInputState)(retro_input_state_t) = nullptr;
        void (*setControllerPortDevice)(unsigned, unsigned) = nullptr;
        void (*init)() = nullptr;
        void (*deinit)() = nullptr;
        void (*getSystemInfo)(retro_system_info*) = nullptr;
        void (*getSystemAvInfo)(retro_system_av_info*) = nullptr;
        bool (*loadGame)(const retro_game_info*) = nullptr;
        void (*unloadGame)() = nullptr;
        void (*run)() = nullptr;
    };

    static constexpr std::size_t kAudioCapacityFrames = 32768;

    LibretroHost() = default;
    ~LibretroHost();
    LibretroHost(const LibretroHost&) = delete;
    LibretroHost& operator=(const LibretroHost&) = delete;

    bool loadApi();
    void resetApi();
    bool initEgl();
    bool initPresenter();
    void presentFrame();
    void destroyPresenter();
    void destroyEgl();
    void startAudio();
    void stopAudio();
    void mixAudio(std::int16_t* destination, int32_t frames);
    std::size_t pushAudio(const std::int16_t* samples, std::size_t frames);
    void run();
    void markStartup(bool success, const std::string& message);
    void setMessage(const std::string& message);
    bool environment(unsigned cmd, void* data);
    void registerOptions(const retro_variable* variables);
    const char* optionValue(const char* key);

    static bool environmentCallback(unsigned cmd, void* data);
    static void videoRefreshCallback(const void* data, unsigned width, unsigned height, std::size_t pitch);
    static void audioSampleCallback(std::int16_t left, std::int16_t right);
    static std::size_t audioBatchCallback(const std::int16_t* data, std::size_t frames);
    static void inputPollCallback();
    static std::int16_t inputStateCallback(unsigned port, unsigned device, unsigned index, unsigned id);
    static std::uintptr_t currentFramebufferCallback();
    static abi::retro_proc_address_t procAddressCallback(const char* symbol);
    static void logCallback(enum retro_log_level level, const char* format, ...);
    static aaudio_data_callback_result_t audioDataCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    Api api_;
    RuntimeConfig config_;
    ANativeWindow* window_ = nullptr;
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    GLuint coreFramebuffer_ = 0;
    GLuint coreTexture_ = 0;
    GLuint presenterProgram_ = 0;
    GLint presenterPosition_ = -1;
    GLint presenterTexCoord_ = -1;
    GLint presenterSampler_ = -1;
    unsigned framebufferWidth_ = 480;
    unsigned framebufferHeight_ = 272;
    abi::retro_hw_render_callback hwRender_{};
    bool hwContextInitialized_ = false;

    std::thread thread_;
    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> running_{false};
    std::atomic<std::uint16_t> buttons_{0};
    std::atomic<std::int16_t> analogX_{0};
    std::atomic<std::int16_t> analogY_{0};
    std::atomic<double> targetFps_{59.94};

    AAudioStream* audioStream_ = nullptr;
    std::array<std::int16_t, kAudioCapacityFrames * 2> audioRing_{};
    std::atomic<std::uint32_t> audioRead_{0};
    std::atomic<std::uint32_t> audioWrite_{0};

    mutable std::mutex messageMutex_;
    std::string message_ = "OmniCore PSP runtime parado";
    std::mutex startupMutex_;
    std::condition_variable startupCondition_;
    bool startupComplete_ = false;
    bool startupSuccess_ = false;
    std::mutex optionMutex_;
    std::unordered_map<std::string, std::string> options_;

    static LibretroHost* active_;
};

}  // namespace omnicore::psp