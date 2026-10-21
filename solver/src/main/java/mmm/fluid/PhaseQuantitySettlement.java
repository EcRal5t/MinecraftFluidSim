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
		private boolean[] alternativesPrepared=new boolean[0];
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
                int n=values.size();firstStock=new int[n];nextStock=new int[n];volume=new SignedDyadic[n];numericalVolume=new double[n];alternativesPrepared=new boolean[n];
                head=new int[n];retained=new int[n];preferredTotal=new double[n];
            }
            for(int i=0;i<values.size();i++){
                var stock=values.get(i);int slot=slot(stock.key()),s=sourceAt[slot];
                if(s<0){
                    sourceAt[slot]=s=sources++;firstStock[s]=-1;volume[s]=SignedDyadic.ZERO;numericalVolume[s]=Double.NaN;alternativesPrepared[s]=false;
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
        void prepareAlternatives(int source,IntConsumer alternatives){
            if(!alternativesPrepared[source]){alternatives.accept(source);alternativesPrepared[source]=true;}
        }
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
        for(int i=0;i<touchedCount;i++){
            int slot=touched[i];
            double low=lower==null||lower[slot]==null?0:lower[slot].toDouble(),high=upper[slot].toDouble();
            double endpoint=Math.clamp(load[slot],low,high);
            if(exceedsAccumulationError(load[slot],endpoint,loadTerms[slot])){
                forest=new CapacityForest(raw,lower,upper,numerical,load,alternatives);
                if(numerical.length<raw.size)numerical=Arrays.copyOf(numerical,raw.size);
                touchedCount=forest.project(numerical,load,loadTerms,touched,touchedCount);break;
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
        if(adjust&&hasCapacityDefect(exactLoad,lower,upper,touched,touchedCount)){
            if(forest==null){
                for(int i=0;i<touchedCount;i++)load[touched[i]]=exactLoad[touched[i]].toDouble();
                forest=new CapacityForest(raw,lower,upper,numerical,load,alternatives);
            }
            amount=forest.commit(amount,exactLoad);
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
    private static boolean hasCapacityDefect(SignedDyadic[] load,SignedDyadic[] lower,SignedDyadic[] upper,int[] touched,int touchedCount){
        for(int i=0;i<touchedCount;i++){
            int slot=touched[i];var amount=load[slot];
            if(amount.compareTo(upper[slot])>0||lower!=null&&lower[slot]!=null&&amount.compareTo(lower[slot])<0)return true;
        }
        return false;
    }

    /** All donor supports share one bounded tree; source-free receivers provide real spill storage. */
    private static final class CapacityForest {
        private final RouteBuffer raw;
        private final SignedDyadic[] lower,upper;
        private final int[] own,parent,order,firstChild,nextChild,toParent,fromParent;
        private final int[] emptySlots,firstIncoming,nextIncoming,spillHead,spillNext,spillRoute;
        private final double[] sourceLow,sourceHigh;
        private int emptyCount,count;
        private double[] numerical,load,accept,release;
        private int[] terms,touched;
        private int touchedCount;
        private SignedDyadic[] exact,exactLoad,exactAccept,exactRelease;

        CapacityForest(RouteBuffer raw,SignedDyadic[] lower,SignedDyadic[] upper,double[] amount,double[] load,IntConsumer alternatives){
            this.raw=raw;this.lower=lower;this.upper=upper;
            int n=raw.sources;
            for(int s=0;s<n;s++)raw.prepareAlternatives(s,alternatives);
            own=new int[n];parent=new int[n];order=new int[n];firstChild=new int[n];nextChild=new int[n];
            toParent=new int[n];fromParent=new int[n];sourceLow=new double[n];sourceHigh=new double[n];
            spillHead=new int[n];spillNext=new int[upper.length];spillRoute=new int[upper.length];
            firstIncoming=new int[upper.length];nextIncoming=new int[raw.size];emptySlots=new int[raw.size];
            Arrays.fill(firstChild,-1);Arrays.fill(spillHead,-1);Arrays.fill(spillRoute,-1);Arrays.fill(firstIncoming,-1);
            int[] component=new int[n],best=new int[n],incoming=new int[n],nextSource=new int[raw.size];
            Arrays.fill(best,-1);Arrays.fill(incoming,-1);
            for(int s=0;s<n;s++){
                own[s]=slot(raw.key(s));component[s]=s;
                sourceLow[s]=minimum(own[s]).toDouble();sourceHigh[s]=upper[own[s]].toDouble();
            }
            for(int r=0;r<raw.size;r++){
                int s=raw.source[r],target=raw.sourceAt[raw.target[r]];
                if(target>=0){
                    if(target==s)continue;
                    int a=root(component,s),b=root(component,target);if(a!=b)component[a]=b;
                    nextSource[r]=incoming[target];incoming[target]=r;
                }else{
                    int slot=raw.target[r];if(firstIncoming[slot]<0)emptySlots[emptyCount++]=slot;
                    nextIncoming[r]=firstIncoming[slot];firstIncoming[slot]=r;
                    int previous=spillRoute[slot];
                    if(previous<0||initial(amount,raw.retained[s])+initial(amount,r)
                            >initial(amount,raw.retained[raw.source[previous]])+initial(amount,previous))spillRoute[slot]=r;
                }
            }
            for(int i=0;i<emptyCount;i++){
                int slot=emptySlots[i],s=raw.source[spillRoute[slot]];spillNext[slot]=spillHead[s];spillHead[s]=slot;
            }
            double[] score=new double[n];
            for(int s=0;s<n;s++){
                double spill=0;
                for(int r=raw.head[s];r>=0;r=raw.next[r])if(raw.sourceAt[raw.target[r]]<0)
                    spill+=Math.max(0,upper[raw.target[r]].toDouble()-load[raw.target[r]]);
                score[s]=Math.max(0,sourceHigh[s]-load[own[s]])+Math.min(spill,initial(amount,raw.retained[s]));
                int c=root(component,s),previous=best[c];
                if(previous<0||score[s]>score[previous]||score[s]==score[previous]
                        &&initial(amount,raw.retained[s])>initial(amount,raw.retained[previous]))best[c]=s;
            }
            Arrays.fill(parent,-2);
            for(int s:best)if(s>=0){parent[s]=-1;order[count++]=s;}
            int[] treeFace=new int[n];int visited=0;
            while(visited<count){
                int s=order[visited++];
                for(int pass=0;pass<2;pass++)for(int r=pass==0?raw.head[s]:incoming[s];r>=0;r=pass==0?raw.next[r]:nextSource[r]){
                    int child=pass==0?raw.sourceAt[raw.target[r]]:raw.source[r];
                    if(child<0||parent[child]!=-2)continue;
                    parent[child]=s;treeFace[child]=raw.face[r];order[count++]=child;
                }
            }
            for(int s=0;s<n;s++)if(parent[s]>=0){
                int p=parent[s],face=treeFace[s];
                raw.alternative(s,face,own[p]/8,raw.key(p).supportMask());
                raw.alternative(p,face,own[s]/8,raw.key(s).supportMask());
                toParent[s]=route(s,own[p],face);fromParent[s]=route(p,own[s],face);
                nextChild[s]=firstChild[p];firstChild[p]=s;
            }
        }
        private static double initial(double[] amount,int r){return r<amount.length?amount[r]:0;}
        private static int root(int[] component,int s){
            int r=s;while(component[r]!=r)r=component[r];
            while(component[s]!=s){int next=component[s];component[s]=r;s=next;}return r;
        }
        private SignedDyadic minimum(int slot){return lower==null||lower[slot]==null?SignedDyadic.ZERO:lower[slot];}
        private int route(int source,int target,int face){
            for(int r=raw.head[source];r>=0;r=raw.next[r])if(raw.target[r]==target&&raw.face[r]==face)return r;
            throw new IllegalStateException("Capacity tree lost its open component route");
        }
        private double low(int slot){int s=raw.sourceAt[slot];return s<0?minimum(slot).toDouble():sourceLow[s];}
        private double high(int slot){int s=raw.sourceAt[slot];return s<0?upper[slot].toDouble():sourceHigh[s];}
        private double numericalNeed(int s){
            double current=load[own[s]],target=Math.clamp(current,sourceLow[s],sourceHigh[s]);
            return exceedsAccumulationError(current,target,terms[own[s]])?target-current:0;
        }
        private void moveLoad(int from,int to,double amount){
            if(amount==0)return;
            load[from]-=amount;load[to]+=amount;
            if(terms[from]==0)touched[touchedCount++]=from;
            if(terms[to]==0)touched[touchedCount++]=to;
            int operations=addTerms(addTerms(terms[from],terms[to]),8);terms[from]=terms[to]=operations;
        }
        /** A positive shift moves volume from the parent receiver to its child receiver. */
        private double shift(int child,double wanted){
            int p=parent[child];boolean increase=wanted>0;double need=Math.abs(wanted);
            int cancel=increase?toParent[child]:fromParent[child],retained=increase?raw.retained[child]:raw.retained[p];
            double first=Math.min(need,numerical[cancel]);numerical[cancel]-=first;numerical[retained]+=first;
            int donor=increase?raw.retained[p]:raw.retained[child],out=increase?fromParent[child]:toParent[child];
            double second=Math.min(need-first,numerical[donor]);numerical[donor]-=second;numerical[out]+=second;
            double moved=first+second;
            if(increase)moveLoad(own[p],own[child],moved);else moveLoad(own[child],own[p],moved);
            return increase?moved:-moved;
        }
        /** Source-free receivers clamp by limiting their actual incoming routes. */
        private void prepareEmpty(){
            for(int i=0;i<emptyCount;i++){
                int slot=emptySlots[i];double target=Math.clamp(load[slot],low(slot),high(slot));
                if(!exceedsAccumulationError(load[slot],target,terms[slot]))continue;
                boolean remove=load[slot]>target;double need=Math.abs(load[slot]-target);
                for(int r=firstIncoming[slot];r>=0&&need>0;r=nextIncoming[r]){
                    int s=raw.source[r],retained=raw.retained[s];double amount=Math.min(need,numerical[remove?r:retained]);
                    if(remove){numerical[r]-=amount;numerical[retained]+=amount;moveLoad(slot,own[s],amount);}
                    else{numerical[retained]-=amount;numerical[r]+=amount;moveLoad(own[s],slot,amount);}
                    need-=amount;
                }
            }
        }
        private void useSpill(int s){
            double need=numericalNeed(s);
            for(int r=raw.head[s];r>=0&&need!=0;r=raw.next[r]){
                int slot=raw.target[r];if(raw.sourceAt[slot]>=0)continue;
                int retained=raw.retained[s];
                if(need<0){
                    double amount=Math.min(-need,Math.min(numerical[retained],Math.max(0,high(slot)-load[slot])));
                    numerical[retained]-=amount;numerical[r]+=amount;moveLoad(own[s],slot,amount);need+=amount;
                }else{
                    double amount=Math.min(need,Math.min(numerical[r],Math.max(0,load[slot]-low(slot))));
                    numerical[r]-=amount;numerical[retained]+=amount;moveLoad(slot,own[s],amount);need-=amount;
                }
            }
        }
        int project(double[] amount,double[] load,int[] terms,int[] touched,int touchedCount){
            numerical=amount;this.load=load;this.terms=terms;this.touched=touched;this.touchedCount=touchedCount;
            prepareEmpty();
            for(int i=count-1;i>=0;i--){int s=order[i];useSpill(s);double need=numericalNeed(s);if(parent[s]>=0&&need!=0)shift(s,need);}
            boolean remaining=false;for(int s=0;s<raw.sources;s++)remaining|=numericalNeed(s)!=0;
            if(!remaining)return this.touchedCount;
            accept=new double[raw.sources];release=new double[raw.sources];
            for(int i=count-1;i>=0;i--){
                int s=order[i];double down=sourceHigh[s]-load[own[s]],up=load[own[s]]-sourceLow[s];
                for(int slot=spillHead[s];slot>=0;slot=spillNext[slot]){
                    down+=Math.max(0,high(slot)-load[slot]);
                    up+=Math.min(numerical[spillRoute[slot]],Math.max(0,load[slot]-low(slot)));
                }
                for(int c=firstChild[s];c>=0;c=nextChild[c]){down+=accept[c];up+=release[c];}
                accept[s]=Math.max(0,down);release[s]=Math.max(0,up);
            }
            for(int i=0;i<count;i++){
                int s=order[i];useSpill(s);double need=numericalNeed(s);
                for(int c=firstChild[s];c>=0&&need!=0;c=nextChild[c]){
                    double moved=shift(c,need<0?Math.min(-need,accept[c]):-Math.min(need,release[c]));
                    accept[c]=Math.max(0,accept[c]-moved);release[c]=Math.max(0,release[c]+moved);need+=moved;
                }
            }
            return this.touchedCount;
        }

        private SignedDyadic exactNeed(int s){
            var current=value(exactLoad[own[s]]);var target=min(current,upper[own[s]]);var low=minimum(own[s]);
            if(target.compareTo(low)<0)target=low;return target==current?SignedDyadic.ZERO:target.subtract(current);
        }
        private void moveExactLoad(int from,int to,SignedDyadic amount){
            if(amount.signum()==0)return;
            exactLoad[from]=value(exactLoad[from]).subtract(amount);exactLoad[to]=value(exactLoad[to]).add(amount);
        }
        private SignedDyadic shiftExact(int child,SignedDyadic wanted){
            int p=parent[child];boolean increase=wanted.signum()>0;var need=increase?wanted:wanted.negate();
            int cancel=increase?toParent[child]:fromParent[child],retained=increase?raw.retained[child]:raw.retained[p];
            var first=min(need,exact[cancel]);exact[cancel]=exact[cancel].subtract(first);exact[retained]=exact[retained].add(first);
            int donor=increase?raw.retained[p]:raw.retained[child],out=increase?fromParent[child]:toParent[child];
            var second=min(need.subtract(first),exact[donor]);exact[donor]=exact[donor].subtract(second);exact[out]=exact[out].add(second);
            var moved=first.add(second);
            if(increase)moveExactLoad(own[p],own[child],moved);else moveExactLoad(own[child],own[p],moved);
            return increase?moved:moved.negate();
        }
        private void prepareEmptyExact(){
            for(int i=0;i<emptyCount;i++){
                int slot=emptySlots[i];var current=value(exactLoad[slot]);var low=minimum(slot);var target=min(current,upper[slot]);
                if(target.compareTo(low)<0)target=low;if(target==current)continue;
                boolean remove=current.compareTo(target)>0;var need=remove?current.subtract(target):target.subtract(current);
                for(int r=firstIncoming[slot];r>=0&&need.signum()>0;r=nextIncoming[r]){
                    int s=raw.source[r],retained=raw.retained[s];var amount=min(need,exact[remove?r:retained]);
                    if(remove){exact[r]=exact[r].subtract(amount);exact[retained]=exact[retained].add(amount);moveExactLoad(slot,own[s],amount);}
                    else{exact[retained]=exact[retained].subtract(amount);exact[r]=exact[r].add(amount);moveExactLoad(own[s],slot,amount);}
                    need=need.subtract(amount);
                }
            }
        }
        private void useSpillExact(int s){
            var need=exactNeed(s);
            for(int r=raw.head[s];r>=0&&need.signum()!=0;r=raw.next[r]){
                int slot=raw.target[r];if(raw.sourceAt[slot]>=0)continue;
                int retained=raw.retained[s];var current=value(exactLoad[slot]);
                if(need.signum()<0){
                    var room=upper[slot].subtract(current);if(room.signum()<=0)continue;
                    var amount=min(need.negate(),min(exact[retained],room));
                    exact[retained]=exact[retained].subtract(amount);exact[r]=exact[r].add(amount);moveExactLoad(own[s],slot,amount);need=need.add(amount);
                }else{
                    var room=current.subtract(minimum(slot));if(room.signum()<=0)continue;
                    var amount=min(need,min(exact[r],room));
                    exact[r]=exact[r].subtract(amount);exact[retained]=exact[retained].add(amount);moveExactLoad(slot,own[s],amount);need=need.subtract(amount);
                }
            }
        }
        SignedDyadic[] commit(SignedDyadic[] amount,SignedDyadic[] load){
            exact=amount;exactLoad=load;
            if(exact.length<raw.size){int old=exact.length;exact=Arrays.copyOf(exact,raw.size);Arrays.fill(exact,old,exact.length,SignedDyadic.ZERO);}
            prepareEmptyExact();
            for(int i=count-1;i>=0;i--){int s=order[i];useSpillExact(s);var need=exactNeed(s);if(parent[s]>=0&&need.signum()!=0)shiftExact(s,need);}
            boolean remaining=false;for(int s=0;s<raw.sources;s++)remaining|=exactNeed(s).signum()!=0;
            if(!remaining)return exact;
            exactAccept=new SignedDyadic[raw.sources];exactRelease=new SignedDyadic[raw.sources];
            for(int i=count-1;i>=0;i--){
                int s=order[i];var current=value(load[own[s]]);var down=upper[own[s]].subtract(current);var up=current.subtract(minimum(own[s]));
                for(int slot=spillHead[s];slot>=0;slot=spillNext[slot]){
                    var dry=upper[slot].subtract(value(load[slot]));if(dry.signum()>0)down=down.add(dry);
                    var room=value(load[slot]).subtract(minimum(slot));if(room.signum()>0)up=up.add(min(exact[spillRoute[slot]],room));
                }
                for(int c=firstChild[s];c>=0;c=nextChild[c]){down=down.add(exactAccept[c]);up=up.add(exactRelease[c]);}
                exactAccept[s]=down.signum()>0?down:SignedDyadic.ZERO;exactRelease[s]=up.signum()>0?up:SignedDyadic.ZERO;
            }
            for(int i=0;i<count;i++){
                int s=order[i];useSpillExact(s);var need=exactNeed(s);
                for(int c=firstChild[s];c>=0&&need.signum()!=0;c=nextChild[c]){
                    var wanted=need.signum()<0?min(need.negate(),exactAccept[c]):min(need,exactRelease[c]).negate();
                    var moved=shiftExact(c,wanted);
                    exactAccept[c]=exactAccept[c].subtract(moved);exactRelease[c]=exactRelease[c].add(moved);need=need.add(moved);
                }
            }
            return exact;
        }
    }    /** Both the component face totals and each original material volume close exactly. */
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
                if(expanded[source])return;expanded[source]=true;raw.prepareAlternatives(source,alternatives);
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
