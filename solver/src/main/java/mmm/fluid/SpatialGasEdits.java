package mmm.fluid;

import java.util.List;
import mmm.fluid.air.SpatialGasState;
import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;

/** Cold host edits reconcile local gas compression with the same mixture volume equation. */
final class SpatialGasEdits {
    private SpatialGasEdits() { }
    record Plan(SpatialGasState before, SpatialGasState after, SignedDyadic gasWork, SignedDyadic escapedEnergy, SignedDyadic transferRoundoff, java.util.List<Transfer> transfers) {
        void requireCurrent(HostFluidState domain) {
            if (domain.gasState.spatial != before) throw new IllegalStateException("Spatial gas changed after edit planning");
        }
        void commit(HostFluidState domain) { domain.gasState.spatial=after; }
        Plan withHeat(HostFluidState domain, java.util.Map<Integer,SignedDyadic> heat) {
            if(heat.isEmpty())return this;
            double[] energies=after.internalEnergy();var work=gasWork;var rounding=transferRoundoff;
            for(var entry:heat.entrySet()){
                int cell=entry.getKey();var exact=SignedDyadic.of(energies[cell]).add(entry.getValue());
                energies[cell]=exact.toDouble();
                domain.domainCapabilities.spatialGasModel().requireInventoryEnergy(after.cells().get(cell),energies[cell]);
                work=work.add(entry.getValue());rounding=rounding.add(SignedDyadic.of(energies[cell]).subtract(exact));
            }
            var next=new SpatialGasState(after.sizeX(),after.sizeY(),after.sizeZ(),after.registryFingerprint(),after.chemicalRegistryFingerprint(),
                    after.cells(),energies,after.externalSource(),after.escapedSink(),after.phaseSource(),after.phaseSink());
            return new Plan(before,next,work,escapedEnergy,rounding,transfers);
        }
        mmm.thermal.ThermalAuditLedger applyAudit(mmm.thermal.ThermalAuditLedger audit) {
            return audit.withMechanicalWork(gasWork).withExternalSinkEnergy(escapedEnergy).withRoundoff(transferRoundoff);
        }
    }

    record Transfer(int source, int target, long massUnits) { }
    static List<Transfer> ventAll(SpatialGasState state) {
        var transfers=new java.util.ArrayList<Transfer>();
        for(int cell=0;cell<state.cellCount();cell++){
            long mass=state.cells().get(cell).totalMassUnits();
            if(mass>0)transfers.add(new Transfer(cell,-1,mass));
        }
        return List.copyOf(transfers);
    }

