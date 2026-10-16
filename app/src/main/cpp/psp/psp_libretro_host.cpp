#include "psp_libretro_host.h"

#include <android/log.h>
#include <android/native_window_jni.h>
#include <EGL/eglext.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <dlfcn.h>
#include <chrono>
#include <thread>
#include <utility>

namespace omnicore::psp {
namespace {

constexpr char kCoreSoname[] = "libppsspp_libretro_android.so";
constexpr unsigned kGetVariableCommand = RETRO_ENVIRONMENT_GET_VARIABLE;
constexpr unsigned kSetVariablesCommand = RETRO_ENVIRONMENT_SET_VARIABLES;
constexpr unsigned kGetVariableUpdateCommand = RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE;
constexpr unsigned kSetPixelFormatCommand = RETRO_ENVIRONMENT_SET_PIXEL_FORMAT;
constexpr unsigned kGetPreferredHwRenderCommand = 56;
constexpr unsigned kGetSystemDirectoryCommand = RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY;
constexpr unsigned kGetSaveDirectoryCommand = RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY;
constexpr unsigned kGetContentDirectoryCommand = RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY;
constexpr unsigned kGetLogInterfaceCommand = RETRO_ENVIRONMENT_GET_LOG_INTERFACE;
constexpr unsigned kGetCanDupeCommand = RETRO_ENVIRONMENT_GET_CAN_DUPE;
constexpr unsigned kSetInputDescriptorsCommand = RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS;
constexpr unsigned kSetMessageCommand = RETRO_ENVIRONMENT_SET_MESSAGE;
constexpr unsigned kGetCoreOptionsVersionCommand = RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION;
constexpr unsigned kSetCoreOptionsCommand = RETRO_ENVIRONMENT_SET_CORE_OPTIONS;
constexpr unsigned kSetCoreOptionsIntlCommand = RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL;
constexpr unsigned kSetCoreOptionsDisplayCommand = RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY;
constexpr unsigned kGetLanguageCommand = RETRO_ENVIRONMENT_GET_LANGUAGE;
constexpr unsigned kSetSystemAvInfoCommand = RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO;
constexpr unsigned kSetGeometryCommand = RETRO_ENVIRONMENT_SET_GEOMETRY;
constexpr unsigned kGetMessageInterfaceVersionCommand = 59;
constexpr unsigned kGetInputMaxUsersCommand = RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS;
constexpr unsigned kSetControllerInfoCommand = RETRO_ENVIRONMENT_SET_CONTROLLER_INFO;
constexpr unsigned kGetInputBitmasksCommand = RETRO_ENVIRONMENT_GET_INPUT_BITMASKS;
constexpr unsigned kSetPerformanceLevelCommand = RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL;
constexpr unsigned kSetAudioBufferStatusCommand = 62;
constexpr unsigned kSetMinimumAudioLatencyCommand = 63;
constexpr unsigned kGetAudioVideoEnableCommand = abi::RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE;
constexpr unsigned kGetFastForwardingCommand = abi::RETRO_ENVIRONMENT_GET_FASTFORWARDING;
constexpr unsigned kGetTargetRefreshRateCommand = abi::RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE;
constexpr unsigned kSetCoreOptionsV2Command = 67;
constexpr unsigned kSetCoreOptionsV2IntlCommand = 68;
constexpr unsigned kSetCoreOptionsUpdateDisplayCommand = 69;

std::string trim(std::string value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    const auto last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

bool requiredSymbol(void* handle, const char* name) {
    return handle != nullptr && dlsym(handle, name) != nullptr;
}

int androidPriority(enum retro_log_level level) {
    switch (level) {
        case RETRO_LOG_DEBUG: return ANDROID_LOG_DEBUG;
        case RETRO_LOG_INFO: return ANDROID_LOG_INFO;
        case RETRO_LOG_WARN: return ANDROID_LOG_WARN;
        case RETRO_LOG_ERROR: return ANDROID_LOG_ERROR;
        default: return ANDROID_LOG_INFO;
    }
}

GLuint compilePresenterShader(GLenum type, const char* source, std::string& error) {
    const GLuint shader = glCreateShader(type);
    if (shader == 0) {
        error = "glCreateShader retornou zero";
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[1024]{};
        GLsizei length = 0;
        glGetShaderInfoLog(shader, static_cast<GLsizei>(sizeof(log)), &length, log);
        error = length > 0 ? std::string(log, static_cast<std::size_t>(length))
                           : "erro de compilação sem log";
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

}  // namespace

LibretroHost* LibretroHost::active_ = nullptr;

LibretroHost& LibretroHost::instance() {
    static LibretroHost host;
    return host;
}

LibretroHost::~LibretroHost() {
    stop();
}

bool LibretroHost::hasCore() {
    void* handle = dlopen(kCoreSoname, RTLD_NOW | RTLD_LOCAL);
    if (!handle) return false;
    const bool valid =
        requiredSymbol(handle, "retro_api_version") &&
        requiredSymbol(handle, "retro_set_environment") &&
        requiredSymbol(handle, "retro_init") &&
        requiredSymbol(handle, "retro_load_game") &&
        requiredSymbol(handle, "retro_run");
    dlclose(handle);
    return valid;
}

bool LibretroHost::start(ANativeWindow* window, RuntimeConfig config) {
    stop();
    if (!window || config.romPath.empty() || config.systemDir.empty() || config.saveDir.empty()) {
        setMessage("PSP: argumentos de inicialização inválidos.");
        return false;
    }

    ANativeWindow_acquire(window);
    window_ = window;
    config_ = std::move(config);
    stopRequested_.store(false, std::memory_order_release);
    paused_.store(false, std::memory_order_release);
    running_.store(false, std::memory_order_release);
    buttons_.store(0, std::memory_order_release);
    analogX_.store(0, std::memory_order_release);
    analogY_.store(0, std::memory_order_release);
    audioRead_.store(0, std::memory_order_release);
    audioWrite_.store(0, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(startupMutex_);
        startupComplete_ = false;
        startupSuccess_ = false;
    }

    setMessage("Carregando PPSSPP...");
    thread_ = std::thread(&LibretroHost::run, this);

    bool success = false;
    {
        std::unique_lock<std::mutex> lock(startupMutex_);
        const bool completed = startupCondition_.wait_for(
            lock, std::chrono::seconds(25), [this] { return startupComplete_; });
        success = completed && startupSuccess_;
    }
    if (!success) {
        stopRequested_.store(true, std::memory_order_release);
        startupCondition_.notify_all();
        stop();
    }
    return success;
}

void LibretroHost::stop() {
    stopRequested_.store(true, std::memory_order_release);
    startupCondition_.notify_all();
    if (thread_.joinable() && thread_.get_id() != std::this_thread::get_id()) {
        thread_.join();
    }
    running_.store(false, std::memory_order_release);
}

void LibretroHost::setPaused(bool paused) {
    paused_.store(paused, std::memory_order_release);
}

void LibretroHost::setButton(unsigned retroPadId, bool pressed) {
    if (retroPadId >= 16) return;
    const auto bit = static_cast<std::uint16_t>(1u << retroPadId);
    if (pressed) {
        buttons_.fetch_or(bit, std::memory_order_acq_rel);
    } else {
        buttons_.fetch_and(static_cast<std::uint16_t>(~bit), std::memory_order_acq_rel);
    }
}

void LibretroHost::setAnalog(float x, float y) {
    const auto sx = static_cast<std::int16_t>(std::lround(std::clamp(x, -1.0f, 1.0f) * 32767.0f));
    const auto sy = static_cast<std::int16_t>(std::lround(std::clamp(y, -1.0f, 1.0f) * 32767.0f));
    analogX_.store(sx, std::memory_order_release);
    analogY_.store(sy, std::memory_order_release);
}

std::string LibretroHost::lastMessage() const {
    std::lock_guard<std::mutex> lock(messageMutex_);
    return message_;
}

void LibretroHost::setMessage(const std::string& message) {
    std::lock_guard<std::mutex> lock(messageMutex_);
    message_ = message;
}

bool LibretroHost::loadApi() {
    api_.handle = dlopen(kCoreSoname, RTLD_NOW | RTLD_LOCAL);
    if (!api_.handle) {
        const char* error = dlerror();
        setMessage(std::string("PPSSPP não carregou: ") + (error ? error : "biblioteca ausente"));
        return false;
    }

#define OMNI_LOAD_REQUIRED(field, symbol) \
    api_.field = reinterpret_cast<decltype(api_.field)>(dlsym(api_.handle, symbol)); \
    if (!api_.field) { setMessage(std::string("Símbolo PPSSPP ausente: ") + symbol); resetApi(); return false; }

    OMNI_LOAD_REQUIRED(apiVersion, "retro_api_version")
    OMNI_LOAD_REQUIRED(setEnvironment, "retro_set_environment")
    OMNI_LOAD_REQUIRED(setVideoRefresh, "retro_set_video_refresh")
    OMNI_LOAD_REQUIRED(setAudioSample, "retro_set_audio_sample")
    OMNI_LOAD_REQUIRED(setAudioSampleBatch, "retro_set_audio_sample_batch")
    OMNI_LOAD_REQUIRED(setInputPoll, "retro_set_input_poll")
    OMNI_LOAD_REQUIRED(setInputState, "retro_set_input_state")
    OMNI_LOAD_REQUIRED(init, "retro_init")
    OMNI_LOAD_REQUIRED(deinit, "retro_deinit")
    OMNI_LOAD_REQUIRED(getSystemInfo, "retro_get_system_info")
    OMNI_LOAD_REQUIRED(getSystemAvInfo, "retro_get_system_av_info")
    OMNI_LOAD_REQUIRED(loadGame, "retro_load_game")
    OMNI_LOAD_REQUIRED(unloadGame, "retro_unload_game")
    OMNI_LOAD_REQUIRED(run, "retro_run")
#undef OMNI_LOAD_REQUIRED

    api_.setControllerPortDevice =
        reinterpret_cast<decltype(api_.setControllerPortDevice)>(
            dlsym(api_.handle, "retro_set_controller_port_device"));
    if (api_.apiVersion() != 1) {
        setMessage("Versão da ABI libretro do PPSSPP incompatível.");
        resetApi();
        return false;
    }
    return true;
}

void LibretroHost::resetApi() {
    if (api_.handle) dlclose(api_.handle);
    api_ = {};
}

bool LibretroHost::initEgl() {
    if (!window_) return false;
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        setMessage("PSP: eglGetDisplay falhou.");
        return false;
    }
    EGLint major = 0;
    EGLint minor = 0;
    if (eglInitialize(display_, &major, &minor) != EGL_TRUE) {
        setMessage("PSP: eglInitialize falhou.");
        display_ = EGL_NO_DISPLAY;
        return false;
    }

    const EGLint configAttributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_STENCIL_SIZE, 0,
        EGL_NONE
    };
    EGLConfig config = nullptr;
    EGLint count = 0;
    if (eglChooseConfig(display_, configAttributes, &config, 1, &count) != EGL_TRUE || count < 1) {
        setMessage("PSP: não foi possível escolher uma configuração EGL ES2.");
        destroyEgl();
        return false;
    }

    EGLint nativeFormat = 0;
    eglGetConfigAttrib(display_, config, EGL_NATIVE_VISUAL_ID, &nativeFormat);
    if (nativeFormat != 0) {
        ANativeWindow_setBuffersGeometry(window_, 0, 0, nativeFormat);
    }
    eglSurface_ = eglCreateWindowSurface(display_, config, window_, nullptr);
    if (eglSurface_ == EGL_NO_SURFACE) {
        setMessage("PSP: eglCreateWindowSurface falhou.");
        destroyEgl();
        return false;
    }

    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    eglContext_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, contextAttributes);
    if (eglContext_ == EGL_NO_CONTEXT) {
        setMessage("PSP: eglCreateContext ES2 falhou.");
        destroyEgl();
        return false;
    }
    if (eglMakeCurrent(display_, eglSurface_, eglSurface_, eglContext_) != EGL_TRUE) {
        setMessage("PSP: eglMakeCurrent falhou.");
        destroyEgl();
        return false;
    }
    eglSwapInterval(display_, 0);
    return true;
}

bool LibretroHost::initPresenter() {
    if (display_ == EGL_NO_DISPLAY || eglContext_ == EGL_NO_CONTEXT) {
        setMessage("PSP: contexto EGL ausente para inicializar o apresentador.");
        return false;
    }

    const unsigned scale = std::clamp(config_.internalResolution, 1u, 2u);
    framebufferWidth_ = 480u * scale;
    framebufferHeight_ = 272u * scale;

    static constexpr char vertexSource[] =
        "attribute vec2 aPosition;\n"
        "attribute vec2 aTexCoord;\n"
        "varying vec2 vTexCoord;\n"
        "void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vTexCoord = aTexCoord; }\n";
    static constexpr char fragmentSource[] =
        "precision mediump float;\n"
        "uniform sampler2D uFrame;\n"
        "varying vec2 vTexCoord;\n"
        "void main() { gl_FragColor = texture2D(uFrame, vTexCoord); }\n";

    std::string shaderError;
    const GLuint vertexShader = compilePresenterShader(GL_VERTEX_SHADER, vertexSource, shaderError);
    if (vertexShader == 0) {
        setMessage("PSP: falha no shader de apresentação: " + shaderError);
        return false;
    }
    const GLuint fragmentShader = compilePresenterShader(GL_FRAGMENT_SHADER, fragmentSource, shaderError);
    if (fragmentShader == 0) {
        glDeleteShader(vertexShader);
        setMessage("PSP: falha no shader de imagem: " + shaderError);
        return false;
    }

    presenterProgram_ = glCreateProgram();
    if (presenterProgram_ == 0) {
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        setMessage("PSP: não foi possível criar o programa de apresentação.");
        return false;
    }
    glAttachShader(presenterProgram_, vertexShader);
    glAttachShader(presenterProgram_, fragmentShader);
    glBindAttribLocation(presenterProgram_, 0, "aPosition");
    glBindAttribLocation(presenterProgram_, 1, "aTexCoord");
    glLinkProgram(presenterProgram_);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    GLint linked = GL_FALSE;
    glGetProgramiv(presenterProgram_, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[1024]{};
        GLsizei length = 0;
        glGetProgramInfoLog(presenterProgram_, static_cast<GLsizei>(sizeof(log)), &length, log);
        setMessage("PSP: falha ao vincular shader de apresentação: " +
            (length > 0 ? std::string(log, static_cast<std::size_t>(length)) : std::string("sem log")));
        destroyPresenter();
        return false;
    }

    presenterPosition_ = glGetAttribLocation(presenterProgram_, "aPosition");
    presenterTexCoord_ = glGetAttribLocation(presenterProgram_, "aTexCoord");
    presenterSampler_ = glGetUniformLocation(presenterProgram_, "uFrame");
    if (presenterPosition_ < 0 || presenterTexCoord_ < 0 || presenterSampler_ < 0) {
        setMessage("PSP: o shader de apresentação não expôs os atributos esperados.");
        destroyPresenter();
        return false;
    }

    glGenTextures(1, &coreTexture_);
    glBindTexture(GL_TEXTURE_2D, coreTexture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA,
        static_cast<GLsizei>(framebufferWidth_),
        static_cast<GLsizei>(framebufferHeight_),
        0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    glGenFramebuffers(1, &coreFramebuffer_);
    glBindFramebuffer(GL_FRAMEBUFFER, coreFramebuffer_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, coreTexture_, 0);
    const GLenum framebufferStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);

    if (coreFramebuffer_ == 0 || coreTexture_ == 0 || framebufferStatus != GL_FRAMEBUFFER_COMPLETE) {
        setMessage("PSP: framebuffer de apresentação incompleto (status " +
            std::to_string(framebufferStatus) + ").");
        destroyPresenter();
        return false;
    }
    return true;
}

void LibretroHost::presentFrame() {
    if (presenterProgram_ == 0 || coreFramebuffer_ == 0 || coreTexture_ == 0 ||
        display_ == EGL_NO_DISPLAY || eglSurface_ == EGL_NO_SURFACE) {
        return;
    }

    EGLint surfaceWidth = 0;
    EGLint surfaceHeight = 0;
    if (eglQuerySurface(display_, eglSurface_, EGL_WIDTH, &surfaceWidth) != EGL_TRUE ||
        eglQuerySurface(display_, eglSurface_, EGL_HEIGHT, &surfaceHeight) != EGL_TRUE ||
        surfaceWidth <= 0 || surfaceHeight <= 0) {
        return;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, surfaceWidth, surfaceHeight);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    const float sourceAspect =
        static_cast<float>(framebufferWidth_) / static_cast<float>(framebufferHeight_);
    const float surfaceAspect = static_cast<float>(surfaceWidth) / static_cast<float>(surfaceHeight);
    float scaleX = 1.0f;
    float scaleY = 1.0f;
    if (surfaceAspect > sourceAspect) {
        scaleX = sourceAspect / surfaceAspect;
    } else {
        scaleY = surfaceAspect / sourceAspect;
    }

    const GLfloat vertices[] = {
        -scaleX, -scaleY, 0.0f, 0.0f,
         scaleX, -scaleY, 1.0f, 0.0f,
        -scaleX,  scaleY, 0.0f, 1.0f,
         scaleX,  scaleY, 1.0f, 1.0f
    };
    glUseProgram(presenterProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, coreTexture_);
    glUniform1i(presenterSampler_, 0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glEnableVertexAttribArray(static_cast<GLuint>(presenterPosition_));
    glEnableVertexAttribArray(static_cast<GLuint>(presenterTexCoord_));
    glVertexAttribPointer(
        static_cast<GLuint>(presenterPosition_), 2, GL_FLOAT, GL_FALSE,
        4 * static_cast<GLsizei>(sizeof(GLfloat)), vertices);
    glVertexAttribPointer(
        static_cast<GLuint>(presenterTexCoord_), 2, GL_FLOAT, GL_FALSE,
        4 * static_cast<GLsizei>(sizeof(GLfloat)), vertices + 2);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(static_cast<GLuint>(presenterPosition_));
    glDisableVertexAttribArray(static_cast<GLuint>(presenterTexCoord_));
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);

    // The core renders into an OmniCore-owned off-screen framebuffer on the
    // next retro_run; only the final scaled quad touches the Android surface.
    glBindFramebuffer(GL_FRAMEBUFFER, coreFramebuffer_);
    glViewport(0, 0, static_cast<GLsizei>(framebufferWidth_),
        static_cast<GLsizei>(framebufferHeight_));
}

bool LibretroHost::configureCoreFramebuffer(bool depth, bool stencil) {
    if (coreFramebuffer_ == 0) return false;
    glBindFramebuffer(GL_FRAMEBUFFER, coreFramebuffer_);

    if (!depth) {
        if (coreDepthBuffer_ != 0) {
            glDeleteRenderbuffers(1, &coreDepthBuffer_);
            coreDepthBuffer_ = 0;
        }
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, 0);
    } else {
        if (coreDepthBuffer_ == 0) glGenRenderbuffers(1, &coreDepthBuffer_);
        if (coreDepthBuffer_ == 0) {
            setMessage("PSP: não foi possível alocar o buffer de profundidade.");
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            return false;
        }
        glBindRenderbuffer(GL_RENDERBUFFER, coreDepthBuffer_);
        GLenum storageFormat = GL_DEPTH_COMPONENT16;
        if (stencil) {
#ifdef GL_DEPTH24_STENCIL8_OES
            const char* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
            const bool packedDepthStencil =
                extensions && std::strstr(extensions, "GL_OES_packed_depth_stencil");
            if (!packedDepthStencil) {
                setMessage("PSP: PPSSPP solicitou depth/stencil, mas o driver não oferece depth-stencil empacotado.");
                glBindRenderbuffer(GL_RENDERBUFFER, 0);
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                return false;
            }
            storageFormat = GL_DEPTH24_STENCIL8_OES;
#else
            setMessage("PSP: este build não expõe suporte a depth-stencil empacotado.");
            glBindRenderbuffer(GL_RENDERBUFFER, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            return false;
#endif
        }
        glRenderbufferStorage(
            GL_RENDERBUFFER, storageFormat,
            static_cast<GLsizei>(framebufferWidth_),
            static_cast<GLsizei>(framebufferHeight_));
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, coreDepthBuffer_);
        if (stencil) {
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, coreDepthBuffer_);
        } else {
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, 0);
        }
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
    }

    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glViewport(0, 0, static_cast<GLsizei>(framebufferWidth_),
        static_cast<GLsizei>(framebufferHeight_));
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        setMessage("PSP: framebuffer PPSSPP incompleto após configurar depth/stencil (status " +
            std::to_string(status) + ").");
    }
    return status == GL_FRAMEBUFFER_COMPLETE;
}

