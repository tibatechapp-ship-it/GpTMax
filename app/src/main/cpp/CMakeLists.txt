cmake_minimum_required(VERSION 3.22.1)
project(gptmax LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- llama.cpp ---------------------------------------------------------------
# Prefer a vendored checkout at app/src/main/cpp/llama.cpp (see VENDOR.md for
# the pinned commit). When it is absent — e.g. a fresh CI checkout — fetch the
# same pinned commit automatically at configure time.
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt)
    add_subdirectory(llama.cpp)
else()
    message(STATUS "llama.cpp not vendored — fetching pinned commit 1fb8a26")
    include(FetchContent)
    FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
        GIT_TAG        1fb8a26
    )
    FetchContent_MakeAvailable(llama)
endif()

# --- GpTMax JNI bridge -------------------------------------------------------
add_library(gptmax SHARED
    gptmax_jni.cpp
)

if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include)
    target_include_directories(gptmax PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include)
endif()

find_library(log-lib log)
target_link_libraries(gptmax PRIVATE llama ${log-lib})

# arm64 codegen for Cortex-X/A7xx-class cores (dotprod is standard on target devices).
target_compile_options(gptmax PRIVATE
    -ffast-math
    -fvisibility=hidden
)
