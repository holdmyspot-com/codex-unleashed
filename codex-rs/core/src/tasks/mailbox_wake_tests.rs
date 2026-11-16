use crate::session::TurnInput;
use crate::session::tests::make_session_and_context_with_auth_and_config_and_rx;
use codex_extension_api::ExtensionRegistryBuilder;
use codex_login::CodexAuth;
use codex_protocol::AgentPath;
use codex_protocol::MailboxWakePolicy;
use codex_protocol::protocol::InterAgentCommunication;
use codex_protocol::protocol::TurnAbortReason;
use std::sync::Arc;
use std::time::Duration;

#[test_case::test_case(TurnAbortReason::Interrupted; "interrupted")]
#[test_case::test_case(TurnAbortReason::Replaced; "replaced")]
#[tokio::test]
async fn background_subagent_reserved_turn_preserves_unrelated_pending_input(
    reason: TurnAbortReason,
) {
    let (session, _context, _events) = make_session_and_context_with_auth_and_config_and_rx(
        CodexAuth::from_api_key("test"),
        Vec::new(),
        |_| {},
    )
    .await;
    let pending = TurnInput::ResponseItem(
        codex_protocol::models::ResponseItem::Message {
            id: None,
            encrypted_content: None,
            status: None,
            role: "user".to_string(),
            content: vec![codex_protocol::models::ContentItem::InputText {
                text: "unrelated pending input".to_string(),
            }],
            phase: None,
            internal_chat_message_metadata_passthrough: None,
        }
        .into(),
    );
    let mut mail = InterAgentCommunication::new(
        AgentPath::root().join("worker").expect("worker"),
        AgentPath::root(),
        Vec::new(),
        "finished".to_string(),
        /*trigger_turn*/ true,
    );
    mail.wake_policy = MailboxWakePolicy::UnlessInterrupted;
    let turn_state = {
        let mut active = session.active_turn.lock().await;
        Arc::clone(&active.get_or_insert_with(Default::default).turn_state)
    };
    session
        .input_queue
        .extend_pending_input_for_turn_state(
            turn_state.as_ref(),
            vec![
                pending.clone(),
                TurnInput::InterAgentCommunication(mail.clone().into()),
                pending.clone(),
            ],
        )
        .await;

    session.abort_all_tasks(reason).await;

    assert!(session.active_turn.lock().await.is_none());
    assert_eq!(
        session
            .input_queue
            .take_pending_input_for_turn_state(turn_state.as_ref())
            .await,
        vec![pending.clone(), pending]
    );
    let (results, _) = session.input_queue.drain_mailbox_input_items().await;
    let [TurnInput::InterAgentCommunication(result)] = results.as_slice() else {
        panic!("unobserved child result must remain queued exactly once");
    };
    mail.trigger_turn = false;
    assert_eq!(&**result, &mail);
    assert!(
        session
            .input_queue
            .drain_mailbox_input_items()
            .await
            .0
            .is_empty()
    );
}

#[derive(Default)]
struct PauseTaskRegistration {
    entered: tokio::sync::Notify,
    release: tokio::sync::Notify,
    aborted: std::sync::Mutex<Vec<TurnAbortReason>>,
}

impl codex_extension_api::TurnLifecycleContributor for PauseTaskRegistration {
    fn on_turn_abort<'a>(
        &'a self,
        input: codex_extension_api::TurnAbortInput<'a>,
    ) -> codex_extension_api::ExtensionFuture<'a, ()> {
        Box::pin(async move {
            self.aborted
                .lock()
                .expect("abort causes")
                .push(input.reason);
        })
    }

    fn on_turn_start<'a>(
        &'a self,
        _input: codex_extension_api::TurnStartInput<'a>,
    ) -> codex_extension_api::ExtensionFuture<'a, ()> {
        Box::pin(async move {
            self.entered.notify_one();
            self.release.notified().await;
        })
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ReservedMailbox {
    BackgroundResult,
    MixedMessages,
    ExplicitFollowup,
}

