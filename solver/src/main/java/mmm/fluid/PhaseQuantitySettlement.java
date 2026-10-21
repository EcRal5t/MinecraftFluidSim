package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.LiquidTransportRoutes;
import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/** Shared-face finite-volume increments; only actual outflow enters material accounting. */
final class PhaseQuantitySettlement {
    private PhaseQuantitySettlement() { }

    /** Fixed supports own face fluxes; material stocks partition only accepted outflow. */
    static final class RouteBuffer {
        private List<LiquidCellInventory.Stock> stocks=List.of();
        private int sources,size;
        private int[] sourceAt=new int[0],firstStock=new int[0],nextStock=new int[0];
        private SignedDyadic[] volume=new SignedDyadic[0];
        private double[] numericalVolume=new double[0];
        private boolean[] alternativesPrepared=new boolean[0];
        private int[] source=new int[32],target=new int[32],support=new int[32],face=new int[32],next=new int[32];
        private double[] preferred=new double[32];
        private int[] head=new int[0];
        private double[] preferredTotal=new double[0];
        private FluidMaterialRegistry materialRegistry;
        private int sizeX,sizeY;
        private double gravity;

        void materialLayers(FluidMaterialRegistry registry,int cellsX,int cellsY,double acceleration){
            materialRegistry=registry;sizeX=cellsX;sizeY=cellsY;gravity=acceleration;
        }
        private int verticalDirection(int route){
            int from=key(source[route]).cell()/sizeX%sizeY,to=target[route]/8/sizeX%sizeY;
            return Integer.compare(to,from)*(gravity>0?1:-1);
        }
        void reset(List<LiquidCellInventory.Stock> values,int slots){
            stocks=values;sources=size=0;
            if(sourceAt.length<slots)sourceAt=new int[slots];
            Arrays.fill(sourceAt,0,slots,-1);
            if(firstStock.length<values.size()){
                int n=values.size();firstStock=new int[n];nextStock=new int[n];volume=new SignedDyadic[n];
                numericalVolume=new double[n];alternativesPrepared=new boolean[n];head=new int[n];preferredTotal=new double[n];
            }
            for(int i=0;i<values.size();i++){
                var stock=values.get(i);int slot=slot(stock.key()),s=sourceAt[slot];
                if(s<0){
                    sourceAt[slot]=s=sources++;firstStock[s]=-1;volume[s]=SignedDyadic.ZERO;
                    numericalVolume[s]=Double.NaN;alternativesPrepared[s]=false;head[s]=-1;preferredTotal[s]=0;
                }
                nextStock[i]=firstStock[s];firstStock[s]=i;volume[s]=volume[s].add(stock.volume());
            }
        }
        int sourceCount(){return sources;}
        int sourceFor(int cell,int supportMask){return sourceAt[8*cell+Integer.numberOfTrailingZeros(supportMask)];}
        double outgoing(int source){return preferredTotal[source];}
        LiquidCellInventory.Key key(int s){return stocks.get(firstStock[s]).key();}
        double volume(int s){double value=numericalVolume[s];if(Double.isNaN(value))numericalVolume[s]=value=volume[s].toDouble();return value;}
        void prepareAlternatives(int source,IntConsumer alternatives){
            if(!alternativesPrepared[source]){alternativesPrepared[source]=true;alternatives.accept(source);}
        }
        void add(int ordinal,int sharedFace,int cell,int supportMask,double preference){
            if(sharedFace<0||!(preference>=0&&Double.isFinite(preference)))
                throw new IllegalArgumentException("A liquid increment requires an actual face and finite nonnegative volume");
            if(size==source.length){
                int n=2*size;source=Arrays.copyOf(source,n);target=Arrays.copyOf(target,n);support=Arrays.copyOf(support,n);
                face=Arrays.copyOf(face,n);next=Arrays.copyOf(next,n);preferred=Arrays.copyOf(preferred,n);
            }
            source[size]=ordinal;target[size]=8*cell+Integer.numberOfTrailingZeros(supportMask);support[size]=supportMask;face[size]=sharedFace;
            preferred[size]=preference;preferredTotal[ordinal]+=preference;next[size]=head[ordinal];head[ordinal]=size++;
        }
        void alternative(int ordinal,int sharedFace,int cell,int supportMask){
            int slot=8*cell+Integer.numberOfTrailingZeros(supportMask);
            for(int r=head[ordinal];r>=0;r=next[r])if(face[r]==sharedFace&&target[r]==slot)return;
            add(ordinal,sharedFace,cell,supportMask,0);
        }
        private void appendRoute(LiquidTransportRoutes.Builder result,int r,SignedDyadic amount){
            result.route(face[r],target[r]/8,support[r],amount);
        }
    }

