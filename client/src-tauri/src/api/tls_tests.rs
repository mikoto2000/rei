use super::HttpReiClient;
use std::{sync::Arc, time::Duration};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_rustls::{rustls, TlsAcceptor};

const CA: &[u8] = include_bytes!("../../tests/fixtures/tls/ca.der");
const CERTIFICATE: &[u8] = include_bytes!("../../tests/fixtures/tls/server.der");
const KEY: &[u8] = include_bytes!("../../tests/fixtures/tls/server-key.der");

async fn server() -> (u16, tokio::task::JoinHandle<()>) {
    let certificate = rustls::pki_types::CertificateDer::from(CERTIFICATE.to_vec());
    let key = rustls::pki_types::PrivatePkcs8KeyDer::from(KEY.to_vec());
    let config = rustls::ServerConfig::builder_with_provider(Arc::new(
        rustls::crypto::aws_lc_rs::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .unwrap()
    .with_no_client_auth()
    .with_single_cert(vec![certificate], key.into())
    .unwrap();
    let acceptor = TlsAcceptor::from(Arc::new(config));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let task = tokio::spawn(async move {
        let (socket, _) = listener.accept().await.unwrap();
        // Verification failures deliberately close the TLS handshake.
        if let Ok(mut stream) = acceptor.accept(socket).await {
            let mut request = [0; 4096];
            if stream.read(&mut request).await.is_ok() {
                let _ = stream
                    .write_all(
                        b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}",
                    )
                    .await;
            }
        }
    });
    (port, task)
}

#[tokio::test]
async fn production_trust_bundle_does_not_accept_a_private_test_ca() {
    let (port, task) = server().await;
    let url = format!("https://localhost:{port}");
    let api = HttpReiClient::new(&url, None).unwrap();
    let error = api
        .client
        .get(&url)
        .timeout(Duration::from_secs(5))
        .send()
        .await
        .unwrap_err();
    assert!(error.is_connect());
    tokio::time::timeout(Duration::from_secs(5), task)
        .await
        .unwrap()
        .unwrap();
}

#[tokio::test]
async fn explicit_roots_verify_the_chain_and_hostname_during_real_tls_handshakes() {
    let client = HttpReiClient::http_client([reqwest::Certificate::from_der(CA).unwrap()]).unwrap();
    let (port, task) = server().await;
    let response = client
        .get(format!("https://localhost:{port}"))
        .timeout(Duration::from_secs(5))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), reqwest::StatusCode::OK);
    tokio::time::timeout(Duration::from_secs(5), task)
        .await
        .unwrap()
        .unwrap();

    let (port, task) = server().await;
    let error = client
        .get(format!("https://127.0.0.1:{port}"))
        .timeout(Duration::from_secs(5))
        .send()
        .await
        .unwrap_err();
    assert!(error.is_connect());
    tokio::time::timeout(Duration::from_secs(5), task)
        .await
        .unwrap()
        .unwrap();
}
