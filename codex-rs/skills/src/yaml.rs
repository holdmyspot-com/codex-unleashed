//! Reads YAML with the YAML 1.2 rules that codex's skill, plugin and agent files were written against.

use serde::Deserialize;

/// Error produced while reading YAML.
pub type YamlError = serde_saphyr::Error;

/// Accepts only `true` and `false` as booleans and keeps numbers spelled with leading zeros or underscores as
/// strings, as serde_yaml did, and reports errors without source snippets.
fn options() -> serde_saphyr::Options {
    serde_saphyr::options! {
        strict_booleans: true,
        leading_zero_numbers_as_strings: true,
        underscored_numbers_as_strings: true,
        with_snippet: false,
    }
}

/// Deserializes one YAML document from a string.
pub fn from_yaml_str<'de, T: Deserialize<'de>>(input: &'de str) -> Result<T, YamlError> {
    serde_saphyr::from_str_with_options(input, options())
}

/// Deserializes one YAML document from UTF-8 bytes.
pub fn from_yaml_slice<'de, T: Deserialize<'de>>(input: &'de [u8]) -> Result<T, YamlError> {
    serde_saphyr::from_slice_with_options(input, options())
}

#[cfg(test)]
#[path = "yaml_tests.rs"]
mod tests;
