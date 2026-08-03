package xyz.w4ve.regioncast.sync;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A small RCON client, used for one job: telling the source server to hold still while we copy.
 *
 * <p>Without this the source may be halfway through writing a region file when we read it, and
 * the copy that lands on the mirror is a region with torn chunks in it. {@code save-off} plus
 * {@code save-all flush} makes the source finish what it was doing and stop, which turns the copy
 * from probably fine into actually safe.
 *
 * <p>Deliberately not a long lived connection: it opens, does its business and closes.
 */
public final class RconClient implements AutoCloseable {
	private static final int REQUEST_ID = 0x5243; // "RC", echoed back so replies can be matched.

	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;

	private RconClient(Socket socket) throws IOException {
		this.socket = socket;
		this.in = new BufferedInputStream(socket.getInputStream());
		this.out = socket.getOutputStream();
	}

	/**
	 * Connects and logs in.
	 *
	 * @throws IOException if the server is unreachable, slow to answer, or rejects the password
	 */
	public static RconClient connect(String host, int port, String password, int timeoutMillis)
			throws IOException {
		Socket socket = new Socket();
		socket.connect(new InetSocketAddress(host, port), timeoutMillis);
		socket.setSoTimeout(timeoutMillis);
		RconClient client = new RconClient(socket);
		try {
			client.authenticate(password);
		} catch (IOException e) {
			client.close();
			throw e;
		}
		return client;
	}

	private void authenticate(String password) throws IOException {
		send(new RconPacket(REQUEST_ID, RconPacket.TYPE_AUTH, password));
		RconPacket reply = RconPacket.read(in);
		// Some servers send an empty RESPONSE_VALUE before the auth answer. Skip it.
		if (reply.type() == RconPacket.TYPE_RESPONSE) {
			reply = RconPacket.read(in);
		}
		if (reply.isAuthFailure()) {
			throw new IOException("RCON password rejected");
		}
	}

	/** Runs one command and returns whatever the server said about it. */
	public String command(String command) throws IOException {
		send(new RconPacket(REQUEST_ID, RconPacket.TYPE_COMMAND, command));
		return RconPacket.read(in).body();
	}

	private void send(RconPacket packet) throws IOException {
		out.write(packet.encode());
		out.flush();
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (IOException ignored) {
			// Closing a socket we are done with: nothing useful to do about a failure here.
		}
	}
}
