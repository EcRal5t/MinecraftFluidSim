package mmm.fluid;

import java.util.ArrayList;

/** Binary32 publication and support checks for fill-constrained first-moment rules. */
public final class CellRemapMomentsValidation {
	private CellRemapMomentsValidation() { }

	public static void main(String[] args) {
		if (args.length > 1 || args.length == 1 && !java.util.List.of("transport-moments", "joint-moments", "sweep-only", "footprint-only").contains(args[0]))
			throw new IllegalArgumentException("Unknown remap moments validation entry");
		if (args.length == 1 && args[0].equals("joint-moments")) {
			partialCellInitializationHasPositiveWetLayerSupport();
			faceArrivalRetainsFiniteNormalThickness();
			boundedFaceTangentsKeepMechanicalMoments();
			sparseFaceArrivalPublishesActualPressureSupport();
			thinJointRuleKeepsActualRank(); jointRuleKeepsIndependentVelocityModes(); thinAffineRuleKeepsCrossMoments();
			unequalSourceMassKeepsFirstMoment();
			chemicalQuadratureRetainsJointMomentsWithFewQuanta(); smallTransferredGroupsRetainShear();
			System.out.println("Positive joint liquid moments validation passed"); return;
		}
		if (args.length == 1 && args[0].equals("transport-moments")) {
			jointTranslationPublishesEdgeAndCornerReceivers();
			limitedFluxMixesTheSamePositiveMomentWeights();
			scalarTransportKeepsUnexcitedSpatialInertia();
			directionalTransportPartitionsActualMomentumAndKineticEnergy();
			System.out.println("Cell remap transport moments validation passed");
			return;
		}
		sweptLiquidMomentsKeepThinTangentialCentroids();
		sweptQuadraticMomentsRetainAffineWedgeGeometry();
		capturedWetVelocityKeepsPhysicalIntegral();
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
		jointTranslationPublishesEdgeAndCornerReceivers();
		thinJointRuleKeepsActualRank();
		partialCellInitializationHasPositiveWetLayerSupport();
		faceArrivalRetainsFiniteNormalThickness();
		boundedFaceTangentsKeepMechanicalMoments();
		sparseFaceArrivalPublishesActualPressureSupport();
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

	private static void jointTranslationPublishesEdgeAndCornerReceivers() {
		for (float h : new float[]{.5F, 1, 2}) for (int axes = 1; axes < 8; axes++) for (int signs = 0; signs < 8; signs++) {
			var domain = translationDomain(h, axes, signs, 256, false);
			domain.cellLiquidVolumeTransport.advect(.125F);
			check(domain.cellLiquidVolumeTransport.momentAudit.reconstructedGroups() == 1 << Integer.bitCount(axes),
					"Production transport omitted an edge or corner receiving cell");
			var expected = new mmm.units.SignedDyadic[domain.cellCount]; java.util.Arrays.fill(expected, mmm.units.SignedDyadic.ZERO);
			var delta = new mmm.units.SignedDyadic[3];
			for (int a = 0; a < 3; a++) delta[a] = mmm.units.SignedDyadic.of((axes & 1 << a) == 0 ? 0 : (signs & 1 << a) == 0 ? .125 : -.125);
			for (var piece : LiquidTranslationPartition.fullCell(delta)) expected[domain.cellIndex(2 + piece.dx(), 2 + piece.dy(), 2 + piece.dz())] = piece.volume();
			for (int c = 0; c < domain.cellCount; c++) check(domain.markerGrid.exactCellVolumeInventory[c].equals(expected[c]), "Production translated destination volume changed");
			var first = new double[3]; var momentum = new double[3]; var angular = new double[3]; double kinetic = 0;
			for (int p = 0; p < domain.particleCount(); p++) {
				var x = new double[]{domain.particles.positionX(p), domain.particles.positionY(p), domain.particles.positionZ(p)};
				var v = new double[]{domain.particles.velocityX(p), domain.particles.velocityY(p), domain.particles.velocityZ(p)};
				for (int a = 0; a < 3; a++) {
					first[a] += domain.particles.mass(p) * x[a]; momentum[a] += domain.particles.mass(p) * v[a];
					kinetic += .5 * domain.particles.mass(p) * v[a] * v[a];
					int b = (a + 1) % 3, c = (a + 2) % 3;
					angular[a] += domain.particles.mass(p) * (x[b] * v[c] - x[c] * v[b]);
				}
			}
			for (int a = 0; a < 3; a++) {
				double v = 8 * delta[a].toDouble() * h;
				precise(momentum[a], 1000 * v, "Joint translation momentum");
				check(Math.abs(first[a] - 1000 * h * (2.5 + delta[a].toDouble())) <= 1000 * Math.ulp(3 * h), "Joint translation first moment exceeded float storage");
				int b = (a + 1) % 3, c = (a + 2) % 3;
				check(Math.abs(angular[a] - 2500 * h * (8 * delta[c].toDouble() * h - 8 * delta[b].toDouble() * h))
						<= 2000 * h * Math.ulp(3 * h), "Joint translation angular momentum exceeded coordinate storage");
			}
			precise(kinetic, 500 * h * h * Integer.bitCount(axes), "Joint translation kinetic energy");
		}
		var tiled = translationDomain(1, 3, 0, 256, true); tiled.cellLiquidVolumeTransport.advect(.125F);
		check(tiled.particleCount() == 48, "Translated positive pieces did not compact a fully covered receiving box");
		var fractional = translationDomain(1, 7, 0, 256, false); fractional.cellLiquidVolumeTransport.advect(.123F);
		var total = mmm.units.SignedDyadic.ZERO;
		for (int p = 0; p < fractional.particleCount(); p++) total = total.add(mmm.units.SignedDyadic.of(fractional.particles.volume(p)));
		check(total.equals(mmm.units.SignedDyadic.of(1)), "Joint translation discarded its float expansion tails");
		var incompatibleGas = translationDomain(1, 7, 0, 256, false); incompatibleGas.sealDomainBoundary(); incompatibleGas.bootstrapGasState(null);
		CellAffineWallProjection.projectTransport(incompatibleGas); var gasBefore = incompatibleGas.exportParticleState(); boolean gasRejected = false;
		try { incompatibleGas.cellLiquidVolumeTransport.advect(.125F); } catch (GasTopologyBlockedException expected) { gasRejected = true; }
		check(gasRejected && incompatibleGas.gasState.network.nodeCount() == 1
				&& java.util.Arrays.equals(gasBefore.copyPositionVolume(), incompatibleGas.exportParticleState().copyPositionVolume())
				&& java.util.Arrays.equals(gasBefore.copyParticleVelocities(), incompatibleGas.exportParticleState().copyParticleVelocities()),
				"Incompatible PLIC gas geometry published a false vacuum or changed source columns");
		var finite = translationDomain(1, 1, 0, 256, false); finite.sealDomainBoundary(); finite.bootstrapGasState(null);
		long gas = finite.gasState.network.standardVolumeNanos(1); double capacity = finite.gasState.network.availableVolume(1);
		CellAffineWallProjection.projectTransport(finite); finite.cellLiquidVolumeTransport.advect(.125F);
		check(finite.cellLiquidVolumeTransport.momentAudit.reconstructedGroups() == 2 && finite.gasState.network.nodeCount() == 1
				&& finite.gasState.network.standardVolumeNanos(1) == gas, "Joint translation changed its sealed gas inventory or destination model: groups="
				+ finite.cellLiquidVolumeTransport.momentAudit.reconstructedGroups() + ", nodes=" + finite.gasState.network.nodeCount()
				+ ", gas=" + finite.gasState.network.standardVolumeNanos(1) + ", before=" + gas);
		precise(finite.gasState.network.availableVolume(1), capacity, "Joint translation sealed gas capacity");
		var restored = FluidDomain.restoreAuthoritativeState(finite.captureAuthoritativeState());
		check(java.util.Arrays.equals(finite.exportParticleState().copyPositionVolume(), restored.exportParticleState().copyPositionVolume()),
				"Joint translation source geometry changed on capture and restore");
		var limited = translationDomain(1, 7, 0, 8, false); var before = limited.exportParticleState(); boolean rejected = false;
		try { limited.cellLiquidVolumeTransport.advect(.125F); } catch (IllegalStateException expected) { rejected = true; }
		check(rejected && java.util.Arrays.equals(before.copyPositionVolume(), limited.exportParticleState().copyPositionVolume())
				&& java.util.Arrays.equals(before.copyParticleVelocities(), limited.exportParticleState().copyParticleVelocities()), "Joint translation marker-budget rejection changed source columns");
	}
	private static FluidDomain translationDomain(float h, int axes, int signs, int budget, boolean tiled) {
		var domain = new FluidDomain(5, 5, 5,
				new FluidSolverConfig(h, 1000, 0, .125F, 200, 1e-5F, .45F, .002F, 0), budget);
		domain.setVolume(2, 2, 2, 1); if (tiled) domain.setVolume(1, 2, 2, 1);
		for (int p = 0; p < domain.particleCount(); p++) for (int a = 0; a < 3; a++)
			setVelocity(domain, p, a, (axes & 1 << a) == 0 ? 0 : (signs & 1 << a) == 0 ? h : -h);
		domain.rebuildGridFromParticles(); var basis = CellAffineVelocity.capture(domain);
		var coefficients = new double[3][4 * basis.blockCount()];
		for (int block = 0; block < basis.blockCount(); block++) for (int a = 0; a < 3; a++)
			coefficients[a][4 * block] = (axes & 1 << a) == 0 ? 0 : (signs & 1 << a) == 0 ? h : -h;
		domain.pressureSystem.updateMarkerVelocityField(new CellAffineVelocityField(basis, coefficients));
		domain.gridToParticleStage.prepareAdvectionCells();
		domain.cellLiquidVolumeTransport = new CellLiquidVolumeTransport(domain);
		return domain;
	}

	private static void capturedWetVelocityKeepsPhysicalIntegral() {
		for (float h : new float[]{.5F, 1, 2}) {
			var domain = new FluidDomain(3, 3, 3,
					new FluidSolverConfig(h, 1000, 0, .01F, 200, 1e-5F, .5F, 1e-4F, 0), 64);
			domain.setVolume(1, 1, 1, 1); domain.rebuildGridFromParticles();
			var geometry = LiquidPressureGeometry.capture(domain); var basis = CellAffineVelocity.capture(domain, geometry);
			int cell = domain.cellIndex(1, 1, 1), block = basis.blockForCell(cell, 0);
			var coefficients = new double[3][4 * basis.blockCount()];
			coefficients[0][4 * block] = 2; coefficients[0][4 * block + 1] = 3;
			coefficients[1][4 * block] = -3; coefficients[2][4 * block] = 4;
			var mean = new CellAffineVelocityField(basis, coefficients).meanWetVelocity(geometry, cell, 255);
			for (int axis = 0; axis < 3; axis++) precise(mean[axis], new double[]{2, -3, 4}[axis], "Translated wet velocity mean retains SI units");
		}
		var closed = new FluidDomain(2, 2, 2,
				new FluidSolverConfig(1, 1000, 0, .01F, 200, 1e-5F, .5F, 1e-4F, 0), 64);
		closed.setSolid(1, 0, 0, true); closed.setSolid(0, 1, 0, true); closed.setSolid(0, 0, 1, true);
		closed.setVolume(0, 0, 0, 1); closed.rebuildGridFromParticles();
		var geometry = LiquidPressureGeometry.capture(closed); var basis = CellAffineVelocity.capture(closed, geometry);
		var coefficients = new double[3][4 * basis.blockCount()];
		coefficients[0][0] = 2; coefficients[0][1] = 3; coefficients[1][0] = -3; coefficients[2][0] = 4;
		var field = new CellAffineVelocityField(basis, coefficients);
		var mean = field.meanWetVelocity(geometry, 0, 255);
		for (int axis = 0; axis < 3; axis++) precise(mean[axis], new double[]{1.0 / 3, -.5, 2.0 / 3}[axis], "Exact quadratic-wall wet velocity mean");
		double sampled = 0;
		for (int p = 0; p < closed.particleCount(); p++) sampled += closed.particles.volume(p)
				* field.value(0, 0, closed.particles.positionX(p), closed.particles.positionY(p), closed.particles.positionZ(p));
		check(Math.abs(sampled - mean[0]) > .01, "Quarter-cell rule accidentally certified the cubic velocity integral");
		var signed = geometry.supportAffineSecondMoments(0, 255, new mmm.chemistry.ExactRational[]{
				mmm.chemistry.ExactRational.of(-2), mmm.chemistry.ExactRational.of(3),
				mmm.chemistry.ExactRational.ZERO, mmm.chemistry.ExactRational.ZERO});
		check(signed[0].equals(mmm.chemistry.ExactRational.of(-2))
				&& signed[1].equals(mmm.chemistry.ExactRational.ONE.divide(4)), "Signed affine wet integral changed");
		closed.setVolume(0, 0, 0, .25F);
		for (int p = 0; p < closed.particleCount(); p++) {
			closed.particles.setPositionX(p, .875F); closed.particles.setPositionY(p, (p & 1) == 0 ? .25F : .75F);
		}
		closed.rebuildGridFromParticles(); geometry = LiquidPressureGeometry.capture(closed); basis = CellAffineVelocity.capture(closed, geometry);
		coefficients = new double[3][4 * basis.blockCount()]; coefficients[0][0] = 2; coefficients[0][1] = 3;
		coefficients[1][0] = -3; coefficients[2][0] = 4;
		field = new CellAffineVelocityField(basis, coefficients); mean = field.meanWetVelocity(geometry, 0, 255);
		for (int axis = 0; axis < 3; axis++) precise(mean[axis], new double[]{-.234375, -.5, 2.0 / 3}[axis], "Partial PLIC wet velocity mean");
		boolean rejected = false;
		try { field.meanWetVelocity(geometry, 0, 0x55); } catch (IllegalArgumentException expected) { rejected = true; }
		check(rejected, "Zero wet volume supplied a velocity mean");
	}

	private static void sweptQuadraticMomentsRetainAffineWedgeGeometry() {
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(1, 1000, 0, .05F, 200, 1e-5F, .45F, .002F, 0), 32);
		domain.setVolume(1, 1, 1, 1); domain.rebuildGridFromParticles();
		int donor = domain.cellIndex(1, 1, 1);
		for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
			int[] xyz = {1, 1, 1}; xyz[axis] += sign;
			int receiver = domain.cellIndex(xyz[0], xyz[1], xyz[2]), tangent = (axis + 1) % 3;
			for (boolean affine : new boolean[]{false, true}) {
				double intercept = sign * (affine ? .125 : .25), slope = affine ? sign * .25 : 0;
				var first = LiquidAffineSweepGeometry.sweptExactMoments(domain, donor, receiver, axis, sign > 0,
						intercept, slope, 0, .5, 255, 255);
				var open = new mmm.chemistry.ExactRational[8]; var liquid = open.clone(); var dry = open.clone();
				LiquidAffineSweepGeometry.lowOrderPhaseVolumes(domain, donor, receiver, axis, sign > 0,
						new double[]{intercept, slope, 0}, .5, 255, 255, open, liquid, dry);
				var total = mmm.chemistry.ExactRational.ZERO;
				for (int m = 0; m < 8; m++) {
					check(open[m].equals(liquid[m].add(dry[m])) && dry[m].isZero(), "Whole-wet low-order phase closure");
					check(liquid[m].signum() >= 0 && liquid[m].compareTo(mmm.chemistry.ExactRational.ONE.divide(8)) <= 0,
							"Low-order source exceeds its microbox capacity");
					total = total.add(liquid[m]);
				}
				check(total.equals(first[0]), "Constant-phase low-order and swept measures differ");
				var second = LiquidAffineSweepGeometry.sweptExactSecondMoments(domain, donor, receiver, axis, sign > 0,
						intercept, slope, 0, .5, 255, 255);
				var q = new double[]{intercept, slope, 0};
				var lowSecond = LiquidAffineSweepGeometry.lowOrderPhaseSecondMoments(domain, donor, receiver, axis, sign > 0,
						q, .5, 255, 255, 255);
				var selectedHigh = new mmm.chemistry.ExactRational[10]; var selectedLow = selectedHigh.clone();
				java.util.Arrays.fill(selectedHigh, mmm.chemistry.ExactRational.ZERO);
				java.util.Arrays.fill(selectedLow, mmm.chemistry.ExactRational.ZERO);
				for (int micro = 0; micro < 8; micro++) {
					var highPart = LiquidAffineSweepGeometry.sweptExactSecondMoments(domain, donor, receiver, axis, sign > 0,
							intercept, slope, 0, .5, 255, 255, 1 << micro);
					var lowPart = LiquidAffineSweepGeometry.lowOrderPhaseSecondMoments(domain, donor, receiver, axis, sign > 0,
							q, .5, 255, 255, 1 << micro);
					check(lowPart[0].equals(liquid[micro]), "Source selection changed low-order column normalization");
					for (int i = 0; i < 10; i++) {
						selectedHigh[i] = selectedHigh[i].add(highPart[i]); selectedLow[i] = selectedLow[i].add(lowPart[i]);
					}
				}
				check(java.util.Arrays.equals(second, selectedHigh) && java.util.Arrays.equals(lowSecond, selectedLow),
						"Selected source microboxes do not partition the complete quadratic measure");
				check(lowSecond[0].equals(total), "Low-order spatial measure changed its actual volume");
				var highArrival = LiquidAffineSweepGeometry.arrivalSecondMoments(second, axis, sign > 0, q, .5, false);
				var lowArrival = LiquidAffineSweepGeometry.arrivalSecondMoments(lowSecond, axis, sign > 0, q, .5, true);
				check(java.util.Arrays.equals(highArrival, lowArrival), "Fully wet high and low arrival measures differ");
				check(highArrival[axis + 1].multiply(8).equals(mmm.chemistry.ExactRational.of(sign * (affine ? 109 : 9)).divide(affine ? 192 : 16)),
						"Actual arrival omitted its finite normal displacement");
				var saved = new LiquidAffineSweepGeometry.MicroSweep(domain, donor, receiver, axis, sign > 0,
						new double[]{intercept, slope, 0}, .5, 255);
				for (int support : new int[]{255, 0x33, 0xcc, 0x55, 0xaa}) {
					int peerSupport = support ^ 0x66;
					LiquidAffineSweepGeometry.lowOrderPhaseVolumes(domain, donor, receiver, axis, sign > 0,
							new double[]{intercept, slope, 0}, .5, support, peerSupport, open, liquid, dry);
					for (int m = 0; m < 8; m++) {
						check(open[m].equals(liquid[m]) && dry[m].isZero(), "Restricted whole-wet phase closure");
						if ((support & 1 << m) == 0) check(open[m].isZero(), "Low-order phase crossed its source support");
					}
					var restricted = LiquidAffineSweepGeometry.sweptExactMoments(domain, donor, receiver, axis, sign > 0,
							intercept, slope, 0, .5, support, peerSupport);
					check(java.util.Arrays.equals(restricted, saved.sum(axis, sign > 0, support, peerSupport)),
							"Stored micro sweep changed exact support-restricted moments");
					double[] swept = new double[8], wet = new double[8], stored = new double[8], storedWet = new double[8];
					LiquidAffineSweepGeometry.sweptMicroVolumes(domain, donor, receiver, axis, sign > 0,
							intercept, slope, 0, .5, support, peerSupport, swept, wet);
					LiquidAffineSweepGeometry.sweptMicroVolumes(domain, donor, receiver, axis, sign > 0,
							intercept, slope, 0, .5, support, peerSupport, stored, storedWet, saved);
					check(java.util.Arrays.equals(swept, stored) && java.util.Arrays.equals(wet, storedWet),
							"Stored micro sweep changed volume or donor wet measure");
				}
				check(!saved.matches(domain, donor, receiver, axis, sign > 0, new double[]{Math.nextUp(intercept), slope, 0}, .5)
						&& !saved.matches(domain, donor, receiver, axis, sign > 0, new double[]{intercept, slope, 0}, Math.nextUp(.5)),
						"Stored sweep accepted changed trace or timestep bits");
				check(second.length == 10, "Quadratic sweep has incomplete moments");
				for (int i = 0; i < 4; i++) check(second[i].equals(first[i]), "Quadratic sweep changed its exact prefix");
				check(second[0].equals(mmm.chemistry.ExactRational.ONE.divide(8)), "Analytic wedge volume differs");
				var means = new mmm.chemistry.ExactRational[]{mmm.chemistry.ExactRational.ZERO,
						mmm.chemistry.ExactRational.ZERO, mmm.chemistry.ExactRational.ZERO};
				means[axis] = affine ? mmm.chemistry.ExactRational.of(83L * sign).divide(192)
						: mmm.chemistry.ExactRational.of(7L * sign).divide(16);
				if (affine) means[tangent] = mmm.chemistry.ExactRational.ONE.divide(12);
				for (int a = 0; a < 3; a++) check(second[a + 1].multiply(8).equals(means[a]), "Analytic wedge centroid differs");
				int entry = 4;
				for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) {
					var expected = mmm.chemistry.ExactRational.ZERO;
					if (a == b) expected = a == axis
							? affine ? mmm.chemistry.ExactRational.of(71).divide(36864) : mmm.chemistry.ExactRational.ONE.divide(768)
							: affine && a == tangent ? mmm.chemistry.ExactRational.of(11).divide(144) : mmm.chemistry.ExactRational.ONE.divide(12);
					else if (affine && (a == axis && b == tangent || a == tangent && b == axis))
						expected = mmm.chemistry.ExactRational.of(-11L * sign).divide(2304);
					var covariance = second[entry++].multiply(8).subtract(means[a].multiply(means[b]));
					check(covariance.equals(expected), "Analytic wedge covariance differs on axes " + a + "," + b);
				}
			}
			var empty = LiquidAffineSweepGeometry.sweptExactSecondMoments(domain, donor, receiver, axis, sign > 0,
					sign * .25, 0, 0, 0, 255, 255);
			for (var value : empty) check(value.isZero(), "Zero-duration quadratic sweep has a nonzero moment");
		}
		var basis = CellAffineVelocity.capture(domain);
		double[][] coefficients = new double[3][4 * basis.blockCount()];
		for (int a = 0; a < 3; a++) coefficients[a][4 * donor] = .25;
		var field = new CellAffineVelocityField(basis, coefficients);
		var faces = new ConservativeLiquidFlux(domain.sizeX, domain.sizeY, domain.sizeZ);
		var retained = new LiquidAffineFaceFlow(domain, faces, field, 1 << 20);
		var unretained = new LiquidAffineFaceFlow(domain, faces, field, 0);
		for (int f = 0; f < faces.physicalFaceCount; f++) if (faces.left[f] == donor) {
			int receiver = faces.right[f], axis = faces.axis[f];
			for (boolean lowOrder : new boolean[]{false, true}) {
				var complete = retained.arrivalSecondMoments(f, donor, receiver, axis, true, .5, 255, 255, lowOrder);
				var partition = new mmm.chemistry.ExactRational[10]; java.util.Arrays.fill(partition, mmm.chemistry.ExactRational.ZERO);
				for (int micro = 0; micro < 8; micro++) {
					var part = retained.arrivalSecondMoments(f, donor, receiver, axis, true, .5, 255, 255, 1 << micro, lowOrder);
					if (lowOrder) check(part[0].signum() > 0, "Inner source selection lost its complete outgoing path");
					for (int i = 0; i < 10; i++) partition[i] = partition[i].add(part[i]);
				}
				check(java.util.Arrays.equals(complete, partition), "Face producer changed source-conditioned arrival moments");
			}
			double[] expected = new double[4], actual = new double[4];
			retained.sweptMoments(f, donor, receiver, axis, true, .5, 255, 255, actual);
			double[] swept = new double[8], wet = new double[8];
			unretained.sweptMicroVolumes(f, donor, receiver, axis, true, .5, 0x33, 255, swept, wet);
			int face = f;
			mmm.compute.CpuWorkBatch.run(16, 1, (start, end) -> {
				for (int i = start; i < end; i++) {
					double[] parallelSwept = new double[8], parallelWet = new double[8];
					retained.sweptMicroVolumes(face, donor, receiver, axis, true, .5, 0x33, 255, parallelSwept, parallelWet);
					check(java.util.Arrays.equals(swept, parallelSwept) && java.util.Arrays.equals(wet, parallelWet),
							"Parallel micro sweep publication changed binary64 measures");
				}
			});
			for (double dt : new double[]{.5, .25}) for (int support : new int[]{255, 0x33, 0xcc}) {
				unretained.sweptMoments(f, donor, receiver, axis, true, dt, support, 255, expected);
				retained.sweptMoments(f, donor, receiver, axis, true, dt, support, 255, actual);
				check(java.util.Arrays.equals(expected, actual), "Retention budget changed binary64 component sum");
			}
			domain.geometry.cutCells.replaceMask(receiver, 1);
			unretained.sweptMoments(f, donor, receiver, axis, true, .25, 255, 255, expected);
			retained.sweptMoments(f, donor, receiver, axis, true, .25, 255, 255, actual);
			check(java.util.Arrays.equals(expected, actual), "Stored sweep ignored changed fixed support");
			domain.geometry.cutCells.replaceMask(receiver, 0);
		}
		var saved = new LiquidAffineSweepGeometry.MicroSweep(domain, donor, donor + 1, 0, true, new double[]{.25, 0, 0}, .5, 255);
		domain.setVolume(1, 1, 1, .5F); domain.rebuildGridFromParticles();
		check(!saved.matches(domain, donor, donor + 1, 0, true, new double[]{.25, 0, 0}, .5),
				"Stored sweep accepted an in-place phase geometry rebuild");
		for (int p = 0; p < domain.particleCount(); p++) {
			domain.particles.setPositionX(p, 1.25F); domain.particles.setPositionY(p, 1.5F); domain.particles.setPositionZ(p, 1.5F);
		}
		domain.rebuildGridFromParticles();
		var open = new mmm.chemistry.ExactRational[8]; var liquid = open.clone(); var dry = open.clone();
		LiquidAffineSweepGeometry.lowOrderPhaseVolumes(domain, donor, donor + domain.sizeX, 1, true,
				new double[]{.125, 0, .25}, .5, 255, 255, open, liquid, dry);
		var wetTotal = mmm.chemistry.ExactRational.ZERO;
		for (int m = 0; m < 8; m++) {
			check(open[m].equals(liquid[m].add(dry[m])) && liquid[m].signum() >= 0 && dry[m].signum() >= 0,
					"Partial PLIC low-order phase closure");
			wetTotal = wetTotal.add(liquid[m]);
		}
		check(wetTotal.equals(mmm.chemistry.ExactRational.of(3).divide(64)),
				"Low-order producer lost correlation between tangential trace and PLIC phase: " + wetTotal);
		LiquidAffineSweepGeometry.lowOrderPhaseVolumes(domain, donor, donor + 1, 0, true,
				new double[]{.25, 0, 0}, .5, 255, 255, open, liquid, dry);
		var exactExit = LiquidAffineSweepGeometry.sweptExactMoments(domain, donor, donor + 1, 0, true, .25, 0, 0, .5, 255, 255);
		check(exactExit[0].isZero() && java.util.Arrays.stream(liquid).anyMatch(v -> v.signum() > 0),
				"Donor-cell phase mixing was confused with an exact supported sweep");
		var partialBasis = CellAffineVelocity.capture(domain);
		double[][] partialCoefficients = new double[3][4 * partialBasis.blockCount()];
		partialCoefficients[1][4 * donor] = .1875; partialCoefficients[1][4 * donor + 1] = .25;
		var partialFaces = new ConservativeLiquidFlux(3, 3, 3, true);
		var partialFlow = new LiquidAffineFaceFlow(domain, partialFaces, new CellAffineVelocityField(partialBasis, partialCoefficients));
		var geometry = new LiquidFluxGeometry(domain); partialFaces.geometry(geometry); partialFaces.faceSweep(partialFlow);
		partialFlow.prepareVelocities(partialFaces, geometry);
		partialFaces.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, .5, domain.markerGrid.exactCellVolumeInventory);
		for (int f = 0; f < partialFaces.physicalFaceCount; f++) if (partialFaces.left[f] == donor && partialFaces.axis[f] == 1) {
			double[] low = new double[8];
			check(partialFaces.lowOrderMicroVolumes(f, 255, 255, low), "Actual affine flux omitted its micro low-order producer");
			precise(java.util.Arrays.stream(low).sum(), 3.0 / 64, "Actual affine low-order transfer");
			check(partialFaces.exactFlux[f].equals(mmm.units.SignedDyadic.of(3.0 / 64)),
					"Actual FCT consumer did not retain the common phase transfer");
		}
		domain.setVolume(1, 1, 1, 1);
		domain.rebuildGridFromParticles();
		var fullCache = new LiquidAffineSweepGeometry.MicroSweep(domain, donor, donor + 1, 0, true, new double[]{.25, 0, 0}, .5, 255);
		for (int p = 0; p < domain.particleCount(); p++) domain.particles.setVolume(p,
				p < 3 ? Math.nextDown(Math.scalb(1F, -24 * p)) : Float.MIN_NORMAL);
		domain.rebuildGridFromParticles();
		check(domain.pressurePhaseGeometry.liquidFractionExact(donor, 0) == 1
				&& domain.markerGrid.exactCellVolumeInventory[donor].compareTo(domain.pressurePhaseGeometry.poreVolume(donor)) < 0,
				"Tiny dry source did not reproduce the rounded full-phase classification");
		boolean rejected = false;
		try { LiquidAffineSweepGeometry.lowOrderPhaseVolumes(domain, donor, donor + 1, 0, true,
				new double[]{.25, 0, 0}, .5, 255, 255, open, liquid, dry); }
		catch (IllegalStateException lostPlane) { rejected = lostPlane.getMessage().contains("no PLIC plane"); }
		check(rejected, "Rounded full-phase observation discarded a positive exact dry inventory");
		check(!fullCache.matches(domain, donor, donor + 1, 0, true, new double[]{.25, 0, 0}, .5),
				"Unchanged rounded phase columns hid changed exact stock from the saved sweep");
		for (boolean micro : new boolean[]{false, true}) {
			rejected = false;
			try {
				if (micro) LiquidAffineSweepGeometry.sweptMicroVolumes(domain, donor, donor + 1, 0, true,
						.25, 0, 0, .5, 255, 255, new double[8], new double[8]);
				else LiquidAffineSweepGeometry.sweptExactMoments(domain, donor, donor + 1, 0, true, .25, 0, 0, .5, 255, 255);
			} catch (IllegalStateException lostPlane) { rejected = lostPlane.getMessage().contains("no PLIC plane"); }
			check(rejected, "High-order or wet source measure discarded the positive exact dry inventory");
		}
		domain.setVolume(1, 1, 1, .5F);
		for (int p = 0; p < domain.particleCount(); p++) {
			domain.particles.setPositionX(p, 1.75F); domain.particles.setPositionY(p, 1.5F); domain.particles.setPositionZ(p, 1.5F);
		}
		domain.rebuildGridFromParticles();
		var supplyBasis = CellAffineVelocity.capture(domain);
		double[][] supplyCoefficients = new double[3][4 * supplyBasis.blockCount()];
		supplyCoefficients[0][4 * donor] = .49;
		for (int a = 1; a < 3; a++) { supplyCoefficients[a][4 * donor] = .375; supplyCoefficients[a][4 * donor + 1] = .5; }
		var supplyFaces = new ConservativeLiquidFlux(3, 3, 3, true);
		var supplyFlow = new LiquidAffineFaceFlow(domain, supplyFaces, new CellAffineVelocityField(supplyBasis, supplyCoefficients));
		supplyFaces.geometry(geometry); supplyFaces.faceSweep(supplyFlow); supplyFlow.prepareVelocities(supplyFaces, geometry);
		double openOutgoing = 0, liquidOutgoing = 0;
		for (int f = 0; f < supplyFaces.physicalFaceCount; f++) if (supplyFaces.left[f] == donor) {
			openOutgoing += supplyFaces.velocity[f]; double[] micro = new double[8];
			liquidOutgoing += supplyFlow.lowOrderVolumes(f, donor, supplyFaces.right[f], supplyFaces.axis[f], true, 1, 255, 255, micro);
		}
		precise(openOutgoing, .99, "Open-face Courant of the three-axis source counterexample");
		precise(liquidOutgoing, .62, "PLIC-correlated low-order source demand");
		boolean refined = false;
		try { supplyFaces.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, 1, domain.markerGrid.exactCellVolumeInventory); }
		catch (MechanicalTimeRefinement supply) { refined = supply.minimumSubsteps == 2 && supply.getMessage().contains("Low-order liquid source"); }
		check(refined, "Three-axis low-order overdraw did not request mechanical time refinement");
		supplyFaces.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, .5, domain.markerGrid.exactCellVolumeInventory);
		check(supplyFaces.resultingVolumes()[donor].signum() >= 0, "Refined low-order plan lost source positivity");
		for (int thinAxis = 0; thinAxis < 3; thinAxis++) {
			domain.setVolume(1, 1, 1, 1F / 64);
			for (int p = 0; p < domain.particleCount(); p++) {
				domain.particles.setPositionX(p, thinAxis == 0 ? 1F + 1F / 128 : 1.5F);
				domain.particles.setPositionY(p, thinAxis == 1 ? 1F + 1F / 128 : 1.5F);
				domain.particles.setPositionZ(p, thinAxis == 2 ? 1F + 1F / 128 : 1.5F);
			}
			domain.rebuildGridFromParticles();
			int flowAxis = (thinAxis + 1) % 3, stride = flowAxis == 0 ? 1 : flowAxis == 1 ? domain.sizeX : domain.sizeX * domain.sizeY;
			var thinBasis = CellAffineVelocity.capture(domain);
			for (int sign : new int[]{-1, 1}) for (int intercept : new int[]{0, -4}) {
				int next = donor + sign * stride;
				double[] q = {sign * intercept, 0, sign * 1024};
				LiquidAffineSweepGeometry.lowOrderPhaseVolumes(domain, donor, next, flowAxis, sign > 0,
						q, 1.0 / 2048, 255, 255, open, liquid, dry);
				var total = mmm.chemistry.ExactRational.ZERO;
				for (int m = 0; m < 8; m++) {
					check(open[m].equals(liquid[m].add(dry[m])) && liquid[m].signum() >= 0 && dry[m].signum() >= 0,
							"Thin partial microbox lost its exact phase flux decomposition");
					total = total.add(liquid[m]);
				}
				check(total.equals(intercept == 0 ? mmm.chemistry.ExactRational.ONE.divide(16384)
						: mmm.chemistry.ExactRational.of(9).divide(262144)),
						"Microbox phase/trace correlation was replaced by the dry-corner velocity mean: " + total);
				var high = LiquidAffineSweepGeometry.sweptExactMoments(domain, donor, next, flowAxis, sign > 0,
						q[0], q[1], q[2], 1.0 / 2048, 255, 255);
				check(high[0].equals(total), "Tangential thin phase low/high measures disagree");
				double[][] thinCoefficients = new double[3][4 * thinBasis.blockCount()];
				thinCoefficients[flowAxis][4 * donor] = sign * (8 + intercept);
				thinCoefficients[flowAxis][4 * donor + thinAxis + 1] = sign * 1024;
				thinCoefficients[thinAxis][4 * donor] = .25;
				var thinFaces = new ConservativeLiquidFlux(3, 3, 3);
				var thinFlow = new LiquidAffineFaceFlow(domain, thinFaces, new CellAffineVelocityField(thinBasis, thinCoefficients));
				for (int f = 0; f < thinFaces.physicalFaceCount; f++) {
					if (thinFaces.axis[f] == flowAxis && (sign > 0 ? thinFaces.left[f] : thinFaces.right[f]) == donor)
						precise(thinFlow.maximumOutwardSpeed(f, sign > 0, 0), 16 + intercept, "Actual wet projected peak excludes the dry corner");
					if (thinFaces.axis[f] == thinAxis && thinFaces.left[f] == donor) {
						check(domain.pressurePhaseGeometry.wetFacePolygon(donor, thinAxis, 1, 0, 0, 1)[0].length == 0,
								"Interior wet source counterexample has a wet current exit face");
						precise(thinFlow.maximumOutwardSpeed(f, true, 0), .25, "Interior wet source retains its future exit speed");
					}
				}
			}
		}
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
		var domain = new FluidDomain(2, 2, 2,
				new FluidSolverConfig(1, 1000, 0, .05F, 200, 1e-5F, .45F, .002F, 0), 32);
		domain.setVolume(0, 0, 0, .25F); var source = new ArrayList<Integer>();
		// Supply quadrature is explicit; the two-cell FCT fixture independently defines the sweep.
		for (int p = 0; p < domain.particleCount(); p++) {
			source.add(p); float y = .25F + .5F * (p >> 1 & 1);
			domain.particles.setPositionY(p, y); domain.particles.setVelocityY(p, y - .5F);
		}
		var preference = CellRemapMicroPreferences.create(domain, flux, source, 255, new int[]{0, -1}, new int[]{255, 255});
		for (int micro = 0; micro < 8; micro++) precise(preference[0][micro], (1 - flux.geometricBlend(0)) / 8
				+ ((micro & 2) == 0 ? 0 : flux.geometricBlend(0) / 4), "Common geometric/donor source mixture");
		var result = new CellRemapTransportMoments(domain, 0, source).allocate(new mmm.units.SignedDyadic[]{flux.exactFlux[0],
				exact[0].subtract(flux.exactFlux[0])}, preference);
		precise(result.momentum()[0][1] / result.mass()[0], .25 * flux.geometricBlend(0), "Mixed normal source velocity");
		compareCarrierMoments(domain, 0, result);
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
		sweptMicroSourcesRetainNormalMomentum();
	}

	/** Normal stretch selects the actual swept half before the common mass/P/K allocation. */
	private static void sweptMicroSourcesRetainNormalMomentum() {
		for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) for (boolean sparse : new boolean[]{false, true}) {
			var domain = new FluidDomain(3, 3, 3,
					new FluidSolverConfig(1, 1000, 0, .05F, 200, 1e-5F, .45F, .002F, 0), 32);
			domain.setVolume(1, 1, 1, 1); var original = new ArrayList<Integer>();
			for (int p = 0; p < domain.particleCount(); p++) {
				original.add(p);
				float[] xyz = {domain.particles.positionX(p), domain.particles.positionY(p), domain.particles.positionZ(p)};
				if (sparse) {
					xyz[axis] = 1.5F - sign * .25F;
					domain.particles.setPositionX(p, xyz[0]); domain.particles.setPositionY(p, xyz[1]); domain.particles.setPositionZ(p, xyz[2]);
				}
				float normal = xyz[axis] - 1.5F;
				domain.particles.setVelocityX(p, axis == 0 ? normal : 0);
				domain.particles.setVelocityY(p, axis == 1 ? normal : 0);
				domain.particles.setVelocityZ(p, axis == 2 ? normal : 0);
			}
			domain.gridDirty = true; domain.ensureInventoryGrid(); int cell = domain.cellIndex(1, 1, 1);
			var flux = new ConservativeLiquidFlux(3, 3, 3); flux.geometry(new LiquidFluxGeometry(domain)); int face = -1;
			for (int f = 0; f < flux.physicalFaceCount; f++) if (flux.axis[f] == axis && (sign > 0 ? flux.left[f] : flux.right[f]) == cell) {
				face = f; flux.velocity[f] = sign * .5; break;
			}
			check(face >= 0, "Normal source fixture lacks its outgoing face");
			double[] volume = new double[27]; volume[cell] = 1;
			var inventory = new mmm.units.SignedDyadic[27]; java.util.Arrays.fill(inventory, mmm.units.SignedDyadic.ZERO);
			inventory[cell] = mmm.units.SignedDyadic.of(1); flux.plan(volume, domain.geometry.solid, .125, inventory);
			var sent = flux.exactFlux[face]; var pieces = new mmm.units.SignedDyadic[]{sent, inventory[cell].subtract(sent)};
			var preferences = CellRemapMicroPreferences.create(domain, flux, original, 255, new int[]{face, -1}, new int[]{255, 255});
			var result = new CellRemapTransportMoments(domain, cell, original).allocate(pieces, preferences);
			precise(result.momentum()[0][axis] / result.mass()[0], sign * (sparse ? -.25 : .25), "Swept micro source normal velocity");
			compareCarrierMoments(domain, cell, result);
			if (!sparse) for (int p = 0; p < original.size(); p++) {
				float normal = axis == 0 ? domain.particles.velocityX(p) : axis == 1 ? domain.particles.velocityY(p) : domain.particles.velocityZ(p);
				if (sign * normal < 0) check(result.markerWeights()[0][p].isZero(), "Represented sweep borrowed the opposite normal half");
			}
		}
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
		nearFaceJointRuleUsesPositiveUnequalWeights();
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
	private static void nearFaceJointRuleUsesPositiveUnequalWeights() {
		for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
			var moments = new CellRemapSpatialMoments(); var source = new double[8][6]; var weights = new double[8];
			for (int p = 0; p < 8; p++) {
				weights[p] = ((p & 4) == 0 ? Math.scalb(1.0, -20) : 1 - Math.scalb(1.0, -20)) / 4;
				for (int a = 0; a < 3; a++) {
					int coordinate = (a + axis) % 3;
					source[p][coordinate] = a == 2 ? sign * ((p & 4) == 0 ? .4 : .5 - Math.scalb(1.0, -24))
							: ((p & 1 << a) == 0 ? -1 : 1) / Math.sqrt(12);
					source[p][coordinate + 3] = (a + 1) * source[p][coordinate];
				}
				moments.add(weights[p], java.util.Arrays.copyOf(source[p], 3), java.util.Arrays.copyOfRange(source[p], 3, 6));
			}
			var volume = mmm.units.SignedDyadic.of(.25);
			var rule = moments.pairRule(volume, new double[]{-.5, -.5, -.5}, new double[]{.5, .5, .5});
			check(rule != null && rule.volumes().size() <= 2 * rule.rank() + CellRemapChemicalSupport.MAX_EXPANSION,
					"Near-face joint measure lost its bounded positive unequal rule");
			var parts = rule.volumes().stream().map(mmm.units.SignedDyadic::of).toArray(mmm.units.SignedDyadic[]::new);
			check(mmm.units.SignedDyadic.sum(parts).equals(volume), "Paired rule changed its exact volume");
			var masses = CellRemapMassAllocation.allocate(moments.mass(), volume, parts);
			var fitted = moments.fitPairs(masses, rule);
			for (double[] point : fitted.positions()) for (double coordinate : point)
				check(coordinate >= -.5 && coordinate <= .5, "Paired rule left its spatial box");
			verifyJointRule(masses, source, weights, fitted);
			var domain = new FluidDomain(3, 3, 3, FluidSolverConfig.prototypeDefaults(), 32);
			var target = moments.centroid(); for (int a = 0; a < 3; a++) target[a] *= moments.mass();
			var projection = new double[3]; var storage = new double[3];
			var prepared = CellRemapSampling.prepare(domain, domain.cellIndex(1, 1, 1), 255, volume, moments.mass(), Double.NaN,
					LiquidCellQuadrature.volumes(volume, 8), moments, new double[8][3], target, storage, 0, .5, projection, 32);
			checkPublished(domain, domain.cellIndex(1, 1, 1), new ArrayList<>(java.util.Arrays.asList(prepared.result().positions())),
					prepared.masses(), target, projection, storage);
		}
	}

	/** Three positive age nodes independently integrate uniform entry over a frozen substep. */
	private static void faceArrivalRetainsFiniteNormalThickness() {
		for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) for (boolean shear : new boolean[]{false, true}) {
			var moments = new CellRemapSpatialMoments();
			double duration = .125, face = -.5 * sign;
			double[][] reference = new double[24][6]; double[] weights = new double[24];
			for (int p = 0; p < 8; p++) {
				double[] position = {(p & 1) == 0 ? -.25 : .25, (p & 2) == 0 ? -.25 : .25, (p & 4) == 0 ? -.25 : .25};
				double[] velocity = {.125, -.25, .5};
				velocity[axis] = sign * (shear ? .5 + .25 * position[(axis + 1) % 3] : .5);
				moments.add(1, position, velocity);
				for (int age = 0; age < 3; age++) {
					int node = 3 * p + age;
					weights[node] = age == 1 ? 4 : 1;
					for (int a = 0; a < 3; a++) {
						reference[node][a] = a == axis ? face + age * duration / 2 * velocity[a] : position[a] + duration * velocity[a];
						reference[node][a + 3] = velocity[a];
					}
				}
			}
			moments.advancePositions(duration / 2);
			moments.projectPositionAxis(axis);
			var centroid = moments.centroid(); centroid[axis] = face; moments.setCentroid(centroid);
			moments.spreadFaceArrival(axis, duration);
			moments.advancePositions(duration / 2);
			check(moments.spatialRank() == 3, "Face arrival lost its finite normal support");
			verifyJointRule(moments, weights, reference);
			if (!shear) precise(moments.spatialVariance(axis), duration * duration * .25 / 12, "Uniform swept slab variance");
		}
	}

	private static void boundedFaceTangentsKeepMechanicalMoments() {
		supportContractionKeepsAngularMomentumAndEnergy();
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(1, 1000, 0, .05F, 200, 1e-5F, .45F, .002F, 0), 32);
		domain.setVolume(1, 1, 1, 1); domain.rebuildGridFromParticles();
		int donor = domain.cellIndex(1, 1, 1);
		for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) for (boolean skew : new boolean[]{false, true})
				for (boolean shear : new boolean[]{false, true}) {
			int tangent = (axis + 1) % 3, other = (axis + 2) % 3;
			int[] xyz = {1, 1, 1}; xyz[axis] += sign;
			var swept = LiquidAffineSweepGeometry.sweptExactSecondMoments(domain, donor,
					domain.cellIndex(xyz[0], xyz[1], xyz[2]), axis, sign > 0, sign * .125,
					sign * (skew ? .125 : .25), skew ? sign * .125 : 0, .5, 255, 255);
			var moments = new CellRemapSpatialMoments();
			double[] mean = {sign * .125, sign * -.25, sign * .0625}, velocity = {sign * .5, sign * -.25, sign * .125};
			for (int node = 0; node < (shear ? 4 : 2); node++) {
				double[] point = mean.clone(), value = velocity.clone();
				int side = (node & 1) == 0 ? -1 : 1, tangentSide = (node & 2) == 0 ? -1 : 1;
				point[axis] += side / 32.0; value[tangent] += side * sign / 8.0;
				if (shear) { point[tangent] += tangentSide / 16.0; value[other] += tangentSide * sign / 4.0; }
				moments.add(1, point, value);
			}
			check(moments.spatialRank() == (shear ? 2 : 1), "Face tangent fixture lost its original spatial rank");
			double[] added = moments.completeFaceTangents(axis, swept, 1.0 / 64, .25);
			double tangentVariance = skew ? 47.0 / 576 : 11.0 / 144, otherVariance = skew ? 47.0 / 576 : 1.0 / 12;
			double kappa = (1.0 / 16) / (16 * (tangentVariance + otherVariance));
			precise(added[axis], 0, "Face completion changed normal variance");
			precise(added[tangent], shear ? 0 : .25 * kappa * tangentVariance, "Wedge first tangent increment");
			precise(added[other], .25 * kappa * otherVariance, "Wedge second tangent increment");
			precise(added[0] + added[1] + added[2], shear ? .25 * kappa * otherVariance : 1.0 / 1024,
					"Volume-scaled spatial trace bound");
			check(moments.spatialRank() == 3 && moments.minimumCount() == 4,
					"Face completion did not produce its actual three-dimensional positive support");
			double[][] reference = new double[8][6]; double[] weights = new double[8];
			for (int p = 0; p < 8; p++) {
				weights[p] = 1;
				for (int a = 0; a < 3; a++) {
					int side = (p & 1 << a) == 0 ? -1 : 1;
					reference[p][a] = mean[a] + side * (a == axis ? 1.0 / 32 : shear && a == tangent ? 1.0 / 16 : Math.sqrt(added[a]));
					reference[p][a + 3] = velocity[a] + (a == tangent ? ((p & 1 << axis) == 0 ? -1 : 1) * sign / 8.0
							: shear && a == other ? ((p & 1 << tangent) == 0 ? -1 : 1) * sign / 4.0 : 0);
				}
				if (skew && !shear) {
					double crossLoading = -.25 * kappa / (576 * Math.sqrt(added[tangent]));
					reference[p][other] = mean[other] + ((p & 1 << tangent) == 0 ? -1 : 1) * crossLoading
							+ ((p & 1 << other) == 0 ? -1 : 1) * Math.sqrt(added[other] - crossLoading * crossLoading);
				}
			}
			// Full joint-moment comparison includes unchanged P, kinetic energy and angular momentum.
			verifyJointRule(moments, weights, reference);
			if (shear) {
				var fitted = moments.fit(weights, java.util.Arrays.stream(reference).map(p -> java.util.Arrays.copyOf(p, 3)).toArray(double[][]::new));
				for (int p = 0; p < weights.length; p++) precise(fitted.velocities()[p][other] - velocity[other],
						4 * sign * (fitted.positions()[p][tangent] - mean[tangent]), "Face completion retained linear tangential shear");
			} else {
				var combined = new CellRemapSpatialMoments();
				for (int side : new int[]{-1, 1}) {
					var point = mean.clone(); var value = velocity.clone(); point[axis] += side / 32.0; value[tangent] += side * sign / 8.0;
					combined.add(1, point, value);
				}
				var originalSpan = combined.spatialSpan();
				combined.completeFaceTangents(axis, swept, 1.0 / 64, .25, originalSpan);
				combined.completeFaceTangents(axis, swept, 1.0 / 64, .75, originalSpan);
				for (var point : reference) for (int a = 0; a < 3; a++) if (a != axis) point[a] = mean[a] + 2 * (point[a] - mean[a]);
				verifyJointRule(combined, weights, reference);
			}
		}
	}
	private static void supportContractionKeepsAngularMomentumAndEnergy() {
		var domain = new FluidDomain(3, 3, 3, FluidSolverConfig.prototypeDefaults(), 32);
		int cell = domain.cellIndex(1, 1, 1);
		for (int axis = 0; axis < 3; axis++) for (boolean enoughEnergy : new boolean[]{false, true}) {
			var moments = new CellRemapSpatialMoments(); var preferred = new double[8][3]; var weights = new double[8];
			double[] beforeAngular = new double[3]; double beforeEnergy = 0;
			for (int p = 0; p < 8; p++) {
				var x = preferred[p]; var v = new double[3]; weights[p] = .125;
				x[axis] = (p & 1) == 0 ? -.75 : .75;
				x[(axis + 1) % 3] = (p & 2) == 0 ? -.25 : .25;
				x[(axis + 2) % 3] = (p & 4) == 0 ? -.125 : .125;
				v[axis] = -.5 * x[(axis + 1) % 3]; v[(axis + 1) % 3] = .5 * x[axis];
				for (int a = 0; a < 3; a++) {
					if (enoughEnergy) v[a] += x[a];
					beforeEnergy += .5 * weights[p] * v[a] * v[a];
				}
				for (int a = 0; a < 3; a++) beforeAngular[a] += weights[p] *
						(x[(a + 1) % 3] * v[(a + 2) % 3] - x[(a + 2) % 3] * v[(a + 1) % 3]);
				moments.add(weights[p], x, v);
			}
			CellRemapSpatialMoments.Result fitted;
			try { fitted = CellRemapSampling.fit(domain, cell, 255, weights, moments, preferred,
					new double[3], new double[3], 1 << axis, .5, new double[3]); }
			catch (MechanicalTimeRefinement unavailable) { check(!enoughEnergy, "Feasible angular support was refused"); continue; }
			check(enoughEnergy, "Contraction retained angular momentum with insufficient actual kinetic energy");
			var angular = new double[3]; var momentum = new double[3]; var rounding = new double[3]; double energy = 0;
			for (int p = 0; p < 8; p++) for (int a = 0; a < 3; a++) {
				var x = fitted.positions()[p]; var v = fitted.velocities()[p];
				momentum[a] += weights[p] * v[a]; energy += .5 * weights[p] * v[a] * v[a];
				angular[a] += weights[p] * (x[(a + 1) % 3] * v[(a + 2) % 3] - x[(a + 2) % 3] * v[(a + 1) % 3]);
				rounding[a] += weights[p] * 4 * Math.ulp((float) (2 * domain.config.cellSize())) / domain.config.cellSize()
						* (Math.abs(v[(a + 1) % 3]) + Math.abs(v[(a + 2) % 3]));
			}
			precise(energy, beforeEnergy, "Support contraction kinetic energy");
			for (int a = 0; a < 3; a++) {
				precise(momentum[a], 0, "Support contraction momentum");
				check(Math.abs(angular[a] - beforeAngular[a]) <= rounding[a] + 64 * Math.ulp(Math.abs(beforeAngular[a])),
						"Support contraction changed angular momentum beyond coordinate storage rounding");
			}
		}
		check(CellRemapMechanicalFit.fit(new double[]{1, 1}, new double[][]{{0, -.25, 0}, {0, .25, 0}},
				new double[][]{{1, 0, 0}, {1, 0, 0}}, new double[]{0, .125, 0}, new double[]{1, 0, 0},
				new double[3], 0) == null, "Centroid transfer fabricated rotational energy from a uniform velocity source");
		var uniform = CellRemapMechanicalFit.fit(new double[]{1, 1}, new double[][]{{0, -.25, 0}, {0, .25, 0}},
				new double[][]{{1, 0, 0}, {1, 0, 0}}, new double[3], new double[]{1, 0, 0}, new double[3], 0);
		check(uniform != null && java.util.Arrays.equals(uniform[0], new double[]{1, 0, 0})
				&& java.util.Arrays.equals(uniform[0], uniform[1]), "Zero-variance source lost its unchanged uniform motion");
	}

	private static void verifyJointRule(CellRemapSpatialMoments moments, double[] weights, double[][] source) {
		verifyJointRule(moments, weights, source, weights);
	}

	private static void sparseFaceArrivalPublishesActualPressureSupport() {
		for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) for (int count : new int[]{1, 2}) {
			float h = .5F, dt = .125F;
			var domain = new FluidDomain(3, 3, 3,
					new FluidSolverConfig(h, 1000, 0, dt, 200, 1e-5F, .45F, .002F, 0), 64);
			float[] positions = new float[4 * count], velocities = new float[3 * count];
			for (int p = 0; p < count; p++) {
				for (int a = 0; a < 3; a++) positions[4 * p + a] = (1.5F + (a == axis && count == 2 ? (p == 0 ? -.125F : .125F) : 0)) * h;
				positions[4 * p + 3] = 1.0F / count; velocities[3 * p + axis] = sign * .25F;
			}
			domain.restoreParticleState(new FlipParticleState(3, 3, 3, h, count, 1, positions, velocities, new byte[27]));
			domain.rebuildGridFromParticles();
			java.util.Arrays.fill(domain.gridToParticleStage.advectionCells(), true);
			int donor = domain.cellIndex(1, 1, 1); int[] xyz = {1, 1, 1}; xyz[axis] += sign;
			int receiver = domain.cellIndex(xyz[0], xyz[1], xyz[2]);
			var flux = new ConservativeLiquidFlux(3, 3, 3); flux.geometry(new LiquidFluxGeometry(domain));
			int face = -1;
			for (int f = 0; f < flux.flux.length; f++) if (flux.axis[f] == axis
					&& flux.left[f] == (sign > 0 ? donor : receiver) && flux.right[f] == (sign > 0 ? receiver : donor)) face = f;
			check(face >= 0, "Sparse arrival has no shared face"); flux.velocity[face] = sign * .25;
			flux.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, dt / h, domain.markerGrid.exactCellVolumeInventory);
			check(flux.exactFlux[face].signum() > 0, "Sparse arrival has no positive flux");
			var remap = new CellCarrierRemapPlan(domain, flux, dt, new BallisticLiquidRemap(27));
			var volume = mmm.units.SignedDyadic.ZERO; var mass = volume; var momentum = volume; var energy = volume;
			double receivedMass = 0, shapeTrace = 0;
			for (var marker : remap.markers) {
				check(marker.volume() > 0 && marker.mass() > 0, "Sparse arrival published a nonpositive weight");
				volume = volume.add(mmm.units.SignedDyadic.of(marker.volume()));
				mass = mass.add(mmm.units.SignedDyadic.of(marker.mass()));
				double[] v = {marker.velocityX(), marker.velocityY(), marker.velocityZ()};
				for (int a = 0; a < 3; a++) check(v[a] == (a == axis ? sign * .25 : 0), "Sparse arrival changed constant velocity");
				momentum = momentum.add(mmm.units.SignedDyadic.of(marker.mass()).multiply(mmm.units.SignedDyadic.of(v[axis])));
				energy = energy.add(mmm.units.SignedDyadic.of(marker.mass()).multiply(mmm.units.SignedDyadic.of(v[axis])).multiply(mmm.units.SignedDyadic.of(v[axis])).multiply(mmm.units.SignedDyadic.of(.5)));
				if (marker.cell() == receiver) receivedMass += domain.unitScale.kilograms(marker.mass());
			}
			check(volume.equals(mmm.units.SignedDyadic.of(1)) && mass.equals(mmm.units.SignedDyadic.of(1000)),
					"Sparse arrival changed its exact raw inventory");
			check(momentum.equals(mmm.units.SignedDyadic.of(sign * 250)) && energy.equals(mmm.units.SignedDyadic.of(31.25)),
					"Sparse arrival changed its exact source P or KE");
			precise(domain.unitScale.signedKilograms(momentum.toDouble()), sign * 31.25, "Sparse arrival actual momentum");
			precise(domain.unitScale.kilograms(energy.toDouble()), 3.90625, "Sparse arrival actual kinetic energy");
			for (double component : remap.momentAudit.shapeApproximation()) { check(component >= 0, "Negative spatial approximation diagnostic"); shapeTrace += component; }
			double edge = Math.cbrt(flux.exactFlux[face].toDouble()), bound = receivedMass * h * h * edge * edge / 16;
			check(shapeTrace > 0 && shapeTrace <= bound + 128 * Math.ulp(bound), "Sparse arrival exceeded its spatial second-moment bound");
			var published = publishRemapForSupport(domain, remap);
			var basis = CellAffineVelocity.capture(published);
			check(basis.rank(receiver) == 4 && basis.hasRepresentableBasis(receiver), "Sparse arrival lacks actual published affine pressure support");
			var zero = new ConservativeLiquidFlux(3, 3, 3); zero.geometry(new LiquidFluxGeometry(domain));
			zero.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, 0, domain.markerGrid.exactCellVolumeInventory);
			for (boolean geometric : new boolean[]{false, true}) {
				var unchanged = new CellCarrierRemapPlan(domain, zero, geometric ? 0 : dt, new BallisticLiquidRemap(27), geometric);
				for (double component : unchanged.momentAudit.shapeApproximation()) check(component == 0, "Unmoved liquid gained a spatial shape approximation");
				check(CellAffineVelocity.capture(publishRemapForSupport(domain, unchanged)).rank(donor) == count,
						"Zero-time or zero-flux remap changed its actual source rank");
			}
		}
	}

	private static FluidDomain publishRemapForSupport(FluidDomain source, CellCarrierRemapPlan remap) {
		float h = source.config.cellSize(); int count = remap.markers.size();
		float[] positions = new float[4 * count], velocities = new float[3 * count]; int p = 0;
		for (var marker : remap.markers) {
			int[] xyz = {marker.cell() % 3, marker.cell() / 3 % 3, marker.cell() / 9};
			double[] offset = {marker.offsetX(), marker.offsetY(), marker.offsetZ()};
			double[] velocity = {marker.velocityX(), marker.velocityY(), marker.velocityZ()};
			for (int a = 0; a < 3; a++) { positions[4 * p + a] = (float) ((xyz[a] + .5 + offset[a]) * h); velocities[3 * p + a] = (float) velocity[a]; }
			positions[4 * p++ + 3] = marker.volume();
		}
		var result = new FluidDomain(3, 3, 3, source.config, 64);
		result.restoreParticleState(new FlipParticleState(3, 3, 3, h, count, 1, positions, velocities, new byte[27]));
		return result;
	}

	private static void verifyJointRule(CellRemapSpatialMoments moments, double[] weights, double[][] source, double[] sourceWeights) {
		double[][] preferred = new double[weights.length][3];
		for (int p = 0; p < weights.length; p++) System.arraycopy(source[p % source.length], 0, preferred[p], 0, 3);
		var result = moments.fit(weights, preferred);
		verifyJointRule(weights, source, sourceWeights, result);
	}
	private static void verifyJointRule(double[] weights, double[][] source, double[] sourceWeights, CellRemapSpatialMoments.Result result) {
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
				observed += outputProbability[p].toDouble() * (jointComponent(result, p, a) - expectedMean[a].toDouble())
						* (jointComponent(result, p, b) - expectedMean[b].toDouble());
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

	private static void partialCellInitializationHasPositiveWetLayerSupport() {
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 0, .05F, 200, 1e-5F, .45F, .002F, .8F), 64);
		domain.setVolume(0, 0, 0, .25F);
		var basis = CellAffineVelocity.capture(domain);
		var volume = mmm.units.SignedDyadic.ZERO; var firstY = mmm.units.SignedDyadic.ZERO;
		check(domain.particleCount() == 8 && basis.rank(0) == 4 && basis.hasRepresentableBasis(0),
				"Partial wet-layer initialization lacks positive three-dimensional affine support");
		for (int p = 0; p < domain.particleCount(); p++) {
			var weight = mmm.units.SignedDyadic.of(domain.particles.volume(p));
			check(weight.equals(mmm.units.SignedDyadic.of(.03125)) && domain.particles.mass(p) == 31.25,
					"Partial wet-layer initialization changed its actual source weights");
			volume = volume.add(weight); firstY = firstY.add(weight.multiply(mmm.units.SignedDyadic.of(domain.particles.positionY(p))));
		}
		check(volume.equals(mmm.units.SignedDyadic.of(.25)) && firstY.equals(volume.multiply(mmm.units.SignedDyadic.of(.0625))),
				"Partial wet-layer initialization changed its exact volume or physical centroid");
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
		domain.bootstrapGasState(""); var sourceGas = domain.gasState.frame;
		var flux = new ConservativeLiquidFlux(2, 2, 2);
		java.util.Arrays.fill(flux.exactFlux, mmm.units.SignedDyadic.ZERO);
		var capacities = new mmm.units.SignedDyadic[domain.cellCount];
		java.util.Arrays.fill(capacities, mmm.units.SignedDyadic.of(1));
		capacities[0] = mmm.units.SignedDyadic.of(0.5);
		flux.geometricResult(domain.markerGrid.exactCellVolumeInventory, capacities);
		var occupied = new long[domain.cellCount]; occupied[0] = mmm.granular.SolidVolume.SVQ_PER_CELL / 2;
		var remap = new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(domain.cellCount), true, occupied);
		domain.gasTopologyCoordinator.requireBeforeCarrierCommit(remap.gasTopology);
		check(domain.gasState.frame == sourceGas && remap.gasTopology.frame().poreVolume(0).equals(capacities[0]),
				"Prospective solid remap changed its source or supplied a different gas pore capacity");
		occupied[0] = 0;
		check(remap.gasTopology.frame().poreVolume(0).equals(capacities[0]), "Gas successor aliases prospective occupancy");
		try {
			new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(domain.cellCount), true, occupied);
			throw new AssertionError("Remap accepted conflicting liquid and gas pore capacities");
		} catch (IllegalArgumentException expected) { }
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
