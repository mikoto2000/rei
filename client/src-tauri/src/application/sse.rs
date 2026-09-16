use crate::domain::*;

#[derive(Default, Debug)]
pub struct SseFrame {
    pub event: String,
    pub id: Option<String>,
    pub data: String,
}
#[derive(Default)]
pub struct SseParser {
    line: Vec<u8>,
    frame: SseFrame,
    previous_cr: bool,
    frame_bytes: usize,
}
impl SseParser {
    /// Streaming UTF-8 framing; CR, LF, CRLF and chunk boundaries are independent.
    pub fn push(&mut self, bytes: &[u8]) -> Result<Vec<SseFrame>> {
        let mut output = Vec::new();
        for &byte in bytes {
            if byte == b'\n' && self.previous_cr {
                self.previous_cr = false;
                continue;
            }
            self.previous_cr = byte == b'\r';
            if byte == b'\n' || byte == b'\r' {
                let line = String::from_utf8(std::mem::take(&mut self.line))
                    .map_err(|_| AppError::InvalidResponse)?;
                if line.is_empty() {
                    if !self.frame.data.is_empty() {
                        self.frame.data.pop();
                        output.push(std::mem::take(&mut self.frame));
                    } else {
                        self.frame = SseFrame::default();
                    }
                    self.frame_bytes = 0;
                } else {
                    let (field, value) = line.split_once(':').unwrap_or((&line, ""));
                    let value = value.strip_prefix(' ').unwrap_or(value);
                    match field {
                        "event" => self.frame.event = value.into(),
                        "id" if !value.contains('\0') => self.frame.id = Some(value.into()),
                        "data" => {
                            self.frame.data.push_str(value);
                            self.frame.data.push('\n');
                        }
                        _ => {}
                    }
                }
            } else {
                self.line.push(byte);
                self.frame_bytes += 1;
            }
            if self.frame_bytes > 2 * 1024 * 1024 {
                return Err(AppError::InvalidResponse);
            }
        }
        Ok(output)
    }
}
