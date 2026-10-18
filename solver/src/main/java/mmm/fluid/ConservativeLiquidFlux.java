package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One bounded allocation graph from original connected stocks to future pore receivers. */
final class ConservativeLiquidFlux {
	final int sizeX,sizeY,sizeZ,cellCount,physicalFaceCount;
	final int[] left,right,axis;
	final double[] velocity,flux,predictedFaceFlux;
	final SignedDyadic[] exactFlux;
	private final SignedDyadic[] after,exactPoreCapacity,exactCapacity;
	private LiquidFluxGeometry geometry;
	private byte[] topologyMasks;
	private int[] starts,cells,supports,donor,receiver,arcFace,self;
	private int[][] incoming,outgoing;
	private SignedDyadic[] supply,pore,capacity,minimum,load,assigned;
	private double[] sourcePore;
	private boolean[] gasReachable;
	private int liquidArcCount;
	private GasVolumeProposal gasProposal;
	private int[] predecessor,predecessorArc,queue,seen;
	private int generation;
	private long workRemaining=Long.MAX_VALUE;
	private long allocationWork,allocationArcVisits;
	private double capacityCorrectionVolume,maximumCapacityResidual;
	private double gasPredictionAdjustmentVolume,maximumRelativeGasAdjustment;
	private double faceFluxCorrectionVolume,maximumFaceFluxCorrection;
	private int capacityCorrectionPaths;
	private record Link(int source,int target,int face) { }
	record Port(int face,int donorCell,int receiverCell,int donorSupport,int receiverSupport,SignedDyadic volume) { }

	ConservativeLiquidFlux(int sx,int sy,int sz) { this(sx,sy,sz,false); }
	ConservativeLiquidFlux(int sx,int sy,int sz,boolean ignored) {
		sizeX=sx;sizeY=sy;sizeZ=sz;cellCount=Math.multiplyExact(Math.multiplyExact(sx,sy),sz);
		physicalFaceCount=(sx-1)*sy*sz+sx*(sy-1)*sz+sx*sy*(sz-1);
		left=new int[physicalFaceCount];right=left.clone();axis=left.clone();
		velocity=new double[physicalFaceCount];flux=velocity.clone();predictedFaceFlux=velocity.clone();exactFlux=new SignedDyadic[physicalFaceCount];
		after=new SignedDyadic[cellCount];exactPoreCapacity=after.clone();exactCapacity=after.clone();
		int face=0;
		for(int z=0;z<sz;z++)for(int y=0;y<sy;y++)for(int x=1;x<sx;x++){left[face]=index(x-1,y,z);right[face]=left[face]+1;axis[face++]=0;}
		for(int z=0;z<sz;z++)for(int y=1;y<sy;y++)for(int x=0;x<sx;x++){left[face]=index(x,y-1,z);right[face]=left[face]+sx;axis[face++]=1;}
		for(int z=1;z<sz;z++)for(int y=0;y<sy;y++)for(int x=0;x<sx;x++){left[face]=index(x,y,z-1);right[face]=left[face]+sx*sy;axis[face++]=2;}
	}
	void geometry(LiquidFluxGeometry value){geometry=value;}
	double capacityCorrectionVolume(){return capacityCorrectionVolume;}
	double maximumCapacityResidual(){return maximumCapacityResidual;}
	int capacityCorrectionPaths(){return capacityCorrectionPaths;}
	long allocationWork(){return allocationWork;}
	long allocationArcVisits(){return allocationArcVisits;}
	int componentCount(){return cells==null?0:cells.length;}
	int allocationArcCount(){return donor==null?0:donor.length;}
	double gasPredictionAdjustmentVolume(){return gasPredictionAdjustmentVolume;}
	double maximumRelativeGasAdjustment(){return maximumRelativeGasAdjustment;}
	double faceFluxCorrectionVolume(){return faceFluxCorrectionVolume;}
	double maximumFaceFluxCorrection(){return maximumFaceFluxCorrection;}

