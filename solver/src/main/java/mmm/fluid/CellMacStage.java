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
	private final boolean[][] unresolvedWall;
	private FlipPressurePhaseGeometry wallPhase;
	private LiquidCellInventory.Snapshot wallInventory;
	private CellMomentumTransport momentumTransport;
	static final class PreparedLiquid {
		private final LiquidCellInventory.Plan inventory;
		private final ConservativeLiquidFlux flux;
		private final GasVolumeAllocation gas;
		private final LiquidCellInventory source;
		PreparedLiquid(LiquidCellInventory.Plan inventory, ConservativeLiquidFlux flux,
		               GasVolumeAllocation gas) {
			this.inventory = inventory;
			this.flux = flux;
			this.gas = gas;
			this.source = flux.sourceInventory();
		}
		LiquidCellInventory.Plan inventory(){return inventory;}
		ConservativeLiquidFlux volumeFlux(){return flux;}
		GasVolumeAllocation gasAllocation(){return gas;}
		LiquidCellInventory sourceInventory(){return source;}
	}

	CellMacStage(HostFluidState domain) {
		this.domain = domain;
		massX = new double[domain.mac.velocityX.length];
		massY = new double[domain.mac.velocityY.length];
		massZ = new double[domain.mac.velocityZ.length];
		cellMass = new double[domain.cellCount];
		unresolvedWall=new boolean[][]{new boolean[massX.length],new boolean[massY.length],new boolean[massZ.length]};
	}

	void rebuildInventory() {
		var inventory = domain.liquidCells;
		boolean materialsChanged=preparedMaterialRegistry!=domain.materialRegistry;
		if(inventoryRevision==inventory.revision()&&!domain.gridDirty&&!materialsChanged)return;
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
		if(quantitiesChanged){quantityRevision++;rebuildFaceInertia();}
		preparedQuantities=inventory.snapshot();preparedMaterialRegistry=domain.materialRegistry;
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

	/** A wall-supported layer has no independent normal inertia above its unresolved free face. */
	void rebuildWallSupport(){
		var phase=domain.pressureSystem.phaseGeometry();var cut=domain.geometry.cutCells;
		for(int axis=0;axis<3;axis++){
			Arrays.fill(unresolvedWall[axis],false);
			int nx=domain.sizeX+(axis==0?1:0),ny=domain.sizeY+(axis==1?1:0);
			int stride=axis==0?1:axis==1?nx:nx*ny;
			for(int cell=0;cell<domain.cellCount;cell++){
				double fill=phase.liquidFractionExact(cell);
				if(!(cellMass[cell]>0&&fill>0&&fill<1)||CellRemapMicroTopology.components(cut.mask(cell)).length!=1)continue;
				int x=cell%domain.sizeX,y=cell/domain.sizeX%domain.sizeY,z=cell/(domain.sizeX*domain.sizeY);
				int lower=(z*ny+y)*nx+x,upper=lower+stride;
				boolean lowWall=isWallFace(axis,lower,null),highWall=isWallFace(axis,upper,null);
				if(lowWall==highWall)continue;
				int side=lowWall?0:1,face=lowWall?upper:lower;
				if(!(phase.liquidFaceArea(cell,axis,side,cut)>FlipPressurePhaseGeometry.CELL_VOLUME_RESOLUTION))continue;
				int a=domain.pressureSystem.faces.firstCell[domain.pressureSystem.faces.offsets[axis]+face];
				int b=domain.pressureSystem.faces.secondCell[domain.pressureSystem.faces.offsets[axis]+face];
				if(a<0||b<0)continue;
				// Either liquid may enter this face. A dry gap between two wet
				// endpoints still carries real donor motion into the empty space.
				if(phase.liquidFaceArea(a,axis,1,cut)>FlipPressurePhaseGeometry.CELL_VOLUME_RESOLUTION
						||phase.liquidFaceArea(b,axis,0,cut)>FlipPressurePhaseGeometry.CELL_VOLUME_RESOLUTION)continue;
				unresolvedWall[axis][face]=true;
			}
		}
		// A supported column transfers its weight to the fixed base. Its dry
		// top face carries no separate normal control volume, at any column height.
		for(int face=0;face<massY.length;face++)if(massY[face]>0
				&&domain.pressureSystem.faces.isGroundedDryNormalFace(face))unresolvedWall[1][face]=true;
		wallPhase=phase;wallInventory=domain.liquidCells.snapshot();
	}
	boolean[] constrainedNormalFaces(int axis){
		return wallPhase==domain.pressureSystem.phaseGeometry()&&domain.liquidCells.hasSameGeometry(wallInventory)?unresolvedWall[axis]:null;
	}
	boolean isUnresolvedWallFace(int axis,int face){
		return wallPhase==domain.pressureSystem.phaseGeometry()&&domain.liquidCells.hasSameGeometry(wallInventory)&&unresolvedWall[axis][face];
	}

	/** Face inertia belongs to the same inventory generation as cell mass. */
	private void rebuildFaceInertia() {
		for (int axis = 0; axis < 3; axis++) {
			double[] mass = axis == 0 ? massX : axis == 1 ? massY : massZ;
			double[] normalized = axis == 0 ? domain.mac.faceMassX : axis == 1 ? domain.mac.faceMassY : domain.mac.faceMassZ;
			float[] flags = axis == 0 ? domain.mac.velocityWeightX : axis == 1 ? domain.mac.velocityWeightY : domain.mac.velocityWeightZ;
			boolean[] valid = axis == 0 ? domain.mac.velocityValidX : axis == 1 ? domain.mac.velocityValidY : domain.mac.velocityValidZ;
			int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0), nz = domain.sizeZ + (axis == 2 ? 1 : 0);
			for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
				int face = (z * ny + y) * nx + x;
				int coordinate = axis == 0 ? x : axis == 1 ? y : z, size = axis == 0 ? domain.sizeX : axis == 1 ? domain.sizeY : domain.sizeZ;
				int right = coordinate < size ? domain.domainFlowFields.cellIndex(x, y, z) : -1;
				int left = coordinate > 0 ? domain.domainFlowFields.cellIndex(x - (axis == 0 ? 1 : 0), y - (axis == 1 ? 1 : 0), z - (axis == 2 ? 1 : 0)) : -1;
				mass[face] = .5 * ((left < 0 ? 0 : cellMass[left]) + (right < 0 ? 0 : cellMass[right]));
				normalized[face] = mass[face] / domain.unitScale.cubicMetersPerNormalizedVolume();
				flags[face] = mass[face] > 0 ? 1 : 0;
				valid[face] = mass[face] > 0;
			}
		}
	}

	record WallImpulse(double x,double y,double z) { }
	void commitMomentum(CellMomentumTransport.Prepared prepared){domain.mac.restoreMomentum(prepared.momentum());recordWallImpulse(prepared.wallImpulse());}
	void commitMomentum(ExternalMomentum prepared){domain.mac.restoreMomentum(prepared.momentum());recordWallImpulse(prepared.wallImpulse());}
	private void recordWallImpulse(WallImpulse impulse){
		domain.lastLiquidBoundaryImpulseX+=impulse.x();domain.lastLiquidBoundaryImpulseY+=impulse.y();domain.lastLiquidBoundaryImpulseZ+=impulse.z();
	}
	boolean isWallFace(int axis,int face,byte[] masks){
		int nx=domain.sizeX+(axis==0?1:0),ny=domain.sizeY+(axis==1?1:0);
		int x=face%nx,y=face/nx%ny,z=face/(nx*ny),coordinate=axis==0?x:axis==1?y:z,size=axis==0?domain.sizeX:axis==1?domain.sizeY:domain.sizeZ;
		if(coordinate==0||coordinate==size)return true;
		if(masks==null)return !(domain.geometry.cutCells.openFaceField(axis)[face]>0);
		int b=domain.domainFlowFields.cellIndex(x,y,z),a=b-(axis==0?1:axis==1?domain.sizeX:domain.sizeX*domain.sizeY);
		for(int micro=0;micro<8;micro++)if((micro>>>axis&1)!=0&&(masks[a]&1<<micro)==0&&(masks[b]&1<<(micro^(1<<axis)))==0)return false;
		return true;
	}
	boolean mechanicsFaceActive(int axis,int face){
		int nx=domain.sizeX+(axis==0?1:0),ny=domain.sizeY+(axis==1?1:0);
		int x=face%nx,y=face/nx%ny,z=face/(nx*ny);
		int coordinate=axis==0?x:axis==1?y:z,size=axis==0?domain.sizeX:axis==1?domain.sizeY:domain.sizeZ;
		int b=coordinate<size?domain.domainFlowFields.cellIndex(x,y,z):-1;
		int a=coordinate>0?domain.domainFlowFields.cellIndex(x-(axis==0?1:0),y-(axis==1?1:0),z-(axis==2?1:0)):-1;
		return domain.activityGroups.mechanicsFaceActive(a,b);
	}
	/** A stationary inelastic normal constraint transfers -P to the liquid and its kinetic energy to local U. */
	WallImpulse stopWallMomentum(double[][] momentum,long[] cellMass,double[] heat,byte[] masks){
		double[] impulse=new double[3];
		for(int axis=0;axis<3;axis++)for(int face=0;face<momentum[axis].length;face++){
			if(!mechanicsFaceActive(axis,face))continue;
			double p=momentum[axis][face];if(p==0||!isWallFace(axis,face,masks))continue;
			int nx=domain.sizeX+(axis==0?1:0),ny=domain.sizeY+(axis==1?1:0),x=face%nx,y=face/nx%ny,z=face/(nx*ny);
			int coordinate=axis==0?x:axis==1?y:z,size=axis==0?domain.sizeX:axis==1?domain.sizeY:domain.sizeZ;
			int b=coordinate<size?domain.domainFlowFields.cellIndex(x,y,z):-1,a=coordinate>0?domain.domainFlowFields.cellIndex(x-(axis==0?1:0),y-(axis==1?1:0),z-(axis==2?1:0)):-1;
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
		return new ExternalMomentum(new MacMomentumState(domain.sizeX,domain.sizeY,domain.sizeZ,values[0],values[1],values[2]),java.util.Map.copyOf(byCell),java.util.Map.of(),walls);
	}

	CellMomentumTransport.Prepared prepareMomentumTransport(PreparedLiquid liquid) {
		if (momentumTransport == null) momentumTransport = new CellMomentumTransport(domain);
		return momentumTransport.prepare(liquid);
	}
	/** Stationary insertion adds inertia; extraction removes the carried momentum at its current velocity. */
	record ExternalMomentum(MacMomentumState momentum, java.util.Map<Integer, SignedDyadic> heatByCell, java.util.Map<Integer,SignedDyadic> removedKineticEnergyByCell,WallImpulse wallImpulse) {
		double removedKineticEnergy(){return removedKineticEnergyByCell.values().stream().mapToDouble(SignedDyadic::toDouble).sum();}
	}
	ExternalMomentum prepareExternalMomentum(LiquidCellInventory.Plan edit) {return prepareExternalMomentum(edit,null);}
	ExternalMomentum prepareExternalMomentum(LiquidCellInventory.Plan edit,byte[] masks) {
		return prepareExternalMomentum(edit,masks,null);
	}
	ExternalMomentum preparePhaseMomentum(LiquidCellInventory.Plan edit,java.util.Map<Integer,Long> removedMassUnits) {
		return prepareExternalMomentum(edit,null,removedMassUnits);
	}
	private ExternalMomentum prepareExternalMomentum(LiquidCellInventory.Plan edit,byte[] masks,java.util.Map<Integer,Long> removedMassUnits) {
		domain.domainFlowFields.ensureInventoryGrid();
		if (domain.velocityGridDirty) rebuildVelocity();
		var heat = new java.util.TreeMap<Integer, SignedDyadic>();
		var removedEnergy = new java.util.TreeMap<Integer,SignedDyadic>();
		double[] removedMass = new double[domain.cellCount];
		if(removedMassUnits!=null) removedMassUnits.forEach((cell,units)->removedMass[cell]=Mass.kilograms(units));
		else for(int cell=0;cell<domain.cellCount;cell++){
            long before=0,after=0;
            for(var stock:domain.liquidCells.stocksInCell(cell))before=Math.addExact(before,stock.massUnits());
            for(var stock:edit.future().stocksInCell(cell))after=Math.addExact(after,stock.massUnits());
            removedMass[cell]=Mass.kilograms(Math.max(0,before-after));
        }
		double[] futureMass = new double[domain.cellCount];
		for (int cell = 0; cell < futureMass.length; cell++) {
			long units = 0;
			for (var stock : edit.future().stocksInCell(cell)) units = Math.addExact(units, stock.massUnits());
			futureMass[cell] = Mass.kilograms(units);
		}
		double[][] result = new double[3][];
		for (int axis = 0; axis < 3; axis++) {
			float[] velocity = axis == 0 ? domain.mac.velocityX : axis == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
			double[] mass = axis == 0 ? massX : axis == 1 ? massY : massZ;
			result[axis] = new double[mass.length];
			int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0), nz = domain.sizeZ + (axis == 2 ? 1 : 0);
			for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
				int face = (z * ny + y) * nx + x;
				int coordinate = axis == 0 ? x : axis == 1 ? y : z, size = axis == 0 ? domain.sizeX : axis == 1 ? domain.sizeY : domain.sizeZ;
				int right = coordinate < size ? domain.domainFlowFields.cellIndex(x, y, z) : -1;
				int left = coordinate > 0 ? domain.domainFlowFields.cellIndex(x - (axis == 0 ? 1 : 0), y - (axis == 1 ? 1 : 0), z - (axis == 2 ? 1 : 0)) : -1;
				double after = .5 * ((left < 0 ? 0 : futureMass[left]) + (right < 0 ? 0 : futureMass[right]));
				double removed=.5*((left<0?0:removedMass[left])+(right<0?0:removedMass[right]));
				double retained=Math.max(0,mass[face]-removed),speed=velocity[face];
				result[axis][face]=retained*speed;
				for(int cell:new int[]{left,right})if(cell>=0&&removedMass[cell]>0)
					removedEnergy.merge(cell,SignedDyadic.of(.25*removedMass[cell]*speed*speed),SignedDyadic::add);
				double dissipated=after>0?.5*retained*speed*speed*Math.max(0,(after-retained)/after):0;
				if(dissipated>0){
					double total=(left<0?0:futureMass[left])+(right<0?0:futureMass[right]);
					var exact=SignedDyadic.of(dissipated);
					var first=left<0||futureMass[left]==0?SignedDyadic.ZERO:right<0||futureMass[right]==0?exact:SignedDyadic.of(dissipated*futureMass[left]/total);
					if(first.signum()>0)heat.merge(left,first,SignedDyadic::add);
					var second=exact.subtract(first);if(second.signum()>0)heat.merge(right,second,SignedDyadic::add);
				}
			}
		}
		var constrained=prepareMomentumInput(result,edit.future(),masks);
		for(var entry:constrained.heatByCell().entrySet())heat.merge(entry.getKey(),entry.getValue(),SignedDyadic::add);
		return new ExternalMomentum(constrained.momentum(), java.util.Map.copyOf(heat), java.util.Map.copyOf(removedEnergy),constrained.wallImpulse());
	}

	PreparedLiquid prepareLiquid(ConservativeLiquidFlux flux) {
		return new PreparedLiquid(flux.prepareInventory(domain.liquidCells), flux, flux.gasAllocation());
	}
	/** Common publication for accepted liquid routes, including internal and external solid displacement. */
	void publishTransport(MechanicalEnergySettlement.Plan energy,CellMomentumTransport.Prepared momentum,
	                      GasTopologyCoordinator.GasTopologyPlan gas,long[] occupied,Runnable solidPublication){
		var inventory=energy.inventory();
		var auditAfter = domain.thermalState == null ? null : domain.thermalState.auditLedger()
				.withMechanicalWork(inventory.mechanicalHeat()).withRoundoff(inventory.energyRoundoff());
		if(auditAfter!=null&&gas!=null&&gas.thermalPlan()!=null){
			var thermal=gas.thermalPlan();auditAfter=auditAfter.withExternalSourceEnergy(thermal.sourceEnergy())
					.withExternalSinkEnergy(thermal.sinkEnergy()).withMechanicalWork(thermal.mechanicalWork()).withRoundoff(thermal.roundoff());
		}
		auditAfter=energy.auditAfter(auditAfter);
		if(auditAfter!=null)auditAfter.expectedInventory();
		energy.requireCurrent();
		publishMaterialChange(inventory,()->commitMomentum(momentum),gas,occupied,solidPublication);
		energy.commitGranular();
		if (auditAfter != null) domain.thermalState.restoreAuditLedger(domain.thermalState.auditLedger()
				.withMechanicalWork(inventory.mechanicalHeat()).withRoundoff(inventory.energyRoundoff()));
	}

    /** Publishes one material endpoint after all participating owners prepared their plans. */
    void publishMaterialChange(LiquidCellInventory.Plan inventory,Runnable momentumPublication,
            GasTopologyCoordinator.GasTopologyPlan gas,long[] occupied,Runnable carrierPublication){
        publishMaterialChange(inventory,momentumPublication,gas,occupied,carrierPublication,
                ()->{if(gas!=null)domain.gasTopologyCoordinator.requireBeforeCarrierCommit(gas);});
    }
    void publishMaterialChange(LiquidCellInventory.Plan inventory,Runnable momentumPublication,
            GasTopologyCoordinator.GasTopologyPlan gas,long[] occupied,Runnable carrierPublication,Runnable gasPreflight){
        inventory.requireCurrent();gasPreflight.run();
        inventory.commit();momentumPublication.run();carrierPublication.run();
        domain.markCellInventoryChanged();
        if(gas!=null){
            if(occupied==null)domain.gasTopologyCoordinator.commit(gas);
            else domain.gasTopologyCoordinator.commitPrepared(gas,occupied);
        }
        if(domain.chemistry!=null)for(int cell:inventory.changedCells())domain.chemistry.markDirtyCell(cell);
    }

	double faceMass(int first, int second) { return .5 * (cellMass[first] + cellMass[second]); }
}
