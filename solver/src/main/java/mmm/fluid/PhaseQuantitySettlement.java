package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntConsumer;

/** One quantity allocation followed by lazy residual repair of actual capacity violations. */
final class PhaseQuantitySettlement {
    static final int UNIT_EXPONENT = -15;
    private PhaseQuantitySettlement() { }

    /** Actual preferences and lazily visited capacity alternatives share the same primitive arena. */
    static final class RouteBuffer {
        private List<LiquidCellInventory.Stock> origins=List.of();
        private int size;
        private int[] source=new int[32],target=new int[32],support=new int[32],face=new int[32],next=new int[32];
        private double[] preferred=new double[32];
        private int[] head=new int[0],retained=new int[0];
        private double[] preferredTotal=new double[0];

        void reset(List<LiquidCellInventory.Stock> stocks){
            origins=stocks;size=0;
            if(head.length<stocks.size()){head=new int[stocks.size()];retained=new int[stocks.size()];preferredTotal=new double[stocks.size()];}
            Arrays.fill(head,0,stocks.size(),-1);Arrays.fill(retained,0,stocks.size(),-1);Arrays.fill(preferredTotal,0,stocks.size(),0);
        }
        void add(int ordinal,int sharedFace,int cell,int supportMask,double preference){
            if(size==source.length){int n=2*size;source=Arrays.copyOf(source,n);target=Arrays.copyOf(target,n);support=Arrays.copyOf(support,n);face=Arrays.copyOf(face,n);next=Arrays.copyOf(next,n);preferred=Arrays.copyOf(preferred,n);}
            source[size]=ordinal;target[size]=8*cell+Integer.numberOfTrailingZeros(supportMask);support[size]=supportMask;face[size]=sharedFace;
            preferred[size]=preference;preferredTotal[ordinal]+=preference;next[size]=head[ordinal];head[ordinal]=size;
            if(sharedFace<0)retained[ordinal]=size;
            size++;
        }
        void alternative(int ordinal,int sharedFace,int cell,int supportMask){
            int slot=8*cell+Integer.numberOfTrailingZeros(supportMask);
            for(int r=head[ordinal];r>=0;r=next[r])if(face[r]==sharedFace&&target[r]==slot)return;
            add(ordinal,sharedFace,cell,supportMask,0);
        }
        private LiquidCellInventory.TransportRoute route(int r,SignedDyadic volume){
            var key=origins.get(source[r]).key();var to=face[r]<0?key:new LiquidCellInventory.Key(target[r]/8,key.material(),support[r]);
            return new LiquidCellInventory.TransportRoute(face[r],key,to,volume);
        }
    }

