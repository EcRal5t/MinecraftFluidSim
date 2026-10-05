package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.geometry.ResolvedControlVolume;

import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.LiquidGeometryState;
import cc.ecisr.mmm.model.region.RegionExtension;
import cc.ecisr.mmm.simulation.RegionCheckpointFixtures;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Resolved support spans sections independently of the inventory group's observable centroid. */
final class RegionLiquidGeometryValidationScenarios extends RegionPersistenceValidationSupport {
	public static void main(String[] args) throws Exception { validate(); System.out.println("Resolved liquid geometry persistence passed"); }
	static void validate() throws Exception {
		validateGasGeometryPersistence();
		var region = populatedRegion(new UUID(811, 812));
		byte[] masks = new byte[region.sizeX() * region.sizeY() * region.sizeZ()];
		masks[2 + region.sizeX() * (1 + region.sizeY())] = 1;
		var geometry = new LiquidGeometryState(4, List.of(box(2, 3, 0, 0.25), box(15, 16, 3, -0.25)), new int[]{0}, new int[]{0, 0});
		var before = new FlipParticleState(region.sizeX(), region.sizeY(), region.sizeZ(), CONFIG.cellSize(), 1, 0.125F,
				new float[]{1.125F, 0.625F, 0.625F, 0.125F}, new float[3], masks, geometry);
		region.domain().restoreParticleState(before);
		var captured = RegionCheckpointFixtures.capture(region, 1);
		captured.validate();
		assertParticleStateEquals(before, restorer(captured).reconstruct());
		var binary = new LiquidGeometryStateCodec();
		byte[] encoded = binary.encode(geometry);
		require(Arrays.equals(encoded, binary.encode(binary.decode(encoded))), "liquid geometry encoding changed on round-trip");
		for (byte[] malformed : List.of(Arrays.copyOf(encoded, encoded.length - 1), Arrays.copyOf(encoded, encoded.length + 1)))
			expectIOException(() -> binary.decode(malformed), "truncated or trailing liquid geometry was accepted");
		byte[] invalidLength = encoded.clone();
		Arrays.fill(invalidLength, 12, 16, (byte) 0x7f);
		expectIOException(() -> binary.decode(invalidLength), "unbounded liquid geometry allocation was accepted");
		for (var invalid : List.of(
				new LiquidGeometryState(4, List.of(box(2, 3, 0, 0)), new int[]{0}),
				new LiquidGeometryState(4, List.of(box(3, 4, 0, 0.25), box(15, 16, 3, -0.25)), new int[]{0}, new int[]{0, 0}),
				new LiquidGeometryState(4, List.of(box(2, 3, 0, 0.5), box(15, 16, 3, -0.25)), new int[]{0}, new int[]{0, 0}),
				new LiquidGeometryState(4, List.of(box(8, 9, 2, 0.25), box(15, 16, 3, -0.25)), new int[]{0}, new int[]{0, 0}))) {
			var changed = new RegionState(manifestWithExtensions(captured.manifest(), List.of(
					new RegionExtension(LiquidGeometryStateCodec.MANIFEST_EXTENSION, binary.encode(invalid)))), captured.sections());
			expectIOException(changed::validate, "geometry centroid, momentum or collision mismatch was accepted");
		}
		var duplicates = new RegionState(manifestWithExtensions(captured.manifest(), List.of(
				captured.manifest().extensions().getFirst(), captured.manifest().extensions().getFirst())), captured.sections());
		expectIOException(duplicates::validate, "duplicate liquid geometry was accepted");
		var files = new RegionGenerationStore(Files.createTempDirectory("mmm-resolved-liquid-"));
		files.publish(captured);
		var after = restorer(files.read(region.id())).reconstruct();
		assertParticleStateEquals(before, after);
		require(region.recordPublishedCheckpoint(1) && region.releaseSolverAfterCheckpoint(1), "resolved liquid unload failed");
		region.restoreSolver(after, 1);
		assertParticleStateEquals(before, region.domain().exportParticleState());
		var material = new FlipMaterialParticleState(before.sizeX(), before.sizeY(), before.sizeZ(), before.cellSize(),
				1, 0.125, 125, List.of(BuiltinFluidMaterials.WATER), before.copyPositionVolume(), before.copyParticleVelocities(),
				masks, new double[]{125}, new int[]{0}, geometry);
		region.domain().restoreMaterialParticleState(material);
		var materials = RegionCheckpointFixtures.captureMaterial(region, 2);
		materials.validate();
		assertMaterialStateEquals(material, restorer(materials).reconstructMaterial());
	}
	private static void validateGasGeometryPersistence() throws Exception {
		var region = emptyRegion(new UUID(813, 814)); var registries = cc.ecisr.mmm.simulation.FullCapabilityTestFixture.load();
		byte[] masks = new byte[512]; Arrays.fill(masks, (byte) 0xff); masks[3 + 8 * (3 + 8 * 3)] = 0;
		var geometry = new LiquidGeometryState(4, List.of(box(new int[]{1536, 1791, 1536}, new int[]{2048, 1793, 2048},
				4096, new int[]{3, 3, 3, 3}, 0)), new int[]{0});
		region.domain().restoreMaterialParticleState(new FlipMaterialParticleState(8, 8, 8, 0.5F, 1, 1.0 / 256, 1000.0 / 256,
				registries.liquids().materials(), new float[]{1.75F, 1.75F, 1.75F, 1.0F / 256}, new float[3], masks,
				new double[]{1000.0 / 256}, new int[]{0}, geometry));
		cc.ecisr.mmm.simulation.FullCapabilityTestFixture.bindFull(region.domain(), registries,
				cc.ecisr.mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K, cc.ecisr.mmm.thermal.ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		var gas = region.domain().exportGasState(registries.chemical().fingerprint());
		var thermal = region.domain().exportThermalParticleState();
		require(gas.geometry() != null && gas.nodes().size() == 2, "thermal fixture lost its two resolved chambers");
		var captured = RegionCheckpointFixtures.captureThermal(region, 1); captured.validate();
		var codec = new GasGeometryStateCodec(); byte[] encoded = codec.encode(gas.geometry());
		require(Arrays.equals(encoded, codec.encode(codec.decode(encoded))), "gas geometry encoding changed on round-trip");
		for (byte[] malformed : List.of(Arrays.copyOf(encoded, encoded.length - 1), Arrays.copyOf(encoded, encoded.length + 1)))
			expectIOException(() -> codec.decode(malformed), "truncated or trailing gas support was accepted");
		byte[] malformed = encoded.clone(); Arrays.fill(malformed, 17, 21, (byte) 0x7f);
		expectIOException(() -> codec.decode(malformed), "unbounded gas geometry allocation was accepted");
		var support = gas.geometry();
		var wrongExterior = new cc.ecisr.mmm.fluid.air.GasGeometryState(support.rootWidthMetres(), support.cells(),
				support.nodeOrdinals(), support.nodeCount(), false);
		byte[] wrongPayload = codec.encode(wrongExterior);
		var changedExtensions = captured.manifest().extensions().stream().map(extension ->
				GasGeometryStateCodec.MANIFEST_EXTENSION.equals(extension.type())
						? new RegionExtension(extension.type(), wrongPayload) : extension).toList();
		var wrong = new RegionState(manifestWithExtensions(captured.manifest(), changedExtensions), captured.sections());
		expectIOException(wrong::validate, "checkpoint accepted gas topology with a different exterior");
		var missingLiquid = captured.manifest().extensions().stream().filter(extension ->
				!LiquidGeometryStateCodec.MANIFEST_EXTENSION.equals(extension.type())).toList();
		expectIOException(new RegionState(manifestWithExtensions(captured.manifest(), missingLiquid), captured.sections())::validate,
				"checkpoint accepted resolved gas without its liquid support");
		var duplicateSupport = new java.util.ArrayList<>(captured.manifest().extensions());
		duplicateSupport.add(new RegionExtension(GasGeometryStateCodec.MANIFEST_EXTENSION, encoded));
		expectIOException(new RegionState(manifestWithExtensions(captured.manifest(), duplicateSupport), captured.sections())::validate,
				"checkpoint accepted duplicate resolved gas support");
		var files = new RegionGenerationStore(Files.createTempDirectory("mmm-resolved-gas-"), registries.chemical(),
				registries.granular(), registries.thermal(), registries.gas()); files.publish(captured);
		var saved = files.read(region.id()); var restoration = restorer(saved);
		assertGasStateEquals(gas, restoration.reconstructGas(registries.gas(), registries.chemical()));
		assertThermalStateEquals(thermal, restoration.reconstructThermal(registries.thermal()));
		var restored = new cc.ecisr.mmm.fluid.FluidDomain(8, 8, 8, CONFIG, 4096); restored.sealDomainBoundary();
		restored.restoreChemicalParticleState(restoration.reconstructChemical(registries.chemical()), registries.chemical());
		restored.restoreGasState(restoration.reconstructGas(registries.gas(), registries.chemical()), registries.gas(), thermal);
		restored.restoreThermalParticleState(thermal, registries.thermal());
		assertGasStateEquals(gas, restored.exportGasState(registries.chemical().fingerprint()));
		require(restored.exactInternalEnergy().equals(thermal.audit().expectedInventory()), "resolved gas cold restore lost thermal inventory");
	}

	private static ResolvedControlVolume box(int lowX, int highX, int cellX, double vx) {
		return box(new int[]{lowX, 4, 4}, new int[]{highX, 6, 6}, 32, new int[]{3, cellX, 1, 1}, vx);
	}
	private static ResolvedControlVolume box(int[] low, int[] high, int denominator, int[] address, double vx) {
		BigInteger[] vertices = new BigInteger[32];
		for (int v = 0; v < 8; v++) {
			int[] coordinates = {(v & 1) == 0 ? low[0] : high[0], (v & 2) == 0 ? low[1] : high[1], (v & 4) == 0 ? low[2] : high[2], denominator};
			BigInteger gcd = BigInteger.valueOf(denominator);
			for (int a = 0; a < 3; a++) gcd = gcd.gcd(BigInteger.valueOf(coordinates[a]));
			for (int a = 0; a < 4; a++) vertices[4 * v + a] = BigInteger.valueOf(coordinates[a]).divide(gcd);
		}
		int[] coefficients = {-denominator, 0, 0, -low[0], denominator, 0, 0, high[0], 0, -denominator, 0, -low[1],
				0, denominator, 0, high[1], 0, 0, -denominator, -low[2], 0, 0, denominator, high[2]};
		BigInteger[] planes = new BigInteger[24];
		for (int f = 0; f < 6; f++) {
			BigInteger gcd = BigInteger.ZERO;
			for (int a = 0; a < 4; a++) gcd = gcd.gcd(BigInteger.valueOf(coefficients[4 * f + a]).abs());
			for (int a = 0; a < 4; a++) planes[4 * f + a] = BigInteger.valueOf(coefficients[4 * f + a]).divide(gcd);
		}
		return new ResolvedControlVolume(address[0], address[1], address[2], address[3], vertices, new int[]{0, 4, 8, 12, 16, 20, 24},
				new int[]{0,4,6,2, 1,3,7,5, 0,1,5,4, 2,6,7,3, 0,2,3,1, 4,5,7,6}, planes, new double[]{vx, 0, 0});
	}
}
