cmake_minimum_required(VERSION 3.22.1)
project(omnicore_runtime LANGUAGES CXX)

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
find_library(dl-lib dl)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)

target_link_libraries(
    omnicore_runtime
    ${android-lib}
    ${log-lib}
    ${aaudio-lib}
    ${dl-lib}
    ${egl-lib}
    ${glesv2-lib}
)
