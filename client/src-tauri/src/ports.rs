use crate::application::RunView;
use crate::domain::*;
use async_trait::async_trait;
use futures_util::Stream;
use std::pin::Pin;
use std::sync::Arc;

pub trait ClientFactory: Send + Sync {
    fn create(
        &self,
        profile: &ServerProfile,
        credential: Option<Secret>,
    ) -> Result<Arc<dyn ReiClient>>;
}
pub trait CredentialFactory: Send + Sync {
    fn unlock(&self, password: Secret) -> Result<Box<dyn CredentialStore>>;
}

pub trait RunObserver: Send + Sync {
    fn changed(&self, run: RunView);
}
pub trait NotificationPort: Send + Sync {
    fn notify(&self, run: &RunView) -> Result<()>;
}

pub type ByteStream = Pin<Box<dyn Stream<Item = Result<Vec<u8>>> + Send>>;
#[async_trait]
pub trait ReiClient: Send + Sync {
    async fn list_tasks(&self, _query: TaskQuery) -> Result<TaskPage> {
        Err(AppError::EndpointNotFound)
    }
    async fn get_task(
        &self,
        _project: &str,
        _session: Option<&str>,
        _id: &str,
    ) -> Result<ManagedTask> {
        Err(AppError::EndpointNotFound)
    }
    async fn submit_task(
        &self,
        _project: &str,
        _session: Option<&str>,
        _message: &str,
    ) -> Result<ManagedTask> {
        Err(AppError::EndpointNotFound)
    }
    async fn control_task(
        &self,
        _project: &str,
        _session: Option<&str>,
        _id: &str,
        _run: Option<&str>,
        _revision: u64,
        _action: TaskAction,
        _message: Option<&str>,
    ) -> Result<ManagedTask> {
        Err(AppError::EndpointNotFound)
    }
    async fn schedule_snapshot(&self, _project: &str, _schedule: &str) -> Result<RunSnapshot> {
        Err(AppError::InvalidResponse)
    }
    async fn goal_snapshot(&self, _project: &str, _goal: &str) -> Result<RunSnapshot> {
        Err(AppError::InvalidResponse)
    }
    async fn resume_checkpoint(&self, _project: &str, _task: &str) -> Result<CheckpointReceipt> {
        Err(AppError::InvalidResponse)
    }
    async fn checkpoint_run(&self, _project: &str, _task: &str) -> Result<CheckpointReceipt> {
        Err(AppError::InvalidResponse)
    }
    async fn background(
        &self,
        _project: &str,
        _operation: BackgroundOperation,
    ) -> Result<BackgroundReceipt> {
        Err(AppError::InvalidResponse)
    }
    async fn workspace(&self, _operation: WorkspaceOperation) -> Result<WorkspaceResult> {
        Err(AppError::InvalidResponse)
    }
    async fn list_sessions(
        &self,
        _project: Option<&str>,
        _query: HistoryQuery,
    ) -> Result<Page<SessionSummary>> {
        Err(AppError::InvalidResponse)
    }
    async fn get_session(&self, _session: &str) -> Result<SessionSummary> {
        Err(AppError::InvalidResponse)
    }
    async fn list_session_turns(
        &self,
        _session: &str,
        _query: HistoryQuery,
    ) -> Result<Page<ConversationTurn>> {
        Err(AppError::InvalidResponse)
    }
    async fn health(&self) -> Result<()>;
    async fn projects(&self) -> Result<Vec<Project>>;
    async fn chat(
        &self,
        project: &str,
        session: Option<&str>,
        message: &str,
    ) -> Result<ChatReceipt>;
    async fn chat_mode(
        &self,
        project: &str,
        session: Option<&str>,
        message: &str,
        mode: RunMode,
    ) -> Result<ChatReceipt> {
        if mode != RunMode::Exclusive {
            return Err(AppError::InvalidInput);
        }
        self.chat(project, session, message).await
    }
    async fn run(&self, run: &str) -> Result<RunSnapshot>;
    async fn input(
        &self,
        _run: &str,
        _project: &str,
        _session: &str,
        _message: &str,
    ) -> Result<()> {
        Err(AppError::EndpointNotFound)
    }
    async fn cancel(&self, run: &str) -> Result<RunSnapshot>;
    async fn cancel_receipt(&self, run: &str) -> Result<CancelReceipt> {
        Ok(CancelReceipt {
            accepted: false,
            snapshot: self.cancel(run).await?,
        })
    }
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
