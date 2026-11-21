use crate::domain::TaskAction;

pub struct TaskControlCommand {
    pub server_id: String,
    pub project_id: String,
    pub session_id: Option<String>,
    pub task_id: String,
    pub expected_run_id: Option<String>,
    pub expected_revision: u64,
    pub action: TaskAction,
    pub message: Option<String>,
}

impl<'de, R: tauri::Runtime> tauri::ipc::CommandArg<'de, R> for TaskControlCommand {
    fn from_command(
        command: tauri::ipc::CommandItem<'de, R>,
    ) -> std::result::Result<Self, tauri::ipc::InvokeError> {
        use tauri::ipc::CommandItem;
        // Decode the original flat IPC keys through Tauri, retaining its validation errors.
        Ok(Self {
            server_id: String::from_command(CommandItem {
                key: "serverId",
                ..command
            })?,
            project_id: String::from_command(CommandItem {
                key: "projectId",
                ..command
            })?,
            session_id: Option::<String>::from_command(CommandItem {
                key: "sessionId",
                ..command
            })?,
            task_id: String::from_command(CommandItem {
                key: "taskId",
                ..command
            })?,
            expected_run_id: Option::<String>::from_command(CommandItem {
                key: "expectedRunId",
                ..command
            })?,
            expected_revision: u64::from_command(CommandItem {
                key: "expectedRevision",
                ..command
            })?,
            action: TaskAction::from_command(CommandItem {
                key: "action",
                ..command
            })?,
            message: Option::<String>::from_command(CommandItem {
                key: "message",
                ..command
            })?,
        })
    }
}
