#pragma once

/* Minimal libretro ABI declarations consumed by OmniCore. */
#include <cstddef>
#include <cstdint>
#include <climits>

using retro_environment_t = bool (*)(unsigned cmd, void* data);
using retro_video_refresh_t = void (*)(const void* data, unsigned width, unsigned height, std::size_t pitch);
using retro_audio_sample_t = void (*)(std::int16_t left, std::int16_t right);
using retro_audio_sample_batch_t = std::size_t (*)(const std::int16_t* data, std::size_t frames);
using retro_input_poll_t = void (*)();
using retro_input_state_t = std::int16_t (*)(unsigned port, unsigned device, unsigned index, unsigned id);
using retro_audio_buffer_status_callback_t = void (*)(bool active, unsigned occupancy, bool underrunLikely);

enum retro_pixel_format {
    RETRO_PIXEL_FORMAT_0RGB1555 = 0,
    RETRO_PIXEL_FORMAT_XRGB8888 = 1,
    RETRO_PIXEL_FORMAT_RGB565 = 2,
    RETRO_PIXEL_FORMAT_UNKNOWN = INT_MAX
};

enum retro_log_level {
    RETRO_LOG_DEBUG = 0,
    RETRO_LOG_INFO,
    RETRO_LOG_WARN,
    RETRO_LOG_ERROR,
    RETRO_LOG_DUMMY = INT_MAX
};

using retro_log_printf_t = void (*)(enum retro_log_level level, const char* fmt, ...);
struct retro_log_callback { retro_log_printf_t log; };

struct retro_game_info {
    const char* path;
    const void* data;
    std::size_t size;
    const char* meta;
};

struct retro_disk_control_callback {
    bool (*set_eject_state)(bool ejected);
    bool (*get_eject_state)(void);
    unsigned (*get_image_index)(void);
    bool (*set_image_index)(unsigned index);
    unsigned (*get_num_images)(void);
    bool (*replace_image_index)(unsigned index, const retro_game_info* info);
    bool (*add_image_index)(void);
};

struct retro_disk_control_ext_callback {
    bool (*set_eject_state)(bool ejected);
    bool (*get_eject_state)(void);
    unsigned (*get_image_index)(void);
    bool (*set_image_index)(unsigned index);
    unsigned (*get_num_images)(void);
    bool (*replace_image_index)(unsigned index, const retro_game_info* info);
    bool (*add_image_index)(void);
    bool (*set_initial_image)(unsigned index, const char* path);
    bool (*get_image_path)(unsigned index, char* path, std::size_t len);
    bool (*get_image_label)(unsigned index, char* label, std::size_t len);
};

struct retro_system_info {
    const char* library_name;
    const char* library_version;
    const char* valid_extensions;
    bool need_fullpath;
    bool block_extract;
};
struct retro_game_geometry {
    unsigned base_width;
    unsigned base_height;
    unsigned max_width;
    unsigned max_height;
    float aspect_ratio;
};
struct retro_system_timing { double fps; double sample_rate; };
struct retro_system_av_info { retro_game_geometry geometry; retro_system_timing timing; };
struct retro_variable { const char* key; const char* value; };
struct retro_message { const char* msg; unsigned frames; };
struct retro_audio_buffer_status_callback { retro_audio_buffer_status_callback_t callback; };
struct retro_framebuffer {
    void* data;
    unsigned width;
    unsigned height;
    std::size_t pitch;
    retro_pixel_format format;
    unsigned access_flags;
    unsigned memory_flags;
};

