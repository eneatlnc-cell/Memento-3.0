cmake_minimum_required(VERSION 3.22.1)
project("llama_jni")

# ── 说明 ──
# 这个 CMake 只编译我们自己写的 libllama_jni.so（<1MB）。
# llama.cpp / ggml / mtmd 的 .so 由 Snapdragon toolchain docker 预编译后
# 直接放在 jniLibs/arm64-v8a/，这里作为 IMPORTED 库引用，不重新编译。
#
# 头文件（llama.h / mtmd.h / mtmd-helper.h）从 llama.cpp 编译产物拷贝到
# cpp/include/ 目录下。

# ── 预编译 .so 作为 IMPORTED 库 ──
set(JNILIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# libggml.so（ggml 核心）
add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES
    IMPORTED_LOCATION ${JNILIB_DIR}/libggml.so)

# libllama.so（推理引擎）
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
    IMPORTED_LOCATION ${JNILIB_DIR}/libllama.so)

# libmtmd.so（多模态）
add_library(mtmd SHARED IMPORTED)
set_target_properties(mtmd PROPERTIES
    IMPORTED_LOCATION ${JNILIB_DIR}/libmtmd.so)

# ── 编译我们自己的 JNI wrapper ──
add_library(llama_jni SHARED llama_jni.cpp)

target_include_directories(llama_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/include)

find_library(log-lib log)

target_link_libraries(llama_jni
    llama
    ggml
    mtmd
    ${log-lib})

# ── 编译选项 ──
# C++17（llama.cpp 要求），开启异常（JNI 边界需要）
set_target_properties(llama_jni PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON)

# 警告但不当错误（避免 NDK 版本差异导致构建失败）
target_compile_options(llama_jni PRIVATE -Wall -Wno-unused-parameter)
