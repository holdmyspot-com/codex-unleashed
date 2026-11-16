use super::*;
use pretty_assertions::assert_eq;
use rtc::interceptor::Attribute;
use rtc::rtp::header::Header;
use rtc::sansio::Protocol;
use rtc::shared::{EcnCodepoint, TransportContext, TransportProtocol};
use std::net::SocketAddr;

fn pair() -> (Incoming, Ingress) {
    let (incoming, ingress) = Incoming::new();
    incoming.set_suppressed(/*suppressed*/ false).unwrap();
    (incoming, ingress)
}

fn packet(size: usize, at: Instant) -> TaggedPacket {
    TaggedPacket {
        now: at,
        transport: Default::default(),
        message: Packet::Rtp(rtc::rtp::Packet {
            header: Header {
                version: 2,
                payload_type: 111,
                ssrc: 7,
                ..Default::default()
            },
            payload: vec![5; size - 12].into(),
        })
        .into(),
    }
}

fn rtcp_packet(
    at: Instant,
    transport: TransportContext,
    source: u32,
    reason: &'static [u8],
    attributes: Vec<Attribute>,
) -> TaggedPacket {
    let mut packet = TaggedPacket {
        now: at,
        transport,
        message: Packet::Rtcp(vec![Box::new(rtc::rtcp::goodbye::Goodbye {
            sources: vec![source],
            reason: reason.into(),
        })])
        .into(),
    };
    packet.message.attributes = attributes;
    packet
}

fn assert_packet_fields_equal(actual: &TaggedPacket, expected: &TaggedPacket) {
    assert_eq!(actual.now, expected.now);
    assert_eq!(actual.transport, expected.transport);
    assert_eq!(actual.message.packet, expected.message.packet);
    assert_eq!(
        actual.message.attributes.len(),
        expected.message.attributes.len()
    );
    assert_eq!(
        actual
            .message
            .attributes
            .iter()
            .map(std::mem::discriminant)
            .collect::<Vec<_>>(),
        expected
            .message
            .attributes
            .iter()
            .map(std::mem::discriminant)
            .collect::<Vec<_>>()
    );
}

#[test]
fn non_rtp_packets_forward_once_in_order_without_changing_rtp_or_outbound_behavior() {
    let (mut incoming, mut ingress) = pair();
    let first = rtcp_packet(
        Instant::now(),
        TransportContext {
            local_addr: SocketAddr::from(([127, 0, 0, 1], 4000)),
            peer_addr: SocketAddr::from(([127, 0, 0, 1], 5000)),
            transport_protocol: TransportProtocol::UDP,
            ecn: Some(EcnCodepoint::Ect0),
        },
        11,
        b"first sender left",
        vec![Attribute::DeliverToApplication],
    );
    let second = rtcp_packet(
        Instant::now(),
        TransportContext {
            local_addr: SocketAddr::from(([127, 0, 0, 1], 4001)),
            peer_addr: SocketAddr::from(([127, 0, 0, 1], 5001)),
            transport_protocol: TransportProtocol::TCP,
            ecn: Some(EcnCodepoint::Ce),
        },
        22,
        b"second sender left",
        vec![Attribute::RecoveredByFec, Attribute::DeliverToApplication],
    );
    let first_expected = TaggedPacket {
        now: first.now,
        transport: first.transport,
        message: first.message.clone(),
    };
    let second_expected = TaggedPacket {
        now: second.now,
        transport: second.transport,
        message: second.message.clone(),
    };

    ingress.handle_read(first).unwrap();
    ingress.handle_read(second).unwrap();
    let first_actual = ingress.poll_read().unwrap();
    let second_actual = ingress.poll_read().unwrap();
    assert_packet_fields_equal(&first_actual, &first_expected);
    assert_packet_fields_equal(&second_actual, &second_expected);
    assert!(ingress.poll_read().is_none());

    let input = packet(/*size*/ 32, Instant::now());
    let Packet::Rtp(expected_rtp) = &input.message.packet else {
        panic!()
    };
    let expected_bytes = expected_rtp.marshal().unwrap();
    let expected_at = input.now;
    ingress.handle_read(input).unwrap();
    let received = incoming.take().unwrap().unwrap();
    assert_eq!(received.at, expected_at);
    assert_eq!(received.as_ref(), expected_bytes.as_ref());
    assert!(ingress.poll_read().is_none());

    let outgoing = packet(/*size*/ 32, Instant::now());
    let outgoing_expected = TaggedPacket {
        now: outgoing.now,
        transport: outgoing.transport,
        message: outgoing.message.clone(),
    };
    ingress.handle_write(outgoing).unwrap();
    let outgoing_actual = ingress.poll_write().unwrap();
    assert_packet_fields_equal(&outgoing_actual, &outgoing_expected);
    assert!(ingress.poll_write().is_none());
}