void LibretroHost::destroyPresenter() {
    if (presenterProgram_ != 0) glDeleteProgram(presenterProgram_);
    if (coreFramebuffer_ != 0) glDeleteFramebuffers(1, &coreFramebuffer_);
    if (coreTexture_ != 0) glDeleteTextures(1, &coreTexture_);
    if (coreDepthBuffer_ != 0) glDeleteRenderbuffers(1, &coreDepthBuffer_);
    presenterProgram_ = 0;
    coreFramebuffer_ = 0;
    coreTexture_ = 0;
    coreDepthBuffer_ = 0;
    presenterPosition_ = -1;
    presenterTexCoord_ = -1;
    presenterSampler_ = -1;
}

void LibretroHost::destroyEgl() {
    destroyPresenter();
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (eglSurface_ != EGL_NO_SURFACE) {
            eglDestroySurface(display_, eglSurface_);
            eglSurface_ = EGL_NO_SURFACE;
        }
        if (eglContext_ != EGL_NO_CONTEXT) {
            eglDestroyContext(display_, eglContext_);
            eglContext_ = EGL_NO_CONTEXT;
        }
        eglTerminate(display_);
        display_ = EGL_NO_DISPLAY;
    }
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

void LibretroHost::startAudio() {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || !builder) {
        setMessage("PPSSPP ativo; saída de áudio indisponível.");
        return;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 44100);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    // Keep the platform defaults here: these setters were added in API 28,
    // while OmniCore still supports API 26. The defaults are media/music and
    // avoid raising the minimum native API level for the PSP-only runtime.
    AAudioStreamBuilder_setDataCallback(builder, &LibretroHost::audioDataCallback, this);

    const aaudio_result_t opened = AAudioStreamBuilder_openStream(builder, &audioStream_);
    AAudioStreamBuilder_delete(builder);
    if (opened != AAUDIO_OK || !audioStream_) {
        audioStream_ = nullptr;
        setMessage("PPSSPP ativo; não foi possível abrir a saída de áudio.");
        return;
    }
    const aaudio_result_t started = AAudioStream_requestStart(audioStream_);
    if (started != AAUDIO_OK) {
        AAudioStream_close(audioStream_);
        audioStream_ = nullptr;
        setMessage("PPSSPP ativo; a saída de áudio não iniciou.");
    }
}

