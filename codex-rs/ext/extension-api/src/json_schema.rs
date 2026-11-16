use serde_json::Value;
use serde_json::json;

/// Removes the `null` alternative that schemars adds for `Option<T>` from a generated JSON Schema.
///
/// Tool input schemas describe optional fields by omitting them, so they keep the plain schema of
/// `T` instead of `T | null`.
pub fn remove_null_variants(schema: &mut Value) {
    match schema {
        Value::Array(items) => items.iter_mut().for_each(remove_null_variants),
        Value::Object(object) => {
            object.values_mut().for_each(remove_null_variants);
            remove_null_variants_from_object(object);
        }
        _ => {}
    }
}

fn remove_null_variants_from_object(object: &mut serde_json::Map<String, Value>) {
    if let Some(Value::Array(types)) = object.get_mut("type")
        && types.len() > 1
        && types.iter().any(|value| value == "null")
    {
        types.retain(|value| value != "null");
        if types.len() == 1 {
            let only = types.remove(0);
            object.insert("type".to_string(), only);
        }
        if let Some(Value::Array(values)) = object.get_mut("enum") {
            values.retain(|value| !value.is_null());
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
