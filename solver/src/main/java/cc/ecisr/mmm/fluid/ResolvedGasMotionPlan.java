package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasGeometryState;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.thermal.GasEnergyStore;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Complete gas/energy successor for material chamber identities preserved by liquid motion. */
final class ResolvedGasMotionPlan {
	private final GasGeometryState previous, geometry;
	private final LiquidGeometryState nextLiquid;
	private final AirNetwork network;
	private final AirNetwork.RemapPlan inventory;
	private final GasEnergyStore.GasRemapPlan energy;
	private final ThermalDomainState thermal;

	ResolvedGasMotionPlan(FluidDomain domain, LiquidGeometryPhaseInput next, ResolvedGasInterfaceMotion interfaces, int maximumPairs) {
		previous = domain.gasState.geometry; network = domain.gasState.network; nextLiquid = next.liquidGeometry; thermal = domain.thermalState;
		if (previous == null || interfaces == null || interfaces.previous != previous || previous.sealedExterior() != next.sealedExterior
				|| previous.rootWidthMetres() != next.rootWidthMetres)
			throw new IllegalArgumentException("Incomplete gas motion input");
		var derived = new ResolvedGasGeometry(next); geometry = derived.state;
		var contacts = interfaces.contacts(next, derived.phaseNodes, geometry.nodeCount(), maximumPairs);
		int[] parents = new int[geometry.nodeCount() + 1]; Arrays.fill(parents, -1); parents[0] = 0;
		if (contacts.getFirst().stream().anyMatch(node -> node > 0))
			throw new GasTopologyBlockedException("Gas venting requires complete material volume lineage");
		var oldVolumes = ResolvedGasGeometry.volumes(previous); ResolvedGasOverlap unchanged = null;
		var used = new boolean[previous.nodeCount() + 1];
		for (int node = 1; node < parents.length; node++) {
			var sources = contacts.get(node);
			if (sources.isEmpty()) {
				if (unchanged == null) unchanged = new ResolvedGasOverlap(previous, geometry, maximumPairs);
				for (int old = 1; old < oldVolumes.length; old++) {
					var overlap = unchanged.destinations(old).get(node);
					if (overlap != null && overlap.equals(oldVolumes[old]) && overlap.equals(derived.volumes[node])) {
						if (parents[node] >= 0) throw new IllegalStateException("Static gas support has multiple identities");
						parents[node] = old;
					}
				}
			} else if (sources.size() == 1) parents[node] = sources.iterator().next();
			int old = parents[node];
			if (old < 1 || old > previous.nodeCount() || used[old])
				throw new GasTopologyBlockedException("Gas chamber change requires complete material volume lineage");
			used[old] = true;
		}
		for (int old = 1; old < used.length; old++) if (!used[old])
			throw new GasTopologyBlockedException("Gas inventory lacks a preserved material chamber");
		var remap = remap(domain, geometry, parents); inventory = remap.inventory; energy = remap.energy;
	}

	/** A spatial reconstruction preserves each chamber volume and transfers only its canonical ordinal. */
	ResolvedGasMotionPlan(FluidDomain domain, LiquidGeometryReconstructionPlan reconstruction, int maximumRegions, int maximumPairs) {
		previous = domain.gasState.geometry; network = domain.gasState.network; nextLiquid = reconstruction.geometry; thermal = domain.thermalState;
		geometry = reconstruction.gasGeometry;
		if (previous == null || reconstruction.previousGasGeometry != previous || reconstruction.input != domain.liquidGeometryState || geometry == null)
			throw new IllegalArgumentException("Incomplete joint phase reconstruction input");
		var partition = new LiquidGeometryDomainPartition(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.geometry.cutCells, reconstruction.leaves, maximumRegions);
		var next = new LiquidGeometryPhaseInput(nextLiquid, partition, geometry, maximumRegions);
		ResolvedGasGeometry.match(geometry, next, maximumPairs);
		var oldVolume = ResolvedGasGeometry.volumes(previous); var nextVolume = ResolvedGasGeometry.volumes(geometry);
		for (int node = 0; node < nextVolume.length; node++) if (!nextVolume[node].equals(oldVolume[reconstruction.previousGasNodes[node]]))
			throw new IllegalArgumentException("Spatial reconstruction changes material gas volume");
		var remap = remap(domain, geometry, reconstruction.previousGasNodes); inventory = remap.inventory; energy = remap.energy;
	}
	private record Remap(AirNetwork.RemapPlan inventory, GasEnergyStore.GasRemapPlan energy) { }
	private static Remap remap(FluidDomain domain, GasGeometryState geometry, int[] parents) {
		var network = domain.gasState.network; var thermal = domain.thermalState;
		var available = ResolvedGasStateLifecycle.available(geometry); var inherited = new double[available.length];
		var inventories = new ArrayList<GasInventory>();
		boolean hasEnergy = thermal != null && thermal.gasModelBound();
		var energies = hasEnergy ? new double[available.length] : null;
		for (int node = 1; node < parents.length; node++) {
			int old = parents[node]; inventories.add(network.inventory(old)); inherited[node - 1] = network.availableVolume(old);
			if (hasEnergy) energies[node - 1] = thermal.gasNodeEnergy(old, network);
		}
		var inventory = network.planRemapNodes(available, inventories, GasInventory.EMPTY, GasInventory.EMPTY, List.of());
		var energy = hasEnergy ? thermal.planGasRemap(network, inventories, available, energies, inherited, 0, 0) : null;
		return new Remap(inventory, energy);
	}

	/** Both owners preflight before the liquid writes; liquid publication preserves their revisions. */
	void publish(FluidDomain domain, LiquidGeometryMotionPlan motion) {
		requirePublication(domain, motion.geometry);
		motion.publish(domain); commit(domain);
	}
	void publish(FluidDomain domain, LiquidGeometryReconstructionPlan reconstruction) {
		requirePublication(domain, reconstruction.geometry);
		if (reconstruction.inventory == null) LiquidGeometryPublication.publish(domain, reconstruction.input, reconstruction.geometry, reconstruction.inventoryObservations);
		else domain.carrierMutations().publishGeometricRepartition(reconstruction.inventory);
		commit(domain);
	}
	private void requirePublication(FluidDomain domain, LiquidGeometryState liquid) {
		if (domain.gasState.geometry != previous || domain.gasState.network != network || domain.thermalState != thermal || liquid != nextLiquid)
			throw new IllegalStateException("Resolved gas motion input changed after planning");
		network.requireCanCommitRemap(inventory);
		if (energy != null) thermal.requireCanCommitGasRemap(energy);
	}
	private void commit(FluidDomain domain) {
		if (energy != null) thermal.commitGasRemap(energy);
		network.commitRemapNodes(inventory); ResolvedGasStateLifecycle.publish(domain, geometry);
	}
}
