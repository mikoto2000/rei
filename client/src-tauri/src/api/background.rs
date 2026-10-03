use super::*;
use serde_json::json;
impl HttpReiClient {
    pub(super) async fn submit_background(
        &self,
        project: &str,
        op: BackgroundOperation,
    ) -> Result<BackgroundReceipt> {
        if project.trim().is_empty() {
            return Err(AppError::InvalidInput);
        }
        let (path, body) = match op {
            BackgroundOperation::Summary { url } => {
                let parsed = url::Url::parse(&url).map_err(|_| AppError::InvalidInput)?;
                if url.chars().count() > 4096
                    || !matches!(parsed.scheme(), "http" | "https")
                    || parsed.host_str().is_none()
                    || !parsed.username().is_empty()
                    || parsed.password().is_some()
                {
                    return Err(AppError::InvalidInput);
                }
                ("/api/v1/summaries", json!({"projectId":project,"url":url}))
            }
            BackgroundOperation::Image { prompt, size } => {
                if prompt.trim().is_empty() || prompt.chars().count() > 10000 {
                    return Err(AppError::InvalidInput);
                }
                if let Some(size) = &size {
                    let parts: Vec<_> = size.split('x').collect();
                    if parts.len() != 2
                        || parts
                            .iter()
                            .any(|p| p.parse::<i32>().map_or(true, |n| n <= 0))
                    {
                        return Err(AppError::InvalidInput);
                    }
                }
                (
                    "/api/v1/images",
                    json!({"projectId":project,"prompt":prompt,"size":size}),
                )
            }
        };
        let response = Self::checked(
            self.request(Method::POST, path, true)?
                .json(&body)
                .timeout(Duration::from_secs(30)),
            Operation::Resource,
        )
        .await
        .map_err(|e| {
            if e == AppError::NotFound {
                AppError::ProjectNotFound
            } else {
                e
            }
        })?;
        if response.status() != reqwest::StatusCode::ACCEPTED {
            return Err(AppError::InvalidResponse);
        }
        let location = response
            .headers()
            .get("location")
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);
        let receipt: BackgroundReceipt = response
            .json()
            .await
            .map_err(|_| AppError::InvalidResponse)?;
        let expected =
            Self::run_path(&receipt.run_id, "").map_err(|_| AppError::InvalidResponse)?;
        if location.as_deref() != Some(expected.as_str()) {
            return Err(AppError::InvalidResponse);
        }
        Ok(receipt)
    }
}
