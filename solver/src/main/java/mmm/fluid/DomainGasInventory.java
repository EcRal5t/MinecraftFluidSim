package mmm.fluid;

import mmm.thermal.MixtureVolumeModel;

import java.util.List;
import mmm.chemistry.ExactRational;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.units.SignedDyadic;

/** Local external gas transfers over the common cell inventory and face momentum. */
final class DomainGasInventory {
  private DomainGasInventory() {}

  static int nodeAt(HostFluidState domain, double x, double y, double z) {
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return -1;
    double h = domain.config.cellSize();
    int cx = (int) Math.floor(x / h), cy = (int) Math.floor(y / h), cz = (int) Math.floor(z / h);
    if (cx < 0
        || cy < 0
        || cz < 0
        || cx >= domain.sizeX
        || cy >= domain.sizeY
        || cz >= domain.sizeZ) return -1;
    domain.domainCapabilities.initializeSpatialGas();
    int cell=domain.grid.cell(cx,cy,cz),micro=((x/h-cx)>=.5?1:0)+((y/h-cy)>=.5?2:0)+((z/h-cz)>=.5?4:0);
    if(cell<0)return -1;
    if((domain.geometry.cutCells.mask(cell)&(1<<micro))!=0||!domain.groupAvailable(cell))return -1;
    domain.pressureSystem.prepareGeometry();
    var plane=domain.pressureSystem.phaseGeometry().componentPlane(cell,micro);
    if(plane!=null && plane.fraction()>0 && (plane.fraction()>=1 ||
        plane.nx()*(x/h-cx)+plane.ny()*(y/h-cy)+plane.nz()*(z/h-cz)<=plane.offset()))return -1;
    return mixture(domain,cell,domain.gasState.spatial.cells().get(cell),domain.gasState.spatial.energyAt(cell)).gasVolumeM3()>0?cell+1:-1;
  }

  static FluidDomainContracts.GasEdit edit(HostFluidState domain,int node,String componentId,long nanos,double temperatureK,boolean extract) {
    if(nanos<0||!extract&&!(Double.isFinite(temperatureK)&&temperatureK>0))
      throw new IllegalArgumentException("Gas transfer requires a nonnegative amount and positive insertion temperature");
    domain.domainCapabilities.initializeSpatialGas();
    return editSpatial(domain,node,componentId,nanos,temperatureK,extract,null,0);
  }

  static FluidDomainContracts.GasEdit insert(HostFluidState domain,int node,GasInventory inventory,double energy) {
    if (inventory == null || inventory.isEmpty() || !Double.isFinite(energy))
      throw new IllegalArgumentException("Gas inventory and finite internal energy are required");
    domain.domainCapabilities.initializeSpatialGas();
    return editSpatial(domain,node,null,inventory.standardVolumeNanos(),0,false,inventory,energy);
  }

  static MixtureVolumeModel.State mixture(HostFluidState domain,int cell,GasInventory gas,double energy){
    return mixture(domain,cell,gas,energy,domain.liquidCells,domain.occupiedSolidSvq(),null);
  }
  static MixtureVolumeModel.State mixture(HostFluidState domain,int cell,GasInventory gas,double energy,
                                         mmm.inventory.LiquidCellInventory liquid,long[] occupied,byte[] masks){
    double h=domain.config.cellSize(),h3=h*h*h;
    int mask=masks==null?domain.geometry.cutCells.mask(cell):Byte.toUnsignedInt(masks[cell]);
    double capacity=(Integer.bitCount(~mask&255)*.125-Math.scalb((double)occupied[cell],-24))*h3;
    double reference=liquid.exactCellVolume(cell).toDouble()*h3;
    long units=0;for(var stock:liquid.physicalStocksInCell(cell))units=Math.addExact(units,stock.massUnits());
    double r=GasRegistry.STANDARD_PRESSURE_PA*gas.standardVolumeNanos()*1e-9/GasRegistry.STANDARD_TEMPERATURE_K;
    double temperature=293.15,gamma=1.4;
    if(!gas.isEmpty()){
      var thermal=domain.domainCapabilities.spatialGasModel().state(gas,energy);
      temperature=thermal.temperatureK();gamma=1+r/thermal.heatCapacityJPerK();
    }
    return MixtureVolumeModel.evaluate(capacity,reference,mmm.units.Mass.kilograms(units),r,temperature,gamma,domain.config.timeStep(),h);
  }

