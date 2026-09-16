use super::{EncryptedVault, JsonRepository};
use crate::{api::HttpReiClient, application::Application, domain::*, ports::*};
use std::{path::PathBuf, sync::Arc};
struct HttpFactory;
impl ClientFactory for HttpFactory {
    fn create(
        &self,
        profile: &ServerProfile,
        credential: Option<Secret>,
    ) -> Result<Arc<dyn ReiClient>> {
        Ok(Arc::new(HttpReiClient::new(&profile.base_url, credential)?))
    }
}
struct VaultFactory(PathBuf);
impl CredentialFactory for VaultFactory {
    fn unlock(&self, password: Secret) -> Result<Box<dyn CredentialStore>> {
        Ok(Box::new(EncryptedVault::open(self.0.clone(), password)?))
    }
}
/// Composition root: dependencies point into application ports, never out of core.
impl Application {
    pub fn open(
        directory: PathBuf,
        observer: Arc<dyn RunObserver>,
        notifications: Arc<dyn NotificationPort>,
    ) -> Result<Self> {
        Self::new(
            Arc::new(JsonRepository::new(directory.join("app.json"))),
            Arc::new(VaultFactory(directory.join("credentials.vault"))),
            Arc::new(HttpFactory),
            observer,
            notifications,
        )
    }
}
