use super::*;
use http::HeaderValue;

#[test]
fn invalid_custom_ca_errors_preserve_the_configured_source_and_path() {
    const CHILD_ENV: &str = "CODEX_CLIENT_BUILDER_INVALID_CA_TEST_CHILD";
    const CA_SOURCE_ENV: &str = "CODEX_CLIENT_BUILDER_INVALID_CA_TEST_SOURCE";
    const CA_PATH_ENV: &str = "CODEX_CLIENT_BUILDER_INVALID_CA_TEST_PATH";

    if std::env::var_os(CHILD_ENV).is_none() {
        let temp_dir = tempfile::tempdir().expect("temporary directory should be created");
        let invalid_ca_path = temp_dir.path().join("invalid-ca.pem");
        std::fs::write(&invalid_ca_path, "not a PEM certificate")
            .expect("invalid CA fixture should be written");

        for ca_env in ["CODEX_CA_CERTIFICATE", "SSL_CERT_FILE"] {
            let output = std::process::Command::new(
                std::env::current_exe().expect("test executable should be available"),
            )
            .arg("--exact")
            .arg(
                concat!(
                    module_path!(),
                    "::invalid_custom_ca_errors_preserve_the_configured_source_and_path"
                )
                .trim_start_matches("codex_http_client::"),
            )
            .arg("--nocapture")
            .env_remove("CODEX_CA_CERTIFICATE")
            .env_remove("SSL_CERT_FILE")
            .env(ca_env, &invalid_ca_path)
            .env(CA_SOURCE_ENV, ca_env)
            .env(CA_PATH_ENV, &invalid_ca_path)
            .env(CHILD_ENV, "1")
            .output()
            .expect("isolated CA subprocess should run");

            assert!(
                output.status.success(),
                "strict client construction did not preserve invalid {ca_env}\nstdout:\n{}\nstderr:\n{}",
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr),
            );
        }
        return;
    }

    let source = std::env::var(CA_SOURCE_ENV).expect("CA source should be set");
    let path = std::env::var(CA_PATH_ENV).expect("CA path should be set");
    let error = HttpClientBuilder::new()
        .build_direct()
        .expect_err("invalid configured CA must reject strict client construction");
    let message = error.to_string();
    assert!(
        message.contains(&source),
        "error omitted CA source: {message}"
    );
    assert!(message.contains(&path), "error omitted CA path: {message}");
}

/// A server cannot keep a connection reusable after the caller requests closure.
#[tokio::test]
async fn connection_close_prevents_reuse_when_server_ignores_it()
-> Result<(), Box<dyn std::error::Error>> {
    use tokio::io::AsyncReadExt;
    use tokio::io::AsyncWriteExt;

    let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await?;
    let url = format!("http://{}/", listener.local_addr()?);
    let server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await?;
        let mut headers = Vec::new();
        while !headers.ends_with(b"\r\n\r\n") {
            headers.push(stream.read_u8().await?);
        }
        // Deliberately ignore the request's Connection: close header.
        let response = b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n";
        stream.write_all(response).await?;
        let mut byte = [0];
        let reused = tokio::select! {
            read = stream.read(&mut byte) => {
                if read? == 0 {
                    stream = listener.accept().await?.0;
                    false
                } else {
                    true
                }
            }
            accepted = listener.accept() => {
                stream = accepted?.0;
                false
            }
        };
        headers.clear();
        if reused {
            headers.push(byte[0]);
        }
        while !headers.ends_with(b"\r\n\r\n") {
            headers.push(stream.read_u8().await?);
        }
        stream.write_all(response).await?;
        Ok::<bool, std::io::Error>(reused)
    });
    let client = HttpClientBuilder::new().build_direct()?;
    for _ in 0..2 {
        client
            .get(&url)
            .header(http::header::CONNECTION, HeaderValue::from_static("close"))
            .send()
            .await?
            .bytes()
            .await?;
    }
    assert!(
        !server.await??,
        "the server reused a connection marked closed"
    );
    Ok(())
}
