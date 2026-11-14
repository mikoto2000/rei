use crate::{domain::*, ports::*};
use chacha20poly1305::{
    aead::{Aead, KeyInit},
    XChaCha20Poly1305, XNonce,
};
use rand::RngCore;
use std::{
    collections::BTreeMap,
    fs::{File, OpenOptions},
    io::{ErrorKind, Read, Write},
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

/// Authenticated vault. The password and derived key never enter app data.
/// File: magic(8) || salt(16) || nonce(24) || authenticated ciphertext.
pub struct EncryptedVault {
    path: PathBuf,
    key: Zeroizing<[u8; 32]>,
    salt: [u8; 16],
}
impl EncryptedVault {
    const MAX_BYTES: usize = 1024 * 1024;

    pub fn open(path: PathBuf, password: Secret) -> Result<Self> {
        if password.expose().len() < 12 {
            return Err(AppError::InvalidPassphrase);
        }
        let bytes = Self::read_file(&path)?;
        if let Some(bytes) = bytes {
            let vault = Self::derive(path, &password, Self::read_salt(&bytes)?)?;
            vault.decrypt(&bytes)?;
            return Ok(vault);
        }

        // Another process may have initialized the vault before this lock was acquired.
        let _lock = Self::lock_file(&path)?;
        let bytes = Self::read_file(&path)?;
        let salt = match &bytes {
            Some(bytes) => Self::read_salt(bytes)?,
            None => {
                let mut salt = [0; 16];
                rand::thread_rng().fill_bytes(&mut salt);
                salt
            }
        };
        let vault = Self::derive(path, &password, salt)?;
        match bytes {
            Some(bytes) => {
                vault.decrypt(&bytes)?;
            }
            None => vault.persist(&BTreeMap::new())?,
        }
        Ok(vault)
    }

    fn derive(path: PathBuf, password: &Secret, salt: [u8; 16]) -> Result<Self> {
        let mut key = Zeroizing::new([0; 32]);
        argon2::Argon2::default()
            .hash_password_into(password.expose().as_bytes(), &salt, key.as_mut())
            .map_err(|_| AppError::Storage)?;
        Ok(Self { path, key, salt })
    }

    fn lock_file(path: &Path) -> Result<File> {
        let parent = path.parent().ok_or(AppError::Storage)?;
        std::fs::create_dir_all(parent).map_err(|_| AppError::Storage)?;
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true).truncate(false);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let lock = options
            .open(path.with_extension("lock"))
            .map_err(|_| AppError::Storage)?;
        lock.lock().map_err(|_| AppError::Storage)?;
        Ok(lock)
    }

    fn read_file(path: &Path) -> Result<Option<Vec<u8>>> {
        let file = match File::open(path) {
            Ok(file) => file,
            Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
            Err(_) => return Err(AppError::Storage),
        };
        let mut bytes = Vec::new();
        file.take((Self::MAX_BYTES + 1) as u64)
            .read_to_end(&mut bytes)
            .map_err(|_| AppError::Storage)?;
        if bytes.len() > Self::MAX_BYTES {
            return Err(AppError::Storage);
        }
        Ok(Some(bytes))
    }

    fn read_salt(bytes: &[u8]) -> Result<[u8; 16]> {
        if bytes.len() < 64 || &bytes[..8] != b"REIVAULT" {
            return Err(AppError::Storage);
        }
        bytes[8..24].try_into().map_err(|_| AppError::Storage)
    }

    fn decrypt(&self, bytes: &[u8]) -> Result<BTreeMap<String, Secret>> {
        if Self::read_salt(bytes)? != self.salt {
            return Err(AppError::VaultLocked);
        }
        let cipher =
            XChaCha20Poly1305::new_from_slice(self.key.as_ref()).map_err(|_| AppError::Storage)?;
        let plain = Zeroizing::new(
            cipher
                .decrypt(XNonce::from_slice(&bytes[24..48]), &bytes[48..])
                .map_err(|_| AppError::VaultLocked)?,
        );
        let decoded: BTreeMap<String, String> =
            serde_json::from_slice(&plain).map_err(|_| AppError::Storage)?;
        Ok(decoded
            .into_iter()
            .map(|(k, v)| (k, Secret::new(v)))
            .collect())
    }

    fn read_secrets(&self) -> Result<BTreeMap<String, Secret>> {
        let bytes = Self::read_file(&self.path)?.ok_or(AppError::Storage)?;
        self.decrypt(&bytes)
    }

    fn persist(&self, secrets: &BTreeMap<String, Secret>) -> Result<()> {
        let map: BTreeMap<_, _> = secrets.iter().map(|(k, v)| (k, v.expose())).collect();
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
        if bytes.len() > Self::MAX_BYTES {
            return Err(AppError::Storage);
        }
        atomic_write(&self.path, &bytes)
    }
}
impl CredentialStore for EncryptedVault {
    fn set(&mut self, reference: &str, secret: Secret) -> Result<()> {
        if secret.expose().trim().is_empty() || secret.expose().contains(['\r', '\n']) {
            return Err(AppError::InvalidCredential);
        }
        let _lock = Self::lock_file(&self.path)?;
        let mut secrets = self.read_secrets()?;
        secrets.insert(reference.into(), secret);
        self.persist(&secrets)
    }
    fn get(&self, reference: &str) -> Result<Secret> {
        self.read_secrets()?
            .remove(reference)
            .ok_or(AppError::AuthenticationFailed)
    }
    fn exists(&self, reference: &str) -> Result<bool> {
        Ok(self.read_secrets()?.contains_key(reference))
    }
    fn delete(&mut self, reference: &str) -> Result<()> {
        let _lock = Self::lock_file(&self.path)?;
        let mut secrets = self.read_secrets()?;
        if secrets.remove(reference).is_some() {
            self.persist(&secrets)?;
        }
        Ok(())
    }
}
