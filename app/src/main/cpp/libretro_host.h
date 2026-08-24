#pragma once

#include <android/native_window.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <string>

struct RuntimePerformanceConfig {
    // 0 = sustained/efficiency, 1 = balanced, 2 = low latency/performance.
    int policy = 1;
    int audioBufferBursts = 3;
    bool tryExclusiveAudio = false;
    bool preferPowerEfficiency = false;
    bool aggressiveFramePacing = false;
};

class LibretroSession {
public:
    LibretroSession(
        std::string coreLibrary,
        std::string gamePath,
        std::string gameKey,
        std::string systemDir,
        std::string saveDir,
        std::string stateDir,
        ANativeWindow* window,
        RuntimePerformanceConfig performance,
        std::string coreOptions,
        bool dualShock
    );
    ~LibretroSession();

    LibretroSession(const LibretroSession&) = delete;
    LibretroSession& operator=(const LibretroSession&) = delete;

    bool start();
    void stop();
    bool running() const;
    void setButton(unsigned id, bool pressed);
    void setAnalog(unsigned stick, std::int16_t x, std::int16_t y);
    bool setModernTankIntent(std::int16_t x, std::int16_t y, bool active);
    void requestSaveState(int slot);
    void requestLoadState(int slot);
    void requestCheatReset();
    void requestCheatSet(unsigned index, bool enabled, std::string code);
    int diskCount() const;
    int diskIndex() const;
    bool diskEjected() const;
    bool requestDiskIndex(int index);
    void updatePerformanceConfig(RuntimePerformanceConfig performance);
    std::string status() const;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

bool probeLibretroCore(const char* libraryName);
