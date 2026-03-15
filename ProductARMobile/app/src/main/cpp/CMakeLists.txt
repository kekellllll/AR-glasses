cmake_minimum_required(VERSION 3.22.1)
project(productarmobile_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# ── Common flags ──
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG")

# ════════════════════════════════════════════════════════════════════
# llama.cpp (LLM inference)
# ════════════════════════════════════════════════════════════════════
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

if(EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    add_subdirectory(${LLAMA_DIR} llama_build)

    add_library(llama_jni SHARED llama_jni.cpp)
    target_include_directories(llama_jni PRIVATE
        ${LLAMA_DIR}/include
        ${LLAMA_DIR}/ggml/include
        ${LLAMA_DIR}/common
    )
    target_link_libraries(llama_jni
        llama
        ggml
        common
        android
        log
    )
endif()

# ════════════════════════════════════════════════════════════════════
# whisper.cpp (STT inference)
# ════════════════════════════════════════════════════════════════════
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

if(EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    add_subdirectory(${WHISPER_DIR} whisper_build)

    add_library(whisper_jni SHARED whisper_jni.cpp)
    target_include_directories(whisper_jni PRIVATE
        ${WHISPER_DIR}/include
        ${WHISPER_DIR}/ggml/include
    )
    target_link_libraries(whisper_jni
        whisper
        android
        log
    )
endif()
