#[path = "transport_network_tests.rs"]
mod network;

use super::*;
use pretty_assertions::assert_eq;
use rtc::interceptor::Interceptor;
use rtc::interceptor::Packet;
use rtc::interceptor::StreamInfo;
use rtc::interceptor::TaggedPacket;
use rtc::rtp::header::Header;
use rtc::sansio::Protocol;
use rtc::shared::marshal::Marshal;
use std::time::Instant;
use tokio::sync::mpsc;
use webrtc::data_channel::RTCDataChannelState;

/// Registry assembly delivers original arrivals once while optional jitter buffering is installed.
#[test]
fn ingress_precedes_optional_jitter_without_duplicate_rtp() {
    let (mut incoming, ingress) = crate::incoming::Incoming::new();
    incoming.set_suppressed(/*suppressed*/ false).unwrap();
    let depth = Duration::from_millis(/*millis*/ 40);
    let mut chain = Transport::interceptor_registry(ingress)
        .with(
            rtc::interceptor::Slot::JitterBuffer,
            rtc::interceptor::JitterBufferBuilder::new()
                .with_depth(depth)
                .build(),
        )
        .build();
    chain.bind_remote_stream(&StreamInfo {
        ssrc: 7,
        clock_rate: 48_000,
        payload_type: 111,
        mime_type: "audio/opus".to_owned(),
        ..Default::default()
    });
    let arrival = Instant::now() - Duration::from_millis(/*millis*/ 20);
    let packet = rtc::rtp::Packet {
        header: Header {
            version: 2,
            payload_type: 111,
            sequence_number: 321,
            timestamp: 960,
            ssrc: 7,
            ..Default::default()
        },
        payload: vec![0xf8, 0xff, 0xfe].into(),
    };
    let expected = packet.marshal().unwrap();

    chain
        .handle_read(TaggedPacket {
            now: arrival,
            transport: Default::default(),
            message: Packet::Rtp(packet).into(),
        })
        .unwrap();
    let received = incoming
        .take()
        .unwrap()
        .expect("ingress must receive RTP before jitter buffering");
    assert_eq!(
        (received.at, received.as_ref()),
        (arrival, expected.as_ref())
    );
    assert!(incoming.take().unwrap().is_none());
    assert!(chain.poll_read().is_none());

    chain
        .handle_timeout(arrival + depth + Duration::from_millis(/*millis*/ 1))
        .unwrap();
    assert!(chain.poll_read().is_none());
    assert!(incoming.take().unwrap().is_none());
}

struct RemoteEvents(mpsc::Sender<Arc<dyn DataChannel>>, Arc<Notify>);

impl PeerConnectionEventHandler for RemoteEvents {
    fn on_data_channel<'a, 'async_trait>(
        &'a self,
        channel: Arc<dyn DataChannel>,
    ) -> BoxFuture<'async_trait, ()>
    where
        'a: 'async_trait,
        Self: 'async_trait,
    {
        Box::pin(async move {
            self.0.try_send(channel).unwrap();
        })
    }

    fn on_ice_gathering_state_change<'a, 'async_trait>(
        &'a self,
        state: RTCIceGatheringState,
    ) -> BoxFuture<'async_trait, ()>
    where
        'a: 'async_trait,
        Self: 'async_trait,
    {
        Box::pin(async move {
            if state == RTCIceGatheringState::Complete {
                self.1.notify_one();
            }
        })
    }
}

#[tokio::test]
async fn negotiates_real_ordered_channel_over_udp_and_tcp() {
    check_negotiation(Arc::new(crate::transport_runtime::VoiceRuntime::default())).await;
}

#[tokio::test]
async fn negotiates_after_early_packet_loss_and_slow_tcp_connect() {
    check_negotiation(Arc::new(network::DelayedNetwork::default())).await;
}