constexpr unsigned RETRO_ENVIRONMENT_EXPERIMENTAL = 0x10000u;
constexpr unsigned RETRO_ENVIRONMENT_GET_CAN_DUPE = 3;
constexpr unsigned RETRO_ENVIRONMENT_SET_MESSAGE = 6;
constexpr unsigned RETRO_ENVIRONMENT_SHUTDOWN = 7;
constexpr unsigned RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL = 8;
constexpr unsigned RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY = 9;
constexpr unsigned RETRO_ENVIRONMENT_SET_PIXEL_FORMAT = 10;
constexpr unsigned RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS = 11;
constexpr unsigned RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE = 13;
constexpr unsigned RETRO_ENVIRONMENT_GET_VARIABLE = 15;
constexpr unsigned RETRO_ENVIRONMENT_SET_VARIABLES = 16;
constexpr unsigned RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE = 17;
constexpr unsigned RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME = 18;
constexpr unsigned RETRO_ENVIRONMENT_GET_LOG_INTERFACE = 27;
constexpr unsigned RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY = 30;
constexpr unsigned RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY = 31;
constexpr unsigned RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO = 32;
constexpr unsigned RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO = 34;
constexpr unsigned RETRO_ENVIRONMENT_SET_CONTROLLER_INFO = 35;
constexpr unsigned RETRO_ENVIRONMENT_SET_GEOMETRY = 37;
constexpr unsigned RETRO_ENVIRONMENT_GET_LANGUAGE = 39;
constexpr unsigned RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER = (40u | RETRO_ENVIRONMENT_EXPERIMENTAL);
constexpr unsigned RETRO_ENVIRONMENT_GET_VFS_INTERFACE = (45u | RETRO_ENVIRONMENT_EXPERIMENTAL);
constexpr unsigned RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE = (47u | RETRO_ENVIRONMENT_EXPERIMENTAL);
constexpr unsigned RETRO_ENVIRONMENT_GET_FASTFORWARDING = (49u | RETRO_ENVIRONMENT_EXPERIMENTAL);
constexpr unsigned RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE = (50u | RETRO_ENVIRONMENT_EXPERIMENTAL);
constexpr unsigned RETRO_ENVIRONMENT_GET_INPUT_BITMASKS = (51u | RETRO_ENVIRONMENT_EXPERIMENTAL);
constexpr unsigned RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION = 52;
constexpr unsigned RETRO_ENVIRONMENT_SET_CORE_OPTIONS = 53;
constexpr unsigned RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL = 54;
constexpr unsigned RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY = 55;
constexpr unsigned RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION = 57;
constexpr unsigned RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE = 58;
constexpr unsigned RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION = 59;
constexpr unsigned RETRO_ENVIRONMENT_SET_MESSAGE_EXT = 60;
constexpr unsigned RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS = 61;
constexpr unsigned RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK = 62;
constexpr unsigned RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY = 63;
constexpr unsigned RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2 = 67;
constexpr unsigned RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL = 68;
constexpr unsigned RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK = 69;

constexpr unsigned RETRO_MEMORY_ACCESS_WRITE = 1u << 0;

constexpr unsigned RETRO_DEVICE_JOYPAD = 1;
constexpr unsigned RETRO_DEVICE_ANALOG = 5;
constexpr unsigned RETRO_DEVICE_INDEX_ANALOG_LEFT = 0;
constexpr unsigned RETRO_DEVICE_INDEX_ANALOG_RIGHT = 1;
constexpr unsigned RETRO_DEVICE_ID_ANALOG_X = 0;
constexpr unsigned RETRO_DEVICE_ID_ANALOG_Y = 1;
constexpr unsigned RETRO_DEVICE_TYPE_SHIFT = 8;
constexpr unsigned RETRO_DEVICE_PSE_DUALSHOCK = ((1u + 1u) << RETRO_DEVICE_TYPE_SHIFT) | RETRO_DEVICE_ANALOG;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_B = 0;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_Y = 1;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_SELECT = 2;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_START = 3;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_UP = 4;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_DOWN = 5;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_LEFT = 6;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_RIGHT = 7;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_A = 8;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_X = 9;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_L = 10;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_R = 11;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_L2 = 12;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_R2 = 13;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_L3 = 14;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_R3 = 15;
constexpr unsigned RETRO_DEVICE_ID_JOYPAD_MASK = 256;

constexpr unsigned RETRO_MEMORY_SAVE_RAM = 0;
constexpr unsigned RETRO_LANGUAGE_ENGLISH = 0;
constexpr unsigned RETRO_AV_ENABLE_VIDEO = 1u << 0;
constexpr unsigned RETRO_AV_ENABLE_AUDIO = 1u << 1;
