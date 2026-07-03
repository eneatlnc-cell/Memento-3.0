# jniLibs/arm64-v8a/ — llama.cpp 预编译原生库
#
# 这些 .so 文件由 Snapdragon toolchain docker 编译产出，不纳入版本控制。
# 构建前需把以下文件放到本目录：
#
# 必需（10 个）：
#   libggml.so              — GGML 核心 + 后端注册表
#   libggml-cpu.so          — CPU 后端（NEON SIMD）
#   libggml-opencl.so       — Adreno GPU 后端
#   libggml-hexagon.so      — Hexagon 后端入口（dispatcher）
#   libggml-htp-v73.so      — HTP v73 skel（骁龙 8 Gen 2）
#   libggml-htp-v75.so      — HTP v75 skel（骁龙 8 Gen 3）
#   libggml-htp-v79.so      — HTP v79 skel（骁龙 8 Gen 4 / 8 Elite）
#   libggml-htp-v81.so      — HTP v81 skel（新一代）
#   libllama.so             — libllama 推理库
#   libmtmd.so              — 多模态库（mtmd.h）
#
# 编译方法（在含 Docker 的环境执行）：
#
#   git clone https://github.com/ggml-org/llama.cpp.git
#   cd llama.cpp
#   git submodule update --init --recursive
#
#   docker run -it -u $(id -u):$(id -g) \
#     --volume $(pwd):/workspace \
#     --platform linux/amd64 \
#     ghcr.io/snapdragon-toolchain/arm64-android:v0.7
#
#   # 容器内：
#   cp docs/backend/snapdragon/CMakeUserPresets.json .
#   cmake --preset arm64-android-snapdragon-release -B build-snapdragon
#   cmake --build build-snapdragon -j
#   cmake --install build-snapdragon --prefix pkg-snapdragon/llama.cpp
#
#   # 产物在 pkg-snapdragon/llama.cpp/lib/*.so，拷贝到本目录
#
# 注意：
# - HTP v73/v75/v79/v81 四个 skel 必须全部打包，运行时按设备 SoC 动态选择
# - libcdsprpc.so 是系统库（/vendor/lib64/），不要打包进 APK
# - 16KB page alignment 已由 Snapdragon toolchain v0.7 默认满足
