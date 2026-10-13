package mmm.fluid;

import java.util.ArrayList;

/** Binary32 publication and support checks for fill-constrained first-moment rules. */
public final class CellRemapMomentsValidation {
	private CellRemapMomentsValidation() { }

	public static void main(String[] args) {
		if (args.length > 1 || args.length == 1 && !java.util.List.of("transport-moments", "joint-moments", "sweep-only", "footprint-only").contains(args[0]))
			throw new IllegalArgumentException("Unknown remap moments validation entry");
		if (args.length == 1 && args[0].equals("joint-moments")) {
			thinJointRuleKeepsActualRank(); jointRuleKeepsIndependentVelocityModes(); thinAffineRuleKeepsCrossMoments();
			unequalSourceMassKeepsFirstMoment();
			chemicalQuadratureRetainsJointMomentsWithFewQuanta(); smallTransferredGroupsRetainShear();
			System.out.println("Positive joint liquid moments validation passed"); return;
		}
		if (args.length == 1 && args[0].equals("transport-moments")) {
			scalarTransportKeepsUnexcitedSpatialInertia();
			directionalTransportPartitionsActualMomentumAndKineticEnergy();
			System.out.println("Cell remap transport moments validation passed");
			return;
		}
		sweptLiquidMomentsKeepThinTangentialCentroids();
		limitedFluxMixesTheSamePositiveMomentWeights();
		thinSlabRemapRetainsItsTangentialMoment();
		if (args.length == 1 && args[0].equals("sweep-only")) return;
		fullHomogeneousReconstructionKeepsMacPatch(false);
		fullHomogeneousReconstructionKeepsMacPatch(true);
		reconstructedFieldsRetainAllShearAxes();
		reconstructedFieldsRetainRotationAxes();
		smallTransferredGroupsRetainShear();
		chemicalQuadratureRetainsJointMomentsWithFewQuanta();
		scalarTransportKeepsUnexcitedSpatialInertia();
		directionalTransportPartitionsActualMomentumAndKineticEnergy();
		thinJointRuleKeepsActualRank();
		jointRuleKeepsIndependentVelocityModes();
		thinAffineRuleKeepsCrossMoments(); unequalSourceMassKeepsFirstMoment();
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
		var exact = new mmm.units.SignedDyadic[] {
				mmm.units.SignedDyadic.of(volume[0]), mmm.units.SignedDyadic.of(volume[1])};
		flux.velocity[0] = .1;
		flux.plan(volume, new boolean[2], 1, exact);
		// B=.025, G=.1, lambda=(1-.975)/.075=1/3: geometric transfer fraction is 2/3.
		precise(flux.geometricBlend(0), 2.0 / 3, "FCT geometric moment weight");
		double[] moments = new double[4]; flux.sweptMoments(0, moments);
		precise(moments[0], .1, "FCT geometric sweep");
		check(flux.exactFlux[0].signum() > 0 && flux.resultingVolumes()[1].compareTo(mmm.units.SignedDyadic.of(1)) <= 0,
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

	/** An ordinary scalar remap preserves spatial inertia even along an unexcited coordinate. */
	private static void scalarTransportKeepsUnexcitedSpatialInertia() {
		var domain = new FluidDomain(6, 6, 6,
				new FluidSolverConfig(.5F, 1000, 0, .05F, 200, 1e-5F, .45F, .002F, 0), 2048);
		byte[] masks = new byte[216];
		for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++)
			if (x == 0 || x == 5 || y == 0 || y == 5 || z == 0 || z == 5)
				masks[domain.cellIndex(x, y, z)] = (byte) 255;
		float[] positions = new float[64], velocities = new float[48];
		for (int cell = 0; cell < 2; cell++) for (int slot = 0; slot < 8; slot++) {
			int p = 8 * cell + slot;
			positions[4 * p] = (2 + cell + .25F + .5F * (slot & 1)) * .5F;
			positions[4 * p + 1] = (2.25F + .5F * (slot >>> 1 & 1)) * .5F;
			positions[4 * p + 2] = (2.25F + .5F * (slot >>> 2 & 1)) * .5F;
			positions[4 * p + 3] = .125F;
			velocities[3 * p] = (slot & 2) == 0 ? 0 : .5F;
		}
		domain.restoreParticleState(new FlipParticleState(6, 6, 6, .5F, 16, 2, positions, velocities, masks));
		domain.bindMaterialRegistry(new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER)));
		domain.bindChemistry(mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY);
		for (int p = 8; p < 16; p++) domain.chemicalParcels.updateIndicator(p, true);
		domain.sealDomainBoundary(); domain.bootstrapGasState("");
		long[] species = domain.chemicalParcels.speciesTotals(domain.particleCount());
		double before = transverseSecondMoment(domain);
		precise(before, 3.90625, "Original unexcited spatial inertia");
		domain.ensureGrid(); domain.gridToParticleStage.prepareAdvectionCells();
		domain.cellLiquidVolumeTransport = new CellLiquidVolumeTransport(domain);
		domain.cellLiquidVolumeTransport.advect(.05F);
		double crossed = 0;
		for (int p = 0; p < domain.particleCount(); p++)
			if (domain.particleCellIndex(p) == domain.cellIndex(3, 2, 2) && !domain.chemicalParcels.indicator(p))
				crossed += domain.particles.volume(p);
		check(crossed > 0, "Scalar remap has no actual labelled face transfer");
		check(domain.totalVolumeExact() == 2 && domain.totalMass() == 2000,
				"Scalar remap changed its raw volume or mass");
		check(java.util.Arrays.equals(species, domain.chemicalParcels.speciesTotals(domain.particleCount())),
				"Scalar remap changed its exact species inventory");
		close(transverseSecondMoment(domain), before, "Unexcited transverse spatial inertia");
	}

	private static double transverseSecondMoment(FluidDomain domain) {
		double result = 0;
		for (int p = 0; p < domain.particleCount(); p++) {
			double z = domain.particles.positionZ(p) - 1.25;
			result += domain.unitScale.kilograms(domain.particles.mass(p)) * z * z;
		}
		return result;
	}

	/** Positive and negative shear shares use the same raw source weights for mass, P and K. */
	private static void directionalTransportPartitionsActualMomentumAndKineticEnergy() {
		var domain = new FluidDomain(3, 3, 3, FluidSolverConfig.prototypeDefaults(), 32);
		domain.setVolume(1, 1, 1, 1);
		var original = new ArrayList<Integer>();
		for (int p = 0; p < domain.particleCount(); p++) {
			original.add(p);
			domain.particles.setVelocityX(p, domain.particles.positionY(p) / domain.config.cellSize() - 1.5F);
		}
		double[][] preferences = new double[2][8];
		for (int micro = 0; micro < 8; micro++) preferences[(micro & 2) == 0 ? 1 : 0][micro] = 1;
		var pieces = new mmm.units.SignedDyadic[] {
				mmm.units.SignedDyadic.of(.125), mmm.units.SignedDyadic.of(.0625),
				mmm.units.SignedDyadic.of(.8125)};
		var result = new CellRemapTransportMoments(domain, domain.cellIndex(1, 1, 1), original).allocate(pieces, preferences);
		double[] expectedMass = {125, 62.5, 812.5}, expectedMomentum = {31.25, -15.625, -15.625};
		double[] expectedKinetic = {7.8125, 3.90625, 50.78125}; // K is the unhalved raw mass-weighted square.
		for (int share = 0; share < pieces.length; share++) {
			precise(result.mass()[share], expectedMass[share], "Directional share mass");
			precise(result.momentum()[share][0], expectedMomentum[share], "Directional share momentum");
			precise(result.momentum()[share][1], 0, "Directional share transverse momentum");
			precise(result.momentum()[share][2], 0, "Directional share transverse momentum");
			precise(result.kinetic()[share], expectedKinetic[share], "Directional share kinetic moment");
			check(result.massRounding()[share].isZero() && result.kineticRounding()[share].isZero(),
					"Dyadic directional moments acquired a rounding remainder");
		}
		for (int i = 0; i < original.size(); i++) {
			var sum = mmm.chemistry.ExactRational.ZERO;
			for (var weights : result.markerWeights()) {
				check(weights[i].signum() >= 0, "Directional transport allocated negative source supply");
				sum = sum.add(weights[i]);
			}
			check(sum.equals(mmm.chemistry.ExactRational.ONE), "Retained share does not close its source marker exactly");
			boolean positive = domain.particles.velocityX(original.get(i)) > 0;
			check(result.markerWeights()[positive ? 1 : 0][i].isZero(), "Directional shear borrowed the opposite source support");
		}
		microAggregatesMatchCarrierReference();
	}

	private static void microAggregatesMatchCarrierReference() {
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.3F, 1000, 0, .05F, 200, 1e-5F, .45F, .002F, 0), 64);
		float h = domain.config.cellSize(); float[] positions = new float[256], velocities = new float[192];
		for (int m = 0; m < 8; m++) for (int slot = 0; slot < 8; slot++) {
			int p = 8 * m + slot;
			for (int a = 0; a < 3; a++) {
				positions[4 * p + a] = (1 + .5F * (m >>> a & 1) + .125F + .25F * (slot >>> a & 1)) * h;
				velocities[3 * p + a] = (float) ((p % 7 - 3) * (a + 1) / 13.0);
			}
			positions[4 * p + 3] = 1F / 64;
		}
		domain.restoreParticleState(new FlipParticleState(3, 3, 3, h, 64, 1, positions, velocities, new byte[27]));
		var source = new ArrayList<Integer>();
		for (int p = 0; p < 64; p++) {
			source.add(p); domain.particles.setMass(p, Math.scalb(1 + (p % 5) / 7.0, p % 9 - 4));
		}
		int cell = domain.cellIndex(1, 1, 1); var moments = new CellRemapTransportMoments(domain, cell, source);
		var pieces = new mmm.units.SignedDyadic[] {mmm.units.SignedDyadic.of(.25), mmm.units.SignedDyadic.of(.125),
				mmm.units.SignedDyadic.of(.25), mmm.units.SignedDyadic.of(.0625), mmm.units.SignedDyadic.of(.3125)};
		double[][] preferences = new double[4][8];
		for (int d = 0; d < 4; d++) for (int m = 0; m < 8; m++) preferences[d][m] = .1 + .03 * (1 + d * m);
		compareCarrierMoments(domain, cell, moments.allocate(pieces, preferences));
		// The second row needs all of microbox zero, forcing earlier supply to be reassigned.
		java.util.Arrays.fill(preferences[0], 0); java.util.Arrays.fill(preferences[1], 0);
		preferences[0][0] = preferences[0][1] = preferences[0][2] = 1; preferences[1][0] = 1;
		compareCarrierMoments(domain, cell, moments.allocate(pieces, preferences));
		compareCarrierMoments(domain, cell, moments.allocate(new mmm.units.SignedDyadic[] {mmm.units.SignedDyadic.ZERO,
				mmm.units.SignedDyadic.of(1)}, new double[1][8]));
		compareCarrierMoments(domain, cell, moments.allocate(new mmm.units.SignedDyadic[] {mmm.units.SignedDyadic.of(1),
				mmm.units.SignedDyadic.ZERO}, new double[][] {{1, 1, 1, 1, 1, 1, 1, 1}}));
	}

	/** Independent per-carrier evaluation retains the original multiplication and observation order. */
	private static void compareCarrierMoments(FluidDomain domain, int cell, CellRemapTransportMoments.Result result) {
		int[] coordinate = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
		for (int d = 0; d < result.markerWeights().length; d++) {
			var mass = mmm.chemistry.ExactRational.ZERO; var kinetic = mass;
			var momentum = new mmm.chemistry.ExactRational[] {mass, mass, mass};
			var position = momentum.clone(); var motion = momentum.clone();
			for (int i = 0; i < result.ordinals().length; i++) {
				int p = result.ordinals()[i]; var weight = result.markerWeights()[d][i];
				check(weight.signum() >= 0, "Aggregate moment used a negative carrier weight");
				var wm = rawRational(domain.particles.mass(p)).multiply(weight);
				var wv = rawRational(domain.particles.volume(p)).multiply(weight); mass = mass.add(wm);
				double[] xyz = {domain.particles.positionX(p), domain.particles.positionY(p), domain.particles.positionZ(p)};
				double[] uvw = {domain.particles.velocityX(p), domain.particles.velocityY(p), domain.particles.velocityZ(p)};
				for (int a = 0; a < 3; a++) {
					var u = rawRational(uvw[a]);
					momentum[a] = momentum[a].add(wm.multiply(u)); kinetic = kinetic.add(wm.multiply(u.multiply(u)));
					position[a] = position[a].add(wv.multiply(rawRational(xyz[a] / domain.config.cellSize() - coordinate[a] - .5)));
					motion[a] = motion[a].add(wv.multiply(u));
				}
			}
			checkSameObservation(result.mass()[d], mass, "Aggregate mass"); checkSameObservation(result.kinetic()[d], kinetic, "Aggregate kinetic moment");
			check(result.massRounding()[d].equals(rawRational(result.mass()[d]).subtract(mass)), "Aggregate mass rounding changed");
			check(result.kineticRounding()[d].equals(rawRational(result.kinetic()[d]).subtract(kinetic)), "Aggregate kinetic rounding changed");
			for (int a = 0; a < 3; a++) {
				checkSameObservation(result.momentum()[d][a], momentum[a], "Aggregate momentum");
				checkSameObservation(result.volumeMoment()[d][a], position[a], "Aggregate volume moment");
				checkSameObservation(result.volumeMotion()[d][a], motion[a], "Aggregate volume motion");
				check(result.momentumRounding()[d][a].equals(rawRational(result.momentum()[d][a]).subtract(momentum[a])), "Aggregate momentum rounding changed");
			}
		}
		for (int i = 0; i < result.ordinals().length; i++) {
			var sum = mmm.chemistry.ExactRational.ZERO;
			for (var weights : result.markerWeights()) sum = sum.add(weights[i]);
			check(sum.equals(mmm.chemistry.ExactRational.ONE), "Aggregate source weights do not close exactly");
		}
	}

	private static mmm.chemistry.ExactRational rawRational(double value) {
		return PlicComponentGeometry.ratio(mmm.units.SignedDyadic.of(value), mmm.units.SignedDyadic.of(1));
	}
	private static void checkSameObservation(double actual, mmm.chemistry.ExactRational expected, String label) {
		check(Double.doubleToRawLongBits(actual) == Double.doubleToRawLongBits(expected.toDouble()), label + " changed binary64 rounding");
	}

	/** Four positive points from the failing representation remap have no independent velocity mode. */
	private static void thinJointRuleKeepsActualRank() {
		double[][] source = {
				{-.2500079617206558, -.25000802408127815, -.24999999999999994, .10578703880310059, .058233842253685, 0},
				{.24999203827934421, -.250280105132629, -.25001930988247867, .10578703880310059, .047350600361824036, -7.723952876403928E-4},
				{-.25028072897048986, .24999197591872188, -.25014213253955736, .09487634897232056, .058233842253685, -.005685301497578621},
				{.24971927102951016, .24971989496050326, -.2501366097733245, .09487634897232056, .047350604087114334, -.005464390851557255}};
		for (int axis = 0; axis < 3; axis++) for (boolean merged : new boolean[] {false, true}) {
			var moments = new CellRemapSpatialMoments(); var part = new CellRemapSpatialMoments();
			double[][] points = new double[4][6]; double[] weights = new double[4];
			for (int p = 0; p < 4; p++) {
				weights[p] = 123.97503480315208;
				for (int a = 0; a < 3; a++) {
					points[p][a] = source[p][(a + axis) % 3]; points[p][a + 3] = source[p][3 + (a + axis) % 3];
				}
				(merged && p >= 2 ? part : moments).add(weights[p], java.util.Arrays.copyOf(points[p], 3),
						java.util.Arrays.copyOfRange(points[p], 3, 6));
			}
			if (merged) moments.add(part);
			check(moments.spatialRank() == 3 && moments.minimumCount() == 4, "Thin four-point rule gained an artificial velocity mode");
			boolean refused = false;
			try { moments.fit(new double[] {1, 1, 1}, new double[3][3]); }
			catch (CellRemapSpatialMoments.FitFailure expected) { refused = expected.failure == CellRemapSpatialMoments.Failure.INSUFFICIENT_NODES; }
			check(refused, "Thin joint rule accepted fewer nodes than its actual rank");
			verifyJointRule(moments, weights, points);
			double alpha = .125; moments.advancePositions(alpha);
			for (double[] point : points) for (int a = 0; a < 3; a++) point[a] += alpha * point[a + 3];
			verifyJointRule(moments, weights, points);
			moments.projectPositionAxis(axis); double[] centroid = moments.centroid();
			for (double[] point : points) point[axis] = centroid[axis];
			verifyJointRule(moments, weights, points);
		}
	}

	private static void verifyJointRule(CellRemapSpatialMoments moments, double[] weights, double[][] source) {
		verifyJointRule(moments, weights, source, weights);
	}

	private static void verifyJointRule(CellRemapSpatialMoments moments, double[] weights, double[][] source, double[] sourceWeights) {
		double[][] preferred = new double[weights.length][3];
		for (int p = 0; p < weights.length; p++) System.arraycopy(source[p % source.length], 0, preferred[p], 0, 3);
		var result = moments.fit(weights, preferred);
		var probability = probabilities(sourceWeights); var outputProbability = probabilities(weights);
		var expectedMean = new mmm.chemistry.ExactRational[6]; double[] observedMean = new double[6];
		for (int a = 0; a < 6; a++) {
			expectedMean[a] = mmm.chemistry.ExactRational.ZERO;
			for (int p = 0; p < source.length; p++) {
				expectedMean[a] = expectedMean[a].add(probability[p].multiply(rawRational(source[p][a])));
			}
			for (int p = 0; p < weights.length; p++) observedMean[a] += outputProbability[p].toDouble() * jointComponent(result, p, a);
			check(Math.abs(observedMean[a] - expectedMean[a].toDouble()) <= 512 * Math.ulp(Math.max(1, Math.abs(expectedMean[a].toDouble()))),
					"Joint rule changed its source first moment");
		}
		double[] variance = new double[6];
		for (int a = 0; a < 6; a++) {
			var exact = mmm.chemistry.ExactRational.ZERO;
			for (int p = 0; p < source.length; p++) {
				var centered = rawRational(source[p][a]).subtract(expectedMean[a]);
				exact = exact.add(probability[p].multiply(centered.multiply(centered)));
			}
			variance[a] = exact.toDouble();
		}
		for (int a = 0; a < 6; a++) for (int b = a; b < 6; b++) {
			var expected = mmm.chemistry.ExactRational.ZERO; double observed = 0;
			for (int p = 0; p < source.length; p++) {
				expected = expected.add(probability[p].multiply(rawRational(source[p][a]).subtract(expectedMean[a]))
						.multiply(rawRational(source[p][b]).subtract(expectedMean[b])));
			}
			for (int p = 0; p < weights.length; p++) {
				observed += outputProbability[p].toDouble() * (jointComponent(result, p, a) - observedMean[a])
						* (jointComponent(result, p, b) - observedMean[b]);
			}
			double limit = 2048 * Math.ulp(Math.sqrt(variance[a]) * Math.sqrt(variance[b]))
					+ 2048 * Math.ulp(expectedMean[a].toDouble()) * Math.sqrt(variance[b])
					+ 2048 * Math.ulp(expectedMean[b].toDouble()) * Math.sqrt(variance[a]);
			check(Math.abs(observed - expected.toDouble()) <= limit, "Joint rule changed a source covariance: axes=" + a + "," + b
					+ " expected=" + expected.toDouble() + " actual=" + observed + " bound=" + limit);
		}
	}

	private static mmm.chemistry.ExactRational[] probabilities(double[] weights) {
		var total = mmm.chemistry.ExactRational.ZERO;
		for (double weight : weights) total = total.add(rawRational(weight));
		var result = new mmm.chemistry.ExactRational[weights.length];
		for (int p = 0; p < weights.length; p++) {
			var w = rawRational(weights[p]); result[p] = new mmm.chemistry.ExactRational(
					w.numerator().multiply(total.denominator()), w.denominator().multiply(total.numerator()));
		}
		return result;
	}

	private static double jointComponent(CellRemapSpatialMoments.Result result, int node, int axis) {
		return axis < 3 ? result.positions()[node][axis] : result.velocities()[node][axis - 3];
	}

	private static void jointRuleKeepsIndependentVelocityModes() {
		for (int exponent : new int[] {-900, 0, 900}) for (boolean unequalVelocity : new boolean[] {false, true}) {
			var moments = new CellRemapSpatialMoments(); double[][] source = new double[8][6];
			double mass = Math.scalb(1.0, exponent); double[] sourceWeights = new double[8]; java.util.Arrays.fill(sourceWeights, mass);
			double[] weights = {mass / 4, mass / 4, mass / 2, mass, mass, 2 * mass, 3 * mass};
			for (int p = 0; p < 8; p++) {
				for (int a = 0; a < 3; a++) source[p][a] = (p >>> a & 1) == 0 ? -.25 : .25;
				for (int a = 0; a < 3; a++) source[p][a + 3] = source[p][a] * source[p][(a + 1) % 3]
						* (unequalVelocity && a == 2 ? Math.scalb(1.0, -40) : 1);
				moments.add(mass, java.util.Arrays.copyOf(source[p], 3), java.util.Arrays.copyOfRange(source[p], 3, 6));
			}
			check(moments.spatialRank() == 3 && moments.minimumCount() == 7, "Independent velocity mode lost its actual joint rank");
			verifyJointRule(moments, weights, source, sourceWeights);
		}
	}

	private static void thinAffineRuleKeepsCrossMoments() {
		double[][] source = {{-.375, -.375, 0, 1, 0, 0}, {.375, .375, 0, 1, 0, 0}, {0, -Math.scalb(1.0, -25), 0, -2, 0, 0}};
		double[] weights = {1, 1, 1}; var moments = new CellRemapSpatialMoments();
		for (double[] point : source) moments.add(1, java.util.Arrays.copyOf(point, 3), java.util.Arrays.copyOfRange(point, 3, 6));
		check(moments.minimumCount() == 3, "Thin affine rule requires more nodes than its positive source");
		verifyJointRule(moments, weights, source);
		double thin = Math.scalb(1.0, -25);
		double[][] positions = {{-.5, -.5, -.25}, {-thin, -thin, -.25}, {-.25, -.25 - thin, -.25}};
		for (int sign : new int[] {-1, 1}) {
			var supported = new CellRemapSpatialMoments();
			for (int p = 0; p < 3; p++) supported.add(1, positions[p], new double[] {sign * (p == 2 ? 2 : -1), 0, 0});
			check(supported.spatialRank() == 1 && supported.minimumCount() == 3,
					"Supported thin rule must retain its second position direction through a joint velocity mode");
			var domain = new FluidDomain(2, 2, 2, FluidSolverConfig.prototypeDefaults(), 64);
			domain.geometry.cutCells.replaceMask(0, 254);
			double[] target = supported.centroid(), storage = new double[3], projection = new double[3];
			for (int a = 0; a < 3; a++) target[a] *= supported.mass();
			var published = CellRemapSampling.fit(domain, 0, 1, weights, supported, positions, target, storage, 0, .5, projection);
			double[] storedMoment = new double[3]; double momentum = 0, kinetic = 0;
			for (int p = 0; p < 3; p++) {
				float[] point = new float[3];
				for (int a = 0; a < 3; a++) {
					point[a] = (float) ((.5 + published.positions()[p][a]) * domain.config.cellSize());
					storedMoment[a] += published.positions()[p][a];
				}
				check(domain.particleCellIndex(point[0], point[1], point[2]) == 0
						&& CellRemapMicroTopology.positionSupport(254, domain.config.cellSize(), point[0], point[1], point[2]) == 1,
						"Thin coupled mode left its actual binary32 open micro support");
				momentum += published.velocities()[p][0]; kinetic += published.velocities()[p][0] * published.velocities()[p][0];
			}
			for (int a = 0; a < 3; a++) {
				precise(storedMoment[a] - target[a], storage[a], "Thin supported position storage audit");
				check(projection[a] == 0, "A supported thin source required a projected centroid");
			}
			precise(momentum, 0, "Thin supported source momentum"); precise(kinetic, 6, "Thin supported source kinetic moment");
		}
	}

	private static void unequalSourceMassKeepsFirstMoment() {
		double[][] source = {{0, 0, 0, Math.scalb(1.0, 120), 0, 0}, {0, 0, 0, 1, 0, 0}};
		double[] weights = {1, Math.scalb(1.0, 150)};
		for (boolean reverse : new boolean[] {false, true}) {
			var moments = new CellRemapSpatialMoments();
			for (int i = 0; i < 2; i++) {
				int p = reverse ? 1 - i : i;
				moments.add(weights[p], java.util.Arrays.copyOf(source[p], 3), java.util.Arrays.copyOfRange(source[p], 3, 6));
			}
			verifyJointRule(moments, weights, source);
		}
	}

	/** A full uniform-density cell has its geometric centroid and the original tensor rule. */
	private static void fullHomogeneousReconstructionKeepsMacPatch(boolean multipleGroups) {
		int n = 5;
		var domain = new FluidDomain(n, n, n,
				new FluidSolverConfig(1, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), n * n * n * 8);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++)
			domain.setVolume(x, y, z, 1);
		var materials = multipleGroups ? new FluidMaterialRegistry(java.util.List.of(
				new FluidMaterial("mmm:patch_a", 1000, "mmm:patch", 0, Math.PI / 2),
				new FluidMaterial("mmm:patch_b", 1000, "mmm:patch", 0, Math.PI / 2))) : null;
		for (int p = 0; p < domain.particleCount(); p++) {
			if (multipleGroups) domain.setMarkerMaterial(p, materials, p & 1);
		}
		// Complementary material groups retain their half-cell centroids and spatial inertia.
		int center = domain.cellIndex(2, 2, 2), groups = multipleGroups ? 2 : 1;
		double[][] before = materialSpatialMoments(domain, center, groups);
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
				close(Math.abs(point[axis] - Math.floor(point[axis]) - 0.5), .25,
						"Full homogeneous remap changed its original spatial rule");
		}
		double[][] after = materialSpatialMoments(domain, center, groups);
		for (int group = 0; group < groups; group++) for (int moment = 0; moment < before[group].length; moment++)
			close(after[group][moment], before[group][moment], "Material group spatial moment");
		for (int c = 0; c < domain.cellCount; c++) {
			check(domain.markerGrid.exactCellMarkerVolume[c] == 1 && domain.projection.pressureVolume[c] == 1,
					"Full homogeneous reconstruction changed cell volume or pressure inventory");
			close(domain.markerGrid.exactCellMarkerMass[c], 1000, "Full-cell density");
		}
		for (int i = 0; i < shearError.length; i++) {
			close(shearError[i], 0, "Affine shear face velocity");
			close(shearWeight[i], 1000, "Uniform-density shear face mass (solver units)");
		}
		close(curvature, 0, "Affine shear curvature");
		close(domain.mac.p2gWeightX[left], 1000, "Uniform-density left face mass (solver units)");
		close(domain.mac.p2gWeightX[right], 1000, "Uniform-density right face mass (solver units)");
		close(domain.mac.velocityX[left], -0.5, "Affine normal left face velocity");
		close(domain.mac.velocityX[right], 0.5, "Affine normal right face velocity");
		close(divergenceError, 0, "Affine normal divergence");
	}

	private static double[][] materialSpatialMoments(FluidDomain domain, int cell, int groups) {
		double[][] moments = new double[groups][7];
		for (int p = 0; p < domain.particleCount(); p++) if (domain.particleCellIndex(p) == cell) {
			double mass = domain.particles.mass(p);
			double[] position = {domain.particles.positionX(p), domain.particles.positionY(p), domain.particles.positionZ(p)};
			int group = domain.particles.materialHandle(p); moments[group][0] += mass;
			for (int axis = 0; axis < 3; axis++) {
				double offset = position[axis] / domain.config.cellSize() - 2.5;
				moments[group][axis + 1] += mass * offset;
				moments[group][axis + 4] += mass * offset * offset;
			}
		}
		return moments;
	}

	/** Material labels can reduce nominal point counts without reducing a resolved velocity coordinate. */
	private static void reconstructedFieldsRetainAllShearAxes() {
		for (int velocityAxis = 0; velocityAxis < 3; velocityAxis++) for (int gradientAxis = 0; gradientAxis < 3; gradientAxis++) {
			if (velocityAxis == gradientAxis) continue;
			var domain = new FluidDomain(4, 4, 4, FluidSolverConfig.prototypeDefaults(), 64);
			domain.setVolume(1, 1, 1, 1);
			var materials = new FluidMaterialRegistry(java.util.List.of(
					new FluidMaterial("test:shear_a", 1000, "test:shear", 0, Math.PI / 2),
					new FluidMaterial("test:shear_b", 1000, "test:shear", 0, Math.PI / 2)));
			for (int p = 0; p < domain.particleCount(); p++) {
				double[] position = {domain.particles.positionX(p), domain.particles.positionY(p), domain.particles.positionZ(p)};
				int slot = 0;
				for (int axis = 0; axis < 3; axis++) if (position[axis] / domain.config.cellSize() > 1.5) slot |= 1 << axis;
				// Each group is a tetrahedron, so all three affine coordinates remain resolved.
				domain.setMarkerMaterial(p, materials, Integer.bitCount(slot) & 1);
				setVelocity(domain, p, velocityAxis, (float) (position[gradientAxis] / domain.config.cellSize() - 1.5));
			}
			domain.rebuildGridFromParticles();
			var flux = new ConservativeLiquidFlux(4, 4, 4);
			flux.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, 0, domain.markerGrid.exactCellVolumeInventory);
			var remap = new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(domain.cellCount), true);
			checkShear(remap, velocityAxis, gradientAxis, 1000.0 / 16);
		}
	}

	/** Every rotation plane retains both velocity coordinates and its discrete angular momentum. */
	private static void reconstructedFieldsRetainRotationAxes() {
		for (int rotationAxis = 0; rotationAxis < 3; rotationAxis++) {
			var domain = new FluidDomain(4, 4, 4, FluidSolverConfig.prototypeDefaults(), 64);
			domain.setVolume(1, 1, 1, 1);
			domain.bindMaterialRegistry(new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER)));
			domain.bindChemistry(mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY);
			int first = (rotationAxis + 1) % 3, second = (rotationAxis + 2) % 3;
			for (int p = 0; p < domain.particleCount(); p++) {
				double[] offset = {domain.particles.positionX(p) / domain.config.cellSize() - 1.5,
						domain.particles.positionY(p) / domain.config.cellSize() - 1.5,
						domain.particles.positionZ(p) / domain.config.cellSize() - 1.5};
				int slot = 0; for (int axis = 0; axis < 3; axis++) if (offset[axis] > 0) slot |= 1 << axis;
				domain.chemicalParcels.updateIndicator(p, (Integer.bitCount(slot) & 1) != 0);
				setVelocity(domain, p, first, (float) -offset[second]); setVelocity(domain, p, second, (float) offset[first]);
			}
			domain.rebuildGridFromParticles(); var flux = new ConservativeLiquidFlux(4, 4, 4);
			flux.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, 0, domain.markerGrid.exactCellVolumeInventory);
			var remap = new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(domain.cellCount), true);
			double squaredSpeedMass = 0, angularMomentum = 0;
			for (var marker : remap.markers) {
				close(marker.velocity(first), -marker.offset(second), "Reconstructed first rotation component");
				close(marker.velocity(second), marker.offset(first), "Reconstructed second rotation component");
				squaredSpeedMass += marker.mass() * (marker.velocity(first) * marker.velocity(first) + marker.velocity(second) * marker.velocity(second));
				angularMomentum += marker.mass() * (marker.offset(first) * marker.velocity(second) - marker.offset(second) * marker.velocity(first));
			}
			close(squaredSpeedMass, 1000.0 / 8, "Reconstructed rotation kinetic energy");
			close(angularMomentum, 1000.0 / 8, "Reconstructed rotation angular momentum");
		}
	}

	/** Four positive tetrahedral nodes per label retain a three-dimensional affine rotation. */
	private static void chemicalQuadratureRetainsJointMomentsWithFewQuanta() {
		var domain = new FluidDomain(4, 4, 4, FluidSolverConfig.prototypeDefaults(), 64);
		domain.setVolume(1, 1, 1, 1);
		domain.bindMaterialRegistry(new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER)));
		domain.bindChemistry(mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY);
		for (int p = 0; p < domain.particleCount(); p++) {
			float x = domain.particles.positionX(p) / domain.config.cellSize() - 1.5F;
			float y = domain.particles.positionY(p) / domain.config.cellSize() - 1.5F;
			float z = domain.particles.positionZ(p) / domain.config.cellSize() - 1.5F;
			domain.particles.setVelocityX(p, 2 * z - 3 * y); domain.particles.setVelocityY(p, 3 * x - z); domain.particles.setVelocityZ(p, y - 2 * x);
			var original = domain.chemicalParcels.record(p);
			var mixture = new mmm.chemistry.ChemicalMixtureRecord(new int[] {original.speciesHandle(0)}, new long[] {1}, 0);
			int slot = (x > 0 ? 1 : 0) | (y > 0 ? 2 : 0) | (z > 0 ? 4 : 0);
			domain.chemicalParcels.assign(p, mixture.withMassResidualKilograms(domain.unitScale.kilograms(domain.particles.mass(p))
					- mixture.exactSpeciesMassKilograms(domain.chemicalParcels.registry()).toDouble()), (Integer.bitCount(slot) & 1) != 0);
		}
		domain.rebuildGridFromParticles(); var flux = new ConservativeLiquidFlux(4, 4, 4);
		flux.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, 0, domain.markerGrid.exactCellVolumeInventory);
		var remap = new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(domain.cellCount), true);
		check(remap.markers.size() == 8 && domain.particleCount() == 8, "Low-quanta joint reconstruction changed its source or node budget");
		for (var marker : remap.markers) {
			close(marker.velocityX(), 2 * marker.offsetZ() - 3 * marker.offsetY(), "Low-quanta rotation X");
			close(marker.velocityY(), 3 * marker.offsetX() - marker.offsetZ(), "Low-quanta rotation Y");
			close(marker.velocityZ(), marker.offsetY() - 2 * marker.offsetX(), "Low-quanta rotation Z");
		}
	}

	/** A positive small indicator share retains its resolved transverse shear when merged into a full cell. */
	private static void smallTransferredGroupsRetainShear() {
		var domain = new FluidDomain(5, 3, 3, FluidSolverConfig.prototypeDefaults(), 128);
		domain.setVolume(1, 1, 1, 1); domain.setVolume(2, 1, 1, 1);
		domain.bindMaterialRegistry(new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER)));
		domain.bindChemistry(mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY);
		for (int p = 0; p < domain.particleCount(); p++) {
			domain.chemicalParcels.updateIndicator(p, domain.particleCellIndex(p) == domain.cellIndex(1, 1, 1));
			domain.particles.setVelocityX(p, (float) (domain.particles.positionY(p) / domain.config.cellSize() - 1.5));
		}
		domain.rebuildGridFromParticles(); java.util.Arrays.fill(domain.gridToParticleStage.advectionCells(), true);
		var flux = new ConservativeLiquidFlux(5, 3, 3);
		for (int f = 0; f < flux.velocity.length; f++) if (flux.axis[f] == 0
				&& (flux.left[f] == domain.cellIndex(1, 1, 1) || flux.left[f] == domain.cellIndex(2, 1, 1))) flux.velocity[f] = .25;
		flux.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, .125, domain.markerGrid.exactCellVolumeInventory);
		var remap = new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(domain.cellCount));
		int arrivals = 0;
		for (var marker : remap.markers) if (marker.cell() == domain.cellIndex(2, 1, 1) && marker.indicator()) arrivals++;
		check(arrivals >= 2, "Small transferred shear has no complete coordinate rule");
		checkShear(remap, 0, 1, 2000.0 / 16);
	}

	private static void setVelocity(FluidDomain domain, int marker, int axis, float value) {
		switch (axis) {
			case 0 -> domain.particles.setVelocityX(marker, value);
			case 1 -> domain.particles.setVelocityY(marker, value);
			case 2 -> domain.particles.setVelocityZ(marker, value);
			default -> throw new AssertionError("Unknown shear axis");
		}
	}

	private static void checkShear(CellCarrierRemapPlan remap, int velocityAxis, int gradientAxis, double expectedSquaredSpeedMass) {
		double momentum = 0, squaredSpeedMass = 0;
		for (var marker : remap.markers) {
			close(marker.velocity(velocityAxis), marker.offset(gradientAxis), "Reconstructed affine shear");
			momentum += marker.mass() * marker.velocity(velocityAxis);
			squaredSpeedMass += marker.mass() * marker.velocity(velocityAxis) * marker.velocity(velocityAxis);
		}
		close(momentum, 0, "Reconstructed shear momentum");
		close(squaredSpeedMass, expectedSquaredSpeedMass, "Reconstructed shear kinetic energy");
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
		double sourceMass = domain.totalMass();
		double[] sourceMoment = new double[3], sourceSecond = new double[3];
		for (int p = 0; p < domain.particleCount(); p++) {
			double[] offset = {domain.particles.positionX(p) - .5, domain.particles.positionY(p) - .5,
					domain.particles.positionZ(p) - .5};
			for (int a = 0; a < 3; a++) {
				sourceMoment[a] += domain.particles.mass(p) * offset[a];
				sourceSecond[a] += domain.particles.mass(p) * offset[a] * offset[a];
			}
		}
		double beforePotential = domain.gravitationalPotentialEnergyJoules();
		domain.rebuildGridFromParticles();
		var flux = new ConservativeLiquidFlux(2, 2, 2);
		java.util.Arrays.fill(flux.exactFlux, mmm.units.SignedDyadic.ZERO);
		var capacities = new mmm.units.SignedDyadic[domain.cellCount];
		java.util.Arrays.fill(capacities, mmm.units.SignedDyadic.of(1));
		capacities[0] = mmm.units.SignedDyadic.of(0.5);
		flux.geometricResult(domain.markerGrid.exactCellVolumeInventory, capacities);
		var remap = new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(domain.cellCount), true);
		var volume = mmm.units.SignedDyadic.ZERO;
		double[] moment = new double[3], second = new double[3];
		for (var marker : remap.markers) {
			volume = volume.add(mmm.units.SignedDyadic.of(marker.volume()));
			for (int a = 0; a < 3; a++) {
				double offset = (float) (.5 + marker.offset(a)) - .5;
				moment[a] += marker.mass() * offset;
				second[a] += marker.mass() * offset * offset;
			}
		}
		check(volume.equals(capacities[0]) && remap.momentAudit.projectedGroups() == 0,
				"Planned full capacity changed its stock or projected a feasible source centroid");
		for (int a = 0; a < 3; a++) {
			close(moment[a] / sourceMass, sourceMoment[a] / sourceMass, "Planned-capacity source centroid");
			close(second[a] / sourceMass, sourceSecond[a] / sourceMass, "Planned-capacity source second moment");
		}
		double afterPotential = 0;
		for (var marker : remap.markers) afterPotential += domain.unitScale.kilograms(marker.mass())
				* domain.config.gravity() * (float) (0.5 + marker.offsetY());
		var diagnostic = remap.momentAudit.stats();
		double storageBound = 4 * domain.unitScale.kilograms(sourceMass) * Math.ulp(1.0F);
		check(diagnostic.massProjection().y() == 0 && Math.abs(diagnostic.signedMassResidual().y()) <= storageBound,
				"Feasible centroid acquired an error beyond binary32 position storage");
		precise(Math.abs(diagnostic.signedMassResidual().y()), diagnostic.massStorage().y(), "Signed position-storage residual");
		check(Math.abs(afterPotential - beforePotential) <= domain.config.gravity() * storageBound + 32 * Math.ulp(beforePotential),
				"Joint spatial remap changed potential energy beyond position storage");
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