    static List<LiquidCellInventory.TransportRoute> settle(RouteBuffer raw,SignedDyadic[] upper,
            int[] receiverGroup,SignedDyadic[] groupUpper,IntConsumer alternatives){
        int sources=raw.origins.size(),initialRoutes=raw.size,groups=groupUpper==null?0:groupUpper.length;
        long[] supply=new long[sources],amount=new long[initialRoutes],load=new long[upper.length];
        var tails=new SignedDyadic[sources];var localTails=new SignedDyadic[upper.length];
        var groupTails=new SignedDyadic[groups];long[] groupLoad=new long[groups],groupHigh=new long[groups];
        int[] touched=new int[Math.max(1,sources+initialRoutes)];int touchedCount=0;
        boolean[] present=new boolean[upper.length];
        for(int s=0;s<sources;s++){
            var stock=raw.origins.get(s);var volume=stock.volume();supply[s]=floor(volume);
            if(!volume.fitsLongUnits(UNIT_EXPONENT)){
                tails[s]=volume.subtract(SignedDyadic.of(supply[s],UNIT_EXPONENT));
                int slot=slot(stock.key());localTails[slot]=add(localTails[slot],tails[s]);
                if(!present[slot]){present[slot]=true;touched[touchedCount++]=slot;}
                int group=receiverGroup==null?-1:receiverGroup[slot];
                if(group>=0)groupTails[group]=add(groupTails[group],tails[s]);
            }
            long remaining=supply[s];double denominator=raw.preferredTotal[s];
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(r!=raw.retained[s]){
                long wanted=(long)Math.floor(supply[s]*(raw.preferred[r]/denominator));
                amount[r]=Math.min(remaining,Math.max(0,wanted));remaining-=amount[r];
            }
            amount[raw.retained[s]]=remaining;
        }
        for(int r=0;r<initialRoutes;r++)if(amount[r]>0){
            int slot=raw.target[r];load[slot]+=amount[r];
            if(!present[slot]){present[slot]=true;touched[touchedCount++]=slot;}
            int group=receiverGroup==null?-1:receiverGroup[slot];if(group>=0)groupLoad[group]+=amount[r];
        }
        boolean adjust=false;
        for(int i=0;i<touchedCount;i++){
            int slot=touched[i];var capacity=localTails[slot]==null?upper[slot]:upper[slot].subtract(localTails[slot]);
            if(capacity.signum()<0)return settleTails(raw,upper,receiverGroup,groupUpper,alternatives);
            adjust|=load[slot]>floor(capacity);
        }
        for(int g=0;g<groups;g++){
            var capacity=groupTails[g]==null?groupUpper[g]:groupUpper[g].subtract(groupTails[g]);
            if(capacity.signum()<0)return settleTails(raw,upper,receiverGroup,groupUpper,alternatives);
            groupHigh[g]=floor(capacity);adjust|=groupLoad[g]>groupHigh[g];
        }
        if(adjust){
            var repair=new ResidualRepair(raw,supply,amount,load,upper,localTails,receiverGroup,groupLoad,groupHigh,alternatives);
            try{repair.solve();}
            catch(ConservativeLiquidFlux.CapacityUnavailable coarsePore){
                // Integer solid endpoints can leave less than one numerical FV
                // word of legal space. Resolve that actual inventory remainder
                // through the existing exact quantity allocation.
                try{return settleTails(raw,upper,receiverGroup,groupUpper,alternatives);}
                catch(ConservativeLiquidFlux.CapacityUnavailable actualPore){actualPore.addSuppressed(coarsePore);throw actualPore;}
            }
            amount=new long[raw.size];
            for(int r=0;r<raw.size;r++)amount[r]=repair.amount(r);
        }
        var result=new ArrayList<LiquidCellInventory.TransportRoute>(raw.size);
        for(int r=0;r<raw.size;r++)if(amount[r]>0||r==raw.retained[raw.source[r]]&&tails[raw.source[r]]!=null){
            int s=raw.source[r];SignedDyadic volume;
            if(r==raw.retained[s]&&amount[r]==supply[s])volume=raw.origins.get(s).volume();
            else{volume=SignedDyadic.of(amount[r],UNIT_EXPONENT);if(r==raw.retained[s]&&tails[s]!=null)volume=volume.add(tails[s]);}
            result.add(raw.route(r,volume));
        }
        return result;
    }

    private static SignedDyadic add(SignedDyadic first,SignedDyadic second){return first==null?second:first.add(second);}
    private static int slot(LiquidCellInventory.Key key){return 8*key.cell()+Integer.numberOfTrailingZeros(key.supportMask());}
    private static long floor(SignedDyadic value){return value.fitsLongUnits(UNIT_EXPONENT)?value.longUnitsExact(UNIT_EXPONENT)
            :value.significand().shiftRight(UNIT_EXPONENT-value.exponent()).longValueExact();}

