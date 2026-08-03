package xyz.w4ve.regioncast.sync;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;

/**
 * Copying a region file the careful way, used both when pulling regions in and when putting a
 * backup back.
 *
 * <p>Two things it does that a plain copy does not:
 *
 * <ul>
 *   <li>Writes to a temporary file next to the target and then moves it into place, so a crash
 *       half way through leaves the old file intact rather than a truncated one.
 *   <li>Carries a timestamp over to the copy. For an incoming region that is what lets a later
 *       scan tell "the source changed again" apart from "we just copied this"; for a restore it
 *       is what puts the file back exactly as it was.
 * </ul>
 */
final class AtomicCopy {
	/** Suffix for the half written file. Not {@code .mca}, so a stray one is never loaded. */
	static final String TEMP_SUFFIX = ".regioncast-tmp";

	private AtomicCopy() {
	}

	/** Copies keeping the source's own timestamp. */
	static void copy(Path from, Path to) throws IOException {
		copy(from, to, Files.getLastModifiedTime(from));
	}

	/** Copies and stamps the result with the given time, whatever the source file says. */
	static void copy(Path from, Path to, FileTime stamp) throws IOException {
		Path parent = to.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temp = to.resolveSibling(to.getFileName() + TEMP_SUFFIX);
		try {
			Files.copy(from, temp,
					StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.COPY_ATTRIBUTES);
			// COPY_ATTRIBUTES is best effort across file systems, so set the time explicitly too.
			// Without it the ledger has nothing meaningful to compare against.
			Files.setLastModifiedTime(temp, stamp);
			moveIntoPlace(temp, to);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private static void moveIntoPlace(Path temp, Path to) throws IOException {
		try {
			Files.move(temp, to,
					StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			// Some bind mounted and network file systems cannot do it. Still better than writing
			// straight over the live file, since the copy is already complete by this point.
			Files.move(temp, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
