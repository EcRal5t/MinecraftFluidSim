package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Exact quantity settlement for sub-unit inventory tails that must change support. */
final class ExactQuantitySettlement {
	private ExactQuantitySettlement() { }

	static List<LiquidCellInventory.TransportRoute> settle(List<LiquidCellInventory.Stock> origins,
			List<LiquidCellInventory.TransportRoute> tentative, SignedDyadic[] upper, SignedDyadic[] minimum) {
		return settle(origins, tentative, upper, minimum, null, null, null);
	}

	static List<LiquidCellInventory.TransportRoute> settle(List<LiquidCellInventory.Stock> origins,
			List<LiquidCellInventory.TransportRoute> tentative, SignedDyadic[] upper, SignedDyadic[] minimum,
			double[] routeMaximum, int[] receiverGroup, SignedDyadic[] groupUpper) {
		if (upper.length != minimum.length) throw new IllegalArgumentException("Liquid quantity capacity columns differ");
		var sourceIndex = new HashMap<LiquidCellInventory.Key, Integer>();
		for (int source = 0; source < origins.size(); source++) {
			if (sourceIndex.put(origins.get(source).key(), source) != null)
				throw new IllegalArgumentException("Repeated original liquid stock");
		}
		int[] routeSource = new int[tentative.size()], routeTarget = new int[tentative.size()];
		var supplied = new SignedDyadic[origins.size()];
		var load = new SignedDyadic[upper.length];
		var routeUpper = new SignedDyadic[tentative.size()];
		Arrays.fill(supplied, SignedDyadic.ZERO);
		Arrays.fill(load, SignedDyadic.ZERO);
		var reachable = new boolean[upper.length];
		boolean adjustment = false;
		for (int route = 0; route < tentative.size(); route++) {
			var item = tentative.get(route);
			Integer source = sourceIndex.get(item.source());
			int target = 8 * item.target().cell() + Integer.numberOfTrailingZeros(item.target().supportMask());
			if (source == null || target < 0 || target >= upper.length || upper[target] == null)
				throw new IllegalArgumentException("Liquid quantity path has no actual source or recipient");
			routeSource[route] = source;
			routeTarget[route] = target;
			reachable[target] = true;
			supplied[source] = supplied[source].add(item.volume());
			load[target] = load[target].add(item.volume());
			routeUpper[route] = origins.get(source).volume();
			if (routeMaximum != null && route < routeMaximum.length) {
				var maximum = SignedDyadic.of(Math.max(0, routeMaximum[route]));
				if (maximum.compareTo(routeUpper[route]) < 0) routeUpper[route] = maximum;
			}
			adjustment |= item.volume().compareTo(routeUpper[route]) > 0;
		}
		for (int source = 0; source < origins.size(); source++) {
			if (!supplied[source].equals(origins.get(source).volume()))
				throw new IllegalArgumentException("Liquid quantity paths do not retain the original stock volume");
		}

		int receivers = 0;
		int[] receiverVertex = new int[upper.length];
		Arrays.fill(receiverVertex, -1);
		for (int slot = 0; slot < upper.length; slot++) if (upper[slot] != null) {
			var low = minimum[slot] == null ? SignedDyadic.ZERO : minimum[slot];
			if (low.signum() < 0 || low.compareTo(upper[slot]) > 0)
				throw new IllegalArgumentException("Invalid liquid quantity recipient capacity");
			adjustment |= load[slot].compareTo(low) < 0 || load[slot].compareTo(upper[slot]) > 0;
			if (reachable[slot] || low.signum() > 0) receiverVertex[slot] = origins.size() + receivers++;
		}
		int groups = groupUpper == null ? 0 : groupUpper.length;
		var groupLoad = new SignedDyadic[groups];
		Arrays.fill(groupLoad, SignedDyadic.ZERO);
		boolean[] groupUsed = new boolean[groups];
		if (receiverGroup != null) for (int slot = 0; slot < upper.length; slot++) {
			int group = receiverGroup[slot];
			if (upper[slot] == null || group < 0) continue;
			groupUsed[group] = true;
			groupLoad[group] = groupLoad[group].add(load[slot]);
		}
		for (int group = 0; group < groups; group++) if (groupUsed[group]) {
			adjustment |= groupLoad[group].compareTo(groupUpper[group]) > 0;
		}
		if (!adjustment) return List.copyOf(tentative);

		int firstGroup = origins.size() + receivers;
		int pool = firstGroup + groups;
		var allocation = new QuantityFlow(pool + 1);
		for (int source = 0; source < origins.size(); source++) {
			var amount = origins.get(source).volume();
			allocation.add(pool, source, amount, amount, amount);
		}
		int[] routeEdges = new int[tentative.size()];
		for (int route = 0; route < tentative.size(); route++) {
			var initial = tentative.get(route).volume();
			if (initial.compareTo(routeUpper[route]) > 0) initial = routeUpper[route];
			routeEdges[route] = allocation.add(routeSource[route], receiverVertex[routeTarget[route]],
					SignedDyadic.ZERO, routeUpper[route], initial);
		}
		for (int slot = 0; slot < receiverVertex.length; slot++) if (receiverVertex[slot] >= 0) {
			var low = minimum[slot] == null ? SignedDyadic.ZERO : minimum[slot];
			var initial = load[slot];
			if (initial.compareTo(low) < 0) initial = low;
			if (initial.compareTo(upper[slot]) > 0) initial = upper[slot];
			int group = receiverGroup == null ? -1 : receiverGroup[slot];
			allocation.add(receiverVertex[slot], group < 0 ? pool : firstGroup + group, low, upper[slot], initial);
		}
		for (int group = 0; group < groups; group++) if (groupUsed[group]) {
			var initial = groupLoad[group];
			if (initial.compareTo(groupUpper[group]) > 0) initial = groupUpper[group];
			allocation.add(firstGroup + group, pool, SignedDyadic.ZERO, groupUpper[group], initial);
		}
		allocation.solve();
		var result = new ArrayList<LiquidCellInventory.TransportRoute>(tentative.size());
		for (int route = 0; route < tentative.size(); route++) {
			var amount = allocation.amount(routeEdges[route]);
			if (amount.signum() > 0) {
				var before = tentative.get(route);
				result.add(new LiquidCellInventory.TransportRoute(before.face(), before.source(), before.target(), amount));
			}
		}
		return List.copyOf(result);
	}

