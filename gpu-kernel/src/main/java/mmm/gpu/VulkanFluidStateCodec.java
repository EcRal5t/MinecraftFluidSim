package mmm.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mmm.fluid.FluidDomainState;
import mmm.fluid.FluidMaterial;
import mmm.fluid.FluidMaterialRegistry;
import mmm.inventory.DeviceLiquidInventory;
import mmm.thermal.FiniteThermalDeviceInventory;
import mmm.thermal.ThermalAuditLedger;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalOwnerSnapshot;

/** One-time host import and explicit checkpoint export. Normal requests retain device captures. */
final class VulkanFluidStateCodec {
    private final FluidDomainState template;
    private final FiniteThermalDeviceInventory devices;
    private final List<VulkanThermalDeviceLayout.Port> ports;
    private final FluidMaterialRegistry fluids;
    private final VulkanReactionDefinitions.Tables reactionDefinitions;
    private final double[] boundaryTemperatures;
    private mmm.units.SignedDyadic gasInitializationSource=mmm.units.SignedDyadic.ZERO,gasInitializationRoundoff=mmm.units.SignedDyadic.ZERO;
    ThermalAuditLedger initialGasAudit(ThermalAuditLedger audit){return audit.withExternalSourceEnergy(gasInitializationSource).withRoundoff(gasInitializationRoundoff);}
    VulkanFluidStateCodec(FluidDomainState template) {
        this(template, null, List.of());
    }
    VulkanFluidStateCodec(
        FluidDomainState template, FiniteThermalDeviceInventory devices, List<VulkanThermalDeviceLayout.Port> ports) {
        java.util.Objects.requireNonNull(template);
        if (template.chemicalRegistry() != null && template.thermalRegistry() == null)
            throw new IllegalArgumentException("Vulkan chemistry requires thermal state for reactions");
        if (!template.hasGas()) {
            var host = mmm.fluid.HostFluidState.restoreAuthoritativeState(template);
            template = host.captureAuthoritativeState();
        }
        this.template = template;
        this.boundaryTemperatures = template.copyThermalBoundaryTemperaturesK();
        this.devices = devices;
        this.ports = List.copyOf(ports);
        var palette = template.materialPalette().isEmpty()
            ? List.of(new FluidMaterial("mmm:water", template.config().density(), "mmm:water", 0, Math.PI / 2))
            : template.materialPalette();
        var groups = new FluidMaterialRegistry(palette);
        var pairs = new ArrayList<FluidMaterialRegistry.LiquidPairTension>();
        if (template.materialDefinitions() != null)
            for (int a = 0; a < groups.phaseGroups().size(); a++)
                for (int b = a + 1; b < groups.phaseGroups().size(); b++) {
                    double tension = template.materialDefinitions().liquidPairTension(
                        groups.phaseGroups().get(a), groups.phaseGroups().get(b));
                    if (tension > 0)
                        pairs.add(new FluidMaterialRegistry.LiquidPairTension(a, b, tension));
                }
        fluids = new FluidMaterialRegistry(palette, pairs);
        if (template.thermalRegistry() != null && template.chemicalRegistry() != null) {
            var granular = template.granularRegistry();
            if (granular == null) {
                var builtin = mmm.granular.BuiltinGranularDefinitions.createRegistry();
                granular = new mmm.granular.GranularRegistry(builtin.materials(), builtin.carriers());
            }
            reactionDefinitions = VulkanReactionDefinitions.fromRegistries(
                template.chemicalRegistry(), granular, template.thermalRegistry(), template.gasRegistry());
        } else
            reactionDefinitions = null;
    }
    private VulkanFluidStateCodec(VulkanFluidStateCodec source, double[] temperatures) {
        template = source.template;
        devices = source.devices;
        ports = source.ports;
        fluids = source.fluids;
        reactionDefinitions = source.reactionDefinitions;
        boundaryTemperatures = temperatures.clone();
        gasInitializationSource=source.gasInitializationSource;gasInitializationRoundoff=source.gasInitializationRoundoff;
    }
    VulkanFluidStateCodec withBoundaryTemperatures(double[] temperatures) {
        return new VulkanFluidStateCodec(this, temperatures);
    }
    FluidDomainState template() {
        return template;
    }
    FluidDomainState.Layout layout() {
        return template.layout();
    }
    FluidMaterialRegistry fluids() {
        return fluids;
    }
    record ImportData(DeviceLiquidInventory inventory, mmm.fluid.MacMomentumState momentum,
        mmm.fluid.FixedGeometrySnapshot fixed, long[] occupiedSolid,
        VulkanLiquidRegion.MechanicalDefinitions definitions, VulkanLiquidRegion.GasConfiguration gas,
        VulkanGranularStateLayout.Configuration granular, VulkanRegionThermal.Configuration heat,
        VulkanCoherentStateLayout.Configuration coherent, VulkanThermalDeviceLayout.Configuration device,
        VulkanRegionReactions.Configuration reactions, mmm.fluid.EntityBodyState bodies) {}