async fn check_negotiation(runtime: Arc<dyn webrtc::runtime::Runtime>) {
    for tcp in [false, true] {
        timeout(Duration::from_secs(/*secs*/ 30), async {
            let (sender, mut channels) = mpsc::channel(/*buffer*/ 1);
            let gathered = Arc::new(Notify::new());
            let settings = webrtc::peer_connection::SettingEngineBuilder::new()
                .with_lite(/*lite*/ true)
                .build();
            let (media, mut remote_audio) = crate::audio_track::AudioTrack::new().unwrap();
            let builder = PeerConnectionBuilder::new()
                .with_media_engine(media)
                .with_setting_engine(settings)
                .with_handler(Arc::new(RemoteEvents(sender, gathered.clone())));
            let remote = if tcp {
                builder.with_tcp_addrs(vec!["0.0.0.0:0"])
            } else {
                builder.with_udp_addrs(vec!["0.0.0.0:0"])
            }
            .build()
            .await
            .unwrap();
            remote.add_track(remote_audio.track.clone()).await.unwrap();
            let mut local = Transport::with_runtime(runtime.clone()).await.unwrap();
            let offer = RTCSessionDescription::offer(local.offer().await.unwrap()).unwrap();
            remote.set_remote_description(offer).await.unwrap();
            let answer = remote.create_answer(/*options*/ None).await.unwrap();
            remote.set_local_description(answer).await.unwrap();
            gathered.notified().await;
            let mut answer = remote.local_description().await.unwrap().sdp;
            let candidates: Vec<_> = answer
                .lines()
                .filter(|line| line.starts_with("a=candidate:"))
                .map(str::to_owned)
                .collect();
            // Normal generated UDP/TCP answers still connect at the admission boundary.
            assert!(!candidates.is_empty() && candidates.len() <= MAX_REMOTE_CANDIDATES);
            for _ in candidates.len()..MAX_REMOTE_CANDIDATES {
                answer.push_str(&format!("{}\r\n", candidates[0]));
            }
            let outcome = local
                .apply_answer(answer)
                .await
                .unwrap_or_else(|error| panic!("tcp={tcp}: {error}"));
            assert_eq!(outcome, super::AnswerOutcome::Ready);
            let channel = channels.recv().await.unwrap();
            assert_eq!(
                (
                    channel.label().await.unwrap(),
                    channel.ordered().await.unwrap()
                ),
                ("oai-events".into(), true)
            );
            let unexpected = remote
                .create_data_channel("unexpected", /*options*/ None)
                .await
                .unwrap();
            loop {
                match unexpected.poll().await {
                    Some(DataChannelEvent::OnClose) => break,
                    Some(
                        DataChannelEvent::OnOpen
                        | DataChannelEvent::OnClosing
                        | DataChannelEvent::OnBufferedAmountLow
                        | DataChannelEvent::OnBufferedAmountHigh,
                    ) => {}
                    event => panic!("unexpected channel did not close: {event:?}"),
                }
            }
            assert_eq!(
                (channel.ready_state().await.unwrap(), *local.ready.borrow()),
                (RTCDataChannelState::Open, true)
            );
            channel.send_text("synthetic sideband event").await.unwrap();
            local.incoming.set_suppressed(/*suppressed*/ false).unwrap();
            let sent = std::time::Instant::now();
            remote_audio
                .send(crate::audio_track::EncodedAudio {
                    data: vec![0xf8, 0xff, 0xfe],
                    at: sent,
                })
                .await
                .unwrap();
            let received = loop {
                if let Some(packet) = local.incoming.take().unwrap() {
                    break packet;
                }
                tokio::time::sleep(Duration::from_millis(/*millis*/ 5)).await;
            };
            assert!(received.at >= sent);
            assert_eq!(
                &received.as_ref()[received.as_ref().len() - 3..],
                &[0xf8, 0xff, 0xfe]
            );
            local.close().await.unwrap();
            assert!(local.ready.has_changed().is_err());
            remote.close().await.unwrap();
        })
        .await
        .unwrap();
    }
}

#[tokio::test]
async fn rejects_excess_or_invalid_candidates_before_mutating_peer() {
    let mut peer = Transport::new().await.unwrap();
    let offer = peer.offer().await.unwrap();
    let header = offer
        .lines()
        .filter(|line| !line.starts_with("a=candidate:"))
        .map(|line| format!("{line}\r\n"))
        .collect::<String>();
    for transport in ["udp", "tcp"] {
        let mut answer = header.clone();
        for index in 0..=MAX_REMOTE_CANDIDATES {
            let suffix = if transport == "tcp" {
                " tcptype passive"
            } else {
                ""
            };
            answer.push_str(&format!(
                "a=candidate:{index} 1 {transport} 1 192.0.2.1 9 typ host{suffix}\r\n"
            ));
        }
        assert_eq!(
            peer.apply_answer(answer).await,
            Err("too many voice candidates")
        );
        assert!(peer.connection.remote_description().await.is_none());
    }
    // The budget is global, before duplicate/component/inactive-media filtering.
    let candidate = "a=candidate:duplicate 2 udp 1 192.0.2.1 9 typ host\r\n";
    let answer = format!(
        "{header}{}m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\na=inactive\r\n{}",
        candidate.repeat(16),
        candidate.repeat(17),
    );
    assert_eq!(
        peer.apply_answer(answer).await,
        Err("too many voice candidates")
    );
    assert!(peer.connection.remote_description().await.is_none());
    let answer = format!("{header}{candidate}a=candidate:synthetic-secret-invalid\r\n");
    assert_eq!(
        peer.apply_answer(answer).await,
        Err("invalid voice candidate")
    );
    assert!(peer.connection.remote_description().await.is_none());
    peer.close().await.unwrap();
}

#[tokio::test]
async fn invalid_answer_is_redacted_and_peer_can_close() {
    let mut peer = Transport::new().await.unwrap();
    peer.offer().await.unwrap();
    assert_eq!(
        peer.apply_answer("synthetic-secret-invalid-sdp".into())
            .await,
        Err("invalid voice answer")
    );
    peer.close().await.unwrap();
}
