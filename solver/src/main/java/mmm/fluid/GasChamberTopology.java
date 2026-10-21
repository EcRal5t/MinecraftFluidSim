package mmm.fluid;

import mmm.fluid.air.GasLimits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * Gas shares one pressure within each coarse fixed-pore component.
 * Adjacent components connect through their shared PLIC dry opening; fixed cut apertures retain finite orifice flow.
 */
final class GasChamberTopology {
	record Chamber(int node, int minimumPiece, double geometricVolume, double volumeErrorBound) { }
	record Connection(int firstNode, int secondNode, double area) { }
	private final int[] pieceNodes;
	private final int[] componentNodes;
	private final GasComplementGeometry geometry;
	private final List<Chamber> chambers;
	private final List<Connection> connections;
	private final boolean sealedExterior;
	private GasChamberCapacity preparedCapacity;

	private GasChamberTopology(GasComplementGeometry geometry, int[] pieceNodes, int[] componentNodes, List<Chamber> chambers, List<Connection> connections, boolean sealedExterior) {
		this.geometry = geometry;
		this.sealedExterior = sealedExterior;
		this.pieceNodes = pieceNodes; this.componentNodes=componentNodes; this.chambers = List.copyOf(chambers); this.connections = List.copyOf(connections);
	}

	static GasChamberTopology capture(GasComplementGeometry geometry, boolean sealedExterior) {
		if (geometry == null) throw new IllegalArgumentException("Gas chamber geometry is missing");
		var cached=geometry.cachedTopology(sealedExterior);if(cached!=null)return cached;
		int cells = cellCount(geometry);
		int[] componentOfPiece = new int[8 * cells];
		Arrays.fill(componentOfPiece, -1);
		int[] minimumPiece = new int[4 * cells];
		int count = 0;
		// One union-find item per coarse pore component; micro pieces only name geometry.
		for (int cell = 0; cell < cells; cell++) {
			for (int support : CellRemapMicroTopology.components(geometry.collisionMask(cell))) {
				int minimum = Integer.MAX_VALUE;
				for (int bits = support; bits != 0; bits &= bits - 1) {
					int piece = 8 * cell + Integer.numberOfTrailingZeros(bits);
					if (geometry.piece(piece) == null) continue;
					minimum = Math.min(minimum, piece);
					componentOfPiece[piece] = count;
				}
				if (minimum != Integer.MAX_VALUE) minimumPiece[count++] = minimum;
			}
		}
		int[] parent = new int[count];
		byte[] rank = new byte[count];
		for (int component = 0; component < count; component++) parent[component] = component;
		for (var port : geometry.ports()) if (port.fullCutConnection())
			join(parent, rank, componentOfPiece[port.first()], componentOfPiece[port.second()]);
		boolean[] ambient = new boolean[count];
		if (!sealedExterior) for (var port : geometry.exteriorPorts()) if (port.fullCutConnection())
			ambient[root(parent, componentOfPiece[port.piece()])] = true;
		int[] minimum = new int[count];
		Arrays.fill(minimum, Integer.MAX_VALUE);
		for (int component = 0; component < count; component++) {
			int owner = root(parent, component);
			minimum[owner] = Math.min(minimum[owner], minimumPiece[component]);
		}
		long[] roots = new long[count];
		int nodes = 0;
		for (int component = 0; component < count; component++) if (parent[component] == component && !ambient[component])
			roots[nodes++] = (long) minimum[component] << 32 | Integer.toUnsignedLong(component);
		if (nodes > GasLimits.MAX_NODES_PER_REGION)
			throw new GasTopologyBlockedException("Gas chamber count exceeds its representation budget");
		Arrays.sort(roots, 0, nodes);
		int[] owners = new int[count];
		for (int node = 0; node < nodes; node++) owners[(int) roots[node]] = node + 1;
		int[] pieceNodes = new int[8 * cells];
		Arrays.fill(pieceNodes, -1);
		int[] componentNodes=new int[8*cells];Arrays.fill(componentNodes,-1);
		double[] volumes = new double[nodes + 1], errors = new double[nodes + 1];
		for (var piece : geometry.pieces()) {
			int owner = owners[parent[componentOfPiece[piece.id()]]];
			pieceNodes[piece.id()] = owner;
			int support=CellRemapMicroTopology.support(geometry.collisionMask(piece.cell()),piece.micro());
			componentNodes[8*piece.cell()+Integer.numberOfTrailingZeros(support)]=owner;
			volumes[owner] += piece.volume();
			errors[owner] += pieceError(piece) + Math.ulp(volumes[owner]);
		}
		var chambers = new ArrayList<Chamber>(nodes);
		for (int node = 0; node < nodes; node++) {
			int owner = node + 1;
			double volume = positive(volumes[owner], "Gas chamber volume");
			chambers.add(new Chamber(owner, (int) (roots[node] >>> 32), volume,
					Math.nextUp(errors[owner] + Math.ulp(volume))));
		}
		var result=new GasChamberTopology(geometry,pieceNodes,componentNodes,chambers,connections(geometry,pieceNodes,sealedExterior),sealedExterior);
		geometry.cacheTopology(sealedExterior,result);return result;
	}
	/** Reuse component membership when the fixed pore graph is unchanged; capacity and orifice areas remain current. */
	static void continueCached(GasComplementGeometry geometry,GasComplementGeometry source) {
		if(!geometry.sameCollisionMasks(source)||!sameConnections(geometry,source))return;
		int cells=cellCount(geometry);
		for(int cell=0;cell<cells;cell++)for(int support:CellRemapMicroTopology.components(geometry.collisionMask(cell))) {
			boolean before=false,after=false;
			for(int bits=support;bits!=0;bits&=bits-1) {
				int piece=8*cell+Integer.numberOfTrailingZeros(bits);
				before|=source.piece(piece)!=null;after|=geometry.piece(piece)!=null;
			}
			if(before!=after)return;
		}
		for(boolean sealed:new boolean[]{false,true}) {
			var previous=source.cachedTopology(sealed);if(previous==null)continue;
			int nodes=previous.chambers.size();
			int[] pieceNodes=new int[8*cells],minimum=new int[nodes+1];
			Arrays.fill(pieceNodes,-1);Arrays.fill(minimum,Integer.MAX_VALUE);
			double[] volumes=new double[nodes+1],errors=new double[nodes+1];
			for(var piece:geometry.pieces()) {
				int slot=componentSlot(geometry,piece.id()),node=previous.componentNodes[slot];
				pieceNodes[piece.id()]=node;minimum[node]=Math.min(minimum[node],piece.id());
				volumes[node]+=piece.volume();errors[node]+=pieceError(piece)+Math.ulp(volumes[node]);
			}
			// Stable node numbering still follows the current minimum represented piece.
			boolean ordered=true;
			for(int node=1;node<=nodes;node++)if(minimum[node]==Integer.MAX_VALUE||node>1&&minimum[node]<=minimum[node-1]){ordered=false;break;}
			if(!ordered)continue;
			var chambers=new ArrayList<Chamber>(nodes);
			for(int node=1;node<=nodes;node++) {
				double volume=positive(volumes[node],"Gas chamber volume");
				chambers.add(new Chamber(node,minimum[node],volume,Math.nextUp(errors[node]+Math.ulp(volume))));
			}
			geometry.cacheTopology(sealed,new GasChamberTopology(geometry,pieceNodes,previous.componentNodes,chambers,
					connections(geometry,pieceNodes,sealed),sealed));
		}
	}
	private static boolean sameConnections(GasComplementGeometry first,GasComplementGeometry second) {
		var a=first.ports();var b=second.ports();if(a.size()!=b.size())return false;
		for(int i=0;i<a.size();i++) {
			var x=a.get(i);var y=b.get(i);
			if(x.axis()!=y.axis()||x.fullCutConnection()!=y.fullCutConnection()
					||componentSlot(first,x.first())!=componentSlot(second,y.first())
					||componentSlot(first,x.second())!=componentSlot(second,y.second()))return false;
		}
		var c=first.exteriorPorts();var d=second.exteriorPorts();if(c.size()!=d.size())return false;
		for(int i=0;i<c.size();i++) {
			var x=c.get(i);var y=d.get(i);
			if(x.axis()!=y.axis()||x.sign()!=y.sign()||x.fullCutConnection()!=y.fullCutConnection()
					||componentSlot(first,x.piece())!=componentSlot(second,y.piece()))return false;
		}
		return true;
	}
	private static int componentSlot(GasComplementGeometry geometry,int piece) {
		int cell=piece/8,support=CellRemapMicroTopology.support(geometry.collisionMask(cell),piece&7);
		return 8*cell+Integer.numberOfTrailingZeros(support);
	}
	private static List<Connection> connections(GasComplementGeometry geometry,int[] pieceNodes,boolean sealed) {
		var areas=new TreeMap<Long,Double>();
		for(var port:geometry.ports())addArea(areas,pieceNodes[port.first()],pieceNodes[port.second()],port.area());
		if(!sealed)for(var port:geometry.exteriorPorts())addArea(areas,0,pieceNodes[port.piece()],port.area());
		var result=new ArrayList<Connection>(areas.size());
		for(var entry:areas.entrySet())result.add(new Connection((int)(entry.getKey()>>>32),(int)(long)entry.getKey(),positive(entry.getValue(),"Gas chamber port area")));
		return result;
	}
	private static void addArea(TreeMap<Long, Double> areas, int first, int second, double area) {
		if (first == second) return;
		int low = Math.min(first, second), high = Math.max(first, second);
		areas.merge((long) low << 32 | (high & 0xffffffffL), area, Double::sum);
	}
    private static double pieceError(GasComplementGeometry.Piece piece) { return .5 * Math.ulp(piece.volume()); }
	private static int cellCount(GasComplementGeometry geometry) { return Math.multiplyExact(geometry.sizeX(), Math.multiplyExact(geometry.sizeY(), geometry.sizeZ())); }
	private static int root(int[] parent, int piece) {
		int root = piece; while (parent[root] != root) root = parent[root];
		while (parent[piece] != piece) { int next = parent[piece]; parent[piece] = root; piece = next; }
		return root;
	}
	private static void join(int[] parent, byte[] rank, int first, int second) {
		int a = root(parent, first), b = root(parent, second);
		if (a == b) return;
		if (rank[a] < rank[b]) parent[a] = b;
		else { parent[b] = a; if (rank[a] == rank[b]) rank[a]++; }
	}
	private static double positive(double result, String name) {
		if (!(result > 0 && Double.isFinite(result))) throw new GasTopologyBlockedException(name + " is not representable");
		return result;
	}
	int[] copyPieceNodes() { return pieceNodes.clone(); }
	int nodeOfPiece(int piece) { return pieceNodes[piece]; }
	int nodeOfComponent(int cell,int support) { return componentNodes[8*cell+Integer.numberOfTrailingZeros(support)]; }
	boolean matchesGeometry(GasComplementGeometry candidate) { return geometry == candidate; }
	GasChamberCapacity preparedCapacity(){return preparedCapacity;}
	void preparedCapacity(GasChamberCapacity capacity){preparedCapacity=capacity;}
	List<Chamber> chambers() { return chambers; }
	List<Connection> connections() { return connections; }
}
