// FlowStudio —— armv8.0 原子操作兜底实现。

// 背景：clang 在 armv8.0 基线上会为中大号的 std::atomic 生成 outline atomics，
// 即对外部符号 __aarch64_swp1_acq_rel / __aarch64_ldadd8_acq_rel 等的调用。
// NDK 的 libatomic.a 是个空壳（150 字节），libc.so 也不导出这些符号，
// 于是 so 能链接、能签名、能安装，但 dlopen 时找不到符号，应用直接起不来。

// 主修法是编译期加 -mno-outline-atomics（让它们内联成 ldaxr/stlxr）。
// 这份汇编是第二道保险：万一某个由内联 asm 或第三方对象引入的调用漏过来，
// 链接期仍有真实实现可以兜住，而不是产出一个装得上、跑不了的包。

// 只用 armv8.0 基线指令（LDXR/STXR + acquire/release 屏障），
// 不依赖 LSE（那需要 armv8.1），所以在任何 arm64 设备上都能跑。

        .text
        .arch   armv8-a

// ---- 1 字节交换：bswap1(ptr, val) -> 旧值 ----
        .global __aarch64_swp1_acq_rel
        .type   __aarch64_swp1_acq_rel, %function
        .balign 16
__aarch64_swp1_acq_rel:
1:      ldaxrb  w2, [x0]
        stlxrb  w3, w1, [x0]
        cbnz    w3, 1b
        mov     w0, w2
        ret
        .size   __aarch64_swp1_acq_rel, .-__aarch64_swp1_acq_rel

// ---- 8 字节交换 ----
        .global __aarch64_swp8_acq_rel
        .type   __aarch64_swp8_acq_rel, %function
        .balign 16
__aarch64_swp8_acq_rel:
1:      ldaxr   x2, [x0]
        stxr    w3, x1, [x0]
        cbnz    w3, 1b
        mov     x0, x2
        ret
        .size   __aarch64_swp8_acq_rel, .-__aarch64_swp8_acq_rel

// ---- 4 字节加并返回旧值 ----
        .global __aarch64_ldadd4_acq_rel
        .type   __aarch64_ldadd4_acq_rel, %function
        .balign 16
__aarch64_ldadd4_acq_rel:
1:      ldaxr   w2, [x0]
        add     w3, w2, w1
        stxr    w4, w3, [x0]
        cbnz    w4, 1b
        mov     w0, w2
        ret
        .size   __aarch64_ldadd4_acq_rel, .-__aarch64_ldadd4_acq_rel

// ---- 8 字节加并返回旧值 ----
        .global __aarch64_ldadd8_acq_rel
        .type   __aarch64_ldadd8_acq_rel, %function
        .balign 16
__aarch64_ldadd8_acq_rel:
1:      ldaxr   x2, [x0]
        add     x3, x2, x1
        stxr    w4, x3, [x0]
        cbnz    w4, 1b
        mov     x0, x2
        ret
        .size   __aarch64_ldadd8_acq_rel, .-__aarch64_ldadd8_acq_rel

// ---- 8 字节加（relaxed）----
        .global __aarch64_ldadd8_relax
        .type   __aarch64_ldadd8_relax, %function
        .balign 16
__aarch64_ldadd8_relax:
1:      ldxr    x2, [x0]
        add     x3, x2, x1
        stxr    w4, x3, [x0]
        cbnz    w4, 1b
        mov     x0, x2
        ret
        .size   __aarch64_ldadd8_relax, .-__aarch64_ldadd8_relax

// ---- 8 字节比较交换：旧值 == 期望 时写入新值，返回旧值 ----
        .global __aarch64_cas8_acq_rel
        .type   __aarch64_cas8_acq_rel, %function
        .balign 16
__aarch64_cas8_acq_rel:
1:      ldaxr   x3, [x0]
        cmp     x3, x1
        b.ne    2f
        stxr    w4, x2, [x0]
        cbnz    w4, 1b
2:      mov     x0, x3
        ret
        .size   __aarch64_cas8_acq_rel, .-__aarch64_cas8_acq_rel

        .section .note.GNU-stack,"",%progbits