    static LiquidTransportRoutes settle(RouteBuffer raw,SignedDyadic[] upper,SignedDyadic[] lower,
            int[] receiverGroup,SignedDyadic[] groupUpper,IntConsumer alternatives){
        var amount=new SignedDyadic[raw.size];Arrays.fill(amount,SignedDyadic.ZERO);
        var outgoing=new SignedDyadic[raw.sources];
        var load=new SignedDyadic[upper.length];
        int[] occupied=new int[Math.max(16,raw.sources+raw.size)];int count=0;
        // Existing stocks remain in place. Only actual face increments change their endpoint.
        for(int s=0;s<raw.sources;s++){
            int own=slot(raw.key(s));load[own]=raw.volume[s];occupied[count++]=own;
            var remaining=raw.volume[s];
            double scale=raw.preferredTotal[s]>raw.volume(s)?raw.volume(s)/raw.preferredTotal[s]:1;
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(raw.preferred[r]>0){
                double preference=raw.preferred[r]*scale;float word=(float)preference;
                if(word>0&&Float.isFinite(word))preference=word;
                amount[r]=min(SignedDyadic.of(preference),remaining);remaining=remaining.subtract(amount[r]);
            }
            outgoing[s]=raw.volume[s].subtract(remaining);
        }
        for(int r=0;r<raw.size;r++)if(amount[r].signum()>0){
            int own=slot(raw.key(raw.source[r])),target=raw.target[r];
            load[own]=load[own].subtract(amount[r]);
            if(load[target]==null){load[target]=SignedDyadic.ZERO;occupied[count++]=target;}
            load[target]=load[target].add(amount[r]);
        }
        int groups=groupUpper==null?0:groupUpper.length;
        var groupLoad=new SignedDyadic[groups];Arrays.fill(groupLoad,SignedDyadic.ZERO);
        boolean adjust=false;
        for(int i=0;i<count;i++){
            int slot=occupied[i];
            adjust|=load[slot].compareTo(upper[slot])>0||load[slot].compareTo(minimum(lower,slot))<0;
            int group=receiverGroup==null?-1:receiverGroup[slot];
            if(group>=0)groupLoad[group]=groupLoad[group].add(load[slot]);
        }
        for(int g=0;g<groups;g++)adjust|=groupLoad[g].compareTo(groupUpper[g])>0;
        if(adjust){
            var balance=new CapacityBalance(raw,amount,outgoing,load,upper,lower,receiverGroup,groupLoad,groupUpper,occupied,count,alternatives);
            balance.solve();amount=balance.amounts();
        }
        return partitionMaterials(raw,amount);
    }

    /**
     * A residual circulation moves actual outflow between receivers. Each donor's
     * unused inventory is an implicit quota, instantiated only when a defect reaches it.
     * Fixed-support capacity, full-liquid rows and finite-gas reserve share this graph.
     */
    private static final class CapacityBalance {
        private final RouteBuffer raw;
        private final SignedDyadic[] initial,outgoing,load,upper,lower,groupHigh;
        private final int[] receiverGroup,receiverVertex,firstIncoming,firstReceiver,nextReceiver,retainedEdge;
        private int[] nextIncoming,routeEdge;
        private final boolean[] expandedSource,expandedReceiver,expandedAggregate;
        private final IntConsumer alternatives;
        private final int firstSource=2,firstGroup,pool;
        private int vertices,edges;
        private int[] head,level,cursor,queue,pathVertex,pathEdge,receiverSlot;
        private int[] to=new int[64],next=new int[64];
        private SignedDyadic[] remaining=new SignedDyadic[64];