  static double spatialTemperature(HostFluidState domain,int cell){
    var state=domain.gasState.spatial;var gas=state.cells().get(cell);
    return gas.isEmpty()?GasRegistry.STANDARD_TEMPERATURE_K:domain.domainCapabilities.spatialGasModel().state(gas,state.energyAt(cell)).temperatureK();
  }
  static double spatialPressure(HostFluidState domain,int cell){var state=domain.gasState.spatial;return mixture(domain,cell,state.cells().get(cell),state.energyAt(cell)).absolutePressurePa();}

  static void resetAmbient(HostFluidState domain){
    domain.domainCapabilities.initializeSpatialGas();var old=domain.gasState.spatial;var registry=domain.gasState.network.registry();
    var model=domain.domainCapabilities.spatialGasModel();long[] occupied=domain.occupiedSolidSvq();
    var cells=new java.util.ArrayList<GasInventory>(old.cellCount());var cache=new java.util.HashMap<Double,GasInventory>();double[] energies=new double[old.cellCount()];
    for(int cell=0;cell<old.cellCount();cell++){
      double volume=mixture(domain,cell,GasInventory.EMPTY,0,domain.liquidCells,occupied,null).gasVolumeM3();
      var gas=volume==0?GasInventory.EMPTY:cache.computeIfAbsent(volume,v->model.inventoryAtPressure(v,GasRegistry.STANDARD_PRESSURE_PA,GasRegistry.STANDARD_TEMPERATURE_K,registry.ambient()));
      cells.add(gas);if(!gas.isEmpty())energies[cell]=model.internalEnergyAtTemperature(gas,GasRegistry.STANDARD_TEMPERATURE_K);
    }
    var next=new mmm.fluid.air.SpatialGasState(old.grid(),old.registryFingerprint(),old.chemicalRegistryFingerprint(),cells,energies,
        old.externalSource().add(mmm.fluid.air.GasRegionAudit.aggregate(cells)),old.escapedSink().add(mmm.fluid.air.GasRegionAudit.aggregate(old.cells())),old.phaseSource(),old.phaseSink());
    var gasEdit=new SpatialGasEdits.Plan(old,next,SignedDyadic.ZERO,SignedDyadic.ZERO,SignedDyadic.ZERO,SpatialGasEdits.ventAll(old));
    var edit=HostMixtureEdit.prepare(domain,domain.liquidCells.planEdits(List.of()),gasEdit,null);
    var audit=domain.thermalState==null?null:edit.gas().applyAudit(domain.thermalState.auditLedger()
        .withExternalSourceEnergy(SignedDyadic.sum(energies)).withExternalSinkEnergy(SignedDyadic.sum(old.internalEnergy())))
        .withMechanicalWork(edit.inventory().mechanicalHeat()).withRoundoff(edit.inventory().energyRoundoff());
    edit.inventory().requireCurrent();edit.gas().requireCurrent(domain);
    edit.inventory().commit();edit.gas().commit(domain);domain.cellMacStage.commitMomentum(edit.momentum());
    if(audit!=null)domain.thermalState.restoreAuditLedger(audit);
    domain.gasState.pressureActive=true;domain.markCellInventoryChanged();domain.phaseOccupancyDirty=true;domain.pressureSystem.invalidateGeometry();
  }

