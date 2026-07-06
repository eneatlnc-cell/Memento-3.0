// ── OpenCL smart proxy library ───────────────────────────────────────
// libllama.so 编译时链接了 28 个 OpenCL 符号（1.0～3.0）。
// 部分设备的 GPU 驱动仅支持 OpenCL 1.2/2.0，缺少 clCreateBufferWithProperties
// （OpenCL 3.0 API），导致 System.loadLibrary("llama") 在 dlopen 阶段失败。
//
// 策略（dlopen/dlsym 动态代理）：
//   1. 构造时用 RTLD_LAZY 打开 libOpenCL.so（允许部分符号缺失）
//   2. 每个导出函数首次调用时 atomic double-check + dlsym 查找真实实现
//   3. 找到 → 委托调用；未找到 → 返回安全 fallback
//   4. 线程安全：atomic acquire/release 保证无锁无竞态
//
// 配合：DT_NEEDED libOpenCL.so 已从 libllama.so 中剥离（空字符串替换），
//       确保系统 OpenCL 不会在 stub 之前被加载。
//
// 编译：CMake → libopencl_stub.so，链接 dl。
// 加载：System.loadLibrary("opencl_stub") → System.loadLibrary("llama")
// ──────────────────────────────────────────────────────────────────────

#include <stdint.h>
#include <stddef.h>
#include <stdatomic.h>
#include <dlfcn.h>
#include <sched.h>
#include <android/log.h>

#define TAG "opencl_stub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// ── OpenCL 类型定义（最小化） ──────────────────────────────────────────

typedef int32_t  cl_int;
typedef uint32_t cl_uint;
typedef uint32_t cl_ulong;
typedef uint64_t cl_mem;
typedef uint64_t cl_context;
typedef uint64_t cl_command_queue;
typedef uint64_t cl_kernel;
typedef uint64_t cl_program;
typedef uint64_t cl_event;
typedef uint64_t cl_platform_id;
typedef uint64_t cl_device_id;
typedef uint64_t cl_sampler;
typedef cl_uint   cl_mem_flags;
typedef cl_uint   cl_device_type;
typedef cl_uint   cl_platform_info;
typedef cl_uint   cl_device_info;
typedef cl_uint   cl_program_build_info;
typedef cl_uint   cl_kernel_work_group_info;
typedef cl_uint   cl_command_queue_properties;
typedef cl_uint   cl_bool;

typedef struct { cl_uint image_channel_order; cl_uint image_channel_data_type; } cl_image_format;
typedef struct {
  cl_uint image_type; size_t image_width, image_height, image_depth;
  size_t image_array_size, image_row_pitch, image_slice_pitch;
  cl_uint num_mip_levels, num_samples; cl_mem buffer;
} cl_image_desc;
typedef struct { cl_uint type; void *value; } cl_mem_properties;
typedef struct { cl_uint type; void *value; } cl_context_properties;

// ── 动态代理核心 ──────────────────────────────────────────────────────

static void *g_ocl = NULL;
static int   g_ocl_load_failed = 0;

__attribute__((constructor))
static void opencl_proxy_init(void) {
  // RTLD_LAZY: 延迟符号解析，允许部分符号缺失
  // RTLD_LOCAL: 不污染全局符号表
  g_ocl = dlopen("libOpenCL.so", RTLD_LAZY | RTLD_LOCAL);
  g_ocl_load_failed = !g_ocl;
  if (g_ocl) {
    LOGI("loaded real libOpenCL.so (vendor GPU driver, lazy resolution)");
  } else {
    LOGI("libOpenCL.so not available on this device, all OpenCL calls will fall back");
  }
}

// ── 无锁三态原子符号解析 ─────────────────────────────────────────────
// 每个符号一个 ocl_sym_t，内嵌在导出函数中作为 static 变量。
// 三态 CAS 保证同一符号只被一个线程解析，其余线程自旋等待。
//
// 状态机：
//   resolved: 0 → 未解析（初始态）
//             2 → 解析中（CAS 抢占，唯一赢家执行 dlsym）
//             1 → 已解析（发布结果，所有线程走快速路径）

