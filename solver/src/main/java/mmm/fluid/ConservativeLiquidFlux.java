package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Conservative liquid displacement for a private solid-inventory edit. */
final class ConservativeLiquidFlux {
	final int sizeX, sizeY, sizeZ, cellCount, physicalFaceCount;
	final int[] left, right, axis;
	private final HostFluidState domain;
	private final int[] coarseFaceAt;
	private final int[] capacityCells;
	private int capacityCount;
	private final PhaseQuantitySettlement.RouteBuffer numericalRoutes=new PhaseQuantitySettlement.RouteBuffer();
	private static final SignedDyadic[] OPEN_CAPACITY=openCapacities();
	private static SignedDyadic[] openCapacities(){var values=new SignedDyadic[9];for(int n=0;n<=8;n++)values[n]=SignedDyadic.of(n,-3);return values;}

	private double[] finalDry;
	private GasComponentCapacity.Prepared preparedCapacity;
	private PhaseSourceColumns gasTags, nextGasTags, macroFlows;
	private FlipPressurePhaseGeometry phase;

	private LiquidCellInventory input, working;
	private LiquidCellInventory.Plan prepared;
	private GasVolumeAllocation allocation;
	private record GasCapacityGroups(int[] receiverGroup,SignedDyadic[] upper) { }
	private long[] targetOccupied;

	/** Builds the shared coarse face indexing once. */
	ConservativeLiquidFlux(HostFluidState domain) {
		this.domain = domain;
		sizeX = domain.sizeX;
		sizeY = domain.sizeY;
		sizeZ = domain.sizeZ;
		cellCount = domain.cellCount;
		physicalFaceCount = (sizeX - 1) * sizeY * sizeZ + sizeX * (sizeY - 1) * sizeZ + sizeX * sizeY * (sizeZ - 1);
		left = new int[physicalFaceCount];
		right = new int[physicalFaceCount];
		axis = new int[physicalFaceCount];
		coarseFaceAt = new int[6 * cellCount];
		Arrays.fill(coarseFaceAt, - 1);
		int f = 0;
		for (int z = 0; z<sizeZ; z++) for (int y = 0; y<sizeY; y++) for (int x = 1; x<sizeX; x++) f = coarseFace(f, (z * sizeY + y) * sizeX + x - 1, 0);
		for (int z = 0; z<sizeZ; z++) for (int y = 1; y<sizeY; y++) for (int x = 0; x<sizeX; x++) f = coarseFace(f, (z * sizeY + y - 1) * sizeX + x, 1);
		for (int z = 1; z<sizeZ; z++) for (int y = 0; y<sizeY; y++) for (int x = 0; x<sizeX; x++) f = coarseFace(f, ((z - 1) * sizeY + y) * sizeX + x, 2);
		capacityCells=new int[cellCount];
		gasTags = new PhaseSourceColumns(cellCount);
		nextGasTags = new PhaseSourceColumns(cellCount);
		macroFlows = new PhaseSourceColumns(2 * physicalFaceCount);
	}

	private int coarseFace(int f, int a, int dimension) {
		int b = a + (dimension == 0 ? 1 : dimension == 1 ? sizeX : sizeX * sizeY);
		left[f] = a;
		right[f] = b;
		axis[f] = dimension;
		coarseFaceAt[6 * a + 2 * dimension + 1] = f;
		coarseFaceAt[6 * b + 2 * dimension] = f;
		return f + 1;
	}

	private static int faceMicro(int axis, int port) {
		return axis == 0 ? 1 | (port << 1)
				: axis == 1 ? 2 | (port & 1) | ((port & 2) << 1) : 4 | port;
	}

	/** Fixed-open neighbors are evaluated only for an occupied source component. */
	private int neighborSupports(int face, int direction, int sourceSupport) {
		int a=axis[face];
		int targetCell=direction==0?right[face]:left[face];
		int targetMask=domain.geometry.cutCells.mask(targetCell),targets=0;
		for(int port=0;port<4;port++){
			int sourceMicro=faceMicro(a,port)^(direction==0?0:1<<a);
			if((sourceSupport&1<<sourceMicro)==0)continue;
			int targetMicro=sourceMicro^(1<<a);
			targets|=CellRemapMicroTopology.support(targetMask,targetMicro);
		}
		return targets;
	}

