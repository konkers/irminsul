//! JNI bridge for Android.
//!
//! Only compiled when targeting `target_os = "android"`.
//! Provides a thin JNI layer that delegates to the platform-agnostic
//! `GameSniffer`, `PlayerData`, and export functions.

#![cfg(target_os = "android")]

use std::collections::HashMap;
use std::sync::Mutex;

use anyhow::Result;
use auto_artifactarium::{
    GamePacket, GameSniffer, matches_achievement_packet, matches_avatar_packet, matches_item_packet,
};
use base64::Engine;
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jint, jstring};

use crate::AchievementFormat;
use crate::player_data::{ExportSettings, PlayerData};

// ---------------------------------------------------------------------------
// Global state (thread-safe via Mutex)
// ---------------------------------------------------------------------------

struct SnifferState {
    sniffer: GameSniffer,
    player_data: PlayerData,
    has_items: bool,
    has_avatars: bool,
    has_achievements: bool,
}

static GLOBAL_STATE: Mutex<Option<SnifferState>> = Mutex::new(None);
static JAVA_VM: Mutex<Option<jni::JavaVM>> = Mutex::new(None);

const PCAPDROID_TRAILER_SIZE: usize = 32;

// ---------------------------------------------------------------------------
// Logging helper
// ---------------------------------------------------------------------------

