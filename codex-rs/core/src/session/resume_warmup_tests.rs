use super::make_session_and_context;
use crate::session::*;
use codex_history::ResumedHistory;
use futures::FutureExt;
use std::sync::atomic::AtomicUsize;
use std::sync::atomic::Ordering;
use tracing::instrument::WithSubscriber;
use tracing_subscriber::Layer;
use tracing_subscriber::layer::SubscriberExt;

#[derive(Clone, Default)]
struct SkillDiscoveryCalls(Arc<AtomicUsize>);

impl<S: tracing::Subscriber> Layer<S> for SkillDiscoveryCalls {
    fn on_new_span(
        &self,
        attrs: &tracing::span::Attributes<'_>,
        _id: &tracing::span::Id,
        _ctx: tracing_subscriber::layer::Context<'_, S>,
    ) {
        if attrs.metadata().name() == "skills_for_config" {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }
}

#[tokio::test]
async fn resumed_history_does_not_discover_skills_before_restoration() {
    // Keep a second dispatch alive so concurrent untraced tests cannot cache shared callsites as disabled.
    let _interest_cache_guard =
        tracing::Dispatch::new(tracing::subscriber::NoSubscriber::default());
    let (session, _) = make_session_and_context().await;
    let calls = SkillDiscoveryCalls::default();
    let subscriber = tracing_subscriber::registry().with(calls.clone());
    session
        .record_initial_history(InitialHistory::Resumed(ResumedHistory {
            conversation_id: ThreadId::default(),
            history: Arc::new(Vec::new()),
            history_revision: None,
            rollout_path: None,
        }))
        .with_subscriber(subscriber)
        .await;
    assert_eq!(
        calls.0.load(Ordering::SeqCst),
        0,
        "restoration must not wait for skill discovery"
    );
    assert!(session.clone_history().await.raw_items().next().is_none());
}

#[tokio::test]
async fn forked_history_does_not_discover_skills_before_restoration() {
    // Keep a second dispatch alive so concurrent untraced tests cannot cache shared callsites as disabled.
    let _interest_cache_guard =
        tracing::Dispatch::new(tracing::subscriber::NoSubscriber::default());
    let (session, _) = make_session_and_context().await;
    let calls = SkillDiscoveryCalls::default();
    let subscriber = tracing_subscriber::registry().with(calls.clone());
    session
        .record_initial_history(InitialHistory::Forked(Vec::new()))
        .with_subscriber(subscriber)
        .await;
    assert_eq!(
        calls.0.load(Ordering::SeqCst),
        0,
        "fork restoration must not wait for skill discovery"
    );
    assert!(session.clone_history().await.raw_items().next().is_none());
}

#[tokio::test]
async fn execution_context_still_discovers_skills() {
    // Keep a second dispatch alive so concurrent untraced tests cannot cache shared callsites as disabled.
    let _interest_cache_guard =
        tracing::Dispatch::new(tracing::subscriber::NoSubscriber::default());
    let (session, _) = make_session_and_context().await;
    let skill_root = tempfile::tempdir().expect("skill root");
    let skill_dir = skill_root.path().join("resume-demo");
    std::fs::create_dir(&skill_dir).expect("skill directory");
    std::fs::write(
        skill_dir.join("SKILL.md"),
        "---\nname: resume-demo\ndescription: test skill\n---\nSkill contents\n",
    )
    .expect("skill document");
    session.services.skills_service.set_extra_roots(vec![
        AbsolutePathBuf::try_from(skill_root.path()).expect("absolute skill root"),
    ]);
    let calls = SkillDiscoveryCalls::default();
    let subscriber = tracing_subscriber::registry().with(calls.clone());
    let context = session.new_default_turn().with_subscriber(subscriber).await;
    assert_eq!(calls.0.load(Ordering::SeqCst), 1);
    assert!(
        context
            .skills_snapshot()
            .outcome()
            .skills
            .iter()
            .any(|skill| skill.name == "resume-demo")
    );
}

#[derive(Clone)]
struct PausedSkillDiscovery {
    entered: Arc<tokio::sync::Notify>,
    first: Arc<std::sync::atomic::AtomicBool>,
    release: Arc<(std::sync::Mutex<bool>, std::sync::Condvar)>,
}

impl Default for PausedSkillDiscovery {
    fn default() -> Self {
        Self {
            entered: Arc::default(),
            first: Arc::new(std::sync::atomic::AtomicBool::new(true)),
            release: Arc::default(),
        }
    }
}

impl PausedSkillDiscovery {
    fn release(&self) {
        let (lock, changed) = self.release.as_ref();
        *lock.lock().expect("discovery release") = true;
        changed.notify_all();
    }
}

impl<S: tracing::Subscriber> Layer<S> for PausedSkillDiscovery {
    fn on_new_span(
        &self,
        attrs: &tracing::span::Attributes<'_>,
        _id: &tracing::span::Id,
        _ctx: tracing_subscriber::layer::Context<'_, S>,
    ) {
        if attrs.metadata().name() == "skills_for_config"
            && self.first.swap(false, Ordering::SeqCst)
        {
            self.entered.notify_one();
            let (lock, changed) = self.release.as_ref();
            let mut released = lock.lock().expect("discovery release");
            while !*released {
                released = changed.wait(released).expect("discovery release wait");
            }
        }
    }
}

struct ReleaseDiscoveryOnDrop(PausedSkillDiscovery);

impl Drop for ReleaseDiscoveryOnDrop {
    fn drop(&mut self) {
        self.0.release();
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn session_is_available_during_skill_warmup_and_first_context_waits() {
    // Keep a second dispatch alive so concurrent untraced tests cannot cache shared callsites as disabled.
    let _interest_cache_guard =
        tracing::Dispatch::new(tracing::subscriber::NoSubscriber::default());
    let discovery = PausedSkillDiscovery::default();
    let _release_on_drop = ReleaseDiscoveryOnDrop(discovery.clone());
    let subscriber = tracing_subscriber::registry().with(discovery.clone());
    let initialize =
        tokio::spawn(super::make_session_with_config_and_rx(|_| {}).with_subscriber(subscriber));
    tokio::time::timeout(
        std::time::Duration::from_secs(10),
        discovery.entered.notified(),
    )
    .await
    .expect("skill discovery must start");
    let (session, _) = tokio::time::timeout(std::time::Duration::from_secs(2), initialize)
        .await
        .expect("session initialization must finish while skill discovery is paused")
        .expect("session initialization task")
        .expect("session initialized");
    let mut first_context = Box::pin(session.new_default_turn());
    assert!(
        tokio::time::timeout(std::time::Duration::from_millis(100), &mut first_context)
            .await
            .is_err(),
        "the first execution context must await the pending discovery"
    );
    discovery.release();
    tokio::time::timeout(std::time::Duration::from_secs(10), first_context)
        .await
        .expect("first execution context completes after discovery is released");
    session.stop_mcp_prewarm_worker().await;
}

#[tokio::test]
async fn cancelled_context_wait_preserves_pending_skill_warmup() {
    let (session, _) = make_session_and_context().await;
    let (release, discovery_finished) = tokio::sync::oneshot::channel();
    // This task represents the startup discovery that an early request waits for.
    let discovery = tokio::spawn(async move {
        discovery_finished.await.expect("discovery released");
    });
    *session.startup_skill_warmup.lock().await = Some(
        tokio_util::task::AbortOnDropHandle::new(discovery)
            .map(|result| result.map_err(Arc::new))
            .boxed()
            .shared(),
    );
    assert!(
        tokio::time::timeout(
            std::time::Duration::from_millis(100),
            session.new_default_turn()
        )
        .await
        .is_err(),
        "cancelling a waiting request must leave discovery running"
    );
    assert!(!release.is_closed());
    release.send(()).expect("discovery is still running");
    let context = tokio::time::timeout(
        std::time::Duration::from_secs(10),
        session.new_default_turn(),
    )
    .await
    .expect("a later request can use the completed discovery");
    assert!(!context.model_info().slug.is_empty());
}

#[tokio::test]
async fn failed_skill_warmup_does_not_skip_execution_discovery() {
    // Keep a second dispatch alive so concurrent untraced tests cannot cache shared callsites as disabled.
    let _interest_cache_guard =
        tracing::Dispatch::new(tracing::subscriber::NoSubscriber::default());
    let (session, _) = make_session_and_context().await;
    let discovery = tokio::spawn(async { panic!("injected startup discovery failure") });
    *session.startup_skill_warmup.lock().await = Some(
        tokio_util::task::AbortOnDropHandle::new(discovery)
            .map(|result| result.map_err(Arc::new))
            .boxed()
            .shared(),
    );
    let calls = SkillDiscoveryCalls::default();
    let subscriber = tracing_subscriber::registry().with(calls.clone());
    let context = session.new_default_turn().with_subscriber(subscriber).await;
    assert_eq!(calls.0.load(Ordering::SeqCst), 1);
    assert!(!context.model_info().slug.is_empty());
}
