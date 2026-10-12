package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.chemistry.ExactRational;
import java.math.BigInteger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Conservative gas region network with an isothermal equation of state and
 * finite-rate orifice transport. Thermal state may supply non-isothermal pressure, density and
 * a jointly validated energy-transfer commit without moving thermal truth into this owner.
 *
 * <p>Inventories are stable component groups with independent standard-state volume,
 * mass and optional species columns. Every quantum originates from the ambient reservoir,
 * so the total identity {@code sealed + vented == drawnFromAmbient} and the
 * per-component volume/species identities hold exactly
 * across node creation, injection, orifice transport, merging, splitting and
 * venting. Pressures are derived gauge pressures in solver units (pascal) with
 * ambient at exactly zero; ambient is id {@link #AMBIENT_NODE} with unbounded
 * inventory and never stores state. Persistence captures this runtime owner through the
 * thermal region payload; this type itself is not a wire format.</p>
 */
public final class AirNetwork {
	public interface NodeThermodynamics {
		double gaugePressure(int nodeId);
		double density(int nodeId);
		/** Standard volume per actual source volume, including the source temperature. */
		default double standardVolumeRatio(int nodeId) {
			return (REFERENCE_PRESSURE + gaugePressure(nodeId)) / REFERENCE_PRESSURE;
		}
		/** Returns an energy-aware exact limit, or -1 to use the isothermal equilibrium. */
		default long limitTransferNanos(int source, int target, long requested) { return -1L; }
		/** Read-only evidence of remaining transfer activity, or -1 for the isothermal fallback. */
		default long activityTransferNanos(int source, int target, long available) {
			return limitTransferNanos(source, target, available);
		}
	}

	@FunctionalInterface
	public interface TransferPlanner {
		TransferCommit plan(int sourceNode, int targetNode, GasInventory moved,
		              GasInventory sourceAfter, GasInventory targetAfter);
	}

	/** A fully validated cross-owner commit. Implementations must only publish prepared values. */
	@FunctionalInterface
	public interface TransferCommit {
		void commitPrepared();
	}
	/** Reference (ambient) absolute pressure in Pa; gauge zero matches the solver convention. */
	public static final double REFERENCE_PRESSURE = 101_325.0;
	/** NIST dry N2/O2/Ar mixture density at the frozen gas standard state (kg/m³). */
	public static final double AIR_DENSITY = REFERENCE_PRESSURE * 0.02895968300420168
			/ (8.314462618 * GasRegistry.STANDARD_TEMPERATURE_K);
	/** The ambient reservoir: gauge pressure zero, unbounded inventory, never stored as a node. */
	public static final int AMBIENT_NODE = 0;

	/** Converts cubic metres to exact nanovolume quanta. */
	public static long nanos(double cubicMeters) { return AirNode.toNanos(cubicMeters); }

	/** Converts nanovolume quanta to cubic metres. */
	public static double cubicMeters(long nanos) { return nanos * AirNode.NANO_VOLUME; }

	private TreeMap<Integer, AirNode> nodes = new TreeMap<>();
	private List<AirEdge> edges = new ArrayList<>();
	private final GasRegistry registry;
	private final double ambientDensity;
	private int nextNodeId = 1;
	private GasAuditLedger escapedSink = GasAuditLedger.EMPTY;
	private GasAuditLedger externalSource = GasAuditLedger.EMPTY;
	private SignedDyadic massRoundoff = SignedDyadic.ZERO;
	private SignedDyadic residualRoundoff = SignedDyadic.ZERO;
	private long revision;
	private PhaseMutationPlan lastCommittedPhasePlan;
	private int transferClampEvents;

	public AirNetwork() { this(BuiltinGasDefinitions.createRegistry()); }

	public AirNetwork(GasRegistry registry) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.ambientDensity = registry.createAmbient(AirNode.toNanos(1.0)).totalMass();
		if (!(Double.isFinite(ambientDensity) && ambientDensity > 0.0)) {
			throw new IllegalArgumentException("Ambient gas density is invalid");
		}
	}

	public GasRegistry registry() { return registry; }
	/** Monotonic token over authoritative nodes, inventories, volumes, edges and audit columns. */
	public long revision() { return revision; }

	/** Creates a sealed cavity node; the initial component inventory is booked as drawn from ambient. */
	public int addNode(double availableVolume, double standardVolume) {
		if (nodes.size() == GasLimits.MAX_NODES_PER_REGION)
			throw new IllegalArgumentException("Gas creation exceeds the regional node budget");
		int id = nextNodeId, nextAfter = Math.addExact(nextNodeId, 1);
		GasInventory created = registry.createAmbient(AirNode.toNanos(standardVolume));
		AirNode node = new AirNode(availableVolume, created);
		TreeMap<Integer, AirNode> replacement = new TreeMap<>(nodes);
		replacement.put(id, node);
		commitRemapNodes(planNodeMutation(replacement, new ArrayList<>(edges), nextAfter, created, GasInventory.EMPTY));
		return id;
	}

	public boolean hasNode(int nodeId) { return nodeId != AMBIENT_NODE && nodes.containsKey(nodeId); }

	public List<Integer> nodeIds() { return new ArrayList<>(nodes.keySet()); }

	public int nodeCount() { return nodes.size(); }

	/** Uses the transport's integer equilibrium limit; slow openings remain active until that limit is zero. */
	public boolean hasPressureDrivenFlow(java.util.function.IntToDoubleFunction pressure) {
		return hasPressureDrivenFlow(pressure, null);
	}

	public boolean hasPressureDrivenFlow(NodeThermodynamics thermodynamics) {
		Objects.requireNonNull(thermodynamics, "thermodynamics");
		return hasPressureDrivenFlow(thermodynamics::gaugePressure, thermodynamics);
	}

	private boolean hasPressureDrivenFlow(java.util.function.IntToDoubleFunction pressure, NodeThermodynamics thermodynamics) {
		Objects.requireNonNull(pressure, "pressure");
		for (AirEdge edge : edges) {
			double first = pressure.applyAsDouble(edge.firstNode());
			double second = pressure.applyAsDouble(edge.secondNode());
			// Invalid thermodynamic evidence must never authorize sleep.
			if (!Double.isFinite(first) || !Double.isFinite(second)) return true;
			if (first == second) continue;
			int source = first > second ? edge.firstNode() : edge.secondNode();
			int target = first > second ? edge.secondNode() : edge.firstNode();
			long available = source == AMBIENT_NODE ? Long.MAX_VALUE : standardVolumeNanos(source);
			if (target != AMBIENT_NODE) available = Math.min(available, Long.MAX_VALUE - standardVolumeNanos(target));
			long thermalLimit = thermodynamics == null ? -1L : thermodynamics.activityTransferNanos(source, target, available);
			if (thermalLimit >= 0L) {
				if (thermalLimit > 0L) return true;
				continue;
			}
			// Ignore the rate here: a small aperture must not make a non-equilibrium state look idle.
			if (equalizationClampedTransfer(source, target, Double.MAX_VALUE,
					REFERENCE_PRESSURE + Math.max(first, second),
					REFERENCE_PRESSURE + Math.min(first, second)) > 0L) return true;
		}
		return false;
	}

	public double availableVolume(int nodeId) { return sealed(nodeId).availableVolume(); }

	public void updateAvailableVolume(int nodeId, double availableVolume) {
		sealed(nodeId).setAvailableVolume(availableVolume);
		revision++;
	}

	public long standardVolumeNanos(int nodeId) { return sealed(nodeId).standardVolumeNanos(); }

	public double standardVolume(int nodeId) { return sealed(nodeId).standardVolume(); }

	public GasInventory inventory(int nodeId) { return sealed(nodeId).inventory(); }

	/** Creates an ambient inventory without mutating this network; used by topology planning. */
	public GasInventory ambientInventory(long standardVolumeNanos) { return registry.createAmbient(standardVolumeNanos); }

	/** Preview for an exact phase-boundary take; no gas or ledger state is changed. */
	public GasInventory.Split previewComponentTake(int nodeId, String componentId, long standardVolumeNanos) {
		return sealed(nodeId).inventory().splitComponent(componentId, standardVolumeNanos);
	}

	/** Preview for a phase take whose mass/residual quantum is independent of gas nanos rounding. */
	public GasInventory.Split previewComponentTake(
			int nodeId, String componentId, long standardVolumeNanos,
			double mass, double massResidual
	) {
		return sealed(nodeId).inventory().splitComponent(
				componentId, standardVolumeNanos, mass, massResidual);
	}

	/** Books material entering the gas owner from another phase and merges all independent columns. */
	public void commitPhaseSource(int nodeId, GasInventory addition) {
		commitPhaseMutation(planPhaseSource(nodeId, addition));
	}

	/** Prepares node and cumulative audit columns before the other phase publishes any edits. */
	public PhaseMutationPlan planPhaseSource(int nodeId, GasInventory addition) {
		if (addition == null || addition.isEmpty()) throw new IllegalArgumentException("Gas phase source is empty");
		registry.requireCompatible(addition);
		AirNode node = sealed(nodeId);
		try {
			GasInventory after = node.inventory().merge(addition);
			return planPhaseMutation(node, after, addition, GasInventory.EMPTY);
		} catch (ArithmeticException | IllegalArgumentException capacity) {
			throw new PhaseCapacityExceededException(capacity);
		}
	}

	/** Books material leaving the gas owner into another phase after an exact preview. */
	public void commitPhaseSink(int nodeId, GasInventory expectedRemoval) {
		commitPhaseMutation(planPhaseSink(nodeId, expectedRemoval));
	}

	/** Prepares the exact component take and its complete cumulative sink ledger. */
	public PhaseMutationPlan planPhaseSink(int nodeId, GasInventory expectedRemoval) {
		if (expectedRemoval == null || expectedRemoval.components().size() != 1) {
			throw new IllegalArgumentException("Gas phase sink must name one component portion");
		}
		GasComponentAmount component = expectedRemoval.components().getFirst();
		AirNode node = sealed(nodeId);
		GasInventory.Split split = node.inventory().splitComponent(component.componentId(),
				component.standardVolumeNanos(), component.mass(), component.massResidual());
		if (!split.first().equals(expectedRemoval)) throw new IllegalStateException("Gas phase sink preview changed");
		try {
			return planPhaseMutation(node, split.second(), GasInventory.EMPTY, split.first());
		} catch (ArithmeticException | IllegalArgumentException capacity) {
			throw new PhaseCapacityExceededException(capacity);
		}
	}

	public void requirePhaseMutationCurrent(PhaseMutationPlan plan) {
		if (plan == null || plan.owner != this || plan.baseRevision != revision)
			throw new IllegalStateException("Gas phase mutation plan is stale or belongs to another network");
	}

	private PhaseMutationPlan planPhaseMutation(AirNode node, GasInventory after, GasInventory source, GasInventory sink) {
		TransferMutation mutation = planTransferMutation(liveMassColumns(), node, after, null, null, source, sink);
		long total = 0L;
		for (AirNode current : nodes.values()) total = Math.addExact(total,
				(current == node ? after : current.inventory()).standardVolumeNanos());
		return new PhaseMutationPlan(this, revision, after, mutation);
	}

	/** Publishes the precomputed columns without repeating inventory or ledger arithmetic. */
	public void commitPhaseMutation(PhaseMutationPlan plan) {
		requirePhaseMutationCurrent(plan);
		commitTransferMutation(plan.mutation);
		lastCommittedPhasePlan = plan;
	}

	/**
	 * Explicitly injects or removes inventory (developer presets and tests). Increases are
	 * booked as drawn from ambient and decreases as vented, keeping the ledger identity exact.
	 */
	public void setStandardVolume(int nodeId, double standardVolume) {
		AirNode node = sealed(nodeId);
		long target = AirNode.toNanos(standardVolume);
		long current = node.standardVolumeNanos();
		if (target == current) return;
		GasInventory source = GasInventory.EMPTY, sink = GasInventory.EMPTY;
		GasInventory after;
		if (target > current) {
			source = registry.createAmbient(target - current);
			after = node.inventory().merge(source);
		} else {
			GasInventory.Split split = node.inventory().splitTotal(target);
			after = split.first(); sink = split.second();
		}
		TreeMap<Integer, AirNode> replacement = new TreeMap<>(nodes);
		replacement.put(nodeId, new AirNode(node.availableVolume(), after));
		commitRemapNodes(planNodeMutation(replacement, new ArrayList<>(edges), nextNodeId, source, sink));
	}

	/** Adds an orifice edge; parallel openings between the same pair of nodes sum their areas. */
	public void addOrMergeEdge(int firstNode, int secondNode, double areaSquareMeters, double dischargeCoefficient) {
		if (firstNode == secondNode) throw new IllegalArgumentException("An edge needs two distinct nodes");
		if (firstNode != AMBIENT_NODE) sealed(firstNode);
		if (secondNode != AMBIENT_NODE) sealed(secondNode);
		AirEdge incoming = new AirEdge(firstNode, secondNode, areaSquareMeters, dischargeCoefficient);
		int existing = findEdge(firstNode, secondNode);
		if (existing >= 0) {
			AirEdge edge = edges.get(existing);
			double combinedArea = edge.areaSquareMeters() + areaSquareMeters;
			double combinedDischarge = (edge.dischargeCoefficient() * edge.areaSquareMeters()
					+ dischargeCoefficient * areaSquareMeters) / combinedArea;
			edges.set(existing, new AirEdge(firstNode, secondNode, combinedArea, combinedDischarge));
			revision++;
			return;
		}
		edges.add(incoming);
		revision++;
	}

	public boolean removeEdge(int firstNode, int secondNode) {
		int existing = findEdge(firstNode, secondNode);
		if (existing < 0) return false;
		edges.remove(existing);
		revision++;
		return true;
	}

	private int findEdge(int firstNode, int secondNode) {
		for (int i = 0; i < edges.size(); i++) {
			AirEdge edge = edges.get(i);
			boolean samePair = (edge.firstNode() == firstNode && edge.secondNode() == secondNode)
					|| (edge.firstNode() == secondNode && edge.secondNode() == firstNode);
			if (samePair) return i;
		}
		return -1;
	}

	public List<AirEdge> edges() { return List.copyOf(edges); }

	public int edgeCount() { return edges.size(); }

	/**
	 * Isothermal state equation: {@code p = P0 * (V0 / V - 1)}. Ambient is exactly zero with
	 * no state. A finite inventory in zero volume is a singular candidate and is rejected rather
	 * than repaired by changing its pressure.
	 */
	public double gaugePressure(int nodeId) {
		if (nodeId == AMBIENT_NODE) return 0.0;
		AirNode node = sealed(nodeId);
		if (node.inventory().isEmpty()) return -REFERENCE_PRESSURE;
		return rawGaugePressure(node);
	}

	/** Read-only derivation of the same pressure; safe for Query/probe consumers. */
	public double observedGaugePressure(int nodeId) {
		if (nodeId == AMBIENT_NODE) return 0.0;
		AirNode node = sealed(nodeId);
		if (node.inventory().isEmpty()) return -REFERENCE_PRESSURE;
		return rawGaugePressure(node);
	}

	private double rawGaugePressure(AirNode node) {
		if (!(node.availableVolume() > 0.0)) {
			throw new IllegalStateException("Finite gas inventory has no available volume");
		}
		double pressure = REFERENCE_PRESSURE * (node.standardVolume() / node.availableVolume() - 1.0);
		if (!Double.isFinite(pressure)) throw new IllegalStateException("Derived gas pressure is not finite");
		return pressure;
	}

	/** Isothermal density from the same inventory and available volume as pressure. */
	public double density(int nodeId) {
		if (nodeId == AMBIENT_NODE) return ambientDensity;
		AirNode node = sealed(nodeId);
		if (node.inventory().isEmpty()) return 0.0;
		if (!(node.availableVolume() > 0.0)) {
			throw new IllegalStateException("Finite gas inventory has no available volume");
		}
		double density = node.inventory().density(node.availableVolume());
		if (!(Double.isFinite(density) && density > 0.0)) {
			throw new IllegalStateException("Derived gas density is invalid");
		}
		return density;
	}

	/**
	 * Advances orifice transport by one step. Flow follows the CONTAM power law
	 * {@code Q = Cd * A * sqrt(2 * dp / rho_source)}; the transferred standard volume is capped
	 * to the equalization split, so the pressure difference decays monotonically and never
	 * overshoots equilibrium. Edges apply in list order with inventories refreshed in place.
	 */
	public void stepEdges(double dt) {
		stepEdges(dt, new NodeThermodynamics() {
			@Override public double gaugePressure(int nodeId) { return AirNetwork.this.gaugePressure(nodeId); }
			@Override public double density(int nodeId) { return AirNetwork.this.density(nodeId); }
		}, (source, target, moved, sourceAfter, targetAfter) -> () -> { });
	}

	public void stepEdges(double dt, NodeThermodynamics thermodynamics, TransferPlanner transferPlanner) {
		if (!(dt > 0.0) || Double.isNaN(dt)) throw new IllegalArgumentException("dt must be positive");
		Objects.requireNonNull(thermodynamics, "thermodynamics");
		Objects.requireNonNull(transferPlanner, "transferPlanner");
		for (int i = 0; i < edges.size(); i++) {
			AirEdge edge = edges.get(i);
			double firstPressure = thermodynamics.gaugePressure(edge.firstNode());
			double secondPressure = thermodynamics.gaugePressure(edge.secondNode());
			if (firstPressure == secondPressure) continue;
			int source = firstPressure > secondPressure ? edge.firstNode() : edge.secondNode();
			int target = firstPressure > secondPressure ? edge.secondNode() : edge.firstNode();
			double delta = Math.abs(firstPressure - secondPressure);
			double density = thermodynamics.density(source);
			if (!(Double.isFinite(density) && density > 0.0)) {
				throw new IllegalArgumentException("Gas orifice source density is invalid");
			}
			double flow = edge.dischargeCoefficient() * edge.areaSquareMeters()
					* (Math.sqrt(delta) / Math.sqrt(density)) * Math.sqrt(2.0);
			double transferCubicMeters = flow * dt * thermodynamics.standardVolumeRatio(source);
			double maximumVolume = source == AMBIENT_NODE ? cubicMeters(Long.MAX_VALUE) : standardVolume(source);
			long requested = transferCubicMeters >= maximumVolume
					? (source == AMBIENT_NODE ? Long.MAX_VALUE : standardVolumeNanos(source))
					: AirNode.toNanos(transferCubicMeters);
			if (target != AMBIENT_NODE) requested = Math.min(requested, Long.MAX_VALUE - standardVolumeNanos(target));
			long thermalLimit = thermodynamics.limitTransferNanos(source, target, requested);
			long transferNanos = thermalLimit >= 0L ? thermalLimit : equalizationClampedTransfer(source, target, transferCubicMeters,
					REFERENCE_PRESSURE + (source == edge.firstNode() ? firstPressure : secondPressure),
					REFERENCE_PRESSURE + (target == edge.firstNode() ? firstPressure : secondPressure));
			if (transferNanos > 0L) moveInventory(source, target, transferNanos, transferPlanner);
		}
	}

	/**
	 * Caps the transfer at the equalization split: a node pair ends at the shared pressure
	 * {@code P0 * (V0_a + V0_b) / (V_a + V_b)}; an ambient edge ends at exactly gauge zero.
	 * The raw flow is pre-capped to the movable inventory so the nanovolume conversion cannot
	 * overflow on extreme pressure or step-size inputs.
	 */
	private long equalizationClampedTransfer(
			int source, int target, double transferCubicMeters,
			double sourceAbsolutePressure, double targetAbsolutePressure
	) {
		if (source == AMBIENT_NODE) {
			long equilibriumNanos = ambientEquilibriumNanos(target, targetAbsolutePressure);
			transferCubicMeters = Math.min(transferCubicMeters,
					Math.max(0.0, (equilibriumNanos - standardVolumeNanos(target)) * AirNode.NANO_VOLUME));
		} else {
			transferCubicMeters = Math.min(transferCubicMeters, standardVolume(source));
		}
		long desired = AirNode.toNanos(transferCubicMeters);
		if (desired <= 0L) return 0L;
		if (source == AMBIENT_NODE) {
			long equilibrium = ambientEquilibriumNanos(target, targetAbsolutePressure);
			return Math.min(desired, Math.max(0L, equilibrium - standardVolumeNanos(target)));
		}
		if (target == AMBIENT_NODE) {
			long equilibrium = Math.round(standardVolumeNanos(source) * REFERENCE_PRESSURE / sourceAbsolutePressure);
			return Math.min(desired, Math.max(0L, standardVolumeNanos(source) - equilibrium));
		}
		double sourceSlope = sourceAbsolutePressure / standardVolumeNanos(source);
		double targetSlope = standardVolumeNanos(target) == 0L
				? REFERENCE_PRESSURE / availableVolume(target)
				: targetAbsolutePressure / standardVolumeNanos(target);
		long equilibriumSourceNanos = Math.round((standardVolumeNanos(source) + standardVolumeNanos(target))
				* targetSlope / (sourceSlope + targetSlope));
		return Math.min(desired, Math.max(0L, standardVolumeNanos(source) - equilibriumSourceNanos));
	}

	private long ambientEquilibriumNanos(int target, double targetAbsolutePressure) {
		if (standardVolumeNanos(target) == 0L && targetAbsolutePressure == 0.0) {
			return AirNode.toNanos(availableVolume(target));
		}
		return Math.round(standardVolumeNanos(target) * REFERENCE_PRESSURE / targetAbsolutePressure);
	}

	private void moveInventory(int source, int target, long nanos, TransferPlanner transferPlanner) {
		MassColumns before = liveMassColumns();
		if (source == AMBIENT_NODE) {
			AirNode targetNode = sealed(target);
			GasInventory created = registry.createAmbient(nanos);
			GasInventory targetAfter = targetNode.inventory().merge(created);
			TransferCommit thermalCommit = Objects.requireNonNull(
					transferPlanner.plan(source, target, created, GasInventory.EMPTY, targetAfter), "transfer commit");
			TransferMutation gasCommit = planTransferMutation(before, null, GasInventory.EMPTY,
					targetNode, targetAfter, created, GasInventory.EMPTY);
			thermalCommit.commitPrepared();
			commitTransferMutation(gasCommit);
			return;
		}
		AirNode sourceNode = sealed(source);
		long actual = Math.min(nanos, sourceNode.standardVolumeNanos());
		if (actual < nanos) transferClampEvents++;
		GasInventory.Split split = sourceNode.inventory().splitTotal(actual);
		GasInventory moved = split.first();
		if (target == AMBIENT_NODE) {
			TransferCommit thermalCommit = Objects.requireNonNull(
					transferPlanner.plan(source, target, moved, split.second(), GasInventory.EMPTY), "transfer commit");
			TransferMutation gasCommit = planTransferMutation(before, sourceNode, split.second(),
					null, GasInventory.EMPTY, GasInventory.EMPTY, moved);
			thermalCommit.commitPrepared();
			commitTransferMutation(gasCommit);
			return;
		}
		AirNode targetNode = sealed(target);
		GasInventory targetInventory = targetNode.inventory().merge(moved);
		TransferCommit thermalCommit = Objects.requireNonNull(
				transferPlanner.plan(source, target, moved, split.second(), targetInventory), "transfer commit");
		TransferMutation gasCommit = planTransferMutation(before, sourceNode, split.second(),
				targetNode, targetInventory, GasInventory.EMPTY, GasInventory.EMPTY);
		thermalCommit.commitPrepared();
		commitTransferMutation(gasCommit);
	}

	/**
	 * Merges source cavities into the target. Inventories sum exactly; edges touching a source
	 * are rewired to the target, dropping self loops and summing parallel areas.
	 */
	public void mergeNodes(int targetNodeId, int... sourceNodeIds) {
		if (sourceNodeIds.length == 0) throw new IllegalArgumentException("A merge needs at least one source");
		for (int sourceNodeId : sourceNodeIds) {
			if (sourceNodeId == targetNodeId) throw new IllegalArgumentException("Cannot merge a node into itself");
			sealed(sourceNodeId);
		}
		for (int first = 0; first < sourceNodeIds.length; first++) {
			for (int second = first + 1; second < sourceNodeIds.length; second++) {
				if (sourceNodeIds[first] == sourceNodeIds[second]) {
					throw new IllegalArgumentException("Cannot merge the same source node twice");
				}
			}
		}
		AirNode target = sealed(targetNodeId);
		GasInventory merged = target.inventory();
		for (int sourceNodeId : sourceNodeIds) merged = merged.merge(sealed(sourceNodeId).inventory());
		TreeMap<Integer, AirNode> replacement = new TreeMap<>(nodes);
		replacement.put(targetNodeId, new AirNode(target.availableVolume(), merged));
		for (int sourceNodeId : sourceNodeIds) {
			replacement.remove(sourceNodeId);
		}
		commitRemapNodes(planNodeMutation(replacement, rewiredEdges(targetNodeId, sourceNodeIds), nextNodeId,
				GasInventory.EMPTY, GasInventory.EMPTY));
	}

	/**
	 * Splits a cavity into children with the given available volumes. The first child keeps
	 * the source id and its edges; later children get fresh ids. Shares are proportional to
	 * the available volumes; each component's largest share receives its mass-column remainder.
	 */
	public int[] splitNode(int sourceNodeId, double... childAvailableVolumes) {
		AirNode source = sealed(sourceNodeId);
		if (childAvailableVolumes.length < 2) {
			throw new IllegalArgumentException("A split needs at least two children");
		}
		if ((long) nodes.size() + childAvailableVolumes.length - 1 > GasLimits.MAX_NODES_PER_REGION)
			throw new IllegalArgumentException("Gas split exceeds the regional node budget");
		int nextAfter = Math.addExact(nextNodeId, childAvailableVolumes.length - 1);
		boolean positiveVolume = false;
		var weights = new ArrayList<ExactRational>(childAvailableVolumes.length);
		for (double volume : childAvailableVolumes) {
			if (!(volume >= 0.0) || !Double.isFinite(volume)) {
				throw new IllegalArgumentException("Child available volumes must be finite and non-negative");
			}
			positiveVolume |= volume>0;
			SignedDyadic value = SignedDyadic.of(volume);
			weights.add(value.exponent()>=0 ? new ExactRational(value.significand().shiftLeft(value.exponent()),BigInteger.ONE)
					: new ExactRational(value.significand(),BigInteger.ONE.shiftLeft(-value.exponent())));
		}
		if (!positiveVolume) throw new IllegalArgumentException("A split needs positive available volume");
		List<GasInventory> partitions = source.inventory().partition(weights);
		TreeMap<Integer, AirNode> replacement = new TreeMap<>(nodes);
		int[] childIds = new int[childAvailableVolumes.length];
		for (int child = 0; child < childAvailableVolumes.length; child++) {
			int id = child == 0 ? sourceNodeId : nextNodeId + child - 1;
			replacement.put(id, new AirNode(childAvailableVolumes[child], partitions.get(child)));
			childIds[child] = id;
		}
		commitRemapNodes(planNodeMutation(replacement, new ArrayList<>(edges), nextAfter, GasInventory.EMPTY, GasInventory.EMPTY));
		return childIds;
	}

	/** Vents a cavity's whole inventory to ambient and removes it together with its edges. */
	public void ventNode(int nodeId) {
		AirNode node = sealed(nodeId);
		TreeMap<Integer, AirNode> replacement = new TreeMap<>(nodes);
		replacement.remove(nodeId);
		commitRemapNodes(planNodeMutation(replacement, rewiredEdges(-1, nodeId), nextNodeId,
				GasInventory.EMPTY, node.inventory()));
	}

	/**
	 * Rewires edges after node removal. A non-negative replacement adopts the edges of removed
	 * nodes (self loops dropped, parallel areas summed); a negative replacement drops every
	 * edge that touched a removed node, because the opening vanished with the node.
	 */
	private ArrayList<AirEdge> rewiredEdges(int replacement, int... removedNodes) {
		ArrayList<AirEdge> rewired = new ArrayList<>();
		for (AirEdge edge : edges) {
			boolean touchesRemoved = false;
			for (int removed : removedNodes) if (edge.touches(removed)) { touchesRemoved = true; break; }
			if (!touchesRemoved) { rewired.add(edge); continue; }
			if (replacement < 0) continue;
			int first = edge.firstNode();
			int second = edge.secondNode();
			for (int removed : removedNodes) {
				if (first == removed) first = replacement;
				if (second == removed) second = replacement;
			}
			if (first == second) continue;
			rewired.add(new AirEdge(first, second, edge.areaSquareMeters(), edge.dischargeCoefficient()));
		}
		return aggregateEdges(rewired);
	}

	/** Parallel openings retain their summed area and discharge-area product, independent of input order. */
	private static ArrayList<AirEdge> aggregateEdges(List<AirEdge> input) {
		var totals = new TreeMap<Long, EdgeTotals>();
		for (AirEdge edge : input) {
			int first = Math.min(edge.firstNode(), edge.secondNode()), second = Math.max(edge.firstNode(), edge.secondNode());
			long pair = ((long) first << 32) | Integer.toUnsignedLong(second);
			SignedDyadic area = SignedDyadic.of(edge.areaSquareMeters());
			SignedDyadic dischargeArea = area.multiply(SignedDyadic.of(edge.dischargeCoefficient()));
			EdgeTotals old = totals.get(pair);
			totals.put(pair, old == null ? new EdgeTotals(area, dischargeArea)
					: new EdgeTotals(old.area.add(area), old.dischargeArea.add(dischargeArea)));
		}
		var result = new ArrayList<AirEdge>(totals.size());
		for (var entry : totals.entrySet()) {
			EdgeTotals total = entry.getValue();
			int shift = total.dischargeArea.exponent() - total.area.exponent();
			BigInteger numerator = total.dischargeArea.significand(), denominator = total.area.significand();
			if (shift >= 0) numerator = numerator.shiftLeft(shift); else denominator = denominator.shiftLeft(-shift);
			result.add(new AirEdge((int) (entry.getKey() >>> 32), (int) (long) entry.getKey(),
					total.area.toDouble(), new ExactRational(numerator, denominator).toDouble()));
		}
		return result;
	}

	private record EdgeTotals(SignedDyadic area, SignedDyadic dischargeArea) { }

	/** Applies a topology plan whose explicit source/sink deltas close every exact component column. */
	public void remapNodes(
			double[] availableVolumes, List<GasInventory> inventories,
			GasInventory sourceDelta, GasInventory sinkDelta
	) {
		commitRemapNodes(planRemapNodes(availableVolumes, inventories, sourceDelta, sinkDelta, List.of()));
	}

	/** Completes all topology, closure and ledger calculations without changing the live network. */
	public RemapPlan planRemapNodes(
			double[] availableVolumes, List<GasInventory> inventories,
			GasInventory sourceDelta, GasInventory sinkDelta, List<AirEdge> replacementEdges
	) {
		if (sourceDelta == null || sinkDelta == null) throw new IllegalArgumentException("Gas remap plan is incomplete");
		return planRemapNodes(availableVolumes, inventories, GasAuditLedger.of(sourceDelta), GasAuditLedger.of(sinkDelta), replacementEdges);
	}

	/** Region source/sink summaries retain exact masses and the registry-wide component budget. */
	public RemapPlan planRemapNodes(
			double[] availableVolumes, List<GasInventory> inventories,
			GasAuditLedger sourceDelta, GasAuditLedger sinkDelta, List<AirEdge> replacementEdges
	) {
		return planRemapNodes(availableVolumes, inventories, sourceDelta, sinkDelta, replacementEdges, null);
	}

	/** Prepares the successor topology against the already planned phase columns. */
	public RemapPlan planRemapAfterPhase(PhaseMutationPlan phase, double[] availableVolumes,
			List<GasInventory> inventories, GasAuditLedger sourceDelta, GasAuditLedger sinkDelta,
			List<AirEdge> replacementEdges) {
		requirePhaseMutationCurrent(phase);
		return planRemapNodes(availableVolumes, inventories, sourceDelta, sinkDelta, replacementEdges, phase);
	}

	private RemapPlan planRemapNodes(double[] availableVolumes, List<GasInventory> inventories,
			GasAuditLedger sourceDelta, GasAuditLedger sinkDelta, List<AirEdge> replacementEdges,
			PhaseMutationPlan phase) {
		if (availableVolumes.length != inventories.size()) throw new IllegalArgumentException("Gas remap slots disagree");
		if (availableVolumes.length > GasLimits.MAX_NODES_PER_REGION)
			throw new IllegalArgumentException("Gas remap exceeds the regional node budget");
		if (sourceDelta == null || sinkDelta == null || replacementEdges == null) {
			throw new IllegalArgumentException("Gas remap plan is incomplete");
		}
		for (GasInventory inventory : inventories) registry.requireCompatible(inventory);
		for (var entry : sourceDelta.entries()) registry.requireCompatible(entry.componentId(), entry.species());
		for (var entry : sinkDelta.entries()) registry.requireCompatible(entry.componentId(), entry.species());
		TreeMap<Integer, AirNode> replacementNodes = new TreeMap<>();
		for (int slot = 0; slot < availableVolumes.length; slot++) {
			if (!(Double.isFinite(availableVolumes[slot]) && availableVolumes[slot] >= 0.0) || inventories.get(slot) == null) {
				throw new IllegalArgumentException("Invalid gas remap slot");
			}
			replacementNodes.put(slot + 1, new AirNode(availableVolumes[slot], inventories.get(slot)));
		}
		ArrayList<AirEdge> plannedEdges = new ArrayList<>(replacementEdges.size());
		for (AirEdge edge : replacementEdges) {
			if (edge == null || edge.firstNode() < AMBIENT_NODE || edge.secondNode() < AMBIENT_NODE
					|| edge.firstNode() > inventories.size() || edge.secondNode() > inventories.size()) {
				throw new IllegalArgumentException("Gas remap edge names an unknown node");
			}
			plannedEdges.add(edge);
		}
		ArrayList<GasInventory> baseline = new ArrayList<>(nodes.size());
		for (AirNode node : nodes.values()) baseline.add(phase != null && node == phase.mutation.sourceNode()
				? phase.inventoryAfter : node.inventory());
		MassColumns before = massColumns(baseline);
		GasRegionAudit.requireClosure(GasRegionAudit.aggregate(baseline), GasRegionAudit.aggregate(inventories), sourceDelta, sinkDelta);
		MassColumns after = massColumns(inventories);
		MassColumns source = massColumns(sourceDelta), sink = massColumns(sinkDelta);
		MassColumns delta = mutationDelta(before, after, source, sink);
		return new RemapPlan(this, phase == null ? revision : Math.addExact(revision, 1), phase, replacementNodes, aggregateEdges(plannedEdges), inventories.size() + 1,
				(phase == null ? massRoundoff : phase.mutation.massRoundoffAfter()).add(delta.mass()),
				(phase == null ? residualRoundoff : phase.mutation.residualRoundoffAfter()).add(delta.residual()),
				(phase == null ? externalSource : phase.mutation.externalSourceAfter()).add(sourceDelta),
				(phase == null ? escapedSink : phase.mutation.escapedSinkAfter()).add(sinkDelta));
	}

	/** Checks a prepared successor before the phase performs its first write. */
	public void requireRemapAfterPhaseCurrent(RemapPlan remap, PhaseMutationPlan phase) {
		requirePhaseMutationCurrent(phase);
		if (remap == null || remap.owner != this || remap.parentPhase != phase || remap.baseRevision != Math.addExact(revision, 1))
			throw new IllegalStateException("Gas phase successor topology is stale");
	}

	/** Rejects a remap planned against another owner or an older authoritative generation. */
	public void requireCanCommitRemap(RemapPlan plan) {
		if (plan == null || plan.owner != this) {
			throw new IllegalArgumentException("Gas remap plan belongs to another network");
		}
		if (plan.baseRevision != revision) {
			throw new IllegalStateException("Gas network changed after remap planning");
		}
		if (plan.parentPhase != null && plan.parentPhase != lastCommittedPhasePlan)
			throw new IllegalStateException("Gas phase successor does not match the committed phase");
	}

	/** Publishes a previously validated remap using assignments only. */
	public void commitRemapNodes(RemapPlan plan) {
		requireCanCommitRemap(plan);
		nodes = plan.replacementNodes;
		edges = plan.replacementEdges;
		nextNodeId = plan.nextNodeId;
		massRoundoff = plan.massRoundoffAfter;
		residualRoundoff = plan.residualRoundoffAfter;
		externalSource = plan.externalSourceAfter;
		escapedSink = plan.escapedSinkAfter;
		lastCommittedPhasePlan = null;
		revision++;
	}

	/** Restores a previously validated complete gas generation into a fresh runtime network. */
	public void restoreNodes(
			double[] availableVolumes, List<GasInventory> inventories,
			GasAuditLedger source, GasAuditLedger sink,
			SignedDyadic savedMassRoundoff, SignedDyadic savedResidualRoundoff
	) {
		if (!nodes.isEmpty() || !externalSource.entries().isEmpty() || !escapedSink.entries().isEmpty()) {
			throw new IllegalStateException("Gas restore requires a fresh network");
		}
		if (availableVolumes.length != inventories.size() || availableVolumes.length > GasLimits.MAX_NODES_PER_REGION || source == null || sink == null
				|| savedMassRoundoff == null || savedResidualRoundoff == null) {
			throw new IllegalArgumentException("Gas restore state is incomplete");
		}
		for (int slot = 0; slot < availableVolumes.length; slot++) {
			if (!(Double.isFinite(availableVolumes[slot]) && availableVolumes[slot] >= 0.0) || inventories.get(slot) == null) {
				throw new IllegalArgumentException("Invalid restored gas node");
			}
		}
		for (GasInventory inventory : inventories) registry.requireCompatible(inventory);
		for (GasAuditLedger.Entry entry : source.entries()) registry.requireCompatible(entry.componentId(), entry.species());
		for (GasAuditLedger.Entry entry : sink.entries()) registry.requireCompatible(entry.componentId(), entry.species());
		TreeMap<Integer, AirNode> replacement = new TreeMap<>();
		for (int slot = 0; slot < inventories.size(); slot++) {
			replacement.put(slot + 1, new AirNode(availableVolumes[slot], inventories.get(slot)));
		}
		if (!GasRegionAudit.closed(GasRegionAudit.aggregate(inventories), source, sink, savedMassRoundoff, savedResidualRoundoff))
			throw new IllegalArgumentException("Restored gas audit does not close");
		nodes = replacement; edges = new ArrayList<>(); nextNodeId = inventories.size() + 1;
		externalSource = source; escapedSink = sink;
		massRoundoff = savedMassRoundoff; residualRoundoff = savedResidualRoundoff;
		revision++;
	}

	public long sealedInventoryNanos() {
		long sum = 0L;
		for (AirNode node : nodes.values()) sum = Math.addExact(sum, node.standardVolumeNanos());
		return sum;
	}

	/** Immutable aggregate of every sealed component column; ambient is deliberately absent. */
	public GasAuditLedger sealedInventory() { return GasRegionAudit.aggregate(nodes.values().stream().map(AirNode::inventory).toList()); }

	public long ventedNanos() { return escapedSink.standardVolumeNanos(); }

	public long drawnFromAmbientNanos() { return externalSource.standardVolumeNanos(); }

	public GasAuditLedger externalSource() { return externalSource; }
	public GasAuditLedger escapedSink() { return escapedSink; }
	public SignedDyadic massRoundoff() { return massRoundoff; }
	public SignedDyadic residualRoundoff() { return residualRoundoff; }

	/** Full exact-column audit used by persistence and validation boundaries. */
	public boolean auditClosed() {
		return GasRegionAudit.closed(sealedInventory(), externalSource, escapedSink, massRoundoff, residualRoundoff);
	}

	public int transferClampEvents() { return transferClampEvents; }

	private int sealedId(int nodeId) {
		if (nodeId == AMBIENT_NODE || !nodes.containsKey(nodeId)) {
			throw new IllegalArgumentException("Unknown sealed gas node " + nodeId);
		}
		return nodeId;
	}

	private AirNode sealed(int nodeId) { return nodes.get(sealedId(nodeId)); }

	/** Stages public node edits and all fallible cumulative accounting before their first publication. */
	private RemapPlan planNodeMutation(TreeMap<Integer, AirNode> replacement, ArrayList<AirEdge> replacementEdges,
	                                 int nextAfter, GasInventory sourceDelta, GasInventory sinkDelta) {
		if (replacement.size() > GasLimits.MAX_NODES_PER_REGION)
			throw new IllegalArgumentException("Gas mutation exceeds the regional node budget");
		MassColumns before = liveMassColumns(), after = massColumns(replacement.values().stream().map(AirNode::inventory).toList());
		MassColumns source = massColumns(sourceDelta), sink = massColumns(sinkDelta);
		MassColumns delta = mutationDelta(before, after, source, sink);
		return new RemapPlan(this, revision, null, replacement, replacementEdges, nextAfter,
				massRoundoff.add(delta.mass()), residualRoundoff.add(delta.residual()),
				externalSource.add(sourceDelta), escapedSink.add(sinkDelta));
	}

	/** Performs every fallible ledger calculation before either gas or thermal state is published. */
	private TransferMutation planTransferMutation(
			MassColumns before,
			AirNode sourceNode, GasInventory sourceAfter,
			AirNode targetNode, GasInventory targetAfter,
			GasInventory sourceDelta, GasInventory sinkDelta
	) {
		MassColumns after = liveMassColumnsReplacing(sourceNode, sourceAfter, targetNode, targetAfter);
		MassColumns source = massColumns(sourceDelta), sink = massColumns(sinkDelta);
		MassColumns delta = mutationDelta(before, after, source, sink);
		return new TransferMutation(sourceNode, sourceAfter, targetNode, targetAfter,
				massRoundoff.add(delta.mass()), residualRoundoff.add(delta.residual()),
				externalSource.add(sourceDelta), escapedSink.add(sinkDelta));
	}

	private static MassColumns mutationDelta(MassColumns before, MassColumns after, MassColumns source, MassColumns sink) {
		return new MassColumns(after.mass().add(sink.mass()).subtract(before.mass()).subtract(source.mass()),
				after.residual().add(sink.residual()).subtract(before.residual()).subtract(source.residual()));
	}

	private void commitTransferMutation(TransferMutation mutation) {
		if (mutation.sourceNode() != null) mutation.sourceNode().setInventory(mutation.sourceAfter());
		if (mutation.targetNode() != null) mutation.targetNode().setInventory(mutation.targetAfter());
		massRoundoff = mutation.massRoundoffAfter();
		residualRoundoff = mutation.residualRoundoffAfter();
		externalSource = mutation.externalSourceAfter();
		escapedSink = mutation.escapedSinkAfter();
		revision++;
	}

	private MassColumns liveMassColumns() {
		SignedDyadic mass = SignedDyadic.ZERO, residual = SignedDyadic.ZERO;
		for (AirNode node : nodes.values()) for (GasComponentAmount amount : node.inventory().components()) {
			mass = mass.add(SignedDyadic.of(amount.mass())); residual = residual.add(SignedDyadic.of(amount.massResidual()));
		}
		return new MassColumns(mass, residual);
	}

	private MassColumns liveMassColumnsReplacing(
			AirNode sourceNode, GasInventory sourceAfter,
			AirNode targetNode, GasInventory targetAfter
	) {
		SignedDyadic mass = SignedDyadic.ZERO, residual = SignedDyadic.ZERO;
		for (AirNode node : nodes.values()) {
			GasInventory inventory = node == sourceNode ? sourceAfter : node == targetNode ? targetAfter : node.inventory();
			for (GasComponentAmount amount : inventory.components()) {
				mass = mass.add(SignedDyadic.of(amount.mass()));
				residual = residual.add(SignedDyadic.of(amount.massResidual()));
			}
		}
		return new MassColumns(mass, residual);
	}

	private static MassColumns massColumns(GasInventory inventory) {
		SignedDyadic mass = SignedDyadic.ZERO, residual = SignedDyadic.ZERO;
		for (GasComponentAmount amount : inventory.components()) {
			mass = mass.add(SignedDyadic.of(amount.mass())); residual = residual.add(SignedDyadic.of(amount.massResidual()));
		}
		return new MassColumns(mass, residual);
	}

	private static MassColumns massColumns(List<GasInventory> inventories) {
		SignedDyadic mass = SignedDyadic.ZERO, residual = SignedDyadic.ZERO;
		for (GasInventory inventory : inventories) for (GasComponentAmount amount : inventory.components()) {
			mass = mass.add(SignedDyadic.of(amount.mass()));
			residual = residual.add(SignedDyadic.of(amount.massResidual()));
		}
		return new MassColumns(mass, residual);
	}

	private static MassColumns massColumns(GasAuditLedger ledger) {
		SignedDyadic mass = SignedDyadic.ZERO, residual = SignedDyadic.ZERO;
		for (GasAuditLedger.Entry entry : ledger.entries()) {
			mass = mass.add(entry.mass()); residual = residual.add(entry.massResidual());
		}
		return new MassColumns(mass, residual);
	}

	private record MassColumns(SignedDyadic mass, SignedDyadic residual) { }
	public static final class PhaseCapacityExceededException extends IllegalArgumentException {
		private PhaseCapacityExceededException(RuntimeException cause) {
			super("Gas phase inventory or cumulative audit exceeds its representation", cause);
		}
	}

	public static final class PhaseMutationPlan {
		private final AirNetwork owner;
		private final long baseRevision;
		private final GasInventory inventoryAfter;
		private final TransferMutation mutation;

		private PhaseMutationPlan(AirNetwork owner, long baseRevision, GasInventory inventoryAfter, TransferMutation mutation) {
			this.owner = owner; this.baseRevision = baseRevision;
			this.inventoryAfter = inventoryAfter; this.mutation = mutation;
		}
		public GasInventory inventoryAfter() { return inventoryAfter; }
	}

	public static final class RemapPlan {
		private final AirNetwork owner;
		private final long baseRevision;
		private final PhaseMutationPlan parentPhase;
		private final TreeMap<Integer, AirNode> replacementNodes;
		private final ArrayList<AirEdge> replacementEdges;
		private final int nextNodeId;
		private final SignedDyadic massRoundoffAfter;
		private final SignedDyadic residualRoundoffAfter;
		private final GasAuditLedger externalSourceAfter;
		private final GasAuditLedger escapedSinkAfter;

		public GasInventory inventory(int nodeId) { return replacementNodes.get(nodeId).inventory(); }
		public double availableVolume(int nodeId) { return replacementNodes.get(nodeId).availableVolume(); }

		private RemapPlan(
				AirNetwork owner, long baseRevision, PhaseMutationPlan parentPhase, TreeMap<Integer, AirNode> replacementNodes,
				ArrayList<AirEdge> replacementEdges, int nextNodeId,
				SignedDyadic massRoundoffAfter, SignedDyadic residualRoundoffAfter,
				GasAuditLedger externalSourceAfter, GasAuditLedger escapedSinkAfter
		) {
			this.owner = owner;
			this.baseRevision = baseRevision;
			this.parentPhase = parentPhase;
			this.replacementNodes = replacementNodes;
			this.replacementEdges = replacementEdges;
			this.nextNodeId = nextNodeId;
			this.massRoundoffAfter = massRoundoffAfter;
			this.residualRoundoffAfter = residualRoundoffAfter;
			this.externalSourceAfter = externalSourceAfter;
			this.escapedSinkAfter = escapedSinkAfter;
			externalSourceAfter.standardVolumeNanos(); escapedSinkAfter.standardVolumeNanos();
		}
	}
	private record TransferMutation(
			AirNode sourceNode, GasInventory sourceAfter,
			AirNode targetNode, GasInventory targetAfter,
			SignedDyadic massRoundoffAfter, SignedDyadic residualRoundoffAfter,
			GasAuditLedger externalSourceAfter, GasAuditLedger escapedSinkAfter
	) {
		private TransferMutation {
			externalSourceAfter.standardVolumeNanos();
			escapedSinkAfter.standardVolumeNanos();
		}
	}
}
