package xyz.w4ve.regioncast.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RconPacketTest {

	private static RconPacket roundTrip(RconPacket packet) throws IOException {
		return RconPacket.read(new ByteArrayInputStream(packet.encode()));
	}

	@Test
	@DisplayName("a packet survives encoding and decoding")
	void roundTrips() throws IOException {
		RconPacket original = new RconPacket(7, RconPacket.TYPE_COMMAND, "save-off");
		RconPacket decoded = roundTrip(original);

		assertEquals(7, decoded.requestId());
		assertEquals(RconPacket.TYPE_COMMAND, decoded.type());
		assertEquals("save-off", decoded.body());
	}

	@Test
	@DisplayName("integers go out little endian, as the protocol wants")
	void usesLittleEndian() {
		byte[] encoded = new RconPacket(1, RconPacket.TYPE_AUTH, "").encode();
		ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);

		assertEquals(10, buffer.getInt(), "length excludes itself: two ints plus two nulls");
		assertEquals(1, buffer.getInt());
		assertEquals(RconPacket.TYPE_AUTH, buffer.getInt());
		assertEquals(14, encoded.length);
	}

	@Test
	@DisplayName("the body is null terminated and the packet ends with a second null")
	void bodyIsNullTerminated() {
		byte[] encoded = new RconPacket(1, RconPacket.TYPE_COMMAND, "hi").encode();

		assertEquals((byte) 'h', encoded[12]);
		assertEquals((byte) 'i', encoded[13]);
		assertEquals(0, encoded[14]);
		assertEquals(0, encoded[15]);
	}

	@Test
	@DisplayName("an empty body is fine")
	void handlesEmptyBody() throws IOException {
		assertEquals("", roundTrip(new RconPacket(1, RconPacket.TYPE_RESPONSE, "")).body());
	}

	@Test
	@DisplayName("accents and other UTF-8 survive the trip")
	void handlesUtf8() throws IOException {
		String body = "say Actualizando región ✓";
		assertEquals(body, roundTrip(new RconPacket(1, RconPacket.TYPE_COMMAND, body)).body());
	}

	@Test
	@DisplayName("a long reply is not truncated")
	void handlesLongBody() throws IOException {
		String body = "x".repeat(3000);
		assertEquals(body, roundTrip(new RconPacket(1, RconPacket.TYPE_RESPONSE, body)).body());
	}

	@Test
	@DisplayName("request id -1 means the password was wrong")
	void detectsAuthFailure() {
		assertTrue(new RconPacket(-1, RconPacket.TYPE_COMMAND, "").isAuthFailure());
		assertFalse(new RconPacket(0, RconPacket.TYPE_COMMAND, "").isAuthFailure());
	}

	@Test
	@DisplayName("padding after the body is ignored rather than read as text")
	void ignoresTrailingPadding() throws IOException {
		// Some servers pad the packet. The body ends at the first null, whatever follows.
		ByteBuffer buffer = ByteBuffer.allocate(4 + 14).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(14);
		buffer.putInt(1);
		buffer.putInt(RconPacket.TYPE_RESPONSE);
		buffer.put("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		buffer.put(new byte[] {0, 0, 'j', 'u'});

		RconPacket decoded = RconPacket.read(new ByteArrayInputStream(buffer.array()));

		assertEquals("ok", decoded.body());
	}

	@Test
	@DisplayName("a nonsense length is refused instead of allocating it")
	void rejectsBogusLength() {
		ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(Integer.MAX_VALUE);
		buffer.putInt(0);

		assertThrows(IOException.class,
				() -> RconPacket.read(new ByteArrayInputStream(buffer.array())));
	}

	@Test
	@DisplayName("a truncated packet fails instead of returning half a reply")
	void rejectsTruncatedPacket() {
		byte[] full = new RconPacket(1, RconPacket.TYPE_RESPONSE, "hello").encode();
		byte[] cut = java.util.Arrays.copyOf(full, full.length - 3);

		assertThrows(IOException.class, () -> RconPacket.read(new ByteArrayInputStream(cut)));
	}
}
