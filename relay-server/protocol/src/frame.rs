//! Framing for the PodRelay wire protocol.
//!
//! `[u32 len][u8 typ][u32 req][payload]`, all big endian. `len` counts only the
//! payload. The decoder never allocates more than `MAX_FRAME` per frame.

use crate::error::{Error, Result};

pub const MAX_FRAME: usize = 4 * 1024 * 1024;
const HEADER: usize = 9;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub typ: u8,
    pub req: u32,
    pub payload: Vec<u8>,
}

impl Frame {
    pub fn new(typ: u8, req: u32, payload: Vec<u8>) -> Self {
        Frame { typ, req, payload }
    }
}

pub fn encode(frame: &Frame) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER + frame.payload.len());
    out.extend_from_slice(&(frame.payload.len() as u32).to_be_bytes());
    out.push(frame.typ);
    out.extend_from_slice(&frame.req.to_be_bytes());
    out.extend_from_slice(&frame.payload);
    out
}

#[derive(Default)]
pub struct FrameDecoder {
    buf: Vec<u8>,
}

impl FrameDecoder {
    pub fn new() -> Self {
        Self { buf: Vec::new() }
    }

    pub fn buffered(&self) -> usize {
        self.buf.len()
    }

    pub fn push(&mut self, data: &[u8]) -> Result<()> {
        if self.buf.len() + data.len() > MAX_FRAME * 2 {
            return Err(Error::Protocol("frame buffer overflow".into()));
        }
        self.buf.extend_from_slice(data);
        Ok(())
    }

    /// Pops one complete frame, or `None` when more bytes are needed.
    pub fn next(&mut self) -> Result<Option<Frame>> {
        if self.buf.len() < HEADER {
            return Ok(None);
        }
        let len = u32::from_be_bytes([self.buf[0], self.buf[1], self.buf[2], self.buf[3]]) as usize;
        if len > MAX_FRAME {
            return Err(Error::Protocol(format!("frame too large: {len}")));
        }
        if self.buf.len() < HEADER + len {
            return Ok(None);
        }
        let typ = self.buf[4];
        let req = u32::from_be_bytes([self.buf[5], self.buf[6], self.buf[7], self.buf[8]]);
        let payload = self.buf[HEADER..HEADER + len].to_vec();
        self.buf.drain(..HEADER + len);
        Ok(Some(Frame { typ, req, payload }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_split() {
        let frame = Frame::new(0x23, 7, vec![1, 2, 3, 4, 5]);
        let bytes = encode(&frame);
        let mut dec = FrameDecoder::new();
        dec.push(&bytes[..4]).unwrap();
        assert!(dec.next().unwrap().is_none());
        dec.push(&bytes[4..]).unwrap();
        assert_eq!(dec.next().unwrap().unwrap(), frame);
        assert!(dec.next().unwrap().is_none());
    }

    #[test]
    fn rejects_huge() {
        let mut bytes = vec![0xff, 0xff, 0xff, 0xff, 0x01];
        bytes.extend_from_slice(&[0, 0, 0, 0]);
        let mut dec = FrameDecoder::new();
        dec.push(&bytes).unwrap();
        assert!(dec.next().is_err());
    }
}
