//! Actual packet emitted by the Android PostPacket implementation (synthetic images).
use moment_keyserver::protocol::{self, Packet};
use serde_json::Value;

#[test]
fn decrypt_and_verify_android_packet() {
    let fixture: Value =
        serde_json::from_str(include_str!("fixtures/android-post-v1.json")).unwrap();
    let field = |name: &str| fixture[name].as_str().unwrap();
    let blob = protocol::unbase64(field("blob"), protocol::MAX_BLOB).unwrap();
    let key = protocol::key64(field("postKey")).unwrap();
    assert_eq!(hex::encode(protocol::hash(&blob)), field("blobRef"));
    let packet = Packet::decrypt(&blob, &key).unwrap();
    let commitment = packet
        .verify(
            field("network"),
            &protocol::key64(field("program")).unwrap(),
            &protocol::key64(field("wallet")).unwrap(),
            fixture["day"].as_i64().unwrap(),
        )
        .unwrap();
    assert_eq!(hex::encode(commitment), field("commitment"));
    assert_eq!(
        *packet.rear,
        protocol::unbase64(field("rear"), 100).unwrap()
    );
    assert_eq!(
        *packet.front,
        protocol::unbase64(field("front"), 100).unwrap()
    );
}

#[test]
fn validate_and_cosign_android_transaction() {
    use ed25519_dalek::SigningKey;
    use moment_keyserver::chain::{self, Expected};
    let fixture: Value =
        serde_json::from_str(include_str!("fixtures/android-post-v1.json")).unwrap();
    let field = |name: &str| fixture[name].as_str().unwrap();
    let authority = SigningKey::from_bytes(&[8; 32]);
    let expected = Expected {
        program: protocol::key64(field("program")).unwrap(),
        wallet: protocol::key64(field("wallet")).unwrap(),
        authority: authority.verifying_key().to_bytes(),
        day: fixture["day"].as_i64().unwrap(),
        commitment: protocol::hash_hex(field("commitment")).unwrap(),
        blob_ref: protocol::hash_hex(field("blobRef")).unwrap(),
    };
    let raw = protocol::unbase64(field("transaction"), 1232).unwrap();
    assert_eq!(
        chain::validate_transaction(&raw, &expected).unwrap(),
        [9; 32]
    );
    let signed = chain::cosign(&raw, &expected, &authority).unwrap();
    assert_eq!(&signed[129..], &raw[129..]);
    protocol::verify(&expected.authority, &signed[129..], &signed[65..129]).unwrap();
}
