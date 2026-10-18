package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One bounded coarse PLIC finite-volume pass with one inventory publication. */
final class ConservativeLiquidFlux {
	final int sizeX, sizeY, sizeZ, cellCount, physicalFaceCount;
	final int[] left, right, axis;
	final double[] flux, predictedFaceFlux;
	private final FluidDomain domain;
	private final int[] coarseFaceAt, pressureFace;
	private final double[] outTotal,sourceVolume;
	private final int[] capacitySlots;
	private int capacityCount;
	private final PhaseQuantitySettlement.RouteBuffer numericalRoutes=new PhaseQuantitySettlement.RouteBuffer();
	private static final SignedDyadic[] OPEN_CAPACITY=openCapacities();
	private static SignedDyadic[] openCapacities(){var values=new SignedDyadic[9];for(int n=0;n<=8;n++)values[n]=SignedDyadic.of(n,-3);return values;}
	private double[] liquid, pore;
	private PhaseSourceColumns gasTags, nextGasTags, macroFlows, macroTotal, gasReservation;
	private FlipPressurePhaseGeometry phase;
	private FlipPressurePhaseGeometry reconstructionWorkspace;
	private double[] phaseLiquid, phasePore;
	private LiquidCellInventory input, working;
	private LiquidCellInventory.Plan prepared;
	private GasVolumeAllocation allocation;
	private GasCapacityGroups gasCapacityGroups;
	private record GasCapacityGroups(int[] receiverGroup,SignedDyadic[] upper,int[] nodeGroup) { }
	private final SignedDyadic[] after;
	private long visits, paths;
	private double correction, maxResidual;
	private long[] targetOccupied;
	record Port(int face, int donorCell, int receiverCell, int donorSupport, int receiverSupport, SignedDyadic volume) {
	}

	/** Builds the shared coarse face indexing once. */
	ConservativeLiquidFlux(FluidDomain domain) {
		this.domain = domain;
		sizeX = domain.sizeX;
		sizeY = domain.sizeY;
		sizeZ = domain.sizeZ;
		cellCount = domain.cellCount;
		physicalFaceCount = (sizeX - 1) * sizeY * sizeZ + sizeX * (sizeY - 1) * sizeZ + sizeX * sizeY * (sizeZ - 1);
		left = new int[physicalFaceCount];
		right = new int[physicalFaceCount];
		axis = new int[physicalFaceCount];
		flux = new double[physicalFaceCount];
		predictedFaceFlux = flux.clone();
		after = new SignedDyadic[cellCount];
		coarseFaceAt = new int[6 * cellCount];
		pressureFace = new int[physicalFaceCount];
		Arrays.fill(coarseFaceAt, - 1);
		int f = 0;
		for (int z = 0; z<sizeZ; z++) for (int y = 0; y<sizeY; y++) for (int x = 1; x<sizeX; x++) f = coarseFace(f, (z * sizeY + y) * sizeX + x - 1, 0);
		for (int z = 0; z<sizeZ; z++) for (int y = 1; y<sizeY; y++) for (int x = 0; x<sizeX; x++) f = coarseFace(f, (z * sizeY + y - 1) * sizeX + x, 1);
		for (int z = 1; z<sizeZ; z++) for (int y = 0; y<sizeY; y++) for (int x = 0; x<sizeX; x++) f = coarseFace(f, ((z - 1) * sizeY + y) * sizeX + x, 2);
		int count = 8 * cellCount;
		outTotal = new double[count];sourceVolume=new double[count];capacitySlots=new int[count];
		gasTags = new PhaseSourceColumns(count);
		nextGasTags = new PhaseSourceColumns(count);
		macroFlows = new PhaseSourceColumns(2 * physicalFaceCount);
		macroTotal = new PhaseSourceColumns(2 * physicalFaceCount);
		gasReservation = new PhaseSourceColumns(count);
		phase = new FlipPressurePhaseGeometry(sizeX, sizeY, sizeZ);
	}

