use super::*;
use codex_protocol::protocol::MultiAgentVersion;
use pretty_assertions::assert_eq;
use test_case::test_case;

#[derive(Clone, Copy)]
enum BackgroundScenario {
    Idle,
    Answering,
    AutoResumeDisabled,
    ChildInterrupted,
    ParentInterrupted,
    ParentRemoved,
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn background_subagent_results_preserve_multiple_children_and_followup_identity() -> Result<()>
{
    let server = start_mock_server().await;
    let test = test_codex()
        .with_model("koffing")
        .with_config(|config| {
            config
                .features
                .enable(Feature::Collab)
                .expect("enable Collab");
            config
                .features
                .enable(Feature::MultiAgentV2)
                .expect("enable MultiAgentV2");
            config.model_provider.supports_websockets = false;
            config.model_provider.request_max_retries = Some(0);
            config.model_provider.stream_max_retries = Some(0);
        })
        .build(&server)
        .await?;

    let mut identities = std::collections::HashMap::new();
    for (index, (name, is_followup)) in [("first", false), ("second", false), ("first", true)]
        .into_iter()
        .enumerate()
    {
        let prompt = format!("delegate task {index}");
        let call_id = format!("background-call-{index}");
        let task = format!("child task {index}");
        let result = format!("child result {index}");
        let parent_answer = format!("handled result {index}");
        let args = if is_followup {
            json!({ "target": format!("/root/{name}"), "message": task })
        } else {
            json!({ "task_name": name, "message": task, "fork_turns": "none" })
        };
        let request_prompt = prompt.clone();
        let request_call = call_id.clone();
        mount_sse_once_match(
            &server,
            move |request: &wiremock::Request| {
                body_contains(request, &request_prompt) && !body_contains(request, &request_call)
            },
            sse(vec![
                ev_response_created(&format!("delegate-{index}")),
                ev_function_call_with_namespace(
                    &call_id,
                    MULTI_AGENT_V2_NAMESPACE,
                    if is_followup {
                        "followup_task"
                    } else {
                        "spawn_agent"
                    },
                    &serde_json::to_string(&args)?,
                ),
                ev_completed(&format!("delegate-{index}")),
            ]),
        )
        .await;
        let request_task = task.clone();
        let request_call = call_id.clone();
        let child_response = mount_response_once_match(
            &server,
            move |request: &wiremock::Request| {
                body_contains(request, &request_task) && !body_contains(request, &request_call)
            },
            sse_response(sse(vec![
                ev_response_created(&format!("child-{index}")),
                ev_assistant_message(&format!("child-message-{index}"), &result),
                ev_completed(&format!("child-{index}")),
            ]))
            .set_delay(Duration::from_secs(1)),
        )
        .await;
        let request_call = call_id.clone();
        let request_result = result.clone();
        mount_sse_once_match(
            &server,
            move |request: &wiremock::Request| {
                body_contains(request, &request_call) && !body_contains(request, &request_result)
            },
            sse(vec![
                ev_response_created(&format!("yield-{index}")),
                ev_assistant_message(&format!("yield-message-{index}"), "The child is running."),
                ev_completed(&format!("yield-{index}")),
            ]),
        )
        .await;
        let request_result = result.clone();
        let resumed = mount_sse_once_match(
            &server,
            move |request: &wiremock::Request| body_contains(request, &request_result),
            sse(vec![
                ev_response_created(&format!("resume-{index}")),
                ev_assistant_message(&format!("resume-message-{index}"), &parent_answer),
                ev_completed(&format!("resume-{index}")),
            ]),
        )
        .await;

        test.submit_turn(&prompt).await?;
        timeout(
            Duration::from_secs(6),
            wait_for_event(&test.codex, |event| {
                matches!(event, EventMsg::TurnComplete(event)
                if event.last_agent_message.as_deref() == Some(parent_answer.as_str()))
            }),
        )
        .await?;
        let root_id = test.session_configured.thread_id.to_string();
        let child_requests: Vec<_> = child_response
            .requests()
            .into_iter()
            .filter(|request| {
                let body = request.body_json();
                body["client_metadata"]["thread_id"] != root_id
                    && serde_json::to_string(&body["input"])
                        .expect("serialize request input")
                        .contains(&task)
            })
            .collect();
        assert_eq!(child_requests.len(), 1);
        let child_id = child_requests[0].body_json()["client_metadata"]["thread_id"]
            .as_str()
            .expect("child thread ID")
            .to_string();
        if is_followup {
            assert_eq!(identities.get(name), Some(&child_id));
        } else {
            identities.insert(name, child_id);
        }
        let parent_requests: Vec<_> = resumed
            .requests()
            .into_iter()
            .filter(|request| {
                request.body_json()["client_metadata"]["thread_id"] == root_id
                    && request
                        .inputs_of_type("agent_message")
                        .iter()
                        .any(|message| message["content"].to_string().contains(&result))
            })
            .collect();
        assert_eq!(parent_requests.len(), 1);
        let messages = parent_requests[0].inputs_of_type("agent_message");
        let matching: Vec<_> = messages
            .iter()
            .filter(|message| message["content"].to_string().contains(&result))
            .collect();
        assert_eq!(matching.len(), 1);
        assert_eq!(matching[0]["author"], format!("/root/{name}"));
        assert_eq!(matching[0]["recipient"], "/root");
    }
    assert_ne!(identities["first"], identities["second"]);
    Ok(())
}

#[test_case(MultiAgentVersion::V2, BackgroundScenario::Idle; "enabled")]
#[test_case(MultiAgentVersion::V2, BackgroundScenario::AutoResumeDisabled; "disabled")]
#[test_case(MultiAgentVersion::V1, BackgroundScenario::Idle; "legacy")]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn background_subagent_guidance_reaches_model_only_with_wakeup_enabled(
    version: MultiAgentVersion,
    scenario: BackgroundScenario,
) -> Result<()> {
    let server = start_mock_server().await;
    let response = core_test_support::responses::mount_sse_once(
        &server,
        sse(vec![
            ev_response_created("inspect"),
            ev_completed("inspect"),
        ]),
    )
    .await;
    let test = test_codex()
        .with_model_info_override("gpt-5.2", move |model| {
            model.multi_agent_version = Some(version);
        })
        .with_config(move |config| {
            config
                .features
                .enable(Feature::Collab)
                .expect("enable Collab");
            if version == MultiAgentVersion::V2 {
                config
                    .features
                    .enable(Feature::MultiAgentV2)
                    .expect("enable MultiAgentV2");
            } else {
                config
                    .features
                    .disable(Feature::MultiAgentV2)
                    .expect("disable MultiAgentV2");
            }
            if matches!(scenario, BackgroundScenario::AutoResumeDisabled) {
                config
                    .features
                    .disable(Feature::UnleashedBackgroundSubagents)
                    .expect("disable UnleashedBackgroundSubagents");
            }
            config.multi_agent_v2.tool_namespace = None;
        })
        .build_with_auto_env(&server)
        .await?;

    assert!(!test.codex.has_running_background_subagents().await?);
    test.submit_turn("Inspect the available tools.").await?;
    let request = response.single_request();
    let tools = request.body_json()["tools"].clone();
    let guidance_is_present = tools
        .to_string()
        .contains("You may finish your current turn while delegated work is still running.");
    assert_eq!(
        guidance_is_present,
        version == MultiAgentVersion::V2
            && !matches!(scenario, BackgroundScenario::AutoResumeDisabled)
    );
    Ok(())
}