	/** Quantity arithmetic ends here; geometric integration and numerical pressure stay binary64. */
	private static final class QuantityFlow {
		private final int vertices,source,sink;
		private final int[] head,level,cursor,queue,pathVertex,pathEdge;
		private final SignedDyadic[] balance;
		private int[] to=new int[32],next=new int[32];
		private SignedDyadic[] remaining=new SignedDyadic[32];
		private int edges;

		QuantityFlow(int vertices){
			this.vertices=vertices;source=vertices;sink=vertices+1;
			head=new int[vertices+2];Arrays.fill(head,-1);
			level=new int[head.length];cursor=new int[head.length];queue=new int[head.length];
			pathVertex=new int[head.length];pathEdge=new int[head.length];
			balance=new SignedDyadic[vertices];Arrays.fill(balance,SignedDyadic.ZERO);
		}
		int add(int from,int target,SignedDyadic lower,SignedDyadic upper,SignedDyadic initial){
			int edge=edges;addPair(from,target,upper.subtract(initial),initial.subtract(lower));
			balance[from]=balance[from].subtract(initial);balance[target]=balance[target].add(initial);return edge;
		}
		private void addPair(int from,int target,SignedDyadic forward,SignedDyadic reverse){
			if(edges+2>to.length){int capacity=Math.multiplyExact(to.length,2);to=Arrays.copyOf(to,capacity);next=Arrays.copyOf(next,capacity);remaining=Arrays.copyOf(remaining,capacity);}
			to[edges]=target;next[edges]=head[from];remaining[edges]=forward;head[from]=edges++;
			to[edges]=from;next[edges]=head[target];remaining[edges]=reverse;head[target]=edges++;
		}
		void solve(){
			for(int vertex=0;vertex<vertices;vertex++){
				var amount=balance[vertex];
				if(amount.signum()>0)addPair(source,vertex,amount,SignedDyadic.ZERO);
				else if(amount.signum()<0)addPair(vertex,sink,amount.negate(),SignedDyadic.ZERO);
			}
			while(levels()){
				System.arraycopy(head,0,cursor,0,head.length);
				while(augment()) { }
			}
			var missing=SignedDyadic.ZERO;
			for(int edge=head[source];edge>=0;edge=next[edge])missing=missing.add(remaining[edge]);
			if(missing.signum()>0)throw new ConservativeLiquidFlux.CapacityUnavailable(
					"Liquid face shares cannot satisfy actual recipient capacity: unresolved="+missing.toDouble());
		}
		private boolean levels(){
			Arrays.fill(level,-1);int first=0,last=0;queue[last++]=source;level[source]=0;
			while(first<last){int vertex=queue[first++];for(int edge=head[vertex];edge>=0;edge=next[edge])
				if(remaining[edge].signum()>0&&level[to[edge]]<0){level[to[edge]]=level[vertex]+1;queue[last++]=to[edge];}
			}
			return level[sink]>=0;
		}
		private boolean augment(){
			int depth=0;pathVertex[0]=source;
			while(depth>=0){
				int vertex=pathVertex[depth];
				if(vertex==sink){
					SignedDyadic amount=null;
					for(int i=0;i<depth;i++){var available=remaining[pathEdge[i]];if(amount==null||available.compareTo(amount)<0)amount=available;}
					for(int i=0;i<depth;i++){int edge=pathEdge[i];remaining[edge]=remaining[edge].subtract(amount);remaining[edge^1]=remaining[edge^1].add(amount);}
					return true;
				}
				int edge=cursor[vertex];
				while(edge>=0&&(remaining[edge].signum()<=0||level[to[edge]]!=level[vertex]+1))edge=next[edge];
				cursor[vertex]=edge;
				if(edge<0){level[vertex]=-1;if(depth==0)return false;depth--;cursor[pathVertex[depth]]=next[pathEdge[depth]];}
				else{pathEdge[depth]=edge;pathVertex[++depth]=to[edge];}
			}
			return false;
		}
		SignedDyadic amount(int edge){return remaining[edge^1];}
	}
}
