package mmm.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Collects committed world edits for the next region geometry and thermal refresh. */
public final class MinecraftWorldUpdates {
    private final FluidRegionManager regions;
    private WorldThermalEnvironment environment;
    private PipeRuntime pipes;
    void pipes(PipeRuntime value) { pipes=value; }
    void environment(WorldThermalEnvironment value) { environment = java.util.Objects.requireNonNull(value); }

    public MinecraftWorldUpdates(FluidRegionManager regions) {
        this.regions = java.util.Objects.requireNonNull(regions);
    }

    FluidRegionManager regions() { return regions; }
    public void chunksChanged() { if(pipes!=null)pipes.chunksChanged(); }

    public void changed(ServerLevel level, BlockPos position) {
        if(pipes!=null)pipes.changed(level,position);
        if (environment != null) environment.markDirty(level.dimension().identifier().toString(), position);
        for (var region : regions.regions()) {
            if (!region.dimension().equals(level.dimension())) continue;
            var origin = region.origin();
            double h = region.solverConfig().cellSize();
            int x = position.getX() - origin.getX();
            int y = position.getY() - origin.getY();
            int z = position.getZ() - origin.getZ();
            if (x < -1 || y < -1 || z < -1
                    || x > Math.ceil(region.sizeX() * h)
                    || y > Math.ceil(region.sizeY() * h)
                    || z > Math.ceil(region.sizeZ() * h)) continue;
            region.requireCollisionRefresh();
            region.wakeForActivity();
            region.wakeThermal(ThermalActivity.WakeReason.BOUNDARY_CHANGE);
        }
    }
    record Motion(java.util.List<mmm.world.MovingBodyFluidCoupling.Request> source,
                          java.util.List<mmm.world.MovingBodyFluidCoupling.Request> target) { }
    private record TrackedMotion(BlockPos position, boolean pistonHead, net.minecraft.world.level.block.Block block, Motion motion) { }
    private final java.util.Map<java.util.UUID, java.util.Map<java.util.UUID, TrackedMotion>> moving = new java.util.HashMap<>();
    private final java.util.Map<String, java.util.Map<Long, Long>> thermalMoves = new java.util.HashMap<>();
    record WallMove(BlockPos destination, boolean active) { }
    private static final long BODY_PREFIX = 0x4d4d4d5000000000L;

    static boolean worldBody(java.util.UUID id) { return (id.getMostSignificantBits() & 0xffffffff00000000L) == BODY_PREFIX; }

    /** Capture each completed vanilla piston tick, including its final block-entity tick. */
    public void pistonTick(ServerLevel level, BlockPos position, net.minecraft.world.level.block.piston.PistonMovingBlockEntity piston) {
        String dimension=level.dimension().identifier().toString();
        if (environment != null) {
            environment.markDirty(dimension, position);
            if(!piston.isSourcePiston()) {
                var source=position.relative(piston.getMovementDirection().getOpposite());
                if(environment.tracksPosition(dimension,source)||environment.tracksPosition(dimension,position)) {
                    var moves=thermalMoves.computeIfAbsent(dimension,ignored->new java.util.HashMap<>());
                    boolean continued=false;
                    for(var entry:moves.entrySet())if(entry.getValue()==source.asLong()){entry.setValue(position.asLong());continued=true;}
                    if(!continued)moves.put(source.asLong(),position.asLong());
                }
            }
        }
        for (var region : regions.regions()) {
            if (!region.dimension().equals(level.dimension()) || !intersects(region, position)) continue;
            Motion frame = capturePiston(level, position, region.origin(), piston);
            if (frame.source().isEmpty()) continue;
            int pieceCount=frame.source().size();
            var source = position.relative(piston.getMovementDirection().getOpposite());
            var regionMotions = moving.computeIfAbsent(region.id(), ignored -> new java.util.LinkedHashMap<>());
            boolean head=piston.isSourcePiston();var block=piston.getMovedState().getBlock();
            var previous=regionMotions.values().stream().filter(value->value.position().equals(position)
                    && value.pistonHead()==head && (head||value.block()==block)).findFirst().orElse(null);
            if(previous==null) {
                var other=level.getBlockEntity(source);
                boolean stillMoving=other instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity movingPiston
                        && movingPiston.isSourcePiston()==head && (head||movingPiston.getMovedState().getBlock()==block);
                if(!stillMoving)previous=regionMotions.values().stream().filter(value->value.position().equals(source)
                        && value.pistonHead()==head && (head||value.block()==block)
                        && value.motion().source().size()==pieceCount).findFirst().orElse(null);
            }
            if(previous!=null)frame=withIdentity(frame,previous.motion());
            var identity=frame.source().getFirst().bodyId();
            var sourceFrame=previous==null?frame.source():previous.motion().source();
            regionMotions.put(identity,new TrackedMotion(position,head,block,new Motion(sourceFrame,frame.target())));
            region.requireCollisionRefresh();
            region.wakeForActivity();
        }
    }

