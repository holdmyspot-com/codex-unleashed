//! An export that starts before the application network policy is published must wait for it.

use crate::network_policy::PolicyExporter;
use codex_http_client::DestinationPolicy;
use codex_http_client::NetworkPolicyController;
use opentelemetry_sdk::error::OTelSdkResult;
use opentelemetry_sdk::trace::SpanData;
use opentelemetry_sdk::trace::SpanExporter;
use pretty_assertions::assert_eq;
use std::collections::BTreeSet;
use std::sync::Arc;
use std::sync::atomic::AtomicUsize;
use std::sync::atomic::Ordering;

#[derive(Debug)]
struct CountingExporter(Arc<AtomicUsize>);

impl SpanExporter for CountingExporter {
    async fn export(&self, _batch: Vec<SpanData>) -> OTelSdkResult {
        self.0.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }
}

/// Runs one export that is polled before the policy is published, then publishes `policy`. `join!` polls
/// its futures in order, so the export always starts first and the outcome does not depend on timing.
async fn export_then_publish(policy: DestinationPolicy) -> (OTelSdkResult, usize) {
    let controller = NetworkPolicyController::default();
    let network_policy = controller.policy();
    let revision = network_policy.revision();
    let exports = Arc::new(AtomicUsize::new(0));
    let exporter = PolicyExporter {
        exporter: CountingExporter(Arc::clone(&exports)),
        policy: network_policy,
    };
    let (result, published) = tokio::join!(SpanExporter::export(&exporter, Vec::new()), async {
        controller.publish(revision, policy)
    },);
    assert!(published);
    (result, exports.load(Ordering::SeqCst))
}

#[tokio::test]
async fn export_started_before_publication_waits_and_then_exports() {
    let (result, exports) = export_then_publish(DestinationPolicy::Unrestricted).await;

    assert!(result.is_ok(), "{result:?}");
    assert_eq!(exports, 1);
}

#[tokio::test]
async fn export_started_before_a_restricting_publication_is_denied_without_exporting() {
    let (result, exports) = export_then_publish(DestinationPolicy::Restricted {
        allowed_hosts: BTreeSet::new(),
    })
    .await;

    assert!(result.is_err(), "{result:?}");
    assert_eq!(exports, 0);
}