typedef struct {
  atomic_int resolved;  // 0=未解析, 2=解析中, 1=已解析
  void      *ptr;       // 解析结果：真实函数指针 或 fallback sentinel
  const char *name;     // dlsym 查找的符号名
  intptr_t    fallback; // 未找到时的默认值
} ocl_sym_t;

static inline void ocl_resolve(ocl_sym_t *s) {
  // 快速路径：已解析，acquire 保证看到 ptr 的写入
  if (atomic_load_explicit(&s->resolved, memory_order_acquire) == 1) {
    return;
  }

  // 慢速路径：CAS 抢占解析权 0 → 2
  int expected = 0;
  if (atomic_compare_exchange_strong_explicit(
          &s->resolved, &expected, 2,
          memory_order_acq_rel, memory_order_acquire)) {

    // === 唯一赢家执行解析 ===
    void *p = NULL;
    if (!g_ocl_load_failed) {
      p = dlsym(g_ocl, s->name);
      if (!p) {
        LOGW("OpenCL: %s not found in vendor driver, using fallback", s->name);
      }
    }
    if (!p) p = (void *)s->fallback;
    s->ptr = p;

    // 发布结果：2 → 1，release 保证 ptr 对所有等待线程可见
    atomic_store_explicit(&s->resolved, 1, memory_order_release);
  } else {
    // === 其他线程自旋等待解析完成 ===
    // dlsym 预期很快（< 1µs），用 yield 降低功耗
    while (atomic_load_explicit(&s->resolved, memory_order_acquire) != 1) {
#if defined(__aarch64__)
      __asm__ volatile("yield" ::: "memory");
#elif defined(__arm__)
      __asm__ volatile("yield" ::: "memory");
#else
      sched_yield();
#endif
    }
  }
}

// ── 导出函数宏 ────────────────────────────────────────────────────────
// 每个函数内嵌一个 static ocl_sym_t，首次调用时 ocl_resolve 执行 dlsym。
// 后续调用直接走 acquire 快速路径，零开销。

