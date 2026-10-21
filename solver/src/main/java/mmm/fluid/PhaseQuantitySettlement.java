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
        private FluidMaterialRegistry materialRegistry;
        private int sizeX,sizeY;
        private double gravity;

        void materialLayers(FluidMaterialRegistry registry,int cellsX,int cellsY,double acceleration){
            materialRegistry=registry;sizeX=cellsX;sizeY=cellsY;gravity=acceleration;
        }
        private int verticalDirection(int route){
            if(face[route]<0)return 0;
            int from=key(source[route]).cell()/sizeX%sizeY,to=target[route]/8/sizeX%sizeY;
            return Integer.compare(to,from)*(gravity>0?1:-1);
        }

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
		int sourceFor(int cell,int supportMask){return sourceAt[8*cell+Integer.numberOfTrailingZeros(supportMask)];}
		double outgoing(int source){return preferredTotal[source];}
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

    static List<LiquidCellInventory.SourceRoutes> settle(RouteBuffer raw,SignedDyadic[] upper,SignedDyadic[] lower,
            int[] receiverGroup,SignedDyadic[] groupUpper,IntConsumer alternatives){
        int sources=raw.sources,groups=groupUpper==null?0:groupUpper.length;
        var amount=new SignedDyadic[raw.size];Arrays.fill(amount,SignedDyadic.ZERO);
        var load=new SignedDyadic[upper.length];var groupLoad=new SignedDyadic[groups];Arrays.fill(groupLoad,SignedDyadic.ZERO);
        int[] touched=new int[upper.length];int touchedCount=0;
        for(int s=0;s<sources;s++){
            var remaining=raw.volume[s];double scale=raw.preferredTotal[s]>raw.volume(s)?raw.volume(s)/raw.preferredTotal[s]:1;
            int largest=raw.retained[s];
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(raw.preferred[r]>raw.preferred[largest])largest=r;
            // Numerical preferences resolve one cell to binary64 precision.
            // The largest share retains the exact source quantity, including finer tails.
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(r!=largest){
                double preference=raw.preferred[r]*scale;
                float rounded=(float)preference;
                double numerical=rounded>0&&Float.isFinite(rounded)?rounded:preference;
                var wanted=SignedDyadic.of(Math.scalb(Math.rint(Math.scalb(numerical,52)),-52));
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
        if(lower!=null)for(int slot=0;slot<lower.length;slot++)if(lower[slot]!=null&&lower[slot].signum()>0&&load[slot]==null){
            load[slot]=SignedDyadic.ZERO;touched[touchedCount++]=slot;
        }
        boolean adjust=false;
        for(int i=0;i<touchedCount;i++){int slot=touched[i];adjust|=load[slot].compareTo(upper[slot])>0;}
        if(lower!=null)for(int i=0;i<touchedCount;i++){int slot=touched[i];adjust|=lower[slot]!=null&&load[slot].compareTo(lower[slot])<0;}
        for(int g=0;g<groups;g++)adjust|=groupLoad[g].compareTo(groupUpper[g])>0;
        if(adjust&&lower!=null){
            amount=balanceFilledInterior(raw,amount,load,lower,upper);
            Arrays.fill(groupLoad,SignedDyadic.ZERO);adjust=false;touchedCount=0;
            for(int slot=0;slot<load.length;slot++)if(load[slot]!=null){
                touched[touchedCount++]=slot;var v=load[slot];
                adjust|=v.compareTo(upper[slot])>0||lower[slot]!=null&&v.compareTo(lower[slot])<0;
                int group=receiverGroup==null?-1:receiverGroup[slot];if(group>=0)groupLoad[group]=groupLoad[group].add(v);
            }
            for(int g=0;g<groups;g++)adjust|=groupLoad[g].compareTo(groupUpper[g])>0;
        }
        if(adjust){
            var repair=new ResidualRepair(raw,amount,load,upper,lower,receiverGroup,groupLoad,groupUpper,touched,touchedCount,alternatives);
            repair.solve();amount=new SignedDyadic[raw.size];
            for(int r=0;r<raw.size;r++)amount[r]=repair.amount(r);
        }
        return partitionMaterials(raw,amount);
    }

    /** A linear local projection sends full-cell volume defects toward the free surface.
     * Existing physical routes define the forest; a reverse route is added only when used.
     * Congestion and independent chamber reserves continue through the same residual solve. */
    private static SignedDyadic[] balanceFilledInterior(RouteBuffer raw,SignedDyadic[] amount,SignedDyadic[] load,
            SignedDyadic[] lower,SignedDyadic[] upper){
        boolean needed=false;
        for(int s=0;s<raw.sources;s++){
            int slot=slot(raw.key(s));
            if(lower[slot]!=null&&lower[slot].equals(upper[slot])&&!value(load[slot]).equals(lower[slot])){needed=true;break;}
        }
        if(!needed)return amount;
        int[] incoming=new int[raw.sources],next=new int[raw.size];Arrays.fill(incoming,-1);
        for(int r=0;r<raw.size;r++){
            int target=raw.sourceAt[raw.target[r]];
            if(target>=0&&target!=raw.source[r]){next[r]=incoming[target];incoming[target]=r;}
        }
        int[] parent=new int[raw.sources],edge=new int[raw.sources],order=new int[raw.sources];
        Arrays.fill(parent,-2);int count=0,visited=0;
        for(int s=0;s<raw.sources;s++){
            int slot=slot(raw.key(s));
            if(lower[slot]==null||!lower[slot].equals(upper[slot])){parent[s]=-1;order[count++]=s;}
        }
        for(int seed=-1;seed<raw.sources;seed++){
            if(seed>=0&&parent[seed]==-2){parent[seed]=-1;order[count++]=seed;}
            while(visited<count){
                int s=order[visited++];
                for(int pass=0;pass<2;pass++)for(int r=pass==0?raw.head[s]:incoming[s];r>=0;r=pass==0?raw.next[r]:next[r]){
                    int child=pass==0?raw.sourceAt[raw.target[r]]:raw.source[r];
                    if(child<0||parent[child]!=-2)continue;
                    parent[child]=s;edge[child]=r;order[count++]=child;
                }
            }
        }
        for(int i=count-1;i>=0;i--){
            int child=order[i],p=parent[child];if(p<0)continue;
            int own=slot(raw.key(child)),peer=slot(raw.key(p));
            var delta=lower[own].subtract(value(load[own]));if(delta.signum()==0)continue;
            int e=edge[child],face=raw.face[e];
            raw.alternative(child,face,peer/8,raw.key(p).supportMask());
            raw.alternative(p,face,own/8,raw.key(child).supportMask());
            if(amount.length<raw.size){int old=amount.length;amount=Arrays.copyOf(amount,Math.max(raw.size,2*old));Arrays.fill(amount,old,amount.length,SignedDyadic.ZERO);}
            int toParent=-1,fromParent=-1;
            for(int r=raw.head[child];r>=0;r=raw.next[r])if(raw.target[r]==peer){toParent=r;break;}
            for(int r=raw.head[p];r>=0;r=raw.next[r])if(raw.target[r]==own){fromParent=r;break;}
            boolean increase=delta.signum()>0;var need=increase?delta:delta.negate();
            int cancel=increase?toParent:fromParent,retained=increase?raw.retained[child]:raw.retained[p];
            var first=min(need,amount[cancel]);amount[cancel]=amount[cancel].subtract(first);amount[retained]=amount[retained].add(first);
            int donor=increase?raw.retained[p]:raw.retained[child],route=increase?fromParent:toParent;
            var second=min(need.subtract(first),amount[donor]);amount[donor]=amount[donor].subtract(second);amount[route]=amount[route].add(second);
            var moved=first.add(second);if(!increase)moved=moved.negate();
            load[own]=value(load[own]).add(moved);load[peer]=value(load[peer]).subtract(moved);
        }
        return amount;
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
            if(partitionLayered(raw,s,amounts,indices,remaining,materialRoutes)){
                for(int m=0;m<materials;m++)result.add(new LiquidCellInventory.SourceRoutes(raw.stocks.get(indices[m]),materialRoutes.get(m)));
                continue;
            }
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

    /** Horizontal faces sample every layer; vertical faces draw from the corresponding density end. */
    private static boolean partitionLayered(RouteBuffer raw,int source,SignedDyadic[] amounts,int[] stocks,
            SignedDyadic[] remaining,List<List<LiquidCellInventory.TransportRoute>> routes){
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
        // The retained row receives the exact material remainders after every outgoing face.
        for(int pass=0;pass<3;pass++)for(int r=raw.head[source];r>=0;r=raw.next[r]){
            if(raw.face[r]<0||amounts[r].signum()==0)continue;
            int direction=raw.verticalDirection(r);
            if(pass==0&&direction!=0||pass==1&&direction!=-1||pass==2&&direction!=1)continue;
            var wanted=amounts[r];
            if(direction==0){
                for(int m=0;m<n;m++)weights[m]=remaining[m].toDouble();
                appendShares(raw,r,stocks,remaining,routes,LiquidCellInventory.allocateVolumes(wanted,remaining,weights));
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
                    appendShares(raw,r,stocks,remaining,routes,LiquidCellInventory.allocateVolumes(part,capacities,weights));
                    wanted=wanted.subtract(part);
                }
                do{step++;position=direction<0?step:groups-1-step;}
                while(step<groups&&density[order[position]]==layerDensity);
            }
            if(wanted.signum()!=0)throw new IllegalStateException("Layered material flux exceeded its accepted source volume");
        }
        for(int m=0;m<n;m++)if(remaining[m].signum()>0)
            routes.get(m).add(raw.route(raw.retained[source],raw.stocks.get(stocks[m]),remaining[m]));
        return true;
    }
    private static void appendShares(RouteBuffer raw,int route,int[] stocks,SignedDyadic[] remaining,
            List<List<LiquidCellInventory.TransportRoute>> routes,SignedDyadic[] shares){
        for(int m=0;m<stocks.length;m++)if(shares[m].signum()>0){
            routes.get(m).add(raw.route(route,raw.stocks.get(stocks[m]),shares[m]));
            remaining[m]=remaining[m].subtract(shares[m]);
        }
    }

    private static int slot(LiquidCellInventory.Key key){return 8*key.cell()+Integer.numberOfTrailingZeros(key.supportMask());}
    private static SignedDyadic value(SignedDyadic amount){return amount==null?SignedDyadic.ZERO:amount;}
    private static SignedDyadic min(SignedDyadic a,SignedDyadic b){return a.compareTo(b)<0?a:b;}

    /** One residual graph handles inventory quantities at every volume scale. */
    private static final class ResidualRepair {
        private final RouteBuffer raw;
        private final SignedDyadic[] upper,lower,initialAmount,load;
        private final int[] receiverGroup,receiverVertex;
        private final IntConsumer alternatives;
        private final boolean[] expanded,expandedReceiver,expandedAggregate;
        private final int[] firstIncoming,nextIncoming,firstReceiver,nextReceiver;
        private final int firstSource=2,firstGroup,pool;
        private int vertices,edges;
        private int[] head,level,cursor,queue,pathVertex,pathEdge,receiverSlot;
        private int[] to=new int[64],next=new int[64],routeEdge;
        private SignedDyadic[] remaining=new SignedDyadic[64];
        private int receivers;

        ResidualRepair(RouteBuffer raw,SignedDyadic[] amount,SignedDyadic[] load,SignedDyadic[] upper,SignedDyadic[] lower,
                int[] receiverGroup,SignedDyadic[] groupLoad,SignedDyadic[] groupHigh,
                int[] touched,int touchedCount,IntConsumer alternatives){
            this.raw=raw;this.upper=upper;this.lower=lower;this.receiverGroup=receiverGroup;this.alternatives=alternatives;initialAmount=amount;this.load=load;
            expanded=new boolean[raw.sources];receiverVertex=new int[upper.length];
            int groups=groupHigh==null?0:groupHigh.length;
            expandedReceiver=new boolean[upper.length];expandedAggregate=new boolean[groups+1];
            firstIncoming=new int[upper.length];Arrays.fill(firstIncoming,-1);nextIncoming=new int[amount.length];
            firstReceiver=new int[groups+1];Arrays.fill(firstReceiver,-1);nextReceiver=new int[upper.length];Arrays.fill(nextReceiver,-2);
            firstGroup=firstSource+raw.sources;pool=firstGroup+groups;vertices=pool+1;
            int capacity=Math.max(16,vertices+2*raw.sources);
            head=new int[capacity];Arrays.fill(head,-1);level=new int[capacity];cursor=new int[capacity];queue=new int[capacity];
            pathVertex=new int[capacity];pathEdge=new int[capacity];receiverSlot=new int[capacity];Arrays.fill(receiverSlot,-1);
            routeEdge=new int[Math.max(32,raw.size)];Arrays.fill(routeEdge,-1);
            // Index existing assignments without building their residual edges.
            // A reverse donor edge is needed only when its receiver is visited.
            for(int r=0;r<amount.length;r++)if(amount[r].signum()>0){
                int slot=raw.target[r];nextIncoming[r]=firstIncoming[slot];firstIncoming[slot]=r;
            }
            var receiverExcess=new SignedDyadic[groups];Arrays.fill(receiverExcess,SignedDyadic.ZERO);
            var poolNeed=SignedDyadic.ZERO;
            for(int i=0;i<touchedCount;i++){
                int slot=touched[i];listReceiver(slot);
                var excess=load[slot].subtract(base(slot));
                if(excess.signum()>0)pair(0,receiver(slot),excess,SignedDyadic.ZERO);
                else if(excess.signum()<0)pair(receiver(slot),1,excess.negate(),SignedDyadic.ZERO);
                int group=receiverGroup==null?-1:receiverGroup[slot];
                if(group<0)poolNeed=poolNeed.add(excess);else receiverExcess[group]=receiverExcess[group].add(excess);
            }
            for(int g=0;g<groups;g++){
                var excess=groupLoad[g].compareTo(groupHigh[g])>0?groupLoad[g].subtract(groupHigh[g]):SignedDyadic.ZERO;
                var base=excess.signum()>0?groupHigh[g]:groupLoad[g];
                pair(firstGroup+g,pool,groupHigh[g].subtract(base),base);
                var balance=excess.subtract(receiverExcess[g]);
                if(balance.signum()>0)pair(0,firstGroup+g,balance,SignedDyadic.ZERO);
                else if(balance.signum()<0)pair(firstGroup+g,1,balance.negate(),SignedDyadic.ZERO);
                poolNeed=poolNeed.add(excess);
            }
            if(poolNeed.signum()>0)pair(pool,1,poolNeed,SignedDyadic.ZERO);
            else if(poolNeed.signum()<0)pair(0,pool,poolNeed.negate(),SignedDyadic.ZERO);
        }
        private SignedDyadic minimum(int slot){return lower==null||lower[slot]==null?SignedDyadic.ZERO:lower[slot];}
        private SignedDyadic base(int slot){var value=min(value(load[slot]),upper[slot]);return value.compareTo(minimum(slot))<0?minimum(slot):value;}
        private void listReceiver(int slot){
            if(nextReceiver[slot]!=-2)return;
            int group=receiverGroup==null?-1:receiverGroup[slot],aggregate=group<0?firstReceiver.length-1:group;
            nextReceiver[slot]=firstReceiver[aggregate];firstReceiver[aggregate]=slot;
        }
        private int receiver(int slot){
            if(receiverVertex[slot]!=0)return receiverVertex[slot];
            if(vertices==head.length){
                int old=head.length,n=2*old;head=Arrays.copyOf(head,n);Arrays.fill(head,old,n,-1);level=Arrays.copyOf(level,n);cursor=Arrays.copyOf(cursor,n);queue=Arrays.copyOf(queue,n);pathVertex=Arrays.copyOf(pathVertex,n);pathEdge=Arrays.copyOf(pathEdge,n);receiverSlot=Arrays.copyOf(receiverSlot,n);Arrays.fill(receiverSlot,old,n,-1);
            }
            int vertex=vertices++;receiverVertex[slot]=vertex;receiverSlot[vertex]=slot;level[vertex]=-1;receivers++;
            listReceiver(slot);
            int group=receiverGroup==null?-1:receiverGroup[slot];var base=base(slot);
            pair(vertex,group<0?pool:firstGroup+group,upper[slot].subtract(base),base.subtract(minimum(slot)));
            return vertex;
        }
        private void pair(int from,int target,SignedDyadic forward,SignedDyadic reverse){
            if(edges+2>to.length){int n=2*to.length;to=Arrays.copyOf(to,n);next=Arrays.copyOf(next,n);remaining=Arrays.copyOf(remaining,n);}
            to[edges]=target;next[edges]=head[from];remaining[edges]=forward;head[from]=edges++;
            to[edges]=from;next[edges]=head[target];remaining[edges]=reverse;head[target]=edges++;
        }
        private void expand(int vertex){
            int source=vertex-firstSource;
            if(source>=0&&source<raw.sources){
                if(expanded[source])return;expanded[source]=true;alternatives.accept(source);
                if(routeEdge.length<raw.size){int old=routeEdge.length;routeEdge=Arrays.copyOf(routeEdge,Math.max(raw.size,2*old));Arrays.fill(routeEdge,old,routeEdge.length,-1);}
                for(int r=raw.head[source];r>=0;r=raw.next[r])route(r);
                return;
            }
            int slot=receiverSlot[vertex];
            if(slot>=0){
                if(expandedReceiver[slot])return;expandedReceiver[slot]=true;
                for(int r=firstIncoming[slot];r>=0;r=nextIncoming[r])route(r);
                return;
            }
            if(vertex<firstGroup||vertex>pool)return;
            int aggregate=vertex-firstGroup;if(expandedAggregate[aggregate])return;expandedAggregate[aggregate]=true;
            for(int r=firstReceiver[aggregate];r>=0;r=nextReceiver[r])receiver(r);
        }
        private void route(int r){
            if(routeEdge[r]>=0)return;
            int target=receiver(raw.target[r]);routeEdge[r]=edges;
            pair(firstSource+raw.source[r],target,null,r<initialAmount.length?initialAmount[r]:SignedDyadic.ZERO);
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
            int slot=receiverSlot[vertex];if(slot>=0)return "receiverCell="+(slot/8)+", component="+(slot%8);
            return vertex>=firstGroup&&vertex<pool?"gasCapacityGroup="+(vertex-firstGroup):"capacityPool";
        }
        private boolean levels(){
            Arrays.fill(level,0,vertices,-1);int first=0,last=0;queue[last++]=0;level[0]=0;
            // Build the complete shortest sink layer. Returning at its first
            // predecessor leaves the other independent congestion repairs for
            // another full search; exploring beyond it opens irrelevant pores.
            while(first<last&&(level[1]<0||level[queue[first]]<level[1])){
                int vertex=queue[first++];boolean endpoint=false;
                // Reaching available capacity closes a shortest path. Its
                // unrelated reverse assignments cannot improve this layer.
                for(int e=head[vertex];e>=0;e=next[e])if(to[e]==1&&positive(remaining[e])){level[1]=level[vertex]+1;endpoint=true;break;}
                if(endpoint)continue;
                expand(vertex);
                for(int e=head[vertex];e>=0;e=next[e])if(positive(remaining[e])&&level[to[e]]<0){
                    int target=to[e];level[target]=level[vertex]+1;
                    if(target!=1)queue[last++]=target;
                }
            }
            return level[1]>=0;
        }
        private boolean augment(){
            int depth=0;pathVertex[0]=0;
            while(depth>=0){
                int vertex=pathVertex[depth];
                if(vertex==1){
                    SignedDyadic amount=null;
                    for(int i=0;i<depth;i++){
                        var capacity=remaining[pathEdge[i]];
                        if(capacity!=null)amount=amount==null?capacity:min(amount,capacity);
                    }
                    for(int i=0;i<depth;i++){
                        int edge=pathEdge[i];
                        if(remaining[edge]!=null)remaining[edge]=remaining[edge].subtract(amount);
                        if(remaining[edge^1]!=null)remaining[edge^1]=remaining[edge^1].add(amount);
                    }
                    return true;
                }
                int edge=cursor[vertex];while(edge>=0&&(!positive(remaining[edge])||level[to[edge]]!=level[vertex]+1))edge=next[edge];cursor[vertex]=edge;
                if(edge<0){level[vertex]=-1;if(depth==0)return false;depth--;cursor[pathVertex[depth]]=next[pathEdge[depth]];}
                else{pathEdge[depth]=edge;pathVertex[++depth]=to[edge];}
            }
            return false;
        }
        // A donor can only be reached by withdrawing an existing assigned share.
        // Its fixed total and nonnegative reverse flows already bound every route;
        // null denotes the redundant unbounded forward reassignment capacity.
        private static boolean positive(SignedDyadic capacity){return capacity==null||capacity.signum()>0;}
        SignedDyadic amount(int route){int edge=routeEdge[route];return edge<0?(route<initialAmount.length?initialAmount[route]:SignedDyadic.ZERO):remaining[edge^1];}
    }
}
