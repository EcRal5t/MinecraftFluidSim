package mmm.thermal;

import mmm.fluid.air.GasInventory;
import mmm.units.SignedDyadic;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** One dimension's world-coordinate thermal inventory and its static contact graph. */
public record EnvironmentThermalState(
        String dimensionId, long generation, String registryFingerprint,
        List<Node> nodes, List<Contact> contacts, Audit audit) {
    public enum Kind { SOLID, AIR }

    public record Key(int x, int y, int z, Kind kind, int component) {
        public Key {
            Objects.requireNonNull(kind);
            if (component < 0) throw new IllegalArgumentException("Negative environment component");
        }
    }

    /** Solid material/mass and air composition retain the same caloric definitions as the fluid. */
    public record Node(Key key, String materialId, long massUnits, double volumeCubicMetres,
                       GasInventory gas, double internalEnergyJ, List<FixedSurfaceState> surfaces) {
        public Node {
            Objects.requireNonNull(key);
            Objects.requireNonNull(materialId);
            Objects.requireNonNull(gas);
            surfaces = List.copyOf(surfaces);
            if (massUnits <= 0 || !Double.isFinite(internalEnergyJ)
                    || !(volumeCubicMetres > 0) || !Double.isFinite(volumeCubicMetres))
                throw new IllegalArgumentException("Invalid environment inventory");
            if (key.kind() == Kind.AIR && (gas.isEmpty() || gas.totalMassUnits() != massUnits || !surfaces.isEmpty())
                    || key.kind() == Kind.SOLID && !gas.isEmpty())
                throw new IllegalArgumentException("Environment carrier differs from its inventory");
        }

        public Node withEnergy(double energy) {
            return new Node(key, materialId, massUnits, volumeCubicMetres, gas, energy, surfaces);
        }
    }

    /** b == -1 connects a finite node to the prescribed far field. */
    public record Contact(int a, int b, double conductanceWPerK, double farTemperatureK) {
        public Contact {
            if (a < 0 || b < -1 || a == b || !(conductanceWPerK > 0) || !Double.isFinite(conductanceWPerK)
                    || b == -1 && (!(farTemperatureK > 0) || !Double.isFinite(farTemperatureK)))
                throw new IllegalArgumentException("Invalid environment contact");
        }
    }

    public record Audit(SignedDyadic initial, SignedDyadic source, SignedDyadic sink,
                        SignedDyadic regionExchange, SignedDyadic roundoff) {
        public Audit {
            Objects.requireNonNull(initial); Objects.requireNonNull(source); Objects.requireNonNull(sink);
            Objects.requireNonNull(regionExchange); Objects.requireNonNull(roundoff);
        }
        public SignedDyadic expectedEnergy() {
            return initial.add(source).subtract(sink).add(regionExchange).add(roundoff);
        }
    }

    public EnvironmentThermalState {
        Objects.requireNonNull(dimensionId); Objects.requireNonNull(registryFingerprint); Objects.requireNonNull(audit);
        nodes = List.copyOf(nodes); contacts = List.copyOf(contacts);
        if (generation < 0) throw new IllegalArgumentException("Negative environment generation");
        var keys = new HashSet<Key>();
        for (var node : nodes) if (!keys.add(node.key())) throw new IllegalArgumentException("Duplicate world thermal inventory");
        for (var contact : contacts) if (contact.a() >= nodes.size() || contact.b() >= nodes.size())
            throw new IllegalArgumentException("Environment contact exceeds inventory");
    }

    public static EnvironmentThermalState initialized(String dimension, String fingerprint,
                                                       List<Node> nodes, List<Contact> contacts) {
        return new EnvironmentThermalState(dimension, 0, fingerprint, nodes, contacts,
                new Audit(energy(nodes), SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO));
    }

    public SignedDyadic exactEnergy() { return energy(nodes); }

    private static SignedDyadic energy(List<Node> nodes) {
        var sum = SignedDyadic.ZERO;
        for (var node : nodes) sum = sum.add(SignedDyadic.of(node.internalEnergyJ()));
        return sum;
    }
}
