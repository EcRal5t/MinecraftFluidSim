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
		private double[] numericalVolume=new double[0];
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
                int n=values.size();firstStock=new int[n];nextStock=new int[n];volume=new SignedDyadic[n];numericalVolume=new double[n];
                head=new int[n];retained=new int[n];preferredTotal=new double[n];
            }
            for(int i=0;i<values.size();i++){
                var stock=values.get(i);int slot=slot(stock.key()),s=sourceAt[slot];
                if(s<0){
                    sourceAt[slot]=s=sources++;firstStock[s]=-1;volume[s]=SignedDyadic.ZERO;numericalVolume[s]=Double.NaN;
                    head[s]=retained[s]=-1;preferredTotal[s]=0;
                }
                nextStock[i]=firstStock[s];firstStock[s]=i;volume[s]=volume[s].add(stock.volume());
            }
        }
        int sourceCount(){return sources;}
		int sourceFor(int cell,int supportMask){return sourceAt[8*cell+Integer.numberOfTrailingZeros(supportMask)];}
		double outgoing(int source){return preferredTotal[source];}
        LiquidCellInventory.Key key(int s){return stocks.get(firstStock[s]).key();}
        double volume(int s){double value=numericalVolume[s];if(Double.isNaN(value))numericalVolume[s]=value=volume[s].toDouble();return value;}
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
        int groups=groupUpper==null?0:groupUpper.length;
        double[] numerical=new double[raw.size+2*raw.sources],load=new double[upper.length],groupLoad=new double[groups];
        int[] sourceTerms=new int[raw.sources],loadTerms=new int[upper.length],groupTerms=new int[groups];
        int[] touched=new int[upper.length];int touchedCount=0;
        for(int s=0;s<raw.sources;s++){
            double remaining=raw.volume(s),scale=raw.preferredTotal[s]>remaining?remaining/raw.preferredTotal[s]:1;
            int largest=raw.retained[s];
            int terms=1;
            for(int r=raw.head[s];r>=0;r=raw.next[r]){terms++;if(raw.preferred[r]>raw.preferred[largest])largest=r;}
            sourceTerms[s]=terms;
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(r!=largest){
                double preference=raw.preferred[r]*scale;float word=(float)preference;
                if(word>0&&Float.isFinite(word))preference=word;
                double amount=Math.min(remaining,preference);
                numerical[r]=amount;remaining-=amount;
            }
            numerical[largest]=remaining;
        }
        for(int r=0;r<raw.size;r++)if(numerical[r]>0){
            int slot=raw.target[r];if(loadTerms[slot]==0)touched[touchedCount++]=slot;
            load[slot]+=numerical[r];loadTerms[slot]=addTerms(loadTerms[slot],sourceTerms[raw.source[r]]+1);
        }
        if(lower!=null)for(int slot=0;slot<lower.length;slot++)if(lower[slot]!=null&&lower[slot].signum()>0&&loadTerms[slot]==0){
            touched[touchedCount++]=slot;loadTerms[slot]=1;
        }
        CapacityForest forest=null;
        if(lower!=null)for(int s=0;s<raw.sources;s++){
            int slot=slot(raw.key(s));
            if(lower[slot]==null||lower[slot].signum()==0)continue;
            double low=lower[slot].toDouble(),high=upper[slot].toDouble();
            double endpoint=Math.clamp(load[slot],low,high);
            if(exceedsAccumulationError(load[slot],endpoint,loadTerms[slot])){
                forest=new CapacityForest(raw,lower);
                touchedCount=forest.project(raw,numerical,load,lower,upper,loadTerms,touched,touchedCount);break;
            }
        }
        boolean adjust=false;
        for(int i=0;i<touchedCount;i++){
            int slot=touched[i];double high=upper[slot].toDouble();
            adjust|=load[slot]>high&&exceedsAccumulationError(load[slot],high,loadTerms[slot]);
            if(lower!=null&&lower[slot]!=null){double low=lower[slot].toDouble();adjust|=load[slot]<low&&exceedsAccumulationError(load[slot],low,loadTerms[slot]);}
            int group=receiverGroup==null?-1:receiverGroup[slot];
            if(group>=0){groupLoad[group]+=load[slot];groupTerms[group]=addTerms(groupTerms[group],addTerms(loadTerms[slot],1));}
        }
        for(int g=0;g<groups;g++){double high=groupUpper[g].toDouble();adjust|=groupLoad[g]>high&&exceedsAccumulationError(groupLoad[g],high,groupTerms[g]);}
        CapacityProjection projection=null;
        if(adjust){
            projection=new CapacityProjection(raw,numerical,load,upper,lower,receiverGroup,groupLoad,groupUpper,loadTerms,groupTerms,touched,touchedCount,alternatives);
            projection.solve();
            numerical=new double[raw.size];
            for(int r=0;r<raw.size;r++)numerical[r]=projection.numericalAmount(r);
        }

        // Exact quantities enter once, after the numerical capacity projection.
        // The largest accepted share carries each source's finer inventory remainder.
        var amount=new SignedDyadic[raw.size];Arrays.fill(amount,SignedDyadic.ZERO);
        for(int s=0;s<raw.sources;s++){
            int largest=raw.retained[s];
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(numerical[r]>numerical[largest])largest=r;
            var remaining=raw.volume[s];
            for(int r=raw.head[s];r>=0;r=raw.next[r])if(r!=largest&&numerical[r]>0){
                var assigned=min(SignedDyadic.of(numerical[r]),remaining);
                amount[r]=assigned;remaining=remaining.subtract(assigned);
            }
            amount[largest]=remaining;
        }
        var exactLoad=new SignedDyadic[upper.length];
        var exactGroupLoad=new SignedDyadic[groups];Arrays.fill(exactGroupLoad,SignedDyadic.ZERO);
        touchedCount=0;
        for(int r=0;r<raw.size;r++)if(amount[r].signum()>0){
            int slot=raw.target[r];if(exactLoad[slot]==null){exactLoad[slot]=SignedDyadic.ZERO;touched[touchedCount++]=slot;}
            exactLoad[slot]=exactLoad[slot].add(amount[r]);
        }
        if(lower!=null)for(int slot=0;slot<lower.length;slot++)if(lower[slot]!=null&&lower[slot].signum()>0&&exactLoad[slot]==null){
            exactLoad[slot]=SignedDyadic.ZERO;touched[touchedCount++]=slot;
        }
        adjust=requiresCommitRepair(exactLoad,upper,lower,receiverGroup,exactGroupLoad,groupUpper,touched,touchedCount);
        if(adjust&&lower!=null&&hasCapacityDefect(raw,exactLoad,lower,upper)){
            if(forest==null)forest=new CapacityForest(raw,lower);
            amount=forest.commit(raw,amount,exactLoad,lower,upper);
            touchedCount=0;
            for(int slot=0;slot<exactLoad.length;slot++)if(exactLoad[slot]!=null)touched[touchedCount++]=slot;
            Arrays.fill(exactGroupLoad,SignedDyadic.ZERO);
            adjust=requiresCommitRepair(exactLoad,upper,lower,receiverGroup,exactGroupLoad,groupUpper,touched,touchedCount);
        }
        if(adjust){
            if(projection==null)projection=new CapacityProjection(raw,numerical,load,upper,lower,receiverGroup,groupLoad,groupUpper,loadTerms,groupTerms,
                    new int[0],0,alternatives);
            projection.prepareCommit(amount,exactLoad,exactGroupLoad,touched,touchedCount);
            projection.solve();
            amount=new SignedDyadic[raw.size];
            for(int r=0;r<raw.size;r++)amount[r]=projection.committedAmount(r);
        }
        return partitionMaterials(raw,amount);
    }

    private static boolean requiresCommitRepair(SignedDyadic[] load,SignedDyadic[] upper,SignedDyadic[] lower,
            int[] receiverGroup,SignedDyadic[] groupLoad,SignedDyadic[] groupUpper,int[] touched,int touchedCount){
        boolean adjust=false;
        for(int i=0;i<touchedCount;i++){
            int slot=touched[i];var amount=load[slot];
            adjust|=amount.compareTo(upper[slot])>0||lower!=null&&lower[slot]!=null&&amount.compareTo(lower[slot])<0;
            int group=receiverGroup==null?-1:receiverGroup[slot];
            if(group>=0)groupLoad[group]=groupLoad[group].add(amount);
        }
        for(int g=0;g<groupLoad.length;g++)adjust|=groupLoad[g].compareTo(groupUpper[g])>0;
        return adjust;
    }
    /** Counts the primitive additions/subtractions whose rounding reaches an endpoint. */
    private static int addTerms(int a,int b){return (int)Math.min(Integer.MAX_VALUE,(long)a+b);}
    private static boolean exceedsAccumulationError(double amount,double bound,int terms){
        return Math.abs(amount-bound)>Math.ulp(Math.max(Math.abs(amount),Math.abs(bound)))*(terms+2.0);
    }
    private static boolean hasCapacityDefect(RouteBuffer raw,SignedDyadic[] load,SignedDyadic[] lower,SignedDyadic[] upper){
        for(int s=0;s<raw.sources;s++){
            int slot=slot(raw.key(s));
            if(lower[slot]!=null&&lower[slot].signum()>0){
                var amount=value(load[slot]);if(amount.compareTo(lower[slot])<0||amount.compareTo(upper[slot])>0)return true;
            }
        }
        return false;
    }

    /** Physical component routes define one bounded-volume forest for numerical projection and exact submission. */
    private static final class CapacityForest {
        private final int[] parent,edge,order;
        private int count;
        CapacityForest(RouteBuffer raw,SignedDyadic[] lower){
            int[] incoming=new int[raw.sources],next=new int[raw.size];Arrays.fill(incoming,-1);
            for(int r=0;r<raw.size;r++){
                int target=raw.sourceAt[raw.target[r]];
                if(target>=0&&target!=raw.source[r]){next[r]=incoming[target];incoming[target]=r;}
            }
            parent=new int[raw.sources];edge=new int[raw.sources];order=new int[raw.sources];
            Arrays.fill(parent,-2);int visited=0;
            for(int s=0;s<raw.sources;s++){
                int slot=slot(raw.key(s));
                if(lower[slot]==null||lower[slot].signum()==0){parent[s]=-1;order[count++]=s;}
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
        }
        private int route(RouteBuffer raw,int source,int target){
            for(int r=raw.head[source];r>=0;r=raw.next[r])if(raw.target[r]==target)return r;
            throw new IllegalStateException("Capacity forest lost its component route");
        }
        int project(RouteBuffer raw,double[] amount,double[] load,SignedDyadic[] lower,SignedDyadic[] upper,int[] terms,int[] touched,int touchedCount){
            for(int i=count-1;i>=0;i--){
                int child=order[i],p=parent[child];if(p<0)continue;
                int own=slot(raw.key(child)),peer=slot(raw.key(p));
                double target=Math.clamp(load[own],lower[own].toDouble(),upper[own].toDouble());
                if(!exceedsAccumulationError(load[own],target,terms[own]))continue;
                double delta=target-load[own];int face=raw.face[edge[child]];
                raw.alternative(child,face,peer/8,raw.key(p).supportMask());
                raw.alternative(p,face,own/8,raw.key(child).supportMask());
                int toParent=route(raw,child,peer),fromParent=route(raw,p,own);
                boolean increase=delta>0;double need=Math.abs(delta);
                int cancel=increase?toParent:fromParent,retained=increase?raw.retained[child]:raw.retained[p];
                double first=Math.min(need,amount[cancel]);amount[cancel]-=first;amount[retained]+=first;
                int donor=increase?raw.retained[p]:raw.retained[child],out=increase?fromParent:toParent;
                double second=Math.min(need-first,amount[donor]);amount[donor]-=second;amount[out]+=second;
                double moved=first+second;if(!increase)moved=-moved;
                load[own]+=moved;load[peer]-=moved;
                if(terms[peer]==0)touched[touchedCount++]=peer;
                // Delta, donor updates and endpoint updates propagate eight rounded operations.
                terms[peer]=addTerms(terms[peer],addTerms(terms[own],8));terms[own]=addTerms(terms[own],8);
            }
            return touchedCount;
        }
        SignedDyadic[] commit(RouteBuffer raw,SignedDyadic[] amount,SignedDyadic[] load,SignedDyadic[] lower,SignedDyadic[] upper){
            for(int i=count-1;i>=0;i--){
                int child=order[i],p=parent[child];if(p<0)continue;
                int own=slot(raw.key(child)),peer=slot(raw.key(p));
                var current=value(load[own]);
                var target=min(current,upper[own]);if(target.compareTo(lower[own])<0)target=lower[own];
                if(target==current)continue;
                var delta=target.subtract(current);
                int face=raw.face[edge[child]];
                raw.alternative(child,face,peer/8,raw.key(p).supportMask());
                raw.alternative(p,face,own/8,raw.key(child).supportMask());
                if(amount.length<raw.size){int old=amount.length;amount=Arrays.copyOf(amount,Math.max(raw.size,2*old));Arrays.fill(amount,old,amount.length,SignedDyadic.ZERO);}
                int toParent=route(raw,child,peer),fromParent=route(raw,p,own);
                boolean increase=delta.signum()>0;var need=increase?delta:delta.negate();
                int cancel=increase?toParent:fromParent,retained=increase?raw.retained[child]:raw.retained[p];
                var first=min(need,amount[cancel]);amount[cancel]=amount[cancel].subtract(first);amount[retained]=amount[retained].add(first);
                int donor=increase?raw.retained[p]:raw.retained[child],out=increase?fromParent:toParent;
                var second=min(need.subtract(first),amount[donor]);amount[donor]=amount[donor].subtract(second);amount[out]=amount[out].add(second);
                var moved=first.add(second);if(!increase)moved=moved.negate();
                load[own]=value(load[own]).add(moved);load[peer]=value(load[peer]).subtract(moved);
            }
            return amount;
        }
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

    /** One component graph: binary64 projection, followed by exact submission only where needed. */
    private static final class CapacityProjection {
        private static final int AUXILIARY=0,ROUTE=1,RECEIVER=2,GROUP=3;
        private final RouteBuffer raw;
        private final SignedDyadic[] upper,lower,groupHigh;
        private final double[] initialAmount,load;
        private final int[] receiverGroup,receiverVertex;
        private final IntConsumer alternatives;
        private final boolean[] expanded,expandedReceiver,expandedAggregate;
        private final int[] firstIncoming,firstReceiver,nextReceiver;
        private int[] nextIncoming;
        private final int firstSource=2,firstGroup,pool;
        private int vertices,edges,receivers;
        private int[] head,level,cursor,queue,pathVertex,pathEdge,receiverSlot;
        private int[] to=new int[64],next=new int[64],kind=new int[64],index=new int[64],routeEdge;
        private double[] remaining=new double[64];
        private SignedDyadic[] committed,commitLoad,commitGroupLoad,exactRemaining;
        private boolean[] exactReady;
        private boolean committing;

        CapacityProjection(RouteBuffer raw,double[] amount,double[] load,SignedDyadic[] upper,SignedDyadic[] lower,
                int[] receiverGroup,double[] groupLoad,SignedDyadic[] groupHigh,
                int[] loadTerms,int[] groupTerms,int[] touched,int touchedCount,IntConsumer alternatives){
            this.raw=raw;this.upper=upper;this.lower=lower;this.groupHigh=groupHigh;
            this.receiverGroup=receiverGroup;this.alternatives=alternatives;initialAmount=amount;this.load=load;
            expanded=new boolean[raw.sources];receiverVertex=new int[upper.length];
            int groups=groupHigh==null?0:groupHigh.length;
            expandedReceiver=new boolean[upper.length];expandedAggregate=new boolean[groups+1];
            firstIncoming=new int[upper.length];Arrays.fill(firstIncoming,-1);nextIncoming=new int[Math.max(raw.size,amount.length)];
            firstReceiver=new int[groups+1];Arrays.fill(firstReceiver,-1);nextReceiver=new int[upper.length];Arrays.fill(nextReceiver,-2);
            firstGroup=firstSource+raw.sources;pool=firstGroup+groups;vertices=pool+1;
            int capacity=Math.max(16,vertices+2*raw.sources);
            head=new int[capacity];Arrays.fill(head,-1);level=new int[capacity];cursor=new int[capacity];queue=new int[capacity];
            pathVertex=new int[capacity];pathEdge=new int[capacity];receiverSlot=new int[capacity];Arrays.fill(receiverSlot,-1);
            routeEdge=new int[Math.max(32,raw.size)];Arrays.fill(routeEdge,-1);
            for(int r=0;r<amount.length;r++)if(amount[r]>0){
                int slot=raw.target[r];nextIncoming[r]=firstIncoming[slot];firstIncoming[slot]=r;
            }
            double[] receiverExcess=new double[groups];double poolNeed=0,poolScale=0;int poolTerms=1;
            for(int i=0;i<touchedCount;i++){
                int slot=touched[i];listReceiver(slot);double base=base(slot),excess=load[slot]-base;
                if(!exceedsAccumulationError(load[slot],base,loadTerms[slot]))excess=0;
                poolScale+=Math.abs(load[slot]);poolTerms=addTerms(poolTerms,addTerms(loadTerms[slot],1));
                if(excess>0)pair(0,receiver(slot),excess,0,AUXILIARY,0);
                else if(excess<0)pair(receiver(slot),1,-excess,0,AUXILIARY,0);
                int group=receiverGroup==null?-1:receiverGroup[slot];
                if(group<0)poolNeed+=excess;else receiverExcess[group]+=excess;
            }
            for(int g=0;g<groups;g++){
                double high=groupHigh[g].toDouble(),excess=groupLoad[g]>high&&exceedsAccumulationError(groupLoad[g],high,groupTerms[g])?groupLoad[g]-high:0;
                double base=Math.min(high,groupLoad[g]);
                pair(firstGroup+g,pool,high-base,base,GROUP,g);
                double balance=excess-receiverExcess[g];
                if(Math.abs(balance)<=Math.ulp(Math.max(Math.abs(groupLoad[g]),Math.abs(high)))*(groupTerms[g]+2.0))balance=0;
                if(balance>0)pair(0,firstGroup+g,balance,0,AUXILIARY,0);
                else if(balance<0)pair(firstGroup+g,1,-balance,0,AUXILIARY,0);
                poolNeed+=excess;
            }
            if(Math.abs(poolNeed)<=Math.ulp(poolScale)*(poolTerms+2.0))poolNeed=0;
            if(poolNeed>0)pair(pool,1,poolNeed,0,AUXILIARY,0);
            else if(poolNeed<0)pair(0,pool,-poolNeed,0,AUXILIARY,0);
        }
        private double minimum(int slot){return lower==null||lower[slot]==null?0:lower[slot].toDouble();}
        private double base(int slot){return Math.max(minimum(slot),Math.min(load[slot],upper[slot].toDouble()));}
        private SignedDyadic exactMinimum(int slot){return lower==null||lower[slot]==null?SignedDyadic.ZERO:lower[slot];}
        private SignedDyadic exactBase(int slot){var amount=min(value(commitLoad[slot]),upper[slot]);return amount.compareTo(exactMinimum(slot))<0?exactMinimum(slot):amount;}
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
            int vertex=vertices++;receiverVertex[slot]=vertex;receiverSlot[vertex]=slot;level[vertex]=-1;receivers++;
            listReceiver(slot);
            int group=receiverGroup==null?-1:receiverGroup[slot];double base=base(slot);
            pair(vertex,group<0?pool:firstGroup+group,upper[slot].toDouble()-base,base-minimum(slot),RECEIVER,slot);
            return vertex;
        }
        private int pair(int from,int target,double forward,double reverse,int type,int owner){
            if(edges+2>to.length){
                int n=2*to.length;to=Arrays.copyOf(to,n);next=Arrays.copyOf(next,n);remaining=Arrays.copyOf(remaining,n);
                kind=Arrays.copyOf(kind,n);index=Arrays.copyOf(index,n);
                if(committing){exactRemaining=Arrays.copyOf(exactRemaining,n);exactReady=Arrays.copyOf(exactReady,n);}
            }
            int edge=edges;kind[edge]=type;index[edge]=owner;
            to[edges]=target;next[edges]=head[from];remaining[edges]=forward;head[from]=edges++;
            to[edges]=from;next[edges]=head[target];remaining[edges]=reverse;head[target]=edges++;
            return edge;
        }
        private void commitPair(int from,int target,SignedDyadic forward){
            int edge=pair(from,target,0,0,AUXILIARY,0);
            exactRemaining[edge]=forward;exactRemaining[edge^1]=SignedDyadic.ZERO;exactReady[edge]=true;
        }
        private void expand(int vertex){
            int source=vertex-firstSource;
            if(source>=0&&source<raw.sources){
                if(expanded[source])return;expanded[source]=true;alternatives.accept(source);
                ensureRouteCapacity();
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
            for(int r=firstReceiver[aggregate];r>=0;r=nextReceiver[r]){
                if(receiverVertex[r]!=0)continue;
                // Only positive reverse capacity leads out of this aggregate.
                // Physical donor routes instantiate every other receiver on demand.
                if(committing?exactBase(r).compareTo(exactMinimum(r))>0:base(r)>minimum(r))receiver(r);
            }
        }
        private void ensureRouteCapacity(){
            if(routeEdge.length>=raw.size)return;
            int old=routeEdge.length;routeEdge=Arrays.copyOf(routeEdge,Math.max(raw.size,2*old));Arrays.fill(routeEdge,old,routeEdge.length,-1);
        }
        private void route(int r){
            if(routeEdge[r]>=0)return;
            int target=receiver(raw.target[r]);
            routeEdge[r]=pair(firstSource+raw.source[r],target,Double.POSITIVE_INFINITY,
                    r<initialAmount.length?initialAmount[r]:0,ROUTE,r);
        }

        /** Reuse the projected graph; only actual endpoint rounding creates submission debts. */
        void prepareCommit(SignedDyadic[] amount,SignedDyadic[] load,SignedDyadic[] groupLoad,int[] touched,int touchedCount){
            committing=true;committed=amount;commitLoad=load;commitGroupLoad=groupLoad;
            exactRemaining=new SignedDyadic[to.length];exactReady=new boolean[to.length];
            Arrays.fill(expanded,false);Arrays.fill(expandedReceiver,false);Arrays.fill(expandedAggregate,false);
            Arrays.fill(firstIncoming,-1);nextIncoming=new int[raw.size];ensureRouteCapacity();
            for(int r=0;r<raw.size;r++)if(amount[r].signum()>0){
                int slot=raw.target[r];nextIncoming[r]=firstIncoming[slot];firstIncoming[slot]=r;
            }
            var receiverExcess=new SignedDyadic[groupLoad.length];Arrays.fill(receiverExcess,SignedDyadic.ZERO);
            var poolNeed=SignedDyadic.ZERO;
            for(int i=0;i<touchedCount;i++){
                int slot=touched[i];listReceiver(slot);var base=exactBase(slot);
                var excess=load[slot]==base?SignedDyadic.ZERO:load[slot].subtract(base);
                if(excess.signum()>0)commitPair(0,receiver(slot),excess);
                else if(excess.signum()<0)commitPair(receiver(slot),1,excess.negate());
                int group=receiverGroup==null?-1:receiverGroup[slot];
                if(group<0)poolNeed=poolNeed.add(excess);else receiverExcess[group]=receiverExcess[group].add(excess);
            }
            for(int g=0;g<groupLoad.length;g++){
                var excess=groupLoad[g].compareTo(groupHigh[g])>0?groupLoad[g].subtract(groupHigh[g]):SignedDyadic.ZERO;
                var balance=excess.subtract(receiverExcess[g]);
                if(balance.signum()>0)commitPair(0,firstGroup+g,balance);
                else if(balance.signum()<0)commitPair(firstGroup+g,1,balance.negate());
                poolNeed=poolNeed.add(excess);
            }
            if(poolNeed.signum()>0)commitPair(pool,1,poolNeed);
            else if(poolNeed.signum()<0)commitPair(0,pool,poolNeed.negate());
        }
        private SignedDyadic exactCapacity(int edge){
            int pair=edge&~1;
            if(!exactReady[pair]){
                exactReady[pair]=true;int owner=index[pair];
                switch(kind[pair]){
                    case ROUTE -> {
                        exactRemaining[pair]=null;
                        exactRemaining[pair^1]=owner<committed.length?committed[owner]:SignedDyadic.ZERO;
                    }
                    case RECEIVER -> {
                        var base=exactBase(owner);
                        exactRemaining[pair]=upper[owner].subtract(base);
                        exactRemaining[pair^1]=base.subtract(exactMinimum(owner));
                    }
                    case GROUP -> {
                        var base=min(commitGroupLoad[owner],groupHigh[owner]);
                        exactRemaining[pair]=groupHigh[owner].subtract(base);exactRemaining[pair^1]=base;
                    }
                    default -> {exactRemaining[pair]=SignedDyadic.ZERO;exactRemaining[pair^1]=SignedDyadic.ZERO;}
                }
            }
            return exactRemaining[edge];
        }
        private boolean positive(int edge){
            if(!committing)return remaining[edge]>0;
            var capacity=exactCapacity(edge);return capacity==null||capacity.signum()>0;
        }
        void solve(){
            while(levels()){
                System.arraycopy(head,0,cursor,0,vertices);
                while(augment()) { }
            }
            // Numerical cancellation is settled against the actual endpoint quantities.
            // Only the submission graph decides exact capacity feasibility.
            if(committing)for(int e=head[0];e>=0;e=next[e])if(positive(e))
                throw new ConservativeLiquidFlux.CapacityUnavailable("Liquid inventory has insufficient reachable pore capacity: residual="
                        +exactCapacity(e).toDouble()+", "+location(to[e])+", sources="+raw.sources
                        +", receivers="+receivers+", routes="+raw.size);
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
            if(committing){
                SignedDyadic amount=null;
                for(int i=0;i<depth;i++){
                    var capacity=exactCapacity(pathEdge[i]);if(capacity!=null)amount=amount==null?capacity:min(amount,capacity);
                }
                for(int i=0;i<depth;i++){
                    int edge=pathEdge[i];var forward=exactCapacity(edge);var reverse=exactCapacity(edge^1);
                    if(forward!=null)exactRemaining[edge]=forward.subtract(amount);
                    if(reverse!=null)exactRemaining[edge^1]=reverse.add(amount);
                }
            }else{
                double amount=Double.POSITIVE_INFINITY;int bottleneck=-1;
                for(int i=0;i<depth;i++)if(remaining[pathEdge[i]]<amount){amount=remaining[pathEdge[i]];bottleneck=pathEdge[i];}
                for(int i=0;i<depth;i++){
                    int edge=pathEdge[i];
                    if(Double.isFinite(remaining[edge]))remaining[edge]=edge==bottleneck?0:Math.max(0,remaining[edge]-amount);
                    if(Double.isFinite(remaining[edge^1]))remaining[edge^1]+=amount;
                }
            }
        }
        double numericalAmount(int route){int edge=routeEdge[route];return edge<0?(route<initialAmount.length?initialAmount[route]:0):remaining[edge^1];}
        SignedDyadic committedAmount(int route){int edge=routeEdge[route];return edge<0?(route<committed.length?committed[route]:SignedDyadic.ZERO):exactCapacity(edge^1);}
    }
}
