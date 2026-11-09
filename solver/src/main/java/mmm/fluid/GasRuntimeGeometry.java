package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;
import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Immutable fixed-pore component geometry and actual capacity derived from phase occupation. */
final class GasRuntimeGeometry {
	private final float cellSize;
	private final boolean sealedExterior;
	private final long[] occupiedSvq,sourceOccupiedSvq;
	private final FixedGeometrySnapshot sourceFixedGeometry;
	private final LiquidCellInventory.Snapshot sourceCells,targetCells;
	private final PhaseOccupancyState sourceOccupancy,occupancy;
	private final FlipPressurePhaseGeometry phase;
	private final GasComplementGeometry geometry;
	private final GasChamberTopology topology;
	private final GasChamberCapacity capacity;
	private final SignedDyadic[] openedPoreVolumes;

	private GasRuntimeGeometry(HostFluidState domain,LiquidCellInventory future,long[] occupied,boolean sealed,
	                           byte[] futureMasks,PhaseOccupancyState futureOccupancy,
                               FlipPressurePhaseGeometry preparedPhase,GasComplementGeometry preparedGeometry,GasComponentCapacity.Prepared preparedCapacity,
                               int[] restoredCells,int restoredNodes) {
		if(domain==null||future==null||occupied==null||occupied.length!=domain.cellCount||futureOccupancy==null
				||futureOccupancy.cellCount()!=domain.cellCount)throw new IllegalArgumentException("Incomplete phase geometry candidate");
		cellSize=domain.config.cellSize();sealedExterior=sealed;sourceCells=domain.liquidCells.snapshot();targetCells=future.snapshot();
		sourceOccupancy=domain.phaseOccupancy;occupancy=futureOccupancy;occupiedSvq=occupied.clone();
		sourceOccupiedSvq=domain.occupiedSolidSvq();
		sourceFixedGeometry=domain.geometry.cutCells.snapshot();
		byte[] masks=futureMasks==null?sourceFixedGeometry.masksView():futureMasks;
		if(masks.length!=domain.cellCount)throw new IllegalArgumentException("Future phase collision shape differs");
		var old=domain.gasState.frame;
		FlipCutCellGeometry cut=null;
		if(preparedPhase==null||preparedGeometry==null){
			cut=domain.geometry.cutCells;
			if(futureMasks!=null){cut=new FlipCutCellGeometry(domain.grid);cut.restoreMicroMasks(masks);}
		}
		if(preparedPhase==null&&old!=null&&occupancy.geometryIdentity()==old.occupancy.geometryIdentity()
				&&Arrays.equals(occupiedSvq,old.occupiedSvq)&&old.geometry.matchesCollisionMasks(masks))phase=old.phase;
		else if(preparedPhase==null){phase=new FlipPressurePhaseGeometry(domain.grid);phase.rebuildOccupancy(occupancy,cut,occupiedSvq);}
		else phase=preparedPhase;
		if(preparedGeometry==null&&old!=null&&phase==old.phase&&future.hasSameGeometry(old.targetCells))geometry=old.geometry;
		else if(preparedGeometry==null){
			geometry=GasComplementGeometry.capture(domain.sizeX,domain.sizeY,domain.sizeZ,cellSize,cut,phase,
					old==null?null:old.geometry,old==null?null:old.phase,targetCells,preparedCapacity);
		}else geometry=preparedGeometry;
		topology=restoredCells==null?GasChamberTopology.capture(geometry,sealed)
				:GasChamberTopology.restore(geometry,sealed,restoredCells,restoredNodes);
		capacity=GasChamberCapacity.capturePrepared(geometry,topology);
		openedPoreVolumes=captureOpenedPore(old);
	}
	/** Identity is a cache shortcut; independently settled plans can carry equal support volumes. */
	static boolean sameLiquidVolumes(LiquidCellInventory actual,LiquidCellInventory.Snapshot expected){
		if(actual.hasSameGeometry(expected))return true;
		if(expected==null||actual.cellCount()!=expected.cellCount())return false;
		for(int cell=0;cell<expected.cellCount();cell++){
			var before=expected.rows().get(cell);var after=actual.stocksInCell(cell);if(before==after)continue;
			for(var stock:before){
				int support=stock.key().supportMask();
				if(!supportVolume(before,support).equals(supportVolume(after,support)))return false;
			}
			for(var stock:after){
				int support=stock.key().supportMask();
				if(!supportVolume(before,support).equals(supportVolume(after,support)))return false;
			}
		}
		return true;
	}
	private static SignedDyadic supportVolume(List<LiquidCellInventory.Stock> stocks,int support){
		var volume=SignedDyadic.ZERO;for(var stock:stocks)if(stock.key().supportMask()==support)volume=volume.add(stock.volume());
		return volume;
	}

