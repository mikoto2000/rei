use super::*;
use serde_json::json;
impl HttpReiClient {
    async fn task_json<T: DeserializeOwned>(
        request: reqwest::RequestBuilder,
        accepted: bool,
    ) -> Result<T> {
        let response = Self::checked(
            request.timeout(Duration::from_secs(30)),
            Operation::Resource,
        )
        .await?;
        if response.status()
            != if accepted {
                reqwest::StatusCode::ACCEPTED
            } else {
                reqwest::StatusCode::OK
            }
            || !response
                .headers()
                .get("content-type")
                .and_then(|v| v.to_str().ok())
                .unwrap_or("")
                .starts_with("application/json")
        {
            return Err(AppError::InvalidResponse);
        }
        let mut stream = response.bytes_stream();
        let mut bytes = Vec::new();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|e| {
                if e.is_timeout() {
                    AppError::RequestTimeout
                } else {
                    AppError::InvalidResponse
                }
            })?;
            if bytes.len() + chunk.len() > 8 * 1024 * 1024 {
                return Err(AppError::InvalidResponse);
            }
            bytes.extend_from_slice(&chunk);
        }
        serde_json::from_slice(&bytes).map_err(|_| AppError::InvalidResponse)
    }
    pub(super) async fn task_list(&self, query: TaskQuery) -> Result<TaskPage> {
        TaskQuery::new(
            query.project_id.clone(),
            query.session_id.clone(),
            Some(query.page.limit.into()),
            query.page.cursor.clone(),
        )?;
        let mut request = self.history_request("/api/v1/tasks", &query.page)?;
        if let Some(project) = &query.project_id {
            request = request.query(&[("projectId", project)]);
        }
        if let Some(session) = &query.session_id {
            request = request.query(&[("sessionId", session)]);
        }
        let page: TaskPage = Self::task_json(request, false).await.map_err(|e| {
            if e == AppError::NotFound {
                AppError::EndpointNotFound
            } else {
                e
            }
        })?;
        page.validate(&query)?;
        Ok(page)
    }
    pub(super) async fn task_get(
        &self,
        project: &str,
        session: Option<&str>,
        id: &str,
    ) -> Result<ManagedTask> {
        Self::task_owner(project, session)?;
        let mut request = self
            .request(Method::GET, &task_path(id, "")?, true)?
            .query(&[("projectId", project)]);
        if let Some(session) = session {
            request = request.query(&[("sessionId", session)]);
        }
        let task: ManagedTask = Self::task_json(request, false).await?;
        task.owned(project, session, Some(id))?;
        Ok(task)
    }
    pub(super) async fn task_submit(
        &self,
        project: &str,
        session: Option<&str>,
        message: &str,
    ) -> Result<ManagedTask> {
        Self::task_owner(project, session)?;
        task_message(message)?;
        let task: ManagedTask = Self::task_json(
            self.request(Method::POST, "/api/v1/tasks", true)?
                .json(&json!({"projectId":project,"sessionId":session,"message":message})),
            true,
        )
        .await?;
        task.validate()?;
        if task.project_id != project
            || task.session_id.is_none()
            || session.is_some_and(|s| task.session_id.as_deref() != Some(s))
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(task)
    }
    pub(super) async fn task_control(
        &self,
        project: &str,
        session: Option<&str>,
        id: &str,
        run: Option<&str>,
        revision: u64,
        action: TaskAction,
        message: Option<&str>,
    ) -> Result<ManagedTask> {
        Self::task_owner(project, session)?;
        if revision > 9_007_199_254_740_991 {
            return Err(AppError::InvalidInput);
        }
        let suffix = match action {
            TaskAction::Cancel => "/cancel",
            TaskAction::Suspend => "/suspend",
            TaskAction::Resume => "/resume",
            TaskAction::Input => "/input",
        };
        let mut body = json!({"projectId":project,"sessionId":session,"expectedRunId":run,"expectedRevision":revision});
        if action == TaskAction::Input {
            let message = message.ok_or(AppError::InvalidInput)?;
            task_message(message)?;
            body["message"] = json!(message);
        } else if message.is_some() {
            return Err(AppError::InvalidInput);
        }
        let task: ManagedTask = Self::task_json(
            self.request(Method::POST, &task_path(id, suffix)?, true)?
                .json(&body),
            true,
        )
        .await?;
        task.owned(project, session, Some(id))?;
        Ok(task)
    }
    fn task_owner(project: &str, session: Option<&str>) -> Result<()> {
        TaskQuery::new(
            Some(project.into()),
            session.map(str::to_owned),
            Some(1),
            None,
        )?;
        Ok(())
    }
}
fn task_message(message: &str) -> Result<()> {
    if message.trim().is_empty() || message.encode_utf16().count() > 16384 {
        return Err(AppError::InvalidInput);
    }
    Ok(())
}
fn task_path(id: &str, suffix: &str) -> Result<String> {
    if !task_id(id) {
        return Err(AppError::InvalidInput);
    }
    let mut url =
        url::Url::parse("http://localhost/api/v1/tasks").map_err(|_| AppError::InvalidInput)?;
    url.path_segments_mut()
        .map_err(|_| AppError::InvalidInput)?
        .push(id);
    Ok(format!("{}{suffix}", url.path()))
}
