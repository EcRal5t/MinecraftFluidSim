package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/** Continuous component fluxes, bounded once before their material inventories are partitioned. */
final class PhaseQuantitySettlement {
    private PhaseQuantitySettlement() { }

    /** Fixed-pore components own numerical routes; material stocks share each accepted route. */
    static final class RouteBuffer {
        private List<LiquidCellInventory.Stock> stocks=List.of();
        private int sources,size;
        private int[] sourceAt=new int[0],firstStock=new int[0],nextStock=new int[0];
        private SignedDyadic[] volume=new SignedDyadic[0];
        private int[] source=new int[32],target=new int[32],support=new int[32],face=new int[32],next=new int[32];
        private double[] preferred=new double[32];
        private int[] head=new int[0],retained=new int[0];
        private double[] preferredTotal=new double[0];

        void reset(List<LiquidCellInventory.Stock> values,int slots){
            stocks=values;sources=size=0;
            if(sourceAt.length<slots)sourceAt=new int[slots];
            Arrays.fill(sourceAt,0,slots,-1);
            if(firstStock.length<values.size()){
                int n=values.size();firstStock=new int[n];nextStock=new int[n];volume=new SignedDyadic[n];
                head=new int[n];retained=new int[n];preferredTotal=new double[n];
            }
            for(int i=0;i<values.size();i++){
                var stock=values.get(i);int slot=slot(stock.key()),s=sourceAt[slot];
                if(s<0){
                    sourceAt[slot]=s=sources++;firstStock[s]=-1;volume[s]=SignedDyadic.ZERO;
                    head[s]=retained[s]=-1;preferredTotal[s]=0;
                }
                nextStock[i]=firstStock[s];firstStock[s]=i;volume[s]=volume[s].add(stock.volume());
            }
        }
        int sourceCount(){return sources;}
        LiquidCellInventory.Key key(int s){return stocks.get(firstStock[s]).key();}
        double volume(int s){return volume[s].toDouble();}
        void add(int ordinal,int sharedFace,int cell,int supportMask,double preference){
            if(!(preference>=0&&Double.isFinite(preference)))throw new IllegalArgumentException("Invalid liquid volume preference");
            if(size==source.length){int n=2*size;source=Arrays.copyOf(source,n);target=Arrays.copyOf(target,n);support=Arrays.copyOf(support,n);face=Arrays.copyOf(face,n);next=Arrays.copyOf(next,n);preferred=Arrays.copyOf(preferred,n);}
            source[size]=ordinal;target[size]=8*cell+Integer.numberOfTrailingZeros(supportMask);support[size]=supportMask;face[size]=sharedFace;
            preferred[size]=preference;if(sharedFace>=0)preferredTotal[ordinal]+=preference;next[size]=head[ordinal];head[ordinal]=size;
            if(sharedFace<0)retained[ordinal]=size;
            size++;
        }
        void alternative(int ordinal,int sharedFace,int cell,int supportMask){
            int slot=8*cell+Integer.numberOfTrailingZeros(supportMask);
            for(int r=head[ordinal];r>=0;r=next[r])if(face[r]==sharedFace&&target[r]==slot)return;
            add(ordinal,sharedFace,cell,supportMask,0);
        }
        private LiquidCellInventory.TransportRoute route(int r,LiquidCellInventory.Stock stock,SignedDyadic amount){
            var key=stock.key();var to=face[r]<0?key:new LiquidCellInventory.Key(target[r]/8,key.material(),support[r]);
            return new LiquidCellInventory.TransportRoute(face[r],key,to,amount);
        }
    }

