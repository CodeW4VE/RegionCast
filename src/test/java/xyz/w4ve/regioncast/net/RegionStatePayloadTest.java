package xyz.w4ve.regioncast.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.ChunkMask;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionStatus;
import xyz.w4ve.regioncast.sync.BackupStore;

/**
 * The state packet on the wire.
 *
 * <p>A reader that stops short of what the writer put down does not fail on its own: the client
 * decodes something plausible and then netty kills the connection over the bytes left behind. So
 * what these check is that nothing is left behind.
 */
class RegionStatePayloadTest {

	private static RegionStatePayload roundTrip(RegionStatePayload payload) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		RegionStatePayload.CODEC.encode(buf, payload);
		int written = buf.writerIndex();
		RegionStatePayload back = RegionStatePayload.CODEC.decode(buf);
		assertEquals(written, buf.readerIndex(), "reader stopped short of the writer");
		assertFalse(buf.isReadable(), buf.readableBytes() + " bytes left unread");
		return back;
	}

	@Test
	@DisplayName("an empty state leaves no bytes behind")
	void empty() {
		roundTrip(new RegionStatePayload(List.of(), List.of(), List.of(), List.of(), "", List.of()));
	}

	@Test
	@DisplayName("a full state survives the wire")
	void full() {
		RegionKey region = new RegionKey(Dim.OVERWORLD, -6, 0);
		ChunkMask copied = new ChunkMask();
		copied.set(new ChunkKey(Dim.OVERWORLD, -6 * 32 + 3, 4));
		RegionStatePayload payload = new RegionStatePayload(
				List.of(new RegionStatePayload.Entry(region, RegionStatus.STALE),
						new RegionStatePayload.Entry(new RegionKey(Dim.NETHER, 1, -2),
								RegionStatus.NEVER_SYNCED)),
				List.of(new RegionKey(Dim.END, 0, 0)),
				List.of(new ChunkKey(Dim.OVERWORLD, -180, 12)),
				List.of(new RegionStatePayload.Partial(region, copied, new ChunkMask())),
				"copied 3 deleted 0",
				List.of(new BackupStore.Snapshot("20260803-221430", 1785634564199L, 6, 12, 407000000L)),
				true);
		RegionStatePayload back = roundTrip(payload);
		assertEquals(payload.entries(), back.entries());
		assertEquals(payload.queued(), back.queued());
		assertEquals(payload.queuedChunks(), back.queuedChunks());
		assertEquals(payload.report(), back.report());
		assertEquals(payload.backups(), back.backups());
		assertEquals(payload.openMap(), back.openMap());
		assertEquals(1, back.partials().size());
		assertEquals(1, back.partials().get(0).copied().count());
	}

	@Test
	@DisplayName("a report with accents survives the wire")
	void unicodeReport() {
		roundTrip(new RegionStatePayload(List.of(), List.of(), List.of(), List.of(),
				"copiado 3 regiones — sin errores ñ", List.of()));
	}
}