#[test]
fn arrival_and_rtp_bytes_survive_the_adapter_without_an_upstream_copy() {
    let (mut incoming, mut ingress) = pair();
    let at = Instant::now() - Duration::from_millis(/*millis*/ 20);
    let input = packet(/*size*/ 32, at);
    let Packet::Rtp(expected) = &input.message.packet else {
        panic!()
    };
    let expected = expected.marshal().unwrap();
    ingress.handle_read(input).unwrap();
    let received = incoming.take().unwrap().unwrap();
    assert_eq!((received.at, received.as_ref()), (at, expected.as_ref()));
    assert!(ingress.poll_read().is_none());
}

#[test]
fn exhausted_packet_or_byte_budget_drops_audio_then_accepts_fresh_packets() {
    for size in [32, PACKET_BYTES] {
        let (mut incoming, mut ingress) = pair();
        let count = PACKETS.min(BYTES / size);
        let mut held = Vec::new();
        for _ in 0..count {
            ingress.handle_read(packet(size, Instant::now())).unwrap();
            held.push(incoming.take().unwrap().unwrap());
        }
        drop(held.pop());
        ingress.handle_read(packet(size, Instant::now())).unwrap();
        held.push(incoming.take().unwrap().unwrap());
        ingress.handle_read(packet(size, Instant::now())).unwrap();
        assert!(incoming.take().unwrap().is_none());
        assert!(!incoming.state.failed.load(Ordering::Acquire));
        drop(held);
        ingress.handle_read(packet(size, Instant::now())).unwrap();
        assert!(incoming.take().unwrap().is_some());
        assert_eq!(
            (
                incoming.state.packets.available_permits(),
                incoming.state.bytes.available_permits()
            ),
            (PACKETS, BYTES)
        );
    }
}

#[test]
fn old_speaker_packets_are_discarded_without_poisoning_fresh_audio() {
    let (mut incoming, mut ingress) = pair();
    ingress
        .handle_read(packet(/*size*/ 32, Instant::now()))
        .unwrap();
    let mut queued = incoming.receiver.try_recv().unwrap();
    queued.at -= Duration::from_secs(/*secs*/ 2);
    ingress.sender.try_send(queued).unwrap();
    assert!(incoming.take().unwrap().is_none());
    ingress
        .handle_read(packet(/*size*/ 32, Instant::now()))
        .unwrap();
    assert!(incoming.take().unwrap().is_some());
}

#[test]
fn suppression_discards_queued_and_in_flight_old_epochs() {
    let (mut incoming, mut ingress) = pair();
    ingress
        .handle_read(packet(/*size*/ 32, Instant::now()))
        .unwrap();
    incoming.set_suppressed(/*suppressed*/ true).unwrap();
    ingress
        .handle_read(packet(/*size*/ 32, Instant::now()))
        .unwrap();
    incoming.set_suppressed(/*suppressed*/ false).unwrap();
    assert!(incoming.take().unwrap().is_none());
    ingress
        .handle_read(packet(/*size*/ 32, Instant::now()))
        .unwrap();
    assert!(incoming.take().unwrap().is_some());
}

#[test]
fn invalid_stream_and_oversize_packets_fail_without_returning_media() {
    for size in [32, PACKET_BYTES + 1] {
        let (mut incoming, mut ingress) = pair();
        let mut input = packet(size, Instant::now());
        if size == 32
            && let Packet::Rtp(packet) = &mut input.message.packet
        {
            packet.header.payload_type = 96;
        }
        ingress.handle_read(input).unwrap();
        assert_eq!(incoming.take().err(), Some("incoming audio failed"));
    }
}
