package mmm.fluid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Strict round-trip, mutation and fault behaviour of the canonical material registry snapshot codec. */
public final class MaterialRegistryCodecValidation {
	private MaterialRegistryCodecValidation() { }

	public static void main(String[] args) throws Exception {
		canonicalResourceIdentifiers();
		builtinRegistryRoundTripsCanonically();
		definitionMutationChangesSnapshotBytes();
		strictDecodeRejectsTampering();
		viscosityDomainIsStrict();
		viscosityContinuationIsBounded();
		expansionSnapshotPreservesItsOwnDefinition();
		System.out.println("Material registry codec validation passed");
	}

	private static void canonicalResourceIdentifiers() {
		var canonical = java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
		// Every UTF-16 code unit in namespace, separator and path positions, including surrogates.
		for (int code = 0; code <= Character.MAX_VALUE; code++) for (int position = 0; position < 3; position++) {
			char[] characters = {'a', ':', 'b'}; characters[position] = (char) code;
			String value = new String(characters);
			require(mmm.api.StableId.isValid(value, 3) == canonical.matcher(value).matches(),
					"resource identity grammar changed for code=" + code + ", position=" + position);
		}
		for (String value : new String[] {null, "", ":", "a:", ":b", "a:b:c", "a/b:c", "abc", "a:b/._-09", "_.-09:z"})
			for (int maximum : new int[] {-1, 0, 1, 2, 3, 4, 128}) {
				boolean expected = maximum > 0 && value != null && value.length() <= maximum
						&& canonical.matcher(value).matches();
				require(mmm.api.StableId.isValid(value, maximum) == expected,
						"resource identity length or separator validation changed");
			}
	}

