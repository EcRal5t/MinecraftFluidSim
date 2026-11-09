package mmm.gpu;

import mmm.spatial.SpatialGrid;

/** Immutable device topology shared by every spatial kernel in a region. */
final class VulkanSpatialGrid {
    static final int HEADER_WORDS = 20;
    private VulkanSpatialGrid() { }

    static int wordCount(SpatialGrid grid) {
        return Math.toIntExact(HEADER_WORDS + 8L * grid.blockCount()
                + 14L * grid.cellCount() + 6L * grid.faceCount() + 2L * grid.columnCount());
    }

    static int[] encode(SpatialGrid grid) {
        int[] data = new int[wordCount(grid)];
        data[0] = grid.sizeX(); data[1] = grid.sizeY(); data[2] = grid.sizeZ(); data[3] = grid.cellCount();
        for (int axis = 0; axis < 3; axis++) data[4 + axis] = grid.faceOffset(axis);
        data[7] = grid.faceCount(); data[8] = grid.blockCount();
        data[9] = HEADER_WORDS;
        data[10] = data[9] + 8 * grid.blockCount();
        data[11] = data[10] + grid.cellCount();
        data[12] = data[11] + 6 * grid.cellCount();
        data[13] = data[12] + 6 * grid.cellCount();
        data[14] = data[13] + 3 * grid.faceCount();
        data[15] = SpatialGrid.BLOCK_SIZE;
        data[16] = data[14] + 3 * grid.faceCount();
        data[17] = data[16] + grid.cellCount();
        data[18] = grid.columnCount();
        for (int slot = 0; slot < grid.blockCount(); slot++) {
            var block = grid.block(slot); int at = data[9] + 8 * slot;
            data[at] = block.x(); data[at + 1] = block.y(); data[at + 2] = block.z();
            data[at + 3] = grid.blockCellOffset(slot);
            for (int axis = 0; axis < 3; axis++) data[at + 4 + axis] = grid.blockSize(slot, axis);
            java.util.Arrays.fill(data, data[10] + grid.blockCellOffset(slot),
                    data[10] + grid.blockCellOffset(slot) + grid.blockCellCount(slot), slot);
        }
        for (int cell = 0; cell < grid.cellCount(); cell++) for (int side = 0; side < 6; side++) {
            data[data[11] + 6 * cell + side] = grid.neighbor(cell, side);
            data[data[12] + 6 * cell + side] = grid.face(cell, side);
        }
        for (int face = 0; face < grid.faceCount(); face++) {
            int at = data[13] + 3 * face;
            data[at] = grid.faceLeft(face); data[at + 1] = grid.faceRight(face); data[at + 2] = grid.faceAxis(face);
            for (int axis = 0; axis < 3; axis++) data[data[14] + 3 * face + axis] = grid.faceCoordinate(face, axis);
        }
        for (int cell = 0; cell < grid.cellCount(); cell++) data[data[16] + cell] = grid.columnOfCell(cell);
        for (int column = 0; column < grid.columnCount(); column++) {
            data[data[17] + 2 * column] = grid.columnX(column);
            data[data[17] + 2 * column + 1] = grid.columnZ(column);
        }
        return data;
    }
}