        CapacityBalance(RouteBuffer raw,SignedDyadic[] amount,SignedDyadic[] outgoing,SignedDyadic[] load,
                SignedDyadic[] upper,SignedDyadic[] lower,int[] receiverGroup,SignedDyadic[] groupLoad,
                SignedDyadic[] groupHigh,int[] occupied,int count,IntConsumer alternatives){
            this.raw=raw;initial=amount;this.outgoing=outgoing;this.load=load;this.upper=upper;this.lower=lower;
            this.receiverGroup=receiverGroup;this.groupHigh=groupHigh;this.alternatives=alternatives;
            int groups=groupHigh==null?0:groupHigh.length;
            receiverVertex=new int[upper.length];expandedSource=new boolean[raw.sources];
            expandedReceiver=new boolean[upper.length];expandedAggregate=new boolean[groups+1];
            firstIncoming=new int[upper.length];Arrays.fill(firstIncoming,-1);nextIncoming=new int[raw.size];
            firstReceiver=new int[groups+1];Arrays.fill(firstReceiver,-1);nextReceiver=new int[upper.length];Arrays.fill(nextReceiver,-2);
            retainedEdge=new int[raw.sources];Arrays.fill(retainedEdge,-1);
            routeEdge=new int[Math.max(32,raw.size)];Arrays.fill(routeEdge,-1);
            firstGroup=firstSource+raw.sources;pool=firstGroup+groups;vertices=pool+1;
            int capacity=Math.max(16,vertices+2*raw.sources);
            head=new int[capacity];Arrays.fill(head,-1);level=new int[capacity];cursor=new int[capacity];queue=new int[capacity];
            pathVertex=new int[capacity];pathEdge=new int[capacity];receiverSlot=new int[capacity];Arrays.fill(receiverSlot,-1);
            for(int r=0;r<amount.length;r++)if(amount[r].signum()>0){
                int slot=raw.target[r];nextIncoming[r]=firstIncoming[slot];firstIncoming[slot]=r;
            }
            var receiverExcess=new SignedDyadic[groups];Arrays.fill(receiverExcess,SignedDyadic.ZERO);
            var poolNeed=SignedDyadic.ZERO;
            for(int i=0;i<count;i++){
                int slot=occupied[i];listReceiver(slot);var excess=load[slot].subtract(base(slot));
                if(excess.signum()>0)pair(0,receiver(slot),excess,SignedDyadic.ZERO);
                else if(excess.signum()<0)pair(receiver(slot),1,excess.negate(),SignedDyadic.ZERO);
                int group=receiverGroup==null?-1:receiverGroup[slot];
                if(group<0)poolNeed=poolNeed.add(excess);else receiverExcess[group]=receiverExcess[group].add(excess);
            }
            for(int g=0;g<groups;g++){
                var base=min(groupLoad[g],groupHigh[g]);var excess=groupLoad[g].subtract(base);
                pair(firstGroup+g,pool,groupHigh[g].subtract(base),base);
                var balance=excess.subtract(receiverExcess[g]);
                if(balance.signum()>0)pair(0,firstGroup+g,balance,SignedDyadic.ZERO);
                else if(balance.signum()<0)pair(firstGroup+g,1,balance.negate(),SignedDyadic.ZERO);
                poolNeed=poolNeed.add(excess);
            }
            if(poolNeed.signum()>0)pair(pool,1,poolNeed,SignedDyadic.ZERO);
            else if(poolNeed.signum()<0)pair(0,pool,poolNeed.negate(),SignedDyadic.ZERO);
        }
        private SignedDyadic base(int slot){
            var amount=min(value(load[slot]),upper[slot]);var low=minimum(lower,slot);
            return amount.compareTo(low)<0?low:amount;
        }
        private void listReceiver(int slot){
            if(nextReceiver[slot]!=-2)return;
            int group=receiverGroup==null?-1:receiverGroup[slot],aggregate=group<0?firstReceiver.length-1:group;
            nextReceiver[slot]=firstReceiver[aggregate];firstReceiver[aggregate]=slot;
        }
        private int receiver(int slot){
            if(receiverVertex[slot]!=0)return receiverVertex[slot];
            if(vertices==head.length){
                int old=head.length,n=2*old;head=Arrays.copyOf(head,n);Arrays.fill(head,old,n,-1);
                level=Arrays.copyOf(level,n);cursor=Arrays.copyOf(cursor,n);queue=Arrays.copyOf(queue,n);
                pathVertex=Arrays.copyOf(pathVertex,n);pathEdge=Arrays.copyOf(pathEdge,n);
                receiverSlot=Arrays.copyOf(receiverSlot,n);Arrays.fill(receiverSlot,old,n,-1);
            }
            int vertex=vertices++;receiverVertex[slot]=vertex;receiverSlot[vertex]=slot;level[vertex]=-1;
            listReceiver(slot);int group=receiverGroup==null?-1:receiverGroup[slot];var base=base(slot);
            pair(vertex,group<0?pool:firstGroup+group,upper[slot].subtract(base),base.subtract(minimum(lower,slot)));
            return vertex;
        }
        private int pair(int from,int target,SignedDyadic forward,SignedDyadic reverse){
            if(edges+2>to.length){int n=2*to.length;to=Arrays.copyOf(to,n);next=Arrays.copyOf(next,n);remaining=Arrays.copyOf(remaining,n);}
            int edge=edges;
            to[edges]=target;next[edges]=head[from];remaining[edges]=forward;head[from]=edges++;
            to[edges]=from;next[edges]=head[target];remaining[edges]=reverse;head[target]=edges++;
            return edge;
        }
        private void retained(int source){
            if(retainedEdge[source]>=0)return;
            retainedEdge[source]=pair(firstSource+source,receiver(slot(raw.key(source))),outgoing[source],raw.volume[source].subtract(outgoing[source]));
        }
        private void route(int r){
            if(routeEdge[r]>=0)return;
            routeEdge[r]=pair(firstSource+raw.source[r],receiver(raw.target[r]),null,r<initial.length?initial[r]:SignedDyadic.ZERO);
        }
        private void expand(int vertex){
            int source=vertex-firstSource;
            if(source>=0&&source<raw.sources){
                if(expandedSource[source])return;expandedSource[source]=true;
                retained(source);raw.prepareAlternatives(source,alternatives);
                if(routeEdge.length<raw.size){
                    int old=routeEdge.length;routeEdge=Arrays.copyOf(routeEdge,Math.max(raw.size,2*old));Arrays.fill(routeEdge,old,routeEdge.length,-1);
                }
                for(int r=raw.head[source];r>=0;r=raw.next[r])route(r);
                return;
            }
            int slot=receiverSlot[vertex];
            if(slot>=0){
                if(expandedReceiver[slot])return;expandedReceiver[slot]=true;
                int own=raw.sourceAt[slot];if(own>=0)retained(own);
                for(int r=firstIncoming[slot];r>=0;r=nextIncoming[r])route(r);
                return;
            }
            if(vertex<firstGroup||vertex>pool)return;
            int aggregate=vertex-firstGroup;if(expandedAggregate[aggregate])return;expandedAggregate[aggregate]=true;
            for(int r=firstReceiver[aggregate];r>=0;r=nextReceiver[r])
                if(receiverVertex[r]==0&&base(r).compareTo(minimum(lower,r))>0)receiver(r);
        }
        private boolean positive(int edge){return remaining[edge]==null||remaining[edge].signum()>0;}
        void solve(){
            while(levels()){
                System.arraycopy(head,0,cursor,0,vertices);
                while(augment()) { }
            }
            for(int e=head[0];e>=0;e=next[e])if(positive(e))
                throw new ConservativeLiquidFlux.CapacityUnavailable("Liquid inventory has insufficient reachable pore capacity: residual="
                        +remaining[e].toDouble()+", "+location(to[e])+", sources="+raw.sources+", routes="+raw.size);
        }
        private String location(int vertex){
            int source=vertex-firstSource;
            if(source>=0&&source<raw.sources){var key=raw.key(source);return "sourceCell="+key.cell()+", supportMask="+key.supportMask();}
            int slot=receiverSlot[vertex];if(slot>=0)return "receiverCell="+(slot/8)+", component="+(slot%8);
            return vertex>=firstGroup&&vertex<pool?"gasCapacityGroup="+(vertex-firstGroup):"capacityPool";
        }
        private boolean levels(){
            Arrays.fill(level,0,vertices,-1);int first=0,last=0;queue[last++]=0;level[0]=0;
            while(first<last&&(level[1]<0||level[queue[first]]<level[1])){
                int vertex=queue[first++];boolean endpoint=false;
                for(int e=head[vertex];e>=0;e=next[e])if(to[e]==1&&positive(e)){level[1]=level[vertex]+1;endpoint=true;break;}
                if(endpoint)continue;
                expand(vertex);
                for(int e=head[vertex];e>=0;e=next[e])if(positive(e)&&level[to[e]]<0){
                    int target=to[e];level[target]=level[vertex]+1;if(target!=1)queue[last++]=target;
                }
            }
            return level[1]>=0;
        }
        private boolean augment(){
            int depth=0;pathVertex[0]=0;
            while(depth>=0){
                int vertex=pathVertex[depth];
                if(vertex==1){augmentPath(depth);return true;}
                int edge=cursor[vertex];
                while(edge>=0&&(!positive(edge)||level[to[edge]]!=level[vertex]+1))edge=next[edge];
                cursor[vertex]=edge;
                if(edge<0){level[vertex]=-1;if(depth==0)return false;depth--;cursor[pathVertex[depth]]=next[pathEdge[depth]];}
                else{pathEdge[depth]=edge;pathVertex[++depth]=to[edge];}
            }
            return false;
        }
        private void augmentPath(int depth){
            SignedDyadic amount=null;
            for(int i=0;i<depth;i++){var capacity=remaining[pathEdge[i]];if(capacity!=null)amount=amount==null?capacity:min(amount,capacity);}
            for(int i=0;i<depth;i++){
                int edge=pathEdge[i];
                if(remaining[edge]!=null)remaining[edge]=remaining[edge].subtract(amount);
                if(remaining[edge^1]!=null)remaining[edge^1]=remaining[edge^1].add(amount);
            }
        }
        SignedDyadic[] amounts(){
            var result=Arrays.copyOf(initial,raw.size);
            for(int r=0;r<raw.size;r++)result[r]=routeEdge[r]>=0?remaining[routeEdge[r]^1]:r<initial.length?initial[r]:SignedDyadic.ZERO;
            return result;
        }
    }

