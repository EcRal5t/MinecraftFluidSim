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
	private ConservativeLiquidFlux preparedFlux;

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
		var grid = domain.markerGrid;
		grid.ensureMarkerCapacity(inventory.physicalSize());
		Arrays.fill(grid.markerCount, 0);
		Arrays.fill(grid.cellMarkerHead, -1); Arrays.fill(grid.cellMarkerTail, -1);
		Arrays.fill(grid.exactCellMarkerMass, 0); Arrays.fill(grid.cellPhaseHead, -1);
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
			grid.exactCellMarkerVolume[cell] = volume.toDouble();
			domain.volume[cell] = (float) volume.toDouble();
			domain.projection.pressureVolume[cell] = volume.toDouble();
			long mass = 0;
			for (var stock : inventory.stocksInCell(cell)) mass = Math.addExact(mass, stock.massUnits());
			cellMass[cell] = Mass.kilograms(mass);
			grid.exactCellMarkerMass[cell] = domain.unitScale.normalizedMass(mass);
			grid.cellDensity[cell] = mass > 0 ? grid.exactCellMarkerMass[cell] / volume.toDouble() : LiquidCarrierProperties.AIR_DENSITY;
		}
		for (int ordinal = 0; ordinal < inventory.physicalSize(); ordinal++) {
			var stock = inventory.physicalStockAt(ordinal); int cell = stock.key().cell();
			int previous = grid.cellMarkerTail[cell];
			grid.particleCellPrevious[ordinal] = previous; grid.particleCellNext[ordinal] = -1;
			if (previous < 0) grid.cellMarkerHead[cell] = ordinal; else grid.particleCellNext[previous] = ordinal;
			grid.cellMarkerTail[cell] = ordinal; grid.markerCount[cell]++;
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
		domain.surfaceTensionActive = domain.hasAnySurfaceTension();
		domain.liquidPairTensionActive = domain.hasAnyLiquidPairTension();
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
		domain.rebuildSurfaceTensionPressure();
		domain.pressureGeometryDirty = false;
	}

	void rebuildVelocity() {
		rebuildInventory();
		for (int axis = 0; axis < 3; axis++) {
			double[] mass = axis == 0 ? massX : axis == 1 ? massY : massZ;
			double[] normalized = axis == 0 ? domain.mac.p2gWeightX : axis == 1 ? domain.mac.p2gWeightY : domain.mac.p2gWeightZ;
			float[] flags = axis == 0 ? domain.mac.velocityWeightX : axis == 1 ? domain.mac.velocityWeightY : domain.mac.velocityWeightZ;
			boolean[] valid = axis == 0 ? domain.mac.velocityValidX : axis == 1 ? domain.mac.velocityValidY : domain.mac.velocityValidZ;
			int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0), nz = domain.sizeZ + (axis == 2 ? 1 : 0);
			for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
				int face = (z * ny + y) * nx + x;
				int coordinate = axis == 0 ? x : axis == 1 ? y : z, size = axis == 0 ? domain.sizeX : axis == 1 ? domain.sizeY : domain.sizeZ;
				int right = coordinate < size ? domain.cellIndex(x, y, z) : -1;
				int left = coordinate > 0 ? domain.cellIndex(x - (axis == 0 ? 1 : 0), y - (axis == 1 ? 1 : 0), z - (axis == 2 ? 1 : 0)) : -1;
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

	void publishMomentum() { domain.mac.publishMomentum(massX, massY, massZ); }

	CellMomentumTransport.Prepared prepareMomentumTransport(CellLiquidVolumeTransport.Prepared liquid) {
		if(liquid.preparedMomentum()!=null)return liquid.preparedMomentum();
		if (momentumTransport == null) momentumTransport = new CellMomentumTransport(domain);
		return momentumTransport.prepare(liquid);
	}
	CellMomentumTransport.Prepared prepareMomentumTransport(ConservativeLiquidFlux flux,LiquidCellInventory.TransportQuantities quantities) {
		if(momentumTransport==null)momentumTransport=new CellMomentumTransport(domain);
		return momentumTransport.prepare(flux,quantities);
	}

	/** Stationary insertion adds inertia; extraction removes the carried momentum at its current velocity. */
	MacMomentumState prepareExternalInventoryChange(LiquidCellInventory.Plan edit) { return prepareExternalMomentum(edit).momentum(); }
	record ExternalMomentum(MacMomentumState momentum, java.util.Map<Integer, SignedDyadic> heatByCell, double removedKineticEnergy) { }
	ExternalMomentum prepareExternalMomentum(LiquidCellInventory.Plan edit) {
		domain.ensureInventoryGrid();
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
				int right = coordinate < size ? domain.cellIndex(x, y, z) : -1;
				int left = coordinate > 0 ? domain.cellIndex(x - (axis == 0 ? 1 : 0), y - (axis == 1 ? 1 : 0), z - (axis == 2 ? 1 : 0)) : -1;
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
		return new ExternalMomentum(new MacMomentumState(domain.sizeX, domain.sizeY, domain.sizeZ, result[0], result[1], result[2]), java.util.Map.copyOf(heat), removedEnergy);
	}

	void prepareTransport(float dt) {
		if (domain.cellLiquidVolumeTransport == null) domain.cellLiquidVolumeTransport = new CellLiquidVolumeTransport(domain);
		preparedFlux = domain.cellLiquidVolumeTransport.prepareVolumeFlux(dt);
	}
	FluidDomain.LiquidTransportStats transport(float dt) {
		if (preparedFlux == null) {
			if (domain.cellLiquidVolumeTransport == null) domain.cellLiquidVolumeTransport = new CellLiquidVolumeTransport(domain);
			preparedFlux = domain.cellLiquidVolumeTransport.prepareVolumeFlux(dt);
		}
		var liquid = domain.cellLiquidVolumeTransport.prepareFlux(preparedFlux, dt); preparedFlux = null;
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
		domain.mac.restoreMomentum(momentum.momentum());
		domain.markCellInventoryChanged();
		if (gas != null) domain.gasTopologyCoordinator.commitPrepared(gas,occupied);
		if (auditAfter != null) domain.thermalState.restoreAuditLedger(auditAfter);
		for (int cell : inventory.changedCells()) if (domain.chemicalParcels != null) domain.chemicalParcels.markDirtyCell(cell);
		return FluidDomain.LiquidTransportStats.INACTIVE;
	}

	double faceMass(int first, int second) { return .5 * (cellMass[first] + cellMass[second]); }
}
