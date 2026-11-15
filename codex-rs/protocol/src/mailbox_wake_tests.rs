use super::InterAgentCommunication;

#[test]
fn legacy_inter_agent_communication_preserves_explicit_wakeup() {
    let value = serde_json::json!({
        "author": "/root/worker",
        "recipient": "/root",
        "other_recipients": [],
        "content": "new task",
        "trigger_turn": true
    });
    let mail: InterAgentCommunication = serde_json::from_value(value.clone()).expect("legacy mail");
    assert_eq!(mail.wake_policy, crate::MailboxWakePolicy::Always);
    assert_eq!(
        serde_json::to_value(mail).expect("serialize legacy mail"),
        value
    );
}

#[test]
fn inter_agent_communication_preserves_conditional_wake_policy() {
    let value = serde_json::json!({
        "author": "/root/worker",
        "recipient": "/root",
        "other_recipients": [],
        "content": "finished",
        "trigger_turn": true,
        "wake_policy": "unless_interrupted"
    });
    let mail: InterAgentCommunication =
        serde_json::from_value(value.clone()).expect("known policy");
    assert_eq!(serde_json::to_value(mail).expect("serialize result"), value);
}

#[test]
fn inter_agent_communication_rejects_unknown_wake_policy() {
    let value = serde_json::json!({
        "author": "/root/worker",
        "recipient": "/root",
        "content": "finished",
        "trigger_turn": true,
        "wake_policy": "ignore_user_interruption"
    });
    assert!(serde_json::from_value::<InterAgentCommunication>(value).is_err());
}
