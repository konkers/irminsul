use std::fs::File;
use std::path::Path;
use std::{env, io};

use flate2::Compression;
use flate2::write::GzEncoder;

#[cfg(not(target_os = "android"))]
#[tokio::main]
async fn main() -> io::Result<()> {
    // Download new game data and save it in a location to be included by the source.
    let out_dir = env::var_os("OUT_DIR").unwrap();
    let cache_path = Path::new(&out_dir).join("game_data.json");
    let out_path = Path::new(&out_dir).join("game_data.gz");

    let mut db = anime_game_data::AnimeGameData::new_with_cache(&cache_path);
    match db.needs_update().await {
        Ok(true) => match db.update().await {
            Ok(_) => {
                eprintln!("Game data updated successfully");
            }
            Err(e) => {
                eprintln!("Failed to update game data: {}", e);
                eprintln!("Using cached data instead");
            }
        },
        Ok(false) => {
            eprintln!("Game data is up to date");
        }
        Err(e) => {
            eprintln!("Failed to check for updates: {}", e);
            eprintln!("Using cached data instead");
        }
    }

    // Always write game_data.gz from current data so include_bytes! can find it.
    if db.has_data() {
        let f = File::create(&out_path).unwrap();
        let writer = GzEncoder::new(f, Compression::best());
        db.save_to_writer(writer).unwrap();
    } else {
        eprintln!("WARNING: No game data available. Build may fail at runtime.");
    }

    // Add icon to windows binary.
    #[cfg(windows)]
    {
        winresource::WindowsResource::new()
            .set_icon("assets/icon.ico")
            .compile()?;
    }

    Ok(())
}

#[cfg(target_os = "android")]
fn main() -> io::Result<()> {
    // On Android, no network access during build — just load from cache.
    let out_dir = env::var_os("OUT_DIR").unwrap();
    let cache_path = Path::new(&out_dir).join("game_data.json");
    let out_path = Path::new(&out_dir).join("game_data.gz");

    let db = anime_game_data::AnimeGameData::new_with_cache(&cache_path);

    // Only write if we have valid data
    if db
        .save_to_writer(GzEncoder::new(
            File::create(&out_path).unwrap(),
            Compression::best(),
        ))
        .is_err()
    {
        // No cached data available — check if a pre-existing game_data.gz exists
        // from a previous build (e.g. when the old build.rs downloaded it).
        if !out_path.exists() || out_path.metadata().map(|m| m.len()).unwrap_or(0) < 100 {
            eprintln!("WARNING: No game data available. Build may fail at runtime.");
        }
    }

    println!("cargo:rerun-if-changed={}", cache_path.display());

    Ok(())
}
