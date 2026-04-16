use std::fs::File;
use std::path::Path;
use std::{env, io};

use flate2::Compression;
use flate2::write::GzEncoder;
use winresource::WindowsResource;

#[tokio::main]
async fn main() -> io::Result<()> {
    // Download new game data and save it in a location to be included by the source.
    let out_dir = env::var_os("OUT_DIR").unwrap();
    let cache_path = Path::new(&out_dir).join("game_data.json");

    let mut db = anime_game_data::AnimeGameData::new_with_cache(&cache_path);
    if db.needs_update().await.unwrap() {
        db.update().await.unwrap();
        let out_path = Path::new(&out_dir).join("game_data.gz");
        let f = File::create(out_path).unwrap();
        let writer = GzEncoder::new(f, Compression::best());
        db.save_to_writer(writer).unwrap();
    }

    // Add icon to windows binary.
    if env::var_os("CARGO_CFG_WINDOWS").is_some() {
        WindowsResource::new()
            .set_icon("assets/icon.ico")
            .compile()?;
    }
    Ok(())
}