void LibretroHost::stopAudio() {
    if (!audioStream_) return;
    AAudioStream_requestStop(audioStream_);
    AAudioStream_close(audioStream_);
    audioStream_ = nullptr;
}

void LibretroHost::mixAudio(std::int16_t* destination, int32_t frames) {
    if (!destination || frames <= 0) return;
    auto read = audioRead_.load(std::memory_order_relaxed);
    const auto write = audioWrite_.load(std::memory_order_acquire);
    for (int32_t frame = 0; frame < frames; ++frame) {
        if (read != write) {
            const std::size_t index = static_cast<std::size_t>(read % kAudioCapacityFrames) * 2;
            destination[frame * 2] = audioRing_[index];
            destination[frame * 2 + 1] = audioRing_[index + 1];
            ++read;
        } else {
            destination[frame * 2] = 0;
            destination[frame * 2 + 1] = 0;
        }
    }
    audioRead_.store(read, std::memory_order_release);
}

std::size_t LibretroHost::pushAudio(const std::int16_t* samples, std::size_t frames) {
    if (!samples || frames == 0) return 0;
    auto write = audioWrite_.load(std::memory_order_relaxed);
    const auto read = audioRead_.load(std::memory_order_acquire);
    std::size_t accepted = 0;
    for (std::size_t frame = 0; frame < frames; ++frame) {
        if (static_cast<std::uint32_t>(write - read) >= kAudioCapacityFrames) break;
        const std::size_t index = static_cast<std::size_t>(write % kAudioCapacityFrames) * 2;
        audioRing_[index] = samples[frame * 2];
        audioRing_[index + 1] = samples[frame * 2 + 1];
        ++write;
        ++accepted;
    }
    audioWrite_.store(write, std::memory_order_release);
    return accepted;
}