	/** Mixture pressure supplies total pore flux; actual donor fractions partition
	 * it into liquid and complementary gas volume at the final velocity. */
	void plan(double[] volumes,boolean[] solid,double dtOverH,SignedDyadic[] exactVolumes) {
		workRemaining=Long.MAX_VALUE;resetDiagnostics();
		if(geometry!=null)geometry.prepareTransport();
		prepareTopology(solid,geometry==null?null:geometry.cutCells());
		prepareQuantities(exactVolumes,null,null);
		Arrays.fill(assigned,SignedDyadic.ZERO);
		for(int arc=0;arc<assigned.length;arc++) if(arcFace[arc]>=0) {
			int face=arcFace[arc],source=donor[arc],target=receiver[arc];
			boolean positive=cells[source]==left[face];
			if((velocity[face]>=0)!=positive)continue;
			double width=Math.abs(velocity[face])*dtOverH;
			if(width==0)continue;
			double coefficient=geometry==null?1:geometry.totalPortCoefficient(cells[source],cells[target],axis[face],supports[source],supports[target]);
			double theta=sourcePore[source]>0?Math.min(1,supply[source].toDouble()/sourcePore[source]):0;
			double amount=width*coefficient*theta;
			if(!(Double.isFinite(amount)&&amount>=0))throw new IllegalStateException("Invalid shared component liquid flux");
			assigned[arc]=SignedDyadic.of(amount);
			predictedFaceFlux[face]+=positive?amount:-amount;
		}
		limitOriginalSupply();initializeReceivers();
		if(geometry!=null)appendGasDonors(geometry.prepareGasReservations(cells,supports,load,pore));
		projectCapacities();finishGasAllocation();publishFaces();
	}

	void planDisplacement(SignedDyadic[] volumes,SignedDyadic[] capacities,SignedDyadic[] displacement,
	                      boolean[] solid,FlipCutCellGeometry cut,long budget) {
		planDisplacement(volumes,capacities,displacement,solid,cut,budget,null);
	}
	void planDisplacement(SignedDyadic[] volumes,SignedDyadic[] capacities,SignedDyadic[] displacement,
	                      boolean[] solid,FlipCutCellGeometry cut,long budget,LiquidCellInventory source) {
		workRemaining=budget;resetDiagnostics();geometry=new LiquidFluxGeometry(cut);
		prepareTopology(solid,cut);prepareQuantities(volumes,capacities,source);
		Arrays.fill(assigned,SignedDyadic.ZERO);
		for(int node=0;node<cells.length;node++)assigned[self[node]]=supply[node];
		initializeReceivers();
		// Some callers remove a fixed-cell amount as well as changing physical pore volume.
		for(int cell=0;cell<cellCount;cell++)if(displacement[cell].signum()>0) {
			if(displacement[cell].compareTo(volumes[cell])>0)throw new IllegalArgumentException("Displacement exceeds source volume");
			var target=min(capacities[cell],volumes[cell].subtract(displacement[cell]));
			var bounds=componentCapacities(cell);var preferences=new double[bounds.length];
			for(int i=0;i<bounds.length;i++)preferences[i]=bounds[i].toDouble();
			var shares=LiquidCellInventory.allocateVolumes(target,bounds,preferences);
			for(int node=starts[cell];node<starts[cell+1];node++)capacity[node]=shares[node-starts[cell]];
		}
		projectCapacities();publishFaces();
		// Retained liquid uses the actual final pore; deliberate displacement only requests movement.
		for(int node=0;node<cells.length;node++)capacity[node]=pore[node];
		aggregateCapacities();
	}

