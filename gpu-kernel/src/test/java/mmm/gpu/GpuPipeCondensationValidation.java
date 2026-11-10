package mmm.gpu;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.fluid.*;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.fluid.air.GasInventory;
import mmm.thermal.*;
import mmm.units.Mass;

/** Cools vapor inside the resolved pipe bore and checks the production phase transition. */
public final class GpuPipeCondensationValidation {
    private GpuPipeCondensationValidation() { }
    public static void main(String[] args) {
        int device = Integer.parseInt(args[0]);
        var defs = TestFluidDefinitions.load(); var thermal = defs.thermal();
        var fluids = new FluidMaterialRegistry(List.of(defs.material().entry("mmm:water").material()));
        var domain = new HostFluidState(3, 3, 3, new FluidSolverConfig(.5f, 0, .5f, 200, 1e-5f, .002f), defs);
        domain.bindMaterialRegistry(fluids); domain.bindMaterialDefinitions(defs.material()); domain.sealDomainBoundary();
        for (int z=0; z<3; z++) for (int y=0; y<3; y++) for (int x=0; x<3; x++) domain.setSolid(x,y,z,true);
        // The open two quarter-block boxes are one corner of PipeGeometry's half-block bore.
        domain.setSolidMicroMask(1,1,1,0x3f);
        domain.bindChemistry(defs.chemistry()); domain.bindGasRegistry(defs.gas());
        domain.bindThermal(thermal, fluids, 350);
        int cell = domain.grid().cell(1,1,1);
        domain.editGas(cell+1, BuiltinGasDefinitions.WATER_VAPOR, 1_000_000_000L, 350, false);

        var wallDefinition = thermal.finiteDevice("mmm:ordinary_metal_wall");
        int material = thermal.materialHandle(wallDefinition.thermalMaterialId());
        var wallMaterial = thermal.material(material); long wallMass = Mass.fromKilograms(wallDefinition.bodyMassKilograms()*.75);
        double wallEnergy = new ThermalStateFunction(thermal).internalEnergyAtTemperature(material, Mass.kilograms(wallMass), Map.of(), 280);
        var wall = new FiniteThermalDeviceState("test:pipe", "test:wall", wallDefinition.id(),
                new FiniteThermalBodyState(0, thermal.fingerprint(), wallDefinition.thermalMaterialId(), wallMass, wallEnergy));
        var devices = new FiniteThermalDeviceInventory(thermal.fingerprint(), List.of(wall));
        var contact = new FluidDomainContracts.ThermalPortRequest(1,1,1,2,.25,wallMaterial.conductivityWPerMeterK(),.125,0xc0,true);
        var port = new FluidStateAccess.DevicePort(wall.providerId(), wall.objectId(), contact);
        var key = new EnvironmentThermalState.Key(1,1,1,EnvironmentThermalState.Kind.SOLID,0);
        var node = new EnvironmentThermalState.Node(key, wallDefinition.thermalMaterialId(), wallMass,.75,GasInventory.EMPTY,wallEnergy,List.of());
        var world = EnvironmentThermalState.initialized("test:pipe_condensation",thermal.fingerprint(),List.of(node),List.of());
        var binding = new EnvironmentBinding(world.dimensionId(),List.of(new EnvironmentBinding.DeviceReference(wall.providerId(),wall.objectId(),key)));
        try (var backend = new VulkanFluidSolverBackend(device)) {
            backend.configureEnvironment(world,defs);
            try (var imported = backend.prepareState(null,domain.captureAuthoritativeState(),devices,List.of(port),binding)) {
                FluidStateAccess current = imported.commit();
                try {
                    var before = current.exportState();
                    long beforeMass = mass(before); var beforeSpecies = species(before);
                    long beforeLiquid = liquidWater(before);
                    if (!before.liquid().stocks().isEmpty()) throw new AssertionError("Pipe must start without liquid");
                    double beforeGasTemperature = new GasThermalModel(thermal,defs.gas()).state(before.spatialGas().cells().get(cell),before.spatialGas().energyAt(cell)).temperatureK();
                    boolean cooled = false, condensed = false;
                    double cooledTemperature = beforeGasTemperature; long liquidGain = 0;
                    for (int step=0; step<80; step++) {
                        try (var candidate = backend.evaluateThermal(new FluidThermalStepRequest(step,current,cooled,
                                cooled ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0));
                             var prepared = candidate.claimPreparedDeviceState()) {
                            var next = prepared.commit(); current.close(); current = next;
                        }
                        var state = current.exportState();
                        double temperature = new GasThermalModel(thermal,defs.gas()).state(state.spatialGas().cells().get(cell),state.spatialGas().energyAt(cell)).temperatureK();
                        if (!cooled && temperature < beforeGasTemperature - 1e-5) { cooledTemperature = temperature; cooled = true; }
                        liquidGain = liquidWater(state) - beforeLiquid;
                        condensed |= liquidGain > 0;
                        if (mass(state)!=beforeMass || !species(state).equals(beforeSpecies)
                                || !((ResidentFluidState)current).observation().thermalAuditClosed())
                            throw new AssertionError("Pipe condensation changed mass, species or thermal audit");
                        if (cooled && condensed) break;
                    }
                    var after = backend.captureEnvironment(world.dimensionId());
                    if (!cooled || !condensed || after.nodes().getFirst().internalEnergyJ() <= wallEnergy
                            || !after.exactEnergy().equals(after.audit().expectedEnergy()))
                        throw new AssertionError("Pipe cooling/condensation or world heat audit failed: cooled="+cooled+" condensed="+condensed);
                    System.out.printf("GPU pipe condensation: gas %.9f -> %.9f K, liquid water +%d quanta, wall +%.6f J; mass/species/energy closed%n",
                            beforeGasTemperature, cooledTemperature, liquidGain, after.nodes().getFirst().internalEnergyJ()-wallEnergy);
                } finally { current.close(); }
            }
        }
    }
    private static long mass(FluidDomainState state) {
        long total=0;
        for (var stock:state.liquid().stocks()) total=Math.addExact(total,stock.massUnits());
        for (var gas:state.spatialGas().cells()) total=Math.addExact(total,gas.totalMassUnits());
        return total;
    }
    private static long liquidWater(FluidDomainState state) {
        long total=0;
        for (var stock:state.liquid().stocks()) if (stock.mixture()!=null)
            total=Math.addExact(total,stock.mixture().stableAmounts(state.chemicalRegistry()).getOrDefault(BuiltinChemicalDefinitions.H2O,0L));
        return total;
    }
    private static Map<String,Long> species(FluidDomainState state) {
        var result = new TreeMap<String,Long>();
        for (var stock:state.liquid().stocks()) if (stock.mixture()!=null)
            stock.mixture().stableAmounts(state.chemicalRegistry()).forEach((id,q)->result.merge(id,q,Math::addExact));
        for (var gas:state.spatialGas().cells()) for (var component:gas.components()) for (var amount:component.species())
            result.merge(amount.speciesId(),amount.amountQuanta(),Math::addExact);
        result.values().removeIf(q->q==0); return result;
    }
}
