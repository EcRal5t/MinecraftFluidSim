package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;
import mmm.units.SignedDyadic;
import java.util.Arrays;

/** Immutable geometry and capacity derived from the persistent micro phase occupation. */
final class GasRuntimeGeometry {
	private final float cellSize;
	private final boolean sealedExterior;
	private final long[] occupiedSvq,sourceOccupiedSvq;
	private final byte[] sourceCollisionMasks;
	private final LiquidCellInventory.Snapshot sourceCells,targetCells;
	private final PhaseOccupancyState sourceOccupancy,occupancy;
	private final FlipPressurePhaseGeometry phase;
	private final GasComplementGeometry geometry;
	private final GasChamberTopology topology;
	private final GasChamberCapacity capacity;
	private final int[] minimumPieceKeys;

	private GasRuntimeGeometry(FluidDomain domain,LiquidCellInventory future,long[] occupied,boolean sealed,
	                           byte[] futureMasks,PhaseOccupancyState futureOccupancy,
	                           FlipPressurePhaseGeometry preparedPhase,GasComplementGeometry preparedGeometry) {
		if(domain==null||future==null||occupied==null||occupied.length!=domain.cellCount||futureOccupancy==null
				||futureOccupancy.cellCount()!=domain.cellCount)throw new IllegalArgumentException("Incomplete phase geometry candidate");
		cellSize=domain.config.cellSize();sealedExterior=sealed;sourceCells=domain.liquidCells.snapshot();targetCells=future.snapshot();
		sourceOccupancy=domain.phaseOccupancy;occupancy=futureOccupancy;occupiedSvq=occupied.clone();
		sourceOccupiedSvq=domain.granularCoupling==null?new long[domain.cellCount]:domain.granularCoupling.copySolidSvq();
		sourceCollisionMasks=domain.geometry.cutCells.copyMicroMasks();
		byte[] masks=futureMasks==null?sourceCollisionMasks:futureMasks;
		if(masks.length!=domain.cellCount)throw new IllegalArgumentException("Future phase collision shape differs");
		FlipCutCellGeometry cut=null;
		if(preparedPhase==null||preparedGeometry==null){cut=new FlipCutCellGeometry(domain.sizeX,domain.sizeY,domain.sizeZ);cut.restoreMicroMasks(masks);}
		if(preparedPhase==null){phase=new FlipPressurePhaseGeometry(domain.sizeX,domain.sizeY,domain.sizeZ);phase.rebuildOccupancy(occupancy,cut,occupiedSvq);}
		else phase=preparedPhase;
		var old=domain.gasState.frame;boolean[] changed=old==null?null:new boolean[domain.cellCount];
		if(preparedGeometry==null){
			boolean[] solid=new boolean[domain.cellCount];for(int cell=0;cell<solid.length;cell++)solid[cell]=cut.fullySolid(cell);
			if(old!=null)for(int cell=0;cell<changed.length;cell++)changed[cell]=!phase.sameCellGeometry(cell,old.phase);
			geometry=GasComplementGeometry.capture(domain.sizeX,domain.sizeY,domain.sizeZ,cellSize,solid,cut,phase,
					old==null?null:old.geometry,changed,targetCells);
		}else geometry=preparedGeometry;
		topology=GasChamberTopology.capture(geometry,sealed);
		capacity=GasChamberCapacity.capture(domain,geometry,topology,occupiedSvq,targetCells,old);
		minimumPieceKeys=new int[topology.chambers().size()+1];Arrays.fill(minimumPieceKeys,-1);
		for(var chamber:topology.chambers())minimumPieceKeys[chamber.node()]=chamber.minimumPiece();
	}
	static GasRuntimeGeometry capture(FluidDomain domain,long[] occupied,boolean sealed) {
		domain.ensurePhaseOccupancy();
		return new GasRuntimeGeometry(domain,domain.liquidCells,occupied,sealed,null,domain.phaseOccupancy,null,null);
	}
	static GasRuntimeGeometry captureCellFuture(FluidDomain domain,LiquidCellInventory future,long[] occupied,
	                                           boolean sealed,byte[] masks,PhaseOccupancyState futureOccupancy) {
		return captureCellFuture(domain,future,occupied,sealed,masks,futureOccupancy,null,null);
	}
	static GasRuntimeGeometry captureCellFuture(FluidDomain domain,LiquidCellInventory future,long[] occupied,
	                                           boolean sealed,byte[] masks,PhaseOccupancyState futureOccupancy,
	                                           FlipPressurePhaseGeometry phase,GasComplementGeometry geometry) {
		return new GasRuntimeGeometry(domain,future,occupied,sealed,masks,futureOccupancy,phase,geometry);
	}
	static GasRuntimeGeometry allDry(FluidDomain domain,boolean sealed) {
		return allDry(domain,new long[domain.cellCount],sealed);
	}
	static GasRuntimeGeometry allDry(FluidDomain domain,long[] occupied,boolean sealed) {
		var empty=new LiquidCellInventory(domain.cellCount,domain.liquidCells.chemicalRegistry());
		return new GasRuntimeGeometry(domain,empty,occupied,sealed,null,new PhaseOccupancyState(new double[8*domain.cellCount]),null,null);
	}
	boolean matchesCarrierGeometry(FluidDomain domain,long[] occupied) {
		return matchesCarrierInventory(domain,occupied)&&domain.phaseOccupancy!=null
				&&domain.phaseOccupancy.geometryIdentity()==occupancy.geometryIdentity();
	}
	boolean matchesCarrierInventory(FluidDomain domain,long[] occupied) {
		return matchesGeometryInputs(domain,occupied,occupiedSvq,null)&&domain.liquidCells.hasSameGeometry(targetCells);
	}
	boolean matchesSourceCarrierGeometry(FluidDomain domain,long[] occupied) {
		return matchesGeometryInputs(domain,occupied,sourceOccupiedSvq,sourceCollisionMasks)
				&&domain.liquidCells.hasSameGeometry(sourceCells)&&domain.phaseOccupancy==sourceOccupancy;
	}
	private boolean matchesGeometryInputs(FluidDomain domain,long[] occupied,long[] expected,byte[] masks) {
		if(domain==null||domain.sizeX!=geometry.sizeX()||domain.sizeY!=geometry.sizeY()||domain.sizeZ!=geometry.sizeZ()
				||Float.floatToRawIntBits(cellSize)!=Float.floatToRawIntBits(domain.config.cellSize())||!Arrays.equals(occupied,expected))return false;
		for(int cell=0;cell<domain.cellCount;cell++)if(domain.geometry.cutCells.mask(cell)!=(masks==null?geometry.collisionMask(cell):Byte.toUnsignedInt(masks[cell])))return false;
		return true;
	}
	GasComplementGeometry geometry(){return geometry;}
	FlipPressurePhaseGeometry phase(){return phase;}
	PhaseOccupancyState occupancy(){return occupancy;}
	float cellSize(){return cellSize;}
	float planeNormal(int cell,int micro,int axis){return phase.planeNormal(cell,micro,axis);}
	double planeOffset(int cell,int micro){return phase.planeOffset(cell,micro);}
	double liquidFractionExact(int cell,int micro){return phase.liquidFractionExact(cell,micro);}
	PlicPlaneOffset.Result offsetBracket(int cell,int micro){return phase.offsetBracket(cell,micro);}
	SignedDyadic poreVolume(int cell){return phase.poreVolume(cell);}
	GasChamberTopology topology(){return topology;}
	GasChamberCapacity capacity(){return capacity;}
	LiquidCellInventory.Snapshot liquidCells(){return targetCells;}
	long occupiedSvq(int cell){return occupiedSvq[cell];}
	long[] occupiedSvq(){return occupiedSvq.clone();}
	int[] pieceNodes(){return topology.copyPieceNodes();}
	int[] minimumPieceKeys(){return minimumPieceKeys.clone();}
	int nodeCount(){return minimumPieceKeys.length-1;}
	int minimumPiece(int node){return minimumPieceKeys[node];}
	int nodeOfPiece(int piece){return topology.nodeOfPiece(piece);}
	int commonCellNode(int cell) {
		int owner=-1;
		for(int bit=0;bit<8;bit++){int next=nodeOfPiece(8*cell+bit);if(next<0)continue;if(owner>=0&&owner!=next)return -1;owner=next;}
		return owner;
	}
	boolean sealedExterior(){return sealedExterior;}
}
