use std::collections::BTreeMap;

use pretty_assertions::assert_eq;
use serde::Deserialize;

use super::from_yaml_slice;
use super::from_yaml_str;

/// A scalar whose type comes from YAML's own inference, as in an untyped frontmatter value.
#[derive(Debug, Deserialize, PartialEq)]
#[serde(untagged)]
enum Scalar {
    Bool(bool),
    Integer(i64),
    String(String),
}

#[derive(Debug, Deserialize)]
struct Flag {
    enabled: bool,
}

#[test]
fn untyped_scalars_follow_yaml_1_2() {
    let parsed: BTreeMap<String, Scalar> =
        from_yaml_str("on: yes\nflag: true\nmode: 0755\ncount: 1_000\nlimit: 42\n")
            .expect("valid YAML");
    let expected = BTreeMap::from([
        ("count".to_string(), Scalar::String("1_000".to_string())),
        ("flag".to_string(), Scalar::Bool(true)),
        ("limit".to_string(), Scalar::Integer(42)),
        ("mode".to_string(), Scalar::String("0755".to_string())),
        ("on".to_string(), Scalar::String("yes".to_string())),
    ]);
    assert_eq!(parsed, expected);
}

#[test]
fn booleans_accept_only_true_and_false() {
    assert_eq!(
        from_yaml_slice::<Flag>(b"enabled: true\n")
            .ok()
            .map(|flag| flag.enabled),
        Some(true)
    );
    assert!(from_yaml_slice::<Flag>(b"enabled: yes\n").is_err());
}