#[test_case::test_case(ReservedMailbox::BackgroundResult; "background_only")]
#[test_case::test_case(ReservedMailbox::MixedMessages; "mixed_messages")]
#[test_case::test_case(ReservedMailbox::ExplicitFollowup; "explicit_followup")]
#[tokio::test]
async fn background_subagent_interruption_before_registration_preserves_result_without_revival(
    contents: ReservedMailbox,
) {
    let (mut session, _context, _events) = make_session_and_context_with_auth_and_config_and_rx(
        CodexAuth::from_api_key("test"),
        Vec::new(),
        |config| {
            config.model_provider.base_url = Some("http://127.0.0.1:9/v1".to_string());
            config.model_provider.request_max_retries = Some(0);
            config.model_provider.stream_max_retries = Some(0);
        },
    )
    .await;
    let gate = Arc::new(PauseTaskRegistration::default());
    let mut extensions = ExtensionRegistryBuilder::<crate::config::Config>::new();
    extensions.turn_lifecycle_contributor(gate.clone());
    Arc::get_mut(&mut session)
        .expect("exclusive session")
        .services
        .extensions = Arc::new(extensions.build());
    let mut mail = InterAgentCommunication::new(
        AgentPath::root().join("worker").expect("worker"),
        AgentPath::root(),
        Vec::new(),
        "finished".to_string(),
        /*trigger_turn*/ true,
    );
    mail.wake_policy = MailboxWakePolicy::UnlessInterrupted;
    let unrelated = InterAgentCommunication::new(
        AgentPath::root().join("other").expect("other agent"),
        AgentPath::root(),
        Vec::new(),
        "unrelated queued message".to_string(),
        contents == ReservedMailbox::ExplicitFollowup,
    );
    let followup_options = codex_protocol::turn_input::TurnStartOptions {
        parent_turn_id: Some("original-parent-turn".to_string()),
        root_turn_id: Some("original-root-turn".to_string()),
        ..Default::default()
    };
    session
        .input_queue
        .enqueue_mailbox_communication(mail.clone().into(), followup_options.clone())
        .await;
    if contents != ReservedMailbox::BackgroundResult {
        session
            .input_queue
            .enqueue_mailbox_communication(unrelated.clone().into(), followup_options.clone())
            .await;
    }
    let scheduler = {
        let session = session.clone();
        tokio::spawn(async move {
            session
                .maybe_start_turn_for_pending_work_with_sub_id("automatic".to_string())
                .await;
        })
    };
    tokio::time::timeout(Duration::from_secs(5), gate.entered.notified())
        .await
        .expect("automatic turn reaches registration boundary");
    session.abort_all_tasks(TurnAbortReason::Interrupted).await;
    gate.release.notify_one();
    scheduler.await.expect("scheduler exits");
    let interrupted = session.is_interrupted();
    let revived = session.active_turn.lock().await.is_some();
    if revived {
        session.abort_all_tasks(TurnAbortReason::Interrupted).await;
    }
    assert!(
        interrupted,
        "interrupting an automatic reservation must mark it interrupted"
    );
    assert!(
        !revived,
        "an interrupted reservation must not register a task"
    );
    assert_eq!(
        *gate.aborted.lock().expect("abort causes"),
        vec![TurnAbortReason::Interrupted]
    );
    let (pending, recovered_options) = session.input_queue.drain_mailbox_input_items().await;
    let Some(TurnInput::InterAgentCommunication(retained)) = pending.first() else {
        panic!("accepted but unobserved result must remain available");
    };
    assert_eq!(retained.author, mail.author);
    assert_eq!(retained.content, mail.content);
    assert!(!retained.trigger_turn);
    if contents != ReservedMailbox::BackgroundResult {
        let [_, TurnInput::InterAgentCommunication(retained)] = pending.as_slice() else {
            panic!("unrelated mailbox input must be available to the next explicit turn");
        };
        assert_eq!(&**retained, &unrelated);
        if contents == ReservedMailbox::ExplicitFollowup {
            assert_eq!(
                recovered_options.parent_turn_id,
                followup_options.parent_turn_id
            );
            assert_eq!(
                recovered_options.root_turn_id,
                followup_options.root_turn_id
            );
        }
    } else {
        assert_eq!(pending.len(), 1);
    }
    assert!(
        session
            .input_queue
            .drain_mailbox_input_items()
            .await
            .0
            .is_empty()
    );
}
