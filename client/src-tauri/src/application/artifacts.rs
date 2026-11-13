use super::Application;
use crate::domain::*;
use serde::Serialize;
use std::io::{Read, Write};
use std::path::Path;
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtifactPreview {
    pub artifact: DeliveryArtifact,
    pub text: Option<String>,
    pub data_url: Option<String>,
}
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtifactSaveReceipt {
    pub path: String,
    pub size: u64,
    pub sha256: String,
}
impl Application {
    pub async fn artifact_get(
        &self,
        server: &str,
        project: &str,
        session: Option<&str>,
        id: &str,
    ) -> Result<DeliveryArtifact> {
        let item = self
            .api(server, true)?
            .get_artifact(project, session, id)
            .await?;
        item.owned(project, session, id)?;
        Ok(item)
    }
    pub async fn artifacts_list(
        &self,
        server: &str,
        project: Option<&str>,
        session: Option<&str>,
        run: Option<&str>,
        limit: Option<i32>,
        cursor: Option<String>,
    ) -> Result<ArtifactPage> {
        let query = ArtifactQuery::new(
            project.map(str::to_owned),
            session.map(str::to_owned),
            run.map(str::to_owned),
            limit,
            cursor,
        )?;
        let page = self
            .api(server, true)?
            .list_artifacts(query.clone())
            .await?;
        page.validate(&query)?;
        Ok(page)
    }
    pub async fn artifact_preview(
        &self,
        server: &str,
        project: &str,
        session: Option<&str>,
        id: &str,
    ) -> Result<ArtifactPreview> {
        let api = self.api(server, true)?;
        let item = api.get_artifact(project, session, id).await?;
        item.owned(project, session, id)?;
        if item.status != "AVAILABLE" {
            return Err(AppError::Conflict);
        }
        if item.size > 2 * 1024 * 1024 {
            return Err(AppError::InvalidInput);
        }
        let bytes = api.artifact_content(&item).await?;
        item.content_valid(&bytes)?;
        if matches!(
            item.media_type.as_str(),
            "text/plain" | "text/markdown" | "application/json"
        ) {
            let text = String::from_utf8(bytes).map_err(|_| AppError::InvalidResponse)?;
            return Ok(ArtifactPreview {
                artifact: item,
                text: Some(text),
                data_url: None,
            });
        }
        if matches!(item.media_type.as_str(), "image/png" | "image/jpeg") {
            image_bounds(&bytes, &item.media_type)?;
            use base64::Engine;
            let data = format!(
                "data:{};base64,{}",
                item.media_type,
                base64::engine::general_purpose::STANDARD.encode(bytes)
            );
            return Ok(ArtifactPreview {
                artifact: item,
                text: None,
                data_url: Some(data),
            });
        }
        Err(AppError::InvalidInput)
    }
    /// The directory is supplied by the native OS adapter, never by a web command argument.
    pub async fn artifact_save(
        &self,
        server: &str,
        project: &str,
        session: Option<&str>,
        id: &str,
        downloads: &Path,
    ) -> Result<ArtifactSaveReceipt> {
        let api = self.api(server, true)?;
        let item = api.get_artifact(project, session, id).await?;
        item.owned(project, session, id)?;
        let bytes = api.artifact_content(&item).await?;
        item.content_valid(&bytes)?;
        if !downloads.is_absolute() {
            return Err(AppError::InvalidInput);
        }
        no_links(downloads)?;
        let directory = downloads.join("Rei").join(&item.artifact_id);
        no_links(&directory)?;
        std::fs::create_dir_all(&directory).map_err(|_| AppError::Storage)?;
        no_links(&directory)?;
        #[cfg(windows)]
        let filename = windows_save_name(&item.filename);
        #[cfg(not(windows))]
        let filename = &item.filename;
        let file = directory.join(filename);
        no_links(&file)?;
        if file.exists() {
            let metadata = std::fs::metadata(&file).map_err(|_| AppError::Storage)?;
            if !metadata.is_file() || metadata.len() != item.size {
                return Err(AppError::Conflict);
            }
            let mut current = Vec::with_capacity(item.size as usize);
            std::fs::File::open(&file)
                .map_err(|_| AppError::Storage)?
                .take(item.size + 1)
                .read_to_end(&mut current)
                .map_err(|_| AppError::Storage)?;
            if item.content_valid(&current).is_err() {
                return Err(AppError::Conflict);
            }
        } else {
            let mut temporary =
                tempfile::NamedTempFile::new_in(&directory).map_err(|_| AppError::Storage)?;
            temporary.write_all(&bytes).map_err(|_| AppError::Storage)?;
            temporary
                .as_file()
                .sync_all()
                .map_err(|_| AppError::Storage)?;
            no_links(&directory)?;
            temporary
                .persist_noclobber(&file)
                .map_err(|_| AppError::Conflict)?;
        }
        Ok(ArtifactSaveReceipt {
            path: file.to_string_lossy().into_owned(),
            size: item.size,
            sha256: item.sha256,
        })
    }
}

