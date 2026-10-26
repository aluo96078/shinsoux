package dev.shinsou.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * S0 stand-in for SandboxProtocol v1 framing (ADR-0002 §4.2): u32 BE length, then
 * type(u8) flags(u8) id(u32) payload. A logical message larger than {@link #MAX_FRAME} is split
 * into frames with the MORE flag; the reader reassembles up to a caller-supplied total cap and
 * fails closed. The production codec is CBOR in commonMain; only the stream behaviour matters here.
 */
public final class FrameCodec {
    public static final int MAX_FRAME = 1 << 20;
    public static final int HEADER = 6;
    public static final int FLAG_MORE = 1;

    public static final class Message {
        public final int type, id;
        public final byte[] payload;

        public Message(int type, int id, byte[] payload) {
            this.type = type; this.id = id; this.payload = payload;
        }
    }

    public static final class ProtocolException extends IOException {
        public ProtocolException(String m) { super(m); }
    }

    private FrameCodec() {}

    public static void write(OutputStream out, int type, int id, byte[] payload) throws IOException {
        int max = MAX_FRAME - HEADER, off = 0;
        do {
            int n = Math.min(max, payload.length - off);
            boolean more = off + n < payload.length;
            int len = HEADER + n;
            byte[] h = {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len,
                    (byte) type, (byte) (more ? FLAG_MORE : 0),
                    (byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id};
            out.write(h);
            out.write(payload, off, n);
            off += n;
        } while (off < payload.length);
        out.flush();
    }

    /** Returns null on clean EOF at a frame boundary. */
    public static Message read(InputStream in, long maxTotal) throws IOException {
        DataInputStream d = new DataInputStream(in);
        ByteArrayOutputStream acc = null;
        int type = -1, id = 0;
        while (true) {
            int len;
            try {
                len = d.readInt();
            } catch (EOFException e) {
                if (acc == null) return null;
                throw new ProtocolException("truncated message");
            }
            if (len < HEADER || len > MAX_FRAME) throw new ProtocolException("bad frame length " + len);
            int t = d.readUnsignedByte(), flags = d.readUnsignedByte(), fid = d.readInt();
            if (acc == null) { type = t; id = fid; acc = new ByteArrayOutputStream(); }
            else if (t != type || fid != id) throw new ProtocolException("interleaved chunk");
            int n = len - HEADER;
            if (acc.size() + (long) n > maxTotal) throw new ProtocolException("message over limit");
            byte[] buf = new byte[n];
            d.readFully(buf);
            acc.write(buf);
            if ((flags & FLAG_MORE) == 0) return new Message(type, id, acc.toByteArray());
        }
    }
}
