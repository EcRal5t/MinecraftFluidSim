package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.thermal.ThermalAuditLedger;
import mmm.thermal.ThermalStateFunction;
import mmm.units.Mass;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Actual K/PE and cavity work close once on each body coupled by pressure, mass transfer and contacts. */
final class MechanicalEnergySettlement {
    private final FluidDomain domain;
    private final double[] liquidExchange;
    private double[] granularExchange=new double[0],granularPotentialExchange=new double[0];
    private boolean hasLiquidExchange,hasGranularExchange;
    private double stepLiquidForce,stepGranularForce,stepPotential,stepGas,stepCorrection,stepAbsoluteCorrection;
    private boolean captureSpatialObservation,spatialObserved;
    private double representation,resolvedPotential,initialRepresentationBound,finalRepresentationBound;
    void beginStep(boolean captureSpatialObservation){
        stepLiquidForce=stepGranularForce=stepPotential=stepGas=stepCorrection=stepAbsoluteCorrection=0;
        representation=resolvedPotential=initialRepresentationBound=finalRepresentationBound=0;
        this.captureSpatialObservation=captureSpatialObservation;spatialObserved=false;
    }
    FluidDomain.MechanicalCorrection correction(){return new FluidDomain.MechanicalCorrection(
            stepLiquidForce,stepGranularForce,stepPotential,stepGas,stepCorrection,stepAbsoluteCorrection,
            spatialObserved,representation,stepCorrection-representation,resolvedPotential,
            initialRepresentationBound,finalRepresentationBound);}

    private record SpatialPotential(double cell,double plic,double bound) { }
    /** Read-only geometry diagnostic; physical inventory continues to use its declared cell-centre PE. */
    private SpatialPotential spatialPotential(LiquidCellInventory inventory,FlipPressurePhaseGeometry phase){
        double cellEnergy=0,plicEnergy=0,bound=0,h=domain.config.cellSize(),g=domain.config.gravity();
        for(var stock:inventory.physicalStocks()){
            int cell=stock.key().cell(),support=stock.key().supportMask();double volume=0,moment=0;
            for(int micro=0;micro<8;micro++)if((support&1<<micro)!=0){
                double fraction=phase.liquidFractionExact(cell,micro);if(fraction<=0)continue;
                double wet=.125*fraction,pore=phase.componentPoreScale(cell,micro,1);
                double first=fraction>=1?wet*(.5*(micro>>1&1)+.25):MicroBoxPlaneVolume.verticalMoment(
                        phase.planeNormal(cell,micro,0),phase.planeNormal(cell,micro,1),
                        phase.planeNormal(cell,micro,2),phase.planeOffset(cell,micro),micro);
                volume+=wet*pore;moment+=first*pore;
            }
            double weight=stock.massKilograms()*g*h,y=cell/domain.sizeX%domain.sizeY;
            cellEnergy+=weight*(y+.5);
            plicEnergy+=weight*(y+(volume>0?moment/volume:.5));
            // In a full open cube the extreme centroid offset is (1-fill)/2.
            // A cut support retains the full half-cell geometric displacement range.
            bound+=weight*.5*(support==255?1-Math.clamp(stock.volumeFraction(),0,1):1);
        }
        return new SpatialPotential(cellEnergy,plicEnergy,bound);
    }
    private void observeSpatialExchange(LiquidCellInventory future,GasTopologyCoordinator.GasTopologyPlan gas){
        if(!captureSpatialObservation||gas==null||gas.source()==null)return;
        var before=spatialPotential(domain.liquidCells,gas.source().phase());
        var after=spatialPotential(future,gas.frame().phase());
        representation+=(before.cell()-before.plic())-(after.cell()-after.plic());
        resolvedPotential+=after.plic()-before.plic();
        if(!spatialObserved)initialRepresentationBound=before.bound();
        finalRepresentationBound=after.bound();spatialObserved=true;
    }

