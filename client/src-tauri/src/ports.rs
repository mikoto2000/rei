use crate::application::RunView;
use crate::domain::*;
use async_trait::async_trait;
use futures_util::Stream;
use std::pin::Pin;

pub trait RunObserver: Send + Sync {
    fn changed(&self, run: RunView);
}
pub trait NotificationPort: Send + Sync {
    fn notify(&self, run: &RunView) -> Result<()>;
}

pub type ByteStream = Pin<Box<dyn Stream<Item = Result<Vec<u8>>> + Send>>;
#[async_trait]
pub trait ReiClient: Send + Sync {
    async fn health(&self) -> Result<()>;
    async fn projects(&self) -> Result<Vec<Project>>;
    async fn chat(
        &self,
        project: &str,
        session: Option<&str>,
        message: &str,
    ) -> Result<ChatReceipt>;
    async fn run(&self, run: &str) -> Result<RunSnapshot>;
    async fn cancel(&self, run: &str) -> Result<RunSnapshot>;
    async fn events(&self, run: &str, last: Option<u64>) -> Result<ByteStream>;
}

pub trait CredentialStore: Send + Sync {
    fn set(&mut self, reference: &str, secret: Secret) -> Result<()>;
    fn get(&self, reference: &str) -> Result<Secret>;
    fn exists(&self, reference: &str) -> Result<bool>;
    fn delete(&mut self, reference: &str) -> Result<()>;
}

pub trait Repository: Send + Sync {
    fn load(&self) -> Result<AppData>;
    fn save(&self, data: &AppData) -> Result<()>;
}
