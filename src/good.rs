use std::collections::HashMap;

use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Substat {
    pub key: String,
    pub value: f32,
    pub initial_value: f32,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Artifact {
    pub set_key: String,
    pub slot_key: String,
    pub level: u32,
    pub rarity: u32,
    pub main_stat_key: String,
    pub location: String,
    pub lock: bool,
    pub substats: Vec<Substat>,

    // GOOD v3 fields.
    pub total_rolls: u32,
    pub astral_mark: bool,
    pub elixir_crafted: bool,
    pub unactivated_substats: Vec<Substat>,

    // Not part of GOOD: the game's roll IDs, only when enabled in the export settings.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub append_prop_id_list: Option<Vec<u32>>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Weapon {
    pub key: String,
    pub level: u32,
    pub ascension: u32,
    pub refinement: u32,
    pub location: String,
    pub lock: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct TalentLevel {
    pub auto: u32,
    pub skill: u32,
    pub burst: u32,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Character {
    pub key: String,
    pub level: u32,
    pub constellation: u32,
    pub ascension: u32,
    pub talent: TalentLevel,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Good {
    pub format: String,
    pub version: u32,
    pub source: String,
    pub characters: Vec<Character>,
    pub artifacts: Vec<Artifact>,
    pub weapons: Vec<Weapon>,
    pub materials: HashMap<String, u32>,
}

/// GOOD key for the Traveler before their element is appended.
pub const TRAVELER_KEY: &str = "Traveler";

pub fn to_good_key(value: &str) -> String {
    let mut result = String::new();
    let mut capitalize_next = true;

    for c in value.chars() {
        if c.is_ascii_alphanumeric() {
            if capitalize_next {
                result.extend(c.to_uppercase());
                capitalize_next = false;
            } else {
                result.push(c);
            }
        } else if c == ' ' {
            capitalize_next = true;
        }
    }

    result
}

pub fn fake_uninitialized_4th_line(artifacts: Vec<Artifact>) -> Vec<Artifact> {
    artifacts
        .into_iter()
        .map(|mut arti| {
            if arti.unactivated_substats.is_empty() || arti.rarity != 5 {
                return arti;
            }
            arti.substats.push(arti.unactivated_substats.pop().unwrap());
            Artifact {
                level: 4,
                total_rolls: 4,
                ..arti
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn artifact(append_prop_id_list: Option<Vec<u32>>) -> Artifact {
        Artifact {
            set_key: "ScarletProof".to_string(),
            slot_key: "plume".to_string(),
            level: 0,
            rarity: 5,
            main_stat_key: "atk".to_string(),
            location: String::new(),
            lock: false,
            substats: Vec::new(),
            total_rolls: 0,
            astral_mark: false,
            elixir_crafted: false,
            unactivated_substats: Vec::new(),
            append_prop_id_list,
        }
    }

    #[test]
    fn roll_ids_are_exported_only_when_included() {
        let plain = serde_json::to_value(artifact(None)).unwrap();
        assert!(plain.get("appendPropIdList").is_none());

        let ids = vec![501093, 501092, 501093];
        let raw = serde_json::to_value(artifact(Some(ids.clone()))).unwrap();
        assert_eq!(raw["appendPropIdList"], serde_json::json!(ids));
    }
}
