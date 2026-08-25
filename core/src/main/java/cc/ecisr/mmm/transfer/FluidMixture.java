package cc.ecisr.mmm.transfer;

import cc.ecisr.mmm.api.StableId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Canonical container inventory: stable material id plus independent nanovolume and mass per component. */
public record FluidMixture(List<Component> components) {
	public static final int MAX_COMPONENTS = 64;
	public static final int MAX_ENCODED_LENGTH = 8192;
	private static final String ENCODING_TAG = "mmm:fluid-mixture;";

	public FluidMixture {
		if (components == null || components.isEmpty()) {
			throw new IllegalArgumentException("Fluid mixture must contain at least one component");
		}
		if (components.size() > MAX_COMPONENTS) {
			throw new IllegalArgumentException("Fluid mixture contains too many components");
		}
		List<Component> canonical = new ArrayList<>(components);
		canonical.sort(Comparator.comparing(Component::materialId));
		Set<String> ids = new HashSet<>();
		long totalVolume = 0L;
		double totalMass = 0.0;
		for (Component component : canonical) {
			if (!ids.add(component.materialId())) {
				throw new IllegalArgumentException("Duplicate fluid-mixture material " + component.materialId());
			}
			totalVolume = Math.addExact(totalVolume, component.volumeNanos());
			totalMass += component.mass();
		}
		if (totalVolume > ContainerFluidLedger.NANOS_PER_BLOCK) {
			throw new IllegalArgumentException("Fluid mixture exceeds one bucket");
		}
		if (!(Double.isFinite(totalMass) && totalMass > 0.0)) {
			throw new IllegalArgumentException("Fluid mixture total mass must be finite and positive");
		}
		components = List.copyOf(canonical);
	}

	public long totalVolumeNanos() {
		long total = 0L;
		for (Component component : components) total = Math.addExact(total, component.volumeNanos());
		return total;
	}

	public double totalMass() {
		double total = 0.0;
		for (Component component : components) total += component.mass();
		if (!(Double.isFinite(total) && total > 0.0)) throw new IllegalStateException("Fluid mixture mass overflow");
		return total;
	}

	/** Canonical textual handle; ids cannot contain ',' or ';' under Minecraft identifier syntax. */
	public String encode() {
		StringBuilder result = new StringBuilder(ENCODING_TAG);
		for (int i = 0; i < components.size(); i++) {
			if (i > 0) result.append(';');
			Component component = components.get(i);
			result.append(component.materialId()).append(',')
					.append(component.volumeNanos()).append(',')
					.append(Long.toUnsignedString(component.massBits(), 16));
		}
		if (result.length() > MAX_ENCODED_LENGTH) {
			throw new IllegalArgumentException("Fluid-mixture handle exceeds the persisted component limit");
		}
		return result.toString();
	}

	public static FluidMixture decode(String encoded) {
		if (encoded == null || encoded.length() > MAX_ENCODED_LENGTH || !encoded.startsWith(ENCODING_TAG)) {
			throw new IllegalArgumentException("Unsupported fluid-mixture handle");
		}
		String body = encoded.substring(ENCODING_TAG.length());
		if (body.isEmpty()) throw new IllegalArgumentException("Empty fluid-mixture handle");
		List<Component> components = new ArrayList<>();
		for (String entry : body.split(";", -1)) {
			String[] fields = entry.split(",", -1);
			if (fields.length != 3) throw new IllegalArgumentException("Malformed fluid-mixture component");
			try {
				components.add(new Component(fields[0], Long.parseLong(fields[1]),
						Long.parseUnsignedLong(fields[2], 16)));
			} catch (NumberFormatException invalidNumber) {
				throw new IllegalArgumentException("Malformed fluid-mixture number", invalidNumber);
			}
		}
		return new FluidMixture(components);
	}

	public record Component(String materialId, long volumeNanos, long massBits) {
		public Component(String materialId, long volumeNanos, double mass) {
			this(materialId, volumeNanos, Double.doubleToRawLongBits(mass));
		}

		public Component {
			StableId.require(materialId, 128, "fluid-mixture material id");
			if (volumeNanos <= 0L || volumeNanos > ContainerFluidLedger.NANOS_PER_BLOCK) {
				throw new IllegalArgumentException("Invalid fluid-mixture component volume: " + volumeNanos);
			}
			double mass = Double.longBitsToDouble(massBits);
			if (!(Double.isFinite(mass) && mass > 0.0)) {
				throw new IllegalArgumentException("Invalid fluid-mixture component mass");
			}
		}

		public double mass() {
			return Double.longBitsToDouble(massBits);
		}
	}
}
