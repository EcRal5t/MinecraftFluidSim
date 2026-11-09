package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.units.Mass;
import mmm.units.SignedDyadic;
import java.util.Arrays;

/** Derived cell properties and dual face inertia over persistent liquid inventory and momentum. */
final class CellMacStage {
	private final HostFluidState domain;
	final double[] massX, massY, massZ;
	private final double[] cellMass;
	private long inventoryRevision=-1,quantityRevision;
	private LiquidCellInventory.Snapshot preparedQuantities;
	private FluidMaterialRegistry preparedMaterialRegistry;
	private mmm.fluid.air.SpatialGasState preparedGas;

	CellMacStage(HostFluidState domain) {
		this.domain = domain;
		massX = new double[domain.mac.velocityX.length];
		massY = new double[domain.mac.velocityY.length];
		massZ = new double[domain.mac.velocityZ.length];
		cellMass = new double[domain.cellCount];
	}

	void rebuildInventory() {
		var inventory = domain.liquidCells;
		boolean materialsChanged=preparedMaterialRegistry!=domain.materialRegistry;
		boolean gasChanged=preparedGas!=domain.gasState.spatial;
		if(inventoryRevision==inventory.revision()&&!domain.gridDirty&&!materialsChanged&&!gasChanged)return;
		var grid=domain.liquidGrid;
		boolean quantitiesChanged=!inventory.hasSameQuantities(preparedQuantities);
		if(quantitiesChanged||materialsChanged){
			grid.ensureStockCapacity(inventory.physicalSize());
			Arrays.fill(grid.stockCount, 0);
			Arrays.fill(grid.cellStockMass, 0); Arrays.fill(grid.cellPhaseHead, -1);
			grid.phaseEntryCount=0;
			if (grid.exactCellVolumeInventory == null) grid.exactCellVolumeInventory = new SignedDyadic[domain.cellCount];
			if (domain.liquidCarrierMaterialByCell == null) {
				domain.liquidCarrierMaterialByCell = new int[domain.cellCount];
				domain.liquidCarrierVolumeByCell = new double[domain.cellCount];
			}
			Arrays.fill(domain.liquidCarrierMaterialByCell, -1);
			Arrays.fill(domain.liquidCarrierVolumeByCell, 0);
			for (int cell = 0; cell < domain.cellCount; cell++) {
				var volume = inventory.exactCellVolume(cell);
				grid.exactCellVolumeInventory[cell] = volume;
				grid.cellStockVolume[cell] = volume.toDouble();
				domain.volume[cell] = (float) volume.toDouble();
				long mass = 0;
				for (var stock : inventory.stocksInCell(cell)) mass = Math.addExact(mass, stock.massUnits());
				cellMass[cell]=Mass.kilograms(mass);
				grid.cellStockMass[cell] = domain.unitScale.normalizedMass(mass);
				grid.cellDensity[cell] = mass > 0 ? grid.cellStockMass[cell] / volume.toDouble() : LiquidCarrierProperties.EMPTY_LIQUID_DENSITY;
			}
			for (int ordinal = 0; ordinal < inventory.physicalSize(); ordinal++) {
				var stock = inventory.physicalStockAt(ordinal); int cell = stock.key().cell();
				grid.stockCount[cell]++;
				int phase = domain.materialRegistry == null ? 0 : domain.materialRegistry.phaseHandle(stock.key().material());
				grid.addCellPhaseVolume(cell, phase, (float) stock.volumeFraction());
				if (stock.volumeFraction() > domain.liquidCarrierVolumeByCell[cell]) {
					domain.liquidCarrierVolumeByCell[cell] = stock.volumeFraction();
					domain.liquidCarrierMaterialByCell[cell] = stock.key().material();
				}
			}
		}
		if (domain.granularCoupling != null) {
			if (domain.granularState != null) domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
			else domain.granularCoupling.rebuildFixed();
		}

		domain.carrierPropertiesWorkspace.invalidate();
		grid.inventoryRevision = inventory.revision(); inventoryRevision = inventory.revision();
		if(quantitiesChanged||materialsChanged||gasChanged){
			for(int cell=0;cell<domain.cellCount;cell++){
				long mass=0;for(var stock:inventory.stocksInCell(cell))mass=Math.addExact(mass,stock.massUnits());
				if(domain.gasState.spatial!=null)mass=Math.addExact(mass,domain.gasState.spatial.cells().get(cell).totalMassUnits());
				cellMass[cell]=Mass.kilograms(mass);
			}
			quantityRevision++;rebuildFaceInertia();domain.velocityGridDirty=true;
		}
		preparedQuantities=inventory.snapshot();preparedMaterialRegistry=domain.materialRegistry;preparedGas=domain.gasState.spatial;
		domain.gridDirty = false;
	}

