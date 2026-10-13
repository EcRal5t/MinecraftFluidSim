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
		near(comparison.volumeDifference(), 0, 0, "Centred source and PLIC volume agree");
		vector(comparison.volumeFirstMomentDifference(), 0, 0, 0, "Centred source and PLIC first moment agree");
		vector(comparison.uniformMassFirstMomentDifference(1000), 0, 0, 0, "Uniform centred actual mass first moment");
		near(comparison.observedMassKg(), 1000, 0, "Actual kilograms remain separate from raw normalized volume");
		var stock = centred.gasState.frame.liquidStock().getFirst().components().getFirst();
		if (!comparison.carriers().volume().equals(stock.volume()) || !comparison.carriers().volumeFirstMoment().x().equals(stock.firstMomentX()))
			throw new AssertionError("Spatial source disagrees with the captured complete liquid tuple");
		centred.particles.setMass(0, 250);
		var updated = LiquidSpatialInput.capture(centred);
		near(updated.component(0, 255).comparison().carriers().mass().toDouble(), 1125, 0, "Mass-only edits read current raw mass despite old index mass");
		near(comparison.carriers().mass().toDouble(), 1000, 0, "Prior source snapshot remains immutable");
		if (!initial.geometry().cellSnapshot(0).equals(updated.geometry().cellSnapshot(0))) throw new AssertionError("Mass-only edit changed the volume geometry");
		var shifted = MicroFaceVelocitySpaceExperiment.closedMask(0);
		for (int marker = 0; marker < shifted.particleCount(); marker++) shifted.particles.setPositionX(marker, shifted.particles.positionX(marker) + 1F / 16);
		var mismatch = capture(shifted).component(0, 255).comparison();
		vector(mismatch.volumeFirstMomentDifference(), .0625, 0, 0, "Full source reports raw/PLIC volume moment difference");
		vector(mismatch.uniformMassFirstMomentDifference(1000), 62.5, 0, 0, "Uniform-density hypothesis reports its mass moment difference");
		disconnectedCancellation();
		emptyComponentAndUnresolvedPiece();
		halfCellUnits();
		capturedPoreScale();
		exactResidual();
		System.out.println("PASS liquid spatial input: exact raw volume/mass moments, shared PLIC piece integrals, disconnected and unresolved support, source immutability and stale-frame rejection");
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
		if (input.piece(7) == null) throw new AssertionError("Dry component piece identity was omitted");
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
	}
	private static void halfCellUnits() {
		var d = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, 0));
		for (int cell = 1; cell < d.cellCount; cell++) d.setSolid(cell % 2, cell / 2 % 2, cell / 4, true);
		d.setVolume(0, 0, 0, 1); var comparison = capture(d).component(0, 255).comparison();
		near(comparison.carriers().mass().toDouble(), 1000, 0, "Half-metre cell retains raw solver mass");
		near(comparison.observedMassKg(), 125, 0, "Half-metre cell converts physical mass once");
		near(comparison.represented().volume(), .125, 0, "Half-metre cell PLIC volume uses cubic metres");
		vector(comparison.volumeFirstMomentDifference(), 0, 0, 0, "Half-metre volume moments share physical coordinates");
		vector(comparison.uniformMassFirstMomentDifference(1000), 0, 0, 0, "Half-metre mass moments use consistent SI scale");
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
}
