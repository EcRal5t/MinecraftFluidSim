package mmm.fluid;

import java.util.HashMap;
import java.util.List;
import mmm.world.MovingBodyFluidCoupling;
import mmm.spatial.SpatialGrid;

/** Connected physical activity in one immutable device generation. */
public final class PhysicalActivityGraph {
    private final int sizeX, sizeY, sizeZ;
    private final SpatialGrid grid;
    private final int[] parent;

    public PhysicalActivityGraph(int sizeX, int sizeY, int sizeZ, FixedGeometrySnapshot geometry,
            int[] gasNodes, int[] particleSupports, List<FluidStateAccess.DevicePort> ports,
            EntityBodyState bodies, double cellSize) {
        if (!geometry.matchesShape(sizeX, sizeY, sizeZ)) throw new IllegalArgumentException("Activity geometry differs");
        this.grid=geometry.grid();
        this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
        int cells = geometry.cellCount();
        if (gasNodes != null && gasNodes.length != cells || particleSupports.length % 8 != 0)
            throw new IllegalArgumentException("Activity topology differs");
        parent = new int[cells];
        for (int cell = 0; cell < cells; cell++) parent[cell] = geometry.mask(cell) == 255 ? -1 : cell;
        for (int cell=0;cell<cells;cell++) {
            for(int axis=0;axis<3;axis++){
                int next=grid.neighbor(cell,2*axis+1);
                if(next>=0&&faceOpen(geometry.mask(cell),geometry.mask(next),axis))join(cell,next);
            }
        }
        if (gasNodes != null) {
            var first = new HashMap<Integer, Integer>();
            for (int cell = 0; cell < cells; cell++) if (gasNodes[cell] > 0) {
                Integer anchor = first.putIfAbsent(gasNodes[cell], cell);
                if (anchor != null) join(anchor, cell);
            }
        }
        for (int i = 0; i < particleSupports.length; i += 8) {
            int anchor = -1;
            for (int j = 0; j < 8; j++) {
                int cell = particleSupports[i + j];
                if (cell < 0 || cell >= cells || parent[cell] < 0) continue;
                if (anchor < 0) anchor = cell; else join(anchor, cell);
            }
            // The contact solver searches neighbouring buckets and permits a quarter-cell
            // trial displacement. Include those buckets before deciding which groups run.
            if (anchor >= 0) for (int j = 0; j < 8; j++) {
                int cell = particleSupports[i + j];
                if (cell < 0 || cell >= cells) continue;
                int cx = grid.x(cell), cy = grid.y(cell), cz = grid.z(cell);
                for (int z = Math.max(0, cz - 1); z <= Math.min(sizeZ - 1, cz + 1); z++)
                    for (int y = Math.max(0, cy - 1); y <= Math.min(sizeY - 1, cy + 1); y++)
                        for (int x = Math.max(0, cx - 1); x <= Math.min(sizeX - 1, cx + 1); x++)
                            join(anchor, index(x, y, z));
            }
        }
        var devices = new HashMap<List<String>, Integer>();
        for (var port : ports) {
            var contact = port.contact();
            int cell = index(contact.cellX(), contact.cellY(), contact.cellZ());
            if(cell<0)continue;
            var id = List.of(port.providerId(), port.objectId());
            Integer anchor = devices.putIfAbsent(id, cell);
            if (anchor != null) join(anchor, cell);
        }
        for (var body : bodies.bodies()) joinBody(body.bounds(), cellSize);
    }

    /** Adds all cells touched by an incoming body to the same physical group. */
    public void joinBody(MovingBodyFluidCoupling.Bounds bounds, double cellSize) {
        int minX = Math.max(0, (int) Math.floor(bounds.minimumX() / cellSize));
        int minY = Math.max(0, (int) Math.floor(bounds.minimumY() / cellSize));
        int minZ = Math.max(0, (int) Math.floor(bounds.minimumZ() / cellSize));
        int maxX = Math.min(sizeX - 1, (int) Math.ceil(bounds.maximumX() / cellSize) - 1);
        int maxY = Math.min(sizeY - 1, (int) Math.ceil(bounds.maximumY() / cellSize) - 1);
        int maxZ = Math.min(sizeZ - 1, (int) Math.ceil(bounds.maximumZ() / cellSize) - 1);
        int anchor = -1;
        for (int z = minZ; z <= maxZ; z++) for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
            int cell = index(x, y, z);
            if (cell<0||parent[cell] < 0) continue;
            if (anchor < 0) anchor = cell; else join(anchor, cell);
        }
    }

    /** Old and requested poses share the same displaced-fluid and response calculation. */
    public void joinBodyMotion(EntityBodyState oldBodies, EntityBodyState requestedBodies, double cellSize) {
        joinBodyMotion(oldBodies, requestedBodies.bodies(), cellSize);
    }

    /** Target observations may exceed the device request budget before unavailable groups are filtered. */
    public void joinBodyMotion(EntityBodyState oldBodies, List<MovingBodyFluidCoupling.Request> requestedBodies, double cellSize) {
        var old = new HashMap<java.util.UUID, MovingBodyFluidCoupling.Bounds>();
        for (var body : oldBodies.bodies()) old.put(body.bodyId(), body.bounds());
        for (var body : requestedBodies) {
            var next = body.bounds();
            var before = old.get(body.bodyId());
            if (before == null) { joinBody(next, cellSize); continue; }
            joinBody(new MovingBodyFluidCoupling.Bounds(
                    Math.min(before.minimumX(), next.minimumX()), Math.min(before.minimumY(), next.minimumY()),
                    Math.min(before.minimumZ(), next.minimumZ()), Math.max(before.maximumX(), next.maximumX()),
                    Math.max(before.maximumY(), next.maximumY()), Math.max(before.maximumZ(), next.maximumZ())), cellSize);
        }
    }

    /** Cells in a group wait together when any member requires an unavailable world chunk. */
    public boolean[] available(boolean[] worldAvailable) {
        if (worldAvailable.length != parent.length) throw new IllegalArgumentException("Activity availability differs");
        boolean[] blocked = new boolean[parent.length], available = new boolean[parent.length];
        for (int cell = 0; cell < parent.length; cell++) if (parent[cell] >= 0 && !worldAvailable[cell]) blocked[root(cell)] = true;
        for (int cell = 0; cell < parent.length; cell++) available[cell] = worldAvailable[cell] && (parent[cell] < 0 || !blocked[root(cell)]);
        return available;
    }

    private int index(int x, int y, int z) { return grid.cell(x,y,z); }
    private int root(int cell) {
        if (cell < 0 || cell >= parent.length || parent[cell] < 0) return -1;
        int root = cell;
        while (parent[root] != root) root = parent[root];
        while (parent[cell] != cell) { int next = parent[cell]; parent[cell] = root; cell = next; }
        return root;
    }
    private void join(int first, int second) {
        int a = root(first), b = root(second);
        if (a >= 0 && b >= 0 && a != b) parent[Math.max(a, b)] = Math.min(a, b);
    }
    private static boolean faceOpen(int first, int second, int axis) {
        for (int bit = 0; bit < 8; bit++) if ((bit & (1 << axis)) != 0
                && (first & (1 << bit)) == 0 && (second & (1 << (bit ^ (1 << axis)))) == 0) return true;
        return false;
    }
}