	private int coarseFace(int f, int a, int dimension) {
		int b = a + (dimension == 0 ? 1 : dimension == 1 ? sizeX : sizeX * sizeY);
		left[f] = a;
		right[f] = b;
		axis[f] = dimension;
		coarseFaceAt[6 * a + 2 * dimension + 1] = f;
		coarseFaceAt[6 * b + 2 * dimension] = f;
		int x = b % sizeX, y = b / sizeX % sizeY, z = b / (sizeX * sizeY);
		pressureFace[f] = domain.pressureSystem.faces.offsets[dimension] + switch (dimension) {
			case 0 -> domain.xFaceIndex(x, y, z);
			case 1 -> domain.yFaceIndex(x, y, z);
			default -> domain.zFaceIndex(x, y, z);
		};
		return f + 1;
	}

	private static int faceMicro(int axis, int port) {
		return axis == 0 ? 1 | (port << 1)
				: axis == 1 ? 2 | (port & 1) | ((port & 2) << 1) : 4 | port;
	}

	/** Actual coarse donor outflow relative to its available pore volume. */
	double courantRate(double verticalPrediction) {
		long[] occupied = currentOccupied();
		var capacities = componentCapacities(occupied);
		Arrays.fill(outTotal, 0);
		boolean[] active = new boolean[8 * cellCount];
		for (var stock : domain.liquidCells.physicalStocks()) active[8 * stock.key().cell() + Integer.numberOfTrailingZeros(stock.key().supportMask())] = true;
		for (int f = 0; f<physicalFaceCount; f++) {
			int b = right[f], x = b % sizeX, y = b / sizeX % sizeY, z = b / (sizeX * sizeY), a = axis[f];
			int local = a == 0 ? domain.xFaceIndex(x, y, z) : a == 1 ? domain.yFaceIndex(x, y, z) : domain.zFaceIndex(x, y, z);
			double speed = (a == 0 ? domain.mac.velocityX[local] : a == 1 ? domain.mac.velocityY[local] : domain.mac.velocityZ[local]) + (a == 1 ? verticalPrediction : 0);
			int donor = speed >= 0 ? left[f] : right[f];
			for (int micro = 0; micro<8; micro++) if ((micro >>> a & 1) != 0) {
				int first = support(8 * left[f] + micro), second = support(8 * right[f] + (micro ^ (1 << a)));
				if (first == 0 || second == 0) continue;
				int source = 8 * donor + Integer.numberOfTrailingZeros(speed >= 0 ? first : second);
				if (active[source]) outTotal[source] += .25 * Math.abs(speed) / domain.config.cellSize();
			}
		}
		double max = 0;
		for (int p = 0; p<capacities.length; p++) if (active[p] && capacities[p].signum()> 0) max = Math.max(max, outTotal[p] / capacities[p].toDouble());
		return max;
	}

	private long[] currentOccupied() {
		return domain.granularCoupling == null ? new long[cellCount] : domain.granularCoupling.copySolidSvq();
	}

	void prepare(float dt, long[] occupied) {
		prepare(dt, occupied, domain.liquidCells, null, null);
	}

