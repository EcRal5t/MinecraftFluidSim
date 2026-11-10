package mmm.validation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Loader-neutral and Fabric transaction, geometry and protocol validations. */
public final class HostValidationSuite {
    private HostValidationSuite() { }
    public static void main(String[] args) throws Exception {
        Map<String, CheckedAction> checks = new LinkedHashMap<>();
        checks.put("chemistry", () -> mmm.chemistry.M7ChemistryValidation.main(new String[0]));
        checks.put("air-network", () -> mmm.fluid.air.AirNetworkValidation.main(new String[0]));
        checks.put("lifecycle", () -> mmm.simulation.FluidRegionLifecycleValidation.main(new String[0]));
        checks.put("mixture-edits", () -> mmm.fluid.MarkerVelocityTransferValidation.main(new String[0]));
        checks.put("region-manager", () -> mmm.simulation.FluidRegionManagerValidation.main(new String[0]));
        checks.put("shape", () -> mmm.simulation.FluidCutCellShapeValidation.main(new String[0]));
        checks.put("region-state", () -> {
            mmm.simulation.persistence.RegionRepositoryAdapterValidation.main(new String[0]);
            mmm.simulation.M7RegionChemistryPersistenceValidation.main(new String[0]);
            mmm.worldprofile.WorldCapabilityProfileValidation.main(new String[0]);
            mmm.fluid.MaterialRegistryCodecValidation.main(new String[0]);
            mmm.simulation.persistence.WorldCapabilityBundleStoreValidation.main(new String[0]);
            mmm.simulation.persistence.WorldCapabilityStartupValidation.main(new String[0]);
        });
        checks.put("protocol", () -> {
            mmm.network.FluidNetworkProtocolValidation.main(new String[0]);
            mmm.network.ProcessTrendHistoryValidation.main(new String[0]);
            mmm.client.ClientSurfaceValidation.main(new String[0]);
            mmm.simulation.CommittedEventHubValidation.main(new String[0]);
            mmm.simulation.ShellFluidQueryValidation.main(new String[0]);
        });
        checks.put("boundary", () -> mmm.simulation.controller.FluidBoundaryScannerValidation.main(new String[0]));
        checks.put("takeover", () -> mmm.simulation.TakeoverJournalValidation.main(new String[0]));
        checks.put("containers", () -> {
            mmm.simulation.persistence.PlayerInventorySaveValidation.main(new String[0]);
            mmm.item.M6ContainerValidation.main(new String[0]);
            mmm.application.ContainerApplicationValidation.main(new String[0]);
            mmm.simulation.ContainerTransferStoreValidation.main(new String[0]);
        });
        checks.put("ownership", () -> mmm.simulation.ownership.M6OwnershipValidation.main(new String[0]));
        checks.put("audit", () -> mmm.logging.CommandAuditFileValidation.main(new String[0]));
        String selection = args.length == 0 ? "full" : String.join(",", args);
        if (selection.equals("list")) { System.out.println(String.join(", ", checks.keySet())); return; }
        for (String name : selection.equals("full") || selection.equals("fast") ? checks.keySet() : java.util.List.of(selection.split(","))) {
            CheckedAction check = checks.get(name.trim());
            if (check == null) throw new IllegalArgumentException("Unknown host validation: " + name);
            System.out.println("=== Host validation: " + name + " ===");
            check.run();
        }
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
