// The code from https://github.com/IceDynamix/reliquary-archiver
//
// MIT License
//
// Copyright (c) 2024 IceDynamix
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

#[cfg(windows)]
pub fn ensure_admin() {
    if unsafe { windows::Win32::UI::Shell::IsUserAnAdmin().into() } {
        tracing::info!("Running with admin privileges");
        return;
    }

    tracing::info!("Escalating to admin privileges");

    use std::env;
    use std::os::windows::ffi::OsStrExt;

    use windows::Win32::System::Console::GetConsoleWindow;
    use windows::Win32::UI::Shell::{
        SEE_MASK_NO_CONSOLE, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW, ShellExecuteExW,
    };
    use windows::Win32::UI::WindowsAndMessaging::{GW_OWNER, GetWindow, SW_SHOWNORMAL};
    use windows::core::{PCWSTR, w};

    let args_str = env::args().skip(1).collect::<Vec<_>>().join(" ");

    let exe_path = env::current_exe()
        .expect("Failed to get current exe")
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect::<Vec<_>>();
    let args = args_str.encode_utf16().chain(Some(0)).collect::<Vec<_>>();

    unsafe {
        let mut options = SHELLEXECUTEINFOW {
            cbSize: size_of::<SHELLEXECUTEINFOW>() as u32,
            fMask: SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NO_CONSOLE,
            hwnd: GetWindow(GetConsoleWindow(), GW_OWNER).unwrap_or(GetConsoleWindow()),
            lpVerb: w!("runas"),
            lpFile: PCWSTR(exe_path.as_ptr()),
            lpParameters: PCWSTR(args.as_ptr()),
            lpDirectory: PCWSTR::null(),
            nShow: SW_SHOWNORMAL.0,
            lpIDList: std::ptr::null_mut(),
            lpClass: PCWSTR::null(),
            dwHotKey: 0,
            ..Default::default()
        };

        if let Err(e) = ShellExecuteExW(&mut options) {
            tracing::error!("unable to run self with admin privs: {e}");
        }
    };

    // Exit the current process since we launched a new elevated one
    std::process::exit(0);
}

#[cfg(unix)]
pub fn ensure_admin() {
    let is_root = unsafe { libc::geteuid() } == 0;
    if is_root {
        return;
    }

    show_root_required_dialog();
}

#[cfg(unix)]
fn show_root_required_dialog() {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([400.0, 150.0])
            .with_resizable(false),
        ..Default::default()
    };

    // Try to get the current executable path
    let exe_path = std::env::current_exe()
        .ok()
        .map(|mut path| path.as_mut_os_string().to_string_lossy().to_string())
        .unwrap_or("./irminsul".to_owned());

    let _ = eframe::run_simple_native(
        "Irminsul must be run as root",
        options,
        move |ctx, _frame| {
            egui::CentralPanel::default().show(ctx, |ui| {
                // Get available height
                let available_height = ui.available_height();

                // Center the text in the upper portion
                ui.vertical_centered(|ui| {
                    ui.add_space(available_height * 0.2);
                    ui.label("Rerun Irminsul with sudo:");
                    ui.add_space(5.0);
                    ui.label(format!("sudo {}", exe_path));
                });

                // Push button to the bottom
                ui.with_layout(
                    egui::Layout::bottom_up(egui::Align::Center).with_cross_justify(true),
                    |ui| {
                        ui.add_space(10.0); // Small margin from bottom edge
                        if ui.button("OK").clicked() {
                            std::process::exit(1);
                        }
                    },
                );
            });
        },
    );

    std::process::exit(1);
}
