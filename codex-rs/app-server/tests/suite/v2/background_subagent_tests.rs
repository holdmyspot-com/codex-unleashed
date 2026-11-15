use super::*;
use codex_app_server_protocol::ThreadItemPayload;
use pretty_assertions::assert_eq;
use std::time::Duration;

#[derive(Clone, Copy, PartialEq, Eq)]
enum GoalScenario {
    NoGoal,
    ActiveGoal,
}

#[test_case::test_case(GoalScenario::NoGoal; "ordinary")]
#[test_case::test_case(GoalScenario::ActiveGoal; "active_goal")]
#[tokio::test]
async fn app_server_resumes_parent_after_background_child_finishes(
    goal: GoalScenario,
) -> Result<()> {
    let server = responses::start_mock_server().await;
    let spawn_args = serde_json::to_string(&json!({
        "message": "complete the background task",
        "task_name": "worker",
        "fork_turns": "none",
    }))?;
    let mut spawn_events = vec![responses::ev_response_created("spawn")];
    if goal == GoalScenario::ActiveGoal {
        spawn_events.push(responses::ev_function_call(
            "background-create-goal",
            "create_goal",
            r#"{"objective":"Complete the delegated work after the worker finishes"}"#,
        ));
    }
    spawn_events.extend([
        responses::ev_function_call_with_namespace(
            "background-spawn",
            MULTI_AGENT_V2_NAMESPACE,
            "spawn_agent",
            &spawn_args,
        ),
        responses::ev_completed("spawn"),
    ]);
    responses::mount_sse_once_match(
        &server,
        |req: &wiremock::Request| {
            body_contains(req, "delegate and remain available")
                && !body_contains(req, "background-spawn")
        },
        responses::sse(spawn_events),
    )
    .await;
    let child = responses::mount_response_once_match(
        &server,
        |req: &wiremock::Request| {
            body_contains(req, "complete the background task")
                && !body_contains(req, "background-spawn")
        },
        responses::sse_response(responses::sse(vec![
            responses::ev_response_created("child"),
            responses::ev_assistant_message("child-message", "background task finished"),
            responses::ev_completed("child"),
        ]))
        .set_delay(Duration::from_secs(5)),
    )
    .await;
    responses::mount_sse_once_match(
        &server,
        |req: &wiremock::Request| {
            body_contains(req, "background-spawn")
                && !body_contains(req, "answer an unrelated question")
                && !body_contains(req, "Message Type: FINAL_ANSWER")
        },
        responses::sse(vec![
            responses::ev_response_created("yield"),
            responses::ev_assistant_message("yield-message", "The worker is running."),
            responses::ev_completed("yield"),
        ]),
    )
    .await;
    responses::mount_sse_once_match(
        &server,
        |req: &wiremock::Request| {
            body_contains(req, "answer an unrelated question")
                && !body_contains(req, "Message Type: FINAL_ANSWER")
        },
        responses::sse(vec![
            responses::ev_response_created("aside"),
            responses::ev_assistant_message("aside-message", "The unrelated answer."),
            responses::ev_completed("aside"),
        ]),
    )
    .await;
    let resume_events = if goal == GoalScenario::ActiveGoal {
        responses::mount_sse_once_match(
            &server,
            |req: &wiremock::Request| body_contains(req, "background-complete-goal"),
            responses::sse(vec![
                responses::ev_response_created("goal-complete"),
                responses::ev_assistant_message(
                    "resume-message",
                    "The delegated task is complete.",
                ),
                responses::ev_completed("goal-complete"),
            ]),
        )
        .await;
        vec![
            responses::ev_response_created("resume"),
            responses::ev_function_call(
                "background-complete-goal",
                "update_goal",
                r#"{"status":"complete"}"#,
            ),
            responses::ev_completed("resume"),
        ]
    } else {
        vec![
            responses::ev_response_created("resume"),
            responses::ev_assistant_message("resume-message", "The delegated task is complete."),
            responses::ev_completed("resume"),
        ]
    };
    let result = responses::mount_sse_once_match(
        &server,
        |req: &wiremock::Request| {
            body_contains(req, "Message Type: FINAL_ANSWER")
                && !body_contains(req, "background-complete-goal")
        },
        responses::sse(resume_events),
    )
    .await;
    let codex_home = TempDir::new()?;
    MockResponsesConfig::new(&server.uri())
        .enable_feature(Feature::Collab)
        .enable_feature(Feature::MultiAgentV2)
        .enable_feature(Feature::Goals)
        .disable_feature(Feature::EnableRequestCompression)
        .write(codex_home.path())?;
    write_models_cache(codex_home.path()).await?;
    let mut app = TestAppServer::builder()
        .with_codex_home(codex_home.path())
        .build_initialized()
        .await?;
    let root = app
        .start_thread(ThreadStartParams {
            model: Some("gpt-5.4".to_string()),
            history_mode: Some(ThreadHistoryMode::Legacy),
            ..Default::default()
        })
        .await?
        .thread;

    let first = app
        .start_turn_and_wait_for_completion(TurnStartParams {
            thread_id: root.id.clone(),
            input: vec![V2UserInput::Text {
                text: if goal == GoalScenario::ActiveGoal {
                    "Create a formal goal, then delegate and remain available".to_string()
                } else {
                    "delegate and remain available".to_string()
                },
                text_elements: Vec::new(),
            }],
            ..Default::default()
        })
        .await?;
    assert_eq!(first.turn.status, TurnStatus::Completed);
    let unsolicited = timeout(
        Duration::from_millis(300),
        app.read_stream_until_matching_notification(
            "premature parent continuation",
            |notification| {
                notification.method == "turn/started"
                    && notification.params.as_ref().is_some_and(|params| {
                        params["threadId"] == root.id && params["turn"]["id"] != first.turn.id
                    })
            },
        ),
    )
    .await;
    assert!(
        unsolicited.is_err(),
        "parent must stay idle until the child reports completion"
    );
    if goal == GoalScenario::ActiveGoal {
        let state: codex_app_server_protocol::ThreadGoalGetResponse = app
            .request(|request_id| ClientRequest::ThreadGoalGet {
                request_id,
                params: codex_app_server_protocol::ThreadGoalGetParams {
                    thread_id: root.id.clone(),
                },
            })
            .await?;
        assert_eq!(
            state.goal.context("created goal")?.status,
            codex_app_server_protocol::ThreadGoalStatus::Active
        );
    }
    let aside = app
        .start_turn_and_wait_for_completion(TurnStartParams {
            thread_id: root.id.clone(),
            input: vec![V2UserInput::Text {
                text: "answer an unrelated question".to_string(),
                text_elements: Vec::new(),
            }],
            ..Default::default()
        })
        .await?;
    assert_eq!(aside.turn.status, TurnStatus::Completed);
    assert!(aside.turn.items.iter().any(|item| {
        matches!(&item.payload, ThreadItemPayload::AgentMessage { text, .. } if text == "The unrelated answer.")
    }));
    let child_requests: Vec<_> = child
        .requests()
        .into_iter()
        .filter(|request| request.body_json()["client_metadata"]["thread_id"] != root.id)
        .collect();
    assert_eq!(child_requests.len(), 1);
    let child_id = child_requests[0].body_json()["client_metadata"]["thread_id"]
        .as_str()
        .context("child thread ID")?
        .to_string();
    let child_state: codex_app_server_protocol::ThreadReadResponse = app
        .request(|request_id| ClientRequest::ThreadRead {
            request_id,
            params: codex_app_server_protocol::ThreadReadParams {
                thread_id: child_id,
                include_turns: false,
            },
        })
        .await?;
    assert!(matches!(
        child_state.thread.status,
        codex_app_server_protocol::ThreadStatus::Active { .. }
    ));
    assert!(result.requests().iter().all(|request| {
        request
            .inputs_of_type("agent_message")
            .iter()
            .all(|message| message["author"] != "/root/worker")
    }));

    let notification = timeout(
        DEFAULT_READ_TIMEOUT,
        app.read_stream_until_matching_notification("automatic parent turn", |notification| {
            notification.method == "turn/started"
                && notification.params.as_ref().is_some_and(|params| {
                    params["threadId"] == root.id
                        && params["turn"]["id"] != first.turn.id
                        && params["turn"]["id"] != aside.turn.id
                })
        }),
    )
    .await??;
    let started: TurnStartedNotification =
        serde_json::from_value(notification.params.context("turn params")?)?;
    let notification = timeout(
        DEFAULT_READ_TIMEOUT,
        app.read_stream_until_matching_notification(
            "automatic parent completion",
            |notification| {
                notification.method == "turn/completed"
                    && notification.params.as_ref().is_some_and(|params| {
                        params["threadId"] == root.id && params["turn"]["id"] == started.turn.id
                    })
            },
        ),
    )
    .await??;
    let completed: TurnCompletedNotification =
        serde_json::from_value(notification.params.context("turn params")?)?;
    assert_eq!(completed.turn.status, TurnStatus::Completed);
    assert!(completed.turn.items.iter().any(|item| {
        matches!(&item.payload, ThreadItemPayload::AgentMessage { text, .. } if text == "The delegated task is complete.")
    }));
    let result_requests: Vec<_> = result
        .requests()
        .into_iter()
        .filter(|request| {
            !request
                .body_json()
                .to_string()
                .contains("background-complete-goal")
                && request
                    .inputs_of_type("agent_message")
                    .iter()
                    .any(|message| message["author"] == "/root/worker")
        })
        .collect();
    assert_eq!(result_requests.len(), 1);
    assert_eq!(
        result_requests[0].inputs_of_type("agent_message")[0]["author"],
        "/root/worker"
    );
    Ok(())
}