    /** Rare subword stock that itself exceeds a local pore keeps the existing exact inventory allocation. */
    private static List<LiquidCellInventory.TransportRoute> settleTails(RouteBuffer raw,SignedDyadic[] upper,
            int[] receiverGroup,SignedDyadic[] groupUpper,IntConsumer alternatives){
        for(int s=0;s<raw.origins.size();s++)alternatives.accept(s);
        var preferred=new ArrayList<LiquidCellInventory.TransportRoute>(raw.size);
        for(int r=0;r<raw.size;r++)preferred.add(raw.route(r,SignedDyadic.of(raw.preferred[r])));
        var grouped=new HashMap<LiquidCellInventory.Key,List<Integer>>();
        for(int r=0;r<preferred.size();r++)grouped.computeIfAbsent(preferred.get(r).source(),ignored->new ArrayList<>()).add(r);
        var volumes=new SignedDyadic[preferred.size()];
        for(var stock:raw.origins){
            var row=grouped.get(stock.key());var bounds=new SignedDyadic[row.size()];var weights=new double[row.size()];
            for(int i=0;i<row.size();i++){bounds[i]=stock.volume();weights[i]=preferred.get(row.get(i)).volume().toDouble();}
            var portions=LiquidCellInventory.allocateVolumes(stock.volume(),bounds,weights);
            for(int i=0;i<row.size();i++)volumes[row.get(i)]=portions[i];
        }
        var closed=new ArrayList<LiquidCellInventory.TransportRoute>(preferred.size());
        for(int r=0;r<preferred.size();r++){var route=preferred.get(r);closed.add(new LiquidCellInventory.TransportRoute(route.face(),route.source(),route.target(),volumes[r]));}
        var minimum=new SignedDyadic[upper.length];Arrays.fill(minimum,SignedDyadic.ZERO);
        return ExactQuantitySettlement.settle(raw.origins,closed,upper,minimum,null,receiverGroup,groupUpper);
    }

    /** Standard residual paths; zero-preference source neighbours are materialized when BFS reaches them. */
    private static final class ResidualRepair {
        private final RouteBuffer raw;
        private final long[] supply,load;
        private final SignedDyadic[] upper,tails;
        private final int[] receiverGroup,receiverVertex;
        private final IntConsumer alternatives;
        private final boolean[] expanded;
        private final int firstSource=2,firstGroup,pool;
        private int vertices,edges;
        private int[] head,level,cursor,queue,pathVertex,pathEdge,receiverSlots;
        private long[] balance;
        private int[] to=new int[64],next=new int[64],routeEdge;
        private long[] remaining=new long[64];
        private int receivers;
        private boolean initialized;

