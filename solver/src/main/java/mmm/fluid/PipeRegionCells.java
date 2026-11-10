package mmm.fluid;

import java.util.ArrayList;
import java.util.List;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.SpatialGasState;
import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;

/** Edits private region candidates with the same mass, momentum and audit rules as external transfers. */
public final class PipeRegionCells {
    private PipeRegionCells() { }
    public record Cell(int index, double capacityM3, int support, List<LiquidCellInventory.Stock> liquid,
                       GasInventory gas, double gasEnergy) {
        public Cell { liquid=List.copyOf(liquid); }
    }
    public record Result(java.util.Map<Integer,SignedDyadic> removedKineticEnergyByCell) {
        public Result { removedKineticEnergyByCell=java.util.Map.copyOf(removedKineticEnergyByCell); }
        public SignedDyadic removedKineticEnergy() {
            var total=SignedDyadic.ZERO;for(var energy:removedKineticEnergyByCell.values())total=total.add(energy);return total;
        }
    }

    /** Physical energy used by coupled regional and pipe candidate checks. */
    public static double energy(HostFluidState domain) {
        domain.domainCapabilities.initializeSpatialGas();
        var owner=domain.captureAuthoritativeState().thermalOwner();double total=domain.liquidKineticEnergyJoules();
        for(var stock:domain.liquidCells.stocks())total+=stock.internalEnergy();
        for(int cell=0;cell<domain.cellCount;cell++) {
            total+=domain.gasState.spatial.energyAt(cell);
            total+=DomainGasInventory.mixture(domain,cell,domain.gasState.spatial.cells().get(cell),domain.gasState.spatial.energyAt(cell)).liquidElasticEnergyJ();
        }
        if(domain.granularState!=null)for(int i=0;i<domain.granularState.parcels.count();i++) {
            var parcel=domain.granularState.parcels.parcel(i);double x=parcel.velocityX(),y=parcel.velocityY(),z=parcel.velocityZ();
            total+=.5*parcel.mass()*(x*x+y*y+z*z);
        }
        if(owner!=null) {
            for(double energy:owner.copyActiveParcelEnergy())total+=energy;
            for(double energy:owner.copyDepositEnergy())total+=energy;
        }
        return total;
    }

    public static Cell capture(HostFluidState domain, int cell) {
        domain.domainCapabilities.initializeSpatialGas();
        if (cell<0 || cell>=domain.cellCount || !domain.groupAvailable(cell))
            throw new IllegalArgumentException("Pipe contact cell is unavailable");
        var supports=LiquidCellInventory.connectedSupports(domain.geometry.cutCells.mask(cell));
        if (supports.size()!=1) throw new IllegalStateException("Pipe contact cell has disconnected open components");
        double h=domain.config.cellSize();
        double capacity=(Integer.bitCount(supports.getFirst())*.125-Math.scalb((double)domain.occupiedSolidSvq()[cell],-24))*h*h*h;
        if (!(capacity>0)) throw new IllegalStateException("Pipe contact has no open capacity");
        var gas=domain.gasState.spatial;
        return new Cell(cell,capacity,supports.getFirst(),domain.liquidCells.stocksInCell(cell),gas.cells().get(cell),gas.energyAt(cell));
    }

