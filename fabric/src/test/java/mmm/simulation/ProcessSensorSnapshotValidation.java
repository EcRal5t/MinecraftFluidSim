package mmm.simulation;

import java.util.UUID;
import mmm.api.sensor.ProcessSensorSnapshot;
import mmm.api.sensor.ProcessSensorSnapshot.Measure;
import mmm.api.sensor.ProcessSensorSnapshot.Status;
import mmm.block.MmmBlocks;
import mmm.block.ProcessSensorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;

/** Committed sensor state and the exact block-entity update/save payload. */
public final class ProcessSensorSnapshotValidation {
    public static void main(String[] args) {
        GpuControllerFixture.initializeItems(); GpuControllerFixture.initializeIndustrialBlocks();
        var registry=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var sensor=create(); var first=new BlockPos(1,2,3); var last=new BlockPos(2,3,4);
        check(sensor.snapshot().status()==Status.WAITING && sensor.snapshot().value()==null,"initial reading");
        var session=UUID.randomUUID();
        var a=new ProcessSensorBlockEntity.TrendObserver(UUID.randomUUID(),session);
        var b=new ProcessSensorBlockEntity.TrendObserver(UUID.randomUUID(),session);
        var key=new mmm.network.ProcessTrendPayload.SeriesKey("chem:h_plus","LIQUID",mmm.network.ProcessTrendPayload.Metric.AMOUNT,true,false);
        check(!sensor.hasTrendObservers(), "trend sampling started without a viewer");
        sensor.subscribeTrend(a,0,10,java.util.List.of(key),false);
        sensor.subscribeTrend(b,0,10,java.util.List.of(key),false);
        var trend=sensor.trendHistory(key); trend.region(UUID.randomUUID(),1); trend.sample(0,1);
        sensor.subscribeTrend(a,1,11,java.util.List.of(),false);
        check(sensor.hasTrendObservers() && sensor.trendKeys().contains(key) && sensor.trendHistory(key)==trend,"empty selection disturbed other viewer");
        sensor.subscribeTrend(b,1,12,java.util.List.of(),true);
        check(sensor.hasTrendObservers() && sensor.trendKeys().isEmpty(),"closing another viewer lost catalog subscription");
        sensor.subscribeTrend(a,2,13,java.util.List.of(key),false);
        check(sensor.trendHistory(key).points().isEmpty(),"reselected component retained an old connection");
        sensor.subscribeTrend(a,3,14,java.util.List.of(),true);
        check(!sensor.hasTrendObservers(),"closed viewer retained subscription");
        sensor.subscribeTrend(a,4,20,java.util.List.of(key),false); sensor.pruneTrend(60);
        check(sensor.hasTrendObservers(),"lease expired early"); sensor.pruneTrend(61);
        check(!sensor.hasTrendObservers() && sensor.trendKeys().isEmpty(),"trend sampling lease expiry");
        check(!sensor.snapshot().display().contains(": 0"),"initial display presented zero as a sample");
        sensor.configure(Measure.LEVEL,.2,.8,true,false,first,last);
        var region=UUID.randomUUID(); sensor.observe(.15,region,7,11,1.5,true);
        var reading=sensor.snapshot();
        check(reading.status()==Status.RUNNING && reading.value()==.15 && reading.measure().unit().equals("1")
                && reading.regionId().equals(region) && reading.regionIncarnation()==7 && reading.solverGeneration()==11
                && reading.simulationSeconds()==1.5 && sensor.output()==15,"committed observation");
        var update=sensor.getUpdateTag(registry); var client=create();
        client.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,registry,update));
        check(client.snapshot().equals(reading) && client.output()==15,"initial/update packet snapshot");
        sensor.observe(.15,region,7,12,1.55,false);
        check(sensor.snapshot().status()==Status.PAUSED && sensor.snapshot().value()==.15
                && sensor.snapshot().solverGeneration()==12,"paused observation");
        var paused=sensor.getUpdateTag(registry);
        client.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,registry,paused));
        check(client.snapshot().equals(sensor.snapshot()),"paused update");
        sensor.unavailable(Status.UNAVAILABLE); sensor.signal(0);
        client.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,registry,sensor.getUpdateTag(registry)));
        check(client.snapshot().status()==Status.UNAVAILABLE && client.snapshot().value()==null
                && client.snapshot().regionId()==null,"unavailable update retained old reading");
        sensor.unavailable(Status.NO_SAMPLE);
        check(sensor.snapshot().status()==Status.NO_SAMPLE && sensor.snapshot().value()==null,"empty carrier");
        var replacement=UUID.randomUUID(); sensor.observe(295.25,replacement,8,1,.05,false);
        sensor.history().region(replacement,8); sensor.history().samplePaused(.05,295.25);
        sensor.history(Measure.PRESSURE).region(replacement,8);
        sensor.history(Measure.PRESSURE).sample(.05,101325);
        sensor.unavailable(Status.NO_SAMPLE);
        check(sensor.history(Measure.PRESSURE).points().size()==1, "one unavailable metric discarded another history");
        sensor.configure(Measure.LIQUID_TEMPERATURE,270,350,true,false,first,last);
        check(sensor.snapshot().status()==Status.WAITING && sensor.snapshot().measure()==Measure.LIQUID_TEMPERATURE
                && sensor.snapshot().value()==null && sensor.snapshot().regionId()==null && sensor.output()==0
                && sensor.history().points().isEmpty() && sensor.history(Measure.PRESSURE).points().isEmpty(),"reconfiguration retained old data");
        check(Measure.LIQUID_TEMPERATURE.unit().equals("K") && Measure.GAS_TEMPERATURE.unit().equals("K")
                && Measure.PRESSURE.unit().equals("Pa") && Measure.FLOW.unit().equals("L/s"),"measurement units");
        sensor.observe(260,replacement,8,3,.1,true);
        var save=sensor.saveCustomOnly(registry); var restored=create();
        restored.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,registry,save));
        check(restored.snapshot().status()==Status.WAITING && restored.snapshot().value()==null
                && restored.snapshot().measure()==Measure.LIQUID_TEMPERATURE && restored.output()==15,
                "save restored an obsolete live observation or lost the persisted signal");
        restored.unavailable(Status.UNAVAILABLE);
        check(restored.output()==0 && restored.snapshot().value()==null,"unavailable observation did not clear persisted signal");
        validateCompositionSampling();
        System.out.println("ProcessSensorSnapshotValidation passed");
    }
    private static void validateCompositionSampling() {
        var defs=mmm.fluid.TestFluidDefinitions.load(); var chemistry=defs.chemistry();
        var config=new mmm.fluid.FluidSolverConfig(.5F,0,.05F,120,1e-5F,.002F);
        var domain=new mmm.fluid.HostFluidState(4,4,4,config,defs);
        domain.bindMaterialRegistry(new mmm.fluid.FluidMaterialRegistry(java.util.List.of(defs.material().entry(mmm.fluid.BuiltinFluidMaterials.WATER).material()))); domain.bindMaterialDefinitions(defs.material()); domain.bindChemistry(chemistry); domain.bindGasRegistry(defs.gas());
        var inventory=new mmm.inventory.LiquidCellInventory(64,chemistry);
        var mixture=mmm.chemistry.ChemicalMixtureRecord.fromStableAmounts(chemistry,java.util.Map.of(
                mmm.chemistry.BuiltinChemicalDefinitions.H2O,1000L,mmm.chemistry.BuiltinChemicalDefinitions.H_PLUS,200L),0);
        var outside=mmm.chemistry.ChemicalMixtureRecord.fromStableAmounts(chemistry,java.util.Map.of(mmm.chemistry.BuiltinChemicalDefinitions.H2O,9000L),0);
        inventory.planAdditions(java.util.List.of(
                new mmm.inventory.LiquidCellInventory.Stock(new mmm.inventory.LiquidCellInventory.Key(domain.grid().cell(0,0,0),0,255),mmm.units.SignedDyadic.of(.25),mixture.massUnits(chemistry),mixture,0),
                new mmm.inventory.LiquidCellInventory.Stock(new mmm.inventory.LiquidCellInventory.Key(domain.grid().cell(3,0,0),0,255),mmm.units.SignedDyadic.of(.25),outside.massUnits(chemistry),outside,0))).commit();
        domain.restoreLiquidCellState(inventory.snapshot(),chemistry); domain.resetAmbientGas();
        var dimension=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm","trend_test"));
        var region=new FluidRegion(UUID.randomUUID(),dimension,BlockPos.ZERO,domain); var sensor=create();
        var water=new mmm.network.ProcessTrendPayload.SeriesKey(mmm.chemistry.BuiltinChemicalDefinitions.H2O,"LIQUID",mmm.network.ProcessTrendPayload.Metric.AMOUNT,false,false);
        var ion=new mmm.network.ProcessTrendPayload.SeriesKey(mmm.chemistry.BuiltinChemicalDefinitions.H_PLUS,"LIQUID",mmm.network.ProcessTrendPayload.Metric.AMOUNT,true,false);
        var ionMass=new mmm.network.ProcessTrendPayload.SeriesKey(mmm.chemistry.BuiltinChemicalDefinitions.H_PLUS,"LIQUID",mmm.network.ProcessTrendPayload.Metric.MASS,true,false);
        var ionVolume=new mmm.network.ProcessTrendPayload.SeriesKey(mmm.chemistry.BuiltinChemicalDefinitions.H_PLUS,"LIQUID",mmm.network.ProcessTrendPayload.Metric.VOLUME,true,false);
        sensor.subscribeTrend(new ProcessSensorBlockEntity.TrendObserver(UUID.randomUUID(),UUID.randomUUID()),0,0,java.util.List.of(water,ion),false);
        IndustrialOperations.observeSensor(region,sensor,BlockPos.ZERO,BlockPos.ZERO);
        check(sensor.trendCatalog().containsAll(java.util.List.of(water,ion,ionMass)) && !sensor.trendCatalog().contains(ionVolume),"ion catalog metrics");
        check(Math.abs(sensor.trendHistory(water).points().getLast().value()-.001)<1e-12 && Math.abs(sensor.trendHistory(ion).points().getLast().value()-.0002)<1e-12,"component sampling or spatial filter");
        check(sensor.trendCatalog().stream().anyMatch(k->k.phase().equals("GAS") && k.metric()==mmm.network.ProcessTrendPayload.Metric.AMOUNT),"ambient gas amount missing");
        check(sensor.trendCatalog().stream().filter(k->k.phase().equals("GAS")).noneMatch(k->k.metric()==mmm.network.ProcessTrendPayload.Metric.VOLUME),"gas volume was offered");
    }
    private static ProcessSensorBlockEntity create() {
        return new ProcessSensorBlockEntity(BlockPos.ZERO,MmmBlocks.PROCESS_SENSOR.defaultBlockState());
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
