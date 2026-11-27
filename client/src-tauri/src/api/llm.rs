use super::*;
use serde::Deserialize;
use serde_json::json;

impl HttpReiClient {
    pub(super) async fn connection_endpoints(&self) -> Result<Vec<LlmEndpoint>> {
        let rows: Vec<LlmEndpoint> =
            Self::connection_json(self.request(Method::GET, "/api/v1/llm/chat", true)?).await?;
        if rows.is_empty() || rows.len() > 256 || rows.iter().filter(|r| r.active).count() != 1 {
            return Err(AppError::InvalidResponse);
        }
        let mut names = std::collections::HashSet::new();
        for row in &rows {
            row.validate()?;
            if !names.insert(&row.profile.id) {
                return Err(AppError::InvalidResponse);
            }
        }
        Ok(rows)
    }
    pub(super) async fn connection_select(&self, endpoint: &str) -> Result<LlmEndpoint> {
        if !llm_endpoint_id(endpoint) {
            return Err(AppError::InvalidInput);
        }
        let row: LlmEndpoint = Self::connection_json(
            self.request(Method::PUT, "/api/v1/llm/chat/current", true)?
                .json(&json!({"endpoint":endpoint})),
        )
        .await?;
        row.validate()?;
        if row.profile.id != endpoint || !row.active {
            return Err(AppError::Conflict);
        }
        Ok(row)
    }
    pub(super) async fn connection_models(&self) -> Result<LlmModels> {
        let models: LlmModels =
            Self::connection_json(self.request(Method::GET, "/api/v1/models", true)?).await?;
        models.validate()?;
        Ok(models)
    }
    pub(super) async fn connection_select_model(
        &self,
        endpoint: &str,
        model: &str,
    ) -> Result<LlmModels> {
        if !llm_endpoint_id(endpoint) || !llm_model_name(model) {
            return Err(AppError::InvalidInput);
        }
        #[derive(Deserialize)]
        struct Selected {
            endpoint: String,
            model: String,
        }
        let selected: Selected = Self::connection_json(
            self.request(Method::PUT, "/api/v1/models/current", true)?
                .json(&json!({"endpoint":endpoint,"model":model})),
        )
        .await?;
        if selected.endpoint != endpoint || selected.model != model {
            return Err(AppError::Conflict);
        }
        Ok(LlmModels {
            endpoint: selected.endpoint,
            current_model: Some(selected.model),
            models: vec![],
        })
    }
    async fn connection_json<T: DeserializeOwned>(request: reqwest::RequestBuilder) -> Result<T> {
        Self::bounded_resource_json(request, false)
            .await
            .map_err(|e| {
                if e == AppError::NotFound {
                    AppError::EndpointNotFound
                } else {
                    e
                }
            })
    }
}
