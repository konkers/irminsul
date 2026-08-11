use std::io::Write;
use std::thread;

use anyhow::{Context, Result, anyhow};
use futures_util::StreamExt;
use reqwest::header;
use self_update::update::{Release, ReleaseAsset};
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

/// Find the asset matching the platform we're running on.
fn asset_for_target(release: &Release) -> Result<ReleaseAsset> {
    let target = if cfg!(windows) {
        "windows-x64"
    } else if cfg!(target_os = "linux") {
        "linux-x64"
    } else {
        return Err(anyhow!("no release assets are published for this platform"));
    };

    release
        .asset_for(target, None)
        .with_context(|| format!("release {} has no {target} asset", release.version))
}

/// Replace the running executable, returning whether packet capture
/// permissions need to be re-granted afterwards.
async fn download_new_version_and_replace_current(release: Release) -> Result<bool> {
    // File capabilities are an attribute of the inode, so replacing the
    // executable drops them.  Running as root loses nothing, since that
    // privilege comes from the invocation rather than from the file.
    #[cfg(unix)]
    let caps_lost = !crate::admin::is_root() && crate::admin::has_cap_net_raw();
    #[cfg(not(unix))]
    let caps_lost = false;

    let asset = asset_for_target(&release)?;
    tracing::info!("asset: {asset:#?}");

    // Stage the download next to the executable being replaced.  self_replace
    // finishes with a rename, which cannot cross filesystems, and the current
    // directory is neither guaranteed to be writable nor on the same mount.
    let current_exe = ::std::env::current_exe().context("could not find the current exe")?;
    let exe_dir = current_exe
        .parent()
        .context("current exe has no parent directory")?;
    let tmp_dir = tempfile::Builder::new()
        .prefix("self_update")
        .tempdir_in(exe_dir)?;
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

    // Release assets are written without the executable bit.
    #[cfg(unix)]
    {
        use ::std::os::unix::fs::PermissionsExt;
        ::std::fs::set_permissions(&tmp_exe_path, ::std::fs::Permissions::from_mode(0o755))
            .context("could not make the downloaded binary executable")?;
    }

    tracing::info!("replacing current exe");
    self_update::self_replace::self_replace(tmp_exe_path)?;

    Ok(caps_lost)
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

    let needs_caps = download_new_version_and_replace_current(release).await?;

    app_state.state = State::Updated { needs_caps };
    state_tx.send(app_state.clone()).unwrap();

    // Loop while waiting for the app to restart or possibly a cancellation.
    while !matches!(ui_message_rx.recv().await, Some(Message::UpdateCanceled)) {}

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn release(assets: &[&str]) -> Release {
        Release {
            version: "0.1.20".to_owned(),
            assets: assets
                .iter()
                .map(|name| ReleaseAsset {
                    download_url: format!("https://example.invalid/{name}"),
                    name: (*name).to_owned(),
                })
                .collect(),
            ..Default::default()
        }
    }

    #[test]
    fn picks_the_asset_for_this_platform() {
        let release = release(&[
            "irminsul.exe",
            "irminsul-windows-x64.exe",
            "irminsul-linux-x64",
        ]);
        let asset = asset_for_target(&release).expect("an asset for this platform");

        #[cfg(windows)]
        assert_eq!(asset.name, "irminsul-windows-x64.exe");
        #[cfg(target_os = "linux")]
        assert_eq!(asset.name, "irminsul-linux-x64");
    }

    #[test]
    fn never_picks_another_platforms_asset() {
        #[cfg(windows)]
        let release = release(&["irminsul-linux-x64"]);
        #[cfg(target_os = "linux")]
        let release = release(&["irminsul-windows-x64.exe"]);

        asset_for_target(&release)
            .expect_err("a release with only another platform's asset must not resolve");
    }

    /// The bare `irminsul.exe` predates per platform assets and is on its way
    /// out, so it is not a candidate on any platform.
    #[test]
    fn a_legacy_only_release_does_not_resolve() {
        let release = release(&["irminsul.exe"]);

        asset_for_target(&release).expect_err("irminsul.exe is not a platform specific asset");
    }
}