#define OCL_FUNC(ret, name, args, params, err_val) \
  __attribute__((visibility("default"))) \
  ret name args { \
    static ocl_sym_t sym = { 0, NULL, #name, (intptr_t)(err_val) }; \
    static ret (*real) args = NULL; \
    ocl_resolve(&sym); \
    real = (ret (*) args)sym.ptr; \
    if ((intptr_t)real == (intptr_t)(err_val)) return (ret)(err_val); \
    return real params; \
  }

// ── 28 个 OpenCL 导出函数 ─────────────────────────────────────────────

OCL_FUNC(cl_mem, clCreateBufferWithProperties,
  (cl_context c, const cl_mem_properties *p, cl_mem_flags f, size_t s, void *h, cl_int *e),
  (c, p, f, s, h, e), 0)

OCL_FUNC(cl_int, clBuildProgram,
  (cl_program p, cl_uint n, const cl_device_id *d, const char *o, void (*cb)(cl_program,void*), void *u),
  (p, n, d, o, cb, u), -30)

OCL_FUNC(cl_mem, clCreateBuffer,
  (cl_context c, cl_mem_flags f, size_t s, void *h, cl_int *e),
  (c, f, s, h, e), 0)

OCL_FUNC(cl_command_queue, clCreateCommandQueue,
  (cl_context c, cl_device_id d, cl_command_queue_properties p, cl_int *e),
  (c, d, p, e), 0)

OCL_FUNC(cl_context, clCreateContext,
  (const cl_context_properties *p, cl_uint n, const cl_device_id *d, void (*cb)(const char*,const void*,size_t,void*), void *u, cl_int *e),
  (p, n, d, cb, u, e), 0)

OCL_FUNC(cl_mem, clCreateImage,
  (cl_context c, cl_mem_flags f, const cl_image_format *fmt, const cl_image_desc *desc, void *h, cl_int *e),
  (c, f, fmt, desc, h, e), 0)

OCL_FUNC(cl_kernel, clCreateKernel,
  (cl_program p, const char *n, cl_int *e),
  (p, n, e), 0)

OCL_FUNC(cl_program, clCreateProgramWithSource,
  (cl_context c, cl_uint n, const char **s, const size_t *l, cl_int *e),
  (c, n, s, l, e), 0)

OCL_FUNC(cl_mem, clCreateSubBuffer,
  (cl_mem b, cl_mem_flags f, cl_uint t, const void *i, cl_int *e),
  (b, f, t, i, e), 0)

OCL_FUNC(cl_int, clEnqueueBarrierWithWaitList,
  (cl_command_queue q, cl_uint n, const cl_event *el, cl_event *ev),
  (q, n, el, ev), -30)

OCL_FUNC(cl_int, clEnqueueCopyBuffer,
  (cl_command_queue q, cl_mem s, cl_mem d, size_t so, size_t do_, size_t cb, cl_uint n, const cl_event *el, cl_event *ev),
  (q, s, d, so, do_, cb, n, el, ev), -30)

OCL_FUNC(cl_int, clEnqueueFillBuffer,
  (cl_command_queue q, cl_mem b, const void *p, size_t ps, size_t o, size_t cb, cl_uint n, const cl_event *el, cl_event *ev),
  (q, b, p, ps, o, cb, n, el, ev), -30)

OCL_FUNC(cl_int, clEnqueueMarkerWithWaitList,
  (cl_command_queue q, cl_uint n, const cl_event *el, cl_event *ev),
  (q, n, el, ev), -30)

OCL_FUNC(cl_int, clEnqueueNDRangeKernel,
  (cl_command_queue q, cl_kernel k, cl_uint wd, const size_t *go, const size_t *gs, const size_t *lo, cl_uint n, const cl_event *el, cl_event *ev),
  (q, k, wd, go, gs, lo, n, el, ev), -30)

OCL_FUNC(cl_int, clEnqueueReadBuffer,
  (cl_command_queue q, cl_mem b, cl_bool bl, size_t o, size_t cb, void *p, cl_uint n, const cl_event *el, cl_event *ev),
  (q, b, bl, o, cb, p, n, el, ev), -30)

OCL_FUNC(cl_int, clEnqueueWriteBuffer,
  (cl_command_queue q, cl_mem b, cl_bool bl, size_t o, size_t cb, const void *p, cl_uint n, const cl_event *el, cl_event *ev),
  (q, b, bl, o, cb, p, n, el, ev), -30)

OCL_FUNC(cl_int, clFinish, (cl_command_queue q), (q), -30)
OCL_FUNC(cl_int, clFlush,  (cl_command_queue q), (q), -30)

OCL_FUNC(cl_int, clGetDeviceIDs,
  (cl_platform_id p, cl_device_type t, cl_uint n, cl_device_id *d, cl_uint *r),
  (p, t, n, d, r), -30)

OCL_FUNC(cl_int, clGetDeviceInfo,
  (cl_device_id d, cl_device_info pn, size_t pvs, void *pv, size_t *psr),
  (d, pn, pvs, pv, psr), -30)

OCL_FUNC(cl_int, clGetKernelWorkGroupInfo,
  (cl_kernel k, cl_device_id d, cl_kernel_work_group_info pn, size_t pvs, void *pv, size_t *psr),
  (k, d, pn, pvs, pv, psr), -30)

OCL_FUNC(cl_int, clGetPlatformIDs,
  (cl_uint n, cl_platform_id *p, cl_uint *r),
  (n, p, r), -30)

OCL_FUNC(cl_int, clGetPlatformInfo,
  (cl_platform_id p, cl_platform_info pn, size_t pvs, void *pv, size_t *psr),
  (p, pn, pvs, pv, psr), -30)

OCL_FUNC(cl_int, clGetProgramBuildInfo,
  (cl_program p, cl_device_id d, cl_program_build_info pn, size_t pvs, void *pv, size_t *psr),
  (p, d, pn, pvs, pv, psr), -30)

OCL_FUNC(cl_int, clReleaseEvent,    (cl_event e), (e), -30)
OCL_FUNC(cl_int, clReleaseMemObject, (cl_mem m), (m), -30)
OCL_FUNC(cl_int, clReleaseProgram,   (cl_program p), (p), -30)

OCL_FUNC(cl_int, clSetKernelArg,
  (cl_kernel k, cl_uint idx, size_t s, const void *v),
  (k, idx, s, v), -30)

OCL_FUNC(cl_int, clWaitForEvents,
  (cl_uint n, const cl_event *el),
  (n, el), -30)