	/** Prepares one coarse finite-volume pass against the actual pore endpoint. */
	private void prepare(float dt, long[] occupied, LiquidCellInventory source, PhaseOccupancyState seed, List<GasVolumeAllocation.Share> seedGas) {
		domain.ensurePhaseOccupancy();
		reconstructionWorkspace = new FlipPressurePhaseGeometry(sizeX, sizeY, sizeZ);
		input = working = source;
		Arrays.fill(sourceVolume,0);for(var stock:source.stocks())sourceVolume[8*stock.key().cell()+Integer.numberOfTrailingZeros(stock.key().supportMask())]+=stock.volumeFraction();
		prepared = null;
		allocation = null;
		targetOccupied = occupied.clone();
		liquid = (seed == null ? domain.phaseOccupancy : seed).copyLiquidMicroVolumes();
		long[] oldOccupied = currentOccupied();
		pore = PhaseOccupancyTransport.poreVolumes(domain, oldOccupied, null);
		double[] endpoint = PhaseOccupancyTransport.poreVolumes(domain, occupied, null);
		var sourceFrame = domain.gasState.frame;
		if (seed == null && source == domain.liquidCells && sourceFrame != null && sourceFrame.matchesCarrierGeometry(domain, oldOccupied)) {
			phase = sourceFrame.phase();
			phaseLiquid = liquid;
			phasePore = pore;
		} else {
			phaseLiquid = null;
			phasePore = null;
		}
		gasTags.reset(liquid.length);
		if (seedGas != null && !seedGas.isEmpty()) {
			for (var share : seedGas) gasTags.add(slot(share.targetPiece()), share.sourceNode(), share.volume());
		} else for (int p = 0; p<liquid.length; p++) if (pore[p]> liquid[p]) {
			int node = domain.gasState.frame == null ? (domain.gasState.sealedDomainBoundary ? - 1 : 0) : domain.gasState.frame.nodeOfPiece(p);
			gasTags.add(slot(p), node, pore[p] - liquid[p]);
		}
		visits = paths = 0;
		correction = maxResidual = 0;
		Arrays.fill(predictedFaceFlux, 0);
		SignedDyadic[] finalCapacity = componentCapacities(occupied);
		if (dt > 0) domain.pressureSystem.beginVolumeTransport();
		advance(dt, endpoint, finalCapacity, dt == 0);
		if (dt == 0) allocation = PhaseOccupancyTransport.prepareEdit(domain, working, occupied, null, null, new PhaseOccupancyState(liquid));
		else allocation = gasAllocation(working, finalCapacity);
		Arrays.fill(flux,0);for(var transfer:prepared.transfers())flux[transfer.face()]+=(transfer.source().cell()==left[transfer.face()]?1:-1)*transfer.volume().toDouble();

	}

	private SignedDyadic[] componentCapacities(long[] occupied) {
        var result=new SignedDyadic[8*cellCount];Arrays.fill(result,SignedDyadic.ZERO);capacityCount=0;
        for(int cell=0;cell<cellCount;cell++){
            int mask=domain.geometry.cutCells.mask(cell);int[] supports=CellRemapMicroTopology.components(mask);
            if(occupied[cell]==0){for(int support:supports){int slot=8*cell+Integer.numberOfTrailingZeros(support);result[slot]=OPEN_CAPACITY[Integer.bitCount(support)];capacitySlots[capacityCount++]=slot;}}
            else{
                long remaining=((long)Integer.bitCount(~mask&255)<<21)-occupied[cell];
                if(occupied[cell]<0||remaining<0)throw new IllegalStateException("Solid occupation exceeds actual open pore");
                if(supports.length==1){int slot=8*cell+Integer.numberOfTrailingZeros(supports[0]);result[slot]=SignedDyadic.of(remaining,-24);capacitySlots[capacityCount++]=slot;}
                else{var parts=CellRemapMicroTopology.capacities(mask,SignedDyadic.of(remaining,-24));for(int j=0;j<supports.length;j++){int slot=8*cell+Integer.numberOfTrailingZeros(supports[j]);result[slot]=parts[j];capacitySlots[capacityCount++]=slot;}}
            }
        }
        return result;
    }