        ResidualRepair(RouteBuffer raw,long[] supply,long[] amount,long[] load,SignedDyadic[] upper,SignedDyadic[] tails,
                int[] receiverGroup,long[] groupLoad,long[] groupHigh,IntConsumer alternatives){
            this.raw=raw;this.supply=supply;this.load=load;this.upper=upper;this.tails=tails;this.receiverGroup=receiverGroup;this.alternatives=alternatives;
            expanded=new boolean[supply.length];receiverVertex=new int[upper.length];
            firstGroup=firstSource+supply.length;pool=firstGroup+groupHigh.length;vertices=pool+1;
            int capacity=Math.max(16,vertices+2*supply.length);
            head=new int[capacity];Arrays.fill(head,-1);level=new int[capacity];cursor=new int[capacity];queue=new int[capacity];
            pathVertex=new int[capacity];pathEdge=new int[capacity];balance=new long[capacity];receiverSlots=new int[Math.max(16,2*supply.length)];
            routeEdge=new int[Math.max(32,raw.size)];
            for(int s=0;s<supply.length;s++){balance[firstSource+s]=supply[s];balance[pool]-=supply[s];}
            for(int r=0;r<amount.length;r++)routeEdge[r]=add(firstSource+raw.source[r],receiver(raw.target[r]),supply[raw.source[r]],amount[r]);
            for(int g=0;g<groupHigh.length;g++)add(firstGroup+g,pool,groupHigh[g],Math.min(groupLoad[g],groupHigh[g]));
            for(int i=0;i<receivers;i++)capacityEdge(receiverSlots[i],Math.min(load[receiverSlots[i]],capacity(receiverSlots[i])));
            initialized=true;
            for(int v=2;v<vertices;v++){
                if(balance[v]>0)pair(0,v,balance[v],0);
                else if(balance[v]<0)pair(v,1,-balance[v],0);
            }
        }
        private long capacity(int slot){return floor(tails[slot]==null?upper[slot]:upper[slot].subtract(tails[slot]));}
        private int receiver(int slot){
            if(receiverVertex[slot]!=0)return receiverVertex[slot];
            if(vertices==head.length){int old=head.length,n=2*old;head=Arrays.copyOf(head,n);Arrays.fill(head,old,n,-1);level=Arrays.copyOf(level,n);cursor=Arrays.copyOf(cursor,n);queue=Arrays.copyOf(queue,n);pathVertex=Arrays.copyOf(pathVertex,n);pathEdge=Arrays.copyOf(pathEdge,n);balance=Arrays.copyOf(balance,n);}
            int vertex=vertices++;receiverVertex[slot]=vertex;level[vertex]=-1;
            if(receivers==receiverSlots.length)receiverSlots=Arrays.copyOf(receiverSlots,2*receivers);
            receiverSlots[receivers++]=slot;
            if(initialized)capacityEdge(slot,0);
            return vertex;
        }
        private void capacityEdge(int slot,long initial){
            int group=receiverGroup==null?-1:receiverGroup[slot];add(receiverVertex[slot],group<0?pool:firstGroup+group,capacity(slot),initial);
        }
        private int add(int from,int target,long upper,long initial){
            int edge=edges;pair(from,target,upper-initial,initial);balance[from]-=initial;balance[target]+=initial;return edge;
        }
        private void pair(int from,int target,long forward,long reverse){
            if(edges+2>to.length){int n=2*to.length;to=Arrays.copyOf(to,n);next=Arrays.copyOf(next,n);remaining=Arrays.copyOf(remaining,n);}
            to[edges]=target;next[edges]=head[from];remaining[edges]=forward;head[from]=edges++;
            to[edges]=from;next[edges]=head[target];remaining[edges]=reverse;head[target]=edges++;
        }
        private void expand(int vertex){
            int source=vertex-firstSource;if(source<0||source>=supply.length||expanded[source])return;
            expanded[source]=true;int first=raw.size;alternatives.accept(source);
            if(routeEdge.length<raw.size)routeEdge=Arrays.copyOf(routeEdge,Math.max(raw.size,2*routeEdge.length));
            for(int r=first;r<raw.size;r++)routeEdge[r]=add(vertex,receiver(raw.target[r]),supply[source],0);
        }
        void solve(){
            while(levels()){
                System.arraycopy(head,0,cursor,0,vertices);
                while(augment()) { }
            }
            for(int e=head[0];e>=0;e=next[e])if(remaining[e]>0)
                throw new ConservativeLiquidFlux.CapacityUnavailable("Bounded liquid volume allocation has insufficient recipient capacity: residual="+Math.scalb((double)remaining[e],UNIT_EXPONENT)+", vertex="+to[e]);
        }
        private boolean levels(){
            Arrays.fill(level,0,vertices,-1);int first=0,last=0;queue[last++]=0;level[0]=0;
            while(first<last){
                int vertex=queue[first++];expand(vertex);
                for(int e=head[vertex];e>=0;e=next[e])if(remaining[e]>0&&level[to[e]]<0){
                    int target=to[e];level[target]=level[vertex]+1;
                    // Stop at the nearest feasible endpoint. Walking through the
                    // common pool after reaching the sink would activate remote
                    // balanced receivers and expand their unrelated source rows.
                    if(target==1)return true;
                    queue[last++]=target;
                }
            }
            return false;
        }
        private boolean augment(){
            int depth=0;pathVertex[0]=0;
            while(depth>=0){
                int vertex=pathVertex[depth];
                if(vertex==1){
                    long amount=Long.MAX_VALUE;for(int i=0;i<depth;i++)amount=Math.min(amount,remaining[pathEdge[i]]);
                    for(int i=0;i<depth;i++){int edge=pathEdge[i];remaining[edge]-=amount;remaining[edge^1]+=amount;}return true;
                }
                int edge=cursor[vertex];while(edge>=0&&(remaining[edge]<=0||level[to[edge]]!=level[vertex]+1))edge=next[edge];cursor[vertex]=edge;
                if(edge<0){level[vertex]=-1;if(depth==0)return false;depth--;cursor[pathVertex[depth]]=next[pathEdge[depth]];}
                else{pathEdge[depth]=edge;pathVertex[++depth]=to[edge];}
            }
            return false;
        }
        long amount(int route){return remaining[routeEdge[route]^1];}
    }
}
