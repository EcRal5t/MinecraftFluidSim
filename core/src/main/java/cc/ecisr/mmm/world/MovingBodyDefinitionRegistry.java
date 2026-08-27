package cc.ecisr.mmm.world;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Immutable ordered moving-body definitions and their current datapack identity. */
public final class MovingBodyDefinitionRegistry {
	private final List<MovingBodyDefinition> definitions;
	private final String fingerprint;

	public MovingBodyDefinitionRegistry(List<MovingBodyDefinition> definitions) {
		if (definitions == null) throw new IllegalArgumentException("Moving-body definitions are missing");
		ArrayList<MovingBodyDefinition> copy = new ArrayList<>(definitions);
		copy.sort(Comparator.comparing(MovingBodyDefinition::id));
		HashSet<String> ids = new HashSet<>(), selectors = new HashSet<>();
		StringBuilder canonical = new StringBuilder();
		for (MovingBodyDefinition definition : copy) {
			if (definition == null || !ids.add(definition.id())
					|| !selectors.add(definition.selectorKind() + "\n" + definition.selectorId())) {
				throw new IllegalArgumentException("Moving-body definition id or selector is duplicated");
			}
			canonical.append(definition.canonical());
		}
		this.definitions = List.copyOf(copy);
		this.fingerprint = sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
	}

	public List<MovingBodyDefinition> definitions() { return definitions; }
	public String fingerprint() { return fingerprint; }

	private static String sha256(byte[] bytes) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
	}
}
