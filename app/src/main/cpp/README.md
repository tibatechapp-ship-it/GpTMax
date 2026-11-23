# Native module

This directory contains the initial C++17 JNI bridge built with Android NDK/CMake.

The current native functions only report bridge/build status. They do not load a model or perform inference. The llama.cpp integration must be added from a reviewed, immutable upstream revision and tested on Android ARM64 before any inference capability is claimed.
