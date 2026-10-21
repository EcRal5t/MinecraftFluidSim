package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.units.Mass;
import mmm.units.SignedDyadic;
import java.util.Arrays;

/** Derived cell properties and dual face inertia over persistent liquid inventory and momentum. */
final class CellMacStage {
	private final FluidDomain domain;
	final double[] massX, massY, massZ;
	private final double[] cellMass;
	private long inventoryRevision = -1;
	private final boolean[][] unresolvedWall;
	private FlipPressurePhaseGeometry wallPhase;
	private LiquidCellInventory.Snapshot wallInventory;
	private CellMomentumTransport momentumTransport;
	private ConservativeLiquidFlux liquidTransport;
	static final class PreparedLiquid {
		private final LiquidCellInventory.Plan inventory;
		private final ConservativeLiquidFlux flux;
		private final GasVolumeAllocation gas;
		private final LiquidCellInventory source;
		private final long[] occupied;
		PreparedLiquid(LiquidCellInventory.Plan inventory, ConservativeLiquidFlux flux,
		               GasVolumeAllocation gas, long[] occupied) {
			this.inventory = inventory;
			this.flux = flux;
			this.gas = gas;
			this.source = flux.sourceInventory();
			this.occupied = occupied;
		}
		LiquidCellInventory.Plan inventory(){return inventory;}
		ConservativeLiquidFlux volumeFlux(){return flux;}
		GasVolumeAllocation gasAllocation(){return gas;}
		LiquidCellInventory sourceInventory(){return source;}
		CellMomentumTransport.Prepared preparedMomentum(){return flux.preparedMomentum;}
	}

	CellMacStage(FluidDomain domain) {
		this.domain = domain;
		massX = new double[domain.mac.velocityX.length];
		massY = new double[domain.mac.velocityY.length];
		massZ = new double[domain.mac.velocityZ.length];
		cellMass = new double[domain.cellCount];
		unresolvedWall=new boolean[][]{new boolean[massX.length],new boolean[massY.length],new boolean[massZ.length]};
	}

	void rebuildInventory() {
		var inventory = domain.liquidCells;
		if (inventoryRevision == inventory.revision() && !domain.gridDirty) return;
		var grid = domain.liquidGrid;
		grid.ensureStockCapacity(inventory.physicalSize());
		Arrays.fill(grid.stockCount, 0);
		Arrays.fill(grid.cellStockMass, 0); Arrays.fill(grid.cellPhaseHead, -1);
		Arrays.fill(cellMass, 0); grid.phaseEntryCount = 0;
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
			domain.projection.pressureVolume[cell] = volume.toDouble();
			long mass = 0;
			for (var stock : inventory.stocksInCell(cell)) mass = Math.addExact(mass, stock.massUnits());
			cellMass[cell] = Mass.kilograms(mass);
			grid.cellStockMass[cell] = domain.unitScale.normalizedMass(mass);
			grid.cellDensity[cell] = mass > 0 ? grid.cellStockMass[cell] / volume.toDouble() : LiquidCarrierProperties.AIR_DENSITY;
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
		if (domain.granularCoupling != null) {
			if (domain.granularState != null) domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
			else domain.granularCoupling.rebuildFixed();
		}
		domain.pressureGranularCoupling = domain.granularCoupling;
		domain.surfaceTensionActive = domain.domainFlowFields.hasAnySurfaceTension();
		domain.liquidPairTensionActive = domain.domainFlowFields.hasAnyLiquidPairTension();
		domain.carrierPropertiesWorkspace.invalidate();
		grid.inventoryRevision = inventory.revision(); inventoryRevision = inventory.revision();
		rebuildFaceInertia();
		domain.gridDirty = false;
	}

	void rebuildGeometry() {
		rebuildInventory();
		domain.gasTopologyCoordinator.refresh();
		domain.pressurePhaseGeometry = domain.gasState.frame.phase();
		domain.pressureSystem.faces.classify();
		domain.pressureSystem.solidFaces.rebuild();
		rebuildWallSupport();
		domain.domainFlowFields.rebuildSurfaceTensionPressure();
		domain.pressureGeometryDirty = false;
	}

