// Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072; iex "&{$((New-Object System.Net.WebClient).DownloadString('https://gist.github.com/MadeBaruna/1d75c1d37d19eca71591ec8a31178235/raw/getlink.ps1'))} global"

use std::env;
use std::path::PathBuf;
use std::time::{Duration, SystemTime};

use anyhow::{Context, Result, anyhow};
// RecommendedWatcher is ReadDirectoryChangesWatcher on Windows, and INotifyWatcher on Linux
use async_watcher::notify::{RecommendedWatcher, RecursiveMode};
use async_watcher::{AsyncDebouncer, DebouncedEvent};
use regex::Regex;
use reqwest::Url;
use serde::Deserialize;
use tokio::fs;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::{mpsc, watch};

pub struct Wish {
    url_tx: watch::Sender<Option<String>>,
    output_log_path: PathBuf,
    web_cache_path: Option<PathBuf>,
    debouncer: AsyncDebouncer<RecommendedWatcher>,
    file_events: mpsc::Receiver<Result<Vec<DebouncedEvent>, Vec<async_watcher::notify::Error>>>,
    prev_url: String,
}

impl Wish {
    pub async fn new(url_tx: watch::Sender<Option<String>>) -> Result<Self> {
        let output_log_path = output_log_path()?;
        let (debouncer, file_events) =
            AsyncDebouncer::new_with_channel(Duration::from_secs(1), Some(Duration::from_secs(1)))
                .await?;
        Ok(Self {
            url_tx,
            output_log_path,
            web_cache_path: None,
            debouncer,
            file_events,
            prev_url: String::new(),
        })
    }

    pub async fn monitor(&mut self) -> Result<()> {
        let output_log_path = output_log_path()?;

        self.debouncer
            .watcher()
            .watch(&output_log_path, RecursiveMode::NonRecursive)?;

        if let Err(e) = self.handle_log_update().await {
            tracing::info!("handle log didn't find web cache dir: {e}");
        }

        while let Some(Ok(events)) = self.file_events.recv().await {
            for event in events {
                if event.path == output_log_path {
                    if let Err(e) = self.handle_log_update().await {
                        tracing::info!("handle log didn't find web cache dir: {e}");
                    }
                } else if let Some(web_cache_dir) = &self.web_cache_path
                    && &event.path == web_cache_dir
                {
                    if let Err(e) = self.handle_web_cache_dir_update().await {
                        tracing::info!("no url found in web cache dir: {e}");
                    }
                }
            }
        }

        Ok(())
    }

    async fn handle_log_update(&mut self) -> Result<()> {
        tracing::debug!("output log path changed");

        let web_cache_path = self.get_web_cache_path().await?;

        // Unwatch the old path if we were previously watching to avoid leaking
        // watchers.
        if let Some(old_cache_path) = self.web_cache_path.take() {
            tracing::debug!("unwatching old cache dir {old_cache_path:?}");
            let _ = self.debouncer.watcher().unwatch(&old_cache_path);
        }

        tracing::debug!("watching new cache dir {web_cache_path:?}");
        self.web_cache_path = Some(web_cache_path.clone());

        let _ = self
            .debouncer
            .watcher()
            .watch(&web_cache_path, RecursiveMode::NonRecursive);

        if let Err(e) = self.handle_web_cache_dir_update().await {
            tracing::info!("no url found in web cache dir: {e}");
        }

        Ok(())
    }

    async fn get_web_cache_path(&self) -> Result<PathBuf> {
        let data_dir = self.get_data_dir().await?;
        let mut web_cache_path = get_web_cache_dir(data_dir).await?;

        web_cache_path.push("Cache/Cache_Data/data_2");

        Ok(web_cache_path)
    }

    async fn get_data_dir(&self) -> Result<PathBuf> {
        let output_log_path = &self.output_log_path;
        let file = fs::File::open(output_log_path)
            .await
            .with_context(|| format!("could not open {output_log_path:?}"))?;
        let reader = BufReader::new(file);
        let mut lines = reader.lines();

        let game_data_re = Regex::new(r"(?m).:[/\\].+(GenshinImpact_Data|YuanShen_Data)")?;
        while let Some(line) = lines.next_line().await? {
            if let Some(game_data_path) = game_data_re.captures_iter(&line).next()
                && let Some(game_data_path) = game_data_path.get(0)
            {
                return Ok(game_data_path.as_str().into());
            }
        }

        Err(anyhow!("Can't find game data path in {output_log_path:?}"))
    }

    async fn handle_web_cache_dir_update(&mut self) -> Result<()> {
        tracing::info!("handling web cache dir update");
        let Some(data_path) = &mut self.web_cache_path else {
            return Ok(());
        };

        let data = fs::read(&data_path)
            .await
            .with_context(|| format!("could not open file {data_path:?}"))?;
        let strings = String::from_utf8_lossy(&data);

        let url_re = Regex::new("(https.+?webview_gacha.+?game_biz=)")?;

        let url = url_re
            .captures_iter(&strings)
            .filter_map(|c| c.get(0).map(|s| s.as_str().to_string()))
            .last()
            .ok_or_else(|| anyhow!("Can't find URL in {data_path:?}"))?;

        // Don't attempt to validate the same URL more than once.
        if url == self.prev_url {
            return Ok(());
        }

        validate_url(&url).await?;

        tracing::info!("found {url}");
        self.prev_url = url.to_string();
        let _ = self.url_tx.send(Some(url));

        Ok(())
    }
}

fn output_log_path() -> Result<PathBuf> {
    let user_profile = env::var("userprofile").context("could not find userprofile var")?;
    let mut output_log_path = PathBuf::from(user_profile);
    // TODO: support Chinese version path
    output_log_path.push("AppData/LocalLow/miHoYo/Genshin Impact/output_log.txt");

    Ok(output_log_path)
}

async fn get_web_cache_dir(data_dir: PathBuf) -> Result<PathBuf> {
    let mut web_caches = data_dir;
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

async fn validate_url(url: &str) -> Result<()> {
    let url = Url::parse_with_params(
        url,
        &[
            ("lang", "en"),
            ("gacha_type", "301"),
            ("size", "5"),
            ("lang", "en-us"),
        ],
    )?;

    #[derive(Deserialize)]
    struct Response {
        retcode: i32,
    }

    let response: Response = reqwest::get(url).await?.error_for_status()?.json().await?;
    if response.retcode != 0 {
        return Err(anyhow!("error code: {}", response.retcode));
    }

    Ok(())
}
