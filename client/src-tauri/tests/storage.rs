use rei_client_lib::{domain::*, infrastructure::*, ports::*};

#[test]
fn settings_roundtrip_and_selection() {
    let dir = tempfile::tempdir().unwrap();
    let repo = JsonRepository::new(dir.path().join("settings.json"));
    let mut data = AppData::default();
    let profile = ServerProfile::new("home", "https://rei.example").unwrap();
    data.selected_server = Some(profile.id.clone());
    data.servers.push(profile);
    repo.save(&data).unwrap();
    assert_eq!(repo.load().unwrap().selected_server, data.selected_server);
    assert_eq!(repo.load().unwrap().servers.len(), 1);
}

#[test]
fn encrypted_vault_set_exists_delete_and_reopen() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("secrets.vault");
    let mut vault = EncryptedVault::open(
        path.clone(),
        Secret::new("correct horse battery staple".into()),
    )
    .unwrap();
    vault
        .set("server", Secret::new("api-key-secret".into()))
        .unwrap();
    assert!(vault.exists("server").unwrap());
    assert!(!String::from_utf8_lossy(&std::fs::read(&path).unwrap()).contains("api-key-secret"));
    assert!(EncryptedVault::open(path.clone(), Secret::new("wrong-password".into())).is_err());
    drop(vault);
    let mut vault =
        EncryptedVault::open(path, Secret::new("correct horse battery staple".into())).unwrap();
    assert_eq!(vault.get("server").unwrap().expose(), "api-key-secret");
    vault.delete("server").unwrap();
    vault.delete("server").unwrap();
    assert!(!vault.exists("server").unwrap());
}

#[test]
fn corrupt_settings_are_not_silently_replaced() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("settings.json");
    std::fs::write(&path, "broken").unwrap();
    assert!(JsonRepository::new(path).load().is_err());
}

#[test]
fn reopening_does_not_rewrite_and_two_handles_preserve_updates() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("secrets.vault");
    let password = || Secret::new("correct horse battery staple".into());
    let mut first = EncryptedVault::open(path.clone(), password()).unwrap();
    let before = std::fs::read(&path).unwrap();
    let mut second = EncryptedVault::open(path.clone(), password()).unwrap();
    assert_eq!(std::fs::read(&path).unwrap(), before);

    first.set("first", Secret::new("key-one".into())).unwrap();
    second.set("second", Secret::new("key-two".into())).unwrap();
    assert!(first.exists("second").unwrap());
    assert!(second.exists("first").unwrap());
    first.delete("second").unwrap();
    assert!(!second.exists("second").unwrap());
}

#[test]
fn rejects_oversized_vault() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("secrets.vault");
    std::fs::File::create(&path)
        .unwrap()
        .set_len(1024 * 1024 + 1)
        .unwrap();
    let password = Secret::new("correct horse battery staple".into());
    assert!(matches!(
        EncryptedVault::open(path, password),
        Err(AppError::Storage)
    ));
}
