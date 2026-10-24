// FlowStudio —— emutls 兜底。
//
// 为什么需要：libc++abi 的 cxa_exception_storage.cpp 用 __emutls_get_address
// 实现 thread_local 的异常存储，而 Android 的 libc 在 21~34 任何 API 级别
// 都不导出这个符号（已逐级核对 sysroot stub）。正常编译路径下 clang 会走
// TLS 直接寻址、不会引用它；一旦某个静态库（这里就是 libc++abi）把它带进来，
// 链接仍然成功，但 dlopen 时会报
//   cannot locate symbol "__emutls_get_address"
// 结果是"装得上、跑不了"。所以这里给一个真实实现。
//
// 语义：每个线程一份、首次访问时按 size/align 分配并清零、随线程退出释放。
// 用 pthread_key + 析构回调实现，不依赖 TLS 描述符，任何 arm64 设备都能跑。

#include <pthread.h>
#include <cstdlib>
#include <cstring>
#include <new>

extern "C" {

// 与编译器生成的 emutls_control 布局一致（size, align, object）
struct EmutlsControl {
    size_t size;
    size_t align;
    void*  object;
};

namespace {

pthread_key_t g_key;
pthread_once_t g_once = PTHREAD_ONCE_INIT;

void destroyBlock(void* p) {
    std::free(p);
}

void makeKey() {
    pthread_key_create(&g_key, &destroyBlock);
}

}  // namespace

// 分配器往返：分配时用 aligned_alloc，释放必须配 free
void* __emutls_get_address(EmutlsControl* control) {
    if (!control) return nullptr;
    pthread_once(&g_once, &makeKey);

    void* block = pthread_getspecific(g_key);
    if (block) return block;

    size_t size = control->size ? control->size : 1;
    size_t align = control->align ? control->align : alignof(std::max_align_t);
    if (align < sizeof(void*)) align = sizeof(void*);

    // 用 posix_memalign：aligned_alloc 在 NDK 的 API 26 sysroot 里不可用。
    // 它分配的内存同样要用 free 释放（与 destroyBlock 一致）。
    const size_t rounded = (size + align - 1) / align * align;
    if (posix_memalign(&block, align, rounded) != 0 || !block) return nullptr;
    std::memset(block, 0, rounded);
    pthread_setspecific(g_key, block);
    return block;
}

}  // extern "C"
