package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalTransport;
import java.util.Arrays;

/** Production thermal stage, exact wall tiles, bounded work and immutable backend rejection. */
final class ResolvedLiquidConductionStageValidation {
	static void validate(FluidDomain source) {
		ambientWallExchange(source);
		var domain = thermalDomain(source, source.config.maxSolverIterations());
		var field = wallField(domain, false); domain.replaceThermalBoundaryTemperatures(field);
		var input = domain.captureAuthoritativeState(); var liquid = domain.liquidGeometryState; var gas = domain.gasState.geometry;
		var candidate = new CpuFluidSolverBackend().evaluateThermal(new FluidThermalStepRequest(17, input, false, 0));
		FluidDomainStateAssertions.assertSameState(input, domain.captureAuthoritativeState(), "resolved thermal backend input");
		var next = candidate.claimPreparedCpuDomain(); var stats = candidate.stats().transport();
		if (candidate.candidateGeneration() != 18 || stats.substeps() != 1 || stats.reservoirExchange().signum() <= 0
				|| !next.exactInternalEnergy().equals(next.thermalAudit().expectedInventory())
				|| !next.thermalAudit().roundoffResidual().subtract(domain.thermalAudit().roundoffResidual()).equals(stats.roundoffResidual())
				|| !next.thermalAudit().externalSource().subtract(domain.thermalAudit().externalSource()).equals(stats.reservoirExchange()))
			throw new AssertionError("Resolved thermal stage failed complete caloric publication or its runtime audit");
		double first = next.thermalState.temperature(0, next.thermalMarkers), second = next.thermalState.temperature(1, next.thermalMarkers);
		if (!(first > 270 && second > first)) throw new AssertionError("Resolved wall tiles lost their different prescribed temperatures");
		if (!liquid.cells().equals(next.liquidGeometryState.cells()) || !Arrays.equals(liquid.copyMarkerCells(), next.liquidGeometryState.copyMarkerCells())
				|| !gas.cells().equals(next.gasState.geometry.cells()) || !input.gas().nodes().equals(next.captureAuthoritativeState().gas().nodes()))
			throw new AssertionError("Conduction stage changed spatial material or gas inventory");
		var mirrored = thermalDomain(source, source.config.maxSolverIterations()); mirrored.replaceThermalBoundaryTemperatures(wallField(mirrored, true));
		mirrored.stepThermalAxis(false, 0);
		closeTemperature(first, mirrored.thermalState.temperature(1, mirrored.thermalMarkers));
		closeTemperature(second, mirrored.thermalState.temperature(0, mirrored.thermalMarkers));
		var restored = FluidDomain.restoreAuthoritativeState(next.captureAuthoritativeState());
		if (!next.exactInternalEnergy().equals(restored.exactInternalEnergy()) || !next.thermalAudit().equals(restored.thermalAudit()))
			throw new AssertionError("Production resolved conduction failed cold handoff");
		var stale = new ResolvedLiquidConductionPlan(domain, domain.config.timeStep());
		domain.replaceThermalBoundaryTemperatures(wallField(domain, true));
		var before = domain.captureAuthoritativeState();
		try { stale.publish(); throw new AssertionError("Changed wall field retained a conduction candidate"); }
		catch (IllegalStateException expected) { }
		FluidDomainStateAssertions.assertSameState(before, domain.captureAuthoritativeState(), "stale resolved thermal wall");
		var limited = thermalDomain(source, 1); before = limited.captureAuthoritativeState();
		try { limited.thermalTransportStage.step(limited.config.timeStep()); throw new AssertionError("Insufficient conduction work published a partial stage"); }
		catch (FluidDomain.ThermalTransportBlockedException expected) { }
		FluidDomainStateAssertions.assertSameState(before, limited.captureAuthoritativeState(), "resolved conduction work rejection");
		before = source.captureAuthoritativeState();
		try { source.stepThermalAxis(false, 0); throw new AssertionError("Unmapped native chemistry entered the old thermal axis"); }
		catch (FluidDomain.ThermalTransportBlockedException expected) { }
		FluidDomainStateAssertions.assertSameState(before, source.captureAuthoritativeState(), "unmapped resolved chemistry");
	}
	private static void ambientWallExchange(FluidDomain source) {
		var domain = new FluidDomain(source.sizeX, source.sizeY, source.sizeZ, source.config, 16);
		var masks = new byte[domain.cellCount];
		for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) masks[domain.cellIndex(x, y, 0)] = (byte) FlipCutCellGeometry.FULL_MASK;
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), 0, 0, 0,
				source.materialRegistry().materials(), new float[0], new float[0], masks, new double[0], new int[0],
				new LiquidGeometryState(1, java.util.List.of(), new int[0])));
		domain.bindGasRegistry(source.gasState.network.registry());
		domain.bindThermal(new ThermalRegistry(source.thermalState.registry().materials()), domain.materialRegistry(),
				source.thermalState.gas().ambientTemperatureK(), ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		var field = new double[6 * domain.cellCount]; Arrays.fill(field, Double.NaN);
		for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) field[6 * domain.cellIndex(x, y, 1) + 4] = x == 0 ? 320 : 360;
		domain.replaceThermalBoundaryTemperatures(field);
		var energy = domain.exactInternalEnergy(); var stats = domain.stepThermalAxis(false, 0).transport();
		if (domain.gasState.network.nodeCount() != 0 || stats.substeps() != 1 || stats.evaluatedContacts() == 0
				|| !domain.exactInternalEnergy().equals(energy) || stats.reservoirExchange().signum() != 0 || stats.roundoffResidual().signum() != 0
				|| !domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()))
			throw new AssertionError("Wall/ambient conduction created finite region heat capacity or internal energy");
	}
	private static FluidDomain thermalDomain(FluidDomain source, int iterations) {
		var c = source.config;
		var config = new FluidSolverConfig(c.cellSize(), c.density(), c.gravity(), c.timeStep(), iterations, c.pressureTolerance(),
				c.maxCourantNumber(), c.fluidEpsilon(), c.interfaceCompression(), c.viscousWallCondition());
		var domain = new FluidDomain(source.sizeX, source.sizeY, source.sizeZ, config, 16);
		domain.restoreMaterialParticleState(source.exportMaterialParticleState()); domain.sealDomainBoundary();
		domain.bindGasRegistry(source.gasState.network.registry());
		domain.bindThermal(new ThermalRegistry(source.thermalState.registry().materials()), domain.materialRegistry(),
				source.thermalState.gas().ambientTemperatureK(), ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		domain.carrierMutations().publishGeometricRepartition(new LiquidInventoryRepartitionPlan(domain));
		for (int p = 0; p < domain.particleCount(); p++) domain.thermalState.resetMarkerTemperature(p, 270, domain.thermalMarkers, domain.exactInternalEnergy());
		return domain;
	}
	private static double[] wallField(FluidDomain domain, boolean mirror) {
		var field = new double[6 * domain.cellCount]; Arrays.fill(field, Double.NaN);
		for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++)
			field[6 * domain.cellIndex(x, y, 0) + 4] = (mirror ? domain.sizeX - 1 - x : x) == 0 ? 320 : 360;
		return field;
	}
	private static void closeTemperature(double actual, double expected) {
		if (Math.abs(actual - expected) > Math.ulp(actual) + Math.ulp(expected))
			throw new AssertionError("Reflected prescribed wall temperatures changed the thermal solution: " + actual + " / " + expected);
	}
}