	/** Only actual solid/collision changes create new vacuum control volume. */
	private SignedDyadic[] captureOpenedPore(GasRuntimeGeometry old){
		if(old==null||geometry==old.geometry||Arrays.equals(occupiedSvq,old.occupiedSvq)&&geometry.sameCollisionMasks(old.geometry))return null;
		var previous=old.geometry.actualCapacity();var future=geometry.actualCapacity();SignedDyadic[] opened=null;
		for(int cell=0;cell<occupiedSvq.length;cell++){
			if(occupiedSvq[cell]==old.occupiedSvq[cell]&&geometry.collisionMask(cell)==old.geometry.collisionMask(cell))continue;
			for(int at=future.offsets[cell];at<future.offsets[cell+1];at++){
				var component=future.components.get(at);
				if(component.availableVolumeNormalized().signum()==0)continue;
				int node=topology.nodeOfComponent(cell,component.supportMask());if(node<=0)continue;
				var oldPore=SignedDyadic.ZERO;
				for(int i=previous.offsets[cell];i<previous.offsets[cell+1];i++){
					var support=previous.components.get(i);int overlap=support.supportMask()&component.supportMask();
					if(overlap==support.supportMask())oldPore=oldPore.add(support.poreVolumeNormalized());
					else if(overlap!=0)oldPore=oldPore.add(support.poreVolumeNormalized().multiply(
							SignedDyadic.of(Integer.bitCount(overlap)/(double)Integer.bitCount(support.supportMask()))));
				}
				var gain=component.poreVolumeNormalized().subtract(oldPore);if(gain.signum()<=0)continue;
				SignedDyadic volume;
				int newOpen=old.geometry.collisionMask(cell)&~geometry.collisionMask(cell)&component.supportMask();
				if(newOpen!=0){
					var dry=geometry.component(cell,component.supportMask());int selected=newOpen&dry.dryMicroMask();if(selected==0)continue;
					volume=component.availableVolumeNormalized();
					if(selected!=dry.dryMicroMask()){
						double fraction=0;
						for(int bits=selected;bits!=0;bits&=bits-1)
							fraction+=geometry.pieceNormalizedVolume(8*cell+Integer.numberOfTrailingZeros(bits));
						volume=volume.multiply(SignedDyadic.of(fraction/dry.normalizedVolume()));
					}
				}else volume=component.availableVolumeNormalized().compareTo(gain)<0?component.availableVolumeNormalized():gain;
				if(volume.signum()>0){
					if(opened==null){opened=new SignedDyadic[nodeCount()];Arrays.fill(opened,SignedDyadic.ZERO);}
					opened[node-1]=opened[node-1].add(volume);
				}
			}
		}
		return opened;
	}
	SignedDyadic openedPoreVolume(int node){return openedPoreVolumes==null?SignedDyadic.ZERO:openedPoreVolumes[node-1];}

