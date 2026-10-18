package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Machine-word volume allocation; unresolved inventory tails remain exact in their source cell. */
final class PhaseQuantitySettlement {
	static final int UNIT_EXPONENT = -32;

	private PhaseQuantitySettlement() { }

	/** Numerical face preferences stay primitive until the final nonzero inventory shares. */
    static final class RouteBuffer {
        private List<LiquidCellInventory.Stock> origins=List.of();
        private int size,capacityCount;
        private int[] source=new int[32],target=new int[32],support=new int[32],face=new int[32],next=new int[32];
        private double[] preferred=new double[32],maximum=new double[32];
        private int[] head=new int[0],retained=new int[0],capacitySlots;
        private double[] preferredTotal=new double[0];

        void reset(List<LiquidCellInventory.Stock> stocks,int[] slots,int count){
            origins=stocks;size=0;capacitySlots=slots;capacityCount=count;
            if(head.length<stocks.size()){head=new int[stocks.size()];retained=new int[stocks.size()];preferredTotal=new double[stocks.size()];}
            Arrays.fill(head,0,stocks.size(),-1);Arrays.fill(retained,0,stocks.size(),-1);Arrays.fill(preferredTotal,0,stocks.size(),0);
        }
        void add(int ordinal,int sharedFace,int cell,int supportMask,double preference,double upper){
            if(size==source.length){int n=2*size;source=Arrays.copyOf(source,n);target=Arrays.copyOf(target,n);support=Arrays.copyOf(support,n);face=Arrays.copyOf(face,n);next=Arrays.copyOf(next,n);preferred=Arrays.copyOf(preferred,n);maximum=Arrays.copyOf(maximum,n);}
            source[size]=ordinal;target[size]=8*cell+Integer.numberOfTrailingZeros(supportMask);support[size]=supportMask;face[size]=sharedFace;
            preferred[size]=preference;maximum[size]=upper;preferredTotal[ordinal]+=preference;next[size]=head[ordinal];head[ordinal]=size;
            if(sharedFace<0)retained[ordinal]=size;
            size++;
        }
        private LiquidCellInventory.TransportRoute route(int r,SignedDyadic volume){
            var key=origins.get(source[r]).key();var to=face[r]<0?key:new LiquidCellInventory.Key(target[r]/8,key.material(),support[r]);
            return new LiquidCellInventory.TransportRoute(face[r],key,to,volume);
        }
        private List<LiquidCellInventory.TransportRoute> preferredRoutes(){
            var result=new ArrayList<LiquidCellInventory.TransportRoute>(size);
            for(int r=0;r<size;r++)result.add(route(r,SignedDyadic.of(preferred[r])));
            return result;
        }
    }

    static List<LiquidCellInventory.TransportRoute> settle(List<LiquidCellInventory.Stock> origins,
            List<LiquidCellInventory.TransportRoute> preferred,SignedDyadic[] upper,SignedDyadic[] minimum){
        return settle(origins,preferred,upper,minimum,null,null,null);
    }
    static List<LiquidCellInventory.TransportRoute> settle(List<LiquidCellInventory.Stock> origins,
            List<LiquidCellInventory.TransportRoute> preferred,SignedDyadic[] upper,SignedDyadic[] minimum,double[] maximum){
        return settle(origins,preferred,upper,minimum,maximum,null,null);
    }
    static List<LiquidCellInventory.TransportRoute> settle(List<LiquidCellInventory.Stock> origins,
            List<LiquidCellInventory.TransportRoute> preferred,SignedDyadic[] upper,SignedDyadic[] minimum,
            double[] maximum,int[] receiverGroup,SignedDyadic[] groupUpper){
        var raw=new RouteBuffer();raw.reset(origins,null,upper.length);
        var ordinals=new HashMap<LiquidCellInventory.Key,Integer>();for(int i=0;i<origins.size();i++)ordinals.put(origins.get(i).key(),i);
        for(int r=0;r<preferred.size();r++){var route=preferred.get(r);int source=ordinals.get(route.source());raw.add(source,route.face(),route.target().cell(),route.target().supportMask(),route.volume().toDouble(),maximum!=null&&r<maximum.length?maximum[r]:origins.get(source).volumeFraction());}
        return settle(raw,upper,minimum,receiverGroup,groupUpper);
    }