    private static LiquidTransportRoutes partitionMaterials(RouteBuffer raw,SignedDyadic[] amounts){
        var result=LiquidTransportRoutes.builder(raw.stocks.size(),raw.size);
        for(int s=0;s<raw.sources;s++){
            int count=0,largest=-1;
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(amounts[r].signum()>0){count++;if(largest<0||amounts[r].compareTo(amounts[largest])>0)largest=r;}
            if(count==0)continue;
            int first=raw.firstStock[s];
            if(raw.nextStock[first]<0){
                result.source(raw.stocks.get(first));
                for(int r=raw.head[s];r>=0;r=raw.next[r])if(amounts[r].signum()>0)raw.appendRoute(result,r,amounts[r]);
                continue;
            }
            int materials=0;for(int i=first;i>=0;i=raw.nextStock[i])materials++;
            int[] indices=new int[materials],active=new int[count];
            var remaining=new SignedDyadic[materials];double[] weights=new double[materials];
            for(int r=raw.head[s],i=0;r>=0;r=raw.next[r])if(amounts[r].signum()>0)active[i++]=r;
            var materialRoutes=new SignedDyadic[materials][count];
            for(int i=first,m=0;i>=0;i=raw.nextStock[i],m++){indices[m]=i;remaining[m]=raw.stocks.get(i).volume();}
            if(!partitionLayered(raw,s,amounts,indices,remaining,active,materialRoutes)){
                for(int pass=0;pass<2;pass++)for(int i=0;i<count;i++)if((active[i]==largest)==(pass==1)){
                    for(int m=0;m<materials;m++)weights[m]=remaining[m].toDouble();
                    appendShares(i,remaining,materialRoutes,LiquidCellInventory.allocateVolumes(amounts[active[i]],remaining,weights));
                }
            }
            for(int m=0;m<materials;m++){
                boolean moving=false;for(int i=0;i<count;i++)moving|=materialRoutes[m][i]!=null&&materialRoutes[m][i].signum()>0;
                if(!moving)continue;result.source(raw.stocks.get(indices[m]));
                for(int i=0;i<count;i++)if(materialRoutes[m][i]!=null&&materialRoutes[m][i].signum()>0)raw.appendRoute(result,active[i],materialRoutes[m][i]);
            }
        }
        return result.build();
    }

