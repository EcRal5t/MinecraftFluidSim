package cc.ecisr.mmm.fluid;

import java.util.ArrayList;

/** Binary32 publication and support checks for fill-constrained first-moment rules. */
public final class CellRemapMomentsValidation {
	private CellRemapMomentsValidation() { }

	public static void main(String[] args) {
		sweptLiquidMomentsKeepThinTangentialCentroids();
		limitedFluxMixesTheSamePositiveMomentWeights();
		thinSlabRemapRetainsItsTangentialMoment();
		if (args.length == 1 && args[0].equals("sweep-only")) return;
		fullHomogeneousReconstructionKeepsMacPatch(false);
		fullHomogeneousReconstructionKeepsMacPatch(true);
		geometryRemapUsesPlannedCapacity();
		partialLayerContractsOnlyItsConstrainedAxis();
		if (args.length == 1 && args[0].equals("footprint-only")) return;
		wholeCellUpperEndpointUsesActualClassifier();
		unchangedTensorReportsAllStorageAxes();
		cutMicroBoxUpperEndpointUsesActualClassifier();
		logicalStepRetainsAllSubstepMoments();
		System.out.println("Cell remap moment publication validation passed");
	}

	private static void sweptLiquidMomentsKeepThinTangentialCentroids() {
		var cut = new FlipCutCellGeometry(2, 2, 2);
		var geometry = new LiquidFluxGeometry(cut);
		double[] moments = new double[4];
		for (int axis = 0; axis < 3; axis++) for (boolean positive : new boolean[] {false, true}) {
			geometry.sweptMoments(0, 1, axis, .125, positive, 0, 0, 0, 0, moments);
			precise(moments[0], .125, "Whole-box swept volume");
			for (int a = 0; a < 3; a++) precise(moments[a + 1], a == axis ? (positive ? .4375 : -.4375) * .125 : 0,
					"Whole-box swept moment");
		}
		for (double scale : new double[] {1, 1e-7, 1e-30}) {
			LiquidFluxGeometry.unitCubeMoments(.5 * scale, scale, 2 * scale, .1 * scale, moments);
			precise(moments[0], .001 / 6, "Tetrahedral swept volume");
			for (int a = 0; a < 3; a++) precise(moments[a + 1], moments[0] * (.05 / (1 << a)), "Tetrahedral moment");
		}
		geometry.sweptMoments(0, 1, 1, .125, true, 1, 0, 0, .1, moments);
		precise(moments[0], .0125, "Thin slab swept volume");
		precise(moments[1] / moments[0], -.45, "Thin slab tangential centroid");
		cut.replaceMask(0, 0xAA); cut.replaceMask(1, 0xAA);
		geometry.sweptMoments(0, 1, 1, .125, true, 0, 0, 0, 0, moments);
		precise(moments[0], .0625, "Cut swept volume");
		precise(moments[1] / moments[0], -.25, "Cut tangential centroid");
	}

	private static void limitedFluxMixesTheSamePositiveMomentWeights() {
		var flux = new ConservativeLiquidFlux(1, 2, 1);
		double[] volume = {.25, .95};
		var exact = new cc.ecisr.mmm.units.SignedDyadic[] {
				cc.ecisr.mmm.units.SignedDyadic.of(volume[0]), cc.ecisr.mmm.units.SignedDyadic.of(volume[1])};
		flux.velocity[0] = .1;
		flux.plan(volume, new boolean[2], 1, exact);
		// B=.025, G=.1, lambda=(1-.975)/.075=1/3: geometric transfer fraction is 2/3.
		precise(flux.geometricBlend(0), 2.0 / 3, "FCT geometric moment weight");
		double[] moments = new double[4]; flux.sweptMoments(0, moments);
		precise(moments[0], .1, "FCT geometric sweep");
		check(flux.exactFlux[0].signum() > 0 && flux.resultingVolumes()[1].compareTo(cc.ecisr.mmm.units.SignedDyadic.of(1)) <= 0,
				"FCT moment mixture changed capacity-constrained inventory");
	}

	private static void precise(double actual, double expected, String label) {
		check(Math.abs(actual - expected) <= 3e-13 * Math.max(1, Math.abs(expected)),
				label + ": expected=" + expected + " actual=" + actual);
	}

