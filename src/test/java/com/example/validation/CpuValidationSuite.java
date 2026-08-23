package com.example.validation;

import com.example.chemistry.M7ChemistryValidation;
import com.example.fluid.AirCavityScannerValidation;
import com.example.fluid.FlipCutCellValidation;
import com.example.fluid.FlipFluidFreeSurfaceValidation;
import com.example.fluid.FlipFluidReseedingValidation;
import com.example.fluid.M3ConnectedVesselValidation;
import com.example.fluid.M4SurfaceTensionValidation;
import com.example.fluid.M5GasLiquidCouplingValidation;
import com.example.fluid.M7ChemicalTransportValidation;
import com.example.fluid.air.AirNetworkValidation;
import com.example.item.M6ContainerValidation;
import com.example.logging.CommandAuditFileValidation;
import com.example.network.FluidNetworkProtocolValidation;
import com.example.simulation.ContainerTransferStoreValidation;
import com.example.simulation.FluidCutCellShapeValidation;
import com.example.simulation.FluidRegionLifecycleValidation;
import com.example.simulation.FluidRegionManagerValidation;
import com.example.simulation.M7RegionChemistryPersistenceValidation;
import com.example.simulation.TakeoverJournalValidation;
import com.example.simulation.controller.FluidBoundaryScannerValidation;
import com.example.simulation.ownership.M6OwnershipValidation;
import com.example.simulation.persistence.FluidRegionPersistenceValidation;

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
			"audit", "air-network", "air-cavity", "region-manager", "lifecycle",
			"free-surface", "reseeding", "cut-cell", "shape", "protocol", "boundary", "containers", "chemistry");
	private static final List<String> MILESTONE_GROUP = List.of(
			"materials", "surface-tension", "air-network", "air-cavity", "gas-liquid",
			"region-manager", "lifecycle", "free-surface", "reseeding", "cut-cell", "shape",
			"region-state", "protocol", "boundary", "takeover", "containers", "ownership", "audit", "chemistry");
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
		gate(gates, "materials", () -> M3ConnectedVesselValidation.main(NONE));
		gate(gates, "surface-tension", () -> M4SurfaceTensionValidation.main(NONE));
		gate(gates, "air-network", () -> AirNetworkValidation.main(NONE));
		gate(gates, "air-cavity", () -> AirCavityScannerValidation.main(NONE));
		gate(gates, "gas-liquid", () -> M5GasLiquidCouplingValidation.main(NONE),
				() -> M5GasLiquidCouplingValidation.main(FAST));
		gate(gates, "region-manager", () -> FluidRegionManagerValidation.main(NONE));
		gate(gates, "lifecycle", () -> FluidRegionLifecycleValidation.main(NONE));
		gate(gates, "free-surface", () -> FlipFluidFreeSurfaceValidation.main(NONE),
				() -> FlipFluidFreeSurfaceValidation.main(FAST));
		gate(gates, "reseeding", () -> FlipFluidReseedingValidation.main(NONE),
				() -> FlipFluidReseedingValidation.main(FAST));
		gate(gates, "cut-cell", () -> FlipCutCellValidation.main(NONE));
		gate(gates, "shape", () -> FluidCutCellShapeValidation.main(NONE));
		gate(gates, "region-state", () -> {
			FluidRegionPersistenceValidation.main(NONE);
			M7RegionChemistryPersistenceValidation.main(NONE);
		});
		gate(gates, "protocol", () -> FluidNetworkProtocolValidation.main(NONE));
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