    /** Replaces complete contacted cells on an unshared host candidate. */
    public static Result replace(HostFluidState domain, List<Cell> cells) {
        domain.domainCapabilities.initializeSpatialGas();
        if (domain.thermalState==null) throw new IllegalStateException("Pipe transfer requires thermal state");
        var oldGas=domain.gasState.spatial;
        var gases=new ArrayList<>(oldGas.cells());var energies=oldGas.internalEnergy();
        var sourceGas=oldGas.externalSource();var sinkGas=oldGas.escapedSink();
        var edits=new ArrayList<LiquidCellInventory.Edit>();
        var gasTransfers=new ArrayList<SpatialGasEdits.Transfer>();
        var seen=new java.util.HashSet<Integer>();
        var source=domain.chemistry==null?null:ChemicalTransferLedger.builder(domain.chemistry.registry());
        var sink=domain.chemistry==null?null:ChemicalTransferLedger.builder(domain.chemistry.registry());
        var imported=SignedDyadic.ZERO;var exported=SignedDyadic.ZERO;
        for(var cell:cells) {
            var before=capture(domain,cell.index());
            if(!seen.add(cell.index()) || cell.support()!=before.support()) throw new IllegalArgumentException("Duplicate or changed pipe cell");
            var changes=new java.util.TreeMap<LiquidCellInventory.Key,LiquidCellInventory.Stock>();
            for(var stock:before.liquid()) {
                changes.put(stock.key(),null);
                exported=exported.add(SignedDyadic.of(stock.internalEnergy()));
                if(sink!=null && stock.mixture()!=null) sink.add(domain.materialRegistry.material(stock.key().material()).id(),stock.volume().toDouble(),stock.massUnits(),stock.mixture());
            }
            for(var stock:cell.liquid()) {
                if(stock.key().cell()!=cell.index() || stock.key().supportMask()!=cell.support()) throw new IllegalArgumentException("Invalid pipe liquid support");
                changes.put(stock.key(),stock);
                imported=imported.add(SignedDyadic.of(stock.internalEnergy()));
                if(source!=null && stock.mixture()!=null) source.add(domain.materialRegistry.material(stock.key().material()).id(),stock.volume().toDouble(),stock.massUnits(),stock.mixture());
            }
            for(var entry:changes.entrySet()) edits.add(new LiquidCellInventory.Edit(entry.getKey(),entry.getValue()));
            exported=exported.add(SignedDyadic.of(before.gasEnergy()));
            imported=imported.add(SignedDyadic.of(cell.gasEnergy()));
            sourceGas=sourceGas.add(cell.gas());sinkGas=sinkGas.add(before.gas());
            long removed=Math.max(0,before.gas().totalMassUnits()-cell.gas().totalMassUnits());
            if(removed>0)gasTransfers.add(new SpatialGasEdits.Transfer(cell.index(),-1,removed));
            gases.set(cell.index(),cell.gas());energies[cell.index()]=cell.gasEnergy();
        }
        var nextGas=new SpatialGasState(oldGas.grid(),oldGas.registryFingerprint(),oldGas.chemicalRegistryFingerprint(),gases,energies,
                sourceGas,sinkGas,oldGas.phaseSource(),oldGas.phaseSink());
        var gasPlan=new SpatialGasEdits.Plan(oldGas,nextGas,SignedDyadic.ZERO,SignedDyadic.ZERO,SignedDyadic.ZERO,gasTransfers);
        var edit=HostMixtureEdit.prepare(domain,domain.liquidCells.planEdits(edits),gasPlan,null);
        var chemical=domain.chemistry==null?null:domain.chemistry.planAuditExchange(source.build(),sink.build());
        var audit=edit.gas().applyAudit(domain.thermalState.auditLedger().withExternalSourceEnergy(imported).withExternalSinkEnergy(exported))
                .withMechanicalWork(edit.inventory().mechanicalHeat());
        var actual=edit.inventory().energyChange();
        for(int cell=0;cell<oldGas.cellCount();cell++)actual=actual.add(SignedDyadic.of(edit.gas().after().energyAt(cell)).subtract(SignedDyadic.of(oldGas.energyAt(cell))));
        audit=audit.withRoundoff(actual.subtract(imported).add(exported).subtract(edit.inventory().mechanicalHeat()).subtract(edit.gas().gasWork()));
        audit.expectedInventory();
        edit.inventory().requireCurrent();edit.gas().requireCurrent(domain);
        if(chemical!=null)domain.chemistry.requireCurrentAuditExchange(chemical);
        var finalAudit=audit;
        domain.cellMacStage.publishMaterialChange(edit.inventory(),()->domain.cellMacStage.commitMomentum(edit.momentum()),()->{
            if(chemical!=null)domain.chemistry.commitAuditExchange(chemical);
            edit.gas().commit(domain);domain.thermalState.restoreAuditLedger(finalAudit);
        });
        domain.gasState.pressureActive=true;
        return new Result(edit.momentum().removedKineticEnergyByCell());
    }
}
