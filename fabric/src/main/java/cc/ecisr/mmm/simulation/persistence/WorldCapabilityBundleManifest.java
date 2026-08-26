package cc.ecisr.mmm.simulation.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Strict inventory of the immutable files inside the committed world-capability slot. */
public record WorldCapabilityBundleManifest(
		String schema,
		long generation,
		UUID publicationTransactionId,
		String profileFingerprint,
		String aggregateFingerprint,
		List<FileEntry> files
) {
	public static final String SCHEMA = "mmm:world-capability-bundle-manifest";
	public static final List<String> REQUIRED_FILES = List.of(
			"chemical-registry.nbt",
			"gas-registry.nbt",
			"granular-registry.nbt",
			"material-registry.nbt",
			"profile.nbt",
			"thermal-registry.nbt");
	public static final long MAX_MEMBER_BYTES = 16L * 1024L * 1024L;
	public static final long MAX_TOTAL_MEMBER_BYTES = 64L * 1024L * 1024L;

	public record FileEntry(String name, long byteLength, String checksum) {
		public FileEntry {
			if (!REQUIRED_FILES.contains(name)) throw new IllegalArgumentException("Unknown bundle member " + name);
			if (byteLength <= 0L || byteLength > MAX_MEMBER_BYTES) {
				throw new IllegalArgumentException("Bundle member byte length is invalid for " + name);
			}
			requireFingerprint(checksum, "bundle member checksum");
		}
	}

	public WorldCapabilityBundleManifest {
		if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("Unknown bundle manifest schema " + schema);
		if (generation <= 0L) throw new IllegalArgumentException("Bundle generation must be positive");
		if (publicationTransactionId == null) {
			throw new IllegalArgumentException("Bundle publication transaction is missing");
		}
		requireFingerprint(profileFingerprint, "bundle profile fingerprint");
		requireFingerprint(aggregateFingerprint, "bundle aggregate fingerprint");
		if (files == null || files.size() != REQUIRED_FILES.size()) {
			throw new IllegalArgumentException("Bundle manifest file table is incomplete");
		}
		ArrayList<FileEntry> canonical = new ArrayList<>(files);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Bundle manifest file table contains null");
		}
		canonical.sort(Comparator.comparing(FileEntry::name));
		long total = 0L;
		for (int index = 0; index < canonical.size(); index++) {
			FileEntry entry = canonical.get(index);
			if (!entry.name().equals(REQUIRED_FILES.get(index))) {
				throw new IllegalArgumentException("Bundle manifest file table is not exact");
			}
			total = Math.addExact(total, entry.byteLength());
		}
		if (total > MAX_TOTAL_MEMBER_BYTES) throw new IllegalArgumentException("Bundle member bytes are over budget");
		files = List.copyOf(canonical);
	}

	public FileEntry file(String name) {
		for (FileEntry entry : files) if (entry.name().equals(name)) return entry;
		throw new IllegalArgumentException("Unknown bundle member " + name);
	}

	static void requireFingerprint(String value, String label) {
		if (value == null || !value.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid " + label);
		}
	}
}
