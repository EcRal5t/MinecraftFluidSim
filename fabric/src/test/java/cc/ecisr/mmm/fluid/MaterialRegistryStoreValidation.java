package cc.ecisr.mmm.fluid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Strict round-trip, mutation and fault behaviour of the world-local material registry snapshot store. */
public final class MaterialRegistryStoreValidation {
	private MaterialRegistryStoreValidation() { }

	public static void main(String[] args) throws Exception {
		builtinRegistryPublishesAndRoundTrips();
		definitionMutationOnlyReportsMismatch();
		strictDecodeRejectsTampering();
		foreignContentIsPreservedAndRejected();
		viscosityDomainIsStrict();
		System.out.println("Material registry store validation passed");
	}

	private static void builtinRegistryPublishesAndRoundTrips() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "material-registry-roundtrip-");
		MaterialRegistryStore store = new MaterialRegistryStore(root);
		MaterialRegistry definitions = BuiltinFluidMaterials.createRegistry();
		MaterialRegistryStore.LoadResult first = store.loadOrCreate(definitions);
		require(first.created(), "first material registry publication must create the snapshot");
		require(!first.definitionMismatch(), "first material registry publication must not report a mismatch");
		MaterialRegistryStore.LoadResult second = store.loadOrCreate(definitions);
		require(!second.created(), "stable material republication must not recreate the snapshot");
		require(!second.definitionMismatch(), "identical material definitions must not report a mismatch");
		MaterialRegistry stored = store.read();
		require(stored.fingerprint().equals(definitions.fingerprint()), "stored material fingerprint must match");
		require(java.util.Arrays.equals(stored.canonicalBytes(), definitions.canonicalBytes()),
				"stored material canonical bytes must match");
		require(stored.size() == definitions.size(), "stored material count must match");
		require(stored.defaultMaterialId().equals("mmm:water")
				&& stored.runtimeRegistry().material(0).id().equals("mmm:water"),
				"snapshot default material must define runtime handle zero");
		require(stored.hasMaterial("mmm:water") && stored.hasMaterial("mmm:oil") && stored.hasMaterial("mmm:mercury"),
				"default palette must be present in the snapshot");
		requireViscosityBitwiseEqual(stored, "mmm:water", 293.15, definitions.dynamicViscosityAt("mmm:water", 293.15));
		requireViscosityBitwiseEqual(stored, "mmm:water", 645.0, definitions.dynamicViscosityAt("mmm:water", 645.0));
		requireViscosityBitwiseEqual(stored, "mmm:oil", 313.15, definitions.dynamicViscosityAt("mmm:oil", 313.15));
		requireViscosityBitwiseEqual(stored, "mmm:mercury", 234.32, definitions.dynamicViscosityAt("mmm:mercury", 234.32));
		double water = stored.dynamicViscosityAt("mmm:water", 293.15);
		require(water > 9.0e-4 && water < 1.1e-3, "water viscosity at ambient temperature must stay near 1 mPa·s");
		FluidMaterialRegistry runtime = stored.runtimeRegistry();
		require(Double.doubleToRawLongBits(runtime.dynamicViscosityAt(runtime.handle("mmm:water"), 293.15))
				== Double.doubleToRawLongBits(water), "runtime handles must consume the frozen viscosity profile");
		FlipMaterialParticleState handoff = new FlipMaterialParticleState(
				2, 2, 2, 0.25F, 0, 0.0, 0.0, runtime,
				new float[0], new float[0], new byte[8], new double[0], new int[0]);
		require(Double.doubleToRawLongBits(handoff.runtimeRegistry().dynamicViscosityAt(
				handoff.runtimeRegistry().handle("mmm:water"), 293.15)) == Double.doubleToRawLongBits(water),
				"backend handoff must retain the frozen viscosity profile");
		require(stored.liquidPairTension("mmm:aqueous", "mmm:oil") == 0.0,
				"default registry has no declared pair tensions");
		System.out.println("builtin material registry fingerprint=" + definitions.fingerprint());
	}

	private static void requireViscosityBitwiseEqual(MaterialRegistry registry, String id, double temperatureK,
			double expected) {
		double actual = registry.dynamicViscosityAt(id, temperatureK);
		require(Double.doubleToRawLongBits(actual) == Double.doubleToRawLongBits(expected),
				"restored viscosity interpolation must be bitwise identical for " + id + " at " + temperatureK);
	}

	private static void definitionMutationOnlyReportsMismatch() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "material-registry-mutation-");
		MaterialRegistryStore store = new MaterialRegistryStore(root);
		MaterialRegistry original = BuiltinFluidMaterials.createRegistry();
		store.loadOrCreate(original);
		MaterialRegistry mutated = mutatedOilDensity(801.0);
		require(mutated.fingerprint().equals(original.fingerprint()) == false,
				"different oil density must change the fingerprint");
		MaterialRegistryStore.LoadResult result = store.loadOrCreate(mutated);
		require(!result.created(), "mutated publication must not overwrite the snapshot");
		require(result.definitionMismatch(), "mutated publication must report a definition mismatch");
		require(store.read().fingerprint().equals(original.fingerprint()),
				"authoritative snapshot must be preserved verbatim");
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
		MaterialRegistryStore store = new MaterialRegistryStore(root);
		store.loadOrCreate(BuiltinFluidMaterials.createRegistry());
		Path target = store.registryPath();
		CompoundTag rootTag = NbtIo.readCompressed(target, net.minecraft.nbt.NbtAccounter.create(1_048_576L));
		rootTag.putLong("UnknownExtraField", 7L);
		NbtIo.writeCompressed(rootTag, target);
		expectIOException(() -> store.read(), "unknown root field must be rejected");
		rootTag = NbtIo.readCompressed(target, net.minecraft.nbt.NbtAccounter.create(1_048_576L));
		rootTag.putByteArray("Canonical", new byte[] {1});
		rootTag.remove("UnknownExtraField");
		NbtIo.writeCompressed(rootTag, target);
		expectIOException(() -> store.read(), "tampered canonical bytes must be rejected");
	}

	private static void foreignContentIsPreservedAndRejected() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "material-registry-foreign-");
		MaterialRegistryStore store = new MaterialRegistryStore(root);
		store.loadOrCreate(BuiltinFluidMaterials.createRegistry());
		Files.writeString(root.resolve("mmm").resolve("material").resolve("stray.txt"), "evidence");
		expectIOException(() -> store.loadOrCreate(BuiltinFluidMaterials.createRegistry()),
				"foreign content must prevent republication");
		expectIOException(() -> store.read(), "foreign content must prevent reading");
	}

	private static void viscosityDomainIsStrict() {
		MaterialRegistry registry = BuiltinFluidMaterials.createRegistry();
		expectIllegal("outside the declared viscosity domain",
				() -> registry.dynamicViscosityAt("mmm:water", 200.0));
		expectIllegal("outside the declared viscosity domain",
				() -> registry.dynamicViscosityAt("mmm:water", 700.0));
		expectIllegal("outside the declared viscosity domain",
				() -> registry.dynamicViscosityAt("mmm:water", Double.NaN));
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