	long quantityRevision(){return quantityRevision;}

	void rebuildVelocity() {
		rebuildInventory();
		derivePreparedVelocity();
	}
	private void derivePreparedVelocity(){
		domain.mac.deriveVelocity(massX, massY, massZ);
		float[][] velocities = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		for (int axis = 0; axis < 3; axis++) for (int face = 0; face < velocities[axis].length; face++)
			if (mechanicsFaceActive(axis, face) && isWallFace(axis, face, null)) velocities[axis][face] = 0;
		domain.velocityGridDirty = false;
	}

	/** Face inertia belongs to the same inventory generation as cell mass. */
	private void rebuildFaceInertia() {
		for (int axis = 0; axis < 3; axis++) {
			double[] mass = axis == 0 ? massX : axis == 1 ? massY : massZ;
			double[] normalized = axis == 0 ? domain.mac.faceMassX : axis == 1 ? domain.mac.faceMassY : domain.mac.faceMassZ;
			float[] flags = axis == 0 ? domain.mac.velocityWeightX : axis == 1 ? domain.mac.velocityWeightY : domain.mac.velocityWeightZ;
			boolean[] valid = axis == 0 ? domain.mac.velocityValidX : axis == 1 ? domain.mac.velocityValidY : domain.mac.velocityValidZ;
			for (int face=0;face<mass.length;face++) {
				int global=domain.grid.faceOffset(axis)+face;
				int right=domain.grid.faceRight(global),left=domain.grid.faceLeft(global);
				mass[face] = .5 * ((left < 0 ? ambientCellMass() : cellMass[left]) + (right < 0 ? ambientCellMass() : cellMass[right]));
				normalized[face] = mass[face] / domain.unitScale.cubicMetersPerNormalizedVolume();
				flags[face] = mass[face] > 0 ? 1 : 0;
				valid[face] = mass[face] > 0;
			}
		}
	}

	record WallImpulse(double x,double y,double z) { }
	private double externalKineticSinkJoules;
    double externalKineticSinkJoules(){return externalKineticSinkJoules;}
    void commitMomentum(ExternalMomentum prepared){
        domain.mac.restoreMomentum(prepared.momentum());recordWallImpulse(prepared.wallImpulse());
        externalKineticSinkJoules+=prepared.removedKineticEnergy();
    }
	private void recordWallImpulse(WallImpulse impulse){
		domain.lastLiquidBoundaryImpulseX+=impulse.x();domain.lastLiquidBoundaryImpulseY+=impulse.y();domain.lastLiquidBoundaryImpulseZ+=impulse.z();
	}
	boolean isWallFace(int axis,int face,byte[] masks){
		int global=domain.grid.faceOffset(axis)+face;
		int a=domain.grid.faceLeft(global),b=domain.grid.faceRight(global);
		if(a<0||b<0){
			if(domain.gasState.spatial==null||domain.gasState.sealedDomainBoundary)return true;
			int cell=a<0?b:a;
			int mask=masks==null?domain.geometry.cutCells.mask(cell):Byte.toUnsignedInt(masks[cell]),side=a<0?0:1;
			for(int micro=0;micro<8;micro++)if((micro>>>axis&1)==side&&(mask&(1<<micro))==0)return false;
			return true;
		}
		if(masks==null)return !(domain.geometry.cutCells.openFaceField(axis)[face]>0);
		for(int micro=0;micro<8;micro++)if((micro>>>axis&1)!=0&&(masks[a]&1<<micro)==0&&(masks[b]&1<<(micro^(1<<axis)))==0)return false;
		return true;
	}
	boolean mechanicsFaceActive(int axis,int face){
		int global=domain.grid.faceOffset(axis)+face;
		int b=domain.grid.faceRight(global),a=domain.grid.faceLeft(global);
		return domain.activityGroups.mechanicsFaceActive(a,b);
	}
	/** A stationary inelastic normal constraint transfers -P to the liquid and its kinetic energy to local U. */
	WallImpulse stopWallMomentum(double[][] momentum,long[] cellMass,double[] heat,byte[] masks){
		double[] impulse=new double[3];
		for(int axis=0;axis<3;axis++)for(int face=0;face<momentum[axis].length;face++){
			if(!mechanicsFaceActive(axis,face))continue;
			double p=momentum[axis][face];if(p==0||!isWallFace(axis,face,masks))continue;
			int global=domain.grid.faceOffset(axis)+face,b=domain.grid.faceRight(global),a=domain.grid.faceLeft(global);
			long ma=a<0?0:cellMass[a],mb=b<0?0:cellMass[b];double total=(double)ma+mb,mass=.5*total/Mass.UNITS_PER_KILOGRAM;
			if(mass>0){double energy=.5*p*(p/mass),first=energy*(ma/total);if(a>=0)heat[a]+=first;if(b>=0)heat[b]+=energy-first;}
			impulse[axis]-=p;momentum[axis][face]=0;
		}
		return new WallImpulse(impulse[0],impulse[1],impulse[2]);
	}
	ExternalMomentum prepareMomentumInput(double[][] values,LiquidCellInventory inventory,byte[] masks){
		long[] mass=new long[domain.cellCount];double[] heat=new double[domain.cellCount];
		for(var stock:inventory.physicalStocks())mass[stock.key().cell()]=Math.addExact(mass[stock.key().cell()],stock.massUnits());
		var walls=stopWallMomentum(values,mass,heat,masks);var byCell=new java.util.TreeMap<Integer,SignedDyadic>();
		for(int cell=0;cell<heat.length;cell++)if(heat[cell]>0)byCell.put(cell,SignedDyadic.of(heat[cell]));
		return new ExternalMomentum(new MacMomentumState(domain.grid,values[0],values[1],values[2]),java.util.Map.copyOf(byCell),java.util.Map.of(),walls);
	}

