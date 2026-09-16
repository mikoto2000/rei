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
