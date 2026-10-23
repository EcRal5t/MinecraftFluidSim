package mmm.simulation.controller;

import mmm.model.region.RegionGeometry.RegionKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Loaded source-water pool with solid containment and one open air layer. */
public final class NativePoolScanner {
    private NativePoolScanner() { }

    public static ScanResult scan(BlockPos seed, PoolWorld world, int maximumInteriorBlocks) {
        if (maximumInteriorBlocks <= 0) throw new IllegalArgumentException("Pool scan budget must be positive");
        Set<Long> water = new HashSet<>(), shell = new HashSet<>(), chunks = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        try {
            if (read(seed, world, chunks) != BlockKind.SOURCE_WATER) return failed(Status.UNSUPPORTED_GEOMETRY, "Select a source-water block");
            water.add(seed.asLong()); pending.add(seed.immutable());
            int minX=seed.getX(), minY=seed.getY(), minZ=seed.getZ(), maxX=minX, maxY=minY, maxZ=minZ;
            while (!pending.isEmpty()) {
                BlockPos pos = pending.removeFirst();
                minX=Math.min(minX,pos.getX()); minY=Math.min(minY,pos.getY()); minZ=Math.min(minZ,pos.getZ());
                maxX=Math.max(maxX,pos.getX()); maxY=Math.max(maxY,pos.getY()); maxZ=Math.max(maxZ,pos.getZ());
                for (Direction direction : Direction.values()) {
                    BlockPos next=pos.relative(direction);
                    BlockKind kind=read(next,world,chunks);
                    if (kind==BlockKind.SOURCE_WATER) {
                        if (water.add(next.asLong())) {
                            if (water.size()>maximumInteriorBlocks) return failed(Status.BUDGET_EXCEEDED,"Pool exceeds the block budget");
                            pending.add(next.immutable());
                        }
                    } else if (kind==BlockKind.FULL_SOLID) shell.add(next.asLong());
                    else if (kind!=BlockKind.AIR || direction!=Direction.UP)
                        return failed(Status.UNSUPPORTED_GEOMETRY,"Pool needs full solid walls and floor, source water and open air above");
                }
            }
            Set<Long> interior=new HashSet<>(water), atmosphere=new HashSet<>();
            for (long packed:water) {
                BlockPos pos=BlockPos.of(packed), above=pos.above();
                if (water.contains(above.asLong())) continue;
                if (pos.getY()!=maxY || read(above,world,chunks)!=BlockKind.AIR)
                    return failed(Status.UNSUPPORTED_GEOMETRY,"Pool needs a level open water surface");
                interior.add(above.asLong());
            }
            for (long packed:Set.copyOf(interior)) {
                if (water.contains(packed)) continue;
                BlockPos air=BlockPos.of(packed);
                for (Direction direction:Direction.values()) {
                    BlockPos next=air.relative(direction);
                    if (interior.contains(next.asLong())) continue;
                    BlockKind kind=read(next,world,chunks);
                    if (kind==BlockKind.FULL_SOLID) shell.add(next.asLong());
                    else if (kind==BlockKind.AIR) atmosphere.add(next.asLong());
                    else return failed(Status.UNSUPPORTED_GEOMETRY,"Air above the pool must open to air or full solid blocks");
                }
            }
            long dense=(long)(maxX-minX+1)*(maxY-minY+2)*(maxZ-minZ+1);
            if (dense>maximumInteriorBlocks) return failed(Status.BUDGET_EXCEEDED,"Pool and air layer exceed the dense solver budget");
            for(int z=minZ>>4;z<=maxZ>>4;z++) for(int x=minX>>4;x<=maxX>>4;x++) {
                if(!world.isChunkLoaded(x,z)) return failed(Status.INCOMPLETE_CHUNKS,"Pool requires a loaded chunk");
                chunks.add(ChunkPos.pack(x,z));
            }
            return new ScanResult(Status.SUCCESS,new FluidRegionFootprint(seed,new BlockPos(minX,minY,minZ),
                    new BlockPos(maxX,maxY+1,maxZ),interior,shell,chunks,RegionKind.POOL,atmosphere),null);
        } catch (Unavailable unavailable) { return failed(Status.INCOMPLETE_CHUNKS,unavailable.getMessage()); }
    }

    public static FluidBoundaryScanner.AvailabilityResult validateAvailability(FluidRegionFootprint footprint, PoolWorld world) {
        for(long packed:footprint.requiredChunks()) {
            ChunkPos chunk=ChunkPos.unpack(packed);
            if(!world.isChunkLoaded(chunk.x(),chunk.z())) return new FluidBoundaryScanner.AvailabilityResult(
                    FluidBoundaryScanner.AvailabilityStatus.INCOMPLETE_CHUNKS,"Pool requires a loaded chunk");
        }
        for(long packed:footprint.boundaryBlocks()) if(world.blockKind(BlockPos.of(packed))!=BlockKind.FULL_SOLID)
            return new FluidBoundaryScanner.AvailabilityResult(FluidBoundaryScanner.AvailabilityStatus.OPEN_BOUNDARY,
                    "Pool wall or floor changed at "+BlockPos.of(packed).toShortString());
        for(long packed:footprint.atmosphereBlocks()) if(world.blockKind(BlockPos.of(packed))!=BlockKind.AIR)
            return new FluidBoundaryScanner.AvailabilityResult(FluidBoundaryScanner.AvailabilityStatus.OPEN_BOUNDARY,
                    "Pool atmosphere opening changed at "+BlockPos.of(packed).toShortString());
        return new FluidBoundaryScanner.AvailabilityResult(FluidBoundaryScanner.AvailabilityStatus.READY,null);
    }

    private static BlockKind read(BlockPos position, PoolWorld world, Set<Long> chunks) {
        if(!world.isInsideBuildHeight(position) || !world.isChunkLoaded(position.getX()>>4,position.getZ()>>4))
            throw new Unavailable("Pool reaches an unloaded chunk or the world build limit");
        chunks.add(ChunkPos.pack(position.getX()>>4,position.getZ()>>4));
        return world.blockKind(position);
    }
    private static ScanResult failed(Status status,String reason) { return new ScanResult(status,null,reason); }
    private static final class Unavailable extends RuntimeException { Unavailable(String message) { super(message); } }
    public interface PoolWorld {
        boolean isInsideBuildHeight(BlockPos position);
        boolean isChunkLoaded(int chunkX,int chunkZ);
        BlockKind blockKind(BlockPos position);
    }
    public enum BlockKind { SOURCE_WATER, AIR, FULL_SOLID, OTHER }
    public enum Status { SUCCESS, UNSUPPORTED_GEOMETRY, INCOMPLETE_CHUNKS, BUDGET_EXCEEDED }
    public record ScanResult(Status status,FluidRegionFootprint footprint,String failure) {
        public boolean succeeded() { return status==Status.SUCCESS; }
    }
}
