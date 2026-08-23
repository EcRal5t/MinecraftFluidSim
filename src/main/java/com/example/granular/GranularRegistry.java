package com.example.granular;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, canonically ordered M8 material and carrier registry. */
public final class GranularRegistry {
	private final List<ParticulateMaterial> materials;
	private final List<GranularCarrier> carriers;
	private final Map<String, Integer> materialHandles;
	private final Map<String, Integer> carrierHandles;
	private final byte[] canonicalBytes;
	private final String fingerprint;

	public GranularRegistry(List<ParticulateMaterial> materials, List<GranularCarrier> carriers) {
		this.materials = canonicalUnique(materials, GranularLimits.MAX_MATERIALS,
				ParticulateMaterial::id, "particulate material");
		this.carriers = canonicalUnique(carriers, GranularLimits.MAX_CARRIERS,
				GranularCarrier::liquidMaterialId, "granular carrier");
		this.materialHandles = handles(this.materials, ParticulateMaterial::id);
		this.carrierHandles = handles(this.carriers, GranularCarrier::liquidMaterialId);
		this.canonicalBytes = encodeCanonical();
		this.fingerprint = sha256(canonicalBytes);
	}

	public List<ParticulateMaterial> materials() { return materials; }
	public List<GranularCarrier> carriers() { return carriers; }
	public ParticulateMaterial material(int handle) { return materials.get(requireHandle(handle, materials.size(), "material")); }
	public GranularCarrier carrier(int handle) { return carriers.get(requireHandle(handle, carriers.size(), "carrier")); }
	public int materialHandle(String id) { return requireStableHandle(materialHandles, id, "particulate material"); }
	public int carrierHandle(String liquidMaterialId) { return requireStableHandle(carrierHandles, liquidMaterialId, "carrier"); }
	public boolean hasCarrier(String liquidMaterialId) { return carrierHandles.containsKey(liquidMaterialId); }
	public byte[] canonicalBytes() { return canonicalBytes.clone(); }
	public String fingerprint() { return fingerprint; }

	private byte[] encodeCanonical() {
		StringBuilder out = new StringBuilder(2048).append("realwater-granular-registry-v1\n")
				.append("svq-per-bv=").append(SolidVolume.SVQ_PER_BV).append('\n')
				.append("porosity-scale=").append(GranularLimits.POROSITY_SCALE).append('\n');
		for (ParticulateMaterial material : materials) {
			out.append("M|").append(material.id()).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.referenceDensity()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.primaryGrainDiameterMeters()))).append('|')
					.append(material.restPorosityQ()).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.reposeAngleRadians()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.staticFriction()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.dynamicFriction()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.restitution()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.criticalShields()))).append('|')
					.append(material.erosionRateSvqPerSecond()).append('|').append(material.maximumActiveParcelSvq()).append('|')
					.append(Integer.toUnsignedString(material.baseArgb())).append('|').append(material.displayTextureId()).append('|')
					.append(material.reactive()).append('\n');
		}
		for (GranularCarrier carrier : carriers) {
			out.append("C|").append(carrier.liquidMaterialId()).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(carrier.dynamicViscosity()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(carrier.maximumReynolds()))).append('\n');
		}
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static int requireHandle(int handle, int size, String label) {
		if (handle < 0 || handle >= size) throw new IllegalArgumentException("Unknown granular " + label + " handle " + handle);
		return handle;
	}

	private static int requireStableHandle(Map<String, Integer> handles, String id, String label) {
		Integer handle = handles.get(id);
		if (handle == null) throw new IllegalArgumentException("Unknown " + label + " " + id);
		return handle;
	}

	private static <T> List<T> canonicalUnique(
			List<T> definitions, int maximum, java.util.function.Function<T, String> id, String label
	) {
		if (definitions == null || definitions.isEmpty() || definitions.size() > maximum) {
			throw new IllegalArgumentException(label + " definitions are empty or over budget");
		}
		ArrayList<T> canonical = new ArrayList<>(definitions);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException(label + " contains null");
		canonical.sort(Comparator.comparing(id));
		for (int index = 1; index < canonical.size(); index++) {
			if (id.apply(canonical.get(index - 1)).equals(id.apply(canonical.get(index)))) {
				throw new IllegalArgumentException("Duplicate " + label + " id " + id.apply(canonical.get(index)));
			}
		}
		return List.copyOf(canonical);
	}

	private static <T> Map<String, Integer> handles(List<T> values, java.util.function.Function<T, String> id) {
		LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
		for (int index = 0; index < values.size(); index++) result.put(id.apply(values.get(index)), index);
		return Collections.unmodifiableMap(result);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}
}