    static List<LiquidCellInventory.SourceRoutes> settle(RouteBuffer raw,SignedDyadic[] upper,
            int[] receiverGroup,SignedDyadic[] groupUpper,IntConsumer alternatives){
        int sources=raw.sources,groups=groupUpper==null?0:groupUpper.length;
        var amount=new SignedDyadic[raw.size];Arrays.fill(amount,SignedDyadic.ZERO);
        var load=new SignedDyadic[upper.length];var groupLoad=new SignedDyadic[groups];Arrays.fill(groupLoad,SignedDyadic.ZERO);
        int[] touched=new int[Math.max(1,sources+raw.size)];int touchedCount=0;
        for(int s=0;s<sources;s++){
            var remaining=raw.volume[s];double scale=raw.preferredTotal[s]>raw.volume(s)?raw.volume(s)/raw.preferredTotal[s]:1;
            int largest=raw.retained[s];
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(raw.preferred[r]>raw.preferred[largest])largest=r;
            // Numerical fluxes use relative floating precision, so an arbitrarily
            // small positive flow remains positive. The largest share absorbs the
            // exact inventory remainder, independently of the absolute flow scale.
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(r!=largest){
                double preference=raw.preferred[r]*scale;
                float rounded=(float)preference;
                var wanted=SignedDyadic.of(rounded>0&&Float.isFinite(rounded)?rounded:preference);
                if(wanted.compareTo(remaining)>0)wanted=remaining;
                amount[r]=wanted;remaining=remaining.subtract(wanted);
            }
            amount[largest]=remaining;
        }
        for(int r=0;r<raw.size;r++)if(amount[r].signum()>0){
            int slot=raw.target[r];if(load[slot]==null){load[slot]=SignedDyadic.ZERO;touched[touchedCount++]=slot;}
            load[slot]=load[slot].add(amount[r]);
            int group=receiverGroup==null?-1:receiverGroup[slot];if(group>=0)groupLoad[group]=groupLoad[group].add(amount[r]);
        }
        boolean adjust=false;
        for(int i=0;i<touchedCount;i++){int slot=touched[i];adjust|=load[slot].compareTo(upper[slot])>0;}
        for(int g=0;g<groups;g++)adjust|=groupLoad[g].compareTo(groupUpper[g])>0;
        if(adjust){
            var repair=new ResidualRepair(raw,amount,load,upper,receiverGroup,groupLoad,groupUpper,alternatives);
            repair.solve();amount=new SignedDyadic[raw.size];
            for(int r=0;r<raw.size;r++)amount[r]=repair.amount(r);
        }
        return partitionMaterials(raw,amount);
    }

    /** Both the component face totals and each original material volume close exactly. */
    private static List<LiquidCellInventory.SourceRoutes> partitionMaterials(RouteBuffer raw,SignedDyadic[] amounts){
        var result=new ArrayList<LiquidCellInventory.SourceRoutes>(raw.stocks.size());
        for(int s=0;s<raw.sources;s++){
            int first=raw.firstStock[s];
            if(raw.nextStock[first]<0){
                var stock=raw.stocks.get(first);
                if(amounts[raw.retained[s]].equals(stock.volume()))continue;
                var routes=new ArrayList<LiquidCellInventory.TransportRoute>();
                for(int r=raw.head[s];r>=0;r=raw.next[r])if(amounts[r].signum()>0)routes.add(raw.route(r,stock,amounts[r]));
                result.add(new LiquidCellInventory.SourceRoutes(stock,routes));
                continue;
            }
            int count=0,last=-1;
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(amounts[r].signum()>0){count++;if(last<0||amounts[r].compareTo(amounts[last])>0)last=r;}
            if(count==1){
                if(last==raw.retained[s])continue;
                for(int i=raw.firstStock[s];i>=0;i=raw.nextStock[i]){var stock=raw.stocks.get(i);result.add(new LiquidCellInventory.SourceRoutes(stock,List.of(raw.route(last,stock,stock.volume()))));}
                continue;
            }
            int materials=0;for(int i=raw.firstStock[s];i>=0;i=raw.nextStock[i])materials++;
            int[] indices=new int[materials];var remaining=new SignedDyadic[materials];double[] weights=new double[materials];
            var materialRoutes=new ArrayList<List<LiquidCellInventory.TransportRoute>>(materials);
            for(int m=0;m<materials;m++)materialRoutes.add(new ArrayList<>());
            for(int i=raw.firstStock[s],m=0;i>=0;i=raw.nextStock[i],m++){indices[m]=i;remaining[m]=raw.stocks.get(i).volume();}
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(r!=last&&amounts[r].signum()>0){
                for(int m=0;m<materials;m++)weights[m]=remaining[m].toDouble();
                var shares=LiquidCellInventory.allocateVolumes(amounts[r],remaining,weights);
                for(int m=0;m<materials;m++)if(shares[m].signum()>0){
                    materialRoutes.get(m).add(raw.route(r,raw.stocks.get(indices[m]),shares[m]));remaining[m]=remaining[m].subtract(shares[m]);
                }
            }
            for(int m=0;m<materials;m++){
                var stock=raw.stocks.get(indices[m]);
                if(remaining[m].signum()>0)materialRoutes.get(m).add(raw.route(last,stock,remaining[m]));
                result.add(new LiquidCellInventory.SourceRoutes(stock,materialRoutes.get(m)));
            }
        }
        return result;
    }