	private void resetDiagnostics(){capacityCorrectionVolume=maximumCapacityResidual=gasPredictionAdjustmentVolume=maximumRelativeGasAdjustment=faceFluxCorrectionVolume=maximumFaceFluxCorrection=0;capacityCorrectionPaths=0;allocationWork=allocationArcVisits=0;Arrays.fill(predictedFaceFlux,0);}
	@SuppressWarnings("unchecked")
	private void prepareTopology(boolean[] solid,FlipCutCellGeometry cut) {
		spend(cellCount+(long)physicalFaceCount);
		byte[] masks=new byte[cellCount];
		for(int cell=0;cell<cellCount;cell++)masks[cell]=(byte)(solid[cell]?255:cut==null?0:cut.mask(cell));
		if(Arrays.equals(masks,topologyMasks)){resetGasTopology();return;}
		topologyMasks=masks;starts=new int[cellCount+1];
		for(int cell=0;cell<cellCount;cell++)starts[cell+1]=starts[cell]+CellRemapMicroTopology.components(Byte.toUnsignedInt(masks[cell])).length;
		int count=starts[cellCount];cells=new int[count];supports=new int[count];
		for(int cell=0;cell<cellCount;cell++){int ordinal=starts[cell];for(int support:CellRemapMicroTopology.components(Byte.toUnsignedInt(masks[cell]))){cells[ordinal]=cell;supports[ordinal++]=support;}}
		var links=new ArrayList<Link>();self=new int[count];
		for(int node=0;node<count;node++){self[node]=links.size();links.add(new Link(node,node,-1));}
		for(int face=0;face<physicalFaceCount;face++) {
			var pairs=new ArrayList<Long>(4);
			for(int micro=0;micro<8;micro++)if((micro>>>axis[face]&1)!=0){
				int a=CellRemapMicroTopology.support(Byte.toUnsignedInt(masks[left[face]]),micro);
				int b=CellRemapMicroTopology.support(Byte.toUnsignedInt(masks[right[face]]),micro^(1<<axis[face]));
				if(a==0||b==0)continue;long pair=(long)a<<8|b;if(pairs.contains(pair))continue;pairs.add(pair);
				int source=node(left[face],a),target=node(right[face],b);
				links.add(new Link(source,target,face));links.add(new Link(target,source,face));
			}
		}
		liquidArcCount=links.size();gasProposal=null;
		donor=new int[liquidArcCount];receiver=donor.clone();arcFace=donor.clone();assigned=new SignedDyadic[liquidArcCount];
		for(int arc=0;arc<links.size();arc++){var link=links.get(arc);donor[arc]=link.source();receiver[arc]=link.target();arcFace[arc]=link.face();}
		supply=new SignedDyadic[count];pore=supply.clone();capacity=supply.clone();minimum=supply.clone();load=supply.clone();
		sourcePore=new double[count];
		gasReachable=new boolean[count];
		rebuildAdjacency();
	}
	private void resetGasTopology(){
		gasProposal=null;
		if(donor.length==liquidArcCount)return;
		donor=Arrays.copyOf(donor,liquidArcCount);receiver=Arrays.copyOf(receiver,liquidArcCount);arcFace=Arrays.copyOf(arcFace,liquidArcCount);
		assigned=new SignedDyadic[liquidArcCount];supply=Arrays.copyOf(supply,cells.length);rebuildAdjacency();
	}
	private void rebuildAdjacency(){
		int[] outCounts=new int[supply.length],inCounts=new int[cells.length];
		for(int arc=0;arc<donor.length;arc++){outCounts[donor[arc]]++;inCounts[receiver[arc]]++;}
		outgoing=new int[supply.length][];incoming=new int[cells.length][];
		for(int node=0;node<outgoing.length;node++)outgoing[node]=new int[outCounts[node]];
		for(int node=0;node<incoming.length;node++)incoming[node]=new int[inCounts[node]];
		Arrays.fill(outCounts,0);Arrays.fill(inCounts,0);
		for(int arc=0;arc<donor.length;arc++){outgoing[donor[arc]][outCounts[donor[arc]]++]=arc;incoming[receiver[arc]][inCounts[receiver[arc]]++]=arc;}
		predecessor=new int[cells.length+supply.length];predecessorArc=predecessor.clone();queue=predecessor.clone();seen=predecessor.clone();generation=0;
	}
	/** Chamber volume is a donor in the same sparse allocation as original liquid stocks. */
	private void appendGasDonors(GasVolumeProposal proposal){
		gasProposal=proposal;
		if(proposal==null||proposal.volumes().length==0)return;
		int arcs=0;for(int[] targets:proposal.receivers())for(int target:targets)if(gasReachable[target])arcs=Math.addExact(arcs,1);
		supply=Arrays.copyOf(supply,cells.length+proposal.volumes().length);
		donor=Arrays.copyOf(donor,liquidArcCount+arcs);receiver=Arrays.copyOf(receiver,donor.length);arcFace=Arrays.copyOf(arcFace,donor.length);assigned=Arrays.copyOf(assigned,donor.length);
		int arc=liquidArcCount;
		for(int gas=0;gas<proposal.volumes().length;gas++){
			int original=cells.length+gas;supply[original]=proposal.volumes()[gas];
			int[] allTargets=proposal.receivers()[gas];int count=0;for(int target:allTargets)if(gasReachable[target])count++;
			if(count==0)throw new CapacityUnavailable("Finite gas proposal has no space-time reachable support");
			int[] targets=new int[count];double[] weights=new double[count];int next=0;
			for(int j=0;j<allTargets.length;j++)if(gasReachable[allTargets[j]]){targets[next]=allTargets[j];weights[next++]=proposal.preferences()[gas][j];}
			var bounds=new SignedDyadic[targets.length];Arrays.fill(bounds,supply[original]);
			var portions=LiquidCellInventory.allocateVolumes(supply[original],bounds,weights);
			for(int j=0;j<targets.length;j++,arc++){
				if(targets[j]<0||targets[j]>=cells.length)throw new IllegalArgumentException("Gas proposal receiver is outside connected pore graph");
				donor[arc]=original;receiver[arc]=targets[j];arcFace[arc]=-1;assigned[arc]=portions[j];
			}
		}
		rebuildAdjacency();initializeReceivers();
	}
	private void finishGasAllocation(){
		if(gasProposal==null)return;
		var gasAmounts=new SignedDyadic[cells.length];Arrays.fill(gasAmounts,SignedDyadic.ZERO);
		for(int arc=liquidArcCount;arc<assigned.length;arc++)gasAmounts[receiver[arc]]=gasAmounts[receiver[arc]].add(assigned[arc]);
		for(int node=0;node<cells.length;node++){capacity[node]=pore[node].subtract(gasAmounts[node]);load[node]=load[node].subtract(gasAmounts[node]);}
		geometry.acceptGasReservations(cells,supports,gasAmounts);
	}
	private void prepareQuantities(SignedDyadic[] volumes,SignedDyadic[] future,LiquidCellInventory source) {
		spend(cellCount+2L*cells.length);
		Arrays.fill(gasReachable,true);
		for(int cell=0;cell<cellCount;cell++) {
			int mask=Byte.toUnsignedInt(topologyMasks[cell]);
			var whole=future==null?SignedDyadic.of(geometry==null?(mask==255?0:1):geometry.finalPoreCapacity(cell)):future[cell];
			exactPoreCapacity[cell]=whole;
			var capacities=CellRemapMicroTopology.capacities(mask,whole);
			var sourceAmounts=new SignedDyadic[capacities.length];Arrays.fill(sourceAmounts,SignedDyadic.ZERO);
			if(source!=null){for(var stock:source.stocksInCell(cell))sourceAmounts[node(cell,stock.key().supportMask())-starts[cell]]=sourceAmounts[node(cell,stock.key().supportMask())-starts[cell]].add(stock.volume());}
			else if(geometry!=null && geometry.hasCellInventory()){for(int j=0;j<sourceAmounts.length;j++)sourceAmounts[j]=geometry.componentVolume(cell,supports[starts[cell]+j]);}
			else if(sourceAmounts.length>0){
				var sourceCaps=CellRemapMicroTopology.capacities(mask,SignedDyadic.of(geometry==null?mask==255?0:1:geometry.capacity(cell)));
				var preferences=Arrays.stream(sourceCaps).mapToDouble(SignedDyadic::toDouble).toArray();
				sourceAmounts=LiquidCellInventory.allocateVolumes(volumes[cell],sourceCaps,preferences);
			}
			var currentCaps=CellRemapMicroTopology.capacities(mask,SignedDyadic.of(geometry==null?(mask==255?0:1):geometry.capacity(cell)));
			for(int j=0;j<capacities.length;j++){
				int node=starts[cell]+j;supply[node]=sourceAmounts[j];pore[node]=capacity[node]=capacities[j];minimum[node]=SignedDyadic.ZERO;sourcePore[node]=currentCaps[j].toDouble();
				if(geometry!=null&&geometry.hasCellInventory())gasReachable[node]=currentCaps[j].compareTo(sourceAmounts[j])>0;
			}
		}
		if(geometry!=null&&geometry.hasCellInventory()){
			var hadGas=gasReachable.clone();
			for(int arc=cells.length;arc<liquidArcCount;arc++)if(hadGas[donor[arc]])gasReachable[receiver[arc]]=true;
			for(int node=0;node<cells.length;node++)if(!gasReachable[node])minimum[node]=pore[node];
		}
	}
	private void limitOriginalSupply() {
		for(int node=0;node<cells.length;node++){
			SignedDyadic sum=SignedDyadic.ZERO;for(int arc:outgoing[node])if(arcFace[arc]>=0)sum=sum.add(assigned[arc]);
			if(sum.compareTo(supply[node])>0){
				int[] arcs=Arrays.stream(outgoing[node]).filter(arc->arcFace[arc]>=0&&assigned[arc].signum()>0).toArray();
				var bounds=new SignedDyadic[arcs.length];var weights=new double[arcs.length];
				for(int i=0;i<arcs.length;i++){bounds[i]=assigned[arcs[i]];weights[i]=bounds[i].toDouble();}
				var shares=LiquidCellInventory.allocateVolumes(supply[node],bounds,weights);for(int i=0;i<arcs.length;i++)assigned[arcs[i]]=shares[i];sum=supply[node];
			}
			assigned[self[node]]=supply[node].subtract(sum);
		}
	}
	private void initializeReceivers(){Arrays.fill(load,SignedDyadic.ZERO);for(int arc=0;arc<assigned.length;arc++)load[receiver[arc]]=load[receiver[arc]].add(assigned[arc]);}