	/** Advances the complete duration and retains its complete inventory plan. */
	private void advance(double dt, double[] targetPore, SignedDyadic[] capacities, boolean displacement) {
		int count = liquid.length;
		preparePhase();
		macroFlows.reset(2 * physicalFaceCount);
		macroTotal.reset(2 * physicalFaceCount);
		// Each shared coarse flux needs at most four donor PLIC slab integrals.
		for (int f = 0; f < physicalFaceCount; f++) {
			double qt=displacement?0:domain.pressureSystem.volumeFlux(pressureFace[f],dt);
            if(!displacement&&qt==0)continue;
			int a = axis[f], maskLeft = domain.geometry.cutCells.mask(left[f]);
			int maskRight = domain.geometry.cutCells.mask(right[f]), openPorts = 0;
			for (int port = 0; port < 4; port++) {
				int micro = faceMicro(a, port), peer = micro ^ (1 << a);
				if ((maskLeft & (1 << micro)) == 0 && (maskRight & (1 << peer)) == 0) openPorts |= 1 << port;
			}
			if (openPorts == 0) continue;
			double portion = Math.abs(qt) / Integer.bitCount(openPorts);
			for (int bits = openPorts; bits != 0; bits &= bits - 1) {
				int micro = faceMicro(a, Integer.numberOfTrailingZeros(bits));
				int first = 8 * left[f] + micro, second = 8 * right[f] + (micro ^ (1 << a));
				if (displacement) {
					if (pore[first] > 0 && targetPore[second] > 0) addMacro(f, first, second, 0, 1);
					if (pore[second] > 0 && targetPore[first] > 0) addMacro(f, second, first, 0, 1);
					continue;
				}
				boolean positive = qt > 0;
				int from = positive ? first : second, target = positive ? second : first;
				double width = pore[from] > 0 ? Math.clamp(portion / pore[from], 0, 1) : 0;
				double low = positive ? 1 - width : 0, high = positive ? 1 : width;
				double ql = liquid[from] <= 0 ? 0 : liquid[from] >= pore[from] ? portion
						: intersection(phase, from, a == 0 ? low : 0, a == 1 ? low : 0, a == 2 ? low : 0,
								a == 0 ? high : 1, a == 1 ? high : 1, a == 2 ? high : 1, pore[from]);
				ql = Math.clamp(ql, 0, portion);
				predictedFaceFlux[f] += positive ? ql : -ql;
				addMacro(f, from, target, ql, portion);
			}
		}
		var next = settle(capacities, displacement);
		prepared = next;
		working = next.future();
		if (!displacement) {
			macroFlows.reset(2 * physicalFaceCount);
			for (var t : next.transfers()) {
				int face = t.face(), row = 2 * face + (t.source().cell() == left[face] ? 0 : 1);
				macroFlows.add(row, (long) t.source().supportMask() << 8 | t.target().supportMask(), t.volume().toDouble());
			}
		}
		nextGasTags.reset(count);
		if (!displacement) for (int row = 0; row<macroTotal.rowCount(); row++) for (int e = macroTotal.first(row); e >= 0; e = macroTotal.next(e)) {
			int face = row / 2, from = row % 2 == 0 ? left[face] : right[face], target = row % 2 == 0 ? right[face] : left[face];
			long key = macroTotal.key(e);
			double ql = 0;
			for (int q = macroFlows.first(row); q >= 0; q = macroFlows.next(q)) if (macroFlows.key(q) == key) {
				ql = macroFlows.amount(q);
				break;
			}
			int sourceSlot = 8 * from + Integer.numberOfTrailingZeros((int)(key >>> 8)), targetSlot = 8 * target + Integer.numberOfTrailingZeros((int) key & 255);
			copyGas(sourceSlot, targetSlot, Math.max(0, macroTotal.amount(e) - ql));
		}
		for (int row = 0; row<gasTags.rowCount(); row++) if (gasTags.first(row) >= 0) copyGas(row, row, gasTags.rowSum(row));
		pore = targetPore;
		liquid = PhaseOccupancyTransport.reconstruct(domain, working, pore, null, reconstructionWorkspace);
		phase = reconstructionWorkspace;
		phaseLiquid = liquid;
		phasePore = pore;
		var swap = gasTags;
		gasTags = nextGasTags;
		nextGasTags = swap;
		for (int cell = 0; cell<cellCount; cell++) for (int support : CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell))) {
			int row = 8 * cell + Integer.numberOfTrailingZeros(support);
			double dry = 0;
			for (int bits = support; bits != 0; bits &= bits - 1) {
				int p = 8 * cell + Integer.numberOfTrailingZeros(bits);
				dry += Math.max(0, pore[p] - liquid[p]);
			}
			if (dry <= 0) {
				gasTags.clear(row);
				continue;
			}
			if (gasTags.first(row)<0) gasTags.add(row, - 1, dry);
			else gasTags.scale(row, dry);
		}
		inheritGasReservoirs();
	}

	private void addMacro(int f, int from, int target, double preferred, double total) {
		int face = f, sourceSupport = support(from), targetSupport = support(target);
		if (sourceSupport == 0 || targetSupport == 0) return;
		int row = 2 * face + (from / 8 == left[face] ? 0 : 1);
		long key = (long) sourceSupport << 8 | targetSupport;
		macroFlows.add(row, key, preferred);
		macroTotal.add(row, key, total);
	}

	private int support(int piece) {
		return CellRemapMicroTopology.support(domain.geometry.cutCells.mask(piece / 8), piece & 7);
	}

	private int slot(int piece) {
		return 8 * (piece / 8) + Integer.numberOfTrailingZeros(support(piece));
	}

	private void copyGas(int from, int target, double amount) {
		if (amount <= 0) return;
		double gas = gasTags.rowSum(from);
		if (gas <= 0) return;
		for (int e = gasTags.first(from); e >= 0; e = gasTags.next(e)) nextGasTags.add(target, gasTags.key(e), amount * (gasTags.amount(e) / gas));
	}

	/** Allocates the complete coarse inventory shares. */
	private LiquidCellInventory.Plan settle(SignedDyadic[] capacities, boolean displacement) {
		var origins = working.stocks();
		numericalRoutes.reset(origins,capacitySlots,capacityCount);
		boolean[] gasSource = new boolean[capacities.length], gasReach = new boolean[capacities.length];
		for (int row = 0; row<gasTags.rowCount(); row++) if (gasTags.first(row) >= 0) gasSource[row] = gasReach[row] = true;
		if (!displacement) for (int row = 0; row<macroTotal.rowCount(); row++) for (int e = macroTotal.first(row); e >= 0; e = macroTotal.next(e)) {
			int face = row / 2, from = row % 2 == 0 ? left[face] : right[face], target = row % 2 == 0 ? right[face] : left[face], sourceSupport = (int)(macroTotal.key(e) >>> 8), targetSupport = (int) macroTotal.key(e) & 255;
			if (!gasSource[8 * from + Integer.numberOfTrailingZeros(sourceSupport)]) continue;
			double ql = 0;
			for (int q = macroFlows.first(row); q >= 0; q = macroFlows.next(q)) if (macroFlows.key(q) == macroTotal.key(e)) {
				ql = macroFlows.amount(q);
				break;
			}
			double qg = macroTotal.amount(e) - ql;
			if (qg> PhaseOccupancyState.VOLUME_RESOLUTION) gasReach[8 * target + Integer.numberOfTrailingZeros(targetSupport)] = true;
		}
		for (int source = 0; source<origins.size(); source++) {
			var stock = origins.get(source);
			int cell = stock.key().cell(), support = stock.key().supportMask();
			double volume=stock.volumeFraction(),component=sourceVolume[8*cell+Integer.numberOfTrailingZeros(support)];
			double outgoing = 0;
			for (int side = 0; side<6; side++) {
				int face = coarseFaceAt[6 * cell + side];
				if (face<0) continue;
				int direction = cell == left[face] ? 0 : 1, row = 2 * face + direction, target = direction == 0 ? right[face] : left[face];
				for (int e = macroTotal.first(row); e >= 0; e = macroTotal.next(e)) if ((int)(macroTotal.key(e) >>> 8) == support) {
					int targetSupport = (int) macroTotal.key(e) & 255;
					double amount = 0;
					for (int q = macroFlows.first(row); q >= 0; q = macroFlows.next(q)) if (macroFlows.key(q) == macroTotal.key(e)) amount = macroFlows.amount(q);
					double fraction = volume / component;
					amount *= fraction;
					outgoing += amount;
					numericalRoutes.add(source,face,target,targetSupport,amount,displacement?volume:macroTotal.amount(e)*fraction);
				}
			}
			numericalRoutes.add(source,-1,cell,support,Math.max(0,volume-outgoing),volume);
		}
		var minimum = new SignedDyadic[capacities.length];
		Arrays.fill(minimum, SignedDyadic.ZERO);
		if(!displacement)for(int i=0;i<capacityCount;i++){int slot=capacitySlots[i];if(!gasReach[slot])minimum[slot]=capacities[slot];}
		gasCapacityGroups=displacement?null:prepareGasGroups(capacities,gasReach);
		var routes=PhaseQuantitySettlement.settle(numericalRoutes,capacities,minimum,
                gasCapacityGroups==null?null:gasCapacityGroups.receiverGroup(),gasCapacityGroups==null?null:gasCapacityGroups.upper());
		return working.planRoutes(routes, FluidThermalBinding.liquidEnergyPartitioner(domain));
	}

	/** A finite chamber constrains aggregate space while liquid may rearrange freely inside it. */
    private GasCapacityGroups prepareGasGroups(SignedDyadic[] capacities,boolean[] gasReach){
        var frame=domain.gasState.frame;if(frame==null||frame.nodeCount()==0)return null;
        int count=frame.nodeCount();gasReservation.reset(capacities.length);
        for(int row=0;row<gasTags.rowCount();row++)for(int e=gasTags.first(row);e>=0;e=gasTags.next(e)){
            int node=(int)gasTags.key(e);if(node>0&&!domain.gasState.network.inventory(node).isEmpty())gasReservation.add(row,node,gasTags.amount(e));
        }
        for(int row=0;row<macroTotal.rowCount();row++)for(int e=macroTotal.first(row);e>=0;e=macroTotal.next(e)){
            int face=row/2,from=row%2==0?left[face]:right[face],to=row%2==0?right[face]:left[face];long key=macroTotal.key(e);
            int source=8*from+Integer.numberOfTrailingZeros((int)(key>>>8)),receiver=8*to+Integer.numberOfTrailingZeros((int)key&255);if(!gasReach[receiver])continue;
            double ql=0;for(int q=macroFlows.first(row);q>=0;q=macroFlows.next(q))if(macroFlows.key(q)==key){ql=macroFlows.amount(q);break;}
            double qg=macroTotal.amount(e)-ql,total=gasTags.rowSum(source);if(qg<=PhaseOccupancyState.VOLUME_RESOLUTION||total<=0)continue;
            for(int q=gasTags.first(source);q>=0;q=gasTags.next(q)){int node=(int)gasTags.key(q);if(node>0&&!domain.gasState.network.inventory(node).isEmpty())gasReservation.add(receiver,node,qg*gasTags.amount(q)/total);}
        }
        int[] parent=new int[count+1],first=new int[capacities.length],nodeGroup=new int[count+1];
        for(int node=0;node<=count;node++)parent[node]=node;
        Arrays.fill(first,-1);Arrays.fill(nodeGroup,-1);
        for(int row=0;row<gasReservation.rowCount();row++)for(int e=gasReservation.first(row);e>=0;e=gasReservation.next(e)){
            int node=(int)gasReservation.key(e);if(first[row]<0)first[row]=node;else{int a=gasRoot(parent,first[row]),b=gasRoot(parent,node);parent[b]=a;}
        }
        int groups=0;for(int row=0;row<first.length;row++)if(first[row]>0){int root=gasRoot(parent,first[row]);if(nodeGroup[root]<0)nodeGroup[root]=groups++;}
        if(groups==0)return null;
        for(int node=1;node<=count;node++)nodeGroup[node]=nodeGroup[gasRoot(parent,node)];
        int[] receiverGroup=new int[capacities.length];Arrays.fill(receiverGroup,-1);
        double[] groupPore=new double[groups],target=new double[groups];double available=0,totalTarget=0;
        for(int row=0;row<capacities.length;row++){
            available+=capacities[row].toDouble();if(first[row]>0){int group=nodeGroup[first[row]];receiverGroup[row]=group;groupPore[group]+=capacities[row].toDouble();}
        }
        for(var stock:working.stocks())available-=stock.volumeFraction();
        for(int node=1;node<=count;node++)if(nodeGroup[node]>=0){double volume=domain.pressureSystem.predictedGasVolumeNormalized(node);target[nodeGroup[node]]+=volume;totalTarget+=volume;}
        double scale=totalTarget>available?Math.max(0,available)/totalTarget:1;var upper=new SignedDyadic[groups];
        for(int group=0;group<groups;group++){
            double volume=Math.min(groupPore[group],scale*target[group]);long words=(long)Math.floor(Math.scalb(volume,-PhaseQuantitySettlement.UNIT_EXPONENT));
            var reserved=words>0?SignedDyadic.of(words,PhaseQuantitySettlement.UNIT_EXPONENT):SignedDyadic.of(volume);
            upper[group]=SignedDyadic.of(groupPore[group]).subtract(reserved);
        }
        return new GasCapacityGroups(receiverGroup,upper,nodeGroup);
    }
    private static int gasRoot(int[] parent,int node){while(parent[node]!=node){parent[node]=parent[parent[node]];node=parent[node];}return node;}

    /** The same aggregate reservoir carries its finite inventories into all surviving coarse dry space. */
    private void inheritGasReservoirs(){
        if(gasCapacityGroups==null)return;
        int[] groups=gasCapacityGroups.receiverGroup(),nodes=gasCapacityGroups.nodeGroup();
        int[] head=new int[gasCapacityGroups.upper().length],next=new int[nodes.length];Arrays.fill(head,-1);
        double[] total=new double[head.length];
        for(int node=1;node<nodes.length;node++)if(nodes[node]>=0){int group=nodes[node];next[node]=head[group];head[group]=node;total[group]+=domain.gasState.network.availableVolume(node);}
        gasReservation.reset(gasTags.rowCount());
        for(int row=0;row<gasTags.rowCount();row++){
            double dry=gasTags.rowSum(row);if(dry<=0)continue;int group=groups[row];
            if(group<0){for(int e=gasTags.first(row);e>=0;e=gasTags.next(e))gasReservation.add(row,gasTags.key(e),gasTags.amount(e));continue;}
            for(int e=gasTags.first(row);e>=0;e=gasTags.next(e))if(gasTags.key(e)<=0)gasReservation.add(row,gasTags.key(e),gasTags.amount(e));
            for(int node=head[group];node>=0;node=next[node])gasReservation.add(row,node,dry*domain.gasState.network.availableVolume(node)/total[group]);
            gasReservation.scale(row,dry);
        }
        var swap=gasTags;gasTags=gasReservation;gasReservation=swap;
    }

	/** Associates final dry capacity with its transported gas sources. */
	private GasVolumeAllocation gasAllocation(LiquidCellInventory future, SignedDyadic[] capacities) {
		var shares = new ArrayList<GasVolumeAllocation.Share>();
		for (int cell = 0; cell<cellCount; cell++) for (int support : CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell))) {
			int row = 8 * cell + Integer.numberOfTrailingZeros(support), piece = - 1;
			double largest = 0;
			for (int bits = support; bits != 0; bits &= bits - 1) {
				int p = 8 * cell + Integer.numberOfTrailingZeros(bits);
				double dry = pore[p] - liquid[p];
				if (dry> largest) {
					largest = dry;
					piece = p;
				}
			}
			if (piece<0) continue;
			var amount = SignedDyadic.ZERO;
			for (var stock : future.stocksInCell(cell)) if (stock.key().supportMask() == support) amount = amount.add(stock.volume());
			double gas = capacities[row].subtract(amount).toDouble(), total = gasTags.rowSum(row);
			for (int e = gasTags.first(row); e >= 0; e = gasTags.next(e)) {
				int node = (int) gasTags.key(e);
				double part = gas * (gasTags.amount(e) / total);
				if (part> 0) shares.add(new GasVolumeAllocation.Share(node, piece, part, node> 0 ? GasVolumeAllocation.SourceKind.EXISTING : node == 0 ? GasVolumeAllocation.SourceKind.AMBIENT : GasVolumeAllocation.SourceKind.EXPLICIT_VACUUM));
			}
		}
		preparePhase();
		phase.setExactPoreVolumes(domain.geometry.cutCells, targetOccupied);
		return new GasVolumeAllocation(domain, future, new PhaseOccupancyState(liquid), shares, phase, null);
	}

	private void preparePhase() {
		if (phaseLiquid == liquid && phasePore == pore) return;
		phase = new FlipPressurePhaseGeometry(sizeX, sizeY, sizeZ);
		phase.rebuildOccupancy(new PhaseOccupancyState(liquid), domain.geometry.cutCells, pore);
		phaseLiquid = liquid;
		phasePore = pore;
	}

	private static double intersection(FlipPressurePhaseGeometry phase, int p, double lx, double ly, double lz, double hx, double hy, double hz, double pore) {
		int cell = p / 8, m = p & 7;
		double nx = .5 * phase.planeNormal(cell, m, 0), ny = .5 * phase.planeNormal(cell, m, 1), nz = .5 * phase.planeNormal(cell, m, 2), d = phase.planeOffset(cell, m) - nx * (m & 1) - ny * (m >>> 1 & 1) - nz * (m >>> 2 & 1), dx = hx - lx, dy = hy - ly, dz = hz - lz;
		return pore * dx * dy * dz * FlipFreeSurfaceGeometry.unitCubeLiquidFraction(nx * dx, ny * dy, nz * dz, d - nx * lx - ny * ly - nz * lz);
	}

	LiquidCellInventory.Plan prepareInventory(LiquidCellInventory source) {
		if (source != input) throw new IllegalArgumentException("Liquid transport source differs");
		return prepared;
	}

	GasVolumeAllocation gasAllocation(LiquidCellInventory future) {
		return allocation;
	}

	LiquidCellInventory sourceInventory() {
		return input;
	}

	List<LiquidCellInventory.FaceFlux> faceFluxes(LiquidCellInventory.Plan plan) {
		var list = new ArrayList<LiquidCellInventory.FaceFlux>();
		for (var t : plan.transfers()) list.add(new LiquidCellInventory.FaceFlux(t.face(), t.source().cell(), t.target().cell(), t.source().supportMask(), t.target().supportMask(), t.source().material(), t.volume()));
		return List.copyOf(list);
	}

	void prepareDisplacement(long[] occupied, LiquidCellInventory source) {
		if (source == domain.liquidCells) prepare(0, occupied);
		else {
			var seed = PhaseOccupancyTransport.prepareEdit(domain, source, currentOccupied(), null, null);
			prepare(0, occupied, source, seed.futureOccupancy(), seed.shares());
		}
	}

	List<Port> ports() {
		var list = new ArrayList<Port>();
		for (var t : prepared.transfers()) list.add(new Port(t.face(), t.source().cell(), t.target().cell(), t.source().supportMask(), t.target().supportMask(), t.volume()));
		return list;
	}

	SignedDyadic[] resultingVolumes() {
        for(int cell=0;cell<cellCount;cell++)after[cell]=prepared.future().exactCellVolume(cell);
		return after;
	}

	double capacityCorrectionVolume() {
		return correction;
	}

	double maximumCapacityResidual() {
		return maxResidual;
	}

	int capacityCorrectionPaths() {
		return(int) Math.min(Integer.MAX_VALUE, paths);
	}

	long allocationWork() {
		return visits;
	}

	long allocationArcVisits() {
		return visits;
	}

	int componentCount() {
		int count = 0;
		for (int cell = 0; cell < cellCount; cell++) count += CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell)).length;
		return count;
	}

	int allocationArcCount() {
		return 0;
	}

	int liquidSourceEntryCount() {
		return 0;
	}

	int gasSourceEntryCount() {
		return gasTags.entryCount();
	}

	int materialRouteCount() {
		return prepared.transfers().size();
	}

	double gasPredictionAdjustmentVolume() {
		return 0;
	}

	double maximumRelativeGasAdjustment() {
		return 0;
	}

	double faceFluxCorrectionVolume() {
		double sum = 0;
		for (int f = 0; f<flux.length; f++) sum += Math.abs(flux[f] - predictedFaceFlux[f]);
		return sum;
	}

	double maximumFaceFluxCorrection() {
		double max = 0;
		for (int f = 0; f<flux.length; f++) max = Math.max(max, Math.abs(flux[f] - predictedFaceFlux[f]));
		return max;
	}

	boolean positive(int f) {
		return flux[f] >= 0;
	}

	int donor(int f) {
		return positive(f) ? left[f] : right[f];
	}

	int receiver(int f) {
		return positive(f) ? right[f] : left[f];
	}

	static final class CapacityUnavailable extends IllegalStateException {
		CapacityUnavailable(String message) {
			super(message);
		}
	}
}
