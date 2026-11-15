use super::*;
use pretty_assertions::assert_eq;

#[test_case::test_case(false; "background_result")]
#[test_case::test_case(true; "explicit_followup_remains_eligible")]
#[tokio::test]
async fn interruption_suppresses_only_background_wakeups(explicit: bool) {
    let queue = InputQueue::new();
    let mut mail = InterAgentCommunication::new(
        codex_protocol::AgentPath::root()
            .join("worker")
            .expect("worker"),
        codex_protocol::AgentPath::root(),
        Vec::new(),
        "finished".to_string(),
        /*trigger_turn*/ true,
    );
    if !explicit {
        mail.wake_policy = codex_protocol::MailboxWakePolicy::UnlessInterrupted;
    }
    queue
        .enqueue_mailbox_communication(mail.into(), Default::default())
        .await;
    assert_eq!(queue.suppress_interrupted_wakeups().await, explicit);
    let (retained, _) = queue.drain_mailbox_input_items().await;
    let [TurnInput::InterAgentCommunication(result)] = retained.as_slice() else {
        panic!("result must remain available");
    };
    assert_eq!(result.trigger_turn, explicit);
    assert!(queue.drain_mailbox_input_items().await.0.is_empty());
}

#[tokio::test]
async fn interruption_retains_unobserved_background_result_once() {
    let queue = InputQueue::new();
    let active = ActiveTurn::default();
    let mail: InterAgentCommunication = serde_json::from_value(serde_json::json!({
        "id": "amsg_result",
        "author": "/root/worker",
        "recipient": "/root",
        "content": "finished",
        "trigger_turn": true,
        "wake_policy": "unless_interrupted"
    }))
    .expect("background result");
    active
        .turn_state
        .lock()
        .await
        .pending_input
        .items
        .push(TurnInput::InterAgentCommunication(mail.clone().into()));

    queue.clear_pending(&active).await;
    let (retained, _) = queue.drain_mailbox_input_items().await;
    let [TurnInput::InterAgentCommunication(result)] = retained.as_slice() else {
        panic!("unobserved background result must remain available after interruption");
    };
    assert_eq!(result.id, mail.id);
    assert_eq!(result.author, mail.author);
    assert_eq!(result.content, mail.content);
    assert!(!result.trigger_turn);
    assert!(queue.drain_mailbox_input_items().await.0.is_empty());
}
