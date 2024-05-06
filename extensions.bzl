"""Third party dependencies."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

def _lucene_cuvs_dependencies_impl(_):
    http_archive(
        name = "local-remote-execution",
        urls = [
            "https://github.com/TraceMachina/nativelink/archive/8a632953b86395088e4ab8c1e160a650739549b7.zip",
        ],
        integrity = "sha256-L+I3608IU4mDSqLWJ6zJV5zA17zGVay8c8TvmLkoneY=",
        # Note: Keep this in sync with `flake.nix` and `devtools/up.sh`.
        strip_prefix = "nativelink-8a632953b86395088e4ab8c1e160a650739549b7/local-remote-execution",
    )

    http_archive(
        name = "cccl",
        build_file = "@lucene-cuvs//thirdparty:cccl.BUILD.bazel",
        integrity = "sha256-M4X16I7csfMzkUNGvKRt6h2CjPqEI4xPU2PiEimaKO4=",
        strip_prefix = "cccl-6a721a0057b4d7759731532fd8248a03d0276ec9",
        urls = [
            "https://github.com/NVIDIA/cccl/archive/6a721a0057b4d7759731532fd8248a03d0276ec9.zip",
        ],
        patches = [
            # This could be a pessimization. Probably shouldn't be upstreamed.
            "@lucene-cuvs//patches:cccl_cub_new.diff",

            # Clang doesn't like `new` in device code.
            "@lucene-cuvs//patches:cccl_thrust_allocator_new.diff",
            "@lucene-cuvs//patches:cccl_cub_uninitialized_copy.diff",
        ],
        patch_args = ["-p1"],
    )

    http_archive(
        name = "cutlass",
        build_file = "@lucene-cuvs//thirdparty:cutlass.BUILD.bazel",
        integrity = "sha256-zyDLhADi8hHx1eKtZ3oen+WF1aGUVk1Wud50GF7chTo=",
        strip_prefix = "cutlass-3.5.0",
        urls = [
            "https://github.com/NVIDIA/cutlass/archive/refs/tags/v3.5.0.zip",
        ],
        patches = [
            # TODO(aaronmondal): Figure out why this crashes clang.
            "@lucene-cuvs//patches:cutlass_ops.diff",

            # TODO(aaronmondal): Upstream.
            "@lucene-cuvs//patches:cutlass_fix_slice.diff",

            # Clang doesn't like these unrolls. It's unclear what a good default
            # is, so we rely on default unrolling which is already fairly
            # aggressive.
            "@lucene-cuvs//patches:cutlass_disable_incompatible_unrolls.diff",
        ],
        patch_args = ["-p1"],
    )

    http_archive(
        name = "cuvs",
        build_file = "@lucene-cuvs//thirdparty:cuvs.BUILD.bazel",
        integrity = "sha256-tf2mzQ+kw8acVImSSdZFxdnABwJz5MbOiDfrXQo5JC4=",
        strip_prefix = "cuvs-d44aa39f9b75dee3d3f5fdc038188333c1d177ac",
        urls = [
            "https://github.com/rapidsai/cuvs/archive/d44aa39f9b75dee3d3f5fdc038188333c1d177ac.zip",
        ],
        patches = [
            # TODO(aaronmondal): Upstream.
            "@lucene-cuvs//patches:cuvs_use_designated_initializers.diff",
        ],
        patch_args = ["-p1"],
    )

    http_archive(
        name = "fmt",
        build_file = "@lucene-cuvs//thirdparty:fmt.BUILD.bazel",
        integrity = "sha256-MSFRotE8gyf1ycWGrGz3zdwWWOj1Ptrg7FZQnI+lFsk=",
        strip_prefix = "fmt-10.2.1",
        urls = [
            "https://github.com/fmtlib/fmt/releases/download/10.2.1/fmt-10.2.1.zip",
        ],
    )

    http_archive(
        name = "raft",
        build_file = "@lucene-cuvs//thirdparty:raft.BUILD.bazel",
        integrity = "sha256-AEZINCR9AyYafw+5TSfiJb5/QGxwpengdn9NXGaE/jA=",
        strip_prefix = "raft-da3b9a9c442396a43a70efa725ca7f489605d632",
        urls = [
            "https://github.com/rapidsai/raft/archive/da3b9a9c442396a43a70efa725ca7f489605d632.zip",
        ],
        patches = [
            # TODO(aaronmondal): Upstream.
            "@lucene-cuvs//patches:raft_fix_hostdevice_template.diff",
            "@lucene-cuvs//patches:raft_topk_host_device.diff",
            "@lucene-cuvs//patches:raft_fix_intrinsic_warning.diff",

            # TODO(aaronmondal): This seems to be a bug in clang.
            "@lucene-cuvs//patches:raft_merge_in.diff",

            # TODO(aaronmondal): Figure out whether this should be treated as a
            #                    bug in libcxx or raft.
            "@lucene-cuvs//patches:raft_variant.diff",
            "@lucene-cuvs//patches:raft_variant_codepacking.diff",

            # Hack, but required. Clang expects consistend host/device usage.
            "@lucene-cuvs//patches:raft_l2_hostdevice.diff",

            # Clang doesn't seem to like these unrolls.
            "@lucene-cuvs//patches:raft_ivf_pq_codepacking_bad_unroll.diff",
        ],
        patch_args = ["-p1"],
    )

    http_archive(
        name = "rmm",
        build_file = "@lucene-cuvs//thirdparty:rmm.BUILD.bazel",
        integrity = "sha256-VdgRWpgHdbI/PjUiXmLiYYUaYB1k47nTv22JtU660Aw=",
        strip_prefix = "rmm-32cd537a55b81726940bb698013a0d684e338c86",
        urls = [
            "https://github.com/rapidsai/rmm/archive/32cd537a55b81726940bb698013a0d684e338c86.zip",
        ],
        patch_args = ["-p1"],
    )

    http_archive(
        name = "spdlog",
        build_file = "@lucene-cuvs//thirdparty:spdlog.BUILD.bazel",
        integrity = "sha256-Qp3986/BmE/rWeQUNTwhwRC8eWCfbXiZ1S9qo4hkb20=",
        strip_prefix = "spdlog-1.14.1",
        urls = [
            "https://github.com/gabime/spdlog/archive/refs/tags/v1.14.1.zip",
        ],
    )

lucene_cuvs_dependencies = module_extension(
    implementation = _lucene_cuvs_dependencies_impl,
)