    static List<LiquidCellInventory.TransportRoute> settle(RouteBuffer raw,SignedDyadic[] upper,
            SignedDyadic[] minimum,int[] receiverGroup,SignedDyadic[] groupUpper){
        var origins=raw.origins;int sources=origins.size();
        for(int s=0;s<sources;s++)if(raw.retained[s]<0){var stock=origins.get(s);raw.add(s,-1,stock.key().cell(),stock.key().supportMask(),0,stock.volumeFraction());}
        int n=raw.size;int[] source=raw.source,target=raw.target;
        long[] amount=new long[n],limit=new long[n],supply=new long[sources],load=new long[upper.length];
        var tails=new SignedDyadic[sources];var localTails=new SignedDyadic[upper.length];Arrays.fill(localTails,SignedDyadic.ZERO);
        for(int s=0;s<sources;s++){
            var volume=origins.get(s).volume();supply[s]=floor(volume);
            tails[s]=volume.fitsLongUnits(UNIT_EXPONENT)?SignedDyadic.ZERO:volume.subtract(SignedDyadic.of(supply[s],UNIT_EXPONENT));
            if(tails[s].signum()!=0){int slot=slot(origins.get(s).key());localTails[slot]=localTails[slot].add(tails[s]);}
        }
        for(int r=0;r<n;r++)limit[r]=Math.min(supply[source[r]],Math.max(0,(long)Math.ceil(Math.scalb(raw.maximum[r],-UNIT_EXPONENT))));
        for(int s=0;s<sources;s++){
            long remaining=supply[s];double denominator=raw.preferredTotal[s];
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(r!=raw.retained[s]){
                long wanted=(long)Math.floor(supply[s]*(raw.preferred[r]/denominator));
                amount[r]=Math.min(remaining,Math.min(limit[r],Math.max(0,wanted)));remaining-=amount[r];
            }
            amount[raw.retained[s]]=remaining;
        }
        for(int r=0;r<n;r++)load[target[r]]+=amount[r];
        long[] high=new long[upper.length],low=new long[upper.length];boolean adjust=false;
        for(int i=0;i<raw.capacityCount;i++){
            int p=raw.capacitySlots==null?i:raw.capacitySlots[i];if(upper[p]==null)continue;
            var available=localTails[p].signum()==0?upper[p]:upper[p].subtract(localTails[p]);
            if(available.signum()<0)return settleTails(origins,raw.preferredRoutes(),upper,minimum,raw.maximum,receiverGroup,groupUpper);
            high[p]=floor(available);
            var required=minimum[p]==null?SignedDyadic.ZERO:localTails[p].signum()==0?minimum[p]:minimum[p].subtract(localTails[p]);
            if(required.signum()>0){low[p]=floor(required);if(!required.fitsLongUnits(UNIT_EXPONENT))low[p]++;}
            if(low[p]>high[p])return settleTails(origins,raw.preferredRoutes(),upper,minimum,raw.maximum,receiverGroup,groupUpper);
            adjust|=load[p]<low[p]||load[p]>high[p];
        }
        int groups=groupUpper==null?0:groupUpper.length;
        long[] groupLoad=new long[groups],groupHigh=new long[groups];boolean[] groupUsed=new boolean[groups];
        var groupTails=new SignedDyadic[groups];Arrays.fill(groupTails,SignedDyadic.ZERO);
        if(receiverGroup!=null)for(int i=0;i<raw.capacityCount;i++){
            int p=raw.capacitySlots==null?i:raw.capacitySlots[i],group=receiverGroup[p];if(group<0||upper[p]==null)continue;
            groupUsed[group]=true;groupLoad[group]+=load[p];if(localTails[p].signum()!=0)groupTails[group]=groupTails[group].add(localTails[p]);
        }
        for(int group=0;group<groups;group++)if(groupUsed[group]){
            var available=groupTails[group].signum()==0?groupUpper[group]:groupUpper[group].subtract(groupTails[group]);
            if(available.signum()<0)return settleTails(origins,raw.preferredRoutes(),upper,minimum,raw.maximum,receiverGroup,groupUpper);
            groupHigh[group]=floor(available);adjust|=groupLoad[group]>groupHigh[group];
        }
        if(adjust){
            if(Boolean.getBoolean("mmm.liquid.trace")){
                for(int i=0;i<raw.capacityCount;i++){int p=raw.capacitySlots==null?i:raw.capacitySlots[i];if(load[p]<low[p]||load[p]>high[p])System.out.printf("LIQ_CAP cell=%d slot=%d load=%.12g low=%.12g high=%.12g%n",p/8,p&7,Math.scalb((double)load[p],UNIT_EXPONENT),Math.scalb((double)low[p],UNIT_EXPONENT),Math.scalb((double)high[p],UNIT_EXPONENT));}
                for(int g=0;g<groups;g++)System.out.printf("LIQ_GROUP g=%d load=%.12g upper=%.12g%n",g,Math.scalb((double)groupLoad[g],UNIT_EXPONENT),Math.scalb((double)groupHigh[g],UNIT_EXPONENT));
            }
            int[] receiver=new int[upper.length];Arrays.fill(receiver,-1);int receivers=0;
            for(int r=0;r<n;r++)if(receiver[target[r]]<0)receiver[target[r]]=sources+receivers++;
            for(int i=0;i<raw.capacityCount;i++){int p=raw.capacitySlots==null?i:raw.capacitySlots[i];if(low[p]>0&&receiver[p]<0)receiver[p]=sources+receivers++;}
            int firstGroup=sources+receivers,pool=firstGroup+groups;
            var graph=new VolumeFlow(pool+1,2*(sources+n+receivers+groups+pool+1));
            for(int s=0;s<sources;s++)graph.add(pool,s,supply[s],supply[s],supply[s]);
            int[] edges=new int[n];for(int r=0;r<n;r++)edges[r]=graph.add(source[r],receiver[target[r]],0,limit[r],amount[r]);
            for(int i=0;i<raw.capacityCount;i++){
                int p=raw.capacitySlots==null?i:raw.capacitySlots[i];if(receiver[p]<0)continue;int group=receiverGroup==null?-1:receiverGroup[p];
                graph.add(receiver[p],group<0?pool:firstGroup+group,low[p],high[p],Math.clamp(load[p],low[p],high[p]));
            }
            for(int group=0;group<groups;group++)if(groupUsed[group])graph.add(firstGroup+group,pool,0,groupHigh[group],Math.min(groupLoad[group],groupHigh[group]));
            graph.solve();for(int r=0;r<n;r++)amount[r]=graph.amount(edges[r]);
        }
        var result=new ArrayList<LiquidCellInventory.TransportRoute>(n);
        for(int r=0;r<n;r++)if(amount[r]>0||r==raw.retained[source[r]]&&tails[source[r]].signum()>0){
            int s=source[r];SignedDyadic volume;
            if(r==raw.retained[s]&&amount[r]==supply[s])volume=origins.get(s).volume();
            else {volume=SignedDyadic.of(amount[r],UNIT_EXPONENT);if(r==raw.retained[s]&&tails[s].signum()!=0)volume=volume.add(tails[s]);}
            result.add(raw.route(r,volume));
        }
        return result;
    }