void LibretroHost::markStartup(bool success, const std::string& message) {
    if (!message.empty()) setMessage(message);
    {
        std::lock_guard<std::mutex> lock(startupMutex_);
        if (startupComplete_) return;
        startupSuccess_ = success;
        startupComplete_ = true;
    }
    startupCondition_.notify_all();
}

void LibretroHost::registerOptions(const retro_variable* variables) {
    if (!variables) return;
    std::lock_guard<std::mutex> lock(optionMutex_);
    for (std::size_t i = 0; variables[i].key && i < 512; ++i) {
        const char* rawValue = variables[i].value;
        if (!rawValue) continue;
        std::string declaration(rawValue);
        const auto delimiter = declaration.find(';');
        if (delimiter == std::string::npos) continue;
        std::string choices = trim(declaration.substr(delimiter + 1));
        const auto separator = choices.find('|');
        std::string selected = trim(choices.substr(0, separator));
        if (!selected.empty()) options_[variables[i].key] = selected;
    }
    const unsigned scale = std::clamp(config_.internalResolution, 1u, 2u);
    options_["ppsspp_backend"] = "opengl";
    options_["ppsspp_cpu_core"] = "JIT";
    options_["ppsspp_internal_resolution"] =
        std::to_string(480u * scale) + "x" + std::to_string(272u * scale);
    options_["ppsspp_software_rendering"] = "disabled";
    options_["ppsspp_frameskip"] = "disabled";
    options_["ppsspp_auto_frameskip"] = "disabled";
    options_["ppsspp_cropto16x9"] = "disabled";
    options_["ppsspp_cache_iso"] = "disabled";
    options_["ppsspp_skip_buffer_effects"] = "disabled";
    options_["ppsspp_skip_gpu_readbacks"] = "disabled";
    options_["ppsspp_lazy_texture_caching"] = "disabled";
    options_["ppsspp_gpu_hardware_transform"] = "enabled";
}

