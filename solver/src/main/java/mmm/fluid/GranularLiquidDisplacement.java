package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.units.SignedDyadic;
import java.util.Arrays;

/** Uses the common cell face network when granular occupation changes liquid pore capacity. */
final class GranularLiquidDisplacement {
	private GranularLiquidDisplacement() { }
	static final class CapacityUnavailable extends IllegalStateException {
		CapacityUnavailable(RuntimeException cause) {
			super("Connected liquid space has insufficient displacement capacity: " + cause.getMessage(), cause);
		}
	}
	static final class Prepared {
		final LiquidCellInventory.Plan inventory;
		final GasTopologyCoordinator.GasTopologyPlan gasTopology;
		final SignedDyadic energyRoundoff;
		final CellMomentumTransport.Prepared momentum;
		final MechanicalEnergySettlement.Plan energy;
		Prepared(LiquidCellInventory.Plan inventory, GasTopologyCoordinator.GasTopologyPlan gasTopology, CellMomentumTransport.Prepared momentum, MechanicalEnergySettlement.Plan energy) {
			this.inventory = inventory; this.gasTopology = gasTopology;
			energyRoundoff = inventory.energyRoundoff();
			this.momentum = momentum; this.energy=energy;
		}
	}
	static long[] begin(FluidDomain domain) {
		if (!domain.domainPhaseInventory.hasGranularInventory()) return null;
		domain.domainFlowFields.ensureInventoryGrid();
		domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		return domain.entityBodyCoupling.addTo(domain.granularCoupling.copySolidSvq());
	}
	static void commit(FluidDomain domain, long[] previous) {
		if (previous == null) return;
		var candidate = prepare(domain, previous, domain.entityBodyCoupling.addTo(domain.granularCoupling.copySolidSvq()));
		if (candidate != null) publish(domain, candidate, () -> { });
	}
	static void publish(FluidDomain domain, Prepared candidate, Runnable solidPublication) {
		domain.cellMacStage.publishTransport(candidate.energy,candidate.momentum,candidate.gasTopology,null,solidPublication);
	}

	static Prepared prepare(FluidDomain domain, long[] previous, long[] next) { return prepare(domain, previous, next, false); }
	static Prepared prepareInsertion(FluidDomain domain, long[] previous, long[] next) { return prepare(domain, previous, next, true); }
	static Prepared prepareExternal(FluidDomain domain, long[] previous, long[] next) { return prepare(domain, previous, next, true, true); }
    private static Prepared prepare(FluidDomain domain, long[] previous, long[] next, boolean insertion) { return prepare(domain, previous, next, insertion, false); }
    private static Prepared prepare(FluidDomain domain, long[] previous, long[] next, boolean insertion, boolean external) {
		if (previous == null || next == null || previous.length != domain.cellCount || next.length != domain.cellCount)
			throw new IllegalArgumentException("Solid transition requires complete source and future occupancy");
		if (Arrays.equals(previous, next)) return null;
		var flux = new ConservativeLiquidFlux(domain);
		long totalBefore = 0, totalAfter = 0;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			if (previous[cell] < 0 || next[cell] < 0) throw new IllegalArgumentException("Solid occupation cannot be negative");
			totalBefore = Math.addExact(totalBefore, previous[cell]); totalAfter = Math.addExact(totalAfter, next[cell]);
			var open = SignedDyadic.of(domain.geometry.cutCells.openCellVolume(cell));
			var old = open.subtract(solidVolume(previous[cell]));
			if (domain.liquidCells.exactCellVolume(cell).compareTo(old) > 0 || open.compareTo(solidVolume(next[cell])) < 0)
				throw new IllegalStateException("Solid transition requires valid source capacity at cell " + cell);
		}
		if (!external && (insertion ? totalAfter < totalBefore : totalBefore != totalAfter))
			throw new IllegalStateException("Solid displacement changed skeleton quantity outside its operation");
		try { flux.prepareDisplacement(next,domain.liquidCells); }
		catch (MechanicalTimeRefinement | ConservativeLiquidFlux.CapacityUnavailable unavailable) { throw new CapacityUnavailable(unavailable); }
		var transport = domain.cellMacStage.prepareLiquid(flux);
		var momentum=domain.cellMacStage.prepareMomentumTransport(transport);
		var inventory=transport.inventory().withMechanicalHeat(momentum.heatByCell());
		var gas = domain.gasTopologyCoordinator.planAfterLiquidDisplacement(inventory.future(), next, transport.gasAllocation());
		var energy=insertion?domain.mechanicalEnergy.new Plan(inventory,java.util.Map.of()):domain.mechanicalEnergy.prepareDisplacement(inventory,gas);
		return new Prepared(energy.inventory(), gas, momentum, energy);
	}
	private static SignedDyadic solidVolume(long svq) { return SignedDyadic.of(svq, -24); }
}