    static Motion capturePiston(net.minecraft.world.level.BlockGetter level, BlockPos position, BlockPos origin,
                                net.minecraft.world.level.block.piston.PistonMovingBlockEntity piston) {
        var shape = piston.isSourcePiston() ? net.minecraft.world.phys.shapes.Shapes.block()
                : piston.getMovedState().getCollisionShape(level, position);
        var boxes = MinecraftCollisionWorldPort.movingBoxes(shape, FluidRegionFactory.CELLS_PER_BLOCK);
        var first = new java.util.ArrayList<mmm.world.MovingBodyFluidCoupling.Request>();
        var last = new java.util.ArrayList<mmm.world.MovingBodyFluidCoupling.Request>();
        double[] a = {piston.getXOff(0), piston.getYOff(0), piston.getZOff(0)};
        double[] b = {piston.getXOff(1), piston.getYOff(1), piston.getZOff(1)};
        var anchor=piston.isExtending()?position.relative(piston.getDirection().getOpposite()):position;
        for (int i = 0; i < boxes.size(); i++) {
            var box = boxes.get(i);
            var raw = java.util.UUID.nameUUIDFromBytes((anchor.asLong() + ":" + piston.isSourcePiston() + ":" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var id = new java.util.UUID(BODY_PREFIX | (raw.getMostSignificantBits() & 0xffffffffL), raw.getLeastSignificantBits());
            double x = position.getX() - origin.getX(), y = position.getY() - origin.getY(), z = position.getZ() - origin.getZ();
            var before = new mmm.world.MovingBodyFluidCoupling.Bounds(x+box.minX+a[0],y+box.minY+a[1],z+box.minZ+a[2],x+box.maxX+a[0],y+box.maxY+a[1],z+box.maxZ+a[2]);
            var after = new mmm.world.MovingBodyFluidCoupling.Bounds(x+box.minX+b[0],y+box.minY+b[1],z+box.minZ+b[2],x+box.maxX+b[0],y+box.maxY+b[1],z+box.maxZ+b[2]);
            double vx=(b[0]-a[0])*20,vy=(b[1]-a[1])*20,vz=(b[2]-a[2])*20;
            first.add(new mmm.world.MovingBodyFluidCoupling.Request(id,before,vx,vy,vz,x+.5+a[0],y+.5+a[1],z+.5+a[2],0,0,0,-1,0));
            last.add(new mmm.world.MovingBodyFluidCoupling.Request(id,after,vx,vy,vz,x+.5+b[0],y+.5+b[1],z+.5+b[2],0,0,0,-1,0));
        }
        return new Motion(java.util.List.copyOf(first),java.util.List.copyOf(last));
    }

    private static Motion withIdentity(Motion frame,Motion previous) {
        if(frame.source().size()!=previous.source().size())return frame;
        var source=new java.util.ArrayList<mmm.world.MovingBodyFluidCoupling.Request>();
        var target=new java.util.ArrayList<mmm.world.MovingBodyFluidCoupling.Request>();
        for(int i=0;i<frame.source().size();i++) {
            var id=previous.source().get(i).bodyId();
            source.add(withIdentity(frame.source().get(i),id));target.add(withIdentity(frame.target().get(i),id));
        }
        return new Motion(java.util.List.copyOf(source),java.util.List.copyOf(target));
    }
    private static mmm.world.MovingBodyFluidCoupling.Request withIdentity(mmm.world.MovingBodyFluidCoupling.Request value,java.util.UUID id) {
        return new mmm.world.MovingBodyFluidCoupling.Request(id,value.bounds(),value.velocityXMetresPerSecond(),value.velocityYMetresPerSecond(),
                value.velocityZMetresPerSecond(),value.centerXMetres(),value.centerYMetres(),value.centerZMetres(),
                value.angularXPerSecond(),value.angularYPerSecond(),value.angularZPerSecond(),value.responseAxis(),value.inverseMassPerKilogram());
    }

    private static boolean intersects(FluidRegion region, BlockPos position) {
        var origin = region.origin(); double h = region.solverConfig().cellSize();
        return position.getX() >= origin.getX()-1 && position.getY() >= origin.getY()-1 && position.getZ() >= origin.getZ()-1
                && position.getX() <= origin.getX()+Math.ceil(region.sizeX()*h)
                && position.getY() <= origin.getY()+Math.ceil(region.sizeY()*h)
                && position.getZ() <= origin.getZ()+Math.ceil(region.sizeZ()*h);
    }

    WallMove thermalMove(String dimension, BlockPos source, net.minecraft.world.level.BlockGetter world) {
        var moves=thermalMoves.get(dimension); if(moves==null)return null;
        Long target=moves.get(source.asLong()); if(target==null)return null;
        var position=BlockPos.of(target);
        return new WallMove(position,world.getBlockEntity(position) instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity);
    }
    void finishThermalMove(String dimension, BlockPos source) {
        var moves=thermalMoves.get(dimension); if(moves==null)return;
        moves.remove(source.asLong()); if(moves.isEmpty())thermalMoves.remove(dimension);
    }

    boolean movingBlock(FluidRegion region, BlockPos position) {
        var frames = moving.get(region.id());
        return frames != null && frames.values().stream().anyMatch(frame->frame.position().equals(position));
    }

    boolean hasPendingMotion(FluidRegion region) {
        var frames = moving.get(region.id());
        return frames != null && !frames.isEmpty();
    }

    java.util.List<mmm.world.MovingBodyFluidCoupling.Request> targetBodies(FluidRegion region) {
        var frames = moving.get(region.id());
        if (frames == null) return java.util.List.of();
        var result = new java.util.ArrayList<mmm.world.MovingBodyFluidCoupling.Request>();
        var previous = region.entityBodyState().bodies();
        double dt = region.solverConfig().timeStep();
        for (var tracked : frames.values()) { var frame=tracked.motion(); for (var target : frame.target()) {
            var source = previous.stream().filter(body -> body.bodyId().equals(target.bodyId())).findFirst()
                    .orElseGet(() -> frame.source().stream().filter(body -> body.bodyId().equals(target.bodyId())).findFirst().orElse(target));
            double vx=(target.localXMetres()-source.localXMetres())/dt;
            double vy=(target.localYMetres()-source.localYMetres())/dt;
            double vz=(target.localZMetres()-source.localZMetres())/dt;
            result.add(new mmm.world.MovingBodyFluidCoupling.Request(target.bodyId(),target.bounds(),vx,vy,vz,
                    target.centerXMetres(),target.centerYMetres(),target.centerZMetres(),0,0,0,-1,0));
        }}
        return java.util.List.copyOf(result);
    }

    java.util.List<mmm.world.MovingBodyFluidCoupling.Request> sourceBodies(FluidRegion region) {
        var frames = moving.get(region.id());
        var existing = region.entityBodyState().bodies();
        if (frames == null) return existing.stream().filter(body -> !worldBody(body.bodyId())).toList();
        var sources = new java.util.LinkedHashMap<java.util.UUID, mmm.world.MovingBodyFluidCoupling.Request>();
        var live = new java.util.HashSet<java.util.UUID>();
        for (var tracked : frames.values()) { var frame=tracked.motion();
            for (var body : frame.source()) live.add(body.bodyId());
            for (var body : frame.target()) live.add(body.bodyId());
        }
        for (var body : existing) if (!worldBody(body.bodyId()) || live.contains(body.bodyId())) sources.put(body.bodyId(), body);
        for (var tracked : frames.values()) for (var body : tracked.motion().source()) sources.putIfAbsent(body.bodyId(), body);
        return java.util.List.copyOf(sources.values());
    }

    void completed(ServerLevel level, FluidRegion region) {
        var frames = moving.get(region.id());
        if (frames == null) return;
        var accepted = region.entityBodyState().bodies();
        if (frames.entrySet().removeIf(entry ->
                !(level.getBlockEntity(entry.getValue().position()) instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity)
                && entry.getValue().motion().target().stream().allMatch(target -> accepted.stream().anyMatch(
                        body -> body.bodyId().equals(target.bodyId()) && body.bounds().equals(target.bounds()))))) {
            region.requireCollisionRefresh();
            if (frames.isEmpty()) moving.remove(region.id());
        }
    }

}
