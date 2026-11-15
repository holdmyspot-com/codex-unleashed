use super::*;
use pretty_assertions::assert_eq;

#[tokio::test]
async fn timestamp_jitter_does_not_accumulate_but_mute_advances_the_clock() {
    let (_, mut track) = AudioTrack::new().unwrap();
    let start = Instant::now();
    // Empty samples exercise the real packetizer without requiring a bound network peer.
    for tick in 0..500 {
        let at = start + Duration::from_millis(tick * 20 + tick % 2);
        track.send(EncodedAudio { data: vec![], at }).await.unwrap();
    }
    assert_eq!(track.end, Some(start + Duration::from_millis(10_000)));
    let at = start + Duration::from_millis(30_007);
    track.send(EncodedAudio { data: vec![], at }).await.unwrap();
    assert_eq!(track.end, Some(start + Duration::from_millis(30_020)));
}

#[test]
fn captured_audio_sample_preserves_capture_time() {
    let captured_at = Instant::now() - Duration::from_secs(30);
    let payload = vec![1, 2, 3, 4];
    let duration = Duration::from_millis(20);
    let sample = EncodedAudio {
        data: payload.clone(),
        at: captured_at,
    }
    .into_sample(duration);

    assert_eq!(sample.timestamp, captured_at);
    assert_eq!(sample.data.as_ref(), payload.as_slice());
    assert_eq!(sample.duration, duration);
}