	private long[] currentOccupied() {
		return domain.occupiedSolidSvq();
	}

	/** Prepares displacement against the edited pore endpoint. */
	private void preparePlan(long[] occupied, LiquidCellInventory source) {
		domain.ensurePhaseOccupancy();
		input = working = source;
		prepared = null;
		allocation = null;
		targetOccupied = occupied.clone();

		var sourceFrame = domain.gasState.frame;
		// Bed transitions may already have published their private solid columns.
		// Gas and phase sources still belong to the retained geometry generation.
		long[] oldOccupied = sourceFrame == null ? currentOccupied() : sourceFrame.occupiedSvq();
		boolean sourceMatches=sourceFrame!=null&&sourceFrame.matchesCarrierGeometry(domain,oldOccupied);
		if(sourceMatches)phase=sourceFrame.phase();
		else {
			phase=new FlipPressurePhaseGeometry(sizeX,sizeY,sizeZ);
			phase.rebuildOccupancy(domain.phaseOccupancy,domain.geometry.cutCells,PhaseOccupancyTransport.poreVolumes(domain,oldOccupied,null));
		}
		gasTags.reset(cellCount);
		if(sourceMatches){
			for(var component:sourceFrame.capacity().components())if(component.availableVolumeNormalized().signum()>0){
				int cell=component.cell(),support=component.supportMask();
				gasTags.add(cell,sourceFrame.topology().nodeOfComponent(cell,support),component.availableVolumeNormalized().toDouble());
			}
		}else for (int cell = 0; cell < cellCount; cell++) {
			for (int support : CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell))) {
				double capacity = 0, stock = 0;
				int node = domain.gasState.sealedDomainBoundary ? -1 : 0;
				boolean found = false;
				for (int bits = support; bits != 0; bits &= bits - 1) {
					int p = 8 * cell + Integer.numberOfTrailingZeros(bits);
					capacity += microPoreVolume(p);
					int owner = sourceFrame == null ? -1 : sourceFrame.nodeOfPiece(p);
					if (!found && owner >= 0) { node = owner; found = true; }
				}
				for (var row : domain.liquidCells.stocksInCell(cell))
					if (row.key().supportMask() == support) stock += row.volumeFraction();
				double dry = capacity - stock;
				if (dry > 0) gasTags.add(cell, node, dry);
			}
		}
		SignedDyadic[] finalCapacity = cellCapacities(occupied);
		settleDisplacement(finalCapacity,sourceMatches&&Arrays.equals(occupied,oldOccupied));
		allocation = gasAllocation(working);
	}

	private SignedDyadic[] cellCapacities(long[] occupied) {
        var result=new SignedDyadic[cellCount];Arrays.fill(result,SignedDyadic.ZERO);capacityCount=0;
        for(int cell=0;cell<cellCount;cell++){
            int support=~domain.geometry.cutCells.mask(cell)&255;
            long remaining=((long)Integer.bitCount(support)<<21)-occupied[cell];
            if(occupied[cell]<0||remaining<0)throw new IllegalStateException("Solid occupation exceeds actual open pore");
            if(support==0)continue;
            result[cell]=occupied[cell]==0?OPEN_CAPACITY[Integer.bitCount(support)]:SignedDyadic.of(remaining,-24);
            capacityCells[capacityCount++]=cell;
        }
        return result;
    }

	private void settleDisplacement(SignedDyadic[] capacities,boolean samePores) {
		prepared=settle(capacities);
		working=prepared.future();

		var sourceFrame=domain.gasState.frame;
		preparedCapacity=samePores&&working.hasSameGeometry(sourceFrame.liquidCells())?sourceFrame.geometry().actualCapacity()
				:GasComponentCapacity.prepare(domain.geometry.cutCells,capacities,working.snapshot(),
					sourceFrame==null?null:sourceFrame.geometry().actualCapacity());
		if(sourceFrame!=null&&preparedCapacity==sourceFrame.geometry().actualCapacity())phase=sourceFrame.phase();
		else{
			phase=new FlipPressurePhaseGeometry(sizeX,sizeY,sizeZ);
			phase.rebuildInventory(domain.geometry.cutCells,working.snapshot(),preparedCapacity,sourceFrame==null?null:sourceFrame.phase());
		}
		finalDry=preparedCapacity.dry;
		advanceGasSources(prepared.transfers());
		var swap=gasTags;gasTags=nextGasTags;nextGasTags=swap;
		normalizeGasSources();
	}
	/** Advects gas only on full fixed openings; restricted openings belong to the orifice solver. */
	private void advanceGasSources(List<LiquidCellInventory.Transfer> transfers) {
		macroFlows.reset(2*physicalFaceCount);
		// Displaced liquid moves gas in the opposite direction.
		for(var transfer:transfers){
			int face=transfer.face(),row=2*face+(transfer.source().cell()==left[face]?0:1);
			long key=(long)transfer.source().supportMask()<<8|transfer.target().supportMask();
			macroFlows.add(row^1,(key&255)<<8|(key>>>8),transfer.volume().toDouble());
		}
		nextGasTags.reset(cellCount);
		double[] outgoing=new double[cellCount],accepted=new double[cellCount];
		for(int row=0;row<macroFlows.rowCount();row++){
			int face=row/2;if(macroFlows.first(row)<0||!advectsGas(face))continue;
			int from=row%2==0?left[face]:right[face];
			for(int e=macroFlows.first(row);e>=0;e=macroFlows.next(e)){
				int source=from;
				if(gasTags.first(source)<0)continue;
				outgoing[source]+=gasFlux(row,e);
			}
		}
		for(int row=0;row<macroFlows.rowCount();row++){
			int face=row/2;if(macroFlows.first(row)<0||!advectsGas(face))continue;
			int from=row%2==0?left[face]:right[face],to=row%2==0?right[face]:left[face];
			for(int e=macroFlows.first(row);e>=0;e=macroFlows.next(e)){
				int source=from;
				if(gasTags.first(source)<0)continue;
				int target=to;
				double gas=gasTags.rowSum(source);
				// A quasi-static cavity keeps its gas on every surviving source support.
				// Only displaced dry control volume supplies source labels to new supports;
				// MAC circulation within an unchanged cavity has no separate gas inventory.
				double movable=Math.max(0,gas-finalDry[source]);
				if(outgoing[source]>0){
					double amount=gasFlux(row,e)*Math.min(1,movable/outgoing[source]);
					copyGas(source,target,amount);accepted[source]+=amount;
				}
			}
		}
		for(int row=0;row<gasTags.rowCount();row++)if(gasTags.first(row)>=0)
			copyGas(row,row,Math.max(0,gasTags.rowSum(row)-accepted[row]));
	}
	private double gasFlux(int row,int entry){
		long key=macroFlows.key(entry),reverse=(key&255)<<8|(key>>>8);
		return Math.max(0,macroFlows.amount(entry)-macroFlows.quantity(row^1,reverse));
	}
	private boolean advectsGas(int face){
		return GasComplementGeometry.fullFixedConnection(domain.geometry.cutCells,left[face],right[face],axis[face]);
	}
	/** Fits source weights to the final dry support geometry. */
	private void normalizeGasSources() {
		for (int i=0;i<capacityCount;i++) {
			int row=capacityCells[i];double dry=finalDry[row];
			if (dry <= 0) {
				gasTags.clear(row);
				continue;
			}
			if (gasTags.first(row)<0) gasTags.add(row, - 1, dry);
			else gasTags.scale(row, dry);
		}
	}

	private void copyGas(int from, int target, double amount) {
		if (amount <= 0) return;
		double gas = gasTags.rowSum(from);
		if (gas <= 0) return;
		for (int e = gasTags.first(from); e >= 0; e = gasTags.next(e)) nextGasTags.add(target, gasTags.key(e), amount * (gasTags.amount(e) / gas));
	}

	/** Allocates the complete coarse inventory shares. */
	private LiquidCellInventory.Plan settle(SignedDyadic[] capacities) {
		var origins = working.stocks();
		prepareRoutes(origins, capacities);
		var minimum=fullLiquidMinimum(capacities);
		var gasCapacityGroups=prepareGasGroups(capacities,minimum);
		var routes=PhaseQuantitySettlement.settle(numericalRoutes,capacities,minimum,
                gasCapacityGroups==null?null:gasCapacityGroups.receiverGroup(),gasCapacityGroups==null?null:gasCapacityGroups.upper(),
                source -> prepareAlternatives(source, capacities));
		return working.planRoutes(routes, FluidThermalBinding.liquidEnergyPartitioner(domain));
	}
	/** A full source uncovers through actual incoming gas; existing interfaces retain their free motion. */
	private SignedDyadic[] fullLiquidMinimum(SignedDyadic[] capacities){
		var frame=domain.gasState.frame;if(frame==null)return null;
		var minimum=new SignedDyadic[capacities.length];
		for(var component:frame.capacity().components()){
			if(component.availableVolumeNormalized().signum()!=0||component.poreVolumeNormalized().signum()==0)continue;
			int cell=component.cell(),support=component.supportMask(),slot=cell;
			if(working!=domain.liquidCells){
				var sourceVolume=SignedDyadic.ZERO;
				for(var stock:working.stocksInCell(cell))if(stock.key().supportMask()==support)sourceVolume=sourceVolume.add(stock.volume());
				// Phase conversion can create a real void before endpoint displacement.
				if(sourceVolume.compareTo(component.poreVolumeNormalized())<0)continue;
			}
			var floor=capacities[slot];
			if(floor.signum()>0)minimum[slot]=floor;
		}
		return minimum;
	}

	/** Displacement starts with retained stock and opens routes as capacity requires. */
	private void prepareRoutes(List<LiquidCellInventory.Stock> origins, SignedDyadic[] capacities) {
		numericalRoutes.reset(origins,capacities.length);
		numericalRoutes.materialLayers(domain.materialRegistry,sizeX,sizeY,domain.config.gravity());
		numericalRoutes.neighborhood(this::visitTransportNeighbors);
	}

	/** Standard residual search opens alternatives only for the actual source being visited. */
	private void prepareAlternatives(int source, SignedDyadic[] capacities) {
		visitTransportNeighbors(source,(face,cell,support)->{
			if(capacities[cell].signum()>0)
				numericalRoutes.alternative(source,face,cell,support);
		});
	}

	/** Physical support adjacency is shared by linear divergence correction and congested residual search. */
	private void visitTransportNeighbors(int source,PhaseQuantitySettlement.NeighborVisitor visitor){
		var key=numericalRoutes.key(source);int cell=key.cell(),support=key.supportMask();
		for(int side=0;side<6;side++){
			int face=coarseFaceAt[6*cell+side];if(face<0)continue;
			int direction=cell==left[face]?0:1,target=direction==0?right[face]:left[face];
			for(int targets=neighborSupports(face,direction,support);targets!=0;){
				int targetSupport=CellRemapMicroTopology.support(domain.geometry.cutCells.mask(target),Integer.numberOfTrailingZeros(targets));
				targets&=~targetSupport;
				visitor.accept(face,target,targetSupport);
			}
		}
	}

	/** A finite chamber constrains aggregate space while liquid may rearrange freely inside it. */
    private GasCapacityGroups prepareGasGroups(SignedDyadic[] capacities,SignedDyadic[] minimum){
        var frame=domain.gasState.frame;if(frame==null||frame.nodeCount()==0)return null;
        int count=frame.nodeCount(),groups=0;
        int[] nodeGroup=new int[count+1];Arrays.fill(nodeGroup,-1);
        for(int node=1;node<=count;node++)if(!domain.gasState.network.inventory(node).isEmpty())nodeGroup[node]=groups++;
        if(groups==0)return null;
        int[] receiverGroup=new int[capacities.length];Arrays.fill(receiverGroup,-1);
        var groupPore=new SignedDyadic[groups];Arrays.fill(groupPore,SignedDyadic.ZERO);
        var groupDry=new SignedDyadic[groups];Arrays.fill(groupDry,SignedDyadic.ZERO);
        var groupRoom=new SignedDyadic[groups];Arrays.fill(groupRoom,SignedDyadic.ZERO);
        var totalCapacity=SignedDyadic.ZERO;
        for(int i=0;i<capacityCount;i++)totalCapacity=totalCapacity.add(capacities[capacityCells[i]]);
        // A chamber's quantity constraint belongs to its actual old dry support;
        // transported source ordering never changes that physical capacity owner.
        for(var component:frame.capacity().components()){
            if(component.availableVolumeNormalized().signum()==0)continue;
            int node=frame.topology().nodeOfComponent(component.cell(),component.supportMask());
            if(node<=0||nodeGroup[node]<0)continue;
            int row=component.cell(),group=nodeGroup[node];
            receiverGroup[row]=group;var capacity=capacities[row];
            groupPore[group]=groupPore[group].add(capacity);
            var floor=minimum==null||minimum[row]==null?SignedDyadic.ZERO:minimum[row];
            groupRoom[group]=groupRoom[group].add(capacity.subtract(floor));
            groupDry[group]=groupDry[group].add(component.availableVolumeNormalized());
        }
        var available=totalCapacity.subtract(working.exactVolume());
        if(available.signum()<=0)throw new CapacityUnavailable("Nonempty finite gas has no actual remaining pore volume");
        var resolution=SignedDyadic.of(PhaseOccupancyState.VOLUME_RESOLUTION);
        var reserve=new SignedDyadic[groups];var totalReserve=SignedDyadic.ZERO;
        for(int group=0;group<groups;group++){
            var volume=groupDry[group].compareTo(resolution)<0?groupDry[group]:resolution;
            if(volume.compareTo(groupRoom[group])>0)volume=groupRoom[group];
            reserve[group]=volume;totalReserve=totalReserve.add(volume);
        }
        if(totalReserve.compareTo(available)>0){
            var weights=new double[groups];for(int group=0;group<groups;group++)weights[group]=reserve[group].toDouble();
            reserve=LiquidCellInventory.allocateVolumes(available,reserve,weights);
        }
        var upper=new SignedDyadic[groups];
        for(int group=0;group<groups;group++)upper[group]=groupPore[group].subtract(reserve[group]);
        return new GasCapacityGroups(receiverGroup,upper);
    }

	/** Associates final dry capacity with its transported gas sources. */
	private GasVolumeAllocation gasAllocation(LiquidCellInventory future) {
		var frame=GasRuntimeGeometry.captureCellFuture(domain,future,targetOccupied,domain.gasState.sealedDomainBoundary,
				null,phase.occupancyState(),phase,null,preparedCapacity);
		var sources=new PhaseSourceColumns(frame.nodeCount()+1);
		for(var component:preparedCapacity.components){
			if(component.availableVolumeNormalized().signum()==0)continue;
			int row=component.cell();
			int target=frame.topology().nodeOfComponent(component.cell(),component.supportMask());
			if(target<0)throw new IllegalStateException("Positive transported gas has no future support: cell="+component.cell());
			double gas=component.availableVolumeNormalized().toDouble(),total=gasTags.rowSum(row);
			for(int entry=gasTags.first(row);entry>=0;entry=gasTags.next(entry)){
				double part=gas*(gasTags.amount(entry)/total);
				if(part>0)sources.add(target,gasTags.key(entry),part);
			}
		}
		return GasVolumeAllocation.fromTargetSources(domain,future,frame,sources);
	}

	private double microPoreVolume(int piece) {
		int cell=piece/8,micro=piece&7;
		if((domain.geometry.cutCells.mask(cell)&1<<micro)!=0)return 0;
		double open=domain.geometry.cutCells.openCellVolume(cell);
		return .125*phase.componentPoreScale(cell,micro,open>0?phase.poreVolume(cell).toDouble()/open:0);
	}

	LiquidCellInventory.Plan prepareInventory(LiquidCellInventory source) {
		if (source != input) throw new IllegalArgumentException("Liquid transport source differs");
		return prepared;
	}

	GasVolumeAllocation gasAllocation() {
		return allocation;
	}

	LiquidCellInventory sourceInventory() {
		return input;
	}


	void prepareDisplacement(long[] occupied, LiquidCellInventory source) {
		preparePlan(occupied, source);
	}

	static final class CapacityUnavailable extends IllegalStateException {
		private final java.util.BitSet blockedCells;
		CapacityUnavailable(String message) { this(message,null); }
		CapacityUnavailable(String message,java.util.BitSet blockedCells) {
			super(message);this.blockedCells=blockedCells==null?null:(java.util.BitSet)blockedCells.clone();
		}
		boolean affectsCell(int cell) { return blockedCells==null||blockedCells.get(cell); }
	}
}
