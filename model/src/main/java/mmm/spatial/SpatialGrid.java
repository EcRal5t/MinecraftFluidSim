package mmm.spatial;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Fixed-size spatial blocks with compact cells and one shared staggered face per location.
 * Coordinates are relative to the domain's world origin; storage indices belong to this layout. */
public final class SpatialGrid {
    public static final int BLOCK_SIZE = 8;
    private final int sizeX, sizeY, sizeZ;
    private final Block[] blocks;
    private final Map<Long, Integer> slots;
    private final int[] cellBlocks, cellOffsets, faceOffsets;
    private final int[][] positiveFaces;
    private final int[] leftCells, rightCells;
    private final int[] cellColumns, columnX, columnZ;
    private final Map<Long,Integer> columns;

    public record Block(int x, int y, int z) implements Comparable<Block> {
        @Override public int compareTo(Block other) {
            int order = Integer.compare(z, other.z);
            if (order == 0) order = Integer.compare(y, other.y);
            return order == 0 ? Integer.compare(x, other.x) : order;
        }
    }

    public static SpatialGrid rectangular(int sizeX, int sizeY, int sizeZ) {
        requireShape(sizeX, sizeY, sizeZ);
        var members = new java.util.ArrayList<Block>();
        for (int z = 0; z < blocksFor(sizeZ); z++)
            for (int y = 0; y < blocksFor(sizeY); y++)
                for (int x = 0; x < blocksFor(sizeX); x++) members.add(new Block(x, y, z));
        return new SpatialGrid(sizeX, sizeY, sizeZ, members);
    }

    public SpatialGrid(int sizeX, int sizeY, int sizeZ, Collection<Block> members) {
        requireShape(sizeX, sizeY, sizeZ);
        this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
        blocks = Objects.requireNonNull(members).toArray(Block[]::new);
        if (blocks.length == 0) throw new IllegalArgumentException("Spatial grid has no blocks");
        Arrays.sort(blocks);
        slots = new HashMap<>();
        cellOffsets = new int[blocks.length + 1];
        for (int slot = 0; slot < blocks.length; slot++) {
            Block block = blocks[slot];
            if (block.x < 0 || block.y < 0 || block.z < 0 || block.x >= blocksFor(sizeX)
                    || block.y >= blocksFor(sizeY) || block.z >= blocksFor(sizeZ))
                throw new IllegalArgumentException("Block is outside the domain extent");
            if (slots.put(key(block.x, block.y, block.z), slot) != null)
                throw new IllegalArgumentException("Duplicate spatial block");
            cellOffsets[slot + 1] = Math.addExact(cellOffsets[slot],
                    blockSize(slot, 0) * blockSize(slot, 1) * blockSize(slot, 2));
        }
        cellBlocks = new int[cellCount()];
        for (int slot = 0; slot < blocks.length; slot++)
            Arrays.fill(cellBlocks, cellOffsets[slot], cellOffsets[slot + 1], slot);

        var columnKeys = new java.util.TreeSet<Long>();
        for (int cell = 0; cell < cellCount(); cell++) columnKeys.add(columnKey(x(cell), z(cell)));
        columnX = new int[columnKeys.size()];
        columnZ = new int[columnKeys.size()];
        columns = new HashMap<>();
        int column = 0;
        for (long key : columnKeys) {
            columnX[column] = (int) key;
            columnZ[column] = (int) (key >>> 32);
            columns.put(key, column++);
        }
        cellColumns = new int[cellCount()];
        for (int cell = 0; cell < cellCount(); cell++) {
            cellColumns[cell] = columns.get(columnKey(x(cell), z(cell)));
        }

        positiveFaces = new int[3][cellCount()];
        faceOffsets = new int[4];
        for (int axis = 0; axis < 3; axis++) {
            int count = cellCount();
            for (int cell = 0; cell < cellCount(); cell++) {
                int next = neighbor(cell, 2 * axis + 1);
                positiveFaces[axis][cell] = next >= 0 ? next : count++;
            }
            faceOffsets[axis + 1] = Math.addExact(faceOffsets[axis], count);
        }
        leftCells = new int[faceCount()]; rightCells = new int[faceCount()];
        Arrays.fill(leftCells, -1); Arrays.fill(rightCells, -1);
        for (int axis = 0; axis < 3; axis++) for (int cell = 0; cell < cellCount(); cell++) {
            int negative = faceOffsets[axis] + cell;
            leftCells[negative] = neighbor(cell, 2 * axis);
            rightCells[negative] = cell;
            int positive = faceOffsets[axis] + positiveFaces[axis][cell];
            leftCells[positive] = cell;
        }
    }

