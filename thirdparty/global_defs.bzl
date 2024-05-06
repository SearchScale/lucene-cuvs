"""Global defines commonly used in all dependencies."""

LUCENE_CUVS_OFFLOAD_SM89 = [
    # When using clang, use this instead:
    # "--offload-arch=sm_89",
    "--gpu-architecture=sm_89",
    '-DCUTLASS_NVCC_ARCHS="89"',
]

LUCENE_CUVS_GLOBAL_COMPILE_FLAGS = [
    "-std=c++17",
    "-O3",

    # These flags assume NVCC as device compiler invoking clang for host
    # compilation.
    "--extended-lambda",
    "--expt-relaxed-constexpr",
    "-Xcompiler",
    "-fopenmp",
]

NVCC_COMPILE_FLAGS = [
    "--extended-lambda",
]

LUCENE_CUVS_GLOBAL_DEFINES = [
    "CUDA_API_PER_THREAD_DEFAULT_STREAM",

    # We expect to run on x86_64 for now.
    "RAFT_SYSTEM_LITTLE_ENDIAN=1",

    # CUDA prevents the use of libc++ by default. We know what we're doing.
    # Enable this when using clang as device compiler.
    # "_ALLOW_UNSUPPORTED_LIBCPP",

    # This enables the `cuda::mr` namespace required by recent versions of rmm.
    "LIBCUDACXX_ENABLE_EXPERIMENTAL_MEMORY_RESOURCE",
]
