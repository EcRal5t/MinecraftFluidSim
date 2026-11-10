package mmm.simulation;

import java.util.List;
import java.util.UUID;
import mmm.fluid.*;
import mmm.inventory.LiquidCellInventory;
import mmm.pipe.*;
import mmm.thermal.GasThermalModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Exercises a private region and pipe using the real gas and caloric routing. */
final class PipeRegionExchangeValidation {
    static void validate() throws Exception {
        initializationPreservesContents();
        var definitions=TestFluidDefinitions.load();var registries=FullCapabilityTestFixture.load();
        var host=new HostFluidState(2,2,2,new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f),definitions);
        FullCapabilityTestFixture.bindFull(host,registries,300,4);
        host.setVolume(0,0,0,.4f);
        var solids=host.tryInsertExactGranular(0,0,0,definitions.granular().materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND),
                0,mmm.granular.SolidVolume.SVQ_PER_CELL/64,mmm.units.Mass.fromKilograms(.2));
        require(solids.inserted(),"Exchange granular fixture failed");
        PipeRegionTestAccess.setFirstParcelVelocity(host,.3f,.1f,0);
        var momentum=host.exportMacMomentum();var x=momentum.copyX();
        x[host.grid().face(0,1)-host.grid().faceOffset(0)]=.1;
        host.restoreMacMomentum(new MacMomentumState(host.grid(),x,momentum.copyY(),momentum.copyZ()));
        var registry=ResourceKey.<Level>createRegistryKey(Identifier.fromNamespaceAndPath("mmm","pipe_exchange_validation"));
        var dimension=ResourceKey.create(registry,Identifier.fromNamespaceAndPath("mmm","pipe_exchange_test"));
        var region=new FluidRegion(UUID.randomUUID(),dimension,BlockPos.ZERO,host);
        region.setPaused(false);
        double fullArea=PipeEndpointResolver.faceAreas(region,BlockPos.ZERO,Direction.EAST).values().stream().mapToDouble(Double::doubleValue).sum();
        require(Math.abs(fullArea-.25)<1e-12,"pipe mouth must cover the actual open quarter-square-metre face");
        var clippedHost=new HostFluidState(2,2,2,new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f),definitions);
        clippedHost.setSolidMicroMask(0,0,0,255);
        var clipped=new FluidRegion(UUID.randomUUID(),dimension,BlockPos.ZERO,clippedHost);clipped.setPaused(false);
        double clippedArea=PipeEndpointResolver.faceAreas(clipped,BlockPos.ZERO,Direction.EAST).values().stream().mapToDouble(Double::doubleValue).sum();
        require(Math.abs(clippedArea-.1875)<1e-12,"blocked target microface must be excluded");
        var gas=definitions.gas().createAmbient(200_000_000L);
        var thermal=new GasThermalModel(definitions.thermal(),definitions.gas());
        double energy=thermal.internalEnergyAtTemperature(gas,300);
        var segment=new PipeSegmentState("pipe",.25,.125,gas,energy,List.of());
        var pipe=new PipeNetworkState(List.of(segment),List.of(),List.of(),new LiquidCellInventory.Snapshot(List.of(List.of())));
        var before=PipeRegionCells.capture(region.beginHostEdit(),0);
        verifyContactPressure(region.beginHostEdit(),before,definitions);
        var initialRegion=region.beginHostEdit().captureAuthoritativeState();
        long total=Math.addExact(gas.totalMassUnits(),regionMass(initialRegion));
        double initial=PipeNetworkSolver.energy(pipe,definitions,0).toDouble()+PipeRegionCells.energy(region.beginHostEdit());
        long granularBefore=region.beginHostEdit().exportGranularState().totalMassUnits();long generation=region.solverGeneration();
        try(var prepared=PipeRegionExchange.prepare(pipe,List.of(new PipeRegionExchange.Port(0,region,0,.25,.5,0,1,Direction.EAST)),.05,definitions,0)) {
            require(prepared.isCurrent() && region.solverGeneration()==generation,"Preparation changed region");
            long afterPipe=prepared.state().segments().getFirst().gas().standardVolumeNanos();
            require(afterPipe<gas.standardVolumeNanos(),"Pressure difference moved no gas into region");
            prepared.commit();prepared.publishObservations();
            var after=PipeRegionCells.capture(region.beginHostEdit(),0);
            long pipeMass=prepared.state().segments().getFirst().gas().totalMassUnits()
                    +prepared.state().liquids().stocks().stream().mapToLong(mmm.inventory.LiquidCellInventory.Stock::massUnits).sum()
                    +prepared.state().segments().getFirst().deposits().stream().mapToLong(v->v.inventory().massUnits()).sum();
            long afterMass=regionMass(region.beginHostEdit().captureAuthoritativeState())+pipeMass;
            require(afterMass==total,"Pipe exchange lost mass: before="+total+", after="+afterMass);
            var afterHost=region.beginHostEdit();
            double finalEnergy=PipeNetworkSolver.energy(prepared.state(),definitions,0).toDouble()+PipeRegionCells.energy(afterHost);
            require(Math.abs(finalEnergy-initial)<1e-12*Math.max(1,Math.abs(initial)),"Pipe exchange lost joint thermal or elastic energy: "+initial+" -> "+finalEnergy);
            long solidAfter=afterHost.exportGranularState().totalMassUnits()+prepared.state().segments().getFirst().deposits().stream().mapToLong(v->v.inventory().massUnits()).sum();
            require(solidAfter==granularBefore,"Pipe exchange lost granular mass");
            require(initialRegion.granular().activeParcels().equals(afterHost.exportGranularState().activeParcels())
                    && initialRegion.granular().depositGroups().equals(afterHost.exportGranularState().depositGroups()),
                    "Unmoved regional granular owners changed representation");
            require(region.solverGeneration()==generation+1,"Region generation did not advance");
        }
    }
    private static void verifyContactPressure(HostFluidState host,PipeRegionCells.Cell cell,FluidDefinitions definitions) {
        var deposits=PipeRegionGranular.capture(host,cell.index(),definitions);
        double capacity=cell.capacityM3();
        for(var deposit:deposits)capacity+=deposit.inventory().solidVolumeSvq()/(double)mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER;
        var segment=new PipeSegmentState("contact",0,capacity,cell.gas(),cell.gasEnergy(),deposits);
        var stocks=new java.util.ArrayList<LiquidCellInventory.Stock>();var palette=new java.util.ArrayList<String>();
        double h=host.config().cellSize();var scale=mmm.units.SignedDyadic.of(h*h*h);
        for(var stock:cell.liquid()) {
            String id=host.materialRegistry().material(stock.key().material()).id();int material=palette.indexOf(id);
            if(material<0){material=palette.size();palette.add(id);}
            stocks.add(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,material,255),stock.volume().multiply(scale),
                    stock.massUnits(),stock.mixture(),stock.internalEnergy()));
        }
        double pipePressure=PipeHydraulics.mixture(segment,stocks,new GasThermalModel(definitions.thermal(),definitions.gas())).absolutePressurePa();
        double regionPressure=host.pressure(0,0,0)+mmm.thermal.MixtureVolumeModel.REFERENCE_PRESSURE_PA;
        require(Math.abs(pipePressure-regionPressure)<.05,"pipe contact pressure differs from region EOS: "+pipePressure+" / "+regionPressure);
    }
    private static void initializationPreservesContents() throws Exception {
        var definitions=TestFluidDefinitions.load();var registries=FullCapabilityTestFixture.load();
        var host=new HostFluidState(2,2,2,new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f),definitions);
        FullCapabilityTestFixture.bindFull(host,registries,300,4);host.setVolume(0,0,0,.4f);
        var inserted=host.tryInsertExactGranular(0,0,0,definitions.granular().materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND),
                0,mmm.granular.SolidVolume.SVQ_PER_CELL/64,mmm.units.Mass.fromKilograms(.2));
        require(inserted.inserted(),"Granular initialization fixture failed");
        var registry=ResourceKey.<Level>createRegistryKey(Identifier.fromNamespaceAndPath("mmm","pipe_init_validation"));
        var dimension=ResourceKey.create(registry,Identifier.fromNamespaceAndPath("mmm","pipe_init_test"));
        var footprint=mmm.simulation.controller.FluidRegionFootprint.selected(BlockPos.ZERO,BlockPos.ZERO,
                java.util.Set.of(BlockPos.ZERO.asLong()),java.util.Set.of(net.minecraft.world.level.ChunkPos.pack(0,0)));
        var region=new FluidRegion(UUID.randomUUID(),dimension,BlockPos.ZERO,host,footprint);region.setPaused(false);
        var manager=new FluidRegionManager(new mmm.application.RegionCatalog.Limits(4,100,400));
        require(manager.add(region).accepted(),"Region fixture was not admitted");
        long granularBefore=host.exportGranularState().totalMassUnits();
        long liquidBefore=host.exportLiquidCellState().stocks().stream().mapToLong(mmm.inventory.LiquidCellInventory.Stock::massUnits).sum();
        var before=region.beginHostEdit().captureAuthoritativeState();
        long gasBefore=before.spatialGas().cells().stream().mapToLong(mmm.fluid.air.GasInventory::standardVolumeNanos).sum();
        var speciesBefore=liquidSpecies(before.liquid().stocks(),definitions.chemistry());
        try(var prepared=PipeRegionAirExchange.prepare(dimension,pos->net.minecraft.world.phys.shapes.Shapes.box(.25,.25,.25,.75,.75,.75),manager,
                java.util.Map.of(BlockPos.ZERO,.125),java.util.Map.of(),definitions)) {
            var contents=prepared.inventories().get(BlockPos.ZERO);
            long moved=contents.liquids().stream().mapToLong(mmm.inventory.LiquidCellInventory.Stock::massUnits).sum();
            long granularMoved=contents.deposits().stream().mapToLong(value->value.inventory().massUnits()).sum();
            require(moved>0 && granularMoved>0 && contents.air().inventory().standardVolumeNanos()>0,"New pipe did not acquire liquid, gas and granular inventory");
            prepared.commit(null);prepared.publishObservations();
            var after=region.beginHostEdit().captureAuthoritativeState();
            long liquidAfter=after.liquid().stocks().stream().mapToLong(mmm.inventory.LiquidCellInventory.Stock::massUnits).sum();
            long gasAfter=after.spatialGas().cells().stream().mapToLong(mmm.fluid.air.GasInventory::standardVolumeNanos).sum();
            require(liquidAfter+moved==liquidBefore,"Pipe initialization lost liquid mass");
            var speciesAfter=liquidSpecies(after.liquid().stocks(),definitions.chemistry());
            liquidSpecies(contents.liquids(),definitions.chemistry()).forEach((id,amount)->speciesAfter.merge(id,amount,Math::addExact));
            require(speciesAfter.equals(speciesBefore),"Pipe initialization lost liquid species");
            require(after.granular().totalMassUnits()+granularMoved==granularBefore,"Pipe initialization lost granular mass");
            require(gasAfter+contents.air().inventory().standardVolumeNanos()==gasBefore,"Pipe initialization lost gas");
        }
    }
    private static long regionMass(FluidDomainState state) {
        long result=state.liquid().stocks().stream().mapToLong(mmm.inventory.LiquidCellInventory.Stock::massUnits).sum();
        result+=state.spatialGas().cells().stream().mapToLong(mmm.fluid.air.GasInventory::totalMassUnits).sum();
        return result+state.granular().totalMassUnits();
    }
    private static java.util.Map<String,Long> liquidSpecies(List<mmm.inventory.LiquidCellInventory.Stock> stocks,mmm.chemistry.ChemicalRegistry chemistry) {
        var result=new java.util.TreeMap<String,Long>();
        for(var stock:stocks)if(stock.mixture()!=null)stock.mixture().stableAmounts(chemistry).forEach((id,amount)->result.merge(id,amount,Math::addExact));
        return result;
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
