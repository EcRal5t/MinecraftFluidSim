package cc.ecisr.mmm.item;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical thermal truth: raw total U bound to one exact underlying liquid inventory. */
public record ThermalContents(
		String registryFingerprint,
		UnderlyingKind underlyingKind,
		String underlyingHash,
		long internalEnergyBits
) {
	public static final int MAX_ENCODED_LENGTH = 256;
	private static final String ENCODING_TAG = "mmm:thermal-contents";

	public enum UnderlyingKind { PURE_WATER, MATERIAL, CHEMICAL }

	public ThermalContents {
		if (registryFingerprint == null || !registryFingerprint.matches("[0-9a-f]{64}")
				|| underlyingKind == null || underlyingHash == null || !underlyingHash.matches("[0-9a-f]{64}")
				|| !Double.isFinite(Double.longBitsToDouble(internalEnergyBits))) {
			throw new IllegalArgumentException("Invalid thermal contents identity or energy");
		}
	}

	public ThermalContents(String registryFingerprint, UnderlyingKind kind, String hash, double internalEnergy) {
		this(registryFingerprint, kind, hash, Double.doubleToRawLongBits(internalEnergy));
	}

	public double internalEnergy() { return Double.longBitsToDouble(internalEnergyBits); }

	public static ThermalContents forPureWater(
			String registryFingerprint, long volumeNanos, double internalEnergy
	) {
		if (volumeNanos <= 0L || volumeNanos > ContainerFluidLedger.NANOS_PER_BLOCK) {
			throw new IllegalArgumentException("Pure-water thermal volume is invalid");
		}
		return create(registryFingerprint, pureWater(volumeNanos), internalEnergy);
	}

	public static ThermalContents forMixture(
			String registryFingerprint, FluidMixture mixture, double internalEnergy
	) {
		if (mixture == null) throw new IllegalArgumentException("Thermal material mixture is missing");
		return create(registryFingerprint, encoded(UnderlyingKind.MATERIAL, mixture.encode()), internalEnergy);
	}

	public static ThermalContents forChemical(
			String registryFingerprint, ChemicalContents contents, double internalEnergy
	) {
		if (contents == null) throw new IllegalArgumentException("Thermal chemical contents are missing");
		return create(registryFingerprint, encoded(UnderlyingKind.CHEMICAL, contents.encode()), internalEnergy);
	}

	/** Creates and attaches thermal truth after validating the exact underlying liquid contents. */
	public static ThermalContents attach(ItemStack stack, String registryFingerprint, double internalEnergy) {
		Underlying underlying = underlying(stack);
		ThermalContents result = create(registryFingerprint, underlying, internalEnergy);
		stack.set(MmmComponents.THERMAL_CONTENTS, result.encode());
		return result;
	}

	/** Returns null only when no thermal component exists; malformed or foreign underlying truth is rejected. */
	public static ThermalContents fromStack(ItemStack stack) {
		String encoded = stack.get(MmmComponents.THERMAL_CONTENTS);
		if (encoded == null) return null;
		ThermalContents result = decode(encoded);
		Underlying underlying = underlying(stack);
		if (result.underlyingKind() != underlying.kind()
				|| !result.underlyingHash().equals(hash(underlying.canonicalBytes()))) {
			throw new IllegalArgumentException("Thermal contents do not match the underlying liquid inventory");
		}
		return result;
	}

	public void validateRegistryFingerprint(String authoritativeFingerprint) {
		if (!registryFingerprint.equals(authoritativeFingerprint)) {
			throw new IllegalArgumentException("Foreign thermal registry fingerprint");
		}
	}

	/** Conservative split for a material transfer; the remainder side is subtraction, not a second rounded product. */
	public EnergySplit splitEnergy(long selectedNanos, long totalNanos) {
		if (selectedNanos <= 0L || totalNanos <= 0L || selectedNanos > totalNanos) {
			throw new IllegalArgumentException("Thermal contents split volume is invalid");
		}
		if (selectedNanos == totalNanos) return new EnergySplit(internalEnergy(), 0.0);
		double selected = internalEnergy() * ((double) selectedNanos / totalNanos);
		double remaining = internalEnergy() - selected;
		if (!Double.isFinite(selected) || !Double.isFinite(remaining)) {
			throw new IllegalArgumentException("Thermal contents split energy is not representable");
		}
		return new EnergySplit(selected, remaining);
	}

	public String encode() {
		return ENCODING_TAG + '|' + registryFingerprint + '|' + underlyingKind.name() + '|' + underlyingHash + '|'
				+ Long.toUnsignedString(internalEnergyBits, 16);
	}

	public static ThermalContents decode(String encoded) {
		if (encoded == null || encoded.length() > MAX_ENCODED_LENGTH) {
			throw new IllegalArgumentException("Invalid thermal contents size");
		}
		String[] fields = encoded.split("\\|", -1);
		if (fields.length != 5 || !fields[0].equals(ENCODING_TAG)) {
			throw new IllegalArgumentException("Unsupported thermal contents");
		}
		try {
			ThermalContents result = new ThermalContents(fields[1], UnderlyingKind.valueOf(fields[2]), fields[3],
					Long.parseUnsignedLong(fields[4], 16));
			if (!result.encode().equals(encoded)) throw new IllegalArgumentException("Thermal contents are not canonical");
			return result;
		} catch (IllegalArgumentException invalid) {
			throw new IllegalArgumentException("Malformed thermal contents", invalid);
		}
	}

	private static Underlying underlying(ItemStack stack) {
		if (stack == null || stack.getCount() != 1 || stack.get(MmmComponents.GRANULAR_CONTENTS) != null) {
			throw new IllegalArgumentException("Thermal contents require one non-granular liquid container");
		}
		if (stack.is(Items.WATER_BUCKET)) {
			requireNoMaterialLedger(stack);
			return pureWater(ContainerFluidLedger.NANOS_PER_BLOCK);
		}
		if (stack.getItem() instanceof PartialWaterBucketItem) {
			requireNoMaterialLedger(stack);
			long nanos = PartialWaterBucketItem.volume(stack);
			if (nanos <= 0L) throw new IllegalArgumentException("Partial water container is invalid");
			return pureWater(nanos);
		}
		if (stack.getItem() instanceof MaterialBucketItem) {
			String chemical = stack.get(MmmComponents.CHEMICAL_CONTENTS);
			if (chemical != null) {
				ChemicalContents contents = ChemicalContents.decode(chemical);
				Long mass = stack.get(MmmComponents.FLUID_MASS_BITS);
				if (MaterialBucketItem.volume(stack) != contents.totalVolumeNanos() || mass == null
						|| mass != Double.doubleToRawLongBits(contents.totalMass())
						|| stack.get(MmmComponents.MATERIAL_ID) != null
						|| stack.get(MmmComponents.MIXTURE_COMPONENTS) != null) {
					throw new IllegalArgumentException("Chemical container ledgers disagree");
				}
				return encoded(UnderlyingKind.CHEMICAL, contents.encode());
			}
			FluidMixture mixture = FluidMixture.fromStack(stack);
			if (mixture == null) throw new IllegalArgumentException("Material container is empty or malformed");
			return encoded(UnderlyingKind.MATERIAL, mixture.encode());
		}
		throw new IllegalArgumentException("Unsupported thermal liquid container");
	}

	private static ThermalContents create(String registryFingerprint, Underlying underlying, double internalEnergy) {
		return new ThermalContents(registryFingerprint, underlying.kind(), hash(underlying.canonicalBytes()), internalEnergy);
	}

	private static Underlying pureWater(long volumeNanos) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(Long.BYTES);
			try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeLong(volumeNanos); }
			return new Underlying(UnderlyingKind.PURE_WATER, bytes.toByteArray());
		} catch (IOException impossible) {
			throw new IllegalStateException("In-memory thermal contents encoding failed", impossible);
		}
	}

	private static Underlying encoded(UnderlyingKind kind, String contents) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(contents.length() + 2);
			try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeUTF(contents); }
			return new Underlying(kind, bytes.toByteArray());
		} catch (IOException impossible) {
			throw new IllegalStateException("In-memory thermal contents encoding failed", impossible);
		}
	}

	private static void requireNoMaterialLedger(ItemStack stack) {
		if (stack.get(MmmComponents.FLUID_VOLUME) != null
				|| stack.get(MmmComponents.MATERIAL_ID) != null
				|| stack.get(MmmComponents.FLUID_MASS_BITS) != null
				|| stack.get(MmmComponents.MIXTURE_COMPONENTS) != null
				|| stack.get(MmmComponents.CHEMICAL_CONTENTS) != null) {
			throw new IllegalArgumentException("Pure-water container has conflicting material ledgers");
		}
	}

	private static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private record Underlying(UnderlyingKind kind, byte[] canonicalBytes) { }
	public record EnergySplit(double selectedEnergy, double remainingEnergy) { }
}
