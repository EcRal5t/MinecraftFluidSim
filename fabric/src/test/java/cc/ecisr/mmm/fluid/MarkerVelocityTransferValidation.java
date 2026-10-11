package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Sparse transfer geometry, actual-mass normalization and adjoint virtual-work invariants. */
public final class MarkerVelocityTransferValidation {
	private MarkerVelocityTransferValidation() { }
	public static void main(String[] args) { validate(); System.out.println("MarkerVelocityTransferValidation PASS"); }
	public static void validate() {
		sparseGeometryAndAdjoint();
		actualMassAndClosedFaces();
		productionP2GPreservesFiniteMassWeightedVelocities();
		productionTransferBudgetsUsePhysicalEnergy();
		captureAndPublicationAreAtomic();
	}

	private static FluidDomain domain() {
		return new FluidDomain(4, 4, 4, new FluidSolverConfig(.5F, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, .8F), 2048);
	}
	private static double[][] markers(MarkerVelocityTransfer j) { return new double[3][j.markerCount()]; }
	private static double[][] faces(MarkerVelocityTransfer j) {
		return new double[][]{new double[j.faceCount(0)], new double[j.faceCount(1)], new double[j.faceCount(2)]};
	}
	private static void near(double actual, double expected, double tolerance, String label) {
		if (!(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance)) throw new AssertionError(label + ": " + actual + " != " + expected);
	}

