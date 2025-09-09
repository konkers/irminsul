use std::io::Write;
use std::thread;

use anyhow::{Context, Result, anyhow};
use futures_util::StreamExt;
use reqwest::header;
use self_update::update::Release;
use serde::Deserialize;
use tokio::sync::{mpsc, watch};

use crate::{AppState, Message, State};

pub fn check_for_new_version() -> Result<Option<Release>> {
    // This needs to be outside of an async context otherwise it panics.
    let releases = thread::spawn(move || -> Result<Vec<Release>> {
        let releases = self_update::backends::github::ReleaseList::configure()
            .repo_owner("konkers")
            .repo_name("irminsul")
            .build()?
            .fetch()?;
        Ok(releases)
    })
    .join();
    let releases = releases
        .map_err(|_| anyhow!("error joining update thread"))?
        .context("error fetching releases")?;

    // Assume the first release is the latest.
    let release = releases[0].clone();
    if release.version == self_update::cargo_crate_version!() {
        tracing::info!(
            "{} is current, continuing with app startup",
            release.version
        );
        return Ok(None);
    }

    tracing::info!(
        "Found update {} -> {}",
        self_update::cargo_crate_version!(),
        release.version
    );

    Ok(Some(release))
}

async fn download_new_version_and_replace_current(release: Release) -> Result<()> {
    let asset = release.asset_for("", None).unwrap();
    tracing::info!("asset: {asset:#?}");

    let tmp_dir = tempfile::Builder::new()
        .prefix("self_update")
        .tempdir_in(::std::env::current_dir()?)?;
    let tmp_exe_path = tmp_dir.path().join(&asset.name);
    let mut tmp_exe = ::std::fs::File::create(&tmp_exe_path)?;

    let client = reqwest::Client::builder().gzip(true).build()?;

    #[derive(Deserialize)]
    struct DownloadMetadata {
        browser_download_url: String,
    }

    tracing::info!("fetching artifact info {}", asset.download_url);
    let metadata: DownloadMetadata = client
        .get(&asset.download_url)
        .header(header::USER_AGENT, "rust-reqwest/self-update")
        .send()
        .await
        .context("Failed to artifact")?
        .json()
        .await?;

    tracing::info!(
        "downloading {} to {tmp_exe_path:?}",
        metadata.browser_download_url
    );
    let mut stream = client
        .get(metadata.browser_download_url)
        .header(header::USER_AGENT, "rust-reqwest/self-update")
        .send()
        .await
        .context("Failed to artifact")?
        .bytes_stream();

    while let Some(chunk) = stream.next().await {
        let chunk = chunk?;
        tmp_exe.write_all(&chunk)?;
    }
    drop(tmp_exe);

    tracing::info!("replacing current exe");
    self_update::self_replace::self_replace(tmp_exe_path)?;

    Ok(())
}

pub async fn check_for_app_update(
    state_tx: &watch::Sender<AppState>,
    ui_message_rx: &mut mpsc::UnboundedReceiver<Message>,
) -> Result<()> {
    let mut app_state = state_tx.borrow().clone();
    app_state.state = State::CheckingForUpdate;
    state_tx.send(app_state.clone()).unwrap();

    let Some(release) = check_for_new_version()? else {
        // No new version.
        return Ok(());
    };

    // Notify user of update and ask for acknowledgement.
    app_state.state = State::WaitingForUpdateConfirmation(release.version.clone());
    state_tx.send(app_state.clone()).unwrap();

    // Wait acknowledgment.
    loop {
        match ui_message_rx.recv().await {
            Some(Message::UpdateAcknowledged) => break,
            Some(Message::UpdateCanceled) => return Ok(()),
            _ => (),
        };
    }

    app_state.state = State::Updating;
    state_tx.send(app_state.clone()).unwrap();

    download_new_version_and_replace_current(release).await?;

    app_state.state = State::Updated;
    state_tx.send(app_state.clone()).unwrap();

    // Loop while waiting for the app to restart or possibly a cancellation.
    while !matches!(ui_message_rx.recv().await, Some(Message::UpdateCanceled)) {}

    Ok(())
}
