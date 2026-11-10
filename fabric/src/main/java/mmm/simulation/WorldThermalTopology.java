package mmm.simulation;

import java.util.*;
import mmm.thermal.*;
import mmm.units.Mass;
import mmm.units.SignedDyadic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Samples solid and connected air microboxes into a finite-volume thermal graph. */
final class WorldThermalTopology {
    private static final int N=4;
    private static final double TEMPERATURE=293.15, AIR_MIXING=1.0, FILM=5.0;
    private final CommittedWorldRegistries definitions;
    private final BlockGetter currentWorld;
    final Map<EnvironmentThermalState.Key, EnvironmentThermalState.Node> old;
    final List<EnvironmentThermalState.Node> nodes = new ArrayList<>();
    final Map<BlockPos, int[]> cells = new HashMap<>();
    final Map<BlockPos, List<EnvironmentThermalState.Key>> oldAt = new HashMap<>();
    record EdgeKey(int a, int b, double temperature) { }
    final Map<EdgeKey, Double> edges = new HashMap<>();
    final Map<BlockPos, Double> fixed = new HashMap<>();
    final Map<BlockPos, Direction.Axis> screenAxes = new HashMap<>();
    final Map<BlockPos, Integer> screenNodes = new HashMap<>();
    final Map<BlockPos, Double> screenOpen = new HashMap<>();
    final Set<BlockPos> pipeScreens = new HashSet<>();
    final Map<Integer, mmm.fluid.air.GasInventory> defaultAir = new HashMap<>();
    final Map<Integer, Double> defaultAirEnergy = new HashMap<>();
    SignedDyadic added = SignedDyadic.ZERO, removed = SignedDyadic.ZERO;
    WorldThermalTopology(CommittedWorldRegistries definitions, BlockGetter currentWorld, Map<EnvironmentThermalState.Key, EnvironmentThermalState.Node> old) {
        this.definitions=definitions; this.currentWorld=currentWorld;
        this.old = old;
        for (var key : old.keySet()) oldAt.computeIfAbsent(new BlockPos(key.x(), key.y(), key.z()), p -> new ArrayList<>()).add(key);
    }
    void boundary(BlockPos pos, double temperature) {
        int[] blocked = new int[64]; Arrays.fill(blocked, -1); cells.put(pos, blocked);
        if (Double.isFinite(temperature)) fixed.put(pos, temperature);
    }
    void unloaded(BlockPos pos) {
        int[] blocked = new int[64]; Arrays.fill(blocked, -1); cells.put(pos, blocked);
        for (var key : oldAt.getOrDefault(pos, List.of())) nodes.add(old.remove(key));
    }
    void add(BlockPos pos, BlockState state, byte[] masks, boolean mechanical) {
        boolean[] solid = new boolean[64]; int solidCount = 0;
        for (int z=0;z<N;z++) for (int y=0;y<N;y++) for (int x=0;x<N;x++) {
            int cell=((z/2)*2+y/2)*2+x/2, bit=(x&1)+2*((y&1)+2*(z&1));
            solid[index(x,y,z)] = (masks[cell] & (1 << bit)) != 0;
            if (solid[index(x,y,z)]) solidCount++;
        }
        int[] ids = new int[64]; Arrays.fill(ids,-1);
        boolean pipeScreen=state.is(mmm.block.MmmBlocks.PIPE_SCREEN);
        boolean isScreen=state.is(mmm.block.MmmBlocks.SCREEN) || pipeScreen;
        if(pipeScreen)pipeScreens.add(pos);
        if (isScreen) {
            var axis=state.getValue(mmm.block.ScreenBlock.FACING).getAxis(); screenAxes.put(pos,axis);
            double open = worldScreenOpen(pos);
            screenOpen.put(pos,open);
            var def=definitions.thermalRegistry().finiteDevice("mmm:screen");
            double mass=def.bodyMassKilograms()*(pipeScreen?.25:1);
            double energy=new ThermalStateFunction(definitions.thermalRegistry()).internalEnergyAtTemperature(
                    definitions.thermalRegistry().materialHandle(def.thermalMaterialId()),mass,Map.of(),TEMPERATURE);
            var key=new EnvironmentThermalState.Key(pos.getX(),pos.getY(),pos.getZ(),EnvironmentThermalState.Kind.SOLID,pipeScreen?1:0);
            screenNodes.put(pos,addNode(new EnvironmentThermalState.Node(key,def.thermalMaterialId(),Mass.fromKilograms(mass),pipeScreen?.03125:.125,
                    mmm.fluid.air.GasInventory.EMPTY,energy,List.of(),axis.name())));
        }
        if (solidCount > 0 && (!isScreen || pipeScreen)) {
            var def = BlockThermalMaterials.resolve(state, definitions.thermalRegistry());
            // Port covers and the valve shutter remain part of the same body as their shape moves.
            boolean movingPipeParts=state.getBlock() instanceof mmm.block.PipeBlock || state.getBlock() instanceof mmm.block.PipeValveBlock;
            double volume = movingPipeParts ? .875 : solidCount / 64.0;
            long mass = Mass.fromKilograms(def.bodyMassKilograms() * volume);
            double energy = new ThermalStateFunction(definitions.thermalRegistry()).internalEnergyAtTemperature(definitions.thermalRegistry().materialHandle(def.thermalMaterialId()), Mass.kilograms(mass), Map.of(), TEMPERATURE);
            var key = new EnvironmentThermalState.Key(pos.getX(),pos.getY(),pos.getZ(),EnvironmentThermalState.Kind.SOLID,0);
            int id = addNode(new EnvironmentThermalState.Node(key,def.thermalMaterialId(),mass,volume,mmm.fluid.air.GasInventory.EMPTY,energy,List.of()));
            for (int i=0;i<64;i++) if (solid[i]) ids[i]=id;
        }
        if (!mechanical) {
            int component=0;
            for (int i=0;i<64;i++) if (!solid[i] && ids[i] == -1) {
                var queue = new ArrayDeque<Integer>(); var members = new ArrayList<Integer>(); queue.add(i); ids[i]=-2;
                while (!queue.isEmpty()) { int at=queue.remove(); members.add(at); int x=at%N,y=at/N%N,z=at/(N*N);
                    for (var dir:Direction.values()) { int xx=x+dir.getStepX(),yy=y+dir.getStepY(),zz=z+dir.getStepZ();
                        if(xx<0||yy<0||zz<0||xx>=N||yy>=N||zz>=N)continue; int next=index(xx,yy,zz);
                        var axis=screenAxes.get(pos);
                        if(axis!=null && screenCrossesAt(pos,axis,x,y,z,xx,yy,zz))continue;
                        if(!solid[next]&&ids[next]==-1){ids[next]=-2;queue.add(next);}
                    }
                }
                double volume=members.size()/64.0; var gasModel=new GasThermalModel(definitions.thermalRegistry(),definitions.gasRegistry());
                var gas=defaultAir.computeIfAbsent(members.size(), ignored -> gasModel.inventoryAtPressure(volume,
                        mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA,TEMPERATURE,definitions.gasRegistry().ambient()));
                var key=new EnvironmentThermalState.Key(pos.getX(),pos.getY(),pos.getZ(),EnvironmentThermalState.Kind.AIR,component++);
                int id=addNode(new EnvironmentThermalState.Node(key,"mmm:air",gas.totalMassUnits(),volume,gas,defaultAirEnergy.computeIfAbsent(members.size(),ignored -> gasModel.internalEnergyAtTemperature(gas,TEMPERATURE)),List.of()));
                for (int at:members) ids[at]=id;
            }
        }
        cells.put(pos,ids);
    }
    int addNode(EnvironmentThermalState.Node fresh) {
        var prior=old.remove(fresh.key());
        if (prior != null && prior.materialId().equals(fresh.materialId()) && prior.volumeCubicMetres()==fresh.volumeCubicMetres()
                && (fresh.key().kind()==EnvironmentThermalState.Kind.AIR || prior.massUnits()==fresh.massUnits())) {
            fresh=new EnvironmentThermalState.Node(prior.key(),prior.materialId(),prior.massUnits(),prior.volumeCubicMetres(),
                    prior.gas(),prior.internalEnergyJ(),prior.surfaces(),fresh.owner());
        }
        else { if(prior!=null)removed=removed.add(SignedDyadic.of(prior.internalEnergyJ())); added=added.add(SignedDyadic.of(fresh.internalEnergyJ())); }
        int index=nodes.size();nodes.add(fresh);return index;
    }
    void connect() {
        for (var entry:cells.entrySet()) {
            var pos=entry.getKey(); var ids=entry.getValue();
            for(int z=0;z<N;z++)for(int y=0;y<N;y++)for(int x=0;x<N;x++) {
                int a=ids[index(x,y,z)]; if(a<0)continue;
                for(var dir:Direction.values()) {
                    int xx=x+dir.getStepX(),yy=y+dir.getStepY(),zz=z+dir.getStepZ(); boolean outside=xx<0||yy<0||zz<0||xx>=N||yy>=N||zz>=N;
                    if(!outside && dir.getAxisDirection()==Direction.AxisDirection.NEGATIVE)continue;
                    int[] other=ids;
                    if(outside){ other=cells.get(pos.relative(dir)); if(other==null){addEdge(a,-1,conductance(a,-1)/16.0,TEMPERATURE);continue;}
                        Double fixedTemperature = fixed.get(pos.relative(dir));
                        if (fixedTemperature != null) { addEdge(a,-1,conductance(a,-1)/16.0,fixedTemperature); continue; }
                        if(dir.getAxisDirection()==Direction.AxisDirection.NEGATIVE)continue;
                        xx=Math.floorMod(xx,N);yy=Math.floorMod(yy,N);zz=Math.floorMod(zz,N); }
                    int b=other[index(xx,yy,zz)];
                    var axis=screenAxes.get(pos);
                    boolean screenFace=!outside && axis!=null && screenCrossesAt(pos,axis,x,y,z,xx,yy,zz);
                    if(b>=0&&a!=b)addEdge(a,b,conductance(a,b)/16.0*(screenFace?screenOpen.get(pos):1),TEMPERATURE);
                    if(screenFace) {
                        int screen=screenNodes.get(pos); double area=(1-screenOpen.get(pos))/16.0;
                        if(a>=0)addEdge(a,screen,conductance(a,screen)*area,TEMPERATURE);
                        if(b>=0)addEdge(b,screen,conductance(b,screen)*area,TEMPERATURE);
                    }
                }
            }
        }
    }
    double conductance(int a,int b) {
        var left=nodes.get(a); var right=b<0?null:nodes.get(b);
        boolean la=left.key().kind()==EnvironmentThermalState.Kind.AIR, ra=right==null||right.key().kind()==EnvironmentThermalState.Kind.AIR;
        if(la&&ra)return AIR_MIXING;
        double lk=la?0:conductivity(left), rk=ra?0:conductivity(right);
        if(!la&&lk==0 || !ra&&rk==0)return 0;
        return 1.0/((la?1/FILM:thickness(left)/lk)+(ra?1/FILM:thickness(right)/rk));
    }
    boolean screenCrossesAt(BlockPos pos,Direction.Axis axis,int x,int y,int z,int xx,int yy,int zz) {
        if(!screenCrosses(axis,x,y,z,xx,yy,zz))return false;
        if(!pipeScreens.contains(pos))return true;
        int[] p={x,y,z}; int normal=axis==Direction.Axis.X?0:axis==Direction.Axis.Y?1:2;
        return p[(normal+1)%3]>=1 && p[(normal+1)%3]<=2 && p[(normal+2)%3]>=1 && p[(normal+2)%3]<=2;
    }
    boolean screenCrosses(Direction.Axis axis,int x,int y,int z,int xx,int yy,int zz) {
        int a=axis==Direction.Axis.X?x:axis==Direction.Axis.Y?y:z;
        int b=axis==Direction.Axis.X?xx:axis==Direction.Axis.Y?yy:zz;
        return (a<2)!=(b<2);
    }
    double worldScreenOpen(BlockPos pos) {
        var entity = currentWorld.getBlockEntity(pos);
        return entity instanceof mmm.block.ScreenBlockEntity screen ? screen.openFraction() : 0.6;
    }
    double thickness(EnvironmentThermalState.Node n){return n.materialId().equals("mmm:screen") ? 0.002 : 0.5;}
    double conductivity(EnvironmentThermalState.Node n){return definitions.thermalRegistry().material(definitions.thermalRegistry().materialHandle(n.materialId())).conductivityWPerMeterK();}
    void addEdge(int a,int b,double g,double temperature) {if(g<=0)return; if(b>=0&&a>b){int t=a;a=b;b=t;} edges.merge(new EdgeKey(a,b,temperature),g,Double::sum);}
    Map<EnvironmentThermalState.Key,Long> airMasks() {
        var result=new HashMap<EnvironmentThermalState.Key,Long>();
        for(var ids:cells.values()) for(int i=0;i<ids.length;i++) if(ids[i]>=0) {
            var key=nodes.get(ids[i]).key();
            if(key.kind()==EnvironmentThermalState.Kind.AIR) result.merge(key,1L<<i,(a,b)->a|b);
        }
        return Map.copyOf(result);
    }
    List<EnvironmentThermalState.Contact> contacts(){var result=new ArrayList<EnvironmentThermalState.Contact>();for(var e:edges.entrySet())result.add(new EnvironmentThermalState.Contact(e.getKey().a(),e.getKey().b(),e.getValue(),e.getKey().temperature()));return List.copyOf(result);}
    private static int index(int x,int y,int z){return (z*N+y)*N+x;}
}