const char* LibretroHost::optionValue(const char* key) {
    if (!key) return nullptr;
    std::lock_guard<std::mutex> lock(optionMutex_);
    auto found = options_.find(key);
    return found == options_.end() ? nullptr : found->second.c_str();
}

bool LibretroHost::environment(unsigned cmd, void* data) {
    switch (cmd) {
        case kGetSystemDirectoryCommand:
        case kGetContentDirectoryCommand:
            if (!data) return false;
            *static_cast<const char**>(data) = config_.systemDir.c_str();
            return true;
        case kGetSaveDirectoryCommand:
            if (!data) return false;
            *static_cast<const char**>(data) = config_.saveDir.c_str();
            return true;
        case kGetVariableCommand: {
            if (!data) return false;
            auto* variable = static_cast<retro_variable*>(data);
            const char* value = optionValue(variable->key);
            variable->value = value;
            return value != nullptr;
        }
        case kSetVariablesCommand:
            registerOptions(static_cast<const retro_variable*>(data));
            return data != nullptr;
        case kSetPixelFormatCommand:
            return data && *static_cast<retro_pixel_format*>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
        case kGetPreferredHwRenderCommand:
            if (!data) return false;
            *static_cast<abi::retro_hw_context_type*>(data) = abi::RETRO_HW_CONTEXT_OPENGLES2;
            return true;
        case abi::RETRO_ENVIRONMENT_SET_HW_RENDER: {
            if (!data || display_ == EGL_NO_DISPLAY || eglContext_ == EGL_NO_CONTEXT) return false;
            auto* callback = static_cast<abi::retro_hw_render_callback*>(data);
            if (callback->context_type != abi::RETRO_HW_CONTEXT_OPENGLES2 &&
                callback->context_type != abi::RETRO_HW_CONTEXT_OPENGLES3) {
                setMessage("PPSSPP solicitou um contexto gráfico incompatível com este host.");
                return false;
            }
            // The libretro frontend contract requires writing these callbacks
            // into the exact structure supplied by the core. PPSSPP reads them
            // back immediately after RETRO_ENVIRONMENT_SET_HW_RENDER returns.
            callback->get_current_framebuffer = &LibretroHost::currentFramebufferCallback;
            callback->get_proc_address = &LibretroHost::procAddressCallback;
            hwRender_ = *callback;
            if (!configureCoreFramebuffer(callback->depth, callback->stencil)) {
                return false;
            }
            // PPSSPP's reset callback dereferences its global graphics context.
            // That pointer is assigned only after retro_load_game returns.
            return true;
        }
        case kGetLogInterfaceCommand:
            if (!data) return false;
            static_cast<retro_log_callback*>(data)->log = &LibretroHost::logCallback;
            return true;
        case kGetCanDupeCommand:
            if (!data) return false;
            *static_cast<bool*>(data) = true;
            return true;
        case kGetCoreOptionsVersionCommand:
            if (!data) return false;
            *static_cast<unsigned*>(data) = 0;
            return true;
        case kGetVariableUpdateCommand:
            if (!data) return false;
            *static_cast<bool*>(data) = false;
            return true;
        case kGetLanguageCommand:
            if (!data) return false;
            *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;
        case abi::RETRO_ENVIRONMENT_GET_USERNAME:
            if (!data) return false;
            *static_cast<const char**>(data) = "OmniCore";
            return true;
        case kGetMessageInterfaceVersionCommand:
            if (!data) return false;
            *static_cast<unsigned*>(data) = 0;
            return true;
        case kGetInputMaxUsersCommand:
            if (!data) return false;
            *static_cast<unsigned*>(data) = 1;
            return true;
        case kGetInputBitmasksCommand:
            return false;
        case kGetTargetRefreshRateCommand:
            if (!data) return false;
            *static_cast<float*>(data) = 60.0f;
            return true;
        case kGetFastForwardingCommand:
            if (!data) return false;
            *static_cast<bool*>(data) = false;
            return true;
        case kGetAudioVideoEnableCommand:
            if (!data) return false;
            *static_cast<unsigned*>(data) = RETRO_AV_ENABLE_VIDEO | RETRO_AV_ENABLE_AUDIO;
            return true;
        case kSetSystemAvInfoCommand: {
            if (!data) return false;
            const auto* info = static_cast<const retro_system_av_info*>(data);
            if (info->timing.fps >= 20.0 && info->timing.fps <= 240.0) {
                targetFps_.store(info->timing.fps, std::memory_order_release);
            }
            return true;
        }
        case kSetGeometryCommand:
        case kSetInputDescriptorsCommand:
        case kSetControllerInfoCommand:
        case kSetPerformanceLevelCommand:
        case kSetCoreOptionsDisplayCommand:
        case kSetAudioBufferStatusCommand:
        case kSetMinimumAudioLatencyCommand:
            return true;
        case kSetMessageCommand: {
            if (!data) return false;
            const auto* message = static_cast<const retro_message*>(data);
            if (message->msg) setMessage(message->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_SHUTDOWN:
            setMessage("PPSSPP solicitou o encerramento do jogo.");
            stopRequested_.store(true, std::memory_order_release);
            return true;
        case kSetCoreOptionsCommand:
        case kSetCoreOptionsIntlCommand:
        case kSetCoreOptionsV2Command:
        case kSetCoreOptionsV2IntlCommand:
        case kSetCoreOptionsUpdateDisplayCommand:
            return false;
        default:
            return false;
    }
}

void LibretroHost::run() {
    active_ = this;
    bool coreInitialized = false;
    bool gameLoaded = false;
    try {
        if (!initEgl()) {
            markStartup(false, lastMessage());
            goto cleanup;
        }
        if (!initPresenter()) {
            markStartup(false, lastMessage());
            goto cleanup;
        }
        if (!loadApi()) {
            markStartup(false, lastMessage());
            goto cleanup;
        }

        api_.setEnvironment(&LibretroHost::environmentCallback);
        api_.setVideoRefresh(&LibretroHost::videoRefreshCallback);
        api_.setAudioSample(&LibretroHost::audioSampleCallback);
        api_.setAudioSampleBatch(&LibretroHost::audioBatchCallback);
        api_.setInputPoll(&LibretroHost::inputPollCallback);
        api_.setInputState(&LibretroHost::inputStateCallback);
        api_.init();
        coreInitialized = true;

        {
            retro_system_info systemInfo{};
            api_.getSystemInfo(&systemInfo);
            if (systemInfo.library_name && std::strcmp(systemInfo.library_name, "PPSSPP") != 0) {
                setMessage("O core carregado não é o PPSSPP esperado.");
                markStartup(false, lastMessage());
                goto cleanup;
            }
        }

        {
            retro_game_info gameInfo{};
            gameInfo.path = config_.romPath.c_str();
            gameInfo.data = nullptr;
            gameInfo.size = 0;
            gameInfo.meta = nullptr;
            if (!api_.loadGame(&gameInfo)) {
                markStartup(false, "PPSSPP recusou a imagem. Confira se ISO/CSO/PBP está íntegro.");
                goto cleanup;
            }
            gameLoaded = true;
        }

        if (hwRender_.context_reset) {
            hwRender_.context_reset();
            hwContextInitialized_ = true;
        }

        if (api_.setControllerPortDevice) {
            api_.setControllerPortDevice(0, RETRO_DEVICE_JOYPAD);
        }
        {
            retro_system_av_info avInfo{};
            api_.getSystemAvInfo(&avInfo);
            if (avInfo.timing.fps >= 20.0 && avInfo.timing.fps <= 240.0) {
                targetFps_.store(avInfo.timing.fps, std::memory_order_release);
            }
        }

        startAudio();
        running_.store(true, std::memory_order_release);
        markStartup(true, "PPSSPP carregado • iniciando o jogo");
        {
            auto nextFrame = std::chrono::steady_clock::now();
            while (!stopRequested_.load(std::memory_order_acquire)) {
                if (paused_.load(std::memory_order_acquire)) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(8));
                    nextFrame = std::chrono::steady_clock::now();
                    continue;
                }
                glBindFramebuffer(GL_FRAMEBUFFER, coreFramebuffer_);
                glViewport(0, 0, static_cast<GLsizei>(framebufferWidth_),
                    static_cast<GLsizei>(framebufferHeight_));
                api_.run();
                if (stopRequested_.load(std::memory_order_acquire)) break;
                if (display_ != EGL_NO_DISPLAY && eglSurface_ != EGL_NO_SURFACE) {
                    presentFrame();
                    if (eglSwapBuffers(display_, eglSurface_) != EGL_TRUE) {
                        setMessage("A superfície gráfica do PSP foi perdida.");
                        break;
                    }
                }
                const double fps = std::clamp(targetFps_.load(std::memory_order_acquire), 20.0, 240.0);
                const auto period = std::chrono::duration_cast<std::chrono::steady_clock::duration>(
                    std::chrono::duration<double>(1.0 / fps));
                nextFrame += period;
                const auto now = std::chrono::steady_clock::now();
                if (nextFrame > now) {
                    std::this_thread::sleep_until(nextFrame);
                } else if (now - nextFrame > period * 2) {
                    nextFrame = now;
                }
            }
        }
    } catch (const std::exception& error) {
        markStartup(false, std::string("Falha no runtime PSP: ") + error.what());
    } catch (...) {
        markStartup(false, "Falha inesperada no runtime PSP.");
    }

cleanup:
    running_.store(false, std::memory_order_release);
    // Destroy the graphics context before retro_unload_game deletes it.
    if (hwContextInitialized_ && hwRender_.context_destroy && display_ != EGL_NO_DISPLAY) {
        hwRender_.context_destroy();
        hwContextInitialized_ = false;
    }
    if (gameLoaded && api_.unloadGame) {
        api_.unloadGame();
    }
    if (coreInitialized && api_.deinit) {
        api_.deinit();
    }
    stopAudio();
    destroyEgl();
    resetApi();
    active_ = nullptr;
    markStartup(false, lastMessage());
}