    private static int slot(LiquidCellInventory.Key key){return 8*key.cell()+Integer.numberOfTrailingZeros(key.supportMask());}
    private static SignedDyadic value(SignedDyadic amount){return amount==null?SignedDyadic.ZERO:amount;}
    private static SignedDyadic min(SignedDyadic a,SignedDyadic b){return a.compareTo(b)<0?a:b;}

    /** One residual graph handles inventory quantities at every volume scale. */
    private static final class ResidualRepair {
        private final RouteBuffer raw;
        private final SignedDyadic[] upper;
        private final int[] receiverGroup,receiverVertex;
        private final IntConsumer alternatives;
        private final boolean[] expanded;
        private final int firstSource=2,firstGroup,pool;
        private int vertices,edges;
        private int[] head,level,cursor,queue,pathVertex,pathEdge,receiverSlots;
        private SignedDyadic[] balance;
        private int[] to=new int[64],next=new int[64],routeEdge;
        private SignedDyadic[] remaining=new SignedDyadic[64];
        private int receivers;
        private boolean initialized;

        ResidualRepair(RouteBuffer raw,SignedDyadic[] amount,SignedDyadic[] load,SignedDyadic[] upper,
                int[] receiverGroup,SignedDyadic[] groupLoad,SignedDyadic[] groupHigh,IntConsumer alternatives){
            this.raw=raw;this.upper=upper;this.receiverGroup=receiverGroup;this.alternatives=alternatives;
            expanded=new boolean[raw.sources];receiverVertex=new int[upper.length];
            int groups=groupHigh==null?0:groupHigh.length;
            firstGroup=firstSource+raw.sources;pool=firstGroup+groups;vertices=pool+1;
            int capacity=Math.max(16,vertices+2*raw.sources);
            head=new int[capacity];Arrays.fill(head,-1);level=new int[capacity];cursor=new int[capacity];queue=new int[capacity];
            pathVertex=new int[capacity];pathEdge=new int[capacity];balance=new SignedDyadic[capacity];Arrays.fill(balance,SignedDyadic.ZERO);
            receiverSlots=new int[Math.max(16,2*raw.sources)];routeEdge=new int[Math.max(32,raw.size)];
            // Each donor partition already closes exactly. Its balance is zero;
            // receiver loads are the existing flow, not a second inventory sum.
            for(int r=0;r<amount.length;r++){
                routeEdge[r]=edges;
                pair(firstSource+raw.source[r],receiver(raw.target[r]),raw.volume[raw.source[r]].subtract(amount[r]),amount[r]);
            }
            for(int i=0;i<receivers;i++){int slot=receiverSlots[i];balance[receiverVertex[slot]]=value(load[slot]);}
            for(int g=0;g<groups;g++)add(firstGroup+g,pool,groupHigh[g],min(groupLoad[g],groupHigh[g]));
            for(int i=0;i<receivers;i++){int slot=receiverSlots[i];capacityEdge(slot,min(value(load[slot]),upper[slot]));}
            var unbalanced=SignedDyadic.ZERO;
            for(int v=firstGroup;v<vertices;v++)if(v!=pool&&balance[v].signum()!=0)unbalanced=unbalanced.add(balance[v]);
            balance[pool]=unbalanced.negate();
            initialized=true;
            for(int v=2;v<vertices;v++){
                if(balance[v].signum()>0)pair(0,v,balance[v],SignedDyadic.ZERO);
                else if(balance[v].signum()<0)pair(v,1,balance[v].negate(),SignedDyadic.ZERO);
            }
        }
        private int receiver(int slot){
            if(receiverVertex[slot]!=0)return receiverVertex[slot];
            if(vertices==head.length){
                int old=head.length,n=2*old;head=Arrays.copyOf(head,n);Arrays.fill(head,old,n,-1);level=Arrays.copyOf(level,n);cursor=Arrays.copyOf(cursor,n);queue=Arrays.copyOf(queue,n);pathVertex=Arrays.copyOf(pathVertex,n);pathEdge=Arrays.copyOf(pathEdge,n);balance=Arrays.copyOf(balance,n);Arrays.fill(balance,old,n,SignedDyadic.ZERO);
            }
            int vertex=vertices++;receiverVertex[slot]=vertex;level[vertex]=-1;
            if(receivers==receiverSlots.length)receiverSlots=Arrays.copyOf(receiverSlots,2*receivers);
            receiverSlots[receivers++]=slot;
            if(initialized)capacityEdge(slot,SignedDyadic.ZERO);
            return vertex;
        }
        private void capacityEdge(int slot,SignedDyadic initial){
            int group=receiverGroup==null?-1:receiverGroup[slot];add(receiverVertex[slot],group<0?pool:firstGroup+group,upper[slot],initial);
        }
        private int add(int from,int target,SignedDyadic upper,SignedDyadic initial){
            int edge=edges;pair(from,target,upper.subtract(initial),initial);balance[from]=balance[from].subtract(initial);balance[target]=balance[target].add(initial);return edge;
        }
        private void pair(int from,int target,SignedDyadic forward,SignedDyadic reverse){
            if(edges+2>to.length){int n=2*to.length;to=Arrays.copyOf(to,n);next=Arrays.copyOf(next,n);remaining=Arrays.copyOf(remaining,n);}
            to[edges]=target;next[edges]=head[from];remaining[edges]=forward;head[from]=edges++;
            to[edges]=from;next[edges]=head[target];remaining[edges]=reverse;head[target]=edges++;
        }
        private void expand(int vertex){
            int source=vertex-firstSource;if(source<0||source>=raw.sources||expanded[source])return;
            expanded[source]=true;int first=raw.size;alternatives.accept(source);
            if(routeEdge.length<raw.size)routeEdge=Arrays.copyOf(routeEdge,Math.max(raw.size,2*routeEdge.length));
            for(int r=first;r<raw.size;r++)routeEdge[r]=add(vertex,receiver(raw.target[r]),raw.volume[source],SignedDyadic.ZERO);
        }
        void solve(){
            while(levels()){
                System.arraycopy(head,0,cursor,0,vertices);
                while(augment()) { }
            }
            for(int e=head[0];e>=0;e=next[e])if(remaining[e].signum()>0)
                throw new ConservativeLiquidFlux.CapacityUnavailable("Liquid inventory has insufficient reachable pore capacity: residual="
                        + remaining[e].toDouble() + ", " + location(to[e]) + ", sources=" + raw.sources
                        + ", receivers=" + receivers + ", routes=" + raw.size);
        }
        private String location(int vertex) {
            int source=vertex-firstSource;
            if(source>=0&&source<raw.sources) {
                var key=raw.key(source);
                return "sourceCell="+key.cell()+", supportMask="+key.supportMask();
            }
            for(int i=0;i<receivers;i++) {
                int slot=receiverSlots[i];
                if(receiverVertex[slot]==vertex)return "receiverCell="+(slot/8)+", component="+(slot%8);
            }
            return vertex>=firstGroup&&vertex<pool?"gasCapacityGroup="+(vertex-firstGroup):"capacityPool";
        }
        private boolean levels(){
            Arrays.fill(level,0,vertices,-1);int first=0,last=0;queue[last++]=0;level[0]=0;
            while(first<last){
                int vertex=queue[first++];expand(vertex);
                for(int e=head[vertex];e>=0;e=next[e])if(remaining[e].signum()>0&&level[to[e]]<0){
                    int target=to[e];level[target]=level[vertex]+1;
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
                    SignedDyadic amount=null;for(int i=0;i<depth;i++)amount=amount==null?remaining[pathEdge[i]]:min(amount,remaining[pathEdge[i]]);
                    for(int i=0;i<depth;i++){int edge=pathEdge[i];remaining[edge]=remaining[edge].subtract(amount);remaining[edge^1]=remaining[edge^1].add(amount);}return true;
                }
                int edge=cursor[vertex];while(edge>=0&&(remaining[edge].signum()<=0||level[to[edge]]!=level[vertex]+1))edge=next[edge];cursor[vertex]=edge;
                if(edge<0){level[vertex]=-1;if(depth==0)return false;depth--;cursor[pathVertex[depth]]=next[pathEdge[depth]];}
                else{pathEdge[depth]=edge;pathVertex[++depth]=to[edge];}
            }
            return false;
        }
        SignedDyadic amount(int route){return remaining[routeEdge[route]^1];}
    }
}