    static Plan prepare(HostFluidState domain,LiquidCellInventory future,long[] futureOccupied,byte[] futureMasks,List<Integer> changed) {
        var state=domain.gasState.spatial;if(state==null)return null;
        long[] oldOccupied=domain.occupiedSolidSvq();
        domain.pressureSystem.prepareGeometry();
        var oldPhase=domain.pressureSystem.phaseGeometry();
        var oldState=new mmm.thermal.MixtureVolumeModel.State[domain.cellCount];
        double[] free=new double[domain.cellCount], scale=new double[domain.cellCount];
        boolean[] displaced=new boolean[domain.cellCount], affected=new boolean[domain.cellCount];
        double h=domain.config.cellSize(),h3=h*h*h;
        for(int cell=0;cell<domain.cellCount;cell++) {
            var inventory=state.cells().get(cell);
            var prior=DomainGasInventory.mixture(domain,cell,inventory,state.energyAt(cell),domain.liquidCells,oldOccupied,null);
            oldState[cell]=prior;scale[cell]=prior.liquidScale();
            free[cell]=Math.max(0,capacity(domain,cell,futureOccupied,futureMasks)*h3-future.exactCellVolume(cell).toDouble()*h3*scale[cell]);
        }
        for(int cell:changed){
            affected[cell]=true;
            displaced[cell]=!state.cells().get(cell).isEmpty() && free[cell]<oldState[cell].gasVolumeM3();
        }
        var futurePhase=futurePhase(domain,future,futureOccupied,futureMasks,scale);
        var connectivity=connectedReceivers(domain,oldPhase,futurePhase,displaced,free,oldState);
        var inventories=new java.util.ArrayList<>(state.cells());
        double[] energy=state.internalEnergy(), volume=new double[domain.cellCount];
        for(int cell=0;cell<domain.cellCount;cell++) volume[cell]=oldState[cell].gasVolumeM3();
        var movedInventories=new java.util.ArrayList<mmm.fluid.air.GasInventory>();
        var movedEnergy=new java.util.ArrayList<Double>();var movedVolume=new java.util.ArrayList<Double>();
        var transfers=new java.util.ArrayList<Transfer>();
        var model=domain.domainCapabilities.spatialGasModel();
        SignedDyadic escaped=SignedDyadic.ZERO,roundoff=SignedDyadic.ZERO,gasWork=SignedDyadic.ZERO;
        for(int cell:changed)if(displaced[cell]){
            var source=state.cells().get(cell);double initialVolume=oldState[cell].gasVolumeM3();
            var receivers=connectivity.groups().get(connectivity.labels()[cell]);
            int[] targets=receivers.targets();double[] areas=receivers.volumes();int count=targets.length;
            if(count==0){
                if(capacity(domain,cell,futureOccupied,futureMasks)<=0)
                    throw new GasTopologyBlockedException("Gas has no open neighboring cell or exterior at cell "+cell);
                continue;
            }
            double retained=Math.min(1.0,Math.max(0.0,free[cell]/initialVolume)),totalArea=0;
            for(int i=0;i<count;i++)totalArea+=areas[i];
            double[] weights=new double[count+1];weights[0]=retained;
            for(int i=0;i<count;i++)weights[i+1]=(1-retained)*areas[i]/totalArea;
            var portions=source.partition(mmm.fluid.air.GasPartitionWeights.of(weights),domain.gasState.network.registry(),domain.chemistry==null?null:domain.chemistry.registry());
            double[] energies=model.partitionEnergy(source,state.energyAt(cell),portions);
            roundoff=roundoff.add(SignedDyadic.sum(energies).subtract(SignedDyadic.of(state.energyAt(cell))));
            inventories.set(cell,portions.get(0));energy[cell]=energies[0];
            double retainedVolume=initialVolume*portions.get(0).standardVolumeNanos()/source.standardVolumeNanos();
            volume[cell]=retainedVolume;double remainingVolume=initialVolume-retainedVolume;
            int last=-1;for(int i=1;i<portions.size();i++)if(!portions.get(i).isEmpty())last=i;
            for(int i=1;i<portions.size();i++)if(!portions.get(i).isEmpty()){
                var part=portions.get(i);double partVolume=i==last?remainingVolume:initialVolume*part.standardVolumeNanos()/source.standardVolumeNanos();
                remainingVolume-=partVolume;int target=targets[i-1];
                transfers.add(new Transfer(cell,target,part.totalMassUnits()));movedInventories.add(part);movedEnergy.add(energies[i]);movedVolume.add(partVolume);
            }
        }
        var sink=state.escapedSink();
        for(int i=0;i<transfers.size();i++){
            var transfer=transfers.get(i);var moved=movedInventories.get(i);double u=movedEnergy.get(i);
            int target=transfer.target();
            if(target<0){sink=sink.add(moved);escaped=escaped.add(SignedDyadic.of(u));continue;}
            affected[target]=true;inventories.set(target,inventories.get(target).merge(moved));
            double before=energy[target];energy[target]+=u;
            roundoff=roundoff.add(SignedDyadic.of(energy[target]).subtract(SignedDyadic.of(before)).subtract(SignedDyadic.of(u)));
            volume[target]+=movedVolume.get(i);
        }
        for(int cell=0;cell<domain.cellCount;cell++)if(affected[cell]){
            var inventory=inventories.get(cell);double oldEnergy=energy[cell],finalEnergy=oldEnergy;
            if(!inventory.isEmpty()) {
                double cap=capacity(domain,cell,futureOccupied,futureMasks)*h3;
                if(!(cap>0))throw new GasTopologyBlockedException("Gas has no remaining cell capacity at cell "+cell);
                var thermal=model.state(inventory,oldEnergy);
                double gasConstant=mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA*inventory.standardVolumeNanos()*1e-9
                        /mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K;
                var work=mmm.thermal.MixtureVolumeModel.gasWorkState(cap,future.exactCellVolume(cell).toDouble()*h3,
                        gasConstant,thermal.heatCapacityJPerK(),thermal.temperatureK(),volume[cell]);
                finalEnergy=model.internalEnergyAtTemperature(inventory,work.temperatureK());
                model.requireInventoryEnergy(inventory,finalEnergy);
            }
            energy[cell]=finalEnergy;gasWork=gasWork.add(SignedDyadic.of(finalEnergy).subtract(SignedDyadic.of(oldEnergy)));
        }
        var next=new SpatialGasState(state.sizeX(),state.sizeY(),state.sizeZ(),state.registryFingerprint(),state.chemicalRegistryFingerprint(),
                inventories,energy,state.externalSource(),sink,state.phaseSource(),state.phaseSink());
        return new Plan(state,next,gasWork,escaped,roundoff,List.copyOf(transfers));
    }
    private static FlipPressurePhaseGeometry futurePhase(HostFluidState domain,LiquidCellInventory future,long[] occupied,byte[] masks,double[] scale){
        double[] pore=PhaseOccupancyTransport.poreVolumes(domain,occupied,masks),liquid=new double[pore.length];
        for(int cell=0;cell<domain.cellCount;cell++){
            int mask=masks==null?domain.geometry.cutCells.mask(cell):Byte.toUnsignedInt(masks[cell]);
            for(int support:CellRemapMicroTopology.components(mask)){
                double cap=0,amount=0;
                for(int bit=0;bit<8;bit++)if((support&1<<bit)!=0)cap+=pore[8*cell+bit];
                for(var stock:future.stocksInCell(cell))if(stock.key().supportMask()==support)amount+=stock.volumeFraction()*scale[cell];
                double fraction=cap>0?Math.clamp(amount/cap,0,1):0;
                for(int bit=0;bit<8;bit++)if((support&1<<bit)!=0)liquid[8*cell+bit]=fraction*pore[8*cell+bit];
            }
        }
        var cut=domain.geometry.cutCells;
        if(masks!=null){cut=new FlipCutCellGeometry(domain.sizeX,domain.sizeY,domain.sizeZ);cut.restoreMicroMasks(masks);}
        var phase=new FlipPressurePhaseGeometry(domain.sizeX,domain.sizeY,domain.sizeZ);
        phase.rebuildOccupancy(new mmm.inventory.PhaseOccupancyState(liquid),cut,pore,null);return phase;
    }
    private static double dryFace(HostFluidState domain,FlipPressurePhaseGeometry oldPhase,FlipPressurePhaseGeometry futurePhase,int cell,int target,int side){
        int axis=side/2,face=side&1;double area=0;
        for(int a=0;a<2;a++)for(int b=0;b<2;b++){
            int bit=face<<axis|a<<((axis+1)%3)|b<<((axis+2)%3);
            if((oldPhase.collisionMask(cell)&1<<bit)!=0)continue;
            if(target>=0 && (futurePhase.collisionMask(target)&1<<(bit^(1<<axis)))!=0)continue;
            area+=GasFaceIntersection.area(oldPhase,cell,futurePhase,target,axis,face,1-face,.5*a,.5*b,.5);
        }
        return area;
    }
    private record Receivers(int[] targets,double[] volumes) { }
    private record Connectivity(int[] labels,List<Receivers> groups) { }
    private static Connectivity connectedReceivers(HostFluidState domain,FlipPressurePhaseGeometry oldPhase,
            FlipPressurePhaseGeometry futurePhase,boolean[] displaced,double[] free,mmm.thermal.MixtureVolumeModel.State[] oldState){
        int n=domain.cellCount;int[] labels=new int[n],queue=new int[n];java.util.Arrays.fill(labels,-1);
        var groups=new java.util.ArrayList<Receivers>();
        for(int start=0;start<n;start++)if(labels[start]<0){
            int group=groups.size(),head=0,tail=0;queue[tail++]=start;labels[start]=group;double demand=0;boolean exterior=false;
            while(head<tail){
                int cell=queue[head++];var phase=displaced[cell]?oldPhase:futurePhase;
                if(displaced[cell])demand+=Math.max(0,oldState[cell].gasVolumeM3()-free[cell]);
                for(int side=0;side<6;side++){
                    int next=neighbor(domain,cell,side);
                    if(next<0){if(!domain.gasState.sealedDomainBoundary && dryFace(domain,phase,futurePhase,cell,-1,side)>0)exterior=true;continue;}
                    if(labels[next]>=0)continue;
                    var nextPhase=displaced[next]?oldPhase:futurePhase;
                    if(dryFace(domain,phase,nextPhase,cell,next,side)>0){labels[next]=group;queue[tail++]=next;}
                }
            }
            var targets=new java.util.ArrayList<Integer>();var volumes=new java.util.ArrayList<Double>();double selected=0;
            if(demand>0)for(int i=0;i<tail && selected<demand;i++){
                int cell=queue[i];if(displaced[cell]||!(free[cell]>0))continue;
                targets.add(cell);volumes.add(free[cell]);selected+=free[cell];
            }
            if(targets.isEmpty()&&exterior){targets.add(-1);volumes.add(Math.max(demand,1e-300));}
            groups.add(new Receivers(targets.stream().mapToInt(Integer::intValue).toArray(),volumes.stream().mapToDouble(Double::doubleValue).toArray()));
        }
        return new Connectivity(labels,List.copyOf(groups));
    }
    private static int neighbor(HostFluidState domain,int cell,int side){
        int x=cell%domain.sizeX,y=cell/domain.sizeX%domain.sizeY,z=cell/(domain.sizeX*domain.sizeY),step=(side&1)==0?-1:1;
        switch(side/2){case 0->x+=step;case 1->y+=step;default->z+=step;}
        return x<0||y<0||z<0||x>=domain.sizeX||y>=domain.sizeY||z>=domain.sizeZ?-1:x+domain.sizeX*(y+domain.sizeY*z);
    }
    private static double capacity(HostFluidState domain,int cell,long[] occupied,byte[] masks){
        int mask=masks==null?domain.geometry.cutCells.mask(cell):Byte.toUnsignedInt(masks[cell]);
        return Integer.bitCount(~mask&255)*.125-Math.scalb((double)occupied[cell],-24);
    }
}
