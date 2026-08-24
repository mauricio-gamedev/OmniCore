cmake_minimum_required(VERSION 3.22.1)
project(omnicore_runtime LANGUAGES CXX)

# PlayStation runtime. Keep PS1 sources and lifecycle isolated from other consoles.
# The disk-control patch is generated into the build directory so the Alpha 6
# source tree stays intact while PS1 gains libretro multi-disc support.
set(OMNICORE_DISK_HOST_INPUT "${CMAKE_CURRENT_SOURCE_DIR}/libretro_host_v7.cpp")
set(OMNICORE_DISK_HOST_OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/libretro_host_disk.cpp")
include("${CMAKE_CURRENT_SOURCE_DIR}/ps1_disk_control_patch.cmake")

add_library(
    omnicore_runtime
    SHARED
    native_bridge.cpp
    gl_presenter.cpp
    "${OMNICORE_DISK_HOST_OUTPUT}"
)

target_compile_features(omnicore_runtime PRIVATE cxx_std_20)

target_compile_options(
    omnicore_runtime
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -O2
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)

target_link_options(
    omnicore_runtime
    PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
)

set_property(TARGET omnicore_runtime PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELEASE TRUE)
target_include_directories(omnicore_runtime PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(android-lib android)
find_library(log-lib log)
find_library(aaudio-lib aaudio)
find_library(opensles-lib OpenSLES)
find_library(dl-lib dl)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)
find_library(glesv3-lib GLESv3)

target_link_libraries(
    omnicore_runtime
    ${android-lib}
    ${log-lib}
    ${aaudio-lib}
    ${dl-lib}
    ${egl-lib}
    ${glesv2-lib}
)

# Nintendo 64 runtime. This remains a different shared object and owns its own
# EGL/GLES3 context, AAudio stream, libretro callbacks, telemetry and input state.
add_library(
    omnicore_n64_runtime
    SHARED
    n64/n64_native_bridge.cpp
    n64/n64_libretro_host.cpp
)

target_compile_features(omnicore_n64_runtime PRIVATE cxx_std_20)
target_compile_options(
    omnicore_n64_runtime
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -O2
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)
target_link_options(
    omnicore_n64_runtime
    PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
)
set_property(TARGET omnicore_n64_runtime PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELEASE TRUE)
target_include_directories(omnicore_n64_runtime PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(
    omnicore_n64_runtime
    ${android-lib}
    ${log-lib}
    ${aaudio-lib}
    ${opensles-lib}
    ${dl-lib}
    ${egl-lib}
    ${glesv3-lib}
)

# PlayStation 2 foundation runtime. PS2 intentionally begins as its own shared
# object. Third-party backend code will be attached behind the OmniCore adapter
# only after the foundation and license/build gates are validated.
add_library(
    omnicore_ps2_runtime
    SHARED
    ps2/ps2_native_bridge.cpp
    ps2/ps2_thread_perf_fallback.cpp
)

target_compile_features(omnicore_ps2_runtime PRIVATE cxx_std_20)
target_compile_options(
    omnicore_ps2_runtime
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -O2
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)
target_link_options(
    omnicore_ps2_runtime
    PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
)
set_property(TARGET omnicore_ps2_runtime PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELEASE TRUE)
target_include_directories(omnicore_ps2_runtime PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(
    omnicore_ps2_runtime
    ${android-lib}
    ${log-lib}
    ${dl-lib}
)