    MechanicalEnergySettlement(FluidDomain domain){
        this.domain=domain;
        liquidExchange=new double[domain.mac.velocityX.length+domain.mac.velocityY.length+domain.mac.velocityZ.length];
    }
    void beginLiquidForceStep(){
        if(hasLiquidExchange)Arrays.fill(liquidExchange,0);
        hasLiquidExchange=false;
    }
    void beginGranularForceStep(int count){
        if(granularExchange.length<count){granularExchange=new double[count];granularPotentialExchange=new double[count];}
        else if(hasGranularExchange){Arrays.fill(granularExchange,0);Arrays.fill(granularPotentialExchange,0);}
        hasGranularExchange=false;
    }
    void recordLiquidExchange(int face,double joules){
        if(joules==0)return;
        if(!Double.isFinite(joules))throw new IllegalStateException("Nonfinite liquid mechanical exchange");
        liquidExchange[face]+=joules;hasLiquidExchange=true;
    }
    void recordGranularExchange(int parcel,double kinetic,double potential){
        if(domain.thermalState==null||(kinetic==0&&potential==0))return;
        if(!(Double.isFinite(kinetic)&&Double.isFinite(potential)))throw new IllegalStateException("Nonfinite granular mechanical exchange");
        granularExchange[parcel]+=kinetic+potential;granularPotentialExchange[parcel]+=potential;hasGranularExchange=true;
    }
    void clear(){beginLiquidForceStep();beginGranularForceStep(0);}

    /** Keeps the complete future U private until liquid, gas and granular plans pass preflight. */
    final class Plan {
        private final LiquidCellInventory.Plan inventory;
        private final Map<Integer,SignedDyadic> grains;
        private final long granularRevision;
        Plan(LiquidCellInventory.Plan inventory,Map<Integer,SignedDyadic> grains){
            this.inventory=inventory;this.grains=Map.copyOf(grains);
            granularRevision=grains.isEmpty()?-1:domain.thermalState.thermalRevision().granular();
        }
        LiquidCellInventory.Plan inventory(){return inventory;}
        ThermalAuditLedger auditAfter(ThermalAuditLedger audit){
            if(audit==null)return null;
            for(var entry:grains.entrySet()){
                var before=SignedDyadic.of(domain.thermalState.granularColumn().activeEnergy(entry.getKey()));
                var exact=before.add(entry.getValue());
                audit=audit.withMechanicalWork(entry.getValue()).withRoundoff(SignedDyadic.of(exact.toDouble()).subtract(exact));
            }
            return audit;
        }
        void requireCurrent(){inventory.requireCurrent();requireGranularCurrent();}
        private void requireGranularCurrent(){
            if(!grains.isEmpty()&&domain.thermalState.thermalRevision().granular()!=granularRevision)
                throw new IllegalStateException("Granular energy changed after common mechanical preparation");
        }
        void commitGranular(){
            requireGranularCurrent();
            for(var entry:grains.entrySet())domain.thermalState.addGranularMechanicalHeat(entry.getKey(),entry.getValue());
        }
    }

