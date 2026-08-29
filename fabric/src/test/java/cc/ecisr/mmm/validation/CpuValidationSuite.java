package cc.ecisr.mmm.validation;

import cc.ecisr.mmm.chemistry.M7ChemistryValidation;
import cc.ecisr.mmm.fluid.AirCavityScannerValidation;
import cc.ecisr.mmm.fluid.CarrierMutationValidation;
import cc.ecisr.mmm.fluid.FlipCutCellValidation;
import cc.ecisr.mmm.fluid.FlipFluidFreeSurfaceValidation;
import cc.ecisr.mmm.fluid.FlipFluidReseedingValidation;
import cc.ecisr.mmm.fluid.M3ConnectedVesselValidation;
import cc.ecisr.mmm.fluid.M4SurfaceTensionValidation;
import cc.ecisr.mmm.fluid.M5GasLiquidCouplingValidation;
import cc.ecisr.mmm.fluid.M7ChemicalTransportValidation;
import cc.ecisr.mmm.fluid.air.AirNetworkValidation;
import cc.ecisr.mmm.granular.M8GranularValidation;
import cc.ecisr.mmm.item.M6ContainerValidation;
import cc.ecisr.mmm.logging.CommandAuditFileValidation;
import cc.ecisr.mmm.network.FluidNetworkProtocolValidation;
import cc.ecisr.mmm.simulation.ContainerTransferStoreValidation;
import cc.ecisr.mmm.simulation.ShellFluidQueryValidation;
import cc.ecisr.mmm.simulation.EntityFluidWiringValidation;
import cc.ecisr.mmm.simulation.CommittedEventHubValidation;
import cc.ecisr.mmm.simulation.FluidCutCellShapeValidation;
import cc.ecisr.mmm.simulation.FluidRegionLifecycleValidation;
import cc.ecisr.mmm.simulation.FluidDomainStateParityValidation;
import cc.ecisr.mmm.simulation.FluidRegionManagerValidation;
import cc.ecisr.mmm.simulation.M7RegionChemistryPersistenceValidation;
import cc.ecisr.mmm.simulation.ProductionFluidReplayValidation;
import cc.ecisr.mmm.simulation.TakeoverJournalValidation;
import cc.ecisr.mmm.simulation.controller.FluidBoundaryScannerValidation;
import cc.ecisr.mmm.simulation.ownership.M6OwnershipValidation;
import cc.ecisr.mmm.simulation.persistence.FluidRegionPersistenceValidation;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleStoreValidation;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityStartupValidation;
import cc.ecisr.mmm.fluid.M9ThermalValidation;
import cc.ecisr.mmm.fluid.MaterialRegistryCodecValidation;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfileValidation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Single-process selector and timing boundary for every retained CPU validation gate. */
public final class CpuValidationSuite {
	private static final String[] NONE = new String[0];
	private static final String[] FAST = {"--fast"};
	private static final List<String> FAST_GROUP = List.of(
			"audit", "air-network", "air-cavity", "region-manager", "lifecycle", "entity-fluid", "production-replay",
			"free-surface", "reseeding", "cut-cell", "shape", "protocol", "boundary", "containers", "chemistry", "granular",
			"thermal");
	private static final List<String> MILESTONE_GROUP = List.of(
			"materials", "surface-tension", "air-network", "air-cavity", "gas-liquid",
			"region-manager", "lifecycle", "entity-fluid", "production-replay", "free-surface", "reseeding", "cut-cell", "shape",
			"region-state", "protocol", "boundary", "takeover", "containers", "ownership", "audit", "chemistry", "granular",
			"thermal");
	private static final List<String> SOAK_GROUP = List.of("gas-liquid", "free-surface", "reseeding");

	private CpuValidationSuite() { }

	public static void main(String[] args) throws Exception {
		Locale.setDefault(Locale.ROOT);
		Map<String, Gate> gates = gates();
		String selection = args.length == 0 ? "fast" : String.join(",", args);
		if (selection.equals("list")) {
			System.out.println("groups: fast, milestone, soak, full");
			System.out.println("selectors: " + String.join(", ", gates.keySet()));
			return;
		}

		LinkedHashMap<String, Mode> requested = new LinkedHashMap<>();
		for (String token : selection.split(",")) addSelection(token.trim(), gates, requested);
		if (requested.isEmpty()) throw new IllegalArgumentException("CPU validation selection is empty");

		long suiteStart = System.nanoTime();
		for (Map.Entry<String, Mode> request : requested.entrySet()) {
			Gate gate = gates.get(request.getKey());
			long start = System.nanoTime();
			System.out.println("\n=== CPU validation: " + request.getKey() + " [" + request.getValue().label + "] ===");
			(request.getValue() == Mode.FULL ? gate.full() : gate.fast()).run();
			System.out.printf("=== PASS %s (%.3fs) ===%n", request.getKey(), secondsSince(start));
		}
		System.out.printf("CPU validation selection '%s' passed %d gates in %.3fs%n",
				selection, requested.size(), secondsSince(suiteStart));
	}

