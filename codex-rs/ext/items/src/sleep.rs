use schemars::JsonSchema;
use serde::Deserialize;
use serde::Serialize;
use ts_rs::TS;

/// Display item emitted by the interruptible `clock.sleep` tool.
#[derive(Debug, Clone, Deserialize, Serialize, TS, JsonSchema, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
#[ts(rename_all = "camelCase")]
// schemars 0.8 inlined this schema at each use; keep it inline so generated schemas and SDK classes stay the same.
#[schemars(inline)]
pub struct SleepItem {
    pub id: String,
    #[ts(type = "number")]
    pub duration_ms: u64,
}
