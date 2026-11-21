use rei_client_lib::ipc::TaskControlCommand;
use serde_json::{json, Value};

#[tauri::command]
fn decode_task_control(command: TaskControlCommand) -> Value {
    json!({
        "serverId": command.server_id, "projectId": command.project_id,
        "sessionId": command.session_id, "taskId": command.task_id,
        "expectedRunId": command.expected_run_id, "expectedRevision": command.expected_revision,
        "action": command.action, "message": command.message,
    })
}

fn invoke(body: Value) -> std::result::Result<Value, Value> {
    let app = tauri::test::mock_builder()
        .invoke_handler(tauri::generate_handler![decode_task_control])
        .build(tauri::test::mock_context(tauri::test::noop_assets()))
        .unwrap();
    let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .unwrap();
    tauri::test::get_ipc_response(
        &window,
        tauri::webview::InvokeRequest {
            cmd: "decode_task_control".into(),
            callback: tauri::ipc::CallbackFn(0),
            error: tauri::ipc::CallbackFn(1),
            url: "http://tauri.localhost".parse().unwrap(),
            body: body.into(),
            headers: Default::default(),
            invoke_key: tauri::test::INVOKE_KEY.to_owned(),
        },
    )
    .map(|response| response.deserialize::<Value>().unwrap())
}

#[test]
fn flat_keys_and_optional_values_keep_the_existing_ipc_contract() {
    for action in ["cancel", "suspend", "resume", "input"] {
        let payload = json!({"serverId":"server", "projectId":"project", "sessionId":"session",
            "taskId":"run:r", "expectedRunId":"r", "expectedRevision":7,
            "action":action, "message":"追加の指示"});
        assert_eq!(invoke(payload.clone()).unwrap(), payload);
    }
    let payload = json!({"serverId":"server", "projectId":"project", "taskId":"run:r",
        "expectedRevision":7,"action":"cancel"});
    let result = invoke(payload).unwrap();
    for key in ["sessionId", "expectedRunId", "message"] {
        assert!(result[key].is_null());
    }
}

#[test]
fn invalid_fields_keep_tauri_argument_names_and_validation_errors() {
    let mut payload =
        json!({"serverId":"server", "projectId":"project", "taskId":"run:r", "action":"cancel"});
    let missing = invoke(payload.clone()).unwrap_err().to_string();
    assert!(missing.contains("expectedRevision") && missing.contains("missing required key"));
    payload["expectedRevision"] = json!("7");
    let invalid = invoke(payload.clone()).unwrap_err().to_string();
    assert!(invalid.contains("expectedRevision") && invalid.contains("invalid type"));
    payload["expectedRevision"] = json!(7);
    payload["action"] = json!("unknown");
    let invalid = invoke(payload).unwrap_err().to_string();
    assert!(invalid.contains("action") && invalid.contains("unknown variant"));
}
