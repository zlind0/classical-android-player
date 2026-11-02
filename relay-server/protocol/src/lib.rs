//! PodRelay wire protocol (v1): framing, messages, crypto and the encrypted
//! library snapshot format. Shared by the relay server, the NAS agent and the
//! test client.

pub mod access;
pub mod crypto;
pub mod error;
pub mod file_id;
pub mod frame;
pub mod msg;
pub mod snapshot;

pub use error::{Error, Result};
pub use frame::{encode, Frame, FrameDecoder, MAX_FRAME};
pub use msg::{from_frame, to_frame, FileId, Msg};