	private static void builtinRegistryRoundTripsCanonically() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "material-registry-roundtrip-");
		Path snapshot = root.resolve("material-registry.nbt");
		MaterialRegistry definitions = BuiltinFluidMaterials.createRegistry();
		MaterialRegistryCodec.writeSnapshot(snapshot, definitions);
		MaterialRegistry stored = MaterialRegistryCodec.readSnapshot(snapshot);
		require(stored.fingerprint().equals(definitions.fingerprint()), "stored material fingerprint must match");
		require(java.util.Arrays.equals(stored.canonicalBytes(), definitions.canonicalBytes()),
				"stored material canonical bytes must match");
		require(stored.size() == definitions.size(), "stored material count must match");
		require(stored.hasMaterial("mmm:water") && stored.hasMaterial("mmm:oil") && stored.hasMaterial("mmm:mercury"),
				"default palette must be present in the snapshot");
		requireViscosityBitwiseEqual(stored, "mmm:water", 293.15, definitions.dynamicViscosityAt("mmm:water", 293.15));
		requireViscosityBitwiseEqual(stored, "mmm:water", 645.0, definitions.dynamicViscosityAt("mmm:water", 645.0));
		requireViscosityBitwiseEqual(stored, "mmm:oil", 313.15, definitions.dynamicViscosityAt("mmm:oil", 313.15));
		requireViscosityBitwiseEqual(stored, "mmm:mercury", 234.32, definitions.dynamicViscosityAt("mmm:mercury", 234.32));
		double water = stored.dynamicViscosityAt("mmm:water", 293.15);
		require(water > 9.0e-4 && water < 1.1e-3, "water viscosity at ambient temperature must stay near 1 mPa·s");
		require(stored.liquidPairTension("mmm:aqueous", "mmm:oil") == 0.0,
				"default registry has no declared pair tensions");
		require(definitions.fingerprint().equals("ff3770b933ac81c7af8f5cfa8391b23c238937d72c069c620bacae0ec9f3057d"),
				"builtin material registry fingerprint drifted from the pinned canonical form");
		System.out.println("builtin material registry fingerprint=" + definitions.fingerprint());
	}

	private static void requireViscosityBitwiseEqual(MaterialRegistry registry, String id, double temperatureK,
			double expected) {
		double actual = registry.dynamicViscosityAt(id, temperatureK);
		require(Double.doubleToRawLongBits(actual) == Double.doubleToRawLongBits(expected),
				"restored viscosity interpolation must be bitwise identical for " + id + " at " + temperatureK);
	}

	private static void expansionSnapshotPreservesItsOwnDefinition() throws Exception {
		var original = BuiltinFluidMaterials.createRegistry().entry("mmm:water");
		var definition = new mmm.thermal.LiquidThermalExpansion.Definition("mmm:water", 300, 0.0003, 0.02);
		var registry = new MaterialRegistry(List.of(new MaterialRegistry.MaterialEntry(
				original.material(), original.viscosity(), definition)), List.of());
		Path snapshot = Files.createTempDirectory(Path.of("."), "material-expansion-").resolve("material.nbt");
		MaterialRegistryCodec.writeSnapshot(snapshot, registry);
		var stored = MaterialRegistryCodec.readSnapshot(snapshot);
		require(stored.entry("mmm:water").thermalExpansion().equals(definition)
				&& stored.fingerprint().equals(registry.fingerprint()), "snapshot lost its committed expansion definition");
		for (var changed : List.of(
				new mmm.thermal.LiquidThermalExpansion.Definition("mmm:water", 301, 0.0003, 0.02),
				new mmm.thermal.LiquidThermalExpansion.Definition("mmm:water", 300, 0.0004, 0.02),
				new mmm.thermal.LiquidThermalExpansion.Definition("mmm:water", 300, 0.0003, 0.03))) {
			var mutated = new MaterialRegistry(List.of(new MaterialRegistry.MaterialEntry(
					original.material(), original.viscosity(), changed)), List.of());
			require(!mutated.fingerprint().equals(registry.fingerprint()), "expansion parameter missing from world identity");
		}
		CompoundTag root = NbtIo.readCompressed(snapshot, net.minecraft.nbt.NbtAccounter.create(1_048_576L));
		var material = (CompoundTag) root.getListOrEmpty("Materials").get(0);
		material.remove("ThermalExpansion");
		NbtIo.writeCompressed(root, snapshot);
		expectIOException(() -> MaterialRegistryCodec.readSnapshot(snapshot), "missing expansion inventory field must be rejected");
	}

	private static void definitionMutationChangesSnapshotBytes() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "material-registry-mutation-");
		MaterialRegistry original = BuiltinFluidMaterials.createRegistry();
		MaterialRegistry mutated = mutatedOilDensity(801.0);
		require(mutated.fingerprint().equals(original.fingerprint()) == false,
				"different oil density must change the fingerprint");
		require(!java.util.Arrays.equals(MaterialRegistryCodec.snapshotBytes(mutated),
						MaterialRegistryCodec.snapshotBytes(original)),
				"mutated material definitions produced identical snapshot bytes");
		Path snapshot = root.resolve("material-registry.nbt");
		MaterialRegistryCodec.writeSnapshot(snapshot, original);
		require(MaterialRegistryCodec.readSnapshot(snapshot).fingerprint().equals(original.fingerprint()),
				"stored material snapshot must keep its own fingerprint");
	}

	private static MaterialRegistry mutatedOilDensity(double density) {
		return new MaterialRegistry(List.of(
				new MaterialRegistry.MaterialEntry(new FluidMaterial("mmm:water", 1_000.0, "mmm:aqueous", 0.0, Math.PI / 2.0),
						BuiltinFluidMaterials.createRegistry().entry("mmm:water").viscosity()),
				new MaterialRegistry.MaterialEntry(new FluidMaterial("mmm:oil", density, "mmm:oil", 0.0, Math.PI / 2.0),
						BuiltinFluidMaterials.createRegistry().entry("mmm:oil").viscosity()),
				new MaterialRegistry.MaterialEntry(new FluidMaterial("mmm:mercury", 13_600.0, "mmm:mercury", 0.0, Math.PI / 2.0),
						BuiltinFluidMaterials.createRegistry().entry("mmm:mercury").viscosity())), List.of());
	}

	private static void strictDecodeRejectsTampering() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "material-registry-tamper-");
		Path target = root.resolve("material-registry.nbt");
		MaterialRegistryCodec.writeSnapshot(target, BuiltinFluidMaterials.createRegistry());
		CompoundTag rootTag = NbtIo.readCompressed(target, net.minecraft.nbt.NbtAccounter.create(1_048_576L));
		rootTag.putLong("UnknownExtraField", 7L);
		NbtIo.writeCompressed(rootTag, target);
		expectIOException(() -> MaterialRegistryCodec.readSnapshot(target), "unknown root field must be rejected");
		rootTag = NbtIo.readCompressed(target, net.minecraft.nbt.NbtAccounter.create(1_048_576L));
		rootTag.putByteArray("Canonical", new byte[] {1});
		rootTag.remove("UnknownExtraField");
		NbtIo.writeCompressed(rootTag, target);
		expectIOException(() -> MaterialRegistryCodec.readSnapshot(target), "tampered canonical bytes must be rejected");
	}

	private static void viscosityDomainIsStrict() {
		MaterialRegistry registry = BuiltinFluidMaterials.createRegistry();
		expectIllegal("outside the declared viscosity domain",
				() -> registry.dynamicViscosityAt("mmm:water", 200.0));
		expectIllegal("outside the declared viscosity domain",
				() -> registry.dynamicViscosityAt("mmm:water", 700.0));
		MaterialRegistry undeclared = new MaterialRegistry(List.of(new MaterialRegistry.MaterialEntry(
				new FluidMaterial("mmm:syrup", 1_400.0, "mmm:syrup", 0.0, Math.PI / 2.0), List.of())), List.of());
		expectIllegal("does not declare dynamic viscosity", () -> undeclared.dynamicViscosityAt("mmm:syrup", 300.0));
		expectIllegal("must be contiguous", () -> new MaterialRegistry(List.of(
				new MaterialRegistry.MaterialEntry(
						new FluidMaterial("mmm:bad", 900.0, "mmm:bad", 0.0, Math.PI / 2.0),
						List.of(segment(273.15, 300.0, 1.0e-3, 9.0e-4), segment(310.0, 350.0, 8.0e-4, 7.0e-4)))),
				List.of()));
		expectIllegalArgumentException("Dynamic viscosity must be finite and positive",
				() -> segment(273.15, 300.0, 0.0, 1.0e-3));
		expectIllegalArgumentException("temperature bounds are invalid", () -> segment(300.0, 300.0, 1.0e-3, 1.0e-3));
	}

	private static MaterialRegistry.ViscositySegment segment(double minK, double maxK, double muMin, double muMax) {
		return new MaterialRegistry.ViscositySegment(minK, maxK, muMin, muMax);
	}

	/**
	 * Carrier coupling must stay defined wherever the caloric data reaches, so temperatures outside the
	 * declared segments take the nearest endpoint value. Nothing is invented between measured points,
	 * and the endpoints stay bitwise identical to the strict lookup.
	 */
	private static void viscosityContinuationIsBounded() {
		MaterialRegistry inputs = BuiltinFluidMaterials.createRegistry();
		for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
				0, -1, Double.MIN_VALUE}) {
			expectIllegal("temperature", () -> inputs.dynamicViscosityAt("mmm:water", invalid));
			expectIllegal("temperature", () -> inputs.dynamicViscosityExtendingAt("mmm:water", invalid));
		}
		MaterialRegistry registry = BuiltinFluidMaterials.createRegistry();
		double low = registry.dynamicViscosityAt("mmm:water", 273.15);
		double high = registry.dynamicViscosityAt("mmm:water", 645.0);
		require(Double.doubleToRawLongBits(registry.dynamicViscosityExtendingAt("mmm:water", 273.15))
						== Double.doubleToRawLongBits(low),
				"the lowest declared endpoint must stay bitwise identical under continuation");
		require(Double.doubleToRawLongBits(registry.dynamicViscosityExtendingAt("mmm:water", 645.0))
						== Double.doubleToRawLongBits(high),
				"the highest declared endpoint must stay bitwise identical under continuation");
		double cold = registry.dynamicViscosityExtendingAt("mmm:water", 1.0);
		double hot = registry.dynamicViscosityExtendingAt("mmm:water", 5_000.0);
		require(cold == low && hot == high, "outside the domain the boundary value must be held, not extrapolated");
		require(registry.viscosityDomainContains("mmm:water", 293.15), "293.15 K is inside the water domain");
		require(!registry.viscosityDomainContains("mmm:water", 646.0), "646 K is outside the water domain");
		require(!registry.viscosityDomainContains("mmm:oil", 500.0), "500 K is outside the oil domain");
		double oil = registry.dynamicViscosityExtendingAt("mmm:oil", 500.0);
		require(Double.doubleToRawLongBits(oil)
						== Double.doubleToRawLongBits(registry.dynamicViscosityAt("mmm:oil", 473.15)),
				"oil continuation must hold its declared upper endpoint");
		// The continuation must stay positive and finite over the whole thermal range, never crossing zero.
		for (double temperature : new double[] {1.0, 273.15, 473.15, 645.0, 1_000.0, 1.0e7}) {
			for (String id : new String[] {"mmm:water", "mmm:oil", "mmm:mercury"}) {
				double mu = registry.dynamicViscosityExtendingAt(id, temperature);
				require(Double.isFinite(mu) && mu > 0.0,
						"continuation must stay finite and positive for " + id + " at " + temperature + " K");
			}
		}
		// The declarations a diagnostic reports must bracket exactly the strict domain.
		require(registry.entry("mmm:water").minimumViscosityTemperatureK() == 273.15
						&& registry.entry("mmm:water").maximumViscosityTemperatureK() == 645.0,
				"declared water viscosity bounds drifted");
	}

	private static void expectIOException(ThrowingAction action, String message) {
		try {
			action.run();
			throw new AssertionError("Expected IOException: " + message);
		} catch (IOException expected) {
			require(expected.getMessage() != null, "rejection must carry a reason");
		} catch (AssertionError propagated) {
			throw propagated;
		} catch (Exception unexpected) {
			throw new AssertionError("Expected IOException but got: " + unexpected, unexpected);
		}
	}

	private static void expectIllegal(String messagePart, ThrowingAction action) {
		try {
			action.run();
			throw new AssertionError("Expected IllegalArgumentException containing '" + messagePart + "'");
		} catch (IllegalArgumentException expected) {
			if (!String.valueOf(expected.getMessage()).contains(messagePart)) {
				throw new AssertionError("Unexpected rejection message: " + expected.getMessage(), expected);
			}
		} catch (AssertionError propagated) {
			throw propagated;
		} catch (Exception unexpected) {
			throw new AssertionError("Expected IllegalArgumentException but got: " + unexpected, unexpected);
		}
	}

	private static void expectIllegalArgumentException(String messagePart, ThrowingAction action) {
		expectIllegal(messagePart, action);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private interface ThrowingAction {
		void run() throws Exception;
	}
}