	/** Every augmenting path cancels one assignment before assigning that same source
	 * elsewhere. Donor totals remain exactly their original inventories throughout. */
	private void projectCapacities() {
		int count=cells.length;
		fillSaturatedReceivers();
		for(int source=0;source<count;source++)while(load[source].compareTo(capacity[source])>0){
			maximumCapacityResidual=Math.max(maximumCapacityResidual,load[source].subtract(capacity[source]).toDouble());
			if(++generation==0){Arrays.fill(seen,0);generation=1;}
			int head=0,tail=0,destination=-1,compressibleGas=-1;queue[tail++]=source;seen[source]=generation;predecessor[source]=-1;
			while(head<tail&&destination<0){
				int vertex=queue[head++];spend(1);
				if(vertex<count){
					for(int arc:incoming[vertex]){visitArc();int next=count+donor[arc];if(assigned[arc].signum()==0||seen[next]==generation)continue;seen[next]=generation;predecessor[next]=vertex;predecessorArc[next]=arc;queue[tail++]=next;}
				}else {
					int original=vertex-count;
					if(original>=count&&compressibleGas<0&&supply[original].compareTo(gasProposal.minimumVolumes()[original-count])>0)compressibleGas=vertex;
					for(int arc:outgoing[original]){visitArc();int next=receiver[arc];if(seen[next]==generation||assigned[arc].compareTo(supply[original])>=0)continue;seen[next]=generation;predecessor[next]=vertex;predecessorArc[next]=arc;queue[tail++]=next;if(load[next].compareTo(capacity[next])<0){destination=next;break;}}
				}
			}
			if(destination<0&&compressibleGas>=0){compressGas(source,compressibleGas);continue;}
			if(destination<0)throw new CapacityUnavailable("Connected liquid allocation has insufficient reachable endpoint capacity at cell "+cells[source]+" support "+supports[source]+": excess="+load[source].subtract(capacity[source]).toDouble()+allocationDiagnostic());
			var amount=min(load[source].subtract(capacity[source]),capacity[destination].subtract(load[destination]));
			for(int vertex=destination;vertex!=source;vertex=predecessor[vertex]){int arc=predecessorArc[vertex];amount=min(amount,vertex<count?supply[donor[arc]].subtract(assigned[arc]):assigned[arc]);}
			for(int vertex=destination;vertex!=source;vertex=predecessor[vertex]){int arc=predecessorArc[vertex];assigned[arc]=vertex<count?assigned[arc].add(amount):assigned[arc].subtract(amount);if(arcFace[arc]>=0)capacityCorrectionVolume+=amount.toDouble();}
			load[source]=load[source].subtract(amount);load[destination]=load[destination].add(amount);capacityCorrectionPaths++;
		}
		// The paired real-face arcs follow each other. Opposed traffic through the same
		// physical port has no effect on receiver capacity and can remain at its source.
		for(int arc=count;arc<liquidArcCount;arc+=2){var cancel=min(assigned[arc],assigned[arc+1]);if(cancel.signum()>0){assigned[arc]=assigned[arc].subtract(cancel);assigned[arc+1]=assigned[arc+1].subtract(cancel);assigned[self[donor[arc]]]=assigned[self[donor[arc]]].add(cancel);assigned[self[donor[arc+1]]]=assigned[self[donor[arc+1]]].add(cancel);}}
	}
	/** Gas-free interior supports remain saturated. A reverse residual path brings
	 * existing liquid from a receiver above its own lower bound to each exact deficit. */
	private void fillSaturatedReceivers(){
		int count=cells.length;
		for(int target=0;target<count;target++)while(load[target].compareTo(minimum[target])<0){
			maximumCapacityResidual=Math.max(maximumCapacityResidual,minimum[target].subtract(load[target]).toDouble());
			if(++generation==0){Arrays.fill(seen,0);generation=1;}
			int head=0,tail=0,source=-1;queue[tail++]=target;seen[target]=generation;predecessor[target]=-1;
			while(head<tail&&source<0){
				int vertex=queue[head++];spend(1);
				if(vertex<count){
					for(int arc:incoming[vertex]){visitArc();int next=count+donor[arc];if(seen[next]==generation||assigned[arc].compareTo(supply[donor[arc]])>=0)continue;seen[next]=generation;predecessor[next]=vertex;predecessorArc[next]=arc;queue[tail++]=next;}
				}else{
					for(int arc:outgoing[vertex-count]){visitArc();int next=receiver[arc];if(seen[next]==generation||assigned[arc].signum()==0)continue;seen[next]=generation;predecessor[next]=vertex;predecessorArc[next]=arc;queue[tail++]=next;if(load[next].compareTo(minimum[next])>0){source=next;break;}}
				}
			}
			if(source<0)throw new CapacityUnavailable("Saturated liquid support has no reachable original supply at cell "+cells[target]+" support "+supports[target]+": deficit="+minimum[target].subtract(load[target]).toDouble());
			var amount=min(minimum[target].subtract(load[target]),load[source].subtract(minimum[source]));
			for(int vertex=source;vertex!=target;vertex=predecessor[vertex]){int arc=predecessorArc[vertex];amount=min(amount,vertex<count?assigned[arc]:supply[donor[arc]].subtract(assigned[arc]));}
			for(int vertex=source;vertex!=target;vertex=predecessor[vertex]){int arc=predecessorArc[vertex];assigned[arc]=vertex<count?assigned[arc].subtract(amount):assigned[arc].add(amount);if(arcFace[arc]>=0)capacityCorrectionVolume+=amount.toDouble();}
			load[target]=load[target].add(amount);load[source]=load[source].subtract(amount);capacityCorrectionPaths++;
		}
	}
	/** EOS target volumes are preferences. Preserve their allocation whenever possible;
	 * otherwise compress only the reached chamber by the exact unresolved liquid deficit. */
	private void compressGas(int source,int destination){
		int count=cells.length,original=destination-count,gas=original-count;
		var amount=min(load[source].subtract(capacity[source]),supply[original].subtract(gasProposal.minimumVolumes()[gas]));
		for(int vertex=destination;vertex!=source;vertex=predecessor[vertex]){
			int arc=predecessorArc[vertex];amount=min(amount,vertex<count?supply[donor[arc]].subtract(assigned[arc]):assigned[arc]);
		}
		for(int vertex=destination;vertex!=source;vertex=predecessor[vertex]){
			int arc=predecessorArc[vertex];assigned[arc]=vertex<count?assigned[arc].add(amount):assigned[arc].subtract(amount);
			if(arcFace[arc]>=0)capacityCorrectionVolume+=amount.toDouble();
		}
		supply[original]=supply[original].subtract(amount);load[source]=load[source].subtract(amount);
		gasPredictionAdjustmentVolume+=amount.toDouble();capacityCorrectionPaths++;
		maximumRelativeGasAdjustment=Math.max(maximumRelativeGasAdjustment,gasProposal.volumes()[gas].subtract(supply[original]).toDouble()/gasProposal.volumes()[gas].toDouble());
	}
	private void publishFaces(){
		Arrays.fill(exactFlux,SignedDyadic.ZERO);Arrays.fill(after,SignedDyadic.ZERO);
		for(int arc=cells.length;arc<liquidArcCount;arc++)if(assigned[arc].signum()>0){int face=arcFace[arc];exactFlux[face]=exactFlux[face].add(cells[donor[arc]]==left[face]?assigned[arc]:assigned[arc].negate());}
		for(int face=0;face<flux.length;face++){flux[face]=exactFlux[face].toDouble();double correction=Math.abs(flux[face]-predictedFaceFlux[face]);faceFluxCorrectionVolume+=correction;maximumFaceFluxCorrection=Math.max(maximumFaceFluxCorrection,correction);if(exactFlux[face].signum()<0)exactFlux[face]=exactFlux[face].negate();}
		for(int node=0;node<cells.length;node++)after[cells[node]]=after[cells[node]].add(load[node]);
		aggregateCapacities();
	}
	private void aggregateCapacities(){Arrays.fill(exactCapacity,SignedDyadic.ZERO);for(int node=0;node<cells.length;node++)exactCapacity[cells[node]]=exactCapacity[cells[node]].add(capacity[node]);}
	private String allocationDiagnostic(){
		var totalSupply=SignedDyadic.ZERO;var totalCapacity=SignedDyadic.ZERO;var reachableLoad=SignedDyadic.ZERO;var reachableCapacity=SignedDyadic.ZERO;int reachable=0;
		for(var amount:supply)totalSupply=totalSupply.add(amount);
		for(int node=0;node<cells.length;node++){totalCapacity=totalCapacity.add(capacity[node]);if(seen[node]==generation){reachableLoad=reachableLoad.add(load[node]);reachableCapacity=reachableCapacity.add(capacity[node]);reachable++;}}
		return ", globalSpare="+totalCapacity.subtract(totalSupply).toDouble()+", reachableSpare="+reachableCapacity.subtract(reachableLoad).toDouble()+", reachableReceivers="+reachable;
	}
	List<Port> ports(){var result=new ArrayList<Port>();for(int arc=cells.length;arc<liquidArcCount;arc++)if(assigned[arc].signum()>0){int a=donor[arc],b=receiver[arc];result.add(new Port(arcFace[arc],cells[a],cells[b],supports[a],supports[b],assigned[arc]));}return result;}
	SignedDyadic componentCapacity(int cell,int support){return capacity[node(cell,support)];}
	SignedDyadic finalCapacity(int cell){return exactCapacity[cell];}
	SignedDyadic finalPoreCapacity(int cell){return exactPoreCapacity[cell];}
	SignedDyadic[] resultingVolumes(){return after;}
	SignedDyadic diagnosticCapacity(int cell){return exactCapacity[cell];}
	boolean positive(int face){return Double.doubleToRawLongBits(flux[face])>=0;}
	int donor(int face){return positive(face)?left[face]:right[face];}
	int receiver(int face){return positive(face)?right[face]:left[face];}
	private int node(int cell,int support){for(int node=starts[cell];node<starts[cell+1];node++)if(supports[node]==support)return node;throw new IllegalArgumentException("Liquid stock has no fixed connected support");}
	private SignedDyadic[] componentCapacities(int cell){return Arrays.copyOfRange(pore,starts[cell],starts[cell+1]);}
	private int index(int x,int y,int z){return (z*sizeY+y)*sizeX+x;}
	private void visitArc(){spend(1);allocationArcVisits++;}
	private void spend(long work){if(work<0||work>workRemaining)throw new FluidSolverBackend.MechanicalWorkBudgetExceededException("Connected liquid allocation exceeded its operation budget");workRemaining-=work;allocationWork+=work;}
	private static SignedDyadic min(SignedDyadic a,SignedDyadic b){return a.compareTo(b)<=0?a:b;}
	static final class CapacityUnavailable extends IllegalStateException { CapacityUnavailable(String message){super(message);} }

}
