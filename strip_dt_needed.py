#!/usr/bin/env python3
"""剥离 libllama.so 的 DT_NEEDED libcdsprpc.so + libOpenCL.so（空字符串替换）。

在 npm install 下载 llama.rn 的预编译 .so 后运行此脚本。
- 非骁龙设备没有 libcdsprpc.so 系统库
- 旧 GPU 驱动（OpenCL 1.2/2.0）缺少 clCreateBufferWithProperties（OpenCL 3.0）
这些 DT_NEEDED 导致 System.loadLibrary("llama") 在 dlopen 阶段失败。

此脚本将 DT_NEEDED "libcdsprpc.so" 和 "libOpenCL.so" 替换为空字符串，
Bionic 动态链接器会静默跳过空字符串的 DT_NEEDED 条目。
OpenCL UNDEF 符号由 libopencl_stub.so 提供（28 个桩函数）。

用法：
  python3 strip_dt_needed.py [path/to/libllama.so]
"""

import struct
import sys
import os

STRIP_LIBS = {'libcdsprpc.so', 'libOpenCL.so'}

def strip_dt_needed(path):
    if not os.path.exists(path):
        print(f"Error: {path} not found")
        return False

    with open(path, 'rb') as f:
        data = bytearray(f.read())

    # 验证 ELF magic
    if data[:4] != b'\x7fELF':
        print(f"Error: {path} is not an ELF file")
        return False

    # 读 ELF header
    e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from('<HHH', data, 0x3A)

    strtab_off = e_shoff + e_shstrndx * e_shentsize
    strtab_file_off = struct.unpack_from('<Q', data, strtab_off + 0x18)[0]

    def sh_name(ndx):
        end = data.find(b'\x00', strtab_file_off + ndx)
        return data[strtab_file_off + ndx:end].decode()

    dyn_off = dyn_size = 0
    dynstr_off = 0

    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name_ndx = struct.unpack_from('<I', data, off)[0]
        sh_file_off = struct.unpack_from('<Q', data, off + 0x18)[0]
        sh_size = struct.unpack_from('<Q', data, off + 0x20)[0]
        name = sh_name(name_ndx)
        if name == '.dynamic':
            dyn_off, dyn_size = sh_file_off, sh_size
        elif name == '.dynstr':
            dynstr_off = sh_file_off

    # 将 DT_NEEDED libcdsprpc.so 改为 DT_NEEDED ""（空字符串）
    stripped = False
    pos = dyn_off
    for i in range(dyn_size // 16):
        d_tag = struct.unpack_from('<q', data, pos)[0]
        if d_tag == 0:
            break
        if d_tag == 1:  # DT_NEEDED
            d_val = struct.unpack_from('<Q', data, pos + 8)[0]
            end = data.find(b'\x00', dynstr_off + d_val)
            name = data[dynstr_off + d_val:end].decode()
            if name in STRIP_LIBS:
                # 指向 .dynstr 中的空字符串（offset 0 就是 \x00）
                struct.pack_into('<Q', data, pos + 8, 0)
                stripped = True
                print(f"  Stripped DT_NEEDED: libcdsprpc.so → \"\"")
        pos += 16

    if not stripped:
        print("  No target DT_NEEDED entries found (already stripped?)")
        return True

    with open(path, 'wb') as f:
        f.write(data)

    print(f"  Done. Wrote {len(data)} bytes to {path}")
    return True

if __name__ == '__main__':
    path = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/jniLibs/arm64-v8a/libllama.so'
    print(f"Stripping DT_NEEDED libcdsprpc.so from {path}")
    ok = strip_dt_needed(path)
    sys.exit(0 if ok else 1)