  private static FluidDomainContracts.GasEdit editSpatial(HostFluidState domain,int node,String componentId,long nanos,double temperatureK,boolean extract,GasInventory supplied,double suppliedEnergy){
    int cell=node-1;var old=domain.gasState.spatial;
    if(cell<0||cell>=old.cellCount()||!domain.groupAvailable(cell))throw new IllegalArgumentException("Gas cell is unavailable");
    if(domain.activityGroups.pendingInGroups(List.of(new FluidDomainContracts.CellCandidate(cell,0))))throw new IllegalStateException("Gas transfer requires pending phase motion to be committed");
    if(domain.thermalState==null)throw new IllegalStateException("Gas transfer requires a bound thermal model");
    var registry=domain.gasState.network.registry();if(componentId!=null)registry.component(componentId);
    var model=new mmm.thermal.GasThermalModel(domain.thermalState.registry(),registry);
    GasInventory before=old.cells().get(cell),moved,after;double finalEnergy,carriedEnergy;
    if(extract){
      long available=componentId==null?before.standardVolumeNanos():before.component(componentId)==null?0:before.component(componentId).standardVolumeNanos();
      long actual=Math.min(nanos,available);if(actual==0)return new FluidDomainContracts.GasEdit(node,GasInventory.EMPTY,SignedDyadic.ZERO);
      var split=componentId==null?before.splitTotal(actual,domain.gasState.network.registry(),domain.chemistry==null?null:domain.chemistry.registry()):before.splitComponent(componentId,actual,domain.gasState.network.registry(),domain.chemistry==null?null:domain.chemistry.registry());moved=split.first();after=split.second();
      if(moved.isEmpty())return new FluidDomainContracts.GasEdit(node,GasInventory.EMPTY,SignedDyadic.ZERO);
      double[] parts=model.partitionEnergy(before,old.energyAt(cell),List.of(moved,after));carriedEnergy=parts[0];finalEnergy=parts[1];
    }else{
      moved=supplied != null ? supplied : componentId==null?registry.createAmbient(nanos):registry.createMixture(nanos,List.of(new GasRegistry.AmbientFraction(componentId,ExactRational.ONE)));
      if(moved.isEmpty())return new FluidDomainContracts.GasEdit(node,GasInventory.EMPTY,SignedDyadic.ZERO);
      if(supplied != null) {
        registry.requireCompatible(moved);
        for(var component:moved.components()) {
          var definition=registry.component(component.componentId());long mass=component.massResidualUnits();
          for(var species:component.species()) {
            long unit;
            if(domain.chemistry!=null) unit=domain.chemistry.registry().speciesMassUnits(domain.chemistry.registry().speciesHandle(species.speciesId()),1);
            else unit=definition.defaultSpeciesConcentrations().stream().filter(value->value.speciesId().equals(species.speciesId())).findFirst().orElseThrow().massUnitsPerQuantum();
            mass=Math.addExact(mass,Math.multiplyExact(unit,species.amountQuanta()));
          }
          if(mass!=component.massUnits())throw new IllegalArgumentException("Gas component mass differs from species and residual");
        }
        model.requireInventoryEnergy(moved,suppliedEnergy);
      }
      after=before.merge(moved);carriedEnergy=supplied != null ? suppliedEnergy : model.internalEnergyAtTemperature(moved,temperatureK);
      finalEnergy=SignedDyadic.of(old.energyAt(cell)).add(SignedDyadic.of(carriedEnergy)).toDouble();
    }
    model.requireInventoryEnergy(after,finalEnergy);mixture(domain,cell,after,finalEnergy);
    var cells=new java.util.ArrayList<>(old.cells());cells.set(cell,after);double[] energies=old.internalEnergy();energies[cell]=finalEnergy;
    var next=new mmm.fluid.air.SpatialGasState(old.grid(),old.registryFingerprint(),old.chemicalRegistryFingerprint(),cells,energies,
            extract?old.externalSource():old.externalSource().add(moved),extract?old.escapedSink().add(moved):old.escapedSink(),old.phaseSource(),old.phaseSink());
    var carried=SignedDyadic.of(carriedEnergy);var delta=SignedDyadic.of(finalEnergy).subtract(SignedDyadic.of(old.energyAt(cell)));
    var audit=domain.thermalState.audit().ledger();audit=extract?audit.withExternalSinkEnergy(carried).withRoundoff(delta.add(carried)):
            audit.withExternalSourceEnergy(carried).withRoundoff(delta.subtract(carried));
    var transfers=extract?List.of(new SpatialGasEdits.Transfer(cell,-1,moved.totalMassUnits())):List.<SpatialGasEdits.Transfer>of();
    var gasEdit=new SpatialGasEdits.Plan(old,next,SignedDyadic.ZERO,SignedDyadic.ZERO,SignedDyadic.ZERO,transfers);
    var edit=HostMixtureEdit.prepare(domain,domain.liquidCells.planEdits(List.of()),gasEdit,null);
    audit=edit.gas().applyAudit(audit).withMechanicalWork(edit.inventory().mechanicalHeat()).withRoundoff(edit.inventory().energyRoundoff());
    audit.expectedInventory();edit.inventory().requireCurrent();edit.gas().requireCurrent(domain);
    edit.inventory().commit();edit.gas().commit(domain);domain.cellMacStage.commitMomentum(edit.momentum());
    domain.thermalState.restoreAuditLedger(audit);domain.gasState.pressureActive=true;domain.markCellInventoryChanged();domain.pressureSystem.invalidateGeometry();
    return new FluidDomainContracts.GasEdit(node,moved,carried);
  }
}
