load("@bazel_skylib//lib:unittest.bzl", "analysistest", "asserts")

def _native_c_standard_test_impl(ctx):
    env = analysistest.begin(ctx)
    actions = [
        action
        for action in analysistest.target_actions(env)
        if action.mnemonic == "CppCompile" and any([arg.endswith(".c") for arg in action.argv])
    ]
    asserts.true(env, len(actions) > 0, "The fixture must compile native C sources")
    for action in actions:
        asserts.true(env, action.argv[0].endswith("cl.exe"), "The fixture uses the native MSVC compiler configuration")
        standards = [arg for arg in action.argv if arg.startswith("/std:")]
        asserts.equals(env, ["/std:c11"], standards, "Each C action selects C11 exactly once")
        asserts.true(env, "/WX" in action.argv, "C compilation retains fatal warnings")
    return analysistest.end(env)

native_c_standard_test = analysistest.make(
    _native_c_standard_test_impl,
    config_settings = {
        "//command_line_option:platforms": str(Label("//:windows_x86_64_msvc")),
        "//command_line_option:extra_toolchains": [str(Label("//tools/aws-lc-standard:analysis_toolchain"))],
        "//command_line_option:features": ["static_link_msvcrt", "-dynamic_link_msvcrt", "treat_warnings_as_errors"],
    },
)

def _toolchain_default_test_impl(ctx):
    env = analysistest.begin(ctx)
    actions = [action for action in analysistest.target_actions(env) if action.mnemonic == "CppCompile"]
    asserts.true(env, len(actions) > 0, "The control must compile a C source")
    for action in actions:
        asserts.equals(env, ["/std:c17"], [arg for arg in action.argv if arg.startswith("/std:")])
    return analysistest.end(env)

toolchain_default_test = analysistest.make(
    _toolchain_default_test_impl,
    config_settings = {
        "//command_line_option:platforms": str(Label("//:windows_x86_64_msvc")),
        "//command_line_option:extra_toolchains": [str(Label("//tools/aws-lc-standard:analysis_toolchain"))],
        "//command_line_option:features": ["static_link_msvcrt", "-dynamic_link_msvcrt"],
    },
)