	private static void thinSlabRemapRetainsItsTangentialMoment() {
		var domain = new FluidDomain(5, 5, 5,
				new FluidSolverConfig(1, 1000, 0, .125F, 200, 1e-5F, .45F, .002F, .8F), 128);
		domain.setVolume(1, 2, 2, .2F); domain.setVolume(2, 2, 2, .1F);
		int donor = domain.cellIndex(2, 2, 2), receiver = domain.cellIndex(2, 3, 2);
		for (int p = 0; p < domain.particleCount(); p++) {
			if (domain.particleCellIndex(p) == donor)
				domain.particles.setPositionX(p, 2.05F);
			domain.particles.setVelocityY(p, 1);
		}
		domain.rebuildGridFromParticles();
		var flux = new ConservativeLiquidFlux(5, 5, 5);
		flux.geometry(new LiquidFluxGeometry(domain.geometry.cutCells));
		for (int f = 0; f < flux.velocity.length; f++) if (flux.axis[f] == 1) flux.velocity[f] = 1;
		flux.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, .125, domain.markerGrid.exactCellVolumeInventory);
		java.util.Arrays.fill(domain.gridToParticleStage.advectionCells(), true);
		var remap = new CellCarrierRemapPlan(domain, flux, .125F, new BallisticLiquidRemap(domain.cellCount));
		double mass = 0, moment = 0, receivedMass = 0, receivedMoment = 0;
		for (var marker : remap.markers) if (marker.cell() == donor || marker.cell() == receiver) {
			mass += marker.mass(); moment += marker.mass() * marker.offsetX();
			if (marker.cell() == receiver) { receivedMass += marker.mass(); receivedMoment += marker.mass() * marker.offsetX(); }
		}
		System.out.printf("thin slab mass=%g CM=%g received=%g CM=%g%n",mass,moment/mass,receivedMass,receivedMoment/receivedMass);
		check(receivedMass > 0, "Thin slab has no positive face transfer");
		close(moment / mass, -.45, "Thin slab total tangential centroid");
		close(receivedMoment / receivedMass, -.45, "Thin slab receiving tangential centroid");
	}

	private static FluidDomain domain() {
		return new FluidDomain(6, 6, 6,
				new FluidSolverConfig(0.1F, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), 64);
	}

	/** A full uniform-density cell has its geometric centroid and the original tensor rule. */
	private static void fullHomogeneousReconstructionKeepsMacPatch(boolean multipleGroups) {
		int n = 5, column = 2;
		var domain = new FluidDomain(n, n, n,
				new FluidSolverConfig(1, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), n * n * n * 8);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++)
			domain.setVolume(x, y, z, 1);
		var materials = multipleGroups ? new FluidMaterialRegistry(java.util.List.of(
				new FluidMaterial("mmm:patch_a", 1000, "mmm:patch", 0, Math.PI / 2),
				new FluidMaterial("mmm:patch_b", 1000, "mmm:patch", 0, Math.PI / 2))) : null;
		for (int p = 0; p < domain.particleCount(); p++) {
			if (multipleGroups) domain.setMarkerMaterial(p, materials, p & 1);
			if (domain.particleCellIndex(p) % n == column)
				domain.particles.setPositionX(p, domain.particles.positionX(p) + 0.2F);
		}
		domain.rebuildGridFromParticles();
		var flux = new ConservativeLiquidFlux(n, n, n);
		flux.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, 0, domain.markerGrid.exactCellVolumeInventory);
		var remap = new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(domain.cellCount), true);
		domain.coordinator.publishCellRemap(CarrierMutationPlan.wholesaleCount(domain.carrierMutationRevision(), domain.particleCount()), remap);

		// Assign one global affine field after publication, isolating the reconstructed sampling rule.
		for (int p = 0; p < domain.particleCount(); p++) domain.particles.setVelocityZ(p, domain.particles.positionX(p) - 2.5F);
		domain.rebuildGridFromParticles();
		double[] shearError = new double[3], shearWeight = new double[3];
		for (int x = 1; x <= 3; x++) {
			int face = domain.zFaceIndex(x, 2, 2);
			shearError[x - 1] = domain.mac.velocityZ[face] - (x - 2.0);
			shearWeight[x - 1] = domain.mac.p2gWeightZ[face];
		}
		double curvature = shearError[0] - 2 * shearError[1] + shearError[2];
		for (int p = 0; p < domain.particleCount(); p++) {
			domain.particles.setVelocityZ(p, 0);
			domain.particles.setVelocityX(p, domain.particles.positionX(p) - 2.5F);
		}
		domain.rebuildGridFromParticles();
		int left = domain.xFaceIndex(2, 2, 2), right = domain.xFaceIndex(3, 2, 2);
		double divergenceError = domain.mac.velocityX[right] - domain.mac.velocityX[left] - 1;
		System.out.printf("full-cell MAC patch groups=%d shearError=%s shearWeight=%s curvature=%g normalWeight=%g/%g divergenceError=%g%n",
				multipleGroups ? 2 : 1, java.util.Arrays.toString(shearError), java.util.Arrays.toString(shearWeight), curvature,
				domain.mac.p2gWeightX[left], domain.mac.p2gWeightX[right], divergenceError);
		check(domain.particleCount() == n * n * n * 8, "Full-cell remap changed the tensor point count");
		for (int p = 0; p < domain.particleCount(); p++) {
			float[] point = {domain.particles.positionX(p), domain.particles.positionY(p), domain.particles.positionZ(p)};
			for (int axis = 0; axis < point.length; axis++)
				check(Math.abs(point[axis] - Math.floor(point[axis]) - 0.5) == (multipleGroups && axis == 1 ? 0 : 0.25),
						"Full homogeneous remap retained a displaced tensor rule");
		}
		for (int c = 0; c < domain.cellCount; c++) {
			check(domain.markerGrid.exactCellMarkerVolume[c] == 1 && domain.projection.pressureVolume[c] == 1,
					"Full homogeneous reconstruction changed cell volume or pressure inventory");
			close(domain.markerGrid.exactCellMarkerMass[c], 1000, "Full-cell density");
		}
		for (int i = 0; i < shearError.length; i++) {
			close(shearError[i], 0, "Affine shear face velocity");
			close(shearWeight[i], 1, "Uniform-density shear face weight");
		}
		close(curvature, 0, "Affine shear curvature");
		close(domain.mac.p2gWeightX[left], 1, "Uniform-density left face weight");
		close(domain.mac.p2gWeightX[right], 1, "Uniform-density right face weight");
		close(domain.mac.velocityX[left], -0.5, "Affine normal left face velocity");
		close(domain.mac.velocityX[right], 0.5, "Affine normal right face velocity");
		close(divergenceError, 0, "Affine normal divergence");
	}

	private static void close(double actual, double expected, String label) {
		double roundoff = 32 * Math.ulp((float) Math.max(1, Math.abs(expected)));
		check(Math.abs(actual - expected) <= roundoff, label + ": expected=" + expected + " actual=" + actual);
	}

	private static void partialLayerContractsOnlyItsConstrainedAxis() {
		var domain = domain(); int cell = domain.cellIndex(3, 3, 3);
		var offsets = new ArrayList<double[]>(); double[] masses = new double[8];
		for (int p = 0; p < 8; p++) {
			offsets.add(new double[] {(p & 1) == 0 ? -.25 : .25, (p & 2) == 0 ? -.25 : .25, (p & 4) == 0 ? -.25 : .25});
			masses[p] = .125;
		}
		var original = new ArrayList<>(offsets);
		double[] target = {.45, 0, .1}, projection = new double[3], storage = new double[3];
		check(CellRemapMoments.fit(domain, cell, offsets, masses, target, projection, storage, .45), "Feasible thin-layer rule did not move");
		double[] mean = new double[3], variance = new double[3];
		for (int p = 0; p < 8; p++) for (int a = 0; a < 3; a++) {
			mean[a] += masses[p] * offsets.get(p)[a]; variance[a] += masses[p] * Math.pow(offsets.get(p)[a] - target[a], 2);
			check(Math.abs(original.get(p)[a]) == .25, "Partial reconstruction mutated shared tensor offsets");
		}
		for (int a = 0; a < 3; a++) {
			check(Math.abs(mean[a] - target[a]) <= 8 * Math.ulp(1.0), "Feasible partial centroid was projected");
			check(projection[a] == 0, "Feasible partial rule reported a projected first moment");
		}
		check(variance[0] > 0 && variance[0] < .002501, "Normal span did not follow its available open interval");
		check(variance[1] == .0625 && Math.abs(variance[2] - .0625) <= Math.ulp(1.0), "Normal contraction changed tangential covariance");
		checkPublished(domain, cell, offsets, masses, target, projection, storage);
		// Saturated capacity keeps the original rule even when its target is unreachable.
		CellRemapMoments.fit(domain, cell, original, masses, target, projection, storage, 0);
		for (double[] point : original) for (double coordinate : point) check(Math.abs(coordinate) == .25, "Full capacity contracted its canonical rule");
		check(projection[0] == -.45 && projection[2] == -.1, "Full capacity hid its unreachable moment");
	}

	/** Representation changes supply their own final pore capacity, independently of domain previews. */
	private static void geometryRemapUsesPlannedCapacity() {
		var domain = new FluidDomain(2, 2, 2,
				new FluidSolverConfig(1, 1000, 9.81F, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), 64);
		domain.setVolume(0, 0, 0, 0.5F);
		for (int p = 0; p < domain.particleCount(); p++) {
			domain.particles.setPositionX(p, domain.particles.positionX(p) + 0.2F);
			domain.particles.setPositionY(p, domain.particles.positionY(p) + 0.2F);
		}
		double beforePotential = domain.gravitationalPotentialEnergyJoules();
		domain.rebuildGridFromParticles();
		var flux = new ConservativeLiquidFlux(2, 2, 2);
		java.util.Arrays.fill(flux.exactFlux, cc.ecisr.mmm.units.SignedDyadic.ZERO);
		var capacities = new cc.ecisr.mmm.units.SignedDyadic[domain.cellCount];
		java.util.Arrays.fill(capacities, cc.ecisr.mmm.units.SignedDyadic.of(1));
		capacities[0] = cc.ecisr.mmm.units.SignedDyadic.of(0.5);
		flux.geometricResult(domain.markerGrid.exactCellVolumeInventory, capacities);
		var remap = new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(domain.cellCount), true);
		double moment = 0;
		for (var marker : remap.markers) moment += marker.volume() * marker.offsetX();
		check(moment == 0 && remap.momentAudit.projectedGroups() == 1,
				"Saturated representation remap used a different final capacity from its flux plan");
		double afterPotential = 0;
		for (var marker : remap.markers) afterPotential += domain.unitScale.kilograms(marker.mass())
				* domain.config.gravity() * (0.5 + marker.offsetY());
		var diagnostic = remap.momentAudit.stats();
		check(diagnostic.signedMassResidual().y() < 0, "Moment projection lost its potential-energy sign");
		close(diagnostic.gravitationalPotentialErrorJoules(domain.config.gravity()), afterPotential - beforePotential,
				"Geometric potential error");
	}

	/** Candidate statistics sum every substep and retain an immutable observation after later steps. */
	private static void logicalStepRetainsAllSubstepMoments() {
		var domain = new FluidDomain(9, 4, 4,
				new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), 512);
		domain.setVolume(1, 1, 1, 0.75F); domain.setVolume(2, 1, 1, 0.25F);
		for (int p = 0; p < domain.particleCount(); p++) domain.particles.setVelocityX(p, 0.2F);
		domain.minimumMechanicalSubsteps = 3;
		var step = domain.step().liquidTransport();
		int lastGroups = domain.cellLiquidVolumeTransport.momentAudit.reconstructedGroups();
		check(lastGroups > 0 && step.reconstructedGroups() > lastGroups,
				"Logical transport statistics retained only their last substep");
		double signed = step.signedMassResidual().x();
		domain.step();
		check(step.signedMassResidual().x() == signed, "Later transport mutated a published moment diagnostic");
		for (int p = 0; p < domain.particleCount(); p++) domain.particles.setVelocityX(p, 0);
		domain.gridDirty = true;
		check(domain.step().liquidTransport().reconstructedGroups() == 0,
				"A stationary step reused an earlier remap diagnostic");
	}

	private static void wholeCellUpperEndpointUsesActualClassifier() {
		var domain = domain(); int cell = domain.cellIndex(4, 0, 0);
		double[] first = {-0.25, 0, 0}, second = {0.25, 0, 0};
		var offsets = new ArrayList<double[]>(); offsets.add(first); offsets.add(second);
		double[] masses = {0.5, 0.5}, target = {1, 0, 0}, projection = new double[3], storage = new double[3];
		check(CellRemapMoments.fit(domain, cell, offsets, masses, target, projection, storage, 0.25), "Projected rule did not translate");
		check(first[0] == -0.25 && second[0] == 0.25, "Translation mutated shared input offsets");
		check(Math.abs(offsets.get(1)[0] - offsets.get(0)[0] - 0.5) <= 8 * Math.ulp(1.0F),
				"Classifier endpoint contracted the rule beyond binary32 publication slack");
		check(projection[0] < 0, "Unreachable upper target was not reported");
		checkPublished(domain, cell, offsets, masses, target, projection, storage);
	}

	private static void unchangedTensorReportsAllStorageAxes() {
		var domain = domain(); int cell = domain.cellIndex(3, 3, 3);
		var offsets = new ArrayList<double[]>(); double[] masses = new double[8];
		for (int p = 0; p < 8; p++) {
			offsets.add(new double[] {(p & 1) == 0 ? -0.25 : 0.25,
					(p & 2) == 0 ? -0.25 : 0.25, (p & 4) == 0 ? -0.25 : 0.25});
			masses[p] = 0.125;
		}
		var original = new ArrayList<>(offsets);
		double[] target = new double[3], projection = new double[3], storage = new double[3];
		check(!CellRemapMoments.fit(domain, cell, offsets, masses, target, projection, storage, 0.25), "Unchanged tensor lost its fast path");
		for (int p = 0; p < 8; p++) {
			check(offsets.get(p) == original.get(p), "Audit replaced an unchanged tensor point");
			for (int a = 0; a < 3; a++) check(Math.abs(offsets.get(p)[a]) == 0.25, "Audit quantized an unchanged tensor offset");
		}
		for (int a = 0; a < 3; a++) check(storage[a] != 0 && projection[a] == 0, "Unmoved axis concealed publication roundoff");
		checkPublished(domain, cell, offsets, masses, target, projection, storage);
	}

	private static void cutMicroBoxUpperEndpointUsesActualClassifier() {
		var domain = domain(); int cell = domain.cellIndex(2, 0, 0);
		domain.geometry.cutCells.replaceMask(cell, 0xFE);
		var offsets = new ArrayList<double[]>(); offsets.add(new double[] {-0.25, -0.25, -0.25});
		double[] masses = {1}, target = {1, -0.25, -0.25}, projection = new double[3], storage = new double[3];
		CellRemapMoments.fit(domain, cell, offsets, masses, target, projection, storage, 0.25);
		float x = (float) ((2.5 + offsets.getFirst()[0]) * domain.config.cellSize());
		check(Math.floor(x / (domain.config.cellSize() / 2)) == 4, "Publication entered the solid positive-x micro box");
		checkPublished(domain, cell, offsets, masses, target, projection, storage);
	}

	private static void checkPublished(FluidDomain domain, int cell, ArrayList<double[]> offsets,
			double[] masses, double[] target, double[] projection, double[] storage) {
		int[] coordinate = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
		double h = domain.config.cellSize(); double[] actual = new double[3];
		for (int p = 0; p < masses.length; p++) {
			float[] point = new float[3];
			for (int a = 0; a < 3; a++) {
				point[a] = (float) ((coordinate[a] + 0.5 + offsets.get(p)[a]) * h);
				actual[a] += masses[p] * (point[a] / h - coordinate[a] - 0.5);
			}
			check(domain.particleCellIndex(point[0], point[1], point[2]) == cell, "Published inventory changed cell ownership");
			check(!domain.geometry.cutCells.pointInsideSolid(point[0], point[1], point[2], domain.config.cellSize()), "Published point entered solid support");
		}
		for (int a = 0; a < 3; a++) check(storage[a] == actual[a] - target[a] - projection[a], "Storage audit differs from published first moment");
	}

	private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
