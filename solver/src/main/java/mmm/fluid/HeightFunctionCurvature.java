package mmm.fluid;

/** Height graph from nine consistently oriented open-cell columns with an entirely wet integration base. */
final class HeightFunctionCurvature {
    // A dominant-axis plane crosses this 3x3 tangent stencil within 4.5 cells.
    private static final int COLUMN_REACH = 5;
    private HeightFunctionCurvature() { }

    static double compute(FluidDomain d, int x, int y, int z) {
        int cell = d.cellIndex(x, y, z);
        double[] color = d.tension.surfaceTensionColor;
        double gx = difference(d, color, x, y, z, 0, cell);
        double gy = difference(d, color, x, y, z, 1, cell);
        double gz = difference(d, color, x, y, z, 2, cell);
        if (!(Double.isFinite(gx) && Double.isFinite(gy) && Double.isFinite(gz))) return Double.NaN;
        if (gx == 0 && gy == 0 && gz == 0) {
            double c = color[cell];
            if ((c == 0 || c == 1) && sameNeighbors(d, color, x, y, z, c, cell)) return 0;
            return Double.NaN;
        }
        int used = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            int axis = -1; double magnitude = 0;
            for (int a = 0; a < 3; a++) {
                double g = a == 0 ? gx : a == 1 ? gy : gz;
                if ((used & 1 << a) == 0 && Math.abs(g) > magnitude) { axis = a; magnitude = Math.abs(g); }
            }
            if (axis < 0) break;
            used |= 1 << axis;
            int sign = (axis == 0 ? gx : axis == 1 ? gy : gz) < 0 ? 1 : -1;
            double[] heights = d.tension.columnHeights;
            boolean valid = true;
            for (int i = -1; i <= 1 && valid; i++) for (int j = -1; j <= 1; j++) {
                int a = (axis + 1) % 3, b = (axis + 2) % 3;
                int sx = x + (a == 0 ? i : b == 0 ? j : 0);
                int sy = y + (a == 1 ? i : b == 1 ? j : 0);
                int sz = z + (a == 2 ? i : b == 2 ? j : 0);
                double height = column(d, color, sx, sy, sz, axis, sign, cell);
                heights[(i + 1) * 3 + j + 1] = height;
                if (!Double.isFinite(height)) { valid = false; break; }
            }
            if (!valid) continue;
            double hx = .5 * (heights[7] - heights[1]), hy = .5 * (heights[5] - heights[3]);
            double hxx = heights[7] - 2 * heights[4] + heights[1];
            double hyy = heights[5] - 2 * heights[4] + heights[3];
            double hxy = .25 * (heights[8] + heights[0] - heights[6] - heights[2]);
            return -sign * (hxx * (1 + hy * hy) + hyy * (1 + hx * hx) - 2 * hxy * hx * hy)
                    / Math.pow(1 + hx * hx + hy * hy, 1.5) / d.config.cellSize();
        }
        return Double.NaN;
    }

    private static double column(FluidDomain d, double[] color, int x, int y, int z, int axis, int sign, int target) {
        int gas = 0, base = 0; boolean gasFound = false, baseFound = false;
        for (int distance = 0; distance <= COLUMN_REACH; distance++) {
            if (!gasFound) {
                double c = sample(d, color, x, y, z, axis, sign * distance, target);
                if (!Double.isFinite(c)) return Double.NaN;
                if (c == 0) { gas = sign * distance; gasFound = true; }
            }
            if (!baseFound) {
                double c = sample(d, color, x, y, z, axis, -sign * distance, target);
                if (!Double.isFinite(c)) return Double.NaN;
                int endpoint = -sign * distance, side = sign > 0 ? 0 : 1;
                if (c == 1 || c > 0 && wetFace(d, color, x, y, z, axis, endpoint, side, sign, target)
                        && wetFace(d, color, x, y, z, axis, endpoint - sign, 1 - side, 0, target)) {
                    base = endpoint; baseFound = true;
                }
            }
            if (gasFound && baseFound) break;
        }
        if (!gasFound || !baseFound || sign * (gas - base) <= 0) return Double.NaN;
        boolean partial = sample(d, color, x, y, z, axis, base, target) < 1;
        double sum = 0, previous = 1;
        for (int offset = base + (partial ? 0 : sign); offset != gas; offset += sign) {
            double c = sample(d, color, x, y, z, axis, offset, target);
            if (!Double.isFinite(c) || c > previous) return Double.NaN;
            sum += c; previous = c;
        }
        int coordinate = axis == 0 ? x : axis == 1 ? y : z;
        return coordinate + base + (partial ? (sign > 0 ? 0 : 1) : (sign > 0 ? 1 : 0)) + sign * sum;
    }

    /** A common entirely wet section supplies a liquid neighbourhood on both sides.
     * Partial end cells contribute their actual volume to the height integral. */
    private static boolean wetFace(FluidDomain d, double[] color, int x, int y, int z,
                                   int axis, int offset, int side, int orientation, int target) {
        double c = sample(d, color, x, y, z, axis, offset, target);
        if (!(c > 0)) return false;
        if (c == 1) return true;
        x += axis == 0 ? offset : 0; y += axis == 1 ? offset : 0; z += axis == 2 ? offset : 0;
        int coordinate = axis == 0 ? x : axis == 1 ? y : z, size = axis == 0 ? d.sizeX : axis == 1 ? d.sizeY : d.sizeZ;
        if (Math.floorMod(coordinate, 2 * size) >= size) { side = 1 - side; orientation = -orientation; }
        int cell = d.cellIndex(reflect(x, d.sizeX), reflect(y, d.sizeY), reflect(z, d.sizeZ));
        if (d.projection.pressureVolume[cell] != c) return false;
        var plane = d.pressurePhaseGeometry;
        if (plane.liquidShape(cell) != null) {
            var box = LiquidShapeGeometry.box(plane.liquidShape(cell));
            if (orientation != 0 && (orientation > 0 ? box.upper(axis).compareTo(mmm.units.SignedDyadic.of(1)) >= 0 : box.lower(axis).signum() == 0)) return false;
            if (side == 0 ? box.lower(axis).signum() > 0 : box.upper(axis).compareTo(mmm.units.SignedDyadic.of(1)) < 0) return false;
            for (int a = 0; a < 3; a++) if (a != axis && (box.lower(a).signum() != 0
                    || !box.upper(a).equals(mmm.units.SignedDyadic.of(1)))) return false;
            return true;
        }
        double normal = plane.planeNormal(cell, axis);
        if (orientation != 0 && !(normal * orientation > 0)) return false;
        double maximum = normal * side + Math.max(plane.planeNormal(cell, (axis + 1) % 3), 0)
                + Math.max(plane.planeNormal(cell, (axis + 2) % 3), 0);
        return maximum <= plane.planeOffset(cell);
    }

    private static double sample(FluidDomain d, double[] color, int x, int y, int z, int axis, int offset, int target) {
        x += axis == 0 ? offset : 0; y += axis == 1 ? offset : 0; z += axis == 2 ? offset : 0;
        boolean reflected = !d.inside(x, y, z);
        if (reflected) {
            if (d.surfaceTensionContactAngleAtCell(target) != Math.PI / 2) return Double.NaN;
            x = reflect(x, d.sizeX); y = reflect(y, d.sizeY); z = reflect(z, d.sizeZ);
        }
        int c = d.cellIndex(x, y, z);
        if (d.geometry.solid[c] || d.geometry.cutCells.mask(c) != 0 || d.effectiveOpenCellVolume(c) != 1) return Double.NaN;
        if (reflected && color[c] > 0 && d.surfaceTensionContactAngleAtCell(c) != Math.PI / 2) return Double.NaN;
        return color[c];
    }
    private static int reflect(int coordinate, int size) {
        int position = Math.floorMod(coordinate, 2 * size);
        return position < size ? position : 2 * size - 1 - position;
    }
    private static double difference(FluidDomain d, double[] c, int x, int y, int z, int axis, int target) {
        return sample(d, c, x, y, z, axis, 1, target) - sample(d, c, x, y, z, axis, -1, target);
    }
    private static boolean sameNeighbors(FluidDomain d, double[] c, int x, int y, int z, double value, int target) {
        for (int a = 0; a < 3; a++) if (sample(d,c,x,y,z,a,1,target) != value || sample(d,c,x,y,z,a,-1,target) != value) return false;
        return true;
    }
}
