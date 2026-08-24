# Generate the PS1 libretro host with disk-control support without duplicating the
# entire runtime source. This runs at CMake configure time and fails closed if the
# v7 anchors change, so native changes cannot silently apply to the wrong code.
if(NOT DEFINED OMNICORE_DISK_HOST_INPUT OR NOT DEFINED OMNICORE_DISK_HOST_OUTPUT)
    message(FATAL_ERROR "PS1 disk host patch requires input/output paths")
endif()

file(READ "${OMNICORE_DISK_HOST_INPUT}" SOURCE)

function(replace_required OLD NEW LABEL)
    string(FIND "${SOURCE}" "${OLD}" FOUND_AT)
    if(FOUND_AT EQUAL -1)
        message(FATAL_ERROR "PS1 disk-control patch anchor missing: ${LABEL}")
    endif()
    string(REPLACE "${OLD}" "${NEW}" SOURCE "${SOURCE}")
    set(SOURCE "${SOURCE}" PARENT_SCOPE)
endfunction()

set(OLD [=[
    void requestCheatSet(unsigned index, bool enabled, std::string code) {
        if (code.empty()) return;
        if (code.size() > 8192) code.resize(8192);
        std::lock_guard<std::mutex> lock(cheatMutex_);
        cheatCommands_.push_back(CheatCommand{std::min(index, 127u), enabled, std::move(code)});
    }

    void updatePerformanceConfig(RuntimePerformanceConfig config) {
]=])
set(NEW [=[
    void requestCheatSet(unsigned index, bool enabled, std::string code) {
        if (code.empty()) return;
        if (code.size() > 8192) code.resize(8192);
        std::lock_guard<std::mutex> lock(cheatMutex_);
        cheatCommands_.push_back(CheatCommand{std::min(index, 127u), enabled, std::move(code)});
    }

    int diskCount() const { return diskCount_.load(std::memory_order_acquire); }
    int diskIndex() const { return diskIndex_.load(std::memory_order_acquire); }
    bool diskEjected() const { return diskEjected_.load(std::memory_order_acquire); }
    bool requestDiskIndex(int index) {
        const int count = diskCount();
        if (!diskControlAvailable_.load(std::memory_order_acquire) || count <= 1 || index < 0 || index >= count) return false;
        diskIndexRequest_.store(index, std::memory_order_release);
        return true;
    }

    void updatePerformanceConfig(RuntimePerformanceConfig config) {
]=])
replace_required("${OLD}" "${NEW}" "public disk request API")

set(OLD [=[
            case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
            case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
            case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
            case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            case RETRO_ENVIRONMENT_SET_MESSAGE_EXT:
                return false;
]=])
set(NEW [=[
            case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE: {
                if (!data) return false;
                diskControl_ = *static_cast<const retro_disk_control_callback*>(data);
                const bool valid = diskControl_.set_eject_state && diskControl_.get_eject_state &&
                                   diskControl_.get_image_index && diskControl_.set_image_index &&
                                   diskControl_.get_num_images;
                diskControlAvailable_.store(valid, std::memory_order_release);
                return valid;
            }
            case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
                if (data) *static_cast<unsigned*>(data) = 1;
                return data != nullptr;
            case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE: {
                if (!data) return false;
                const auto* ext = static_cast<const retro_disk_control_ext_callback*>(data);
                diskControl_.set_eject_state = ext->set_eject_state;
                diskControl_.get_eject_state = ext->get_eject_state;
                diskControl_.get_image_index = ext->get_image_index;
                diskControl_.set_image_index = ext->set_image_index;
                diskControl_.get_num_images = ext->get_num_images;
                diskControl_.replace_image_index = ext->replace_image_index;
                diskControl_.add_image_index = ext->add_image_index;
                const bool valid = diskControl_.set_eject_state && diskControl_.get_eject_state &&
                                   diskControl_.get_image_index && diskControl_.set_image_index &&
                                   diskControl_.get_num_images;
                diskControlAvailable_.store(valid, std::memory_order_release);
                return valid;
            }
            case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            case RETRO_ENVIRONMENT_SET_MESSAGE_EXT:
                return false;
]=])
replace_required("${OLD}" "${NEW}" "libretro disk environment")

set(OLD [=[
    void registerLegacyVariables(const retro_variable* vars) {
]=])
set(NEW [=[
    void refreshDiskState() {
        if (!diskControlAvailable_.load(std::memory_order_acquire)) {
            diskCount_.store(0, std::memory_order_release);
            diskIndex_.store(0, std::memory_order_release);
            diskEjected_.store(false, std::memory_order_release);
            return;
        }
        const unsigned count = diskControl_.get_num_images ? diskControl_.get_num_images() : 0u;
        const unsigned index = diskControl_.get_image_index ? diskControl_.get_image_index() : 0u;
        const bool ejected = diskControl_.get_eject_state ? diskControl_.get_eject_state() : false;
        diskCount_.store(static_cast<int>(std::min(count, 99u)), std::memory_order_release);
        diskIndex_.store(static_cast<int>(index), std::memory_order_release);
        diskEjected_.store(ejected, std::memory_order_release);
    }

    void applyPendingDiskRequest() {
        const int requested = diskIndexRequest_.exchange(-1, std::memory_order_acq_rel);
        if (requested < 0) return;
        refreshDiskState();
        const int count = diskCount();
        if (!diskControlAvailable_.load(std::memory_order_acquire) || requested >= count) {
            setStatus("DISCO • troca indisponível para esta mídia");
            return;
        }
        if (requested == diskIndex()) {
            setStatus("DISCO • disco " + std::to_string(requested + 1) + " já está inserido");
            return;
        }

        const bool wasEjected = diskControl_.get_eject_state ? diskControl_.get_eject_state() : false;
        if (!wasEjected && (!diskControl_.set_eject_state || !diskControl_.set_eject_state(true))) {
            setStatus("DISCO • o core recusou abrir a tampa virtual");
            return;
        }
        if (!diskControl_.set_image_index || !diskControl_.set_image_index(static_cast<unsigned>(requested))) {
            if (!wasEjected && diskControl_.set_eject_state) diskControl_.set_eject_state(false);
            refreshDiskState();
            setStatus("DISCO • o core recusou selecionar o disco " + std::to_string(requested + 1));
            return;
        }
        if (!wasEjected && (!diskControl_.set_eject_state || !diskControl_.set_eject_state(false))) {
            refreshDiskState();
            setStatus("DISCO • disco selecionado, mas a tampa virtual não fechou");
            return;
        }
        refreshDiskState();
        setStatus("DISCO • disco " + std::to_string(diskIndex() + 1) + "/" + std::to_string(std::max(1, diskCount())) + " inserido");
    }

    void registerLegacyVariables(const retro_variable* vars) {
]=])
replace_required("${OLD}" "${NEW}" "disk state helpers")

set(OLD [=[
        } else {
            gameLoaded = true;
            retro_system_av_info av{};
]=])
set(NEW [=[
        } else {
            gameLoaded = true;
            refreshDiskState();
            retro_system_av_info av{};
]=])
replace_required("${OLD}" "${NEW}" "initial disk state")

set(OLD [=[
            while (!stopRequested_.load(std::memory_order_acquire)) {
                const int loadSlot = loadStateRequest_.exchange(-1, std::memory_order_acq_rel);
]=])
set(NEW [=[
            while (!stopRequested_.load(std::memory_order_acquire)) {
                applyPendingDiskRequest();
                const int loadSlot = loadStateRequest_.exchange(-1, std::memory_order_acq_rel);
]=])
replace_required("${OLD}" "${NEW}" "emulation-thread disk switch")

set(OLD [=[
    std::atomic<int> saveStateRequest_{-1};
    std::atomic<int> loadStateRequest_{-1};
    std::mutex cheatMutex_;
]=])
set(NEW [=[
    std::atomic<int> saveStateRequest_{-1};
    std::atomic<int> loadStateRequest_{-1};
    retro_disk_control_callback diskControl_{};
    std::atomic<bool> diskControlAvailable_{false};
    std::atomic<int> diskCount_{0};
    std::atomic<int> diskIndex_{0};
    std::atomic<bool> diskEjected_{false};
    std::atomic<int> diskIndexRequest_{-1};
    std::mutex cheatMutex_;
]=])
replace_required("${OLD}" "${NEW}" "disk state fields")

set(OLD [=[
void LibretroSession::requestCheatReset() { impl_->requestCheatReset(); }
void LibretroSession::requestCheatSet(unsigned index, bool enabled, std::string code) { impl_->requestCheatSet(index, enabled, std::move(code)); }
void LibretroSession::updatePerformanceConfig(RuntimePerformanceConfig performance) { impl_->updatePerformanceConfig(performance); }
]=])
set(NEW [=[
void LibretroSession::requestCheatReset() { impl_->requestCheatReset(); }
void LibretroSession::requestCheatSet(unsigned index, bool enabled, std::string code) { impl_->requestCheatSet(index, enabled, std::move(code)); }
int LibretroSession::diskCount() const { return impl_->diskCount(); }
int LibretroSession::diskIndex() const { return impl_->diskIndex(); }
bool LibretroSession::diskEjected() const { return impl_->diskEjected(); }
bool LibretroSession::requestDiskIndex(int index) { return impl_->requestDiskIndex(index); }
void LibretroSession::updatePerformanceConfig(RuntimePerformanceConfig performance) { impl_->updatePerformanceConfig(performance); }
]=])
replace_required("${OLD}" "${NEW}" "LibretroSession disk wrappers")

file(WRITE "${OMNICORE_DISK_HOST_OUTPUT}" "${SOURCE}")
message(STATUS "OmniCore PS1 disk-control host generated: ${OMNICORE_DISK_HOST_OUTPUT}")