	private static int slot(LiquidCellInventory.Key key) {
		return 8 * key.cell() + Integer.numberOfTrailingZeros(key.supportMask());
	}

	private static long floor(SignedDyadic value) {
		return value.fitsLongUnits(UNIT_EXPONENT) ? value.longUnitsExact(UNIT_EXPONENT)
				: value.significand().shiftRight(UNIT_EXPONENT - value.exponent()).longValueExact();
	}

	private static List<LiquidCellInventory.TransportRoute> settleTails(List<LiquidCellInventory.Stock> origins,
			List<LiquidCellInventory.TransportRoute> preferred, SignedDyadic[] upper, SignedDyadic[] minimum,
			double[] routeMaximum, int[] receiverGroup, SignedDyadic[] groupUpper) {
		var grouped = new HashMap<LiquidCellInventory.Key, List<Integer>>();
		for (int r = 0; r < preferred.size(); r++) grouped.computeIfAbsent(preferred.get(r).source(), ignored -> new ArrayList<>()).add(r);
		var volumes = new SignedDyadic[preferred.size()];
		for (var stock : origins) {
			var row = grouped.get(stock.key());
			var bounds = new SignedDyadic[row.size()];
			var weights = new double[row.size()];
			for (int i = 0; i < row.size(); i++) {
				int r = row.get(i);
				bounds[i] = stock.volume();
				if (routeMaximum != null && r < routeMaximum.length) {
					var maximum = SignedDyadic.of(Math.max(0, routeMaximum[r]));
					if (maximum.compareTo(bounds[i]) < 0) bounds[i] = maximum;
				}
				weights[i] = preferred.get(r).volume().toDouble();
			}
			var portions = LiquidCellInventory.allocateVolumes(stock.volume(), bounds, weights);
			for (int i = 0; i < row.size(); i++) volumes[row.get(i)] = portions[i];
		}
		var closed = new ArrayList<LiquidCellInventory.TransportRoute>(preferred.size());
		for (int r = 0; r < preferred.size(); r++) {
			var route = preferred.get(r);
			closed.add(new LiquidCellInventory.TransportRoute(route.face(), route.source(), route.target(), volumes[r]));
		}
		return ExactQuantitySettlement.settle(origins, closed, upper, minimum, routeMaximum, receiverGroup, groupUpper);
	}

