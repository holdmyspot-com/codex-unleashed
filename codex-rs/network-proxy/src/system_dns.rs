//! This macOS-only module implements DNS resolution
//! using macOS's native system resolver. We have to
//! do this rather than relying on the default behavior
//! (which reads /etc/resolv.conf) since default behavior
//! breaks Codex's ability to interact with network paths
//! that are provided by applications that modify / hook
//! into macOS DNS behavior (i.e. proxies, VPNs, etc)
use rama_core::futures::Stream;
use rama_core::futures::async_stream::stream_fn;
use rama_dns::client::resolver::DnsAddressResolver;
use rama_net::address::Domain;
use std::io;
use std::net::IpAddr;
use std::net::Ipv4Addr;
use std::net::Ipv6Addr;
use tokio::net::lookup_host;

/// Resolve TCP destinations through macOS's native resolver, including supplemental
/// resolvers and VPN split DNS that are not represented in `/etc/resolv.conf`.
#[derive(Clone)]
pub(crate) struct SystemDnsResolver;

impl DnsAddressResolver for SystemDnsResolver {
    type Error = io::Error;

    fn lookup_ipv4(
        &self,
        domain: Domain,
    ) -> impl Stream<Item = Result<Ipv4Addr, Self::Error>> + Send + '_ {
        stream_fn(
            async move |mut yielder| match lookup_host((domain.as_str(), 0)).await {
                Ok(addrs) => {
                    for addr in addrs {
                        if let IpAddr::V4(ip) = addr.ip() {
                            yielder.yield_item(Ok(ip)).await;
                        }
                    }
                }
                Err(err) => yielder.yield_item(Err(err)).await,
            },
        )
    }

    fn lookup_ipv6(
        &self,
        domain: Domain,
    ) -> impl Stream<Item = Result<Ipv6Addr, Self::Error>> + Send + '_ {
        stream_fn(
            async move |mut yielder| match lookup_host((domain.as_str(), 0)).await {
                Ok(addrs) => {
                    for addr in addrs {
                        if let IpAddr::V6(ip) = addr.ip() {
                            yielder.yield_item(Ok(ip)).await;
                        }
                    }
                }
                Err(err) => yielder.yield_item(Err(err)).await,
            },
        )
    }
}

#[cfg(test)]
#[path = "system_dns_tests.rs"]
mod tests;