#[cfg(any(test, windows))]
fn windows_save_name(filename: &str) -> String {
    let sanitized: String = filename
        .chars()
        .map(|character| match character {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            character => character,
        })
        .collect();
    let name = sanitized.trim_end_matches([' ', '.']);
    if name.is_empty() {
        return "artifact".into();
    }
    let stem = name
        .split('.')
        .next()
        .unwrap_or_default()
        .trim_end_matches(' ');
    let stem = stem.to_ascii_uppercase();
    let reserved = matches!(
        stem.as_str(),
        "CON" | "PRN" | "AUX" | "NUL" | "CONIN$" | "CONOUT$"
    ) || stem
        .strip_prefix("COM")
        .or_else(|| stem.strip_prefix("LPT"))
        .is_some_and(|suffix| {
            matches!(
                suffix,
                "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" | "¹" | "²" | "³"
            )
        });
    if reserved {
        format!("_{name}")
    } else {
        name.into()
    }
}

#[cfg(test)]
mod windows_filename_tests {
    use super::windows_save_name;

    #[test]
    fn windows_save_names_handle_invalid_characters_device_names_and_suffixes() {
        for (original, expected) in [
            ("report?.txt", "report_.txt"),
            ("report.txt. ", "report.txt"),
            ("nul.log.txt", "_nul.log.txt"),
            ("aux .txt", "_aux .txt"),
            ("LPT²", "_LPT²"),
            ("COM10.txt", "COM10.txt"),
            ("...", "artifact"),
            ("報告書.md", "報告書.md"),
        ] {
            assert_eq!(windows_save_name(original), expected);
        }
    }
}
fn no_links(path: &Path) -> Result<()> {
    for part in path.ancestors() {
        match std::fs::symlink_metadata(part) {
            Ok(metadata) => {
                if metadata.file_type().is_symlink() {
                    return Err(AppError::InvalidInput);
                }
            }
            Err(error) => {
                if error.kind() != std::io::ErrorKind::NotFound {
                    return Err(AppError::Storage);
                }
            }
        }
    }
    Ok(())
}
fn image_bounds(bytes: &[u8], media: &str) -> Result<()> {
    let dimensions = if media == "image/png" {
        if bytes.len() < 33
            || bytes[..8] != [137, 80, 78, 71, 13, 10, 26, 10]
            || &bytes[12..16] != b"IHDR"
        {
            return Err(AppError::InvalidResponse);
        }
        Some((
            u32::from_be_bytes(bytes[16..20].try_into().unwrap()),
            u32::from_be_bytes(bytes[20..24].try_into().unwrap()),
        ))
    } else {
        if !bytes.starts_with(&[255, 216]) {
            return Err(AppError::InvalidResponse);
        }
        let mut cursor = 2;
        let mut dimensions = None;
        while cursor + 4 <= bytes.len() {
            if bytes[cursor] != 255 {
                break;
            }
            let marker = bytes[cursor + 1];
            if marker == 255 {
                cursor += 1;
                continue;
            }
            let length = u16::from_be_bytes([bytes[cursor + 2], bytes[cursor + 3]]) as usize;
            if length < 2 || cursor + length + 2 > bytes.len() {
                break;
            }
            if matches!(
                marker,
                0xC0 | 0xC1
                    | 0xC2
                    | 0xC3
                    | 0xC5
                    | 0xC6
                    | 0xC7
                    | 0xC9
                    | 0xCA
                    | 0xCB
                    | 0xCD
                    | 0xCE
                    | 0xCF
            ) && length >= 7
            {
                dimensions = Some((
                    u16::from_be_bytes([bytes[cursor + 7], bytes[cursor + 8]]) as u32,
                    u16::from_be_bytes([bytes[cursor + 5], bytes[cursor + 6]]) as u32,
                ));
                break;
            }
            cursor += length + 2;
        }
        dimensions
    };
    let (width, height) = dimensions.ok_or(AppError::InvalidResponse)?;
    if width == 0
        || height == 0
        || width > 8192
        || height > 8192
        || u64::from(width) * u64::from(height) > 16 * 1024 * 1024
    {
        return Err(AppError::InvalidResponse);
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn preview_rejects_truncation_zero_dimensions_and_pixel_bombs() {
        let mut png = vec![0u8; 33];
        png[..8].copy_from_slice(&[137, 80, 78, 71, 13, 10, 26, 10]);
        png[12..16].copy_from_slice(b"IHDR");
        png[16..20].copy_from_slice(&1u32.to_be_bytes());
        png[20..24].copy_from_slice(&1u32.to_be_bytes());
        assert!(image_bounds(&png, "image/png").is_ok());
        assert!(image_bounds(&png[..20], "image/png").is_err());
        png[16..20].copy_from_slice(&0u32.to_be_bytes());
        assert!(image_bounds(&png, "image/png").is_err());
        png[16..20].copy_from_slice(&8192u32.to_be_bytes());
        png[20..24].copy_from_slice(&8192u32.to_be_bytes());
        assert!(image_bounds(&png, "image/png").is_err());
        assert!(image_bounds(&[255, 216, 255, 192, 0, 7], "image/jpeg").is_err());
        assert!(image_bounds(&[255, 216, 255, 192, 0, 7, 8, 0, 1, 0, 1], "image/jpeg").is_ok());
    }
}
