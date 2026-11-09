package mmm.world;

import mmm.spatial.SpatialGrid;
import java.util.Set;

/** World origin, resident solver layout and interior block membership for collision capture. */
public record CollisionRegion(String dimensionId, int originX, int originY, int originZ,
        int cellsPerBlock, SpatialGrid grid, Set<Block> interiorBlocks) {
    public record Block(int x, int y, int z) { }
    public CollisionRegion {
        if (dimensionId == null || dimensionId.isBlank() || cellsPerBlock <= 0 || grid == null || interiorBlocks == null)
            throw new IllegalArgumentException("Collision capture region is invalid");
        interiorBlocks = Set.copyOf(interiorBlocks);
    }
    public int cellsX() { return grid.sizeX(); }
    public int cellsY() { return grid.sizeY(); }
    public int cellsZ() { return grid.sizeZ(); }
    public boolean isInteriorBlock(int x, int y, int z) { return interiorBlocks.contains(new Block(x,y,z)); }
}
