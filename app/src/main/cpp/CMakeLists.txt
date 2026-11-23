cmake_minimum_required(VERSION 3.22.1)

project(gptmax_native LANGUAGES CXX)

add_library(gptmax_native SHARED native-lib.cpp)

target_compile_features(gptmax_native PRIVATE cxx_std_17)
target_compile_options(gptmax_native PRIVATE -Wall -Wextra -Wpedantic)

find_library(android-log log)

target_link_libraries(gptmax_native PRIVATE ${android-log})
