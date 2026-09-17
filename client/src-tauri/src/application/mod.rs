mod activity;
mod conversation_service;
pub use activity::*;
mod projection;
mod run_manager;
mod service;
mod session_history;
mod sse;
pub use conversation_service::*;
pub use projection::*;
pub use run_manager::*;
pub use service::*;
pub use session_history::*;
pub use sse::*;

pub fn backoff(attempt: usize) -> u64 {
    [1, 2, 4, 8, 15, 30][attempt.min(5)]
}
