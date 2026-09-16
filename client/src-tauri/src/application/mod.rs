mod projection;
mod run_manager;
mod sse;
pub use projection::*;
pub use run_manager::*;
pub use sse::*;

pub fn backoff(attempt: usize) -> u64 {
    [1, 2, 4, 8, 15, 30][attempt.min(5)]
}
