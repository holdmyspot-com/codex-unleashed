use schemars::JsonSchema;
use serde::Deserialize;
use serde::Serialize;
use ts_rs::TS;

/// Controls whether a mailbox message may resume an interrupted recipient.
#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq, JsonSchema, TS)]
#[serde(rename_all = "snake_case")]
pub enum MailboxWakePolicy {
    #[default]
    Always,
    UnlessInterrupted,
}

impl MailboxWakePolicy {
    pub fn is_always(&self) -> bool {
        *self == Self::Always
    }
}