#[test_case(CompletionScenario::Completed, BackgroundScenario::Idle; "completed")]
#[test_case(CompletionScenario::TerminalError, BackgroundScenario::Idle; "failed")]
#[test_case(CompletionScenario::Completed, BackgroundScenario::Answering; "answering")]
#[test_case(CompletionScenario::Completed, BackgroundScenario::AutoResumeDisabled; "disabled")]
#[test_case(CompletionScenario::Completed, BackgroundScenario::ChildInterrupted; "interrupted")]
#[test_case(CompletionScenario::Completed, BackgroundScenario::ParentInterrupted; "parent_interrupted")]
#[test_case(CompletionScenario::Completed, BackgroundScenario::ParentRemoved; "parent_removed")]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn background_subagent_completion_coordinates_parent_turns(
    scenario: CompletionScenario,
    parent_state: BackgroundScenario,
) -> Result<()> {
    let server = start_mock_server().await;
    let spawn_args = serde_json::to_string(&json!({
        "message": "background work",
        "task_name": "worker",
    }))?;
    mount_sse_once_match(
        &server,
        |req: &wiremock::Request| {
            body_contains(req, "delegate background work") && !body_contains(req, SPAWN_CALL_ID)
        },
        sse(vec![
            ev_response_created("spawn"),
            ev_function_call_with_namespace(
                SPAWN_CALL_ID,
                MULTI_AGENT_V2_NAMESPACE,
                "spawn_agent",
                &spawn_args,
            ),
            ev_completed("spawn"),
        ]),
    )
    .await;
    let mut child_events = vec![ev_response_created("child")];
    if matches!(scenario, CompletionScenario::Completed) {
        child_events.extend([
            ev_assistant_message("child-result", "background result"),
            ev_completed("child"),
        ]);
    }
    mount_response_once_match(
        &server,
        |req: &wiremock::Request| request_has_input_type(req, "agent_message"),
        sse_response(sse(child_events)).set_delay(Duration::from_secs(2)),
    )
    .await;
    mount_sse_once_match(
        &server,
        |req: &wiremock::Request| {
            body_contains(req, SPAWN_CALL_ID)
                && !request_has_input_type(req, "agent_message")
                && !body_contains(req, "unrelated question")
        },
        sse(vec![
            ev_response_created("parent-yield"),
            ev_assistant_message("parent-yield-message", "The worker is running."),
            ev_completed("parent-yield"),
        ]),
    )
    .await;
    mount_response_once_match(
        &server,
        |req: &wiremock::Request| {
            body_contains(req, "unrelated question")
                && !request_has_input_type(req, "agent_message")
                && !body_contains(req, "summarize the background result")
        },
        sse_response(sse(vec![
            ev_response_created("aside"),
            ev_assistant_message("aside-message", "Here is the unrelated answer."),
            ev_completed("aside"),
        ]))
        .set_delay(
            if matches!(
                parent_state,
                BackgroundScenario::Answering | BackgroundScenario::ParentInterrupted
            ) {
                Duration::from_secs(3)
            } else {
                Duration::ZERO
            },
        ),
    )
    .await;
    // Queue-only mail is consumed at a continuation boundary after the fresh user input.
    mount_sse_once_match(
        &server,
        |req: &wiremock::Request| {
            body_contains(req, "summarize the background result")
                && !body_contains(req, "Message Type: FINAL_ANSWER")
        },
        sse(vec![
            ev_response_created("manual-continuation"),
            ev_function_call_with_namespace(
                "manual-wait",
                MULTI_AGENT_V2_NAMESPACE,
                "wait_agent",
                "{}",
            ),
            ev_completed("manual-continuation"),
        ]),
    )
    .await;
    let resumed = mount_sse_once_match(
        &server,
        |req: &wiremock::Request| {
            body_contains(req, "Message Type: FINAL_ANSWER")
                && body_contains(req, "Sender: /root/worker")
        },
        sse(vec![
            ev_response_created("parent-resumed"),
            ev_assistant_message(
                "parent-resumed-message",
                "I incorporated the background result.",
            ),
            ev_completed("parent-resumed"),
        ]),
    )
    .await;
    let test = test_codex()
        .with_model("koffing")
        .with_config(move |config| {
            config
                .features
                .enable(Feature::Collab)
                .expect("enable Collab");
            config
                .features
                .enable(Feature::MultiAgentV2)
                .expect("enable MultiAgentV2");
            if matches!(parent_state, BackgroundScenario::AutoResumeDisabled) {
                config
                    .features
                    .disable(Feature::UnleashedBackgroundSubagents)
                    .expect("disable UnleashedBackgroundSubagents");
            }
            config.model_provider.supports_websockets = false;
            config.model_provider.request_max_retries = Some(0);
            config.model_provider.stream_max_retries = Some(0);
        })
        .build(&server)
        .await?;

    test.submit_turn("delegate background work").await?;
    let child_id = ThreadId::from_string(&wait_for_spawned_thread_id(&test).await?)?;
    let child = test.thread_manager.get_thread(child_id).await?;
    wait_for_event(&child, |event| matches!(event, EventMsg::TurnStarted(_))).await;
    assert_eq!(child.agent_status().await, AgentStatus::Running,);
    assert_eq!(
        test.codex.has_running_background_subagents().await?,
        !matches!(parent_state, BackgroundScenario::AutoResumeDisabled)
    );
    if matches!(
        parent_state,
        BackgroundScenario::Answering | BackgroundScenario::ParentInterrupted
    ) {
        test.codex
            .start_or_steer_turn(TurnInputRequest::user_input(vec![UserInput::Text {
                text: "unrelated question".to_string(),
                text_elements: Vec::new(),
            }]))
            .await?;
        if matches!(parent_state, BackgroundScenario::ParentInterrupted) {
            wait_for_event(&test.codex, |event| {
                matches!(event, EventMsg::TurnStarted(_))
            })
            .await;
            test.codex.submit(Op::Interrupt).await?;
            wait_for_event(&test.codex, |event| {
                matches!(event, EventMsg::TurnAborted(_))
            })
            .await;
            assert_eq!(test.codex.agent_status().await, AgentStatus::Interrupted);
        } else {
            wait_for_event(&test.codex, |event| {
                matches!(event, EventMsg::AgentMessage(event)
                    if event.message == "Here is the unrelated answer.")
            })
            .await;
        }
    } else {
        test.submit_turn("unrelated question").await?;
        assert_eq!(
            test.codex.agent_status().await,
            AgentStatus::Completed(Some("Here is the unrelated answer.".to_string())),
        );
        assert_eq!(child.agent_status().await, AgentStatus::Running);
    }

    if matches!(parent_state, BackgroundScenario::ChildInterrupted) {
        child.submit(Op::Interrupt).await?;
        wait_for_event(&child, |event| matches!(event, EventMsg::TurnAborted(_))).await;
        assert_eq!(child.agent_status().await, AgentStatus::Interrupted);
    }
    if matches!(parent_state, BackgroundScenario::ParentRemoved) {
        assert!(
            test.thread_manager
                .remove_thread(&test.session_configured.thread_id)
                .await
                .is_some()
        );
    }
    if matches!(
        parent_state,
        BackgroundScenario::AutoResumeDisabled
            | BackgroundScenario::ChildInterrupted
            | BackgroundScenario::ParentInterrupted
            | BackgroundScenario::ParentRemoved
    ) {
        if matches!(
            parent_state,
            BackgroundScenario::AutoResumeDisabled
                | BackgroundScenario::ParentInterrupted
                | BackgroundScenario::ParentRemoved
        ) {
            wait_for_event(&child, |event| matches!(event, EventMsg::TurnComplete(_))).await;
        }
        assert!(
            timeout(Duration::from_millis(300), async {
                wait_for_event(&test.codex, |event| {
                    matches!(event, EventMsg::TurnStarted(_))
                })
                .await;
            })
            .await
            .is_err(),
            "an opted-out parent or interrupted child must not start an automatic parent turn"
        );
        assert!(resumed.requests().is_empty());
        if matches!(parent_state, BackgroundScenario::ParentRemoved) {
            assert!(
                test.thread_manager
                    .get_thread(test.session_configured.thread_id)
                    .await
                    .is_err(),
                "child completion must not recreate the removed parent"
            );
            return Ok(());
        }
        if matches!(parent_state, BackgroundScenario::ChildInterrupted) {
            return Ok(());
        }
        let submitted = test
            .codex
            .start_or_steer_turn(TurnInputRequest::user_input(vec![UserInput::Text {
                text: "summarize the background result".to_string(),
                text_elements: Vec::new(),
            }]))
            .await?;
        assert!(
            matches!(submitted, codex_core::TurnInputSubmission::Started { .. }),
            "manual turn admission: {submitted:?}"
        );
    }

    let completed = timeout(Duration::from_secs(6), async {
        wait_for_event(&test.codex, |event| {
            matches!(event, EventMsg::TurnComplete(event)
                if event.last_agent_message.as_deref() == Some("I incorporated the background result."))
        })
        .await;
    })
    .await;
    if completed.is_err() {
        for req in server.received_requests().await.unwrap_or_default() {
            if req.url.path().ends_with("/responses") {
                eprintln!(
                    "request: notification={}, aside={}, manual={}",
                    body_contains(&req, "Message Type: FINAL_ANSWER"),
                    body_contains(&req, "unrelated question"),
                    body_contains(&req, "summarize the background result")
                );
            }
        }
        eprintln!("parent status: {:?}", test.codex.agent_status().await);
    }
    completed.expect("child result must be delivered automatically when enabled, or on the next user turn when disabled");
    let request = resumed.single_request();
    let messages = request.inputs_of_type("agent_message");
    assert_eq!(messages.len(), 1);
    assert_eq!(messages[0]["author"], "/root/worker");
    assert_eq!(messages[0]["recipient"], "/root");
    let history = serde_json::to_string(&request.input())?;
    assert!(history.contains("unrelated question"));
    if !matches!(parent_state, BackgroundScenario::ParentInterrupted) {
        assert!(history.contains("Here is the unrelated answer."));
    }
    let expected_payload = match scenario {
        CompletionScenario::Completed => "background result",
        CompletionScenario::TerminalError => "Agent errored:",
    };
    assert!(
        messages[0]["content"]
            .to_string()
            .contains(expected_payload)
    );
    Ok(())
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn background_subagent_simultaneous_results_serialize_parent_turns() -> Result<()> {
    let server = start_mock_server().await;
    let test = test_codex()
        .with_model("koffing")
        .with_config(|config| {
            config
                .features
                .enable(Feature::Collab)
                .expect("enable Collab");
            config
                .features
                .enable(Feature::MultiAgentV2)
                .expect("enable MultiAgentV2");
            config.model_provider.supports_websockets = false;
            config.model_provider.request_max_retries = Some(0);
            config.model_provider.stream_max_retries = Some(0);
        })
        .build(&server)
        .await?;
    let mut spawn_events = vec![ev_response_created("spawn-both")];
    for name in ["first", "second"] {
        spawn_events.push(ev_function_call_with_namespace(
            &format!("spawn-{name}"),
            MULTI_AGENT_V2_NAMESPACE,
            "spawn_agent",
            &serde_json::to_string(&json!({
                "task_name": name,
                "message": format!("simultaneous child {name}"),
                "fork_turns": "none",
            }))?,
        ));
        let child_prompt = format!("simultaneous child {name}");
        mount_response_once_match(
            &server,
            move |request: &wiremock::Request| {
                body_contains(request, &child_prompt)
                    && !body_contains(request, "spawn-first")
                    && !body_contains(request, "spawn-second")
            },
            sse_response(sse(vec![
                ev_response_created(&format!("child-{name}")),
                ev_assistant_message(
                    &format!("result-{name}"),
                    &format!("simultaneous result {name}"),
                ),
                ev_completed(&format!("child-{name}")),
            ]))
            .set_delay(Duration::from_secs(3)),
        )
        .await;
    }
    spawn_events.push(ev_completed("spawn-both"));
    mount_sse_once_match(
        &server,
        |request: &wiremock::Request| {
            body_contains(request, "delegate simultaneous work")
                && !body_contains(request, "spawn-first")
        },
        sse(spawn_events),
    )
    .await;
    mount_sse_once_match(
        &server,
        |request: &wiremock::Request| {
            body_contains(request, "spawn-first")
                && !body_contains(request, "Message Type: FINAL_ANSWER")
        },
        sse(vec![
            ev_response_created("yield-both"),
            ev_assistant_message("yield-both-message", "Both children are running."),
            ev_completed("yield-both"),
        ]),
    )
    .await;

    // The first notification can arrive before the second, or both can be
    // consumed together. Either path must preserve both authors and serialize turns.
    wiremock::Mock::given(wiremock::matchers::method("POST"))
        .and(|request: &wiremock::Request| body_contains(request, "Message Type: FINAL_ANSWER"))
        .respond_with(|request: &wiremock::Request| {
            let answer = if body_contains(request, "simultaneous result first")
                && body_contains(request, "simultaneous result second")
            {
                "Handled both child results."
            } else {
                "Handled one child result."
            };
            sse_response(sse(vec![
                ev_response_created("coordinate-results"),
                ev_assistant_message("coordinate-results-message", answer),
                ev_completed("coordinate-results"),
            ]))
            .set_delay(Duration::from_secs(1))
        })
        .mount(&server)
        .await;
    test.submit_turn("delegate simultaneous work").await?;
    let mut running_children = 0;
    for id in test.thread_manager.list_thread_ids().await {
        if id != test.session_configured.thread_id {
            let child = test.thread_manager.get_thread(id).await?;
            wait_for_event(&child, |event| matches!(event, EventMsg::TurnStarted(_))).await;
            assert_eq!(child.agent_status().await, AgentStatus::Running);
            running_children += 1;
        }
    }
    assert_eq!(running_children, 2);

    let root_id = test.session_configured.thread_id.to_string();
    timeout(Duration::from_secs(8), async {
        let mut active_turn = None;
        loop {
            let event = test.codex.next_event().await.expect("parent event");
            match event.msg {
                EventMsg::TurnStarted(event) => {
                    assert!(
                        active_turn.replace(event.turn_id).is_none(),
                        "parent turns must not overlap"
                    );
                }
                EventMsg::TurnComplete(event) => {
                    assert_eq!(active_turn.take().as_deref(), Some(event.turn_id.as_str()));
                    let requests =
                        core_test_support::responses::received_responses_requests(&server).await;
                    let handled = requests
                        .into_iter()
                        .filter(|request| {
                            request.body_json()["client_metadata"]["thread_id"] == root_id
                        })
                        .flat_map(|request| {
                            let messages = request.inputs_of_type("agent_message");
                            for name in ["first", "second"] {
                                assert!(
                                    messages
                                        .iter()
                                        .filter(|message| {
                                            message["author"] == format!("/root/{name}")
                                                && message["content"].to_string().contains(
                                                    &format!("simultaneous result {name}"),
                                                )
                                        })
                                        .count()
                                        <= 1,
                                    "a child result must not be duplicated in a model request"
                                );
                            }
                            messages
                        })
                        .collect::<Vec<_>>();
                    if event.last_agent_message.as_deref() == Some("Handled both child results.")
                        && ["first", "second"].into_iter().all(|name| {
                            handled.iter().any(|message| {
                                message["author"] == format!("/root/{name}")
                                    && message["recipient"] == "/root"
                                    && message["content"]
                                        .to_string()
                                        .contains(&format!("simultaneous result {name}"))
                            })
                        })
                    {
                        break;
                    }
                }
                EventMsg::Error(error) => panic!("parent error: {error:?}"),
                _ => {}
            }
        }
    })
    .await?;
    assert!(!test.codex.has_running_background_subagents().await?);
    Ok(())
}
