package cc.ecisr.mmm.simulation.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
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

	/** Creates every missing directory and publishes each new directory entry durably where supported. */
	public static void createDirectories(Path directory) throws IOException {
		if (directory == null) throw new IllegalArgumentException("Directory is missing");
		Path normalized = directory.toAbsolutePath().normalize();
		ArrayList<Path> missing = new ArrayList<>();
		for (Path current = normalized; current != null && !Files.exists(current); current = current.getParent()) {
			missing.add(current);
		}
		Files.createDirectories(normalized);
		Collections.reverse(missing);
		for (Path created : missing) forceParent(created);
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}
}
