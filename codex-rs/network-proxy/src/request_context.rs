//! Request target resolution shared by the proxy request paths.

use rama_core::error::BoxError;
use rama_core::error::BoxErrorExt as _;
use rama_http::Request;
use rama_net::AuthorityInputExt as _;
use rama_net::Protocol;
use rama_net::ProtocolInputExt as _;
use rama_net::address::HostWithOptPort;
use rama_net::address::HostWithPort;

/// The protocol and routing authority of an HTTP request.
///
/// The authority follows rama's request resolution: the request URI, then TLS SNI, `Forwarded`
/// information and finally the `Host` header. URI authorities carry the port implied by the
/// protocol when the URI does not state one.
#[derive(Debug, Clone)]
pub(crate) struct RequestContext {
    pub(crate) protocol: Protocol,
    pub(crate) authority: HostWithOptPort,
}

impl RequestContext {
    /// Whether the authority's port is the default port of the request protocol.
    pub(crate) fn authority_has_default_port(&self) -> bool {
        self.authority.has_default_port_for(Some(&self.protocol))
    }

    /// The authority with a port, using the protocol's default when the authority has none.
    pub(crate) fn host_with_port(&self) -> HostWithPort {
        self.authority.clone().into_host_with_port_or(
            self.protocol
                .default_port()
                .unwrap_or(Protocol::HTTP_DEFAULT_PORT),
        )
    }
}

impl<B> TryFrom<&Request<B>> for RequestContext {
    type Error = BoxError;

    fn try_from(req: &Request<B>) -> Result<Self, Self::Error> {
        let authority = req
            .authority()
            .ok_or_else(|| BoxError::from_static_str("no authority found in HTTP request"))?;
        Ok(Self {
            protocol: req.protocol().cloned().unwrap_or(Protocol::HTTP),
            authority,
        })
    }
}
