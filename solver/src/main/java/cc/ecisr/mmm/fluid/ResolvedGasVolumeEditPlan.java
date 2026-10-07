package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasGeometryState;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.thermal.GasEnergyStore;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Conservative gas inventory, vacuum capacity and work for an external liquid volume edit. */
final class ResolvedGasVolumeEditPlan {
	private final LiquidGeometryState input, liquid;
	private final GasGeometryState previous, geometry;
	private final AirNetwork network;
	private final ThermalDomainState thermal;
	private final byte[] collision;
	private final AirNetwork.RemapPlan inventory;
	final GasEnergyStore.GasRemapPlan energy;

	ResolvedGasVolumeEditPlan(FluidDomain domain, LiquidGeometryState nextLiquid) {
		input = domain.liquidGeometryState; liquid = nextLiquid;
		previous = domain.gasState.geometry; network = domain.gasState.network; thermal = domain.thermalState;
		collision = domain.geometry.cutCells.copyMicroMasks();
		if (input == null || liquid == null || previous == null) throw new IllegalArgumentException("Incomplete gas volume edit input");
		ResolvedGasStateLifecycle.requireCompatible(domain);
		int maximum = LiquidGeometryInsertionPlan.MAXIMUM_SOURCE_REGIONS;
		var requested = new ArrayList<>(LiquidGeometryStateMapper.restore(liquid));
		requested.addAll(LiquidGeometryStateMapper.restoreCells(previous.cells()));
		var partition = new LiquidGeometryDomainPartition(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(),
				domain.geometry.cutCells, requested, maximum);
		var derived = new ResolvedGasGeometry(new LiquidGeometryPhaseInput(liquid, partition, previous.sealedExterior(), maximum)); geometry = derived.state;
		var overlap = new ResolvedGasOverlap(previous, geometry, 4_194_304);
		var volumes = ResolvedGasGeometry.volumes(previous);
		var capacities = ResolvedGasStateLifecycle.available(derived.volumes);
		var quantities = new ArrayList<GasInventory>();
		for (int n = 0; n < capacities.length; n++) quantities.add(GasInventory.EMPTY);
		boolean hasEnergy = thermal != null && thermal.gasModelBound();
		var energies = new SignedDyadic[capacities.length]; Arrays.fill(energies, SignedDyadic.ZERO);
		var inherited = new Fraction[capacities.length]; Arrays.fill(inherited, Fraction.ZERO);
		var covered = new Fraction[geometry.nodeCount() + 1]; Arrays.fill(covered, Fraction.ZERO);
		var nextVolumes = derived.volumes;
		var source = GasInventory.EMPTY; var sink = GasInventory.EMPTY;
		var sourceEnergy = SignedDyadic.ZERO; var sinkEnergy = SignedDyadic.ZERO;
		for (int old = 0; old < volumes.length; old++) {
			var destinations = overlap.destinations(old); var retained = Fraction.ZERO;
			for (var entry : destinations.entrySet()) {
				retained = retained.add(entry.getValue()); covered[entry.getKey()] = covered[entry.getKey()].add(entry.getValue());
			}
			if (retained.compareTo(volumes[old]) > 0) throw new IllegalStateException("Gas volume edit overlaps previous support");
			if (old == 0) {
				for (var entry : destinations.entrySet()) if (entry.getKey() > 0) {
					int node = entry.getKey() - 1; var volume = entry.getValue();
					var ambient = network.ambientInventory(AirNetwork.nanos(volume.rounded()));
					quantities.set(node, quantities.get(node).merge(ambient)); source = source.merge(ambient);
					inherited[node] = inherited[node].add(volume);
					if (hasEnergy) { var u = SignedDyadic.of(thermal.ambientGasEnergy(ambient)); energies[node] = energies[node].add(u); sourceEnergy = sourceEnergy.add(u); }
				}
				continue;
			}
			var quantity = network.inventory(old);
			if (destinations.isEmpty()) {
				if (!quantity.isEmpty()) throw new GasTopologyBlockedException("Liquid volume edit leaves finite gas in zero capacity");
				continue;
			}
			var parts = quantity.partition(ResolvedGasOverlap.weights(List.copyOf(destinations.values())));
			var partEnergy = hasEnergy && !quantity.isEmpty() ? thermal.partitionGasNodeEnergy(old, parts, network) : new double[parts.size()];
			int part = 0;
			for (var entry : destinations.entrySet()) {
				int node = entry.getKey(); var u = SignedDyadic.of(partEnergy[part]);
				if (node == 0) { sink = sink.merge(parts.get(part)); sinkEnergy = sinkEnergy.add(u); }
				else {
					quantities.set(node - 1, quantities.get(node - 1).merge(parts.get(part))); energies[node - 1] = energies[node - 1].add(u);
					inherited[node - 1] = inherited[node - 1].add(volumes[old].multiply(entry.getValue()).divide(retained));
				}
				part++;
			}
		}
		for (int node = 1; node < nextVolumes.length; node++) {
			var vacuum = nextVolumes[node].subtract(covered[node]);
			if (vacuum.signum() < 0) throw new IllegalStateException("Gas volume edit overlaps successor support");
			// Newly vacated liquid space is vacuum; expansion into it contributes no reversible work.
			inherited[node - 1] = inherited[node - 1].add(vacuum);
		}
		var mixed = hasEnergy ? Arrays.stream(energies).mapToDouble(SignedDyadic::toDouble).toArray() : null;
		var inheritedVolumes = Arrays.stream(inherited).mapToDouble(Fraction::rounded).toArray();
		energy = hasEnergy ? thermal.planGasRemap(network, quantities, capacities, mixed, inheritedVolumes, sourceEnergy.toDouble(), sinkEnergy.toDouble()) : null;
		inventory = network.planRemapNodes(capacities, quantities, source, sink, List.of());
		requireSource(domain);
	}
	void requireSource(FluidDomain domain) {
		if (domain.liquidGeometryState != input || !Arrays.equals(collision, domain.geometry.cutCells.copyMicroMasks()))
			throw new IllegalStateException("Liquid or collision geometry changed after gas volume edit planning");
		requireGas(domain);
	}
	private void requireGas(FluidDomain domain) {
		if (domain.gasState.geometry != previous || domain.gasState.network != network || domain.thermalState != thermal)
			throw new IllegalStateException("Gas volume edit owner changed after planning");
		network.requireCanCommitRemap(inventory);
		if (energy != null) thermal.requireCanCommitGasRemap(energy);
	}
	void publish(FluidDomain domain) {
		requireGas(domain);
		if (domain.particles.count() != liquid.markerCount()) throw new IllegalStateException("Gas volume edit has a different liquid inventory");
		domain.liquidGeometryState = liquid;
		if (energy != null) thermal.commitGasRemap(energy);
		network.commitRemapNodes(inventory); ResolvedGasStateLifecycle.publish(domain, geometry);
	}
}
