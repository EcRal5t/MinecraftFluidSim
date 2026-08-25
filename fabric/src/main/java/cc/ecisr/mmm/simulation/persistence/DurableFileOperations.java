package cc.ecisr.mmm.simulation.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Durability barriers for atomic directory-entry publication where the host JDK exposes them. */
public final class DurableFileOperations {
	private DurableFileOperations() { }

	/** Windows does not expose directory channels through the JDK; file content is forced separately. */
	public static void forceDirectory(Path directory) throws IOException {
		if (!Files.isDirectory(directory) || isWindows()) return;
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (UnsupportedOperationException unsupported) {
			// The JDK has no portable directory-fsync API.
		}
	}

	public static void forceParent(Path path) throws IOException {
		Path parent = path == null ? null : path.toAbsolutePath().normalize().getParent();
		if (parent != null) forceDirectory(parent);
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}
}
