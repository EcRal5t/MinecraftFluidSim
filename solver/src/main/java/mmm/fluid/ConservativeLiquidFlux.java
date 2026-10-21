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
	final double[] flux;
	private final FluidDomain domain;
	private final int[] coarseFaceAt, pressureFace;
	private final int[] capacitySlots;
	private int capacityCount;
	private final PhaseQuantitySettlement.RouteBuffer numericalRoutes=new PhaseQuantitySettlement.RouteBuffer();
	private static final SignedDyadic[] OPEN_CAPACITY=openCapacities();
	private static SignedDyadic[] openCapacities(){var values=new SignedDyadic[9];for(int n=0;n<=8;n++)values[n]=SignedDyadic.of(n,-3);return values;}
	private double[] liquid, pore;
	private double[] finalDry;
	private GasComponentCapacity.Prepared preparedCapacity;
	private PhaseSourceColumns gasTags, nextGasTags, macroFlows, macroTotal;
	private FlipPressurePhaseGeometry phase;
	private FlipPressurePhaseGeometry reconstructionWorkspace;
	private double[] phaseLiquid, phasePore;
	private LiquidCellInventory input, working;
	private LiquidCellInventory.Plan prepared;
	private LiquidCellInventory.TransportFinalizer inventoryFinalizer;
	CellMomentumTransport.Prepared preparedMomentum;
	private GasVolumeAllocation allocation;
	private record GasCapacityGroups(int[] receiverGroup,SignedDyadic[] upper) { }
	private long[] targetOccupied;

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
		coarseFaceAt = new int[6 * cellCount];
		pressureFace = new int[physicalFaceCount];
		Arrays.fill(coarseFaceAt, - 1);
		int f = 0;
		for (int z = 0; z<sizeZ; z++) for (int y = 0; y<sizeY; y++) for (int x = 1; x<sizeX; x++) f = coarseFace(f, (z * sizeY + y) * sizeX + x - 1, 0);
		for (int z = 0; z<sizeZ; z++) for (int y = 1; y<sizeY; y++) for (int x = 0; x<sizeX; x++) f = coarseFace(f, (z * sizeY + y - 1) * sizeX + x, 1);
		for (int z = 1; z<sizeZ; z++) for (int y = 0; y<sizeY; y++) for (int x = 0; x<sizeX; x++) f = coarseFace(f, ((z - 1) * sizeY + y) * sizeX + x, 2);
		int count = 8 * cellCount;
		capacitySlots=new int[count];
		gasTags = new PhaseSourceColumns(count);
		nextGasTags = new PhaseSourceColumns(count);
		macroFlows = new PhaseSourceColumns(2 * physicalFaceCount);
		macroTotal = new PhaseSourceColumns(2 * physicalFaceCount);
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
			case 0 -> domain.domainFlowFields.xFaceIndex(x, y, z);
			case 1 -> domain.domainFlowFields.yFaceIndex(x, y, z);
			default -> domain.domainFlowFields.zFaceIndex(x, y, z);
		};
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
		return domain.granularCoupling == null ? new long[cellCount] : domain.granularCoupling.copySolidSvq();
	}

	void prepare(float dt, long[] occupied) {
		prepare(dt,occupied,null);
	}
	void prepare(float dt,long[] occupied,LiquidCellInventory.TransportFinalizer finalizer) {
		preparePlan(dt,occupied,domain.liquidCells,finalizer);
	}

	/** Prepares one coarse finite-volume pass against the actual pore endpoint. */
	private void preparePlan(float dt, long[] occupied, LiquidCellInventory source,LiquidCellInventory.TransportFinalizer finalizer) {
		inventoryFinalizer=finalizer;preparedMomentum=null;
		domain.ensurePhaseOccupancy();
		reconstructionWorkspace = new FlipPressurePhaseGeometry(sizeX, sizeY, sizeZ);
		input = working = source;
		prepared = null;
		allocation = null;
		targetOccupied = occupied.clone();
		liquid = domain.phaseOccupancy.copyLiquidMicroVolumes();
		var sourceFrame = domain.gasState.frame;
		// Bed transitions may already have published their private solid columns.
		// Gas and phase sources still belong to the retained geometry generation.
		long[] oldOccupied = sourceFrame == null ? currentOccupied() : sourceFrame.occupiedSvq();
		pore = PhaseOccupancyTransport.poreVolumes(domain, oldOccupied, null);
		double[] endpoint = Arrays.equals(oldOccupied,occupied)?pore:PhaseOccupancyTransport.poreVolumes(domain, occupied, null);
		boolean sourceMatches=sourceFrame!=null&&sourceFrame.matchesCarrierGeometry(domain,oldOccupied);
		if (source == domain.liquidCells && sourceMatches) {
			phase = sourceFrame.phase();
			phaseLiquid = liquid;
			phasePore = pore;
		} else {
			phaseLiquid = null;
			phasePore = null;
		}
		gasTags.reset(liquid.length);
		if(sourceMatches){
			for(var component:sourceFrame.capacity().components())if(component.availableVolumeNormalized().signum()>0){
				int cell=component.cell(),support=component.supportMask();
				gasTags.add(8*cell+Integer.numberOfTrailingZeros(support),sourceFrame.topology().nodeOfComponent(cell,support),component.availableVolumeNormalized().toDouble());
			}
		}else for (int cell = 0; cell < cellCount; cell++) {
			for (int support : CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell))) {
				double capacity = 0, stock = 0;
				int node = domain.gasState.sealedDomainBoundary ? -1 : 0;
				boolean found = false;
				for (int bits = support; bits != 0; bits &= bits - 1) {
					int p = 8 * cell + Integer.numberOfTrailingZeros(bits);
					capacity += pore[p];
					int owner = sourceFrame == null ? -1 : sourceFrame.nodeOfPiece(p);
					if (!found && owner >= 0) { node = owner; found = true; }
				}
				for (var row : domain.liquidCells.stocksInCell(cell))
					if (row.key().supportMask() == support) stock += row.volumeFraction();
				double dry = capacity - stock;
				if (dry > 0) gasTags.add(8 * cell + Integer.numberOfTrailingZeros(support), node, dry);
			}
		}
		SignedDyadic[] finalCapacity = componentCapacities(occupied);
		if (dt > 0) domain.pressureSystem.beginVolumeTransport();
		advance(dt, endpoint, finalCapacity, dt == 0);
		allocation = gasAllocation(working);
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
		preparePhase();
		prepareFacePreferences(dt,displacement);
		prepared=settle(capacities);
		working=prepared.future();
		pore=targetPore;
		liquid=PhaseOccupancyTransport.reconstruct(domain,working,pore,null,reconstructionWorkspace);
		phase=reconstructionWorkspace;phaseLiquid=liquid;phasePore=pore;
		phase.setExactPoreVolumes(domain.geometry.cutCells,targetOccupied);
		var sourceFrame=domain.gasState.frame;
		preparedCapacity=GasComponentCapacity.prepare(domain.geometry.cutCells,phase,working.snapshot(),
				sourceFrame==null?null:sourceFrame.geometry().actualCapacity());
		finalDry=preparedCapacity.dry;
		advanceGasSources(prepared.transfers(),displacement);
		var swap=gasTags;gasTags=nextGasTags;nextGasTags=swap;
		normalizeGasSources();
	}
	/** Integrates the actual donor slabs on shared open faces. */
	private void prepareFacePreferences(double dt,boolean displacement) {
		macroFlows.reset(2 * physicalFaceCount);
		macroTotal.reset(2 * physicalFaceCount);
		// Each shared coarse flux needs at most four donor PLIC slab integrals.
		for (int f = 0; f < physicalFaceCount; f++) {
			double qt=displacement?0:domain.pressureSystem.volumeFlux(pressureFace[f],dt);
			if (qt == 0) continue;
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
				boolean positive = qt > 0;
				int from = positive ? first : second, target = positive ? second : first;
				double width = pore[from] > 0 ? Math.clamp(portion / pore[from], 0, 1) : 0;
				double low = positive ? 1 - width : 0, high = positive ? 1 : width;
				double wet = phase.liquidFractionExact(from / 8, from & 7);
				double ql = wet <= 0 ? 0 : wet >= 1 ? portion
						: intersection(phase, from, a == 0 ? low : 0, a == 1 ? low : 0, a == 2 ? low : 0,
								a == 0 ? high : 1, a == 1 ? high : 1, a == 2 ? high : 1, pore[from]);
				ql = Math.clamp(ql, 0, portion);
				addMacro(f, from, target, ql, portion);
			}
		}
	}
	/** Advects gas only on full fixed openings; restricted openings belong to the orifice solver. */
	private void advanceGasSources(List<LiquidCellInventory.Transfer> transfers,boolean displacement) {
		macroFlows.reset(2*physicalFaceCount);
		for(var transfer:transfers){
			int face=transfer.face(),row=2*face+(transfer.source().cell()==left[face]?0:1);
			long key=(long)transfer.source().supportMask()<<8|transfer.target().supportMask();
			macroFlows.add(row,key,transfer.volume().toDouble());
		}
		nextGasTags.reset(liquid.length);
		double[] outgoing=new double[liquid.length],accepted=new double[liquid.length];
		if(!displacement)for(int row=0;row<macroTotal.rowCount();row++){
			int face=row/2;if(!advectsGas(face))continue;
			int from=row%2==0?left[face]:right[face];
			for(int e=macroTotal.first(row);e>=0;e=macroTotal.next(e)){
				long key=macroTotal.key(e);
				int source=8*from+Integer.numberOfTrailingZeros((int)(key>>>8));
				outgoing[source]+=gasFlux(row,e);
			}
		}
		if(!displacement)for(int row=0;row<macroTotal.rowCount();row++){
			int face=row/2;if(!advectsGas(face))continue;
			int from=row%2==0?left[face]:right[face],to=row%2==0?right[face]:left[face];
			for(int e=macroTotal.first(row);e>=0;e=macroTotal.next(e)){
				long key=macroTotal.key(e);
				int source=8*from+Integer.numberOfTrailingZeros((int)(key>>>8));
				int target=8*to+Integer.numberOfTrailingZeros((int)key&255);
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
		long key=macroTotal.key(entry),reverse=(key&255)<<8|(key>>>8);
		double liquidFlux=macroFlows.quantity(row,key)-macroFlows.quantity(row^1,reverse);
		return Math.max(0,macroTotal.amount(entry)-liquidFlux);
	}
	private boolean advectsGas(int face){
		return GasComplementGeometry.fullFixedConnection(domain.geometry.cutCells,left[face],right[face],axis[face]);
	}
	/** Fits source weights to the final dry support geometry. */
	private void normalizeGasSources() {
		for (int i=0;i<capacityCount;i++) {
			int row=capacitySlots[i];double dry=finalDry[row];
			if (dry <= 0) {
				gasTags.clear(row);
				continue;
			}
			if (gasTags.first(row)<0) gasTags.add(row, - 1, dry);
			else gasTags.scale(row, dry);
		}
	}

	private void addMacro(int f, int from, int target, double preferred, double total) {
		int face = f, sourceSupport = support(from), targetSupport = support(target);
		if (sourceSupport == 0 || targetSupport == 0) return;
		int row = 2 * face + (from / 8 == left[face] ? 0 : 1);
		long key = (long) sourceSupport << 8 | targetSupport;
		if (preferred > 0) macroFlows.add(row, key, preferred);
		macroTotal.add(row, key, total);
	}

	private int support(int piece) {
		return CellRemapMicroTopology.support(domain.geometry.cutCells.mask(piece / 8), piece & 7);
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
		var gasCapacityGroups=prepareGasGroups(capacities);
		var routes=PhaseQuantitySettlement.settle(numericalRoutes,capacities,
                gasCapacityGroups==null?null:gasCapacityGroups.receiverGroup(),gasCapacityGroups==null?null:gasCapacityGroups.upper(),
                source -> prepareAlternatives(source, capacities));
		return working.planRoutes(routes, FluidThermalBinding.liquidEnergyPartitioner(domain),inventoryFinalizer);
	}

	/** The initial allocation contains only physical preferences and the retained source share. */
	private void prepareRoutes(List<LiquidCellInventory.Stock> origins, SignedDyadic[] capacities) {
		numericalRoutes.reset(origins,capacities.length);
		numericalRoutes.materialLayers(domain.materialRegistry,sizeX,sizeY,domain.config.gravity());
		for (int source = 0; source<numericalRoutes.sourceCount(); source++) {
			var key = numericalRoutes.key(source);
			int cell = key.cell(), support = key.supportMask();
			double volume=numericalRoutes.volume(source);
			double outgoing = 0;
			for (int side = 0; side<6; side++) {
				int face = coarseFaceAt[6 * cell + side];
				if (face<0) continue;
				int direction = cell == left[face] ? 0 : 1, row = 2 * face + direction, target = direction == 0 ? right[face] : left[face];
				for (int targets=neighborSupports(face,direction,support);targets!=0;) {
					int targetSupport=CellRemapMicroTopology.support(domain.geometry.cutCells.mask(target),Integer.numberOfTrailingZeros(targets));
					targets&=~targetSupport;
					if (capacities[8 * target + Integer.numberOfTrailingZeros(targetSupport)].signum() == 0) continue;
					double amount = 0;
					long pair = (long) support << 8 | targetSupport;
					amount = macroFlows.quantity(row,pair);
					outgoing += amount;
					if (amount > 0) numericalRoutes.add(source,face,target,targetSupport,amount);
				}
			}
			numericalRoutes.add(source,-1,cell,support,Math.max(0,volume-outgoing));
		}
	}

	/** Standard residual search opens alternatives only for the actual source being visited. */
	private void prepareAlternatives(int source, SignedDyadic[] capacities) {
		var key = numericalRoutes.key(source);
		int cell = key.cell(), support = key.supportMask();
		for (int side = 0; side < 6; side++) {
			int face = coarseFaceAt[6 * cell + side];
			if (face < 0) continue;
			int direction = cell == left[face] ? 0 : 1, row = 2 * face + direction;
			int target = direction == 0 ? right[face] : left[face];
			for (int targets=neighborSupports(face,direction,support);targets!=0;) {
				int targetSupport=CellRemapMicroTopology.support(domain.geometry.cutCells.mask(target),Integer.numberOfTrailingZeros(targets));
				targets&=~targetSupport;
				if (capacities[8 * target + Integer.numberOfTrailingZeros(targetSupport)].signum() > 0)
					numericalRoutes.alternative(source, face, target, targetSupport);
			}
		}
	}

	/** A finite chamber constrains aggregate space while liquid may rearrange freely inside it. */
    private GasCapacityGroups prepareGasGroups(SignedDyadic[] capacities){
        var frame=domain.gasState.frame;if(frame==null||frame.nodeCount()==0)return null;
        int count=frame.nodeCount(),groups=0;
        int[] nodeGroup=new int[count+1];Arrays.fill(nodeGroup,-1);
        for(int node=1;node<=count;node++)if(!domain.gasState.network.inventory(node).isEmpty())nodeGroup[node]=groups++;
        if(groups==0)return null;
        int[] receiverGroup=new int[capacities.length];Arrays.fill(receiverGroup,-1);
        double[] groupPore=new double[groups],target=new double[groups];double available=0,totalTarget=0;
        // Independent pressure owners reserve their own actual dry support. Capacity
        // feasibility never merges reservoirs merely because a predicted gas flux connects them.
        for(int i=0;i<capacityCount;i++){
            int row=capacitySlots[i];double capacity=capacities[row].toDouble();available+=capacity;
            for(int e=gasTags.first(row);e>=0;e=gasTags.next(e)){
                int node=(int)gasTags.key(e);if(node<=0||nodeGroup[node]<0)continue;
                int group=nodeGroup[node];receiverGroup[row]=group;groupPore[group]+=capacity;break;
            }
        }
        for(var stock:working.stocks())available-=stock.volumeFraction();
        for(int node=1;node<=count;node++)if(nodeGroup[node]>=0){
            double oldVolume=domain.gasState.network.availableVolume(node)/domain.unitScale.cubicMetersPerNormalizedVolume();
            double volume=Math.min(oldVolume,PhaseOccupancyState.VOLUME_RESOLUTION);
            target[nodeGroup[node]]=volume;totalTarget+=volume;
        }
        if(!(available>0))throw new CapacityUnavailable("Nonempty finite gas has no actual remaining pore volume");
        double scale=totalTarget>available?available/totalTarget:1;var upper=new SignedDyadic[groups];
        for(int group=0;group<groups;group++){
            double volume=Math.min(groupPore[group],scale*target[group]);
            upper[group]=SignedDyadic.of(groupPore[group]).subtract(SignedDyadic.of(volume));
        }
        return new GasCapacityGroups(receiverGroup,upper);
    }

	/** Associates final dry capacity with its transported gas sources. */
	private GasVolumeAllocation gasAllocation(LiquidCellInventory future) {
		var shares = new ArrayList<GasVolumeAllocation.Share>();
		for(var component:preparedCapacity.components){
			if(component.availableVolumeNormalized().signum()==0)continue;
			int row=8*component.cell()+Integer.numberOfTrailingZeros(component.supportMask());
			// Provenance belongs to this actual fixed support, including compact dry
			// volume whose micro occupation rounds to a full liquid cell.
			double gas=component.availableVolumeNormalized().toDouble(),total=gasTags.rowSum(row);
			for(int e=gasTags.first(row);e>=0;e=gasTags.next(e)){
				int node=(int)gasTags.key(e);double part=gas*(gasTags.amount(e)/total);
				if(part>0)shares.add(new GasVolumeAllocation.Share(node,row,part,node>0
						?GasVolumeAllocation.SourceKind.EXISTING:node==0
						?GasVolumeAllocation.SourceKind.AMBIENT:GasVolumeAllocation.SourceKind.EXPLICIT_VACUUM));
			}
		}
		preparePhase();
		return new GasVolumeAllocation(domain, future, new PhaseOccupancyState(liquid), shares, phase, null,preparedCapacity);
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

	GasVolumeAllocation gasAllocation() {
		return allocation;
	}

	LiquidCellInventory sourceInventory() {
		return input;
	}


	void prepareDisplacement(long[] occupied, LiquidCellInventory source) {
		if (source == domain.liquidCells) prepare(0, occupied);
		else {
			// Reactions can change liquid volume before the joint solid endpoint and
			// displacement are settled. Geometry and gas start from the real old
			// domain; only the final displaced inventory has to fit the new pore.
			preparePlan(0, occupied, source,null);
		}
	}

	static final class CapacityUnavailable extends IllegalStateException {
		CapacityUnavailable(String message) {
			super(message);
		}
	}
}