bool LibretroHost::environmentCallback(unsigned cmd, void* data) {
    return active_ && active_->environment(cmd, data);
}

void LibretroHost::videoRefreshCallback(const void* data, unsigned, unsigned, std::size_t) {
    // PPSSPP is pinned to its OpenGL ES libretro backend. Its video callback
    // announces the hardware framebuffer; the PSP host swaps the EGL surface
    // after retro_run rather than uploading a software frame.
    if (!active_ || data == abi::RETRO_HW_FRAME_BUFFER_VALID || data == nullptr) return;
    active_->setMessage("PPSSPP enviou um frame de software inesperado; verifique o backend OpenGL.");
}

void LibretroHost::audioSampleCallback(std::int16_t left, std::int16_t right) {
    const std::int16_t sample[2] = {left, right};
    if (active_) active_->pushAudio(sample, 1);
}

std::size_t LibretroHost::audioBatchCallback(const std::int16_t* data, std::size_t frames) {
    if (!active_) return 0;
    return active_->pushAudio(data, frames);
}

void LibretroHost::inputPollCallback() {}

std::int16_t LibretroHost::inputStateCallback(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (!active_ || port != 0) return 0;
    if ((device & 0xFFu) == RETRO_DEVICE_JOYPAD) {
        if (id >= 16) return 0;
        return (active_->buttons_.load(std::memory_order_acquire) & (1u << id)) ? 1 : 0;
    }
    if ((device & 0xFFu) == RETRO_DEVICE_ANALOG) {
        if (index != RETRO_DEVICE_INDEX_ANALOG_LEFT) return 0;
        if (id == RETRO_DEVICE_ID_ANALOG_X) return active_->analogX_.load(std::memory_order_acquire);
        if (id == RETRO_DEVICE_ID_ANALOG_Y) return active_->analogY_.load(std::memory_order_acquire);
    }
    return 0;
}

std::uintptr_t LibretroHost::currentFramebufferCallback() {
    return active_ ? static_cast<std::uintptr_t>(active_->coreFramebuffer_) : 0;
}

abi::retro_proc_address_t LibretroHost::procAddressCallback(const char* symbol) {
    if (!symbol) return nullptr;
    return reinterpret_cast<abi::retro_proc_address_t>(eglGetProcAddress(symbol));
}

void LibretroHost::logCallback(enum retro_log_level level, const char* format, ...) {
    if (!format) return;
    char buffer[2048]{};
    va_list args;
    va_start(args, format);
    std::vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    __android_log_write(androidPriority(level), "OmniCore-PPSSPP", buffer);
}

aaudio_data_callback_result_t LibretroHost::audioDataCallback(
    AAudioStream*, void* userData, void* audioData, int32_t numFrames) {
    auto* host = static_cast<LibretroHost*>(userData);
    if (host) host->mixAudio(static_cast<std::int16_t*>(audioData), numFrames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

}  // namespace omnicore::psp