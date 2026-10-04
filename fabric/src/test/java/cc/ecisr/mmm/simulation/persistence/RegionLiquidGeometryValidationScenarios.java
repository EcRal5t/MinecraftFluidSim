package cc.ecisr.mmm.simulation.persistence;

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

	private static LiquidGeometryState.ControlVolume box(int lowX, int highX, int cellX, double vx) {
		BigInteger[] vertices = new BigInteger[32];
		for (int v = 0; v < 8; v++) {
			int[] coordinates = {(v & 1) == 0 ? lowX : highX, (v & 2) == 0 ? 4 : 6, (v & 4) == 0 ? 4 : 6, 32};
			BigInteger gcd = BigInteger.valueOf(32);
			for (int a = 0; a < 3; a++) gcd = gcd.gcd(BigInteger.valueOf(coordinates[a]));
			for (int a = 0; a < 4; a++) vertices[4 * v + a] = BigInteger.valueOf(coordinates[a]).divide(gcd);
		}
		int[] coefficients = {-32, 0, 0, -lowX, 32, 0, 0, highX, 0, -32, 0, -4, 0, 32, 0, 6, 0, 0, -32, -4, 0, 0, 32, 6};
		BigInteger[] planes = new BigInteger[24];
		for (int f = 0; f < 6; f++) {
			BigInteger gcd = BigInteger.ZERO;
			for (int a = 0; a < 4; a++) gcd = gcd.gcd(BigInteger.valueOf(coefficients[4 * f + a]).abs());
			for (int a = 0; a < 4; a++) planes[4 * f + a] = BigInteger.valueOf(coefficients[4 * f + a]).divide(gcd);
		}
		return new LiquidGeometryState.ControlVolume(3, cellX, 1, 1, vertices, new int[]{0, 4, 8, 12, 16, 20, 24},
				new int[]{0,4,6,2, 1,3,7,5, 0,1,5,4, 2,6,7,3, 0,2,3,1, 4,5,7,6}, planes, new double[]{vx, 0, 0});
	}
}
