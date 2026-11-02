use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::BTreeMap;
use wasm_bindgen::prelude::*;

mod router;

#[wasm_bindgen]
pub fn route(input: &str) -> Result<String, JsValue> {
    router::route_impl(input).map_err(|e| JsValue::from_str(&e))
}

#[derive(Deserialize)]
struct Request {
    elements: Vec<Value>,
    baseline: Vec<Value>,
    positions: BTreeMap<String, Position>,
    conflicts: Vec<Value>,
    #[serde(default, rename = "scopeKey")]
    scope_key: String,
}
#[derive(Clone, Serialize, Deserialize)]
struct Position {
    x: i32,
    y: i32,
}

// Canonical decimals never pass through f64, including values beyond JSON number precision.
fn decimal(input: &str) -> Option<String> {
    let (negative, input) = match input.strip_prefix('-') {
        Some(s) => (true, s),
        None => (false, input.strip_prefix('+').unwrap_or(input)),
    };
    let parts: Vec<&str> = input.split(['e', 'E']).collect();
    if parts.len() > 2 {
        return None;
    }
    let exponent: i64 = if parts.len() == 2 {
        parts[1].parse().ok()?
    } else {
        0
    };
    let mut digits = String::new();
    let mut fraction = 0i64;
    let mut dot = false;
    for c in parts[0].chars() {
        if c == '.' && !dot {
            dot = true;
        } else if c.is_ascii_digit() {
            digits.push(c);
            if dot {
                fraction += 1;
            }
        } else {
            return None;
        }
    }
    if digits.is_empty() {
        return None;
    }
    let mut digits = digits.trim_start_matches('0').to_string();
    if digits.is_empty() {
        return Some("0".into());
    }
    let mut power = exponent.checked_sub(fraction)?;
    while digits.ends_with('0') {
        digits.pop();
        power = power.checked_add(1)?;
    }
    Some(format!(
        "{}{}e{}",
        if negative { "-" } else { "" },
        digits,
        power
    ))
}

fn same(a: &Value, b: &Value) -> bool {
    if a.is_null() || b.is_null() {
        return a == b;
    }
    if a["typeName"] != b["typeName"] {
        return false;
    }
    let (Some(a), Some(b)) = (
        a.get("scalar").filter(|v| !v.is_null()),
        b.get("scalar").filter(|v| !v.is_null()),
    ) else {
        return false;
    };
    if a["kind"] != b["kind"] {
        return false;
    }
    let (Some(x), Some(y)) = (a["canonical"].as_str(), b["canonical"].as_str()) else {
        return false;
    };
    match a["kind"].as_str() {
        Some("INTEGER" | "REAL") => decimal(x).is_some_and(|v| Some(v) == decimal(y)),
        Some("STRING" | "BOOLEAN") => x == y,
        _ => false,
    }
}

fn project_impl(input: &str) -> Result<String, String> {
    let mut r: Request = serde_json::from_str(input).map_err(|e| e.to_string())?;
    let mut cards = vec![];
    for element in &r.elements {
        let id = element["id"].as_str().ok_or("Missing element ID")?;
        let prefix = format!("{}|", r.scope_key);
        let key = format!("{prefix}{id}");
        if !r.positions.contains_key(&key) {
            let slot = r
                .positions
                .keys()
                .filter(|k| k.starts_with(&prefix))
                .count() as i32;
            r.positions.insert(
                key.clone(),
                Position {
                    x: 120 + (slot % 3) * 360,
                    y: 120 + (slot / 3) * 360,
                },
            );
        }
        let old = r.baseline.iter().find(|v| v["id"] == element["id"]);
        let mut properties = vec![];
        for attribute in element["attributes"]
            .as_array()
            .ok_or("Missing attributes")?
        {
            let previous = old
                .and_then(|e| e["attributes"].as_array())
                .and_then(|a| a.iter().find(|v| v["id"] == attribute["id"]));
            let changed = attribute["editable"].as_bool().unwrap_or(false)
                && previous.is_none_or(|p| !same(&p["value"], &attribute["value"]));
            let conflict = r.conflicts.iter().find(|c| {
                c["target"]["attributeId"] == attribute["id"]
                    || attribute["definitionAttributeId"]
                        .as_str()
                        .is_some_and(|id| c["target"]["attributeId"] == id)
            });
            properties.push(json!({ "attribute": attribute, "changed": changed, "conflictId": conflict.map(|c| &c["id"]) }));
        }
        let conflicted = properties.iter().any(|p| !p["conflictId"].is_null());
        cards.push(
            json!({ "element": element, "position": r.positions[&key], "properties": properties,
            "conflicted": conflicted }),
        );
    }
    Ok(json!({ "cards": cards, "positions": r.positions }).to_string())
}

#[wasm_bindgen]
pub fn project(input: &str) -> Result<String, JsValue> {
    project_impl(input).map_err(|e| JsValue::from_str(&e))
}

