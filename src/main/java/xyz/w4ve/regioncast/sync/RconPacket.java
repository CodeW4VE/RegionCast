package xyz.w4ve.regioncast.sync;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * One packet of the Source RCON protocol, split out from the socket so the framing can be tested.
 *
 * <p>Wire format, all integers little endian: length, request id, type, the body as a null
 * terminated string, and one more null byte. The length does not count itself.
 *
 * @param requestId echoed back by the server, and set to -1 when authentication fails
 * @param type see the constants on this class
 * @param body the command, or the server's reply
 */
public record RconPacket(int requestId, int type, String body) {
	/** Client asks to log in. */
	public static final int TYPE_AUTH = 3;
	/** Client runs a command. Also the type of a successful auth reply. */
	public static final int TYPE_COMMAND = 2;
	/** Server replies to a command. */
	public static final int TYPE_RESPONSE = 0;
	/** Request id the server sends back when the password was wrong. */
	public static final int AUTH_FAILED = -1;

	/** Bytes of framing around the body: two ints plus two null terminators. */
	private static final int OVERHEAD = 10;
	/** Refuse anything larger rather than trying to allocate it. */
	private static final int MAX_LENGTH = 4 * 1024 * 1024;

	public byte[] encode() {
		byte[] payload = body.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buffer = ByteBuffer.allocate(payload.length + OVERHEAD + 4)
				.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(payload.length + OVERHEAD);
		buffer.putInt(requestId);
		buffer.putInt(type);
		buffer.put(payload);
		buffer.put((byte) 0);
		buffer.put((byte) 0);
		return buffer.array();
	}

	/** Reads one packet off a stream, blocking until it is complete. */
	public static RconPacket read(InputStream in) throws IOException {
		DataInputStream data = new DataInputStream(in);
		int length = Integer.reverseBytes(data.readInt());
		if (length < OVERHEAD - 2 || length > MAX_LENGTH) {
			throw new IOException("bogus RCON packet length: " + length);
		}
		byte[] rest = new byte[length];
		data.readFully(rest);
		ByteBuffer buffer = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
		int requestId = buffer.getInt();
		int type = buffer.getInt();
		// The body runs to the first null. Trailing padding varies between server implementations.
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		while (buffer.hasRemaining()) {
			byte b = buffer.get();
			if (b == 0) {
				break;
			}
			payload.write(b);
		}
		return new RconPacket(requestId, type, payload.toString(StandardCharsets.UTF_8));
	}

	/** True when this is the server refusing our password. */
	public boolean isAuthFailure() {
		return requestId == AUTH_FAILED;
	}
}