fn log_to_android(level: &str, message: &str) {
    if let Some(vm) = JAVA_VM.lock().unwrap().as_ref() {
        if let Ok(mut env) = vm.attach_current_thread() {
            let msg_str = format!("[{}] {}", level, message);
            if let Ok(msg) = env.new_string(&msg_str) {
                if let Ok(class) = env.find_class("com/esc/irminsul/NativeLib") {
                    let _ = env.call_static_method(
                        class,
                        "log",
                        "(Ljava/lang/String;)V",
                        &[(&msg).into()],
                    );
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Key loading
// ---------------------------------------------------------------------------

fn load_keys() -> Result<HashMap<u16, Vec<u8>>> {
    let keys_json: HashMap<u16, String> =
        serde_json::from_slice(include_bytes!("../keys/gi.json"))?;
    keys_json
        .into_iter()
        .map(|(k, v)| {
            let decoded = base64::engine::general_purpose::STANDARD
                .decode(&v)
                .map_err(|e| anyhow::anyhow!("Failed to decode base64 key {}: {}", k, e))?;
            Ok((k, decoded))
        })
        .collect()
}

// ---------------------------------------------------------------------------
// Packet preparation (same logic as irminsul-android)
// ---------------------------------------------------------------------------

fn extract_and_prepare_packet(data: &[u8]) -> Option<Vec<u8>> {
    if data.is_empty() {
        return None;
    }

    let mut packet_data = data;

    // Strip PCAPdroid trailer
    if packet_data.len() >= PCAPDROID_TRAILER_SIZE {
        let trailer_start = packet_data.len() - PCAPDROID_TRAILER_SIZE;
        let trailer = &packet_data[trailer_start..];
        if trailer[0] == 0x01 && trailer[1] == 0x00 {
            packet_data = &packet_data[..trailer_start];
        }
    }

    if packet_data.is_empty() {
        return None;
    }

    let first_byte = packet_data[0];

    // IPv4 — prepend fake Ethernet header with EtherType 0x0800
    if (first_byte >> 4) == 0x04 {
        let mut fake_eth = Vec::with_capacity(14 + packet_data.len());
        fake_eth.extend_from_slice(&[0x00; 6]); // dst MAC
        fake_eth.extend_from_slice(&[0x00; 6]); // src MAC
        fake_eth.extend_from_slice(&[0x08, 0x00]); // EtherType: IPv4
        fake_eth.extend_from_slice(packet_data);
        Some(fake_eth)
    }
    // IPv6 — prepend fake Ethernet header with EtherType 0x86DD
    else if (first_byte & 0xF0) == 0x60 {
        let mut fake_eth = Vec::with_capacity(14 + packet_data.len());
        fake_eth.extend_from_slice(&[0x00; 6]);
        fake_eth.extend_from_slice(&[0x00; 6]);
        fake_eth.extend_from_slice(&[0x86, 0xDD]); // EtherType: IPv6
        fake_eth.extend_from_slice(packet_data);
        Some(fake_eth)
    } else {
        // Already has link-layer header
        Some(packet_data.to_vec())
    }
}

// ---------------------------------------------------------------------------
// Database initialization
// ---------------------------------------------------------------------------

fn init_player_data() -> Result<PlayerData> {
    use anime_game_data::AnimeGameData;
    use flate2::read::GzDecoder;

    static DATABASE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/game_data.gz"));
    let reader = GzDecoder::new(DATABASE);
    let db = AnimeGameData::new_from_reader(reader)?;
    Ok(PlayerData::new(db))
}

// ---------------------------------------------------------------------------
// ExportSettings parsing from JSON
// ---------------------------------------------------------------------------

fn parse_export_settings(json: &str) -> ExportSettings {
    match serde_json::from_str(json) {
        Ok(settings) => settings,
        Err(e) => {
            log_to_android(
                "WARN",
                &format!("Failed to parse export settings, using defaults: {}", e),
            );
            ExportSettings::default()
        }
    }
}

// ---------------------------------------------------------------------------
// JNI functions
// ---------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_NativeLib_nativeInitLogging(
    env: JNIEnv,
    _class: JClass,
) {
    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    *JAVA_VM.lock().unwrap() = Some(vm);
    log_to_android("INFO", "Native library initialized");
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_NativeLib_nativeCreateSniffer(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let mut state = GLOBAL_STATE.lock().unwrap();

    let keys = match load_keys() {
        Ok(k) => k,
        Err(e) => {
            log_to_android("ERROR", &format!("Failed to load keys: {}", e));
            return -1;
        }
    };

    let sniffer = GameSniffer::new().set_initial_keys(keys);

    let player_data = match init_player_data() {
        Ok(pd) => pd,
        Err(e) => {
            log_to_android("ERROR", &format!("Failed to init player data: {}", e));
            return -2;
        }
    };

    *state = Some(SnifferState {
        sniffer,
        player_data,
        has_items: false,
        has_avatars: false,
        has_achievements: false,
    });

    log_to_android("INFO", "Sniffer created successfully");
    0
}

/// Process a raw IP packet from the VPN capture.
///
/// Returns a JSON string with current data status:
/// ```json
/// {"has_items":true,"has_avatars":true,"has_achievements":true,
///  "item_count":3657,"avatar_count":90,"achievement_count":1712,
///  "artifact_count":1200,"weapon_count":150,"material_count":2307}
/// ```
///
/// When all three data types are collected, also notifies Kotlin via
/// `NativeLib.onDataComplete(artifactCount, weaponCount, materialCount,
///                            characterCount, achievementCount)`.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_NativeLib_nativeProcessPacket(
    mut env: JNIEnv,
    _class: JClass,
    packet_data: JByteArray,
) -> jstring {
    let bytes = match env.convert_byte_array(&packet_data) {
        Ok(data) => data,
        Err(_) => return std::ptr::null_mut(),
    };

    let prepared_packet = match extract_and_prepare_packet(&bytes) {
        Some(p) => p,
        None => return std::ptr::null_mut(),
    };

    let mut state_guard = GLOBAL_STATE.lock().unwrap();
    let state = match state_guard.as_mut() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };

    let Some(GamePacket::Commands(commands)) = state.sniffer.receive_packet(prepared_packet) else {
        return std::ptr::null_mut();
    };

    for command in &commands {
        if let Some(items) = matches_item_packet(command) {
            log_to_android(
                "INFO",
                &format!("Matched item packet: {} items", items.len()),
            );
            state.player_data.process_items(&items);
            state.has_items = true;
        } else if let Some(avatars) = matches_avatar_packet(command) {
            log_to_android(
                "INFO",
                &format!("Matched avatar packet: {} avatars", avatars.len()),
            );
            state.player_data.process_characters(&avatars);
            state.has_avatars = true;
        } else if let Some(achievements) = matches_achievement_packet(command) {
            log_to_android(
                "INFO",
                &format!(
                    "Matched achievement packet: {} achievements",
                    achievements.len()
                ),
            );
            state.player_data.process_achievements(&achievements);
            state.has_achievements = true;
        }
    }

    // Build status JSON with granular counts
    let artifact_count = state.player_data.artifact_count();
    let weapon_count = state.player_data.weapon_count();
    let material_count = state.player_data.material_count();
    let character_count = state.player_data.character_count();
    let achievement_count = state.player_data.achievement_count();

    let status_json = serde_json::json!({
        "has_items": state.has_items,
        "has_avatars": state.has_avatars,
        "has_achievements": state.has_achievements,
        "artifact_count": artifact_count,
        "weapon_count": weapon_count,
        "material_count": material_count,
        "character_count": character_count,
        "achievement_count": achievement_count,
    });

    let status_str = status_json.to_string();

    // Check if all data is collected — notify Kotlin
    let all_complete = state.has_items && state.has_avatars && state.has_achievements;

    // Release the lock before calling back into Java
    drop(state_guard);

    if all_complete {
        notify_data_complete(
            &mut env,
            artifact_count,
            weapon_count,
            material_count,
            character_count,
            achievement_count,
        );
    }

    match env.new_string(&status_str) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Export GOOD v3 JSON with optional settings.
/// If `settings_json` is null, uses default settings.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_NativeLib_nativeExportGood(
    mut env: JNIEnv,
    _class: JClass,
    settings_json: jstring,
) -> jstring {
    let state_guard = GLOBAL_STATE.lock().unwrap();
    let state = match state_guard.as_ref() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };

    let settings = if settings_json.is_null() {
        ExportSettings::default()
    } else {
        let jstr =
            jni::objects::JString::from(unsafe { jni::objects::JObject::from_raw(settings_json) });
        match env.get_string(&jstr) {
            Ok(java_str) => {
                let json_str: String = java_str.into();
                parse_export_settings(&json_str)
            }
            Err(_) => ExportSettings::default(),
        }
    };

    let json = match state.player_data.export_genshin_optimizer(&settings) {
        Ok(j) => j,
        Err(e) => {
            log_to_android("ERROR", &format!("Export GOOD failed: {}", e));
            return std::ptr::null_mut();
        }
    };

    match env.new_string(&json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Export achievements in the specified format.
/// `format_code`: 0 = UIAF, 1 = Seelie, 2 = CSV
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_NativeLib_nativeExportAchievements(
    env: JNIEnv,
    _class: JClass,
    format_code: jint,
) -> jstring {
    let state_guard = GLOBAL_STATE.lock().unwrap();
    let state = match state_guard.as_ref() {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };

    let format = match format_code {
        0 => AchievementFormat::Uiaf,
        1 => AchievementFormat::Seelie,
        2 => AchievementFormat::Csv,
        _ => {
            log_to_android(
                "ERROR",
                &format!("Unknown achievement format: {}", format_code),
            );
            return std::ptr::null_mut();
        }
    };

    let json = match state.player_data.export_achievements(format) {
        Ok(j) => j,
        Err(e) => {
            log_to_android("ERROR", &format!("Export achievements failed: {}", e));
            return std::ptr::null_mut();
        }
    };

    match env.new_string(&json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_esc_irminsul_NativeLib_nativeDestroySniffer(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut state = GLOBAL_STATE.lock().unwrap();
    *state = None;
    log_to_android("INFO", "Sniffer destroyed");
}

// ---------------------------------------------------------------------------
// Callback: notify Kotlin that all data is collected
// ---------------------------------------------------------------------------

fn notify_data_complete(
    env: &mut JNIEnv,
    artifact_count: usize,
    weapon_count: usize,
    material_count: usize,
    character_count: usize,
    achievement_count: usize,
) {
    if let Ok(class) = env.find_class("com/esc/irminsul/NativeLib") {
        let _ = env.call_static_method(
            class,
            "onDataComplete",
            "(IIIII)V",
            &[
                (artifact_count as jint).into(),
                (weapon_count as jint).into(),
                (material_count as jint).into(),
                (character_count as jint).into(),
                (achievement_count as jint).into(),
            ],
        );
    }
}
