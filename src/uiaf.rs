use std::collections::HashMap;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct UiafInfo {
    pub export_app: String,
    pub export_app_version: String,
    pub uiaf_version: String,
    pub export_timestamp: i64,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct UiafAchievement {
    pub id: u32,
    pub current: u32,
    pub status: u32,
    pub timestamp: u32,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct UiafRoot {
    pub info: UiafInfo,
    pub list: Vec<UiafAchievement>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct SeelieAchievement {
    pub done: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct SeelieRoot {
    pub achievements: HashMap<u32, SeelieAchievement>,
}