    private static void requireShape(int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0) throw new IllegalArgumentException("Invalid spatial extent");
    }
    private static int blocksFor(int extent) { return (extent - 1) / BLOCK_SIZE + 1; }
    private long key(int x, int y, int z) {
        return ((long) z * blocksFor(sizeY) + y) * blocksFor(sizeX) + x;
    }
    private static long columnKey(int x, int z) { return ((long) z << 32) | (x & 0xffffffffL); }
    public int columnCount() { return columnX.length; }
    public int column(int x, int z) { return columns.getOrDefault(columnKey(x, z), -1); }
    public int columnOfCell(int cell) { return cellColumns[cell]; }
    public int columnX(int column){return columnX[column];}
    public int columnZ(int column){return columnZ[column];}
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public int size(int axis) { return axis == 0 ? sizeX : axis == 1 ? sizeY : sizeZ; }
    public int cellCount() { return cellOffsets[blocks.length]; }
    public int blockCount() { return blocks.length; }
    public Block block(int slot) { return blocks[slot]; }
    public int blockCellOffset(int slot) { return cellOffsets[slot]; }
    public int blockCellCount(int slot) { return cellOffsets[slot + 1] - cellOffsets[slot]; }
    public int blockSize(int slot, int axis) {
        Block block = blocks[slot];
        int coordinate = axis == 0 ? block.x : axis == 1 ? block.y : block.z;
        return Math.min(BLOCK_SIZE, size(axis) - coordinate * BLOCK_SIZE);
    }
    public int blockSlot(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= blocksFor(sizeX)
                || y >= blocksFor(sizeY) || z >= blocksFor(sizeZ)) return -1;
        return slots.getOrDefault(key(x, y, z), -1);
    }
    public int blockNeighbor(int slot, int side) {
        Block b = blocks[slot]; int delta = (side & 1) == 0 ? -1 : 1;
        return blockSlot(b.x + (side / 2 == 0 ? delta : 0),
                b.y + (side / 2 == 1 ? delta : 0), b.z + (side / 2 == 2 ? delta : 0));
    }
    /** Returns -1 for coordinates outside the registered block set. */
    public int cell(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return -1;
        int slot = blockSlot(x / BLOCK_SIZE, y / BLOCK_SIZE, z / BLOCK_SIZE);
        if (slot < 0) return -1;
        return cellOffsets[slot] + ((z % BLOCK_SIZE) * blockSize(slot, 1)
                + y % BLOCK_SIZE) * blockSize(slot, 0) + x % BLOCK_SIZE;
    }
    public int coordinate(int cell, int axis) {
        int slot = cellBlocks[cell], local = cell - cellOffsets[slot]; Block b = blocks[slot];
        return switch (axis) {
            case 0 -> b.x * BLOCK_SIZE + local % blockSize(slot, 0);
            case 1 -> b.y * BLOCK_SIZE + local / blockSize(slot, 0) % blockSize(slot, 1);
            case 2 -> b.z * BLOCK_SIZE + local / (blockSize(slot, 0) * blockSize(slot, 1));
            default -> throw new IllegalArgumentException("Invalid coordinate axis");
        };
    }
    public int x(int cell) { return coordinate(cell, 0); }
    public int y(int cell) { return coordinate(cell, 1); }
    public int z(int cell) { return coordinate(cell, 2); }
    public int neighbor(int cell, int side) {
        int delta = (side & 1) == 0 ? -1 : 1, axis = side / 2;
        return cell(x(cell) + (axis == 0 ? delta : 0), y(cell) + (axis == 1 ? delta : 0),
                z(cell) + (axis == 2 ? delta : 0));
    }
    public int faceCount() { return faceOffsets[3]; }
    public int faceCount(int axis) { return faceOffsets[axis + 1] - faceOffsets[axis]; }
    public int faceOffset(int axis) { return faceOffsets[axis]; }
    public int face(int cell, int side) {
        int axis = side / 2;
        return faceOffsets[axis] + ((side & 1) == 0 ? cell : positiveFaces[axis][cell]);
    }
    public int faceAt(int x, int y, int z, int axis) {
        int right = cell(x, y, z);
        if (right >= 0) return faceOffsets[axis] + right;
        int left = cell(x - (axis == 0 ? 1 : 0), y - (axis == 1 ? 1 : 0), z - (axis == 2 ? 1 : 0));
        return left < 0 ? -1 : face(left, 2 * axis + 1);
    }
    public int faceAxis(int face) { return face < faceOffsets[1] ? 0 : face < faceOffsets[2] ? 1 : 2; }
    public int faceLeft(int face) { return leftCells[face]; }
    public int faceRight(int face) { return rightCells[face]; }
    public int faceCoordinate(int face, int axis) {
        int right = rightCells[face];
        return right >= 0 ? coordinate(right, axis)
                : coordinate(leftCells[face], axis) + (axis == faceAxis(face) ? 1 : 0);
    }
    @Override public boolean equals(Object other) {
        return other instanceof SpatialGrid grid && sizeX == grid.sizeX && sizeY == grid.sizeY
                && sizeZ == grid.sizeZ && Arrays.equals(blocks, grid.blocks);
    }
    @Override public int hashCode() {
        return 31 * (31 * (31 * sizeX + sizeY) + sizeZ) + Arrays.hashCode(blocks);
    }
}
