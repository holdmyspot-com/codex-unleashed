//! The wait for policy publication is bounded.

use super::acquire;
use codex_http_client::NetworkPolicyController;
use codex_http_client::NetworkPolicyDenied;
use pretty_assertions::assert_eq;
use std::time::Duration;

#[tokio::test]
async fn acquire_gives_up_when_the_policy_is_never_published() {
    let controller = NetworkPolicyController::default();

    let result = acquire(&controller.policy(), Duration::from_millis(/*millis*/ 20)).await;

    assert_eq!(result.err(), Some(NetworkPolicyDenied::Unavailable));
}
