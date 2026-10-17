package mmm.fluid;

import java.util.Arrays;
import java.util.List;
import mmm.fluid.air.GasAuditLedger;
import mmm.units.SignedDyadic;

/** Normal conservative transport, macroscopic motion and complete backend recovery. */
public final class CellRemapMomentsValidation {
	private CellRemapMomentsValidation() { }

	public static void main(String[] args) {
		if (args.length > 1) throw new IllegalArgumentException("Expected one transport validation selector");
		String entry = args.length == 0 ? "all" : args[0];
		switch (entry) {
			case "common-mesh" -> generalFieldContinuesAfterRestore();
			case "transport-moments" -> { generalFieldContinuesAfterRestore(); translationAndMaterialTransport(); }
			case "joint-moments", "footprint-only" -> { rotationRetainsMacroscopicInertia(); materialTransportKeepsInventories(); }
			case "sweep-only" -> translationAndMaterialTransport();
			case "all" -> { generalFieldContinuesAfterRestore(); translationAndMaterialTransport(); rotationRetainsMacroscopicInertia(); }
			default -> throw new IllegalArgumentException("Unknown remap validation entry: " + entry);
		}
		System.out.println("Conservative liquid transport validation passed: " + entry);
	}

	/** Original L-shaped source: h=1, dt=.125, three full cells and velocity (1,1,0). */
	static FluidDomain lShapedTranslationSource() {
		var domain = new FluidDomain(5, 5, 5,
				new FluidSolverConfig(1, 1000, 0, .125F, 200, 1e-5F, .45F, .002F, 0), 4096);
		domain.setVolume(2, 2, 2, 1); domain.setVolume(1, 2, 2, 1); domain.setVolume(2, 1, 2, 1);
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			domain.particles.setVelocityX(marker, 1); domain.particles.setVelocityY(marker, 1);
		}
		domain.sealDomainBoundary(); domain.bootstrapGasState(null);
		return domain;
	}

	private static void generalFieldContinuesAfterRestore() {
		var domain = lShapedTranslationSource(); var before = Motion.capture(domain);
		domain = runSteps(domain, 8, false, 0, "L-shape");
		var after = Motion.capture(domain);
		// Eight steps move one cell; first-order volume transport may smear the interface within a cell.
		check(after.volumeCenter[0] > before.volumeCenter[0] + .25
				&& after.volumeCenter[1] > before.volumeCenter[1] + .25,
				"The restored L-shaped liquid did not continue moving in both prescribed directions");
		check(after.kinetic <= 1.05 * before.kinetic, "Unforced L-shaped transport amplified kinetic energy");
	}

	private static void translationAndMaterialTransport() {
		for (float h : new float[]{.5F, 1}) for (int axes : new int[]{1, 3, 7}) for (int sign : new int[]{-1, 1}) {
			var domain = new FluidDomain(7, 7, 7,
					new FluidSolverConfig(h, 1000, 0, .125F, 200, 1e-5F, .4F, .002F, .8F), 4096);
			domain.setVolume(3, 3, 3, 1);
			for (int marker = 0; marker < domain.particleCount(); marker++) for (int axis = 0; axis < 3; axis++)
				setVelocity(domain, marker, axis, (axes & 1 << axis) == 0 ? 0 : sign * h);
			domain.sealDomainBoundary(); domain.bootstrapGasState(null);
			var before = Motion.capture(domain); domain = runSteps(domain, 4, false, 0, "translation-" + axes + "-" + sign + "-h" + h);
			var after = Motion.capture(domain);
			for (int axis = 0; axis < 3; axis++) {
				double displacement = after.volumeCenter[axis] - before.volumeCenter[axis];
				if ((axes & 1 << axis) == 0) check(Math.abs(displacement) <= .05 * h, "Translation gained transverse motion");
				else check(sign * displacement > .2 * h, "Uniform liquid translation stalled after recovery");
			}
			check(after.kinetic <= before.kinetic * 1.05, "Uniform translation gained kinetic energy");
		}
		materialTransportKeepsInventories();
	}

	private static void materialTransportKeepsInventories() {
		var domain = new FluidDomain(7, 5, 5,
				new FluidSolverConfig(1, 1000, 0, .05F, 200, 1e-5F, .4F, .002F, .8F), 4096);
		domain.setVolume(2, 2, 2, 1); domain.setVolume(3, 2, 2, 1);
		var materials = new FluidMaterialRegistry(List.of(
				new FluidMaterial("test:transport_dense", 1000, "test:transport", 0, Math.PI / 2),
				new FluidMaterial("test:transport_light", 2.5, "test:transport", 0, Math.PI / 2)));
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			domain.setMarkerMaterial(marker, materials, domain.particles.positionX(marker) < 3 ? 0 : 1);
			domain.particles.setVelocityX(marker, .5F);
		}
		domain.sealDomainBoundary(); domain.bootstrapGasState(null);
		var materialVolume = materialVolume(domain); var materialMass = materialMass(domain);
		domain = runSteps(domain, 4, false, 0, "two-density");
		check(Arrays.equals(materialVolume, materialVolume(domain)), "Transport exchanged volume between material ledgers");
		var after = materialMass(domain);
		for (int material = 0; material < after.length; material++)
			close(after[material].toDouble(), materialMass[material].toDouble(), 1e-11, "Per-material mass");
	}

	private static void rotationRetainsMacroscopicInertia() {
		for (int axis = 0; axis < 3; axis++) {
			var domain = new FluidDomain(7, 7, 7,
					new FluidSolverConfig(1, 1000, 0, .025F, 200, 1e-5F, .4F, .002F, .8F), 4096);
			for (int z = 2; z < 5; z++) for (int y = 2; y < 5; y++) for (int x = 2; x < 5; x++) domain.setVolume(x, y, z, 1);
			int first = (axis + 1) % 3, second = (axis + 2) % 3;
			for (int marker = 0; marker < domain.particleCount(); marker++) {
				setVelocity(domain, marker, first, (float) (-.1 * (position(domain, marker, second) - 3.5)));
				setVelocity(domain, marker, second, (float) (.1 * (position(domain, marker, first) - 3.5)));
			}
			domain.sealDomainBoundary(); domain.bootstrapGasState(null);
			var before = Motion.capture(domain); domain = runSteps(domain, 4, false, 0, "rotation-" + axis); var after = Motion.capture(domain);
			// A 3-cell-radius diameter is coarse: quantify accumulated first-order transport dissipation.
			check(after.angular[axis] > .8 * before.angular[axis] && after.angular[axis] <= 1.05 * before.angular[axis],
					"Resolved macroscopic rotation exceeded the 20% transport-decay budget");
			check(after.kinetic <= 1.05 * before.kinetic, "Unforced rotation gained kinetic energy");
		}
	}

	/** Captures source, restores a private backend candidate, publishes once, then captures and restores the result. */
	static FluidDomain runSteps(FluidDomain domain, int steps, boolean phase, int maximumOperations, String label) {
		var inventory = Inventory.capture(domain); var backend = new CpuFluidSolverBackend();
		for (int step = 0; step < steps; step++) {
			long started = System.nanoTime(); var source = domain.captureAuthoritativeState();
			var candidate = backend.evaluate(new FluidStepRequest(step, source, List.of(), phase, maximumOperations, true));
			domain = candidate.claimPreparedCpuDomain();
			domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			double fullMs = (System.nanoTime() - started) / 1e6;
			inventory.requireUnchanged(domain, label + " step=" + step);
			System.out.printf("TRANSPORT %s step=%d fullMs=%.3f substeps=%d pressureIterations=%d residual=%g markers=%d gasNodes=%d K=%g%n",
					label, step, fullMs, candidate.stats().substeps(), candidate.stats().pressureIterations(),
					(double) candidate.stats().pressureResidual(), domain.particleCount(), domain.gasState.network.nodeCount(), Motion.capture(domain).kinetic);
		}
		return domain;
	}

	record Inventory(SignedDyadic volume, double mass, long[] species, GasAuditLedger gas, double gasCapacity) {
		static Inventory capture(FluidDomain domain) {
			domain.synchronizeGasTopologyForPersistence();
			return new Inventory(rawVolume(domain), domain.totalMass(), domain.chemicalParcels == null ? null
					: domain.chemicalParcels.speciesTotals(domain.particleCount()), domain.gasState.network.sealedInventory(), CellRemapMomentsValidation.gasCapacity(domain));
		}
		void requireUnchanged(FluidDomain domain, String context) {
			domain.ensureInventoryGrid();
			check(rawVolume(domain).equals(volume), context + ": liquid volume changed");
			close(domain.totalMass(), mass, 1e-11, context + ": liquid mass");
			if (species != null) check(Arrays.equals(species, domain.chemicalParcels.speciesTotals(domain.particleCount())), context + ": species changed");
			var actualGas = domain.gasState.network.sealedInventory();
			check(actualGas.standardVolumeNanos() == gas.standardVolumeNanos(), context + ": sealed gas inventory changed");
			for (var component : gas.entries()) {
				var after = actualGas.component(component.componentId());
				check(after != null && after.standardVolumeNanos() == component.standardVolumeNanos()
						&& after.species().equals(component.species()), context + ": gas component inventory changed");
			}
			check(domain.gasBoundary().auditClosed(), context + ": gas audit does not close");
			close(CellRemapMomentsValidation.gasCapacity(domain), gasCapacity, 2e-12, context + ": closed gas capacity");
			for (int cell = 0; cell < domain.cellCount; cell++) {
				double amount = domain.markerGrid.exactCellVolumeInventory[cell].toDouble();
				check(amount >= 0 && amount <= domain.effectiveOpenCellVolume(cell) + 8 * Math.ulp(1F), context + ": cell capacity exceeded at " + cell);
			}
			for (int marker = 0; marker < domain.particleCount(); marker++) {
				check(domain.particles.volume(marker) > 0 && domain.particles.mass(marker) > 0, context + ": nonpositive material point");
				for (int axis = 0; axis < 3; axis++) check(Double.isFinite(velocity(domain, marker, axis))
						&& Double.isFinite(position(domain, marker, axis)), context + ": nonfinite carrier state");
			}
			if (domain.thermalState != null) check(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()), context + ": thermal audit does not close");
		}
	}

	record Motion(double[] volumeCenter, double[] momentum, double[] angular, double kinetic) {
		static Motion capture(FluidDomain domain) {
			var center = new double[3]; var momentum = new double[3]; var angular = new double[3]; double total = 0, kinetic = 0;
			for (int marker = 0; marker < domain.particleCount(); marker++) {
				double volume = domain.particles.volume(marker), mass = domain.unitScale.kilograms(domain.particles.mass(marker)); total += volume;
				for (int axis = 0; axis < 3; axis++) {
					double x = position(domain, marker, axis), v = velocity(domain, marker, axis);
					center[axis] += volume * x; momentum[axis] += mass * v; kinetic += .5 * mass * v * v;
					int first = (axis + 1) % 3, second = (axis + 2) % 3;
					angular[axis] += mass * (position(domain, marker, first) * velocity(domain, marker, second)
							- position(domain, marker, second) * velocity(domain, marker, first));
				}
			}
			for (int axis = 0; axis < 3; axis++) center[axis] /= total;
			return new Motion(center, momentum, angular, kinetic);
		}
	}
	private static SignedDyadic rawVolume(FluidDomain domain) {
		var volume = SignedDyadic.ZERO;
		for (int marker = 0; marker < domain.particleCount(); marker++) volume = volume.add(SignedDyadic.of(domain.particles.volume(marker)));
		return volume;
	}
	private static SignedDyadic[] materialVolume(FluidDomain domain) {
		var result = new SignedDyadic[]{SignedDyadic.ZERO, SignedDyadic.ZERO};
		for (int marker = 0; marker < domain.particleCount(); marker++) { int material = domain.particles.materialHandle(marker); result[material] = result[material].add(SignedDyadic.of(domain.particles.volume(marker))); }
		return result;
	}
	private static SignedDyadic[] materialMass(FluidDomain domain) {
		var result = new SignedDyadic[]{SignedDyadic.ZERO, SignedDyadic.ZERO};
		for (int marker = 0; marker < domain.particleCount(); marker++) { int material = domain.particles.materialHandle(marker); result[material] = result[material].add(SignedDyadic.of(domain.particles.mass(marker))); }
		return result;
	}
	private static double gasCapacity(FluidDomain domain) {
		double total = 0; for (int node = 1; node <= domain.gasState.network.nodeCount(); node++) total += domain.gasState.network.availableVolume(node); return total;
	}
	private static double position(FluidDomain d, int p, int axis) { return axis == 0 ? d.particles.positionX(p) : axis == 1 ? d.particles.positionY(p) : d.particles.positionZ(p); }
	private static double velocity(FluidDomain d, int p, int axis) { return axis == 0 ? d.particles.velocityX(p) : axis == 1 ? d.particles.velocityY(p) : d.particles.velocityZ(p); }
	private static void setVelocity(FluidDomain d, int p, int axis, float value) { if (axis == 0) d.particles.setVelocityX(p, value); else if (axis == 1) d.particles.setVelocityY(p, value); else d.particles.setVelocityZ(p, value); }
	private static void close(double actual, double expected, double relative, String context) { check(Math.abs(actual - expected) <= relative * Math.max(1, Math.abs(expected)), context + ": expected=" + expected + " actual=" + actual); }
	private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
