/// The current Codex CLI version as embedded at compile time.
pub const CODEX_CLI_VERSION: &str = concat!(
    env!("CARGO_PKG_VERSION"),
    "+",
    env!("CODEX_UNLEASHED_BUILD_NUMBER")
);

pub const CODEX_UNLEASHED_URL: &str = "https://holdmyspot.com/codex-unleashed/";