	/** Bounded integer circulation; iterative volume arithmetic stays in machine words. */
	private static final class VolumeFlow {
		private final int vertices, source, sink;
		private final int[] head, level, cursor, queue, pathVertex, pathEdge;
		private final long[] balance;
		private int[] to = new int[32], next = new int[32];
		private long[] remaining = new long[32];
		private int edges;

		VolumeFlow(int count,int expectedEdges) {
			vertices = count;
			source = count;
			sink = count + 1;
			head = new int[count + 2];
			Arrays.fill(head, -1);
			level = new int[head.length];
			cursor = new int[head.length];
			queue = new int[head.length];
			pathVertex = new int[head.length];
			pathEdge = new int[head.length];
			balance = new long[count];
            to=new int[Math.max(32,expectedEdges)];next=new int[to.length];remaining=new long[to.length];
		}

		int add(int from, int target, long lower, long upper, long initial) {
			int edge = edges;
			pair(from, target, upper - initial, initial - lower);
			balance[from] -= initial;
			balance[target] += initial;
			return edge;
		}

		private void pair(int from, int target, long forward, long reverse) {
			if (edges + 2 > to.length) {
				int size = to.length * 2;
				to = Arrays.copyOf(to, size);
				next = Arrays.copyOf(next, size);
				remaining = Arrays.copyOf(remaining, size);
			}
			to[edges] = target;
			next[edges] = head[from];
			remaining[edges] = forward;
			head[from] = edges++;
			to[edges] = from;
			next[edges] = head[target];
			remaining[edges] = reverse;
			head[target] = edges++;
		}

		void solve() {
			for (int v = 0; v < vertices; v++) {
				if (balance[v] > 0) pair(source, v, balance[v], 0);
				else if (balance[v] < 0) pair(v, sink, -balance[v], 0);
			}
			while (levels()) {
				System.arraycopy(head, 0, cursor, 0, head.length);
				while (augment()) { }
			}
			for (int e = head[source]; e >= 0; e = next[e]) {
				if (remaining[e] > 0) throw new ConservativeLiquidFlux.CapacityUnavailable(
						"Bounded liquid volume allocation has insufficient recipient capacity: residual="+Math.scalb((double)remaining[e],UNIT_EXPONENT)+", vertex="+to[e]);
			}
		}

		private boolean levels() {
			Arrays.fill(level, -1);
			int first = 0, last = 0;
			queue[last++] = source;
			level[source] = 0;
			while (first < last) {
				int v = queue[first++];
				for (int e = head[v]; e >= 0; e = next[e]) {
					if (remaining[e] > 0 && level[to[e]] < 0) {
						level[to[e]] = level[v] + 1;
						queue[last++] = to[e];
					}
				}
			}
			return level[sink] >= 0;
		}

		private boolean augment() {
			int depth = 0;
			pathVertex[0] = source;
			while (depth >= 0) {
				int v = pathVertex[depth];
				if (v == sink) {
					long amount = Long.MAX_VALUE;
					for (int i = 0; i < depth; i++) amount = Math.min(amount, remaining[pathEdge[i]]);
					for (int i = 0; i < depth; i++) {
						int e = pathEdge[i];
						remaining[e] -= amount;
						remaining[e ^ 1] += amount;
					}
					return true;
				}
				int e = cursor[v];
				while (e >= 0 && (remaining[e] <= 0 || level[to[e]] != level[v] + 1)) e = next[e];
				cursor[v] = e;
				if (e < 0) {
					level[v] = -1;
					if (depth == 0) return false;
					depth--;
					cursor[pathVertex[depth]] = next[pathEdge[depth]];
				} else {
					pathEdge[depth] = e;
					pathVertex[++depth] = to[e];
				}
			}
			return false;
		}

		long amount(int edge) { return remaining[edge ^ 1]; }
	}
}