    VulkanLiquidRegion importRegion(VulkanDeviceContext context, VulkanLiquidGeometry geometry,
        VulkanLiquidProjection projection, VulkanPressureKernel pressure, VulkanPressureMultigrid multigrid) {
        var data = importData();
        var region = new VulkanLiquidRegion(context, geometry, projection, pressure, multigrid, data.inventory(),
            data.momentum(), data.fixed(), data.occupiedSolid(), data.definitions(), data.gas(), data.granular(),
            data.heat(), data.coherent(), data.device(), data.reactions(), null);
        region.initializeInputs(data.bodies(), template.config().cellSize());
        return region;
    }

    ImportData importData() {
        gasInitializationSource=mmm.units.SignedDyadic.ZERO;gasInitializationRoundoff=mmm.units.SignedDyadic.ZERO;
        var inventory = DeviceLiquidInventory.encode(template.liquid());
        var thermal = template.thermalRegistry();
        var owner = template.thermalOwner();
        var chemistry = template.chemicalRegistry();
        var gasChemistry = chemistry == null ? mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY : chemistry;
        var species = gasChemistry.species().stream().map(mmm.chemistry.ChemicalSpecies::id).toList();
        int stride = Math.max(1, chemistry == null ? 0 : chemistry.species().size()), materials = fluids.size();
        int[] phases = new int[materials];
        long[] speciesMass = new long[stride];
        double[] reference = new double[materials], speciesReference = new double[materials * stride];
        Arrays.fill(speciesReference, Double.NaN);
        if (chemistry != null)
            for (int i = 0; i < stride; i++)
                speciesMass[i] = chemistry.speciesMassUnits(i, 1);
        var liquidMaterials = new ArrayList<ThermalMaterialDefinition>();
        for (int m = 0; m < materials; m++) {
            phases[m] = fluids.phaseHandle(m);
            if (thermal == null)
                liquidMaterials.add(new ThermalMaterialDefinition("mmm:resident_isothermal_" + m,
                    ThermalMaterialDefinition.CarrierKind.LIQUID, fluids.material(m).id(), 293.15, 0, 0,
                    List.of(new ThermalMaterialDefinition.CaloricSegment(
                        mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, 100000, 4200))));
            else {
                int host =
                    thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID, fluids.material(m).id());
                var definition = thermal.material(host);
                liquidMaterials.add(definition);
                reference[m] = definition.referenceSpecificEnergyJPerKg();
                if (chemistry != null)
                    for (int s = 0; s < stride; s++)
                        if (thermal.hasSpeciesOffset(host, chemistry.species(s).id()))
                            speciesReference[m * stride + s] = thermal.speciesOffset(host, chemistry.species(s).id())
                                                                   .referenceInternalEnergyJPerMol()
                                                                   .divide(mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL)
                                                                   .toDouble();
            }
        }
        var constitutive = template.materialDefinitions();
        int[] zeroOffsets = new int[materials + 1];
        double[] zeroSegments = new double[4 * materials];
        for (int m = 0; m < materials; m++) {
            zeroOffsets[m + 1] = m + 1;
            zeroSegments[4 * m] = mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
            zeroSegments[4 * m + 1] = 100000;
        }
        var viscosity = constitutive == null ? new VulkanLiquidViscosity.Tables(zeroOffsets, zeroSegments)
                                             : VulkanLiquidViscosity.tables(fluids, constitutive, chemistry);
        double[] temperatures = new double[materials];
        Arrays.fill(temperatures, 293.15);
        var forces = thermal == null || constitutive == null
            ? new VulkanLiquidForces.Tables(temperatures, new double[materials])
            : VulkanLiquidForces.tables(fluids, constitutive);
        int cells = inventory.cellCount(), stocks = Math.max(1, inventory.stockCount());
        var definitions = new VulkanLiquidRegion.MechanicalDefinitions(stocks, inventory.speciesHandles().length,
            Math.max(1, inventory.volumes().limbs().length), 4, -40, phases, speciesMass, reference, speciesReference,
            stride, VulkanLiquidThermodynamics.tables(liquidMaterials).withAqueous(
                fluids.materials().stream().map(m -> m.id()).toList().indexOf("mmm:water"),
                species.indexOf(mmm.chemistry.BuiltinChemicalDefinitions.ETHANOL)), viscosity, forces,
            VulkanSurfaceTension.tables(fluids), true);
        VulkanLiquidRegion.GasConfiguration gas = null;
        var gasMaterials = new ArrayList<ThermalMaterialDefinition>();
        if (template.hasGas()) {
            var spatial = template.spatialGas();
            var snapshot = spatial == null ? template.gas() : null;
            var registry = template.gasRegistry();
            var gasDefinitions =
                new VulkanGasStateLayout.Definitions(registry.fingerprint(), spatial == null ? snapshot.chemicalRegistryFingerprint() : spatial.chemicalRegistryFingerprint(),
                    registry.components().stream().map(mmm.fluid.air.GasComponentDefinition::id).toList(), species,
                    registry.components().stream().map(mmm.fluid.air.GasComponentDefinition::allowedSpecies).toList());
            var tables = thermal == null
                ? VulkanGasThermodynamics.Tables.isothermal(gasDefinitions)
                : VulkanGasThermodynamics.tables(gasDefinitions, registry, thermal, gasChemistry);
            if (spatial == null) {
                var gasThermal = thermal;
                if (gasThermal == null) {
                    var defaults = new ArrayList<ThermalMaterialDefinition>();
                    for (String id : gasDefinitions.componentIds())
                        defaults.add(new ThermalMaterialDefinition("mmm:isothermal_" + id.replace(':','_'),
                            ThermalMaterialDefinition.CarrierKind.GAS, id, 293.15, 0, 0,
                            List.of(new ThermalMaterialDefinition.CaloricSegment(mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,100000,1000))));
                    gasThermal = new mmm.thermal.ThermalRegistry(defaults);
                }
                var gasModel = new mmm.thermal.GasThermalModel(gasThermal,registry);
                double[] energy = owner == null ? new double[snapshot.nodes().size()] : owner.copyGasNodeEnergy();
                if(owner==null)for(int node=0;node<energy.length;node++)energy[node]=gasModel.internalEnergyAtTemperature(snapshot.nodes().get(node).inventory(),293.15);
                double[] dry = new double[cells];double h3=Math.pow(template.config().cellSize(),3);
                long[] occupiedGas=new long[cells];
                if(owner!=null)for(var group:owner.coherentGroups())occupiedGas[group.cell()]=Math.addExact(occupiedGas[group.cell()],group.occupiedVolumeSvq());
                if(template.granular()!=null){
                    var grains=template.granular();
                    for(var deposit:grains.depositGroups())occupiedGas[deposit.cell()]=Math.addExact(occupiedGas[deposit.cell()],deposit.solidVolumeSvq());
                    var kernel=new mmm.granular.GranularKernel(template.sizeX(),template.sizeY(),template.sizeZ(),template.config().cellSize());
                    for(var parcel:grains.activeParcels()){
                        kernel.capture(parcel.x(),parcel.y(),parcel.z(),parcel.solidVolumeSvq());int mask=parcel.ceilMask();kernel.requireMask(mask);
                        for(int part=0;part<kernel.size();part++){int cell=kernel.cell(part);occupiedGas[cell]=Math.addExact(occupiedGas[cell],(mask&(1<<part))!=0?kernel.ceil(part):kernel.floor(part));}
                    }
                }
                var phase=template.phaseOccupancy();var cellOffsets=inventory.cellOffsets();
                for(int cell=0;cell<cells;cell++){
                    double liquidVolume=0;
                    if(phase!=null)for(int micro=0;micro<8;micro++)liquidVolume+=phase.liquidVolume(8*cell+micro);
                    else for(int stock=cellOffsets[cell];stock<cellOffsets[cell+1];stock++)liquidVolume+=inventory.volumes().valueAt(stock).toDouble();
                    dry[cell]=Math.max(0,((Integer.bitCount(~template.fixedGeometry().mask(cell)&255)/8.0)-occupiedGas[cell]/16777216.0-liquidVolume)*h3);
                }
                var initialized=mmm.thermal.SpatialGasInitialization.fromChambers(snapshot,energy,dry,registry,gasChemistry,gasModel,101325,owner==null?293.15:owner.insertionTemperatureK());
                spatial=initialized.state();
                gasInitializationSource=mmm.units.SignedDyadic.of(initialized.capturedAmbientEnergy());
                gasInitializationRoundoff=mmm.units.SignedDyadic.sum(spatial.internalEnergy()).subtract(mmm.units.SignedDyadic.sum(energy)).subtract(gasInitializationSource);
            }
            gas = VulkanLiquidRegion.GasConfiguration.forState(spatial,tables,template.config().cellSize(),template.sealedDomainBoundary(),
                VulkanGasAmbient.ambientTables(registry,gasDefinitions,owner==null?293.15:owner.insertionTemperatureK()),gasChemistry);
            if (thermal != null)
                for (String id : gasDefinitions.componentIds())
                    gasMaterials.add(
                        thermal.material(thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS, id)));
        }
        VulkanGranularStateLayout.Configuration granular = null;
        var granularMaterials = new ArrayList<ThermalMaterialDefinition>();
        if (template.granular() != null) {
            var snapshot = template.granular();
            var state =
                VulkanGranularStateLayout.State
                    .fromSnapshot(snapshot, species,
                        owner == null ? new double[snapshot.activeParcels().size()] : owner.copyActiveParcelEnergy(),
                        owner == null ? new double[snapshot.depositGroups().size()] : owner.copyDepositEnergy(),
                        template.config().cellSize())
                    .withDynamics(template.granularDynamics());
            if (thermal == null) {
                for (String id : state.definitions().materialIds())
                    granularMaterials.add(new ThermalMaterialDefinition(id,
                        ThermalMaterialDefinition.CarrierKind.GRANULAR, id, 293.15, 0, 0,
                        List.of(new ThermalMaterialDefinition.CaloricSegment(
                            mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, 100000, 800))));
                granular = new VulkanGranularStateLayout.Configuration(state, template.granularRegistry(),
                    VulkanLiquidThermodynamics.tables(granularMaterials), new double[granularMaterials.size()],
                    new double[granularMaterials.size() * Math.max(1, species.size())], Math.max(1, species.size()),
                    Math.max(1, 4 * state.particles().size()));
            } else {
                granular = VulkanGranularStateLayout.Configuration.fromRegistries(state, template.granularRegistry(),
                    gasChemistry, thermal, Math.max(1, 4 * state.particles().size()));
                for (String id : state.definitions().materialIds())
                    granularMaterials.add(
                        thermal.material(thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR, id)));
            }
        }
        VulkanCoherentStateLayout.Configuration coherent = null;
        VulkanThermalDeviceLayout.Configuration device = null;
        VulkanRegionThermal.Configuration heat = null;
        var coherentMaterials = new ArrayList<ThermalMaterialDefinition>();
        var deviceMaterials = new ArrayList<ThermalMaterialDefinition>();
        long[] occupied = new long[cells];
        if (thermal != null) {
            coherent = VulkanCoherentStateLayout.Configuration.fromRegistries(
                owner.coherentGroups(), species, thermal, gasChemistry);
            for (var group : owner.coherentGroups())
                occupied[group.cell()] = Math.addExact(occupied[group.cell()], group.occupiedVolumeSvq());
            for (String id : coherent.definitions().phaseIds())
                coherentMaterials.add(thermal.material(thermal.materialHandle(id)));
            if (devices != null) {
                device = VulkanThermalDeviceLayout.Configuration.fromRegistries(
                    new VulkanThermalDeviceLayout.State(devices, ports), thermal);
                for (String id : device.definitions().materialIds())
                    deviceMaterials.add(thermal.material(thermal.materialHandle(id)));
            }
            var bath = boundaryTemperatures;
            if (bath == null) {
                bath = new double[6 * cells];
                Arrays.fill(bath, Double.NaN);
            }
            heat = VulkanRegionThermal.Configuration.fromMaterials(liquidMaterials, gasMaterials, granularMaterials,
                coherentMaterials, deviceMaterials, template.config().cellSize(), bath, 1);
        }
        VulkanRegionReactions.Configuration reactions = null;
        if (reactionDefinitions != null) {
            var pending = template.chemicalMassTransferPending()
                              .stream()
                              .map(p -> {
                                  int rule = reactionDefinitions.identity().rules().indexOf(p.definitionId());
                                  if (rule < 0)
                                      throw new IllegalArgumentException("Pending transfer is absent from GPU rules");
                                  return new VulkanReactionProgressLayout.Pending(p.cell(), p.material(),
                                      p.supportMask(), rule, p.pendingSeconds(), p.residualMoles());
                              })
                              .toList();
            var dirty = template.copyChemicalDirtyCells();
            var phase = template.pendingPhaseMotion() == null
                ? new int[0]
                : template.pendingPhaseMotion()
                      .entries()
                      .stream()
                      .mapToInt(mmm.fluid.PendingPhaseMotionState.Entry::cell)
                      .toArray();
            var progress = new VulkanReactionProgressLayout.State(
                template.chemicalReactionCursor(), 0, pending, dirty == null ? new int[0] : dirty, phase);
            reactions = new VulkanRegionReactions.Configuration(reactionDefinitions, fluids, chemistry, progress,
                template.config().gravity(), template.precipitationEnabled(), thermal, template.gasRegistry());
        }
        return new ImportData(inventory, template.momentum(), template.fixedGeometry(), occupied, definitions, gas,
            granular, heat, coherent, device, reactions, template.entityBodyState());
    }
    FluidDomainState export(VulkanLiquidRegion.Capture capture, ThermalAuditLedger audit) {
        var progress = capture.reactionProgress();
        var pending = reactionDefinitions == null
            ? List.<mmm.chemistry.RegionChemistry.MassTransferPending>of()
            : progress.pending()
                  .stream()
                  .map(p
                      -> new mmm.chemistry.RegionChemistry.MassTransferPending(p.cell(), p.material(), p.support(),
                          reactionDefinitions.identity().rules().get(p.rule()), p.seconds(), p.residualMoles()))
                  .sorted()
                  .toList();
        var liquid = capture.liquid();
        var granular = capture.hasGranular() ? capture.granular() : null;
        var gas = capture.hasGas() ? capture.gas() : null;
        ThermalOwnerSnapshot owners = null;
        if (template.thermalOwner() != null) {
            owners = new ThermalOwnerSnapshot(template.thermalRegistry().fingerprint(),
                template.thermalOwner().insertionTemperatureK(),
                granular == null
                    ? new double[0]
                    : granular.particles().stream().mapToDouble(p -> p.inventory().internalEnergy()).toArray(),
                granular == null ? new double[0] : granular.depositEnergy(),
                new double[0], capture.hasCoherent() ? capture.coherent() : List.of(),
                java.util.Objects.requireNonNull(audit));
            double[] energy =
                Arrays.stream(liquid.inventory().energyBits()).mapToDouble(Double::longBitsToDouble).toArray();
            owners.requireClosed(
                mmm.units.SignedDyadic.sum(energy).add(gas == null ? mmm.units.SignedDyadic.ZERO : mmm.units.SignedDyadic.sum(gas.internalEnergy())), template.sizeX() * template.sizeY() * template.sizeZ());
        }
        var physicalGranular = granular == null
            ? null
            : granular.toSnapshot(template.sizeX(), template.sizeY(), template.sizeZ(), template.config().cellSize());
        return new FluidDomainState(template.sizeX(), template.sizeY(), template.sizeZ(), template.config(),
            template.sealedDomainBoundary(), liquid.inventory().decode(),
            capture.generation() == 0 ? template.phaseOccupancy() : capture.occupancy(), liquid.momentum(),
            template.fixedGeometry(), capture.entityBodies(), template.materialPalette(), template.chemicalRegistry(),
            template.chemicalSource().plus(capture.filmExternalExchange(true)),
            template.chemicalSink().plus(capture.filmExternalExchange(false)),
            template.chemicalRegistry() == null ? null
                : capture.generation() == 0     ? template.copyChemicalDirtyCells()
                                                : progress.dirtyCells(),
            capture.generation() == 0 ? template.chemicalReactionCursor() : progress.cursor(),
            capture.generation() == 0 ? template.chemicalMassTransferPending() : pending, template.granularRegistry(),
            physicalGranular,
            granular == null ? null : granular.schedulerState(template.sizeX(), template.sizeY(), template.sizeZ()),
            template.precipitationEnabled(), template.gasRegistry(),
            gas == null ? null : FluidDomainState.GasContinuation.spatial(gas), template.thermalRegistry(),
            owners, template.maximumThermalTransportSubsteps(),
            template.thermalOwner() == null ? null
                : capture.generation() == 0 ? template.pendingPhaseMotion()
                                            : capture.pendingPhaseMotion(),
            boundaryTemperatures == null ? null : boundaryTemperatures.clone(),
            template.thermalOwner() == null ? null
                : capture.generation() == 0 ? template.exportCondensedPhasePressure()
                                            : capture.pressure(),
            template.materialDefinitions(), template.activityState());
    }
    FiniteThermalDeviceInventory exportDevices(VulkanLiquidRegion.Capture capture) {
        return capture.hasDevices() ? capture.devices().inventory() : devices;
    }
}