#[wasm_bindgen]
pub fn scalar_equal(a: &str, b: &str) -> Result<bool, JsValue> {
    let a: Value = serde_json::from_str(a).map_err(|e| JsValue::from_str(&e.to_string()))?;
    let b: Value = serde_json::from_str(b).map_err(|e| JsValue::from_str(&e.to_string()))?;
    Ok(same(&a, &b))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shared_contract_fixtures_preserve_precision() {
        let fixtures: Value =
            serde_json::from_str(include_str!("../../../contracts/golden/model-cases.json"))
                .unwrap();
        for scalar in fixtures["scalars"].as_array().unwrap() {
            let value = json!({"scalar": scalar});
            assert!(same(&value, &value));
        }
        let a = json!({"scalar":{"kind":"REAL","canonical":"0.123456789012345678901"}});
        let b = json!({"scalar":{"kind":"REAL","canonical":"0.123456789012345678902"}});
        assert!(!same(&a, &b));
        assert_eq!(decimal("12.000"), decimal("1.2e1"));
        assert_eq!(decimal("-0.000"), decimal("0"));
        assert_ne!(decimal("9007199254740993"), decimal("9007199254740992"));
        assert!(!same(
            &fixtures["conflict"]["incoming"]["value"],
            &fixtures["conflict"]["local"]["value"]
        ));
    }
    #[test]
    fn scopes_keep_separate_fixed_slots_and_inherited_conflicts() {
        let input = json!({"scopeKey":"system-a","elements":[{"id":"pump","attributes":[{"id":"usage-speed","definitionAttributeId":"base-speed"}]}],"baseline":[],"positions":{},"conflicts":[{"id":"speed-conflict","target":{"elementId":"definition","attributeId":"base-speed"}}]});
        let first: Value =
            serde_json::from_str(&project_impl(&input.to_string()).unwrap()).unwrap();
        assert_eq!(
            first["cards"][0]["properties"][0]["conflictId"],
            "speed-conflict"
        );
        assert_eq!(first["cards"][0]["conflicted"], true);
        let mut other = input.clone();
        other["scopeKey"] = json!("system-b");
        other["positions"] = first["positions"].clone();
        let second: Value =
            serde_json::from_str(&project_impl(&other.to_string()).unwrap()).unwrap();
        assert_eq!(
            first["cards"][0]["position"],
            second["cards"][0]["position"]
        );
        assert_eq!(second["positions"].as_object().unwrap().len(), 2);
        other["scopeKey"] = json!("system-a");
        other["positions"] = second["positions"].clone();
        let returned: Value =
            serde_json::from_str(&project_impl(&other.to_string()).unwrap()).unwrap();
        assert_eq!(
            first["cards"][0]["position"],
            returned["cards"][0]["position"]
        );
    }
    #[test]
    fn compact_default_slots_are_three_across_and_preserve_stored_positions() {
        let mut input = json!({"elements":[
            {"id":"a","attributes":[]}, {"id":"b","attributes":[]},
            {"id":"c","attributes":[]}, {"id":"d","attributes":[]}
        ],"baseline":[],"positions":{},"conflicts":[]});
        let first: Value =
            serde_json::from_str(&project_impl(&input.to_string()).unwrap()).unwrap();
        for (id, x, y) in [
            ("a", 120, 120),
            ("b", 480, 120),
            ("c", 840, 120),
            ("d", 120, 480),
        ] {
            assert_eq!(first["positions"][format!("|{id}")], json!({"x":x,"y":y}));
        }
        input["positions"] = first["positions"].clone();
        input["positions"]["|b"] = json!({"x":777,"y":888});
        input["elements"]
            .as_array_mut()
            .unwrap()
            .push(json!({"id":"e","attributes":[]}));
        let next: Value = serde_json::from_str(&project_impl(&input.to_string()).unwrap()).unwrap();
        assert_eq!(next["positions"]["|b"], json!({"x":777,"y":888}));
        assert_eq!(next["positions"]["|e"], json!({"x":480,"y":480}));
        for id in ["a", "c", "d"] {
            assert_eq!(
                next["positions"][format!("|{id}")],
                first["positions"][format!("|{id}")]
            );
        }
    }
    #[test]
    fn fixed_coordinates_survive_additions_and_values() {
        let request = json!({"elements":[{"id":"pump-1","attributes":[]}],"baseline":[],"positions":{},"conflicts":[]});
        let first: Value =
            serde_json::from_str(&project_impl(&request.to_string()).unwrap()).unwrap();
        let second = json!({"elements":[{"id":"pump-1","attributes":[{"id":"flow","value":null}]}],
            "baseline":[],"positions":first["positions"],"conflicts":[]});
        let second: Value =
            serde_json::from_str(&project_impl(&second.to_string()).unwrap()).unwrap();
        assert_eq!(
            first["cards"][0]["position"],
            second["cards"][0]["position"]
        );
    }
}
