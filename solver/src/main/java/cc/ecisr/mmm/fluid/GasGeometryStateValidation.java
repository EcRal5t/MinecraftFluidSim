package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.GasGeometryState;

/** Complete resolved gas geometry for immutable backend and checkpoint admission. */
public final class GasGeometryStateValidation {
	private GasGeometryStateValidation() { }
	public static GasGeometryState capture(FlipParticleState particles, boolean sealedExterior, int maximumRegions) {
		return new ResolvedGasGeometry(input(particles, sealedExterior, maximumRegions)).state;
	}
	public static void requireCompatible(GasGeometryState gas, FlipParticleState particles, int maximumRegions, int maximumPairs) {
		if (gas == null) throw new IllegalArgumentException("Resolved gas support is missing");
		if (particles == null || particles.liquidGeometry() == null) throw new IllegalArgumentException("Resolved gas requires complete liquid geometry");
		var state = particles.liquidGeometry(); var cells = new java.util.ArrayList<>(LiquidGeometryStateMapper.restore(state));
		cells.addAll(LiquidGeometryStateMapper.restoreCells(gas.cells()));
		var domain = new LiquidGeometryDomainPartition(particles.sizeX(), particles.sizeY(), particles.sizeZ(), particles.cellSize(), particles.copyCollisionMicroMasks(), cells, maximumRegions);
		ResolvedGasGeometry.requireCompatible(gas, new LiquidGeometryPhaseInput(state, domain, gas, maximumRegions), maximumPairs);
	}
	private static LiquidGeometryPhaseInput input(FlipParticleState particles, boolean sealedExterior, int maximumRegions) {
		if (particles == null || particles.liquidGeometry() == null) throw new IllegalArgumentException("Resolved gas requires complete liquid geometry");
		var state = particles.liquidGeometry(); var cells = LiquidGeometryStateMapper.restore(state);
		var domain = new LiquidGeometryDomainPartition(particles.sizeX(), particles.sizeY(), particles.sizeZ(), particles.cellSize(),
				particles.copyCollisionMicroMasks(), cells, maximumRegions);
		return new LiquidGeometryPhaseInput(state, domain, sealedExterior, maximumRegions);
	}
}
