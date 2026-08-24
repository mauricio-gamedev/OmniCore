# Layer Modern Control Engine v2 on top of the already-generated PS1 disk host.
# The validated Alpha 6 libretro_host_v7.cpp and disk-control patch remain untouched.
# Every replacement is anchored and fails closed if the generated host shape changes.
if(NOT DEFINED OMNICORE_MODERN_HOST_INPUT OR NOT DEFINED OMNICORE_MODERN_HOST_OUTPUT)
    message(FATAL_ERROR "PS1 modern-control patch requires input/output paths")
endif()

file(READ "${OMNICORE_MODERN_HOST_INPUT}" SOURCE)

function(modern_replace_required OLD NEW LABEL)
    string(FIND "${SOURCE}" "${OLD}" FOUND_AT)
    if(FOUND_AT EQUAL -1)
        message(FATAL_ERROR "PS1 modern-control patch anchor missing: ${LABEL}")
    endif()
    string(REPLACE "${OLD}" "${NEW}" SOURCE "${SOURCE}")
    set(SOURCE "${SOURCE}" PARENT_SCOPE)
endfunction()

# Frontend-facing intent mailbox. Android only publishes the newest analog target;
# all digital modulation is resolved later on the emulation thread, once per retro_run.
set(OLD [=[
    void setAnalog(unsigned stick, std::int16_t x, std::int16_t y) {
        if (stick == 0) {
            leftX_.store(x, std::memory_order_release);
            leftY_.store(y, std::memory_order_release);
        } else if (stick == 1) {
            rightX_.store(x, std::memory_order_release);
            rightY_.store(y, std::memory_order_release);
        }
    }

    void requestSaveState(int slot) { saveStateRequest_.store(std::clamp(slot, 0, 9), std::memory_order_release); }
]=])
set(NEW [=[
    void setAnalog(unsigned stick, std::int16_t x, std::int16_t y) {
        if (stick == 0) {
            leftX_.store(x, std::memory_order_release);
            leftY_.store(y, std::memory_order_release);
        } else if (stick == 1) {
            rightX_.store(x, std::memory_order_release);
            rightY_.store(y, std::memory_order_release);
        }
    }

    bool setModernTankIntent(std::int16_t x, std::int16_t y, bool active) {
        modernTankX_.store(static_cast<int>(x), std::memory_order_release);
        modernTankY_.store(static_cast<int>(y), std::memory_order_release);
        modernTankActive_.store(active, std::memory_order_release);
        if (!active) {
            // Release generated directions immediately from the Android thread. The
            // emulation thread resets its smoothing/quantizer state on its next frame.
            modernTankButtons_.store(0u, std::memory_order_release);
            modernTankResetRequested_.store(true, std::memory_order_release);
        }
        return running_.load(std::memory_order_acquire);
    }

    void requestSaveState(int slot) { saveStateRequest_.store(std::clamp(slot, 0, 9), std::memory_order_release); }
]=])
modern_replace_required("${OLD}" "${NEW}" "modern intent mailbox")

# Generated modern directions are a separate mask. Ordinary touch/D-pad/gamepad
# buttons remain in buttons_, so disabling Modern Control restores the exact legacy path.
set(OLD [=[
            const std::uint32_t mask = active_->buttons_.load(std::memory_order_acquire);
]=])
set(NEW [=[
            const std::uint32_t mask =
                active_->buttons_.load(std::memory_order_acquire) |
                active_->modernTankButtons_.load(std::memory_order_acquire);
]=])
modern_replace_required("${OLD}" "${NEW}" "joypad mask composition")

