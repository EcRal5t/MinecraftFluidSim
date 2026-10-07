package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasGeometryState;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.thermal.GasEnergyStore;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Conservative gas successor when an external liquid removal adds vacuum space. */
final class ResolvedGasExpansionPlan {
	private final LiquidGeometryState input, liquid;
	private final GasGeometryState previous, geometry;
	private final AirNetwork network;
	private final ThermalDomainState thermal;
	private final byte[] collision;
	private final AirNetwork.RemapPlan inventory;
	final GasEnergyStore.GasRemapPlan energy;

	ResolvedGasExpansionPlan(FluidDomain domain, LiquidGeometryState nextLiquid) {
		input = domain.liquidGeometryState; liquid = nextLiquid;
		previous = domain.gasState.geometry; network = domain.gasState.network; thermal = domain.thermalState;
		collision = domain.geometry.cutCells.copyMicroMasks();
		if (input == null || liquid == null || previous == null) throw new IllegalArgumentException("Incomplete gas expansion input");
		ResolvedGasStateLifecycle.requireCompatible(domain);
		int maximum = LiquidGeometryInsertionPlan.MAXIMUM_SOURCE_REGIONS;
		var requested = new ArrayList<>(LiquidGeometryStateMapper.restore(liquid));
		requested.addAll(LiquidGeometryStateMapper.restoreCells(previous.cells()));
		var partition = new LiquidGeometryDomainPartition(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(),
				domain.geometry.cutCells, requested, maximum);
		geometry = new ResolvedGasGeometry(new LiquidGeometryPhaseInput(liquid, partition, previous.sealedExterior(), maximum)).state;
		var overlap = new ResolvedGasOverlap(previous, geometry, 4_194_304);
		var volumes = ResolvedGasGeometry.volumes(previous);
		var capacities = ResolvedGasStateLifecycle.available(geometry);
		var quantities = new ArrayList<GasInventory>();
		for (int n = 0; n < capacities.length; n++) quantities.add(GasInventory.EMPTY);
		boolean hasEnergy = thermal != null && thermal.gasModelBound();
		var energies = new SignedDyadic[capacities.length]; Arrays.fill(energies, SignedDyadic.ZERO);
		var sink = GasInventory.EMPTY; var sinkEnergy = SignedDyadic.ZERO;
		for (int old = 0; old < volumes.length; old++) {
			if (volumes[old].signum() == 0) continue;
			var destinations = overlap.destinations(old);
			if (destinations.size() != 1 || !destinations.firstEntry().getValue().equals(volumes[old]))
				throw new GasTopologyBlockedException("Liquid removal must preserve complete previous gas support");
			int node = destinations.firstKey();
			if (old == 0) {
				if (node != 0) throw new GasTopologyBlockedException("Gas expansion disconnected ambient support");
				continue;
			}
			var quantity = network.inventory(old);
			var u = hasEnergy ? SignedDyadic.of(thermal.gasNodeEnergy(old, network)) : SignedDyadic.ZERO;
			if (node == 0) { sink = sink.merge(quantity); sinkEnergy = sinkEnergy.add(u); }
			else { quantities.set(node - 1, quantities.get(node - 1).merge(quantity)); energies[node - 1] = energies[node - 1].add(u); }
		}
		// All added capacity is newly vacated space. Free expansion and chamber mixing preserve U.
		var mixed = hasEnergy ? Arrays.stream(energies).mapToDouble(SignedDyadic::toDouble).toArray() : null;
		energy = hasEnergy ? thermal.planGasRemap(network, quantities, capacities, mixed, capacities, 0, sinkEnergy.toDouble()) : null;
		inventory = network.planRemapNodes(capacities, quantities, GasInventory.EMPTY, sink, List.of());
		requireSource(domain);
	}
	void requireSource(FluidDomain domain) {
		if (domain.liquidGeometryState != input || !Arrays.equals(collision, domain.geometry.cutCells.copyMicroMasks()))
			throw new IllegalStateException("Liquid or collision geometry changed after gas expansion planning");
		requireGas(domain);
	}
	private void requireGas(FluidDomain domain) {
		if (domain.gasState.geometry != previous || domain.gasState.network != network || domain.thermalState != thermal)
			throw new IllegalStateException("Gas expansion owner changed after planning");
		network.requireCanCommitRemap(inventory);
		if (energy != null) thermal.requireCanCommitGasRemap(energy);
	}
	void publish(FluidDomain domain) {
		requireGas(domain);
		if (domain.particles.count() != liquid.markerCount()) throw new IllegalStateException("Gas expansion has a different liquid inventory");
		domain.liquidGeometryState = liquid;
		if (energy != null) thermal.commitGasRemap(energy);
		network.commitRemapNodes(inventory); ResolvedGasStateLifecycle.publish(domain, geometry);
	}
}
