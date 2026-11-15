use super::*;
use crate::config::NetworkProxyConfig;
use crate::connect_policy::TargetCheckedTcpConnector;
use crate::state::network_proxy_state_for_policy;
use pretty_assertions::assert_eq;
use rama_core::Service;
use rama_core::extensions::ExtensionsRef;
use rama_core::futures::StreamExt as _;
use rama_net::address::Host;
use rama_net::address::HostWithPort;
use rama_net::address::ProxyAddress;
use rama_net::client::ConnectRequest;
use rama_net::client::ConnectorTarget;
use rama_net::client::ProxyRoute;
use rama_net::stream::Socket;
use std::sync::Arc;
use tokio::net::TcpListener;

#[tokio::test]
async fn native_resolution_supports_both_address_families() {
    let domain: Domain = "localhost".parse().expect("valid domain");
    let (ipv4, ipv6) = tokio::join!(
        SystemDnsResolver
            .lookup_ipv4(domain.clone())
            .collect::<Vec<_>>(),
        SystemDnsResolver.lookup_ipv6(domain).collect::<Vec<_>>(),
    );

    assert_eq!(
        ipv4.into_iter()
            .collect::<Result<Vec<_>, _>>()
            .expect("resolve IPv4 localhost"),
        vec![Ipv4Addr::LOCALHOST]
    );
    assert_eq!(
        ipv6.into_iter()
            .collect::<Result<Vec<_>, _>>()
            .expect("resolve IPv6 localhost"),
        vec![Ipv6Addr::LOCALHOST]
    );
}

#[tokio::test]
async fn connector_reaches_native_hostname_over_ipv4_and_ipv6() {
    for ip in [
        IpAddr::V4(Ipv4Addr::LOCALHOST),
        IpAddr::V6(Ipv6Addr::LOCALHOST),
    ] {
        let listener = TcpListener::bind((ip, 0)).await.expect("bind listener");
        let target = listener.local_addr().expect("local addr");
        let connector = TargetCheckedTcpConnector::new(Arc::new(network_proxy_state_for_policy(
            NetworkProxyConfig {
                allow_local_binding: Some(true),
                ..NetworkProxyConfig::default()
            },
        )));
        let request = ConnectRequest::new(HostWithPort::new(Host::LOCALHOST_NAME, target.port()));

        let connection = connector
            .serve(request)
            .await
            .expect("connect to localhost");

        assert_eq!(connection.conn.peer_addr().expect("peer addr"), target);
    }
}

#[tokio::test]
async fn native_resolution_preserves_local_network_rejection() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .await
        .expect("bind listener");
    let target = listener.local_addr().expect("local addr");
    let connector = TargetCheckedTcpConnector::new(Arc::new(network_proxy_state_for_policy(
        NetworkProxyConfig::default(),
    )));
    let request = ConnectRequest::new(HostWithPort::new(Host::LOCALHOST_NAME, target.port()));

    let _rejection = connector
        .serve(request)
        .await
        .expect_err("resolved loopback addresses must still be checked against policy");
}

#[tokio::test]
async fn connector_resolves_upstream_proxy_instead_of_destination() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .await
        .expect("bind listener");
    let target = listener.local_addr().expect("local addr");
    let connector = TargetCheckedTcpConnector::new(Arc::new(network_proxy_state_for_policy(
        NetworkProxyConfig::default(),
    )));
    let request = ConnectRequest::new(
        HostWithPort::try_from("destination.invalid:80").expect("valid destination"),
    );
    let proxy = ProxyAddress::try_from(format!("http://localhost:{}", target.port()))
        .expect("valid upstream proxy");
    // The HTTP proxy connector retargets the dial at the proxy before this connector runs.
    request
        .extensions()
        .insert(ConnectorTarget(proxy.address.clone()));
    request.extensions().insert(ProxyRoute::Proxy(proxy));

    let connection = connector.serve(request).await.expect("connect to proxy");

    assert_eq!(connection.conn.peer_addr().expect("peer addr"), target);
}
