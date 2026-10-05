package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasGeometryState;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Initializes and restores finite inventories against complete resolved gas support. */
final class ResolvedGasStateLifecycle {
	private static final int MAXIMUM_REGIONS = 65_536, MAXIMUM_PAIRS = 4_194_304;
	private ResolvedGasStateLifecycle() { }
	static void bootstrap(FluidDomain domain) {
		requireSolidSupport(domain, null);
		var geometry = GasGeometryStateValidation.capture(particles(domain), domain.gasState.sealedDomainBoundary, MAXIMUM_REGIONS);
		var available = available(geometry); var inventories = new ArrayList<GasInventory>(); var source = GasInventory.EMPTY;
		var thermal = domain.thermalState != null && domain.thermalState.gasModelBound();
		double[] energies = thermal ? new double[available.length] : null; double sourceEnergy = 0;
		for (int n = 0; n < available.length; n++) {
			var inventory = domain.gasState.network.ambientInventory(AirNetwork.nanos(available[n]));
			inventories.add(inventory); source = source.merge(inventory);
			if (thermal) { energies[n] = domain.thermalState.ambientGasEnergy(inventory); sourceEnergy += energies[n]; }
		}
		var energyPlan = thermal ? domain.thermalState.planGasRemap(domain.gasState.network, inventories, available, energies, available, sourceEnergy, 0) : null;
		var gasPlan = domain.gasState.network.planRemapNodes(available, inventories, source, GasInventory.EMPTY, List.of());
		domain.gasState.network.requireCanCommitRemap(gasPlan);
		if (thermal) domain.thermalState.requireCanCommitGasRemap(energyPlan);
		if (thermal) domain.thermalState.commitGasRemap(energyPlan);
		domain.gasState.network.commitRemapNodes(gasPlan); publish(domain, geometry);
	}
	static void restore(FluidDomain domain, GasStateSnapshot state, GasRegistry registry, FlipThermalParticleState thermal) {
		var geometry = state.geometry();
		if (geometry.sealedExterior() != domain.gasState.sealedDomainBoundary)
			throw new IllegalArgumentException("Resolved gas exterior differs from the domain");
		requireSolidSupport(domain, thermal);
		GasGeometryStateValidation.requireCompatible(geometry, particles(domain), MAXIMUM_REGIONS, MAXIMUM_PAIRS);
		var network = new AirNetwork(registry);
		network.restoreNodes(available(geometry), state.nodes().stream().map(GasStateSnapshot.Node::inventory).toList(),
				state.externalSource(), state.escapedSink(), state.massRoundoff(), state.residualRoundoff());
		domain.gasState.network = network; publish(domain, geometry);
	}
	static void requireCompatible(FluidDomain domain) {
		try {
			requireSolidSupport(domain, null);
			GasGeometryStateValidation.requireCompatible(domain.gasState.geometry, particles(domain), MAXIMUM_REGIONS, MAXIMUM_PAIRS);
		} catch (IllegalArgumentException | IllegalStateException invalid) {
			throw new GasTopologyBlockedException("Resolved gas support requires a complete geometric successor: " + invalid.getMessage(), invalid);
		}
	}
	private static void requireSolidSupport(FluidDomain domain, FlipThermalParticleState incoming) {
		if (domain.hasGranularInventory() || domain.thermalState != null && domain.thermalState.coherentPhases().groupCount() > 0
				|| incoming != null && !incoming.coherentGroups().isEmpty())
			throw new IllegalArgumentException("Resolved gas requires spatial support for all solid inventories");
	}
	static double[] available(GasGeometryState geometry) {
		var exact = ResolvedGasGeometry.volumes(geometry); var result = new double[geometry.nodeCount()];
		for (int node = 1; node < exact.length; node++) {
			result[node - 1] = exact[node].rounded();
			if (!(result[node - 1] > 0) || !Double.isFinite(result[node - 1])) throw new IllegalArgumentException("Resolved gas capacity exceeds binary64 representation");
		}
		return result;
	}
	private static FlipParticleState particles(FluidDomain domain) {
		return domain.materialRegistry == null ? domain.exportParticleState() : domain.exportMaterialParticleState().particles();
	}
	static void publish(FluidDomain domain, GasGeometryState geometry) {
		domain.gasState.geometry = geometry; domain.gasState.cellNodeId = new int[domain.cellCount];
		domain.gasState.cellLineageNodeId = new int[domain.cellCount]; Arrays.fill(domain.gasState.cellLineageNodeId, -1);
		domain.gasState.previousSealedNodeCount = geometry.nodeCount(); domain.gasState.pressureActive = geometry.nodeCount() > 0;
	}
}
