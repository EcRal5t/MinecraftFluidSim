package mmm.simulation;

import mmm.world.MovingBodyFluidCoupling;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Persistent configuration for the prescribed mechanical driver. */
public final class MinecraftProcessOperations {
    record Drive(long start,long end,int x,int y,int z,double cellSize,int axis,Long anchor) {}
    private final FluidRegionManager regions;
    private final FluidWorldTargetResolver targets;
    private final WorldStartupCoordinator.Result startup;
    private final Path path;
    private final Map<UUID,Drive> drives=new HashMap<>();

    MinecraftProcessOperations(Path worldRoot,FluidRegionManager regions,FluidWorldTargetResolver targets,
            WorldStartupCoordinator.Result startup){
        this.regions=regions;this.targets=targets;this.startup=startup;
        path=worldRoot.resolve("mmm").resolve("processes.properties");load();
    }
    public String startDrive(ServerPlayer player,int seconds){
        var region=selected(player);if(region==null)return "请选择玩家所在维度的已加载区域";
        if(!startup.authoritativeWritesReady())return "世界状态尚未允许写入";
        var cell=targets.aimedCellForEdit(player,region,false);if(cell==null)return "请对准区域内部的开放格";
        double h=region.solverConfig().cellSize();
        long start=region.tick(),end=Math.addExact(start,Math.multiplyExact((long)seconds,20L));
        setDrive(region.id(),new Drive(start,end,cell.x(),cell.y(),cell.z(),h,0,null));region.wakeForActivity();
        return "已启动 "+seconds+" 秒往复驱动；驱动体位于格 "+cell.x()+","+cell.y()+","+cell.z()+"，实际外功记入机械账";
    }
    public String startDriveFromBlock(ServerPlayer player,net.minecraft.core.BlockPos position,net.minecraft.core.Direction outward){
        var region=selected(player);if(region==null)return "请选择玩家所在维度的已加载区域";
        if(!startup.authoritativeWritesReady())return "世界状态尚未允许写入";
        var footprint=region.footprint();
        var interior=footprint==null?null:footprint.adjacentInteriorAlong(position,outward.getOpposite());
        if(interior==null)return "驱动方块外侧需朝向已选择区域的内部";
        var cell=FluidWorldTargetResolver.cellAtInteriorFace(region,interior,outward);
        if(cell==null)return "驱动方块内侧没有开放格";
        int axis=outward.getAxis()==net.minecraft.core.Direction.Axis.X?0:outward.getAxis()==net.minecraft.core.Direction.Axis.Y?1:2;
        if(region.solidMicroMask(cell[0],cell[1],cell[2])==255)return "驱动方块内侧没有开放格";
        long start=region.tick();setDrive(region.id(),new Drive(start,Math.addExact(start,200),cell[0],cell[1],cell[2],region.solverConfig().cellSize(),axis,position.asLong()));
        region.wakeForActivity();return "驱动方块已启动 10 秒，实际外功记入机械账";
    }
    public String stopDriveFromBlock(ServerPlayer player,net.minecraft.core.BlockPos position){
        if(!startup.authoritativeWritesReady())return "世界状态尚未允许写入";
        return stopDriveAt(player.level().dimension(),position)?"已停止机械驱动":"该驱动方块未运行";
    }
    boolean stopDriveAt(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,net.minecraft.core.BlockPos position){
        for(var region:regions.regions()){
            var drive=drives.get(region.id());
            if(!region.dimension().equals(dimension)||drive==null||!Objects.equals(drive.anchor(),position.asLong()))continue;
            drives.remove(region.id());save();return true;
        }
        return false;
    }
    public String stop(ServerPlayer player){
        var region=selected(player);if(region==null)return "请选择玩家所在维度的已加载区域";
        if(!startup.authoritativeWritesReady())return "世界状态尚未允许写入";
        clear(region.id());return "已停止机械驱动";
    }
    public String status(ServerPlayer player){
        var region=selected(player);if(region==null)return "请选择玩家所在维度的已加载区域";
        return "机械驱动="+(drives.containsKey(region.id())?"运行":"停止")
                +"，区域代="+region.solverGeneration()+"，checkpoint="+region.checkpointGeneration();
    }
    public String sample(ServerPlayer player){
        var region=selected(player);if(region==null)return "请选择玩家所在维度的已加载区域";
        var cell=targets.aimedCellForEdit(player,region,true);if(cell==null)cell=targets.aimedCellForEdit(player,region,false);
        if(cell==null)return "请对准区域内部";
        return "格 "+cell.x()+","+cell.y()+","+cell.z()+" 液体="+region.observeCellInventory(cell.x(),cell.y(),cell.z())
                +" 其他载体="+region.observeCellCarriers(cell.x(),cell.y(),cell.z());
    }
    private FluidRegion selected(ServerPlayer player){
        var region=regions.selected();return region!=null&&region.solverLoaded()&&region.dimension().equals(player.level().dimension())?region:null;
    }
    void tick(MinecraftServer server){
        boolean changed=false;
        for(var entry:List.copyOf(drives.entrySet())){
            var drive=entry.getValue();if(drive.anchor()==null)continue;
            var region=regions.region(entry.getKey());if(region==null)continue;
            var level=server.getLevel(region.dimension());var position=net.minecraft.core.BlockPos.of(drive.anchor());
            if(level!=null&&MinecraftGroupAvailability.loaded(level,position)
                    &&(!level.getBlockState(position).is(mmm.block.MmmBlocks.MECHANICAL_DRIVER)
                    ||region.footprint()==null||!region.footprint().boundaryBlocks().contains(drive.anchor()))){
                drives.remove(entry.getKey());changed=true;
            }
        }
        if(changed)save();
    }
    List<MovingBodyFluidCoupling.Request> manualBodies(UUID regionId,List<MovingBodyFluidCoupling.Request> previous){
        if(!drives.containsKey(regionId))return List.of();
        UUID driveId=driveId(regionId);
        for(var body:previous)if(body.bodyId().equals(driveId))
            return List.of(new MovingBodyFluidCoupling.Request(body.bodyId(),body.bounds(),0,0,0));
        return List.of();
    }
    private static UUID driveId(UUID regionId){return UUID.nameUUIDFromBytes(("mmm:drive:"+regionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    List<MovingBodyFluidCoupling.Request> bodies(FluidRegion region,long tick){
        var drive=drives.get(region.id());if(drive==null)return List.of();
        if(!region.canStep()){
            drives.put(region.id(),new Drive(Math.addExact(drive.start(),1L),Math.addExact(drive.end(),1L),drive.x(),drive.y(),drive.z(),drive.cellSize(),drive.axis(),drive.anchor()));
            save();return List.of();
        }
        if(tick>=drive.end()){drives.remove(region.id());save();return List.of();}
        if(tick<drive.start())return List.of();
        double h=drive.cellSize(),phase=(tick-drive.start())*Math.PI/20.0,amp=.25*h;
        double[] center={(drive.x()+.5)*h,(drive.y()+.5)*h,(drive.z()+.5)*h};
        center[drive.axis()]+=amp*Math.sin(phase);double r=.2*h;
        double[] velocity={0,0,0};velocity[drive.axis()]=amp*Math.PI*Math.cos(phase);
        var bounds=new MovingBodyFluidCoupling.Bounds(center[0]-r,center[1]-r,center[2]-r,center[0]+r,center[1]+r,center[2]+r);
        return List.of(new MovingBodyFluidCoupling.Request(driveId(region.id()),bounds,velocity[0],velocity[1],velocity[2]));
    }
    Drive drive(UUID regionId){return drives.get(regionId);}
    void setDrive(UUID regionId,Drive drive){drives.put(regionId,drive);save();}
    void clear(UUID regionId){
        boolean changed=drives.remove(regionId)!=null;
        if(changed)save();
    }
    void regionRemoved(FluidRegion region){clear(region.id());}
    private void load(){
        if(!Files.exists(path))return;
        var p=new Properties();try(var input=Files.newInputStream(path)){p.load(input);
            for(String key:p.stringPropertyNames()){
                int split=key.indexOf('.');UUID region=UUID.fromString(key.substring(0,split));String[] v=p.getProperty(key).split(",");
                if(key.endsWith(".drive"))drives.put(region,new Drive(Long.parseLong(v[0]),Long.parseLong(v[1]),Integer.parseInt(v[2]),Integer.parseInt(v[3]),Integer.parseInt(v[4]),Double.parseDouble(v[5]),Integer.parseInt(v[6]),v[7].equals("-")?null:Long.parseLong(v[7])));
            }
        }catch(IOException|RuntimeException failure){throw new IllegalStateException("工艺配置无法恢复："+path,failure);}
    }
    private void save(){
        var p=new Properties();
        drives.forEach((id,d)->p.setProperty(id+".drive",d.start()+","+d.end()+","+d.x()+","+d.y()+","+d.z()+","+d.cellSize()+","+d.axis()+","+(d.anchor()==null?"-":d.anchor())));
        try{Files.createDirectories(path.getParent());Path temporary=path.resolveSibling(path.getFileName()+".tmp");
            try(var out=Files.newOutputStream(temporary)){p.store(out,"MMM process settings");}
            try{Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unavailable){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException failure){throw new IllegalStateException("工艺配置保存失败："+path,failure);}
    }
}