    /** Horizontal faces sample every layer; vertical faces draw from the corresponding density end. */
    private static boolean partitionLayered(RouteBuffer raw,int source,SignedDyadic[] amounts,int[] stocks,
            SignedDyadic[] remaining,int[] active,SignedDyadic[][] routes){
        if(raw.materialRegistry==null||raw.gravity==0)return false;
        boolean vertical=false;
        for(int r=raw.head[source];r>=0;r=raw.next[r])
            if(amounts[r].signum()>0&&raw.verticalDirection(r)!=0){vertical=true;break;}
        if(!vertical)return false;
        int n=stocks.length,groups=0;
        int[] phase=new int[n],member=new int[n],order=new int[n];
        double[] volume=new double[n],mass=new double[n];
        for(int m=0;m<n;m++){
            var stock=raw.stocks.get(stocks[m]);int id=raw.materialRegistry.phaseHandle(stock.key().material()),g=0;
            while(g<groups&&phase[g]!=id)g++;
            if(g==groups){phase[groups]=id;order[groups]=groups;groups++;}
            member[m]=g;volume[g]+=stock.volumeFraction();mass[g]+=stock.massUnits();
        }
        if(groups<2)return false;
        double[] density=new double[groups];
        for(int g=0;g<groups;g++)density[g]=mass[g]/volume[g];
        for(int i=1;i<groups;i++){
            int g=order[i],j=i;
            while(j>0&&density[order[j-1]]<density[g]){order[j]=order[j-1];j--;}
            order[j]=g;
        }
        var capacities=new SignedDyadic[n];double[] weights=new double[n];
        // Remove horizontal slabs proportionally before drawing disjoint bottom/top slabs.
        // Material left after the actual face slabs stays in its original stock.
        for(int pass=0;pass<3;pass++)for(int i=0;i<active.length;i++){
            int r=active[i];if(raw.face[r]<0)continue;
            int direction=raw.verticalDirection(r);
            if(pass==0&&direction!=0||pass==1&&direction!=-1||pass==2&&direction!=1)continue;
            var wanted=amounts[r];
            if(direction==0){
                for(int m=0;m<n;m++)weights[m]=remaining[m].toDouble();
                appendShares(i,remaining,routes,LiquidCellInventory.allocateVolumes(wanted,remaining,weights));
                continue;
            }
            for(int step=0;step<groups&&wanted.signum()>0;){
                int position=direction<0?step:groups-1-step,g=order[position];
                double layerDensity=density[g];var available=SignedDyadic.ZERO;
                for(int m=0;m<n;m++){
                    boolean selected=density[member[m]]==layerDensity;
                    capacities[m]=selected?remaining[m]:SignedDyadic.ZERO;
                    weights[m]=capacities[m].toDouble();available=available.add(capacities[m]);
                }
                var part=min(wanted,available);
                if(part.signum()>0){
                    appendShares(i,remaining,routes,LiquidCellInventory.allocateVolumes(part,capacities,weights));
                    wanted=wanted.subtract(part);
                }
                do{step++;position=direction<0?step:groups-1-step;}
                while(step<groups&&density[order[position]]==layerDensity);
            }
            if(wanted.signum()!=0)throw new IllegalStateException("Layered material flux exceeded its accepted source volume");
        }
        return true;
    }
    private static void appendShares(int route,SignedDyadic[] remaining,SignedDyadic[][] routes,SignedDyadic[] shares){
        for(int m=0;m<remaining.length;m++)if(shares[m].signum()>0){
            routes[m][route]=routes[m][route]==null?shares[m]:routes[m][route].add(shares[m]);
            remaining[m]=remaining[m].subtract(shares[m]);
        }
    }

    private static int slot(LiquidCellInventory.Key key){return 8*key.cell()+Integer.numberOfTrailingZeros(key.supportMask());}
    private static SignedDyadic value(SignedDyadic amount){return amount==null?SignedDyadic.ZERO:amount;}
    private static SignedDyadic minimum(SignedDyadic[] lower,int slot){return lower==null?SignedDyadic.ZERO:value(lower[slot]);}
    private static SignedDyadic min(SignedDyadic a,SignedDyadic b){return a.compareTo(b)<0?a:b;}
}