	void rebuildVelocity() {
		rebuildInventory();
		derivePreparedVelocity();
	}
	private void derivePreparedVelocity(){
		domain.mac.deriveVelocity(massX, massY, massZ);
		domain.bodyForceStage.enforceSolidFaceVelocities();
		domain.velocityGridDirty = false;
	}

	/** A wall-supported layer has no independent normal inertia above its unresolved free face. */
	private void rebuildWallSupport(){
		var phase=domain.pressurePhaseGeometry;var cut=domain.geometry.cutCells;
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
		return wallPhase==domain.pressurePhaseGeometry&&domain.liquidCells.hasSameGeometry(wallInventory)?unresolvedWall[axis]:null;
	}
	boolean isUnresolvedWallFace(int axis,int face){
		return wallPhase==domain.pressurePhaseGeometry&&domain.liquidCells.hasSameGeometry(wallInventory)&&unresolvedWall[axis][face];
	}

	/** Retires existing normal momentum once, before any force can act on the constrained mode. */
	void constrainUnresolvedWallMomentum(){
		double[][] old={domain.mac.momentumX,domain.mac.momentumY,domain.mac.momentumZ};boolean changed=false;
		for(int axis=0;axis<3&&!changed;axis++)for(int face=0;face<old[axis].length;face++)if(old[axis][face]!=0&&isUnresolvedWallFace(axis,face)){changed=true;break;}
		if(!changed)return;
		double[][] values={old[0].clone(),old[1].clone(),old[2].clone()};double[] heat=new double[domain.cellCount],impulse=new double[3];
		for(int axis=0;axis<3;axis++)for(int face=0;face<values[axis].length;face++){
			double p=values[axis][face];if(p==0||!isUnresolvedWallFace(axis,face))continue;
			int f=domain.pressureSystem.faces.offsets[axis]+face,a=domain.pressureSystem.faces.firstCell[f],b=domain.pressureSystem.faces.secondCell[f];
			double total=cellMass[a]+cellMass[b],energy=p*p/total,first=energy*(cellMass[a]/total);
			heat[a]+=first;heat[b]+=energy-first;impulse[axis]-=p;values[axis][face]=0;
		}
		var byCell=new java.util.TreeMap<Integer,SignedDyadic>();
		for(int cell=0;cell<heat.length;cell++)if(heat[cell]>0)byCell.put(cell,SignedDyadic.of(heat[cell]));
		var plan=domain.liquidCells.planEdits(java.util.List.of()).withMechanicalHeat(byCell);
		var audit=domain.thermalState==null?null:domain.thermalState.auditLedger().withMechanicalWork(plan.mechanicalHeat()).withRoundoff(plan.energyRoundoff());
		if(audit!=null)audit.expectedInventory();
		plan.commit();domain.mac.restoreMomentum(new MacMomentumState(domain.sizeX,domain.sizeY,domain.sizeZ,values[0],values[1],values[2]));
		recordWallImpulse(new WallImpulse(impulse[0],impulse[1],impulse[2]));
		if(audit!=null)domain.thermalState.restoreAuditLedger(audit);
		// Heat changes U alone; the existing mass, PLIC and gas frame stay current.
		inventoryRevision=domain.liquidCells.revision();
		domain.carrierPropertiesWorkspace.invalidate();derivePreparedVelocity();
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

	/** Publishes explicit velocity input through the same stationary-wall impulse and heat plan. */
	void publishMomentum() {
		rebuildInventory();
		double[][] values=new double[3][];
		for(int axis=0;axis<3;axis++){
			float[] velocity=axis==0?domain.mac.velocityX:axis==1?domain.mac.velocityY:domain.mac.velocityZ;
			double[] mass=axis==0?massX:axis==1?massY:massZ;values[axis]=new double[mass.length];
			for(int face=0;face<mass.length;face++)values[axis][face]=mass[face]*velocity[face];
		}
		var prepared=prepareMomentumInput(values,domain.liquidCells,null);
		if(!prepared.heatByCell().isEmpty()){
			var heat=domain.liquidCells.planEdits(java.util.List.of()).withMechanicalHeat(prepared.heatByCell());
			var audit=domain.thermalState==null?null:domain.thermalState.auditLedger()
					.withMechanicalWork(heat.mechanicalHeat()).withRoundoff(heat.energyRoundoff());
			heat.commit();if(audit!=null)domain.thermalState.restoreAuditLedger(audit);
			domain.markCellInventoryChanged();
		}
		commitMomentum(prepared);
		domain.mac.deriveVelocity(massX,massY,massZ);
	}

	record WallImpulse(double x,double y,double z) { }
	void commitMomentum(CellMomentumTransport.Prepared prepared){domain.mac.restoreMomentum(prepared.momentum());recordWallImpulse(prepared.wallImpulse());}
	void commitMomentum(ExternalMomentum prepared){domain.mac.restoreMomentum(prepared.momentum());recordWallImpulse(prepared.wallImpulse());}
	private void recordWallImpulse(WallImpulse impulse){
		domain.lastLiquidBoundaryImpulseX+=impulse.x();domain.lastLiquidBoundaryImpulseY+=impulse.y();domain.lastLiquidBoundaryImpulseZ+=impulse.z();
	}
	void recordSupportedWeightImpulse(double impulseY){recordWallImpulse(new WallImpulse(0,impulseY,0));}
	boolean isWallFace(int axis,int face,byte[] masks){
		int nx=domain.sizeX+(axis==0?1:0),ny=domain.sizeY+(axis==1?1:0);
		int x=face%nx,y=face/nx%ny,z=face/(nx*ny),coordinate=axis==0?x:axis==1?y:z,size=axis==0?domain.sizeX:axis==1?domain.sizeY:domain.sizeZ;
		if(coordinate==0||coordinate==size)return true;
		if(masks==null)return !(domain.geometry.cutCells.openFaceField(axis)[face]>0);
		int b=domain.domainFlowFields.cellIndex(x,y,z),a=b-(axis==0?1:axis==1?domain.sizeX:domain.sizeX*domain.sizeY);
		for(int micro=0;micro<8;micro++)if((micro>>>axis&1)!=0&&(masks[a]&1<<micro)==0&&(masks[b]&1<<(micro^(1<<axis)))==0)return false;
		return true;
	}
	/** A stationary inelastic normal constraint transfers -P to the liquid and its kinetic energy to local U. */
	WallImpulse stopWallMomentum(double[][] momentum,long[] cellMass,double[] heat,byte[] masks){
		double[] impulse=new double[3];
		for(int axis=0;axis<3;axis++)for(int face=0;face<momentum[axis].length;face++){
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
		return new ExternalMomentum(new MacMomentumState(domain.sizeX,domain.sizeY,domain.sizeZ,values[0],values[1],values[2]),java.util.Map.copyOf(byCell),0,walls);
	}

	CellMomentumTransport.Prepared prepareMomentumTransport(PreparedLiquid liquid) {
		if(liquid.preparedMomentum()!=null)return liquid.preparedMomentum();
		if (momentumTransport == null) momentumTransport = new CellMomentumTransport(domain);
		return momentumTransport.prepare(liquid);
	}
	CellMomentumTransport.Prepared prepareMomentumTransport(ConservativeLiquidFlux flux,LiquidCellInventory.TransportQuantities quantities) {
		if(momentumTransport==null)momentumTransport=new CellMomentumTransport(domain);
		return momentumTransport.prepare(flux,quantities);
	}

	/** Stationary insertion adds inertia; extraction removes the carried momentum at its current velocity. */
	record ExternalMomentum(MacMomentumState momentum, java.util.Map<Integer, SignedDyadic> heatByCell, double removedKineticEnergy,WallImpulse wallImpulse) { }
	ExternalMomentum prepareExternalMomentum(LiquidCellInventory.Plan edit) {return prepareExternalMomentum(edit,null);}
	ExternalMomentum prepareExternalMomentum(LiquidCellInventory.Plan edit,byte[] masks) {
		domain.domainFlowFields.ensureInventoryGrid();
		if (domain.velocityGridDirty) rebuildVelocity();
		var heat = new java.util.TreeMap<Integer, SignedDyadic>(); double removedEnergy = 0;
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
				result[axis][face] = Math.min(mass[face], after) * velocity[face];
				double speed = velocity[face], kinetic = .5 * mass[face] * speed * speed;
				if (after > mass[face] && mass[face] > 0) {
					double dissipated = kinetic * ((after - mass[face]) / after);
					if (dissipated > 0) {
						double total = (left < 0 ? 0 : futureMass[left]) + (right < 0 ? 0 : futureMass[right]);
						var exact = SignedDyadic.of(dissipated);
						var first = left < 0 || futureMass[left] == 0 ? SignedDyadic.ZERO
								: right < 0 || futureMass[right] == 0 ? exact : SignedDyadic.of(dissipated * (futureMass[left] / total));
						if (first.signum() > 0) heat.merge(left, first, SignedDyadic::add);
						var second = exact.subtract(first);
						if (second.signum() > 0) heat.merge(right, second, SignedDyadic::add);
					}
				} else if (after < mass[face]) removedEnergy += .5 * (mass[face] - after) * speed * speed;
			}
		}
		var constrained=prepareMomentumInput(result,edit.future(),masks);
		for(var entry:constrained.heatByCell().entrySet())heat.merge(entry.getKey(),entry.getValue(),SignedDyadic::add);
		return new ExternalMomentum(constrained.momentum(), java.util.Map.copyOf(heat), removedEnergy,constrained.wallImpulse());
	}

