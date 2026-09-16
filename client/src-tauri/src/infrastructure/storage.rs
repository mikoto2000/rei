use crate::{domain::*, ports::*};
use chacha20poly1305::{
    aead::{Aead, KeyInit},
    XChaCha20Poly1305, XNonce,
};
use rand::RngCore;
use std::{
    collections::BTreeMap,
    io::Write,
    path::{Path, PathBuf},
};
use zeroize::Zeroizing;

fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().ok_or(AppError::Storage)?;
    std::fs::create_dir_all(parent).map_err(|_| AppError::Storage)?;
    let mut file = tempfile::NamedTempFile::new_in(parent).map_err(|_| AppError::Storage)?;
    file.write_all(bytes).map_err(|_| AppError::Storage)?;
    file.as_file().sync_all().map_err(|_| AppError::Storage)?;
    file.persist(path).map_err(|_| AppError::Storage)?;
    Ok(())
}

pub struct JsonRepository {
    path: PathBuf,
}
impl JsonRepository {
    pub fn new(path: PathBuf) -> Self {
        Self { path }
    }
}
impl Repository for JsonRepository {
    fn load(&self) -> Result<AppData> {
        match std::fs::read(&self.path) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(|_| AppError::Storage),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(AppData::default()),
            Err(_) => Err(AppError::Storage),
        }
    }
    fn save(&self, data: &AppData) -> Result<()> {
        atomic_write(
            &self.path,
            &serde_json::to_vec_pretty(data).map_err(|_| AppError::Storage)?,
        )
    }
}

/// Versioned authenticated vault. The password and derived key never enter app data.
/// File: magic(8) || salt(16) || nonce(24) || authenticated ciphertext.
pub struct EncryptedVault {
    path: PathBuf,
    key: Zeroizing<[u8; 32]>,
    salt: [u8; 16],
    secrets: BTreeMap<String, Secret>,
}
impl EncryptedVault {
    pub fn open(path: PathBuf, password: Secret) -> Result<Self> {
        if password.expose().len() < 12 {
            return Err(AppError::InvalidInput);
        }
        let bytes = match std::fs::read(&path) {
            Ok(bytes) => Some(bytes),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
            Err(_) => return Err(AppError::Storage),
        };
        let mut salt = [0; 16];
        if let Some(bytes) = &bytes {
            if bytes.len() < 64 || &bytes[..8] != b"REIVAULT" {
                return Err(AppError::Storage);
            }
            salt.copy_from_slice(&bytes[8..24]);
        } else {
            rand::thread_rng().fill_bytes(&mut salt);
        }
        let mut key = Zeroizing::new([0; 32]);
        argon2::Argon2::default()
            .hash_password_into(password.expose().as_bytes(), &salt, key.as_mut())
            .map_err(|_| AppError::Storage)?;
        let mut secrets = BTreeMap::new();
        if let Some(bytes) = bytes {
            let cipher =
                XChaCha20Poly1305::new_from_slice(key.as_ref()).map_err(|_| AppError::Storage)?;
            let plain = Zeroizing::new(
                cipher
                    .decrypt(XNonce::from_slice(&bytes[24..48]), &bytes[48..])
                    .map_err(|_| AppError::VaultLocked)?,
            );
            let decoded: BTreeMap<String, String> =
                serde_json::from_slice(&plain).map_err(|_| AppError::Storage)?;
            secrets.extend(decoded.into_iter().map(|(k, v)| (k, Secret::new(v))));
        }
        let vault = Self {
            path,
            key,
            salt,
            secrets,
        };
        vault.persist()?;
        Ok(vault)
    }
    fn persist(&self) -> Result<()> {
        let map: BTreeMap<_, _> = self.secrets.iter().map(|(k, v)| (k, v.expose())).collect();
        let plain = Zeroizing::new(serde_json::to_vec(&map).map_err(|_| AppError::Storage)?);
        let mut nonce = [0; 24];
        rand::thread_rng().fill_bytes(&mut nonce);
        let cipher =
            XChaCha20Poly1305::new_from_slice(self.key.as_ref()).map_err(|_| AppError::Storage)?;
        let encrypted = cipher
            .encrypt(XNonce::from_slice(&nonce), plain.as_slice())
            .map_err(|_| AppError::Storage)?;
        let mut bytes = b"REIVAULT".to_vec();
        bytes.extend(self.salt);
        bytes.extend(nonce);
        bytes.extend(encrypted);
        atomic_write(&self.path, &bytes)
    }
}
impl CredentialStore for EncryptedVault {
    fn set(&mut self, reference: &str, secret: Secret) -> Result<()> {
        if secret.expose().trim().is_empty() || secret.expose().contains(['\r', '\n']) {
            return Err(AppError::InvalidInput);
        }
        let previous = self.secrets.insert(reference.into(), secret);
        if let Err(error) = self.persist() {
            self.secrets.remove(reference);
            if let Some(previous) = previous {
                self.secrets.insert(reference.into(), previous);
            }
            return Err(error);
        }
        Ok(())
    }
    fn get(&self, reference: &str) -> Result<Secret> {
        self.secrets
            .get(reference)
            .cloned()
            .ok_or(AppError::AuthenticationFailed)
    }
    fn exists(&self, reference: &str) -> Result<bool> {
        Ok(self.secrets.contains_key(reference))
    }
    fn delete(&mut self, reference: &str) -> Result<()> {
        let previous = self.secrets.remove(reference);
        if let Err(error) = self.persist() {
            if let Some(previous) = previous {
                self.secrets.insert(reference.into(), previous);
            }
            return Err(error);
        }
        Ok(())
    }
}
