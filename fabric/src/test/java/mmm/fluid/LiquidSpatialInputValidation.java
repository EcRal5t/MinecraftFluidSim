package mmm.fluid;

import java.util.Arrays;
import static mmm.fluid.MarkerVelocitySpaceExperiment.near;

/** Source/PLIC moment comparison, including disconnected and unresolved carrier support. */
final class LiquidSpatialInputValidation {
	private LiquidSpatialInputValidation() { }
	static void validate() {
		FlipFluidFreeSurfaceValidation.liquidPressureGeometryConservesShapeMoments();
		MarkerPressureValidation.pressureUsesCurrentMicroGasGeometry(false);
		var centred = MicroFaceVelocitySpaceExperiment.closedMask(0); var initial = capture(centred);
		if (initial.frame() != centred.gasState.frame) throw new AssertionError("Spatial input lost its captured gas frame identity");
		var comparison = initial.component(0, 255).comparison();
		if (LiquidVolumeMomentSupport.evaluate(initial.frame(), 0, 255, centred.sizeX, centred.sizeY).excess().signum() != 0)
			throw new AssertionError("Centred full volume violates its exact capacity moment");
		var fullRule = initial.geometry().componentQuadrature(0, 255);
		if (fullRule.size() != 8 || fullRule.stream().anyMatch(point -> !point.volume().equals(mmm.chemistry.ExactRational.ONE.divide(8))))
			throw new AssertionError("Full component lost its compact positive Gauss rule");
		near(comparison.volumeDifference(), 0, 0, "Centred source and PLIC volume agree");
		vector(comparison.volumeFirstMomentDifference(), 0, 0, 0, "Centred source and PLIC first moment agree");
		vector(comparison.uniformMassFirstMomentDifference(1000), 0, 0, 0, "Uniform centred actual mass first moment");
		near(comparison.observedMassKg(), 1000, 0, "Actual kilograms remain separate from raw normalized volume");
		tensor(comparison.volumeSecondMomentDifference(), -1.0 / 48, "Quarter-cell source versus continuous cube second moment");
		tensor(comparison.uniformMassSecondMomentDifference(1000), -1000.0 / 48, "Uniform source versus geometric inertia");
		tensor(comparison.representedLocal().covariance().observe(), 1.0 / 12, "Continuous cube covariance is centred on its geometric centroid");
		try { comparison.representedSecondMoment().entries().set(0, mmm.chemistry.ExactRational.ZERO); throw new AssertionError("Quadratic snapshot allowed mutation"); }
		catch (UnsupportedOperationException expected) { }
		var stock = centred.gasState.frame.liquidStock().getFirst().components().getFirst();
		if (!comparison.carriers().volume().equals(stock.volume()) || !comparison.carriers().volumeFirstMoment().x().equals(stock.firstMomentX()))
			throw new AssertionError("Spatial source disagrees with the captured complete liquid tuple");
		centred.particles.setMass(0, 250);
		var updated = LiquidSpatialInput.capture(centred);
		near(updated.component(0, 255).comparison().carriers().mass().toDouble(), 1125, 0, "Mass-only edits read current raw mass despite old index mass");
		near(comparison.carriers().mass().toDouble(), 1000, 0, "Prior source snapshot remains immutable");
		near(updated.component(0, 255).comparison().carriers().massSecondMoment().observe().xx(), 70.3125, 0, "Mass-only edit refreshes the actual second moment");
		near(comparison.carriers().massSecondMoment().observe().xx(), 62.5, 0, "Prior quadratic source snapshot remains immutable");
		if (!comparison.representedSecondMoment().equals(updated.component(0, 255).comparison().representedSecondMoment()))
			throw new AssertionError("Mass-only edit changed quadratic volume geometry");
		if (!initial.geometry().cellSnapshot(0).equals(updated.geometry().cellSnapshot(0))) throw new AssertionError("Mass-only edit changed the volume geometry");
		var shifted = MicroFaceVelocitySpaceExperiment.closedMask(0);
		for (int marker = 0; marker < shifted.particleCount(); marker++) shifted.particles.setPositionX(marker, shifted.particles.positionX(marker) + 1F / 16);
		var mismatch = capture(shifted).component(0, 255).comparison();
		if (LiquidVolumeMomentSupport.evaluate(shifted.gasState.frame, 0, 255, shifted.sizeX, shifted.sizeY).excess().signum() <= 0)
			throw new AssertionError("Translated full point measure was accepted as a full wet-volume moment");
		vector(mismatch.volumeFirstMomentDifference(), .0625, 0, 0, "Full source reports raw/PLIC volume moment difference");
		vector(mismatch.uniformMassFirstMomentDifference(1000), 62.5, 0, 0, "Uniform-density hypothesis reports its mass moment difference");
		disconnectedCancellation();
		emptyComponentAndUnresolvedPiece();
		halfCellUnits();
		capturedPoreScale();
		exactResidual();
		volumeMomentSupport();
		System.out.println("PASS liquid spatial input: exact raw volume/mass moments, shared PLIC piece integrals, disconnected and unresolved support, source immutability and stale-frame rejection");
	}
	private static void volumeMomentSupport() {
		var zero = mmm.chemistry.ExactRational.ZERO; var one = mmm.chemistry.ExactRational.ONE;
		// Independent face slabs with transverse motion omit the positive corner destination.
		for (int axis = 0; axis < 3; axis++) for (int tangent = 0; tangent < 3; tangent++) if (axis != tangent)
		for (int sign : new int[]{-1, 1}) for (int transverse : new int[]{-1, 1}) {
			var n = new double[3]; n[axis] = -sign; n[tangent] = transverse / 8.0;
			var direction = new GasGeometryOverlap.HalfSpace(mmm.units.SignedDyadic.of(n[0]), mmm.units.SignedDyadic.of(n[1]),
					mmm.units.SignedDyadic.of(n[2]), mmm.units.SignedDyadic.of(.375 + .5 * (n[0] + n[1] + n[2])));
			var incompatible = new mmm.chemistry.ExactRational[]{zero, zero, zero};
			incompatible[axis] = mmm.chemistry.ExactRational.of(-7L * sign).divide(128);
			incompatible[tangent] = mmm.chemistry.ExactRational.of(transverse).divide(64);
			if (LiquidVolumeMomentSupport.evaluate(255, one, one.divide(8), incompatible, direction).excess().signum() <= 0)
				throw new AssertionError("Single-face diagonal arrival acquired a positive volume representation");
			var actual = new mmm.chemistry.ExactRational[]{zero, zero, zero};
			actual[axis] = mmm.chemistry.ExactRational.of(-49L * sign).divide(1024);
			actual[tangent] = mmm.chemistry.ExactRational.of(7L * transverse).divide(1024);
			if (LiquidVolumeMomentSupport.evaluate(255, one, mmm.chemistry.ExactRational.of(7).divide(64), actual, direction).excess().signum() > 0)
				throw new AssertionError("Actual translated rectangle violates its volume moment support");
		}
		var plane = new GasGeometryOverlap.HalfSpace(mmm.units.SignedDyadic.of(-1), mmm.units.SignedDyadic.ZERO,
				mmm.units.SignedDyadic.ZERO, mmm.units.SignedDyadic.of(-1, -3));
		var first = new mmm.chemistry.ExactRational[]{mmm.chemistry.ExactRational.of(-7).divide(128), zero, zero};
		var slab = LiquidVolumeMomentSupport.evaluate(255, one, one.divide(8), first, plane);
		if (!slab.excess().isZero()) throw new AssertionError("Exact axis slab does not saturate its volume-moment upper bound");
		first[0] = first[0].divide(2);
		if (!LiquidVolumeMomentSupport.evaluate(255, one.divide(2), one.divide(16), first, plane).excess().isZero())
			throw new AssertionError("Capacity porosity was omitted or applied twice in the volume-moment bound");
		if (!LiquidVolumeMomentSupport.evaluate(0, one, zero, new mmm.chemistry.ExactRational[]{zero, zero, zero}, plane).excess().isZero())
			throw new AssertionError("Empty support acquired a nonzero volume moment");
		var corner = oneMarker(0, Math.scalb(1F, -16), Math.nextDown(1F)); var input = capture(corner);
		var certificate = LiquidVolumeMomentSupport.evaluate(input.frame(), 0, 255, corner.sizeX, corner.sizeY);
		if (certificate.excess().signum() <= 0 || certificate.excess().toDouble() / Math.scalb(1.0, -16) <= 16 * Math.ulp(1F))
			throw new AssertionError("Finite volume at a near-corner point did not exceed its physical moment support beyond storage rounding");
	}
	private static void disconnectedCancellation() {
		var d = MicroFaceVelocitySpaceExperiment.closedMask(126);
		for (int marker = 0; marker < d.particleCount(); marker++) {
			float x = d.particles.positionX(marker); d.particles.setPositionX(marker, x + (x < .5F ? 1 : -1) / 16F);
		}
		var input = capture(d);
		near(input.components(0).size(), 2, 0, "Disconnected components keep separate comparisons");
		var first = input.component(0, 1).comparison(); var second = input.component(0, 128).comparison();
		vector(first.volumeFirstMomentDifference(), .0078125, 0, 0, "First component spatial difference");
		vector(second.volumeFirstMomentDifference(), -.0078125, 0, 0, "Second component spatial difference");
		near(first.volumeFirstMomentDifference().x() + second.volumeFirstMomentDifference().x(), 0, 0, "Whole-cell cancellation cannot replace component data");
		var before = d.exportParticleState(); LiquidSpatialInput.capture(d); var after = d.exportParticleState();
		if (!Arrays.equals(before.copyPositionVolume(), after.copyPositionVolume()) || !Arrays.equals(before.copyParticleVelocities(), after.copyParticleVelocities()))
			throw new AssertionError("Spatial comparison rewrote raw particle columns");
		d.particles.setPositionX(0, Math.nextUp(d.particles.positionX(0)));
		boolean rejected = false;
		try { LiquidSpatialInput.capture(d); } catch (GasTopologyBlockedException expected) { rejected = true; }
		if (!rejected) throw new AssertionError("Spatial input accepted a stale gas geometry source");
	}
	private static void emptyComponentAndUnresolvedPiece() {
		var emptyDomain = capture(MicroFaceVelocitySpaceExperiment.domain(2, 2, 2));
		near(emptyDomain.component(7, 255).comparison().carriers().count(), 0, 0, "Empty cells retain fixed component identities");
		if (emptyDomain.piece(63) == null) throw new AssertionError("Entirely dry open piece identity was omitted");
		var d = oneMarker(126, .125F, .25F); var input = capture(d);
		var empty = input.component(0, 128).comparison();
		near(empty.carriers().count(), 0, 0, "Empty fixed component keeps its source tuple");
		near(empty.represented().volume(), 0, 0, "Empty fixed component has zero wet volume");
		tensor(empty.representedLocal().covariance().observe(), 0, "Empty geometric covariance remains zero");
		near(input.component(0, 128).poreScale(), 1, 0, "Dry disconnected component retains its open pore capacity");
		if (input.piece(7) == null) throw new AssertionError("Dry component piece identity was omitted");
		d.granularCoupling = new mmm.granular.GranularCouplingFields(2, 2, 2, 1);
		var occupied = new long[8]; occupied[0] = 3; d.granularCoupling.setFixedCellSvq(occupied);
		// A partial source leaves enough room for the integer occupancy allocated to both components.
		d.particles.setVolume(0, 1F / 16);
		d.gasState.frame = GasRuntimeGeometry.capture(d, null, occupied, d.gasState.sealedDomainBoundary);
		var occupiedInput = LiquidSpatialInput.capture(d);
		near(occupiedInput.component(0, 128).poreScale(), 1 - Math.scalb(1.0, -21), 0,
				"Dry component retains its one-SVQ share of the captured pore capacity");
		near(occupiedInput.piece(7).poreScale(), occupiedInput.component(0, 128).poreScale(), 0,
				"Dry microbox and component share the captured pore scale");
		var dry = MicroFaceVelocitySpaceExperiment.domain(2, 2, 2);
		var masks = new byte[8]; Arrays.fill(masks, (byte) 255); masks[0] = 126;
		dry.restoreParticleState(new FlipParticleState(2, 2, 2, 1, 0, 0, new float[0], new float[0], masks));
		dry.granularCoupling = new mmm.granular.GranularCouplingFields(2, 2, 2, 1);
		dry.granularCoupling.setFixedCellSvq(occupied);
		dry.gasState.frame = GasRuntimeGeometry.capture(dry, null, occupied, dry.gasState.sealedDomainBoundary);
		var dryInput = LiquidSpatialInput.capture(dry);
		near(dryInput.component(0, 1).poreScale(), 1 - Math.scalb(1.0, -20), 0,
				"Entirely dry cell retains its first component's two-SVQ allocation");
		near(dryInput.component(0, 128).poreScale(), 1 - Math.scalb(1.0, -21), 0,
				"Entirely dry cell retains its second component's one-SVQ allocation");
		for (var gasPiece : dry.gasState.frame.capacity().pieces())
			near(dryInput.piece(gasPiece.id()).poreScale() / 8, gasPiece.cubicMeters(), 0,
					"Dry liquid spatial view shares the gas piece's captured pore capacity");
		var unresolved = oneMarker(0, 1F / 64, .5F); var source = capture(unresolved);
		near(source.piece(7).comparison().carriers().count(), 1, 0, "Unresolved carrier keeps its actual microbox identity");
		near(source.piece(7).comparison().represented().volume(), 0, 0, "PLIC can leave the carrier's microbox dry");
		near(source.component(0, 255).comparison().carriers().volume().toDouble(), 1.0 / 64, 0, "Unresolved raw stock remains present");
	}
	private static void exactResidual() {
		var d = MicroFaceVelocitySpaceExperiment.closedMask(0); capture(d);
		for (int marker = 0; marker < d.particleCount(); marker++) {
			int micro = CellRemapMicroTopology.positionMicro(1, d.particles.positionX(marker), d.particles.positionY(marker), d.particles.positionZ(marker));
			d.particles.setMass(marker, micro == 0 || micro == 7 ? Math.scalb(1.0, 500) : 1);
		}
		var comparison = LiquidSpatialInput.capture(d).component(0, 255).comparison(); double density = Math.scalb(1.0, 501);
		near(comparison.uniformMassDifference(density), 6, 0, "Mass residual is computed before observer rounding");
		vector(comparison.uniformMassFirstMomentDifference(density), 3, 3, 3, "Mass moment residual survives large-mass cancellation");
		var quadratic = comparison.uniformMassSecondMomentDifference(Math.scalb(3.0, 499));
		for (int a = 0; a < 3; a++) near(quadratic.component(a, a), .375, 0,
				"Quadratic mass residual is subtracted before large-mass observer rounding");
	}
	private static void halfCellUnits() {
		var d = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, 0));
		for (int cell = 0; cell < d.cellCount - 1; cell++) d.setSolid(cell % 2, cell / 2 % 2, cell / 4, true);
		d.setVolume(1, 1, 1, 1); var comparison = capture(d).component(7, 255).comparison();
		near(comparison.carriers().mass().toDouble(), 1000, 0, "Half-metre cell retains raw solver mass");
		near(comparison.observedMassKg(), 125, 0, "Half-metre cell converts physical mass once");
		near(comparison.represented().volume(), .125, 0, "Half-metre cell PLIC volume uses cubic metres");
		vector(comparison.volumeFirstMomentDifference(), 0, 0, 0, "Half-metre volume moments share physical coordinates");
		vector(comparison.uniformMassFirstMomentDifference(1000), 0, 0, 0, "Half-metre mass moments use consistent SI scale");
		tensor(comparison.volumeSecondMomentDifference(), -1.0 / 1536, "Half-metre volume second moments use cubic volume and squared position scale");
		tensor(comparison.uniformMassSecondMomentDifference(1000), -1000.0 / 1536, "Half-metre source inertia has consistent units");
		tensor(comparison.representedLocal().covariance().observe(), 1.0 / 48, "Translated half-metre cell covariance uses squared metres");
		d.setVolume(1, 1, 1, Math.scalb(1F, -20));
		var thin = capture(d).component(7, 255).comparison(); var covariance = thin.representedLocal().covariance().observe();
		for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) near(covariance.component(a, b),
				a != b ? 0 : a == 1 ? Math.scalb(1.0 / 48, -40) : 1.0 / 48, 0,
				"Thin translated PLIC covariance is evaluated before local moment cancellation axes=" + a + "," + b);
	}
	private static void capturedPoreScale() {
		var d = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, 0));
		var masks = new byte[8]; Arrays.fill(masks, (byte) 255); masks[0] = 0;
		d.restoreParticleState(new FlipParticleState(2, 2, 2, .5F, 1, .75F, new float[]{.25F,.25F,.25F,.75F}, new float[3], masks));
		d.granularCoupling = new mmm.granular.GranularCouplingFields(2, 2, 2, .5F);
		var occupied = new long[8]; occupied[0] = mmm.granular.SolidVolume.fromCellVolumeExact(.25F); d.granularCoupling.setFixedCellSvq(occupied);
		d.gasState.frame = GasRuntimeGeometry.capture(d, null, occupied, false);
		var input = LiquidSpatialInput.capture(d); var component = input.component(0, 255); var comparison = component.comparison();
		near(component.poreScale(), .75, 0, "Captured component porosity remains explicit");
		near(comparison.wetEnvelope().volume(), .125, 0, "PLIC coverage remains a geometric measure");
		near(comparison.represented().volume(), .09375, 0, "Represented liquid volume includes pore scale");
		near(comparison.volumeDifference(), 0, 0, "Pore occupancy is distinguished from source approximation error");
		vector(comparison.uniformMassFirstMomentDifference(1000), 0, 0, 0, "Pore-scaled uniform mass moment");
		near(input.geometry().cellSnapshot(0).rawMarkerVolume(), .09375, 0, "Frame geometry reads captured raw stock rather than an unprepared index");
		tensor(comparison.wetEnvelopeSecondMoment().observe(), 1.0 / 384, "Full geometric quadratic envelope");
		tensor(comparison.representedSecondMoment().observe(), 1.0 / 512, "Quadratic geometry uses captured porosity");
		tensor(comparison.volumeSecondMomentDifference(), -1.0 / 512, "Single-point source has zero cell-centred quadratic measure");
		tensor(comparison.representedLocal().covariance().observe(), 1.0 / 48, "Scalar porosity preserves the geometric covariance");
	}
	private static FluidDomain oneMarker(int mask, float volume, float position) {
		var d = MicroFaceVelocitySpaceExperiment.domain(2, 2, 2); var masks = new byte[8]; Arrays.fill(masks, (byte) 255); masks[0] = (byte) mask;
		d.restoreParticleState(new FlipParticleState(2, 2, 2, 1, 1, volume, new float[]{position, position, position, volume}, new float[3], masks));
		return d;
	}
	private static LiquidSpatialInput capture(FluidDomain d) {
		d.gridDirty = true; d.ensureInventoryGrid(); d.gasTopologyCoordinator.refresh(); return LiquidSpatialInput.capture(d);
	}
	private static void vector(LiquidPressureGeometry.Vec3 actual, double x, double y, double z, String message) {
		near(actual.x(), x, 0, message + " X"); near(actual.y(), y, 0, message + " Y"); near(actual.z(), z, 0, message + " Z");
	}
	private static void tensor(LiquidPressureGeometry.SecondMoment actual, double diagonal, String message) {
		for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) near(actual.component(a, b), a == b ? diagonal : 0, 0, message + " axes=" + a + "," + b);
	}
}
