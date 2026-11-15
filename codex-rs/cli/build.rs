fn main() {
    let build_number =
        std::env::var("CODEX_UNLEASHED_BUILD_NUMBER").unwrap_or_else(|_| "dev".to_string());
    println!("cargo:rustc-env=CODEX_UNLEASHED_BUILD_NUMBER={build_number}");
    println!("cargo:rerun-if-env-changed=CODEX_UNLEASHED_BUILD_NUMBER");

    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        println!("cargo:rustc-link-arg=-ObjC");
    }
}
