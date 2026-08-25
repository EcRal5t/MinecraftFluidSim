package cc.ecisr.mmm.item;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Canonical container inventory: stable material id plus independent nanovolume and mass per component. */
public record FluidMixture(List<Component> components) {
	public static final int MAX_COMPONENTS = 64;
	public static final int MAX_ENCODED_LENGTH = 8192;

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

	/** Stable v1 textual handle; ids cannot contain ',' or ';' under Minecraft identifier syntax. */
	public String encode() {
		StringBuilder result = new StringBuilder("v1;");
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
		if (encoded == null || encoded.length() > MAX_ENCODED_LENGTH || !encoded.startsWith("v1;")) {
			throw new IllegalArgumentException("Unsupported fluid-mixture handle");
		}
		String body = encoded.substring(3);
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

	public static FluidMixture fromStack(ItemStack stack) {
		if (!(stack.getItem() instanceof MaterialBucketItem)) return null;
		if (stack.get(MmmComponents.CHEMICAL_CONTENTS) != null) return null;
		long volume = MaterialBucketItem.volume(stack);
		if (volume <= 0L) return null;
		String encoded = stack.get(MmmComponents.MIXTURE_COMPONENTS);
		String materialId = stack.get(MmmComponents.MATERIAL_ID);
		FluidMixture mixture;
		if (encoded != null) {
			if (materialId != null) return null;
			mixture = decode(encoded);
			if (mixture.components().size() < 2 || !mixture.encode().equals(encoded)) return null;
		} else {
			Long massBits = stack.get(MmmComponents.FLUID_MASS_BITS);
			if (materialId == null || massBits == null) return null;
			mixture = new FluidMixture(List.of(new Component(materialId, volume, massBits)));
		}
		Long persistedMassBits = stack.get(MmmComponents.FLUID_MASS_BITS);
		return mixture.totalVolumeNanos() == volume
				&& persistedMassBits != null
				&& Double.doubleToRawLongBits(mixture.totalMass()) == persistedMassBits
				? mixture : null;
	}

	public ItemStack toStack() {
		ItemStack stack = new ItemStack(MmmItems.MATERIAL_BUCKET);
		stack.set(MmmComponents.FLUID_VOLUME, totalVolumeNanos());
		stack.set(MmmComponents.FLUID_MASS_BITS, Double.doubleToRawLongBits(totalMass()));
		if (components.size() == 1) {
			stack.set(MmmComponents.MATERIAL_ID, components.getFirst().materialId());
		} else {
			stack.set(MmmComponents.MIXTURE_COMPONENTS, encode());
		}
		return stack;
	}

	public record Component(String materialId, long volumeNanos, long massBits) {
		public Component(String materialId, long volumeNanos, double mass) {
			this(materialId, volumeNanos, Double.doubleToRawLongBits(mass));
		}

		public Component {
			if (Identifier.tryParse(materialId) == null) {
				throw new IllegalArgumentException("Invalid fluid-mixture material id: " + materialId);
			}
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
