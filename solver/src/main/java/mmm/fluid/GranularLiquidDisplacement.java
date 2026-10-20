package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.granular.SolidVolume;
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
		final CellMacStage.PreparedLiquid transport;
		final LiquidCellInventory.Plan inventory;
		final GasTopologyCoordinator.GasTopologyPlan gasTopology;
		final SignedDyadic energyRoundoff;
		final CellMomentumTransport.Prepared momentum;
		Prepared(CellMacStage.PreparedLiquid transport, LiquidCellInventory.Plan inventory, GasTopologyCoordinator.GasTopologyPlan gasTopology, CellMomentumTransport.Prepared momentum) {
			this.transport = transport; this.inventory = inventory; this.gasTopology = gasTopology;
			energyRoundoff = inventory.energyRoundoff();
			this.momentum = momentum;
		}
	}
	static long[] begin(FluidDomain domain) {
		if (!domain.domainPhaseInventory.hasGranularInventory()) return null;
		domain.domainFlowFields.ensureInventoryGrid();
		domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		return domain.granularCoupling.copySolidSvq();
	}
	static void commit(FluidDomain domain, long[] previous) {
		if (previous == null) return;
		var candidate = prepare(domain, previous, domain.granularCoupling.copySolidSvq());
		if (candidate != null) publish(domain, candidate, () -> { });
	}
	static void publish(FluidDomain domain, Prepared candidate, Runnable solidPublication) {
		candidate.inventory.requireCurrent();
		if (candidate.gasTopology != null) domain.gasTopologyCoordinator.requireBeforeCarrierCommit(candidate.gasTopology);
		var audit = domain.thermalState == null ? null : domain.thermalState.auditLedger()
				.withMechanicalWork(candidate.inventory.mechanicalHeat()).withRoundoff(candidate.energyRoundoff);
		if (audit != null) {
			var gas = candidate.gasTopology == null ? null : candidate.gasTopology.thermalPlan();
			if (gas != null) audit = audit.withExternalSourceEnergy(gas.sourceEnergy()).withExternalSinkEnergy(gas.sinkEnergy())
					.withMechanicalWork(gas.mechanicalWork()).withRoundoff(gas.roundoff());
			audit.expectedInventory();
		}
		candidate.inventory.commit();
		domain.cellMacStage.commitMomentum(candidate.momentum);
		solidPublication.run();
		domain.markCellInventoryChanged();
		if (candidate.gasTopology != null) domain.gasTopologyCoordinator.commit(candidate.gasTopology);
		// Solid import/export and the gas remap publish their own energy transfers.
		// Append only this liquid transport's terms to that current shared ledger.
		if (audit != null) domain.thermalState.restoreAuditLedger(domain.thermalState.auditLedger()
				.withMechanicalWork(candidate.inventory.mechanicalHeat()).withRoundoff(candidate.energyRoundoff));
	}
	static Prepared prepare(FluidDomain domain, long[] previous, long[] next) { return prepare(domain, previous, next, false); }
	static Prepared prepareInsertion(FluidDomain domain, long[] previous, long[] next) { return prepare(domain, previous, next, true); }
	private static Prepared prepare(FluidDomain domain, long[] previous, long[] next, boolean insertion) {
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
		if (insertion ? totalAfter < totalBefore : totalBefore != totalAfter)
			throw new IllegalStateException("Solid displacement changed skeleton quantity outside its operation");
		try { flux.prepareDisplacement(next,domain.liquidCells); }
		catch (MechanicalTimeRefinement | ConservativeLiquidFlux.CapacityUnavailable unavailable) { throw new CapacityUnavailable(unavailable); }
		var transport = domain.cellMacStage.prepareLiquid(flux);
		var momentum=domain.cellMacStage.prepareMomentumTransport(transport);
		var inventory=transport.inventory().withMechanicalHeat(momentum.heatByCell());
		var gas = domain.gasTopologyCoordinator.planAfterLiquidCells(inventory.future(), next, transport.gasAllocation());
		return new Prepared(transport, inventory, gas, momentum);
	}
	private static SignedDyadic solidVolume(long svq) { return SignedDyadic.of(svq, -24); }
}
