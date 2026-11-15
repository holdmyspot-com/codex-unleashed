use crate::config_toml::ConfigToml;
use crate::types::RawMcpServerConfig;
use codex_features::FEATURES;
use codex_features::legacy_feature_keys;
use codex_protocol::protocol::GranularApprovalConfig;
use schemars::JsonSchema;
use schemars::Schema;
use schemars::SchemaGenerator;
use schemars::generate::SchemaSettings;
use schemars::json_schema;
use schemars::transform::ReplaceConstValue;
use schemars::transform::transform_subschemas;
use serde_json::Map;
use serde_json::Value;
use serde_json::json;
use std::collections::BTreeMap;
use std::path::Path;

/// Determines the conditions under which the user is consulted to approve
/// running the command proposed by Codex.
#[allow(dead_code)]
#[derive(JsonSchema)]
#[schemars(rename = "AskForApproval")]
#[serde(rename_all = "kebab-case")]
pub(crate) enum ConfigAskForApproval {
    /// The model decides when to ask the user for approval.
    OnRequest,

    /// Fine-grained controls for individual approval flows.
    ///
    /// When a field is `true`, commands in that category are allowed. When it
    /// is `false`, those requests are automatically rejected instead of shown
    /// to the user.
    Granular(GranularApprovalConfig),

    /// Never ask the user to approve commands. Failures are immediately returned
    /// to the model, and never escalated to the user for approval.
    Never,
}

