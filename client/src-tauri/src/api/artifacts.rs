use super::*;
impl HttpReiClient {
    pub(super) async fn artifacts_list(&self, query: ArtifactQuery) -> Result<ArtifactPage> {
        let mut request = self.history_request("/api/v1/artifacts", &query.page)?;
        if let Some(project) = &query.project_id {
            request = request.query(&[("projectId", project)]);
        }
        if let Some(session) = &query.session_id {
            request = request.query(&[("sessionId", session)]);
        }
        if let Some(run) = &query.run_id {
            request = request.query(&[("runId", run)]);
        }
        let page: ArtifactPage =
            Self::bounded_resource_json(request, false)
                .await
                .map_err(|error| {
                    if error == AppError::NotFound {
                        AppError::EndpointNotFound
                    } else {
                        error
                    }
                })?;
        page.validate(&query)?;
        Ok(page)
    }
    pub(super) async fn artifact_get(
        &self,
        project: &str,
        session: Option<&str>,
        id: &str,
    ) -> Result<DeliveryArtifact> {
        let item: DeliveryArtifact =
            Self::bounded_resource_json(self.artifact_request(project, session, id, "")?, false)
                .await?;
        item.owned(project, session, id)?;
        Ok(item)
    }
    pub(super) async fn artifact_bytes(&self, item: &DeliveryArtifact) -> Result<Vec<u8>> {
        item.validate()?;
        if item.status != "AVAILABLE" {
            return Err(AppError::Conflict);
        }
        let response = Self::checked(
            self.artifact_request(
                &item.project_id,
                item.session_id.as_deref(),
                &item.artifact_id,
                "content",
            )?
            .timeout(Duration::from_secs(30)),
            Operation::Resource,
        )
        .await?;
        if response.status() != reqwest::StatusCode::OK
            || response
                .headers()
                .get("content-type")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.split(';').next())
                .map(str::trim)
                != Some(item.media_type.as_str())
            || response
                .content_length()
                .is_some_and(|length| length != item.size)
        {
            return Err(AppError::InvalidResponse);
        }
        let mut stream = response.bytes_stream();
        let mut bytes = Vec::new();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|error| {
                if error.is_timeout() {
                    AppError::RequestTimeout
                } else {
                    AppError::InvalidResponse
                }
            })?;
            if bytes.len() as u64 + chunk.len() as u64 > item.size {
                return Err(AppError::InvalidResponse);
            }
            bytes.extend_from_slice(&chunk);
        }
        item.content_valid(&bytes)?;
        Ok(bytes)
    }
    fn artifact_request(
        &self,
        project: &str,
        session: Option<&str>,
        id: &str,
        suffix: &str,
    ) -> Result<reqwest::RequestBuilder> {
        if project.is_empty()
            || project.len() > 128
            || session.is_some_and(|s| s.is_empty() || s.len() > 1024)
            || !artifact_id(id)
        {
            return Err(AppError::InvalidInput);
        }
        let path = if suffix.is_empty() {
            format!("/api/v1/artifacts/{id}")
        } else {
            format!("/api/v1/artifacts/{id}/{suffix}")
        };
        let mut request = self
            .request(Method::GET, &path, true)?
            .query(&[("projectId", project)]);
        if let Some(session) = session {
            request = request.query(&[("sessionId", session)]);
        }
        Ok(request)
    }
}