	private static void sparseGeometryAndAdjoint() {
		var d = domain();
		for (int z = 1; z <= 2; z++) for (int y = 1; y <= 2; y++) for (int x = 1; x <= 2; x++) d.setVolume(x, y, z, .6F);
		d.ensureGrid(); var j = new MarkerVelocityTransfer(); j.capture(d);
		checkGeometryAndAdjoint(d, j);
		var unit = markers(j); for (double[] axis : unit) Arrays.fill(axis, 1);
		var mac = faces(j); j.apply(unit, mac);
		for (int z = 1; z <= 2; z++) for (int y = 1; y <= 2; y++) for (int x = 1; x <= 2; x++) {
			double flux = mac[0][d.xFaceIndex(x+1,y,z)] - mac[0][d.xFaceIndex(x,y,z)]
					+ mac[1][d.yFaceIndex(x,y+1,z)] - mac[1][d.yFaceIndex(x,y,z)]
					+ mac[2][d.zFaceIndex(x,y,z+1)] - mac[2][d.zFaceIndex(x,y,z)];
			near(flux * .25, 0, 2e-15, "C translation");
		}
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < j.markerCount(); marker++) {
			if (j.coefficientCount(axis, marker) > 8) throw new AssertionError("Nonlocal marker stencil");
			for (int slot = 0; slot < j.coefficientCount(axis, marker); slot++) {
				if (!(j.coefficient(axis, marker, slot) > 0) || !j.supportsFace(axis, j.faceIndex(axis, marker, slot))) throw new AssertionError("Invalid sparse support");
			}
		}
	}

	private static void actualMassAndClosedFaces() {
		var variable = domain();
		variable.bindMaterialRegistry(new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
		if (!variable.tryInsertExactMaterial(1, 1, 1, .2F, 200, 0, 0).transferred()
				|| !variable.tryInsertExactMaterial(1, 1, 1, .2F, 800, 0, 0).transferred()) throw new AssertionError("Variable-mass setup refused");
		var j = new MarkerVelocityTransfer(); j.capture(variable); checkGeometryAndAdjoint(variable, j);
		for (int marker = 0; marker < j.markerCount(); marker++) {
			near(j.massKilograms(marker), variable.unitScale.kilograms(variable.particles.mass(marker)), 0, "Actual SI mass");
		}
		var dense = domain();
		dense.restoreMaterialParticleState(new FlipMaterialParticleState(4, 4, 4, .5F, 1, .125, 1.25e39,
				java.util.List.of(new FluidMaterial("mmm:water", 1000, "mmm:water", 0, Math.PI / 2),
						new FluidMaterial("mmm:dense", 1e40, "mmm:dense", 0, Math.PI / 2)),
				new float[]{.625F, .625F, .625F, .125F}, new float[]{1, 0, 0}, new byte[64], new double[]{1.25e39}, new int[]{1}));
		j.capture(dense); checkGeometryAndAdjoint(dense, j);
		near(j.massKilograms(0), 1.5625e38, 0, "Finite mass beyond binary32");
		var cut = domain(); cut.setSolid(1, 1, 1, true); cut.setSolidMicroMask(2, 2, 1, 1);
		cut.setVolume(2, 1, 1, .2F); cut.setVolume(0, 0, 0, .2F); cut.ensureGrid(); j.capture(cut);
		checkGeometryAndAdjoint(cut, j);
		int closed = cut.xFaceIndex(2, 1, 1), partial = cut.yFaceIndex(2, 2, 1);
		if (cut.geometry.cutCells.xFaceOpen(2,1,1) != 0 || j.supportsFace(0, closed)) throw new AssertionError("Closed cut face retained inertia");
		float aperture = cut.geometry.cutCells.yFaceOpen(2,2,1);
		if (!(aperture > 0 && aperture < 1 && j.supportsFace(1, partial))) throw new AssertionError("Partial cut-face support missing");
		var load = faces(j); load[0][closed] = 7; var markerLoad = markers(j); j.transpose(load, markerLoad);
		for (double[] axis : markerLoad) for (double value : axis) near(value, 0, 0, "Closed-face impulse");
	}

	private static void productionP2GPreservesFiniteMassWeightedVelocities() {
		for (double scale : new double[] {125, 1.25e39}) {
			var d = restoredMassDomain(new double[] {scale, 3 * scale},
					new float[] {1, -2, 3, 5, 2, -1}, .125F, .625F, .625F, .625F);
			var sample = d.sampleVelocity(.625F, .625F, .625F);
			near(sample.x(), 4, 4 * Math.ulp(4F), "Production weighted X");
			near(sample.y(), 1, 4 * Math.ulp(1F), "Production weighted Y");
			near(sample.z(), 0, 1e-7, "Production weighted Z cancellation");
			for (int axis = 0; axis < 3; axis++) {
				double[] actual = axis == 0 ? d.mac.p2gWeightX : axis == 1 ? d.mac.p2gWeightY : d.mac.p2gWeightZ;
				double[] expected = new double[actual.length];
				for (int marker = 0; marker < 2; marker++) {
					float[] geometric = new float[actual.length], scratch = new float[actual.length];
					d.splatFaceField(geometric, scratch, axis, 1.25F, 1.25F, 1.25F, 1, 1);
					for (int face = 0; face < actual.length; face++) expected[face] += geometric[face] * d.particles.mass(marker);
				}
				for (int face = 0; face < actual.length; face++) near(actual[face], expected[face], 0, "Production retained mass");
			}
		}
		for (var d : java.util.List.of(
				restoredMassDomain(new double[] {1.25e39}, new float[] {1, -2, 3}, .125F, .625F, .625F, .625F),
				restoredMassDomain(new double[] {8e307}, new float[] {3, 1, -1}, 1, .5F, .75F, .75F))) {
			var sample = d.sampleVelocity(d.particles.positionX(0), d.particles.positionY(0), d.particles.positionZ(0));
			near(sample.x(), d.particles.velocityX(0), 0, "Production constant X");
			near(sample.y(), d.particles.velocityY(0), 0, "Production constant Y");
			near(sample.z(), d.particles.velocityZ(0), 0, "Production constant Z");
			for (float[] axis : new float[][] {d.mac.velocityX, d.mac.velocityY, d.mac.velocityZ})
				for (float value : axis) if (!Float.isFinite(value)) throw new AssertionError("Finite marker mean polluted a MAC face");
		}
	}

	private static void productionTransferBudgetsUsePhysicalEnergy() {
		var d = restoredMassDomain(new double[] {8e307}, new float[] {3, 0, 0}, 1, .5F, .75F, .75F);
		d.sampleVelocity(.5F, .75F, .75F);
		double expected = 4.5e307;
		near(d.particleKineticEnergyJoules(), expected, Math.ulp(expected), "Finite physical marker energy");
		near(d.gridKineticEnergyJoules(), expected, 2 * Math.ulp(expected), "Finite physical grid energy");
		if (!Double.isInfinite(d.gridKineticEnergySolverUnits())) throw new AssertionError("Energy fixture missed the solver-unit overflow");
		// Exercise the real handoff without a pressure solve: zero previous MAC gives a nonzero FLIP candidate.
		d.gridToParticleStage.capturePreProjectionEnergy();
		d.gridToParticleStage.extrapolateAndTransfer();
		near(d.particles.velocityX(0), 3, 0, "Physical-energy transfer changed constant velocity");
		near(d.particleKineticEnergyJoules(), expected, Math.ulp(expected), "Physical-energy transfer lost the finite marker budget");
	}

	private static FluidDomain restoredMassDomain(double[] masses, float[] velocities, float volume, float x, float y, float z) {
		var d = domain(); float[] positionVolume = new float[4 * masses.length]; int[] materials = new int[masses.length];
		for (int marker = 0; marker < masses.length; marker++) {
			positionVolume[4 * marker] = x; positionVolume[4 * marker + 1] = y;
			positionVolume[4 * marker + 2] = z; positionVolume[4 * marker + 3] = volume; materials[marker] = 1;
		}
		d.restoreMaterialParticleState(new FlipMaterialParticleState(4, 4, 4, .5F, masses.length,
				(double) volume * masses.length, cc.ecisr.mmm.units.SignedDyadic.sum(masses).toDouble(),
				java.util.List.of(new FluidMaterial("mmm:water", 1000, "mmm:water", 0, Math.PI / 2),
						new FluidMaterial("mmm:dense", 1e40, "mmm:dense", 0, Math.PI / 2)),
				positionVolume, velocities, new byte[64], masses, materials));
		return d;
	}

	private static void checkGeometryAndAdjoint(FluidDomain d, MarkerVelocityTransfer j) {
		var v = markers(j); var load = faces(j); var applied = faces(j); var transposed = markers(j);
		for (int axis = 0; axis < 3; axis++) {
			for (int marker = 0; marker < j.markerCount(); marker++) v[axis][marker] = Math.sin(.3 * marker + axis);
			for (int face = 0; face < j.faceCount(axis); face++) load[axis][face] = Math.cos(.7 * face + axis);
		}
		j.apply(v, applied); j.transpose(load, transposed);
		double faceWork = 0, markerWork = 0;
		for (int axis = 0; axis < 3; axis++) {
			double[] numerator = new double[j.faceCount(axis)], denominator = new double[j.faceCount(axis)];
			var open = d.geometry.cutCells.openFaceField(axis);
			// Independent dense oracle invokes the original geometric splat with unit amount.
			for (int marker = 0; marker < j.markerCount(); marker++) {
				float[] geometric = new float[j.faceCount(axis)], scratch = new float[j.faceCount(axis)];
				d.splatFaceField(geometric, scratch, axis, d.particles.positionX(marker) / d.config.cellSize(),
						d.particles.positionY(marker) / d.config.cellSize(), d.particles.positionZ(marker) / d.config.cellSize(), 1, 1);
				for (int face = 0; face < geometric.length; face++) if (open[face] > 0) {
					double weighted = geometric[face] * d.unitScale.kilograms(d.particles.mass(marker));
					denominator[face] += weighted; numerator[face] += weighted * v[axis][marker];
				}
			}
			double sumLoad = 0, sumAdjoint = 0;
			for (int face = 0; face < j.faceCount(axis); face++) {
				near(j.faceMassKilograms(axis, face), denominator[face], 0, "Face SI mass");
				near(applied[axis][face], denominator[face] == 0 ? 0 : numerator[face] / denominator[face], 4e-15, "Dense geometric P2G");
				faceWork += load[axis][face] * applied[axis][face];
				if (j.supportsFace(axis, face)) sumLoad += load[axis][face];
			}
			for (int marker = 0; marker < j.markerCount(); marker++) {
				markerWork += v[axis][marker] * transposed[axis][marker]; sumAdjoint += transposed[axis][marker];
			}
			near(sumAdjoint, sumLoad, 3e-14, "Adjoint total impulse");
		}
		near(markerWork, faceWork, 3e-14, "Adjoint virtual work");
		float[][] published = new float[][]{new float[j.faceCount(0)], new float[j.faceCount(1)], new float[j.faceCount(2)]};
		j.publish(v, published, faces(j));
		for (int axis = 0; axis < 3; axis++) for (int face = 0; face < j.faceCount(axis); face++) {
			if (Float.floatToRawIntBits(published[axis][face]) != Float.floatToRawIntBits((float) applied[axis][face])) throw new AssertionError("Publication uses a different J");
		}
	}

	private static void captureAndPublicationAreAtomic() {
		var d = domain(); d.setVolume(1, 1, 1, .2F); var j = new MarkerVelocityTransfer(); j.capture(d);
		var v = markers(j); for (double[] axis : v) Arrays.fill(axis, .3); var before = faces(j); j.apply(v, before);
		double sourceMass = d.particles.mass(0); d.particles.setMass(0, Double.NaN);
		try { j.capture(d); throw new AssertionError("Invalid mass accepted"); }
		catch (IllegalStateException expected) { }
		finally { d.particles.setMass(0, sourceMass); }
		var after = faces(j); j.apply(v, after); if (!Arrays.deepEquals(before, after)) throw new AssertionError("Failed capture changed J");
		float[][] published = new float[][]{new float[j.faceCount(0)], new float[j.faceCount(1)], new float[j.faceCount(2)]};
		for (float[] axis : published) Arrays.fill(axis, 17);
		Arrays.fill(v[2], 2 * (double) Float.MAX_VALUE);
		try { j.publish(v, published, faces(j)); throw new AssertionError("MAC overflow accepted"); }
		catch (IllegalStateException expected) { }
		for (float[] axis : published) for (float value : axis) if (value != 17) throw new AssertionError("Failed publication wrote MAC");
		published[1]=published[0];
		try { j.publish(v,published,faces(j));throw new AssertionError("Aliased MAC axes accepted"); }
		catch(IllegalArgumentException expected) { }
		for(float[] axis:published)for(float value:axis)if(value!=17)throw new AssertionError("Aliased publication wrote MAC");
	}
}