	static GasRuntimeGeometry capture(HostFluidState domain,long[] occupied,boolean sealed) {
		domain.ensurePhaseOccupancy();
		return new GasRuntimeGeometry(domain,domain.liquidCells,occupied,sealed,null,domain.phaseOccupancy,null,null,null,null,0);
	}
	static GasRuntimeGeometry captureRestored(HostFluidState domain,long[] occupied,boolean sealed,int[] savedCells,int savedNodes) {
		domain.ensurePhaseOccupancy();
		return new GasRuntimeGeometry(domain,domain.liquidCells,occupied,sealed,null,domain.phaseOccupancy,null,null,null,savedCells,savedNodes);
	}
	static GasRuntimeGeometry captureCellFuture(HostFluidState domain,LiquidCellInventory future,long[] occupied,
	                                           boolean sealed,byte[] masks,PhaseOccupancyState futureOccupancy) {
		return captureCellFuture(domain,future,occupied,sealed,masks,futureOccupancy,null,null,null);
	}
	static GasRuntimeGeometry captureCellFuture(HostFluidState domain,LiquidCellInventory future,long[] occupied,
	                                           boolean sealed,byte[] masks,PhaseOccupancyState futureOccupancy,
	                                           FlipPressurePhaseGeometry phase,GasComplementGeometry geometry,GasComponentCapacity.Prepared capacity) {
		return new GasRuntimeGeometry(domain,future,occupied,sealed,masks,futureOccupancy,phase,geometry,capacity,null,0);
	}
	static GasRuntimeGeometry allDry(HostFluidState domain,boolean sealed) {
		return allDry(domain,new long[domain.cellCount],sealed);
	}
	static GasRuntimeGeometry allDry(HostFluidState domain,long[] occupied,boolean sealed) {
		var empty=new LiquidCellInventory(domain.cellCount,domain.liquidCells.chemicalRegistry());
		return new GasRuntimeGeometry(domain,empty,occupied,sealed,null,new PhaseOccupancyState(new double[8*domain.cellCount]),null,null,null,null,0);
	}
	boolean matchesCarrierGeometry(HostFluidState domain,long[] occupied) {
		return matchesCarrierInventory(domain,occupied)&&domain.phaseOccupancy!=null
				&&domain.phaseOccupancy.geometryIdentity()==occupancy.geometryIdentity();
	}
	boolean matchesCarrierInventory(HostFluidState domain,long[] occupied) {
		return matchesGeometryInputs(domain,occupied,occupiedSvq,null)&&sameLiquidVolumes(domain.liquidCells,targetCells);
	}
	boolean matchesSourceCarrierGeometry(HostFluidState domain,long[] occupied) {
		return matchesGeometryInputs(domain,occupied,sourceOccupiedSvq,sourceFixedGeometry)
				&&sameLiquidVolumes(domain.liquidCells,sourceCells)&&domain.phaseOccupancy==sourceOccupancy;
	}
	private boolean matchesGeometryInputs(HostFluidState domain,long[] occupied,long[] expected,FixedGeometrySnapshot fixed) {
		if(domain==null||domain.sizeX!=geometry.sizeX()||domain.sizeY!=geometry.sizeY()||domain.sizeZ!=geometry.sizeZ()
				||Float.floatToRawIntBits(cellSize)!=Float.floatToRawIntBits(domain.config.cellSize())||!Arrays.equals(occupied,expected))return false;
		var expectedFixed=fixed==null?geometry.actualCapacity().fixedGeometry:fixed;
		if(domain.geometry.cutCells.snapshot()!=expectedFixed)
			for(int cell=0;cell<domain.cellCount;cell++)if(domain.geometry.cutCells.mask(cell)!=expectedFixed.mask(cell))return false;
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
	int nodeCount(){return topology.chambers().size();}
	int minimumPiece(int node){return topology.chambers().get(node-1).minimumPiece();}
	int nodeOfPiece(int piece){return topology.nodeOfPiece(piece);}
	int commonCellNode(int cell) {
		int owner=-1;
		for(int support:CellRemapMicroTopology.components(geometry.collisionMask(cell))){
			int next=topology.nodeOfComponent(cell,support);if(next<0)continue;if(owner>=0&&owner!=next)return -1;owner=next;
		}
		return owner;
	}
	boolean sealedExterior(){return sealedExterior;}
}