# Frame-synchronous intent resolver. It deliberately does not read game memory and
# never changes the core clock, frame pacing, audio or video. Horizontal strength is
# quantized with a sigma-delta accumulator so turn frames are distributed evenly.
set(OLD [=[
    void runLoop() {
]=])
set(NEW [=[
    void resetModernTankFrameState() {
        modernFilteredX_ = 0.0f;
        modernFilteredY_ = 0.0f;
        modernMovementActive_ = false;
        modernTurnSign_ = 0;
        modernTurnAccumulator_ = 0.0f;
        modernTankButtons_.store(0u, std::memory_order_release);
    }

    static std::uint32_t modernButtonBit(unsigned id) {
        return id < 16u ? (1u << id) : 0u;
    }

    void applyModernTankFrame() {
        if (modernTankResetRequested_.exchange(false, std::memory_order_acq_rel)) {
            resetModernTankFrameState();
        }
        if (!modernTankActive_.load(std::memory_order_acquire)) {
            modernTankButtons_.store(0u, std::memory_order_release);
            return;
        }

        constexpr float kAxisMax = 32767.0f;
        float targetX = static_cast<float>(modernTankX_.load(std::memory_order_acquire)) / kAxisMax;
        float targetY = static_cast<float>(modernTankY_.load(std::memory_order_acquire)) / kAxisMax;
        targetX = std::clamp(targetX, -1.0f, 1.0f);
        targetY = std::clamp(targetY, -1.0f, 1.0f);

        const float rawMagnitude = std::hypot(targetX, targetY);
        if (rawMagnitude > 1.0f) {
            targetX /= rawMagnitude;
            targetY /= rawMagnitude;
        }

        // Lightweight one-pole filter, executed exactly once per emulated frame.
        // It removes touch/controller jitter without adding a timer or another thread.
        constexpr float kFilterAlpha = 0.50f;
        modernFilteredX_ += (targetX - modernFilteredX_) * kFilterAlpha;
        modernFilteredY_ += (targetY - modernFilteredY_) * kFilterAlpha;

        const float x = modernFilteredX_;
        const float y = modernFilteredY_;
        const float magnitude = std::hypot(x, y);

        constexpr float kEnterDeadzone = 0.17f;
        constexpr float kExitDeadzone = 0.105f;
        if (!modernMovementActive_) {
            if (magnitude < kEnterDeadzone) {
                modernTankButtons_.store(0u, std::memory_order_release);
                return;
            }
            modernMovementActive_ = true;
        } else if (magnitude < kExitDeadzone) {
            resetModernTankFrameState();
            return;
        }

        const float ax = std::abs(x);
        const float ay = std::abs(y);
        constexpr float kTurnAxisDeadzone = 0.15f;
        constexpr float kMoveAxisDeadzone = 0.21f;
        int turnSign = x <= -kTurnAxisDeadzone ? -1 : (x >= kTurnAxisDeadzone ? 1 : 0);

        // Near-horizontal intent remains a direct full-speed turn. This preserves
        // precise alignment behavior expected by tank-control games.
        constexpr float kTurnInPlaceX = 0.50f;
        constexpr float kTurnInPlaceY = 0.24f;
        if (turnSign != 0 && ax >= kTurnInPlaceX && ay <= kTurnInPlaceY) {
            modernTurnSign_ = turnSign;
            modernTurnAccumulator_ = 0.0f;
            const unsigned id = turnSign < 0 ? 6u : 7u;
            modernTankButtons_.store(modernButtonBit(id), std::memory_order_release);
            return;
        }

        constexpr float kVerticalDominance = 0.72f;
        int moveSign = y <= -kMoveAxisDeadzone ? -1 : (y >= kMoveAxisDeadzone ? 1 : 0);
        if (moveSign == 0 && ay >= ax * kVerticalDominance) {
            moveSign = y < 0.0f ? -1 : (y > 0.0f ? 1 : 0);
        }

        if (moveSign == 0) {
            modernTurnAccumulator_ = 0.0f;
            modernTurnSign_ = turnSign;
            if (turnSign == 0) {
                modernTankButtons_.store(0u, std::memory_order_release);
            } else {
                modernTankButtons_.store(modernButtonBit(turnSign < 0 ? 6u : 7u), std::memory_order_release);
            }
            return;
        }

        const unsigned moveId = moveSign < 0 ? 4u : 5u;
        std::uint32_t mask = modernButtonBit(moveId);

        // Wide straight cone: natural sideways thumb drift stays straight.
        constexpr float kStraightSteerDeadzone = 0.14f;
        constexpr float kStraightConeRatio = 0.20f;
        if (turnSign == 0 || ax <= kStraightSteerDeadzone || ax < ay * kStraightConeRatio) {
            modernTurnSign_ = 0;
            modernTurnAccumulator_ = 0.0f;
            modernTankButtons_.store(mask, std::memory_order_release);
            return;
        }

        // A direction change gets an immediate correction frame, then sigma-delta
        // distributes later turn frames according to analog strength. This is stable
        // at PAL/NTSC rates because it is tied to retro_run, not Android milliseconds.
        if (turnSign != modernTurnSign_) {
            modernTurnSign_ = turnSign;
            modernTurnAccumulator_ = 1.0f;
        }

        const float normalized = std::clamp(
            (ax - kStraightSteerDeadzone) / (1.0f - kStraightSteerDeadzone), 0.0f, 1.0f);
        const float curved = 0.30f * normalized + 0.70f * normalized * normalized;
        constexpr float kMinTurnDuty = 0.10f;
        constexpr float kMaxTurnDuty = 0.94f;
        const float duty = std::clamp(
            kMinTurnDuty + (kMaxTurnDuty - kMinTurnDuty) * curved,
            kMinTurnDuty, kMaxTurnDuty);

        modernTurnAccumulator_ += duty;
        const bool turnThisFrame = modernTurnAccumulator_ >= 1.0f;
        if (turnThisFrame) modernTurnAccumulator_ -= 1.0f;

        if (turnThisFrame) mask |= modernButtonBit(turnSign < 0 ? 6u : 7u);
        modernTankButtons_.store(mask, std::memory_order_release);
    }

    void runLoop() {
]=])
modern_replace_required("${OLD}" "${NEW}" "frame-synchronous modern resolver")

# Resolve the modern mask immediately before retro_run so one modulation decision
# corresponds to exactly one emulated frame.
set(OLD [=[
                const auto workStart = clock::now();
                api.run();
]=])
set(NEW [=[
                applyModernTankFrame();
                const auto workStart = clock::now();
                api.run();
]=])
modern_replace_required("${OLD}" "${NEW}" "retro_run frame hook")

# Mailbox state is atomic; filter/quantizer state is touched only by the emulation thread.
set(OLD [=[
    std::atomic<std::uint32_t> buttons_{0};
    std::atomic<int> leftX_{0};
]=])
set(NEW [=[
    std::atomic<std::uint32_t> buttons_{0};
    std::atomic<std::uint32_t> modernTankButtons_{0};
    std::atomic<int> modernTankX_{0};
    std::atomic<int> modernTankY_{0};
    std::atomic<bool> modernTankActive_{false};
    std::atomic<bool> modernTankResetRequested_{false};
    float modernFilteredX_ = 0.0f;
    float modernFilteredY_ = 0.0f;
    bool modernMovementActive_ = false;
    int modernTurnSign_ = 0;
    float modernTurnAccumulator_ = 0.0f;
    std::atomic<int> leftX_{0};
]=])
modern_replace_required("${OLD}" "${NEW}" "modern state fields")

# Public LibretroSession wrapper. Disk-control wrappers added by the previous layer
# remain untouched.
set(OLD [=[
void LibretroSession::setAnalog(unsigned stick, std::int16_t x, std::int16_t y) { impl_->setAnalog(stick, x, y); }
void LibretroSession::requestSaveState(int slot) { impl_->requestSaveState(slot); }
]=])
set(NEW [=[
void LibretroSession::setAnalog(unsigned stick, std::int16_t x, std::int16_t y) { impl_->setAnalog(stick, x, y); }
bool LibretroSession::setModernTankIntent(std::int16_t x, std::int16_t y, bool active) { return impl_->setModernTankIntent(x, y, active); }
void LibretroSession::requestSaveState(int slot) { impl_->requestSaveState(slot); }
]=])
modern_replace_required("${OLD}" "${NEW}" "LibretroSession modern wrapper")

file(WRITE "${OMNICORE_MODERN_HOST_OUTPUT}" "${SOURCE}")
message(STATUS "OmniCore PS1 Modern Control Engine v2 host generated: ${OMNICORE_MODERN_HOST_OUTPUT}")
