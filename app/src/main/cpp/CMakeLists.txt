cmake_minimum_required(VERSION 3.22.1)
project("llama_jni")

# ── 说明 ──
# 这个 CMake 只编译我们自己写的 libllama_jni.so（<1MB）。
# llama.cpp + ggml + mtmd + Hexagon + OpenCL 全部静态链接进 libllama.so，
# 该 .so 来自 llama.rn 0.12.5 预编译产物（librnllama_v8_2_dotprod_i8mm_hexagon_opencl.so 重命名）。
#
# 头文件（llama.h / mtmd.h / mtmd-helper.h）从 llama.rn npm 包的 cpp/ 目录拷贝。

# ── 预编译合体库作为 IMPORTED ──
set(JNILIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
    IMPORTED_LOCATION ${JNILIB_DIR}/libllama.so)

# ── 编译我们自己的 JNI wrapper ──
add_library(llama_jni SHARED llama_jni.cpp)

target_include_directories(llama_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/include)

find_library(log-lib log)

target_link_libraries(llama_jni
    llama
    ${log-lib})

# ── 编译选项 ──
set_target_properties(llama_jni PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON)

target_compile_options(llama_jni PRIVATE -Wall -Wno-unused-parameter)
