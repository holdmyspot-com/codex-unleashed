//! HTTP transport for curated plugin startup sync.
//!
//! Both proxy modes retain the supplied application network policy and fail closed on HTTP client
//! construction errors. Startup-sync request helpers apply the standard Codex headers.

use std::sync::Arc;

use crate::http_client_selector::HttpClientSelector;
use codex_http_client::ClientRouteClass;
use codex_http_client::HttpClientFactory;
use codex_http_client::RequestBuilder;
use codex_http_client::RouteAwareClientPool;
use http::Method;

pub(super) struct StartupSyncHttpClient(Arc<dyn HttpClientSelector>);

impl StartupSyncHttpClient {
    pub(super) fn new(http_client_factory: &HttpClientFactory) -> Self {
        let http_clients =
            RouteAwareClientPool::with_chatgpt_cloudflare_cookies_without_request_logging(
                http_client_factory.clone(),
                ClientRouteClass::Api,
            );
        Self(Arc::new(http_clients))
    }

    #[cfg(test)]
    pub(super) fn route_aware(http_clients: Arc<dyn HttpClientSelector>) -> Self {
        Self(http_clients)
    }

    pub(super) fn request(&self, method: Method, url: &str) -> RequestBuilder {
        self.0.request(method, url)
    }
}