	PreparedLiquid prepareLiquid(float dt){
		if(!(Float.isFinite(dt)&&dt>0))throw new IllegalArgumentException("Liquid transport duration must be positive");
		domain.domainFlowFields.ensurePressureGeometry();
		if(liquidTransport==null)liquidTransport=new ConservativeLiquidFlux(domain);
		long[] occupied=domain.granularCoupling==null?new long[domain.cellCount]:domain.granularState!=null&&domain.granularState.parcels.count()>0?domain.granularNextCoupling.copySolidSvq():domain.granularCoupling.copySolidSvq();
		liquidTransport.prepare(dt,occupied,quantities->{
			liquidTransport.preparedMomentum=prepareMomentumTransport(liquidTransport,quantities);
			return liquidTransport.preparedMomentum.heatByCell();
		});
		return prepareLiquid(liquidTransport,domain.liquidCells,occupied);
	}
	PreparedLiquid prepareLiquid(ConservativeLiquidFlux flux){return prepareLiquid(flux,domain.liquidCells);}
	PreparedLiquid prepareLiquid(ConservativeLiquidFlux flux,LiquidCellInventory source){
		return prepareLiquid(flux,source,null);
	}
	private PreparedLiquid prepareLiquid(ConservativeLiquidFlux flux,LiquidCellInventory source,long[] occupied){
		var inventory=flux.prepareInventory(source);
		return new PreparedLiquid(inventory,flux,flux.gasAllocation(),occupied);
	}
	void transport(float dt) {
		var liquid = prepareLiquid(dt);
		var momentum = prepareMomentumTransport(liquid);
		var inventory = liquid.preparedMomentum()==null?liquid.inventory().withMechanicalHeat(momentum.heatByCell()):liquid.inventory();
		var gas = domain.gasTopologyCoordinator.planAfterLiquidCells(inventory.future(), liquid.occupied, liquid.gasAllocation());
		var auditAfter = domain.thermalState == null ? null : domain.thermalState.auditLedger()
				.withMechanicalWork(inventory.mechanicalHeat()).withRoundoff(inventory.energyRoundoff());
		if(auditAfter!=null&&gas!=null&&gas.thermalPlan()!=null){
			var thermal=gas.thermalPlan();auditAfter=auditAfter.withExternalSourceEnergy(thermal.sourceEnergy())
					.withExternalSinkEnergy(thermal.sinkEnergy()).withMechanicalWork(thermal.mechanicalWork()).withRoundoff(thermal.roundoff());
		}
		if(auditAfter!=null)auditAfter.expectedInventory();
		inventory.requireCurrent();
		if (gas != null) domain.gasTopologyCoordinator.requireBeforeCarrierCommit(gas);
		domain.granularMechanicalStage.commitCollision();
		inventory.commit();
		commitMomentum(momentum);
		domain.markCellInventoryChanged();
		if (gas != null) domain.gasTopologyCoordinator.commitPrepared(gas,liquid.occupied);
		if (auditAfter != null) domain.thermalState.restoreAuditLedger(auditAfter);
		for (int cell : inventory.changedCells()) if (domain.chemicalParcels != null) domain.chemicalParcels.markDirtyCell(cell);
	}

	double faceMass(int first, int second) { return .5 * (cellMass[first] + cellMass[second]); }
}
