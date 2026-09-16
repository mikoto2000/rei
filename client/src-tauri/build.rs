fn main() {
    #[cfg(feature = "native")]
    tauri_build::build();
}
