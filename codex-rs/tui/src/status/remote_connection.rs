use crate::AppServerTarget;
use crate::RemoteAppServerEndpoint;
use crate::update_versions::ServerVersionNoticeKind;
use sha2::Digest;
use sha2::Sha256;
use url::Url;

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct RemoteConnectionStatus {
    pub(crate) address: String,
    pub(crate) version: String,
    pub(crate) is_local_daemon: bool,
    pub(crate) is_managed_daemon: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ServerVersionNotice {
    pub(crate) message: String,
    pub(crate) offer_update: bool,
}

pub(crate) fn remote_connection_status_value(
    app_server_target: &AppServerTarget,
    server_version: Option<&str>,
) -> Option<RemoteConnectionStatus> {
    let endpoint = match app_server_target {
        AppServerTarget::Embedded => return None,
        AppServerTarget::LocalDaemon { endpoint, .. } | AppServerTarget::Remote { endpoint } => {
            endpoint
        }
    };
    let address = match endpoint {
        RemoteAppServerEndpoint::WebSocket { websocket_url, .. } => {
            sanitized_websocket_url(websocket_url)
                .map(|url| url.to_string())
                .unwrap_or_else(|| "<invalid websocket URL>".to_string())
        }
        RemoteAppServerEndpoint::UnixSocket { socket_path } => {
            format!("unix://{}", socket_path.display())
        }
    };
    let version = server_version
        .map(|version| format!("v{version}"))
        .unwrap_or_else(|| "unknown".to_string());
    Some(RemoteConnectionStatus {
        address,
        version,
        is_local_daemon: matches!(app_server_target, AppServerTarget::LocalDaemon { .. }),
        is_managed_daemon: false,
    })
}

pub(crate) fn server_version_notice(client: &str, server: Option<&str>) -> Option<String> {
    let client_version = semver::Version::parse(client).ok()?;
    let Some((server, server_version)) = server.and_then(|server| {
        semver::Version::parse(server)
            .ok()
            .map(|version| (server, version))
    }) else {
        return Some("The connected app-server did not provide a valid version. Its distribution and compatibility with this Codex CLI could not be verified.".to_string());
    };
    let client_build = client_version.build.as_str();
    let client_is_unleashed = client_build == "dev"
        || crate::update_versions::is_numeric_vendor_build(&client_version.build);
    let kind = crate::update_versions::server_version_notice_kind(client, server)?;
    if client_is_unleashed
        && server_version.build.is_empty()
        && kind != ServerVersionNoticeKind::Newer
    {
        let comparison = if kind == ServerVersionNoticeKind::Older {
            format!(", older than your Codex CLI v{client}")
        } else {
            String::new()
        };
        return Some(format!(
            "The connected app-server identifies itself as OpenAI Codex v{server}{comparison}. Update it to a Codex Unleashed app-server to use Codex Unleashed server features."
        ));
    }
    let comparison = match kind {
        ServerVersionNoticeKind::Older => "older than",
        ServerVersionNoticeKind::Newer => "newer than",
        ServerVersionNoticeKind::Different => "different from",
    };
    Some(format!(
        "A background Codex service is running v{server}, {comparison} your Codex CLI v{client}."
    ))
}

pub(crate) fn server_version_notice_for_tui(
    settings: &codex_config::types::Tui,
    client: &str,
    server: Option<&str>,
) -> Option<String> {
    if settings.show_server_version_notice {
        server_version_notice(client, server)
    } else {
        None
    }
}

fn hash_identity_part(hasher: &mut Sha256, tag: &[u8], value: &[u8]) {
    hasher.update(tag);
    hasher.update((value.len() as u64).to_le_bytes());
    hasher.update(value);
}

pub(crate) fn server_version_notice_key(
    target: &AppServerTarget,
    server_home: Option<&str>,
    client: &str,
    server: Option<&str>,
) -> Option<String> {
    server_version_notice(client, server)?;
    let version_key = match server {
        Some(server) => match semver::Version::parse(server) {
            Ok(_) => server.to_string(),
            Err(_) => format!("invalid-{}", hex::encode(Sha256::digest(server.as_bytes()))),
        },
        None => "missing".to_string(),
    };
    let mut hasher = Sha256::new();
    let endpoint = match target {
        AppServerTarget::Embedded => None,
        AppServerTarget::LocalDaemon { endpoint, .. } | AppServerTarget::Remote { endpoint } => {
            Some(endpoint)
        }
    };
    if let Some(server_home) = server_home {
        hash_identity_part(&mut hasher, b"server-home:", server_home.as_bytes());
    }
    match endpoint {
        Some(RemoteAppServerEndpoint::UnixSocket { socket_path }) => {
            hash_identity_part(
                &mut hasher,
                b"unix:",
                socket_path.as_path().as_os_str().as_encoded_bytes(),
            );
        }
        Some(RemoteAppServerEndpoint::WebSocket { websocket_url, .. }) => {
            if let Ok(mut url) = Url::parse(websocket_url) {
                let routing = url
                    .query_pairs()
                    .filter(|(name, _)| {
                        matches!(
                            name.to_ascii_lowercase().as_str(),
                            "workspace"
                                | "project"
                                | "tenant"
                                | "environment"
                                | "namespace"
                                | "organization"
                                | "org"
                                | "account"
                                | "route"
                                | "instance"
                                | "region"
                                | "deployment"
                                | "cluster"
                        )
                    })
                    .map(|(name, value)| (name.into_owned(), value.into_owned()))
                    .collect::<Vec<_>>();
                let _ = url.set_username("");
                let _ = url.set_password(None);
                url.set_query(None);
                url.set_fragment(None);
                if !routing.is_empty() {
                    url.query_pairs_mut().extend_pairs(routing);
                }
                hash_identity_part(&mut hasher, b"websocket:", url.as_str().as_bytes());
            }
        }
        None => hash_identity_part(&mut hasher, b"embedded:", b""),
    }
    Some(format!(
        "{}:{client}-{version_key}",
        hex::encode(hasher.finalize())
    ))
}

pub(crate) fn pending_server_version_notice(
    settings: &codex_config::types::Tui,
    target: &AppServerTarget,
    server_home: Option<&str>,
    client: &str,
    server: Option<&str>,
    last_shown: Option<&str>,
) -> Option<(ServerVersionNotice, String)> {
    if matches!(target, AppServerTarget::Embedded) {
        return None;
    }
    let message = server_version_notice_for_tui(settings, client, server)?;
    let notice = ServerVersionNotice {
        message,
        offer_update: matches!(target, AppServerTarget::LocalDaemon { .. }),
    };
    let key = server_version_notice_key(target, server_home, client, server)?;
    (last_shown != Some(key.as_str())).then_some((notice, key))
}

pub(crate) fn sanitized_websocket_url(raw: &str) -> Option<Url> {
    let mut url = Url::parse(raw).ok()?;
    let _ = url.set_username("");
    let _ = url.set_password(None);
    url.set_query(None);
    url.set_fragment(None);
    Some(url)
}

#[cfg(test)]
mod tests {
    use super::*;
    use codex_utils_absolute_path::AbsolutePathBuf;
    use pretty_assertions::assert_eq;

    #[test]
    fn numeric_unleashed_versions_order_release_before_build() {
        for (server, comparison) in [
            ("0.160.0", "older than"),
            ("0.160.0+40", "older than"),
            ("0.160.0+42", "newer than"),
            ("0.161.0", "newer than"),
            ("0.161.0+1", "newer than"),
            ("0.159.0+999", "older than"),
        ] {
            let notice = server_version_notice("0.160.0+41", Some(server)).unwrap();
            assert!(notice.contains(comparison), "{server}: {notice}");
        }
        assert_eq!(
            server_version_notice("0.160.0+41", Some("0.160.0+41")),
            None
        );
    }

    #[test]
    fn newer_unmarked_server_keeps_its_release_precedence() {
        let notice = server_version_notice("0.160.0+41", Some("0.161.0")).unwrap();
        assert!(notice.contains("newer than"), "{notice}");
        assert!(!notice.contains("Update it"), "{notice}");
    }

    #[test]
    fn unleashed_client_warns_for_openai_server_and_accepts_unleashed_server() {
        let notice = server_version_notice("0.160.0+39", Some("0.160.0"))
            .expect("OpenAI server needs an upgrade warning");
        assert!(notice.contains("OpenAI"), "{notice}");
        assert!(notice.contains("Codex Unleashed app-server"), "{notice}");
        assert_eq!(
            server_version_notice("0.160.0+39", Some("0.160.0+39")),
            None
        );
        assert_eq!(
            server_version_notice("0.160.0+39", /*server*/ None),
            Some("The connected app-server did not provide a valid version. Its distribution and compatibility with this Codex CLI could not be verified.".to_string())
        );
        assert_eq!(
            server_version_notice("0.160.0+39", Some("unknown")),
            Some("The connected app-server did not provide a valid version. Its distribution and compatibility with this Codex CLI could not be verified.".to_string())
        );
        assert!(
            server_version_notice("0.160.0+dev", Some("0.160.0"))
                .unwrap()
                .contains("OpenAI")
        );
        assert_eq!(server_version_notice("0.160.0", Some("0.160.0")), None);
        assert!(
            !server_version_notice("0.160.0+39", Some("0.160.0+38"))
                .unwrap()
                .contains("OpenAI")
        );
    }

    #[test]
    fn unleashed_provider_notice_respects_settings_and_connection_identity() -> anyhow::Result<()> {
        let endpoint = RemoteAppServerEndpoint::UnixSocket {
            socket_path: AbsolutePathBuf::relative_to_current_dir("codex.sock")?,
        };
        let local = AppServerTarget::LocalDaemon {
            allow_embedded_fallback: true,
            endpoint: endpoint.clone(),
        };
        let remote = AppServerTarget::Remote { endpoint };
        let settings = codex_config::types::Tui {
            show_server_version_notice: true,
            ..Default::default()
        };
        let pending = |settings: &codex_config::types::Tui, target, server, last_shown| {
            pending_server_version_notice(
                settings,
                target,
                Some("/server"),
                "0.160.0+39",
                Some(server),
                last_shown,
            )
        };
        let (notice, key) = pending(&settings, &local, "0.160.0", None).unwrap();
        assert!(notice.offer_update);
        assert!(notice.message.contains("OpenAI"));
        assert!(
            !pending(&settings, &remote, "0.160.0", None)
                .unwrap()
                .0
                .offer_update
        );
        assert!(pending(&settings, &local, "0.160.0", Some(&key)).is_none());
        assert!(pending(&settings, &local, "0.161.0", Some(&key)).is_some());
        assert!(pending(&settings, &local, "0.160.0+39", Some(&key)).is_none());
        let disabled = codex_config::types::Tui {
            show_server_version_notice: false,
            ..settings
        };
        assert!(pending(&disabled, &local, "0.160.0", None).is_none());
        Ok(())
    }

    #[test]
    fn remote_connection_status_value_formats_display_value() -> color_eyre::Result<()> {
        assert_eq!(
            remote_connection_status_value(&AppServerTarget::Embedded, Some("1.2.3")),
            None
        );

        let websocket_target = AppServerTarget::Remote {
            endpoint: RemoteAppServerEndpoint::WebSocket {
                websocket_url: "ws://user:secret@127.0.0.1:4500/?token=abc#frag".to_string(),
                auth_token: Some("abc".to_string()),
            },
        };
        assert_eq!(
            remote_connection_status_value(&websocket_target, Some("1.2.3")),
            Some(RemoteConnectionStatus {
                address: "ws://127.0.0.1:4500/".to_string(),
                version: "v1.2.3".to_string(),
                is_local_daemon: false,
                is_managed_daemon: false,
            })
        );

        let socket_path = AbsolutePathBuf::relative_to_current_dir("codex.sock")?;
        let daemon_target = AppServerTarget::LocalDaemon {
            allow_embedded_fallback: true,
            endpoint: RemoteAppServerEndpoint::UnixSocket {
                socket_path: socket_path.clone(),
            },
        };
        assert_eq!(
            remote_connection_status_value(&daemon_target, /*server_version*/ None),
            Some(RemoteConnectionStatus {
                address: format!("unix://{}", socket_path.display()),
                version: "unknown".to_string(),
                is_local_daemon: true,
                is_managed_daemon: false,
            })
        );
        let remote_socket_target = AppServerTarget::Remote {
            endpoint: RemoteAppServerEndpoint::UnixSocket {
                socket_path: socket_path.clone(),
            },
        };
        assert_eq!(
            remote_connection_status_value(&remote_socket_target, Some("1.2.3")),
            Some(RemoteConnectionStatus {
                address: format!("unix://{}", socket_path.display()),
                version: "v1.2.3".to_string(),
                is_local_daemon: false,
                is_managed_daemon: false,
            })
        );
        Ok(())
    }

    #[test]
    fn server_version_notice_uses_client_release_policy() {
        assert_eq!(
            server_version_notice("0.153.0", Some("0.152.1")),
            Some("A background Codex service is running v0.152.1, older than your Codex CLI v0.153.0.".to_string())
        );
        assert_eq!(server_version_notice("0.153.0", Some("0.153.0")), None);
        assert_eq!(
            server_version_notice("0.0.0", Some("0.152.1")),
            Some("A background Codex service is running v0.152.1, different from your Codex CLI v0.0.0.".to_string())
        );
        assert_eq!(
            server_version_notice("0.153.0", /*server*/ None),
            Some("The connected app-server did not provide a valid version. Its distribution and compatibility with this Codex CLI could not be verified.".to_string())
        );
        assert_eq!(
            server_version_notice("0.153.0-alpha.10", Some("0.153.0-alpha.9")),
            Some("A background Codex service is running v0.153.0-alpha.9, older than your Codex CLI v0.153.0-alpha.10.".to_string())
        );
        for server in ["0.153.0-alpha.10", "0.153.0-alpha.11", "0.153.0"] {
            assert_eq!(
                server_version_notice("0.153.0-alpha.10", Some(server)),
                None
            );
        }
        assert_eq!(
            server_version_notice("0.155.0-alpha.12", Some("0.156.0")),
            Some("A background Codex service is running v0.156.0, different from your Codex CLI v0.155.0-alpha.12.".to_string())
        );
    }

    #[test]
    fn unknown_server_version_notice_reports_missing_or_malformed_versions() {
        let expected = "The connected app-server did not provide a valid version. Its distribution and compatibility with this Codex CLI could not be verified.";
        let malformed = "not a semantic version";

        assert_eq!(
            server_version_notice("0.160.0+39", None),
            Some(expected.to_string())
        );
        let notice = server_version_notice("0.160.0+39", Some(malformed)).unwrap();
        assert_eq!(notice, expected);
        assert!(!notice.contains(malformed));
    }

    #[test]
    fn unknown_server_version_notice_respects_settings_and_embedded_transport() -> anyhow::Result<()>
    {
        let endpoint = RemoteAppServerEndpoint::UnixSocket {
            socket_path: AbsolutePathBuf::relative_to_current_dir("codex.sock")?,
        };
        let remote = AppServerTarget::Remote { endpoint };
        let embedded = AppServerTarget::Embedded;
        let settings = codex_config::types::Tui {
            show_server_version_notice: true,
            ..Default::default()
        };
        let malformed = Some("not a semantic version");

        let (notice, _) = pending_server_version_notice(
            &settings,
            &remote,
            Some("/server"),
            "0.160.0+39",
            None,
            None,
        )
        .expect("missing remote version should produce a notice");
        assert!(notice.message.contains("did not provide a valid version"));
        assert!(!notice.offer_update);
        assert!(
            pending_server_version_notice(
                &settings,
                &remote,
                Some("/server"),
                "0.160.0+39",
                malformed,
                None,
            )
            .is_some()
        );

        let disabled = codex_config::types::Tui {
            show_server_version_notice: false,
            ..Default::default()
        };
        assert!(
            pending_server_version_notice(
                &disabled,
                &remote,
                Some("/server"),
                "0.160.0+39",
                malformed,
                None,
            )
            .is_none()
        );
        assert!(
            pending_server_version_notice(
                &settings,
                &embedded,
                Some("/server"),
                "0.160.0+39",
                Some("0.159.0"),
                None,
            )
            .is_none()
        );
        Ok(())
    }

    #[test]
    fn unknown_server_version_notice_key_is_stable_and_preserves_connection_identity()
    -> anyhow::Result<()> {
        let socket = |path| -> anyhow::Result<RemoteAppServerEndpoint> {
            Ok(RemoteAppServerEndpoint::UnixSocket {
                socket_path: AbsolutePathBuf::from_absolute_path(path)?,
            })
        };
        let target = AppServerTarget::Remote {
            endpoint: socket("/server-a")?,
        };
        let other_endpoint = AppServerTarget::Remote {
            endpoint: socket("/server-b")?,
        };
        let key = |target: &AppServerTarget, server_home, client, server| {
            server_version_notice_key(target, server_home, client, server)
        };
        let absent = key(&target, Some("/home/a"), "0.160.0+39", None)
            .expect("missing version should have a deduplication key");
        let malformed_version = "not a semantic version";
        let malformed = key(
            &target,
            Some("/home/a"),
            "0.160.0+39",
            Some(malformed_version),
        )
        .expect("malformed version should have a deduplication key");

        assert_eq!(
            malformed,
            key(
                &target,
                Some("/home/a"),
                "0.160.0+39",
                Some(malformed_version),
            )
            .unwrap()
        );
        assert_ne!(absent, malformed);
        assert!(!malformed.contains(malformed_version));
        assert_ne!(
            malformed,
            key(
                &other_endpoint,
                Some("/home/a"),
                "0.160.0+39",
                Some(malformed_version)
            )
            .unwrap()
        );
        assert_ne!(
            malformed,
            key(
                &target,
                Some("/home/b"),
                "0.160.0+39",
                Some(malformed_version)
            )
            .unwrap()
        );
        assert_ne!(
            malformed,
            key(
                &target,
                Some("/home/a"),
                "0.160.0+40",
                Some(malformed_version)
            )
            .unwrap()
        );
        Ok(())
    }

    #[test]
    fn update_command_is_only_suggested_for_implicit_local_daemon() -> color_eyre::Result<()> {
        let endpoint = RemoteAppServerEndpoint::UnixSocket {
            socket_path: AbsolutePathBuf::relative_to_current_dir("codex.sock")?,
        };
        let local = AppServerTarget::LocalDaemon {
            allow_embedded_fallback: true,
            endpoint: endpoint.clone(),
        };
        let remote = AppServerTarget::Remote { endpoint };
        let settings = codex_config::types::Tui {
            show_server_version_notice: true,
            ..Default::default()
        };
        let notice = |target| {
            pending_server_version_notice(
                &settings,
                target,
                /*server_home*/ None,
                "0.153.0-alpha.10.1",
                Some("0.153.0-alpha.9.2"),
                /*last_shown*/ None,
            )
            .map(|(notice, _)| notice)
        };
        let remote_notice =
            server_version_notice("0.153.0-alpha.10.1", Some("0.153.0-alpha.9.2")).unwrap();
        assert_eq!(
            notice(&remote),
            Some(ServerVersionNotice {
                message: remote_notice.clone(),
                offer_update: false,
            })
        );
        assert_eq!(
            notice(&local),
            Some(ServerVersionNotice {
                message: remote_notice,
                offer_update: true,
            })
        );
        Ok(())
    }

    #[test]
    fn notice_key_identifies_socket_and_server_home_across_connection_modes() -> anyhow::Result<()>
    {
        let socket = |path| -> anyhow::Result<RemoteAppServerEndpoint> {
            Ok(RemoteAppServerEndpoint::UnixSocket {
                socket_path: AbsolutePathBuf::from_absolute_path(path)?,
            })
        };
        let local = AppServerTarget::LocalDaemon {
            allow_embedded_fallback: true,
            endpoint: socket("/c")?,
        };
        let remote = AppServerTarget::Remote {
            endpoint: socket("/c")?,
        };
        let key = |target: &AppServerTarget, server_home| {
            server_version_notice_key(target, server_home, "0.153.0", Some("0.152.0"))
        };
        assert_eq!(key(&local, Some("/a")), key(&remote, Some("/a")));
        let other = AppServerTarget::Remote {
            endpoint: socket("/bunix:/c")?,
        };
        assert_ne!(key(&remote, Some("/aunix:/b")), key(&other, Some("/a")));
        Ok(())
    }
}
