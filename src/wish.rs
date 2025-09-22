// Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072; iex "&{$((New-Object System.Net.WebClient).DownloadString('https://gist.github.com/MadeBaruna/1d75c1d37d19eca71591ec8a31178235/raw/getlink.ps1'))} global"

use std::env;
use std::path::PathBuf;
use std::time::SystemTime;

use anyhow::{Context, Result, anyhow};
use regex::Regex;
use tokio::fs;
use tokio::io::{AsyncBufReadExt, BufReader};

async fn get_data_dir() -> Result<String> {
    let user_profile = env::var("userprofile").context("could not find userprofile var")?;
    let mut output_log_path = PathBuf::from(user_profile);
    // TODO: support Chinese version path
    output_log_path.push("AppData/LocalLow/miHoYo/Genshin Impact/output_log.txt");

    let file = fs::File::open(&output_log_path)
        .await
        .with_context(|| format!("could not open {output_log_path:?}"))?;
    let reader = BufReader::new(file);
    let mut lines = reader.lines();

    let game_data_re = Regex::new("(?m).:/.+(GenshinImpact_Data|YuanShen_Data)")?;
    while let Some(line) = lines.next_line().await? {
        if let Some(game_data_path) = game_data_re.captures_iter(&line).next()
            && let Some(game_data_path) = game_data_path.get(0)
        {
            return Ok(game_data_path.as_str().into());
        }
    }

    Err(anyhow!("Can't find game data path in {output_log_path:?}"))
}

async fn get_web_cache_dir(data_dir: &str) -> Result<PathBuf> {
    let mut web_caches = PathBuf::from(data_dir);
    web_caches.push("webCaches");
    let mut dir = fs::read_dir(&web_caches)
        .await
        .with_context(|| format!("could not open directory {web_caches:?}"))?;
    let mut latest_dir = (SystemTime::UNIX_EPOCH, None);
    while let Some(entry) = dir.next_entry().await? {
        let metadata = entry.metadata().await?;
        if !metadata.is_dir() {
            continue;
        }
        let modified = metadata.modified()?;
        if modified > latest_dir.0 {
            latest_dir = (modified, Some(entry.path()))
        }
    }

    latest_dir
        .1
        .ok_or_else(|| anyhow!("Unable to find directory in {web_caches:?}"))
}

async fn find_wish_url(cache_dir: PathBuf) -> Result<String> {
    let mut data_path = cache_dir;
    data_path.push("Cache/Cache_Data/data_2");
    let data = fs::read(&data_path)
        .await
        .with_context(|| format!("could not open file {data_path:?}"))?;
    let strings = String::from_utf8_lossy(&data);

    let url_re = Regex::new("(https.+?webview_gacha.+?game_biz=)")?;

    url_re
        .captures_iter(&strings)
        .filter_map(|c| c.get(0).map(|s| s.as_str().to_string()))
        .last()
        .ok_or_else(|| anyhow!("Can't find URL in {data_path:?}"))
}

pub async fn get_url() -> Result<String> {
    let data_dir = get_data_dir().await?;
    let web_cache_dir = get_web_cache_dir(&data_dir).await?;
    find_wish_url(web_cache_dir).await
}
