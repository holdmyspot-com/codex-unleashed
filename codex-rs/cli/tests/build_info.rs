use std::process::Command;

use codex_utils_cargo_bin::cargo_bin;

#[test]
fn version_identifies_codex_unleashed_build() {
    let output = Command::new(cargo_bin("codex").expect("resolve codex executable"))
        .arg("--version")
        .output()
        .expect("run codex --version");

    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout).expect("UTF-8 version");
    let build_number = option_env!("CODEX_UNLEASHED_BUILD_NUMBER").unwrap_or("dev");
    assert_eq!(
        stdout,
        format!(
            "codex-cli {}+{build_number}\n(See --build-info for more information)\n",
            env!("CARGO_PKG_VERSION")
        )
    );
}

#[test]
fn build_info_reports_upstream_and_provider() {
    let output = Command::new(cargo_bin("codex").expect("resolve codex executable"))
        .arg("--build-info")
        .output()
        .expect("run codex --build-info");

    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );

    let stdout = String::from_utf8(output.stdout).expect("UTF-8 build information");
    assert!(stdout.contains("Codex Unleashed build information"));
    assert!(stdout.contains(&format!("Upstream version:  {}", env!("CARGO_PKG_VERSION"))));
    if let Some(build_number) = option_env!("CODEX_UNLEASHED_BUILD_NUMBER") {
        assert!(stdout.contains(&format!(
            "Codex CLI version: {}+{build_number}",
            env!("CARGO_PKG_VERSION")
        )));
        assert!(stdout.contains(&format!("Build number:      {build_number}")));
    }
    assert!(stdout.contains("Provided by:       Codex Unleashed"));
    assert!(
        stdout.contains("Project:           https://github.com/holdmyspot-com/codex-unleashed")
    );
}
