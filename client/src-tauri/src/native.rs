use crate::dto::*;
use crate::{application::*, domain::*, ports::*};
use serde::Serialize;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use tauri::{Emitter, Manager, State};
use tauri_plugin_notification::NotificationExt;

struct NativeObserver(tauri::AppHandle);
impl RunObserver for NativeObserver {
    fn changed(&self, run: RunView) {
        let _ = self.0.emit("rei://run-state", run);
    }
}
struct NativeNotifications {
    app: tauri::AppHandle,
    enabled: Arc<AtomicBool>,
}
impl NotificationPort for NativeNotifications {
    fn notify(&self, run: &RunView) -> Result<()> {
        if !self.enabled.load(Ordering::Relaxed) {
            return Ok(());
        }
        if self.app.notification().permission_state().ok()
            != Some(tauri::plugin::PermissionState::Granted)
        {
            return Ok(());
        }
        let title = match run.status {
            RunStatus::Completed => "Rei: Run completed",
            RunStatus::Failed => "Rei: Run failed",
            _ => return Ok(()),
        };
        self.app
            .notification()
            .builder()
            .title(title)
            .body("Rei Client で結果を確認してください。")
            .show()
            .map_err(|_| AppError::Cancelled)
    }
}
type App<'a> = State<'a, Arc<Application>>;
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ConversationDto {
    local_id: String,
    server_profile_id: String,
    project_id: String,
    session_id: Option<String>,
    title: String,
    created_at: u64,
    last_accessed_at: u64,
}
impl From<Conversation> for ConversationDto {
    fn from(c: Conversation) -> Self {
        Self {
            local_id: c.local_id,
            server_profile_id: c.server_profile_id,
            project_id: c.project_id,
            session_id: c.session_id,
            title: c.title,
            created_at: c.created_at,
            last_accessed_at: c.last_accessed_at,
        }
    }
}
#[derive(Serialize)]
struct ProjectDto {
    id: String,
    name: String,
    path: String,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SnapshotDto {
    servers: Vec<ServerView>,
    selected_server: Option<String>,
    conversations: Vec<ConversationDto>,
    runs: Vec<RunView>,
    unlocked: bool,
    notifications: bool,
}

#[tauri::command]
fn app_snapshot(app: App<'_>) -> Result<SnapshotDto> {
    let data = app.conversations.store.snapshot();
    Ok(SnapshotDto {
        servers: app.servers()?,
        selected_server: data.selected_server,
        conversations: app
            .conversations
            .list()
            .into_iter()
            .map(Into::into)
            .collect(),
        runs: app.runs.all(),
        unlocked: app.unlocked(),
        notifications: data.notifications,
    })
}
#[tauri::command]
async fn vault_unlock(app: App<'_>, password: String) -> Result<()> {
    let app = app.inner().clone();
    let password = Secret::new(password);
    tauri::async_runtime::spawn_blocking(move || app.unlock(password))
        .await
        .map_err(|_| AppError::Storage)?
}
#[tauri::command]
fn server_list(app: App<'_>) -> Result<Vec<ServerView>> {
    app.servers()
}
#[tauri::command]
fn server_save(app: App<'_>, id: Option<String>, name: String, base_url: String) -> Result<String> {
    app.save_server(id.as_deref(), &name, &base_url)
}
#[tauri::command]
fn server_remove(app: App<'_>, server_id: String) -> Result<()> {
    app.remove_server(&server_id)
}
#[tauri::command]
fn server_select(app: App<'_>, server_id: String) -> Result<()> {
    app.select_server(&server_id)
}
#[tauri::command]
async fn server_test(
    app: App<'_>,
    handle: tauri::AppHandle,
    server_id: String,
) -> Result<ConnectionView> {
    let _ = handle.emit(
        "rei://connection-state",
        ConnectionView {
            server_id: server_id.clone(),
            state: ConnectionState::Connecting,
            reachable: false,
            authenticated: false,
            error: None,
        },
    );
    let result = app.test_server(&server_id).await?;
    let _ = handle.emit("rei://connection-state", result.clone());
    Ok(result)
}
#[tauri::command]
fn credential_set(app: App<'_>, server_id: String, credential: String) -> Result<()> {
    app.set_credential(&server_id, Secret::new(credential))
}
#[tauri::command]
fn credential_delete(app: App<'_>, server_id: String) -> Result<()> {
    app.delete_credential(&server_id)
}
#[tauri::command]
async fn projects_list(app: App<'_>, server_id: String) -> Result<Vec<ProjectDto>> {
    Ok(app
        .api(&server_id, true)?
        .projects()
        .await?
        .into_iter()
        .map(|p| ProjectDto {
            id: p.id,
            name: p.name,
            path: p.path,
        })
        .collect())
}
#[tauri::command]
async fn session_list(
    app: App<'_>,
    server_id: String,
    project_id: Option<String>,
    limit: Option<i32>,
    cursor: Option<String>,
) -> Result<SessionPageDto> {
    Ok(app
        .session_list(&server_id, project_id.as_deref(), limit, cursor)
        .await?
        .into())
}
#[tauri::command]
async fn session_get(
    app: App<'_>,
    server_id: String,
    session_id: String,
) -> Result<SessionSummaryDto> {
    Ok(app.session_get(&server_id, &session_id).await?.into())
}
#[tauri::command]
async fn session_turns(
    app: App<'_>,
    server_id: String,
    session_id: String,
    limit: Option<i32>,
    cursor: Option<String>,
) -> Result<TurnPageDto> {
    Ok(app
        .session_turns(&server_id, &session_id, limit, cursor)
        .await?
        .into())
}
#[tauri::command]
async fn session_open(
    app: App<'_>,
    server_id: String,
    session_id: String,
) -> Result<ConversationDto> {
    Ok(app.resume_session(&server_id, &session_id).await?.into())
}
#[tauri::command]
fn conversation_list(app: App<'_>) -> Vec<ConversationDto> {
    app.conversations
        .list()
        .into_iter()
        .map(Into::into)
        .collect()
}
#[tauri::command]
fn conversation_create(
    app: App<'_>,
    server_id: String,
    project_id: String,
    title: String,
) -> Result<ConversationDto> {
    Ok(app
        .conversations
        .create(&server_id, &project_id, &title)?
        .into())
}
#[tauri::command]
fn conversation_continue_new(app: App<'_>, conversation_id: String) -> Result<ConversationDto> {
    Ok(app.conversations.continue_as_new(&conversation_id)?.into())
}
#[tauri::command]
fn conversation_select(app: App<'_>, conversation_id: String) -> Result<()> {
    app.conversations.touch(&conversation_id)
}
#[tauri::command]
fn conversation_delete(app: App<'_>, conversation_id: String) -> Result<()> {
    app.delete_conversation(&conversation_id)
}
#[tauri::command]
async fn chat_submit(app: App<'_>, conversation_id: String, message: String) -> Result<RunView> {
    app.submit(&conversation_id, &message).await
}
#[tauri::command]
async fn run_get(app: App<'_>, server_id: String, run_id: String) -> Result<RunView> {
    app.runs
        .refresh(&server_id, &run_id, app.api(&server_id, true)?)
        .await
}
#[tauri::command]
async fn run_subscribe(app: App<'_>, server_id: String, run_id: String) -> Result<()> {
    app.runs
        .subscribe(&server_id, &run_id, app.api(&server_id, true)?)
}
#[tauri::command]
fn run_unsubscribe(app: App<'_>, server_id: String, run_id: String) -> Result<()> {
    app.runs.unsubscribe(&server_id, &run_id)
}
#[tauri::command]
async fn run_cancel(app: App<'_>, server_id: String, run_id: String) -> Result<()> {
    app.runs
        .cancel(&server_id, &run_id, app.api(&server_id, true)?)
        .await
}
#[tauri::command]
fn run_list_active(app: App<'_>) -> Vec<RunView> {
    app.runs.active()
}
#[tauri::command]
fn notification_settings(
    app: App<'_>,
    handle: tauri::AppHandle,
    enabled: bool,
    flag: State<'_, Arc<AtomicBool>>,
) -> Result<bool> {
    let granted = !enabled
        || handle.notification().request_permission().ok()
            == Some(tauri::plugin::PermissionState::Granted);
    app.notifications(enabled && granted)?;
    flag.store(enabled && granted, Ordering::Relaxed);
    Ok(granted)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default().plugin(tauri_plugin_notification::init());

    // Register before configured windows are created so initial restoration runs.
    #[cfg(desktop)]
    let builder = builder.plugin(tauri_plugin_window_state::Builder::default().build());

    builder
        .setup(|app| {
            let flag = Arc::new(AtomicBool::new(false));
            let handle = app.handle().clone();
            let application = Application::open(
                app.path().app_data_dir()?,
                Arc::new(NativeObserver(handle.clone())),
                Arc::new(NativeNotifications {
                    app: handle,
                    enabled: flag.clone(),
                }),
            )
            .map_err(|_| std::io::Error::other("Rei app data could not be opened"))?;
            flag.store(
                application.conversations.store.snapshot().notifications,
                Ordering::Relaxed,
            );
            app.manage(flag);
            app.manage(Arc::new(application));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            app_snapshot,
            vault_unlock,
            server_list,
            server_save,
            server_remove,
            server_select,
            server_test,
            credential_set,
            credential_delete,
            projects_list,
            session_list,
            session_get,
            session_turns,
            session_open,
            conversation_list,
            conversation_create,
            conversation_continue_new,
            conversation_select,
            conversation_delete,
            chat_submit,
            run_get,
            run_subscribe,
            run_unsubscribe,
            run_cancel,
            run_list_active,
            notification_settings
        ])
        .run(tauri::generate_context!())
        .expect("Rei Client runtime failed");
}
