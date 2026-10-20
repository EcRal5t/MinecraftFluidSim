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
	private CellMomentumTransport momentumTransport;
	private ConservativeLiquidFlux liquidTransport;
	static final class PreparedLiquid {
		private final LiquidCellInventory.Plan inventory;
		private final ConservativeLiquidFlux flux;
		private final GasVolumeAllocation gas;
		private final LiquidCellInventory source;
		PreparedLiquid(LiquidCellInventory.Plan inventory,ConservativeLiquidFlux flux,GasVolumeAllocation gas){this.inventory=inventory;this.flux=flux;this.gas=gas;this.source=flux.sourceInventory();}
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
		domain.gridDirty = false;
	}

	void rebuildGeometry() {
		rebuildInventory();
		domain.gasTopologyCoordinator.refresh();
		domain.pressurePhaseGeometry = domain.gasState.frame.phase();
		domain.pressureSystem.faces.classify();
		domain.pressureSystem.solidFaces.rebuild();
		domain.domainFlowFields.rebuildSurfaceTensionPressure();
		domain.pressureGeometryDirty = false;
	}

	void rebuildVelocity() {
		rebuildInventory();
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
		domain.mac.deriveVelocity(massX, massY, massZ);
		domain.bodyForceStage.enforceSolidFaceVelocities();
		domain.velocityGridDirty = false;
	}

	/** Publishes explicit velocity input through the same stationary-wall impulse and heat plan. */
	void publishMomentum() {
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
		return prepareLiquid(liquidTransport,domain.liquidCells);
	}
	PreparedLiquid prepareLiquid(ConservativeLiquidFlux flux){return prepareLiquid(flux,domain.liquidCells);}
	PreparedLiquid prepareLiquid(ConservativeLiquidFlux flux,LiquidCellInventory source){
		var inventory=flux.prepareInventory(source);
		return new PreparedLiquid(inventory,flux,flux.gasAllocation(inventory.future()));
	}
	void transport(float dt) {
		var liquid = prepareLiquid(dt);
		var momentum = prepareMomentumTransport(liquid);
		var inventory = liquid.preparedMomentum()==null?liquid.inventory().withMechanicalHeat(momentum.heatByCell()):liquid.inventory();
		long[] occupied = domain.granularCoupling == null ? new long[domain.cellCount]
				: domain.granularState != null && domain.granularState.parcels.count() > 0
				? domain.granularNextCoupling.copySolidSvq() : domain.granularCoupling.copySolidSvq();
		var gas = domain.gasTopologyCoordinator.planAfterLiquidCells(inventory.future(), occupied, liquid.gasAllocation());
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
		if (gas != null) domain.gasTopologyCoordinator.commitPrepared(gas,occupied);
		if (auditAfter != null) domain.thermalState.restoreAuditLedger(auditAfter);
		for (int cell : inventory.changedCells()) if (domain.chemicalParcels != null) domain.chemicalParcels.markDirtyCell(cell);
	}

	double faceMass(int first, int second) { return .5 * (cellMass[first] + cellMass[second]); }
}