	/** Stationary insertion adds inertia; extraction removes the carried momentum at its current velocity. */
	record ExternalMomentum(MacMomentumState momentum, java.util.Map<Integer, SignedDyadic> heatByCell, java.util.Map<Integer,SignedDyadic> removedKineticEnergyByCell,WallImpulse wallImpulse) {
		double removedKineticEnergy(){return removedKineticEnergyByCell.values().stream().mapToDouble(SignedDyadic::toDouble).sum();}
	}
    ExternalMomentum prepareExternalMomentum(LiquidCellInventory.Plan edit) { return prepareExternalMomentum(edit,null,null); }
    ExternalMomentum prepareExternalMomentum(LiquidCellInventory.Plan edit,byte[] masks) { return prepareExternalMomentum(edit,masks,null); }

    /** Both phases use the same retained face mass and carried momentum during a cold edit. */
    ExternalMomentum prepareExternalMomentum(LiquidCellInventory.Plan edit,byte[] masks,SpatialGasEdits.Plan gasEdit) {
        domain.domainFlowFields.ensureInventoryGrid();
        var oldGas=domain.gasState.spatial;var nextGas=gasEdit==null?oldGas:gasEdit.after();
        double[] retained=new double[domain.cellCount],future=new double[domain.cellCount],removed=new double[domain.cellCount];
        double[][] incomingP=new double[3][domain.cellCount],incomingK=new double[3][domain.cellCount];
        long[] movedGas=new long[domain.cellCount],escapedGas=new long[domain.cellCount];
        if(gasEdit!=null)for(var transfer:gasEdit.transfers()){
            int cell=transfer.source(),target=transfer.target();long amount=transfer.massUnits();
            movedGas[cell]=Math.addExact(movedGas[cell],amount);
            if(target<0){escapedGas[cell]=Math.addExact(escapedGas[cell],amount);continue;}
            double kg=Mass.kilograms(amount);
            for(int axis=0;axis<3;axis++){
                int low=domain.grid.face(cell,2*axis)-domain.grid.faceOffset(axis);
                int high=domain.grid.face(cell,2*axis+1)-domain.grid.faceOffset(axis);
                double[] mass=axis==0?massX:axis==1?massY:massZ,p=axis==0?domain.mac.momentumX:axis==1?domain.mac.momentumY:domain.mac.momentumZ;
                double a=mass[low]>0?p[low]/mass[low]:0,b=mass[high]>0?p[high]/mass[high]:0;
                incomingP[axis][target]+=.5*kg*(a+b);
                incomingK[axis][target]+=.25*kg*(a*a+b*b);
            }
        }
        for(int cell=0;cell<domain.cellCount;cell++){
            long oldLiquid=0,newLiquid=0;
            for(var stock:domain.liquidCells.physicalStocksInCell(cell))oldLiquid=Math.addExact(oldLiquid,stock.massUnits());
            for(var stock:edit.future().physicalStocksInCell(cell))newLiquid=Math.addExact(newLiquid,stock.massUnits());
            long oldGasMass=oldGas==null?0:oldGas.cells().get(cell).totalMassUnits();
            long newGasMass=nextGas==null?0:nextGas.cells().get(cell).totalMassUnits();
            long retainedGas=Math.subtractExact(oldGasMass,movedGas[cell]);
            retained[cell]=Mass.kilograms(Math.addExact(Math.min(oldLiquid,newLiquid),retainedGas));
            future[cell]=Mass.kilograms(Math.addExact(newLiquid,newGasMass));
            removed[cell]=Mass.kilograms(Math.addExact(Math.max(0,oldLiquid-newLiquid),escapedGas[cell]));
        }
        var heat=new java.util.TreeMap<Integer,SignedDyadic>();var removedEnergy=new java.util.TreeMap<Integer,SignedDyadic>();
        double[][] result=new double[3][];double[] impulse=new double[3];
        for(int axis=0;axis<3;axis++){
            double[] oldMass=axis==0?massX:axis==1?massY:massZ,oldP=axis==0?domain.mac.momentumX:axis==1?domain.mac.momentumY:domain.mac.momentumZ;
            result[axis]=new double[oldMass.length];
            for(int face=0;face<oldMass.length;face++){
                int global=domain.grid.faceOffset(axis)+face;
                int right=domain.grid.faceRight(global),left=domain.grid.faceLeft(global);
                double speed=oldMass[face]>0?oldP[face]/oldMass[face]:0;
                double ambient=(left<0||right<0)&&!isWallFace(axis,face,masks)?ambientCellMass():0;
                double retainedMass=.5*(at(retained,left)+at(retained,right)+ambient),newMass=.5*(at(future,left)+at(future,right)+ambient);
                double p=retainedMass*speed+.5*(at(incomingP[axis],left)+at(incomingP[axis],right));
                double energy=.5*retainedMass*speed*speed+.5*(at(incomingK[axis],left)+at(incomingK[axis],right));
                if(isWallFace(axis,face,masks)){impulse[axis]-=p;p=0;}
                result[axis][face]=p;
                double dissipation=Math.max(0,energy-(newMass>0?.5*p*p/newMass:0));
                if(dissipation>0){
                    var exact=SignedDyadic.of(dissipation);double total=at(future,left)+at(future,right);
                    var first=left<0||future[left]==0?SignedDyadic.ZERO:right<0||future[right]==0?exact:SignedDyadic.of(dissipation*future[left]/total);
                    if(first.signum()>0)heat.merge(left,first,SignedDyadic::add);
                    var second=exact.subtract(first);if(second.signum()>0)heat.merge(right,second,SignedDyadic::add);
                }
                for(int cell:new int[]{left,right})if(cell>=0&&removed[cell]>0)
                    removedEnergy.merge(cell,SignedDyadic.of(.25*removed[cell]*speed*speed),SignedDyadic::add);
            }
        }
        return new ExternalMomentum(new MacMomentumState(domain.grid,result[0],result[1],result[2]),
                java.util.Map.copyOf(heat),java.util.Map.copyOf(removedEnergy),new WallImpulse(impulse[0],impulse[1],impulse[2]));
    }
    private static double at(double[] values,int cell){return cell<0?0:values[cell];}
    private double ambientCellMass(){
        if(domain.gasState.spatial==null||domain.gasState.sealedDomainBoundary)return 0;
        double h=domain.config.cellSize(),temperature=domain.thermalState==null?293.15:domain.thermalState.insertionTemperatureK();
        double density=domain.gasState.network.registry().standardAmbientInventory().totalMass();
        return (float)Mass.kilograms(Mass.fromKilograms(density*293.15/temperature*h*h*h));
    }

    /** Publishes one material endpoint after all participating owners prepared their plans. */
    void publishMaterialChange(LiquidCellInventory.Plan inventory,Runnable momentumPublication,Runnable carrierPublication){
        inventory.requireCurrent();
        inventory.commit();momentumPublication.run();carrierPublication.run();
        domain.markCellInventoryChanged();
        if(domain.chemistry!=null)for(int cell:inventory.changedCells())domain.chemistry.markDirtyCell(cell);
    }

	double faceMass(int first, int second) { return .5 * (cellMass[first] + cellMass[second]); }
}
