package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.granular.SolidVolume;
import mmm.granular.GranularLimits;
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
		final CellLiquidVolumeTransport.Prepared transport;
		final LiquidCellInventory.Plan inventory;
		final GasTopologyCoordinator.GasTopologyPlan gasTopology;
		final SignedDyadic energyRoundoff;
		final MacMomentumState momentum;
		Prepared(CellLiquidVolumeTransport.Prepared transport, GasTopologyCoordinator.GasTopologyPlan gasTopology, MacMomentumState momentum) {
			this.transport = transport; inventory = transport.inventory(); this.gasTopology = gasTopology;
			energyRoundoff = inventory.energyRoundoff();
			this.momentum = momentum;
		}
	}
	static long[] begin(FluidDomain domain) {
		if (domain.liquidCells.size() == 0 || !domain.hasGranularInventory()) return null;
		domain.ensureInventoryGrid();
		domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		return domain.granularCoupling.copySolidSvq();
	}
	static FluidDomain.LiquidTransportStats commit(FluidDomain domain, long[] previous) {
		if (previous == null) return FluidDomain.LiquidTransportStats.INACTIVE;
		var candidate = prepare(domain, previous, domain.granularCoupling.copySolidSvq());
		if (candidate != null) publish(domain, candidate, () -> { });
		return FluidDomain.LiquidTransportStats.INACTIVE;
	}
	static void publish(FluidDomain domain, Prepared candidate, Runnable solidPublication) {
		candidate.inventory.requireCurrent();
		if (candidate.gasTopology != null) domain.gasTopologyCoordinator.requireBeforeCarrierCommit(candidate.gasTopology);
		if (domain.thermalState != null) {
			var audit = domain.thermalState.auditLedger().withRoundoff(candidate.energyRoundoff);
			var gas = candidate.gasTopology == null ? null : candidate.gasTopology.thermalPlan();
			if (gas != null) audit = audit.withExternalSourceEnergy(gas.sourceEnergy()).withExternalSinkEnergy(gas.sinkEnergy())
					.withMechanicalWork(gas.mechanicalWork()).withRoundoff(gas.roundoff());
			audit.expectedInventory();
		}
		candidate.inventory.commit();
		domain.mac.restoreMomentum(candidate.momentum);
		solidPublication.run();
		if (domain.thermalState != null) domain.thermalState.recordRoundoff(candidate.energyRoundoff);
		domain.markCellInventoryChanged();
		if (candidate.gasTopology != null) domain.gasTopologyCoordinator.commit(candidate.gasTopology);
	}
	static Prepared prepare(FluidDomain domain, long[] previous, long[] next) { return prepare(domain, previous, next, false); }
	static Prepared prepareInsertion(FluidDomain domain, long[] previous, long[] next) { return prepare(domain, previous, next, true); }
	private static Prepared prepare(FluidDomain domain, long[] previous, long[] next, boolean insertion) {
		if (previous == null || next == null || previous.length != domain.cellCount || next.length != domain.cellCount)
			throw new IllegalArgumentException("Solid transition requires complete source and future occupancy");
		if (Arrays.equals(previous, next)) return null;
		var flux = new ConservativeLiquidFlux(domain.sizeX, domain.sizeY, domain.sizeZ, true);
		var volume = new SignedDyadic[domain.cellCount];
		var capacity = new SignedDyadic[domain.cellCount]; var displaced = new SignedDyadic[domain.cellCount];
		long totalBefore = 0, totalAfter = 0; boolean componentOverfull=false;
		for (int cell = 0; cell < volume.length; cell++) {
			volume[cell] = domain.liquidCells.exactCellVolume(cell);
			if (previous[cell] < 0 || next[cell] < 0) throw new IllegalArgumentException("Solid occupation cannot be negative");
			totalBefore = Math.addExact(totalBefore, previous[cell]); totalAfter = Math.addExact(totalAfter, next[cell]);
			var open = SignedDyadic.of(domain.geometry.cutCells.openCellVolume(cell));
			var old = open.subtract(solidVolume(previous[cell])); capacity[cell] = open.subtract(solidVolume(next[cell]));
			if (volume[cell].compareTo(old) > 0 || capacity[cell].signum() < 0)
				throw new IllegalStateException("Solid transition requires valid source capacity at cell " + cell);
			componentOverfull |= CellRemapMicroTopology.exceedsCapacity(domain.liquidCells,cell,domain.geometry.cutCells.mask(cell),capacity[cell]);
			if (insertion) displaced[cell] = volume[cell].compareTo(capacity[cell]) > 0 ? volume[cell].subtract(capacity[cell]) : SignedDyadic.ZERO;
			else if (next[cell] > previous[cell] && volume[cell].signum() > 0) {
				displaced[cell] = SignedDyadic.of(solidVolume(next[cell] - previous[cell]).toDouble() * (volume[cell].toDouble() / old.toDouble()));
				if (displaced[cell].compareTo(volume[cell]) > 0) displaced[cell] = volume[cell];
			} else displaced[cell] = SignedDyadic.ZERO;
		}
		if (insertion ? totalAfter < totalBefore : totalBefore != totalAfter)
			throw new IllegalStateException("Solid displacement changed skeleton quantity outside its operation");
		boolean changed = componentOverfull; for (var amount : displaced) changed |= amount.signum() > 0;
		if (!changed) return null;
		try { flux.planDisplacement(volume, capacity, displaced, domain.geometry.solid, domain.geometry.cutCells,
				GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP, domain.liquidCells); }
		catch (MechanicalTimeRefinement | ConservativeLiquidFlux.CapacityUnavailable unavailable) { throw new CapacityUnavailable(unavailable); }
		if (domain.cellLiquidVolumeTransport == null) domain.cellLiquidVolumeTransport = new CellLiquidVolumeTransport(domain);
		var transport = domain.cellLiquidVolumeTransport.prepareFlux(flux, 0);
		var gas = domain.gasState.frame == null ? null : domain.gasTopologyCoordinator.planAfterLiquidCells(transport.inventory().future(), next, false);
		return new Prepared(transport, gas, domain.cellMacStage.prepareMomentum(transport));
	}
	private static SignedDyadic solidVolume(long svq) { return SignedDyadic.of(svq, -24); }
}