/// Schema for the `[features]` map with known + legacy keys only.
pub fn features_schema(schema_gen: &mut SchemaGenerator) -> Schema {
    let mut properties: BTreeMap<String, Schema> = BTreeMap::new();
    for feature in FEATURES {
        if feature.id == codex_features::Feature::Artifact {
            continue;
        }
        if feature.id == codex_features::Feature::CodeMode {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::CodeModeConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::CodeModeHost {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::CodeModeHostConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::NonPrefixedMcpToolNames {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::NonPrefixedMcpToolNamesConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::GuardianThreadContext {
            // This setting is already part of the guardianv2 feature table.
            continue;
        }
        if feature.id == codex_features::Feature::GuardianV2 {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::GuardianV2ConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::GuardianV3 {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::GuardianV3ConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::MultiAgentV2 {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::MultiAgentV2ConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::AsyncCompaction {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::AsyncCompactionConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::TokenBudget {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::TokenBudgetConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::ContextManagement {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::ContextManagementConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::RolloutBudget {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::RolloutBudgetConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::CurrentTimeReminder {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::CurrentTimeReminderConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::SleepTool {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::SleepToolConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::RuntimeAgentInstances {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::RuntimeAgentInstancesConfigToml,
                >>(),
            );
            continue;
        }
        if feature.id == codex_features::Feature::AppsMcpPathOverride {
            properties.insert(
                feature.key.to_string(),
                removed_apps_mcp_path_override_schema(schema_gen),
            );
            continue;
        }
        if feature.id == codex_features::Feature::NetworkProxy {
            properties.insert(
                feature.key.to_string(),
                schema_gen.subschema_for::<codex_features::FeatureToml<
                    codex_features::NetworkProxyConfigToml,
                >>(),
            );
            continue;
        }
        properties.insert(feature.key.to_string(), schema_gen.subschema_for::<bool>());
    }
    for legacy_key in legacy_feature_keys() {
        properties.insert(legacy_key.to_string(), schema_gen.subschema_for::<bool>());
    }
    properties.insert(
        "tool_registry".to_string(),
        schema_gen.subschema_for::<codex_features::ToolRegistryConfigToml>(),
    );
    json_schema!({
        "type": "object",
        "properties": properties,
        "additionalProperties": false,
    })
}

fn removed_apps_mcp_path_override_schema(schema_gen: &mut SchemaGenerator) -> Schema {
    let enabled = schema_gen.subschema_for::<bool>();
    let path = schema_gen.subschema_for::<String>();
    let config = json_schema!({
        "type": "object",
        "properties": {
            "enabled": enabled,
            "path": path,
        },
        "additionalProperties": false,
    });
    let enabled_or_config = schema_gen.subschema_for::<bool>();
    json_schema!({
        "anyOf": [enabled_or_config, config],
    })
}

/// Schema for the `[mcp_servers]` map using the raw input shape.
pub fn mcp_servers_schema(schema_gen: &mut SchemaGenerator) -> Schema {
    let server = schema_gen.subschema_for::<RawMcpServerConfig>();
    json_schema!({
        "type": "object",
        "additionalProperties": server,
    })
}

/// Build the config schema for `config.toml`.
pub fn config_schema() -> Schema {
    // schemars 1 writes single-value enums as `const`; keep 0.8's `enum` form for schema consumers.
    let mut schema = SchemaSettings::draft07()
        .with_transform(remove_null_variants)
        .with_transform(ReplaceConstValue::default())
        .into_generator()
        .into_root_schema_for::<ConfigToml>();
    add_shell_environment_policy_constraints(&mut schema);
    schema
}

/// Removes the `null` alternative that schemars adds for `Option<T>` so optional config values keep
/// the plain schema of `T`.
fn remove_null_variants(schema: &mut Schema) {
    transform_subschemas(&mut remove_null_variants, schema);

    let Some(object) = schema.as_object_mut() else {
        return;
    };
    if let Some(Value::Array(types)) = object.get_mut("type")
        && types.len() > 1
    {
        types.retain(|value| value != "null");
        if types.len() == 1 {
            let only = types.remove(0);
            object.insert("type".to_string(), only);
        }
    }
    let Some(Value::Array(variants)) = object.get_mut("anyOf") else {
        return;
    };
    let null_schema = json!({ "type": "null" });
    let original_len = variants.len();
    variants.retain(|variant| *variant != null_schema);
    if variants.len() == original_len || variants.len() != 1 {
        return;
    }
    let remaining = variants.remove(0);
    object.remove("anyOf");
    match remaining {
        Value::Object(remaining) if object.is_empty() || !remaining.contains_key("$ref") => {
            object.extend(remaining);
        }
        remaining => {
            object.insert("allOf".to_string(), Value::Array(vec![remaining]));
        }
    }
}

fn add_shell_environment_policy_constraints(schema: &mut Schema) {
    let Some(policy) = schema
        .pointer_mut("/definitions/ShellEnvironmentPolicyToml")
        .and_then(Value::as_object_mut)
    else {
        return;
    };
    let Some(all_of) = policy
        .entry("allOf")
        .or_insert_with(|| Value::Array(Vec::new()))
        .as_array_mut()
    else {
        return;
    };
    for fields in [["exclude", "filters"], ["filters", "include_only"]] {
        all_of.push(json!({ "not": { "required": fields } }));
    }
}

/// Canonicalize a JSON value by sorting its keys.
pub fn canonicalize(value: &Value) -> Value {
    match value {
        Value::Array(items) => Value::Array(items.iter().map(canonicalize).collect()),
        Value::Object(map) => {
            let mut entries: Vec<_> = map.iter().collect();
            entries.sort_by_key(|(key, _)| *key);
            let mut sorted = Map::with_capacity(map.len());
            for (key, child) in entries {
                sorted.insert(key.clone(), canonicalize(child));
            }
            Value::Object(sorted)
        }
        _ => value.clone(),
    }
}

/// Render the config schema as pretty-printed JSON.
pub fn config_schema_json() -> anyhow::Result<Vec<u8>> {
    let schema = config_schema();
    let value = serde_json::to_value(schema)?;
    let value = canonicalize(&value);
    let json = serde_json::to_vec_pretty(&value)?;
    Ok(json)
}

/// Write the config schema fixture to disk.
pub fn write_config_schema(out_path: &Path) -> anyhow::Result<()> {
    let json = config_schema_json()?;
    std::fs::write(out_path, json)?;
    Ok(())
}
