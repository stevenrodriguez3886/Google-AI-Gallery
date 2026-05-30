cmake_minimum_required(VERSION 3.22.1)
project("gallery_voice_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(gallery_voice_native SHARED
    kokoro_engine.cpp
    audio_buffer.cpp
    jni_bridge.cpp
)

set_target_properties(gallery_voice_native PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

if(NOT ONNXRUNTIME_DIR)
    set(ONNXRUNTIME_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/onnxruntime")
endif()

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${ONNXRUNTIME_DIR}/jni/arm64-v8a/libonnxruntime.so"
)

# Include directories
target_include_directories(gallery_voice_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link libraries
target_link_libraries(gallery_voice_native
    android
    log
    onnxruntime
)

# 16KB page size alignment (required for Android 15+ and Google Play)
target_link_options(gallery_voice_native PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

# Release optimizations
target_compile_options(gallery_voice_native PRIVATE
    $<$<CONFIG:Release>:-O3 -ffast-math -march=armv8-a+simd>
)