	private static void addSelection(
			String token, Map<String, Gate> gates, LinkedHashMap<String, Mode> requested
	) {
		if (token.isEmpty()) return;
		switch (token) {
			case "fast" -> FAST_GROUP.forEach(id -> add(requested, id, Mode.FAST));
			case "milestone" -> MILESTONE_GROUP.forEach(id -> add(requested, id, Mode.FAST));
			case "soak" -> SOAK_GROUP.forEach(id -> add(requested, id, Mode.FULL));
			case "full" -> gates.keySet().forEach(id -> add(requested, id, Mode.FULL));
			default -> {
				if (!gates.containsKey(token)) {
					throw new IllegalArgumentException("Unknown CPU validation selector '" + token
							+ "'; use -PcpuValidation=list");
				}
				add(requested, token, Mode.FULL);
			}
		}
	}

	private static void add(LinkedHashMap<String, Mode> requested, String id, Mode mode) {
		requested.merge(id, mode, (existing, replacement) ->
				existing == Mode.FULL || replacement == Mode.FULL ? Mode.FULL : Mode.FAST);
	}

	private static Map<String, Gate> gates() {
		LinkedHashMap<String, Gate> gates = new LinkedHashMap<>();
		gate(gates, "chemistry", () -> {
			M7ChemistryValidation.main(NONE);
			M7ChemicalTransportValidation.main(NONE);
		}, () -> {
			M7ChemistryValidation.main(FAST);
			M7ChemicalTransportValidation.main(FAST);
		});
		gate(gates, "granular", () -> M8GranularValidation.main(NONE),
				() -> M8GranularValidation.main(FAST));
		gate(gates, "thermal", () -> M9ThermalValidation.main(NONE));
		gate(gates, "materials", () -> M3ConnectedVesselValidation.main(NONE));
		gate(gates, "surface-tension", () -> M4SurfaceTensionValidation.main(NONE));
		gate(gates, "air-network", () -> AirNetworkValidation.main(NONE));
		gate(gates, "air-cavity", () -> AirCavityScannerValidation.main(NONE));
		gate(gates, "gas-liquid", () -> M5GasLiquidCouplingValidation.main(NONE),
				() -> M5GasLiquidCouplingValidation.main(FAST));
		gate(gates, "region-manager", () -> FluidRegionManagerValidation.main(NONE));
		gate(gates, "lifecycle", () -> {
		FluidRegionLifecycleValidation.main(NONE);
		FluidDomainStateParityValidation.main(NONE);
	});
		gate(gates, "entity-fluid", () -> {
			EntityFluidWiringValidation.main(NONE);
			ProductionFluidReplayValidation.dynamicPlayerFloatRemainsBoundedAcrossBackend();
			ProductionFluidReplayValidation.walkingBodyDisturbsThenSettlesAcrossBackend();
		});
		gate(gates, "controller-gas-lineage", () -> {
			ProductionFluidReplayValidation.controllerEmptyThenBoundaryPourPreservesGasAcrossBackend();
			ProductionFluidReplayValidation.controllerPoursKeepSingleSealedGasOwner();
		});
		gate(gates, "production-live-budget", ProductionFluidReplayValidation::defaultLiveStepsRemainBounded);
		gate(gates, "production-replay", () -> ProductionFluidReplayValidation.main(NONE));
		gate(gates, "free-surface", () -> FlipFluidFreeSurfaceValidation.main(NONE),
				() -> FlipFluidFreeSurfaceValidation.main(FAST));
		gate(gates, "reseeding", () -> {
			FlipFluidReseedingValidation.main(NONE);
			CarrierMutationValidation.main(NONE);
		}, () -> FlipFluidReseedingValidation.main(FAST));
		gate(gates, "cut-cell", () -> FlipCutCellValidation.main(NONE));
		gate(gates, "shape", () -> FluidCutCellShapeValidation.main(NONE));
		gate(gates, "region-state", () -> {
			FluidRegionPersistenceValidation.main(NONE);
			M7RegionChemistryPersistenceValidation.main(NONE);
			WorldCapabilityProfileValidation.main(NONE);
			MaterialRegistryCodecValidation.main(NONE);
			WorldCapabilityBundleStoreValidation.main(NONE);
			WorldCapabilityStartupValidation.main(NONE);
		});
		gate(gates, "protocol", () -> {
			FluidNetworkProtocolValidation.main(NONE);
			CommittedEventHubValidation.main(NONE);
			ShellFluidQueryValidation.main(NONE);
		});
		gate(gates, "boundary", () -> FluidBoundaryScannerValidation.main(NONE));
		gate(gates, "takeover", () -> TakeoverJournalValidation.main(NONE));
		gate(gates, "containers", () -> {
			M6ContainerValidation.main(NONE);
			ContainerTransferStoreValidation.main(NONE);
		});
		gate(gates, "ownership", () -> M6OwnershipValidation.main(NONE));
		gate(gates, "audit", () -> CommandAuditFileValidation.main(NONE));
		return Collections.unmodifiableMap(gates);
	}

	private static void gate(Map<String, Gate> gates, String id, CheckedAction action) {
		gate(gates, id, action, action);
	}

	private static void gate(Map<String, Gate> gates, String id, CheckedAction full, CheckedAction fast) {
		if (gates.put(id, new Gate(full, fast)) != null) throw new IllegalStateException("Duplicate CPU gate " + id);
	}

	private static double secondsSince(long start) {
		return (System.nanoTime() - start) / 1_000_000_000.0;
	}

	private enum Mode {
		FAST("short"), FULL("full");
		private final String label;
		Mode(String label) { this.label = label; }
	}

	private record Gate(CheckedAction full, CheckedAction fast) { }

	@FunctionalInterface
	private interface CheckedAction {
		void run() throws Exception;
	}
}