    Plan prepare(LiquidCellInventory.Plan inventory,GasTopologyCoordinator.GasTopologyPlan gas){
        return prepare(inventory,gas,true);
    }
    Plan prepareDisplacement(LiquidCellInventory.Plan inventory,GasTopologyCoordinator.GasTopologyPlan gas){
        return prepare(inventory,gas,false);
    }
    private Plan prepare(LiquidCellInventory.Plan inventory,GasTopologyCoordinator.GasTopologyPlan gas,boolean forces){
        if(!hasMechanicalWork(inventory,gas,forces))return new Plan(inventory,Map.of());
        observeSpatialExchange(inventory.future(),gas);
        GasRuntimeGeometry source=gas==null?domain.gasState.frame:gas.source();
        int gasBase=domain.cellCount,particleBase=gasBase+(source==null?0:source.nodeCount())+1;
        int parcels=!forces||domain.granularState==null?0:domain.granularState.parcels.count();
        int[] body=mechanicalBodies(source,inventory,particleBase,parcels);
        double[] numericalWork=new double[body.length];
        if(forces&&hasLiquidExchange)collectLiquidForceExchange(body,numericalWork);
        collectTransportPotential(inventory,body,numericalWork);
        if(forces&&hasGranularExchange)for(int p=0;p<parcels;p++)if(granularExchange[p]!=0)
            numericalWork[mechanicalRoot(body,particleBase+p)]+=granularExchange[p];
        recordContributions(inventory,gas,forces,parcels);
        var exchanges=collectBodyExchanges(body,numericalWork,gas);
        return prepareBodyHeat(inventory,gas,body,numericalWork,exchanges,particleBase);
    }
    private boolean hasMechanicalWork(LiquidCellInventory.Plan inventory,GasTopologyCoordinator.GasTopologyPlan gas,boolean forces){
        boolean hasWork=forces&&(hasLiquidExchange||hasGranularExchange);
        if(gas!=null)for(var work:gas.workBySource())hasWork|=work.signum()!=0;
        if(!hasWork)for(var transfer:inventory.transfers())if(transfer.massUnits()>0
                &&transfer.source().cell()/domain.sizeX%domain.sizeY!=transfer.target().cell()/domain.sizeX%domain.sizeY){
            hasWork=domain.config.gravity()!=0;break;
        }
        return hasWork;
    }
    private void collectLiquidForceExchange(int[] body,double[] numericalWork){
        int[] singleBody=new int[domain.cellCount];Arrays.fill(singleBody,-2);
        for(var stock:domain.liquidCells.physicalStocks()){
            int cell=stock.key().cell(),root=mechanicalRoot(body,supportVertex(stock.key()));
            if(singleBody[cell]==-2)singleBody[cell]=root;else if(singleBody[cell]!=root)singleBody[cell]=-1;
        }
        var faces=domain.pressureSystem.faces;
        for(int face=0;face<liquidExchange.length;face++)if(liquidExchange[face]!=0){
            int a=faces.firstCell[face],b=faces.secondCell[face];
            int ra=a<0?-2:singleBody[a],rb=b<0?-2:singleBody[b];
            double exchange=liquidExchange[face];
            if(ra>=0&&(ra==rb||rb==-2))numericalWork[ra]+=exchange;
            else if(rb>=0&&ra==-2)numericalWork[rb]+=exchange;
            else splitFaceExchange(body,numericalWork,a,b,exchange);
        }
    }
    private void collectTransportPotential(LiquidCellInventory.Plan inventory,int[] body,double[] numericalWork){
        for(var transfer:inventory.transfers())if(transfer.massUnits()>0){
            int from=transfer.source().cell()/domain.sizeX%domain.sizeY,to=transfer.target().cell()/domain.sizeX%domain.sizeY;
            double potential=-Mass.kilograms(transfer.massUnits())*domain.config.gravity()*domain.config.cellSize()*(to-from);
            if(potential!=0)numericalWork[mechanicalRoot(body,supportVertex(transfer.target()))]+=potential;
        }
    }
    private void recordContributions(LiquidCellInventory.Plan inventory,GasTopologyCoordinator.GasTopologyPlan gas,boolean forces,int parcels){
        double liquidForce=0,granularForce=0,potentialWork=0,gasWork=0;
        if(forces){for(double value:liquidExchange)liquidForce+=value;for(int p=0;p<parcels;p++)granularForce+=granularExchange[p];}
        for(var transfer:inventory.transfers())potentialWork-=Mass.kilograms(transfer.massUnits())*domain.config.gravity()*domain.config.cellSize()
            *(transfer.target().cell()/domain.sizeX%domain.sizeY-transfer.source().cell()/domain.sizeX%domain.sizeY);
        if(gas!=null)for(var work:gas.workBySource())gasWork-=work.toDouble();
        stepLiquidForce+=liquidForce;stepGranularForce+=granularForce;stepPotential+=potentialWork;stepGas+=gasWork;
    }
    private TreeMap<Integer,SignedDyadic> collectBodyExchanges(int[] body,double[] numericalWork,GasTopologyCoordinator.GasTopologyPlan gas){
        int gasBase=domain.cellCount;
        var exchanges=new TreeMap<Integer,SignedDyadic>();
        for(int root=0;root<numericalWork.length;root++)if(numericalWork[root]!=0)
            exchanges.put(root,SignedDyadic.of(numericalWork[root]));
        if(gas!=null)for(int node=1;node<gas.workBySource().length;node++)if(gas.workBySource()[node].signum()!=0)
            exchanges.merge(mechanicalRoot(body,gasBase+node),gas.workBySource()[node].negate(),SignedDyadic::add);
        for(var value:exchanges.values()){stepCorrection+=value.toDouble();stepAbsoluteCorrection+=Math.abs(value.toDouble());}
        return exchanges;
    }
    private Plan prepareBodyHeat(LiquidCellInventory.Plan inventory,GasTopologyCoordinator.GasTopologyPlan gas,
                                 int[] body,double[] numericalWork,TreeMap<Integer,SignedDyadic> exchanges,int particleBase){
        int parcels=body.length-particleBase;
        var liquids=new TreeMap<Integer,ArrayList<LiquidCellInventory.Stock>>();
        for(var stock:inventory.future().physicalStocks()){
            int root=mechanicalRoot(body,supportVertex(stock.key()));
            if(exchanges.containsKey(root))liquids.computeIfAbsent(root,ignored->new ArrayList<>()).add(stock);
        }
        var grains=new TreeMap<Integer,ArrayList<Integer>>();
        if(domain.thermalState!=null)for(int p=0;p<parcels;p++){
            int root=mechanicalRoot(body,particleBase+p);
            if(exchanges.containsKey(root))grains.computeIfAbsent(root,ignored->new ArrayList<>()).add(p);
        }
        var liquidHeat=new TreeMap<LiquidCellInventory.Key,SignedDyadic>();
        var grainHeat=new TreeMap<Integer,SignedDyadic>();
        for(var entry:exchanges.entrySet()){
            var exchange=entry.getValue();if(exchange.signum()==0)continue;
            var targets=liquids.getOrDefault(entry.getKey(),new ArrayList<>());
            var active=grains.getOrDefault(entry.getKey(),new ArrayList<>());
            if(targets.isEmpty()&&active.isEmpty())throw new IllegalStateException(
                    "Mechanical body has no future caloric carrier: body="+entry.getKey()+", exchange="+exchange);
            SignedDyadic[] shares;
            if(domain.thermalState==null)shares=massShares(targets,exchange);
            else{
                var recipients=mechanicalRecipients(targets,active);
                try{shares=ThermalStateFunction.partitionMechanicalEnergy(recipients,exchange);}
                catch(IllegalArgumentException failure){
                    failure.addSuppressed(new IllegalStateException("Mechanical body="+entry.getKey()+", liquidStocks="+targets.size()
                            +", granularParcels="+active.size()+", exchange="+exchange
                            +failureContributions(body,entry.getKey(),particleBase,parcels,numericalWork,inventory,gas)));throw failure;
                }
            }
            int ordinal=0;
            for(var stock:targets){var part=shares[ordinal++];if(part.signum()!=0)liquidHeat.merge(stock.key(),part,SignedDyadic::add);}
            for(int p:active){var part=shares[ordinal++];if(part.signum()!=0)grainHeat.merge(p,part,SignedDyadic::add);}
        }
        return new Plan(liquidHeat.isEmpty()?inventory:inventory.withMechanicalHeatByStock(liquidHeat),grainHeat);
    }
    private List<ThermalStateFunction.MechanicalRecipient> mechanicalRecipients(List<LiquidCellInventory.Stock> targets,List<Integer> active){
        var recipients=new ArrayList<ThermalStateFunction.MechanicalRecipient>(targets.size()+active.size());
        var thermal=domain.thermalState;var state=thermal.stateFunction();
        for(var stock:targets){
            int material=thermal.thermalMaterialHandleForFluid(stock.key().material());
            double mass=stock.massKilograms();
            double base=state.baseEnergy(material,mass,stock.mixture(),domain.liquidCells.chemicalRegistry());
            recipients.add(new ThermalStateFunction.MechanicalRecipient(thermal.registry().material(material),mass,base,stock.internalEnergy()));
        }
        for(int p:active){
            var parcel=domain.granularState.parcels.parcel(p);
            int material=thermal.thermalMaterialHandleForGranular(parcel.materialHandle());
            double base=state.baseEnergy(material,parcel.mass(),domain.thermalMarkers.solidComposition(parcel.solidCompositionHandle()));
            recipients.add(new ThermalStateFunction.MechanicalRecipient(thermal.registry().material(material),parcel.mass(),base,
                    thermal.granularColumn().activeEnergy(p)));
        }
        return recipients;
    }
    /** Only a failed caloric solve gathers this operator decomposition; normal steps keep no statistics. */
    private String failureContributions(int[] body,int root,int particleBase,int parcels,double[] numericalWork,
                                       LiquidCellInventory.Plan inventory,GasTopologyCoordinator.GasTopologyPlan gas){
        double route=0,grain=0,grainPotential=0;
        for(var transfer:inventory.transfers())if(transfer.massUnits()>0&&mechanicalRoot(body,supportVertex(transfer.target()))==root){
            int from=transfer.source().cell()/domain.sizeX%domain.sizeY,to=transfer.target().cell()/domain.sizeX%domain.sizeY;
            route-=Mass.kilograms(transfer.massUnits())*domain.config.gravity()*domain.config.cellSize()*(to-from);
        }
        if(hasGranularExchange)for(int p=0;p<parcels;p++)if(mechanicalRoot(body,particleBase+p)==root){
            grain+=granularExchange[p];grainPotential+=granularPotentialExchange[p];
        }
        var gasCounter=SignedDyadic.ZERO;int largestGas=-1;
        if(gas!=null)for(int node=1;node<gas.workBySource().length;node++)if(mechanicalRoot(body,domain.cellCount+node)==root){
            gasCounter=gasCounter.subtract(gas.workBySource()[node]);
            if(largestGas<0||Math.abs(gas.workBySource()[node].toDouble())>Math.abs(gas.workBySource()[largestGas].toDouble()))largestGas=node;
        }
        int peak=-1;
        for(int face=0;face<liquidExchange.length;face++)if(liquidExchange[face]!=0
                &&(peak<0||Math.abs(liquidExchange[face])>Math.abs(liquidExchange[peak])))peak=face;
        String peakFace="none";
        if(peak>=0){
            var faces=domain.pressureSystem.faces;int axis=faces.axis[peak],local=faces.localFace[peak];
            double velocity=switch(axis){case 0->domain.mac.velocityX[local];case 1->domain.mac.velocityY[local];default->domain.mac.velocityZ[local];};
            peakFace="{face="+peak+", axis="+axis+", cells="+faces.firstCell[peak]+"/"+faces.secondCell[peak]
                    +", massKg="+faces.faceMass(peak)+", velocityAfter="+velocity+", exchangeJ="+liquidExchange[peak]+"}";
        }
        String gasSource=largestGas<0?"none":"{node="+largestGas+", oldVolumeM3="+domain.gasState.network.availableVolume(largestGas)
                +", oldGaugePressurePa="+domain.pressureBoundaryView.gasPressureOfNode(largestGas)+", actualWorkJ="+gas.workBySource()[largestGas].toDouble()+"}";
        return ", liquidForceExchangeJ="+(numericalWork[root]-route-grain)
                +", granularKineticExchangeJ="+(grain-grainPotential)+", granularPotentialExchangeJ="+grainPotential
                +", transportPotentialExchangeJ="+route+", gasCounterWorkJ="+gasCounter.toDouble()
                +", largestGasSource="+gasSource+", globalPeakForceFace="+peakFace;
    }
    private static SignedDyadic[] massShares(List<LiquidCellInventory.Stock> targets,SignedDyadic exchange){
        var shares=new SignedDyadic[targets.size()];double total=0;int largest=0;
        for(int i=0;i<targets.size();i++){total+=targets.get(i).massUnits();
            if(targets.get(i).massUnits()>targets.get(largest).massUnits())largest=i;}
        var remaining=exchange;
        for(int i=0;i<shares.length;i++)if(i!=largest){
            shares[i]=SignedDyadic.of(exchange.toDouble()*(targets.get(i).massUnits()/total));remaining=remaining.subtract(shares[i]);
        }
        shares[largest]=remaining;return shares;
    }
    private void splitFaceExchange(int[] body,double[] exchanges,int a,int b,double exchange){
        double total=0;LiquidCellInventory.Stock largest=null;
        for(int cell:new int[]{a,b})if(cell>=0)for(var stock:domain.liquidCells.stocksInCell(cell))if(stock.massUnits()>0){
            total+=stock.massUnits();if(largest==null||stock.massUnits()>largest.massUnits())largest=stock;
        }
        if(largest==null)throw new IllegalStateException("Mechanical face exchange has no source liquid carrier");
        var remaining=exchange;
        for(int cell:new int[]{a,b})if(cell>=0)for(var stock:domain.liquidCells.stocksInCell(cell))if(stock.massUnits()>0&&stock!=largest){
            double part=exchange*(stock.massUnits()/total);remaining-=part;
            exchanges[mechanicalRoot(body,supportVertex(stock.key()))]+=part;
        }
        exchanges[mechanicalRoot(body,supportVertex(largest.key()))]+=remaining;
    }
	/** Reuses the solved wet microfaces, fixed supports and accepted routes; no new geometric intersections. */
	private int[] mechanicalBodies(GasRuntimeGeometry source,LiquidCellInventory.Plan inventory,int particleBase,int parcels){
		int gasBase=domain.cellCount;int[] body=new int[particleBase+parcels];
		for(int i=0;i<body.length;i++)body[i]=i;
		for(int p=0;p<parcels;p++)joinMechanical(body,particleBase+p,
				particleBase+domain.granularMechanicalStage.plannedContactComponent(p));
		if(source!=null)for(var component:source.capacity().components()){
			int node=source.topology().nodeOfComponent(component.cell(),component.supportMask());
			if(node>0)joinMechanical(body,component.cell(),gasBase+node);
		}
		var faces=domain.pressureSystem.faces;var cut=domain.geometry.cutCells;
		for(int face=0;face<faces.faceCount;face++){
			if(!(faces.inverseMass[face]>0))continue;
			int a=faces.firstCell[face],b=faces.secondCell[face],axis=faces.axis[face];if(a<0)continue;
			int maskA=cut.mask(a),maskB=cut.mask(b);
			for(int micro=0;micro<8;micro++)if((micro>>>axis&1)!=0){
				int peer=micro^(1<<axis),port=(micro>>>((axis+1)%3)&1)+2*(micro>>>((axis+2)%3)&1);
				if((maskA&1<<micro)!=0||(maskB&1<<peer)!=0||!(faces.pressurePortArea(face,port)>0))continue;
				joinMechanical(body,a,b);
			}
		}
		for(var transfer:inventory.transfers())if(transfer.massUnits()>0)
			joinMechanical(body,supportVertex(transfer.source()),supportVertex(transfer.target()));
		var solids=domain.pressureSystem.solidFaces;
		for(int p=0;p<parcels;p++)for(int axis=0;axis<3;axis++)for(int slot=0;slot<solids.parcelFaceCount(p,axis);slot++){
			int face=solids.parcelFace(p,axis,slot);if(!(solids.solidArea(face)*solids.parcelPressureShare(p,axis,slot)>0))continue;
			int a=faces.firstCell[face],b=faces.secondCell[face];if(a<0)continue;
			int maskA=cut.mask(a),maskB=cut.mask(b);
			for(int micro=0;micro<8;micro++)if((micro>>>axis&1)!=0){
				int peer=micro^(1<<axis);if((maskA&1<<micro)!=0||(maskB&1<<peer)!=0)continue;
				joinMechanical(body,particleBase+p,a);
				joinMechanical(body,particleBase+p,b);
			}
		}
		return body;
	}
	// Fixed collision admission and stock restore both require the cell's sole canonical open support.
	private static int supportVertex(LiquidCellInventory.Key key){return key.cell();}
	private static int mechanicalRoot(int[] body,int node){
		while(body[node]!=node){body[node]=body[body[node]];node=body[node];}return node;
	}
	private static void joinMechanical(int[] body,int first,int second){
		int a=mechanicalRoot(body,first),b=mechanicalRoot(body,second);if(a!=b)body[Math.max(a,b)]=Math.min(a,b);
	}
}
