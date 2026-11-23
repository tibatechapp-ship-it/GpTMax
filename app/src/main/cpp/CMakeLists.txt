cmake_minimum_required(VERSION 3.22.1)
project(gptmax LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- llama.cpp (vendored; see VENDOR.md) ------------------------------------
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

# --- GpTMax JNI bridge -------------------------------------------------------
add_library(gptmax SHARED
    gptmax_jni.cpp
)

target_include_directories(gptmax PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include)

find_library(log-lib log)
target_link_libraries(gptmax PRIVATE llama ${log-lib})

# arm64 codegen for Cortex-X/A7xx-class cores (dotprod is standard on target devices).
target_compile_options(gptmax PRIVATE
    -ffast-math
    -fvisibility=hidden
)
