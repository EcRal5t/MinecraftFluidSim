package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.thermal.SignedDyadic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
	/** Numerical ceiling for cavity gauge pressure; guards the fully-filled cavity singularity. */
	public static final double MAX_GAUGE_PRESSURE = 20.0 * REFERENCE_PRESSURE;
	/** Numerical floor for cavity gauge pressure; a cavity cannot be pulled below near-vacuum. */
	public static final double MIN_GAUGE_PRESSURE = -0.99 * REFERENCE_PRESSURE;
	/** Available-volume regularisation (m³) for the isothermal state equation; equal to one nanovolume quantum so a newly discovered cavity always starts at gauge zero. */
	public static final double MIN_AVAILABLE_VOLUME = 1.0E-9;
	/** Constant air density (kg/m³) used by isothermal orifice flow. */
	public static final double AIR_DENSITY = 1.2;
	/** The ambient reservoir: gauge pressure zero, unbounded inventory, never stored as a node. */
	public static final int AMBIENT_NODE = 0;

	/** Converts cubic metres to exact nanovolume quanta. */
	public static long nanos(double cubicMeters) { return AirNode.toNanos(cubicMeters); }

	/** Converts nanovolume quanta to cubic metres. */
	public static double cubicMeters(long nanos) { return nanos * AirNode.NANO_VOLUME; }

	private TreeMap<Integer, AirNode> nodes = new TreeMap<>();
	private List<AirEdge> edges = new ArrayList<>();
	private final GasRegistry registry;
	private int nextNodeId = 1;
	private GasAuditLedger escapedSink = GasAuditLedger.EMPTY;
	private GasAuditLedger externalSource = GasAuditLedger.EMPTY;
	private SignedDyadic massRoundoff = SignedDyadic.ZERO;
	private SignedDyadic residualRoundoff = SignedDyadic.ZERO;
	private int pressureFloorEvents;
	private int pressureCeilingEvents;
	private int transferClampEvents;

	public AirNetwork() { this(BuiltinGasDefinitions.createRegistry()); }

	public AirNetwork(GasRegistry registry) { this.registry = Objects.requireNonNull(registry, "registry"); }

	public GasRegistry registry() { return registry; }

	/** Creates a sealed cavity node; the initial component inventory is booked as drawn from ambient. */
	public int addNode(double availableVolume, double standardVolume) {
		MassColumns before = liveMassColumns();
		int id = nextNodeId++;
		GasInventory created = registry.createAmbient(AirNode.toNanos(standardVolume));
		AirNode node = new AirNode(availableVolume, created);
		nodes.put(id, node);
		finishMutation(before, created, GasInventory.EMPTY);
		return id;
	}

	public boolean hasNode(int nodeId) { return nodeId != AMBIENT_NODE && nodes.containsKey(nodeId); }

	public List<Integer> nodeIds() { return new ArrayList<>(nodes.keySet()); }

	public int nodeCount() { return nodes.size(); }

	public double availableVolume(int nodeId) { return sealed(nodeId).availableVolume(); }

	public void updateAvailableVolume(int nodeId, double availableVolume) {
		sealed(nodeId).setAvailableVolume(availableVolume);
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
		if (addition == null || addition.isEmpty()) throw new IllegalArgumentException("Gas phase source is empty");
		registry.requireCompatible(addition);
		AirNode node = sealed(nodeId); MassColumns before = liveMassColumns();
		node.setInventory(node.inventory().merge(addition));
		finishMutation(before, addition, GasInventory.EMPTY);
	}

	/** Books material leaving the gas owner into another phase after an exact preview. */
	public void commitPhaseSink(int nodeId, GasInventory expectedRemoval) {
		if (expectedRemoval == null || expectedRemoval.components().size() != 1) {
			throw new IllegalArgumentException("Gas phase sink must name one component portion");
		}
		GasComponentAmount component = expectedRemoval.components().getFirst();
		AirNode node = sealed(nodeId);
		GasInventory.Split split = node.inventory().splitComponent(component.componentId(),
				component.standardVolumeNanos(), component.mass(), component.massResidual());
		if (!split.first().equals(expectedRemoval)) throw new IllegalStateException("Gas phase sink preview changed");
		MassColumns before = liveMassColumns(); node.setInventory(split.second());
		finishMutation(before, GasInventory.EMPTY, split.first());
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
		MassColumns before = liveMassColumns();
		GasInventory source = GasInventory.EMPTY, sink = GasInventory.EMPTY;
		if (target > current) {
			source = registry.createAmbient(target - current);
			node.setInventory(node.inventory().merge(source));
		} else {
			GasInventory.Split split = node.inventory().splitTotal(target);
			node.setInventory(split.first()); sink = split.second();
		}
		finishMutation(before, source, sink);
	}

	/** Adds an orifice edge; parallel openings between the same pair of nodes sum their areas. */
	public void addOrMergeEdge(int firstNode, int secondNode, double areaSquareMeters, double dischargeCoefficient) {
		if (firstNode == secondNode) throw new IllegalArgumentException("An edge needs two distinct nodes");
		int existing = findEdge(firstNode, secondNode);
		if (existing >= 0) {
			AirEdge edge = edges.get(existing);
			double combinedArea = edge.areaSquareMeters() + areaSquareMeters;
			double combinedDischarge = (edge.dischargeCoefficient() * edge.areaSquareMeters()
					+ dischargeCoefficient * areaSquareMeters) / combinedArea;
			edges.set(existing, new AirEdge(firstNode, secondNode, combinedArea, combinedDischarge));
			return;
		}
		edges.add(new AirEdge(firstNode, secondNode, areaSquareMeters, dischargeCoefficient));
	}

	public boolean removeEdge(int firstNode, int secondNode) {
		int existing = findEdge(firstNode, secondNode);
		if (existing < 0) return false;
		edges.remove(existing);
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
	 * Isothermal state equation: {@code p = P0 * (V0 / max(V, V_min) - 1)}, clamped to the
	 * numerical pressure window. Ambient is exactly zero with no state. Floor and ceiling
	 * counters count queries that hit the regularisation window.
	 */
	public double gaugePressure(int nodeId) {
		double pressure = rawGaugePressure(nodeId);
		if (pressure > MAX_GAUGE_PRESSURE) { pressureCeilingEvents++; return MAX_GAUGE_PRESSURE; }
		if (pressure < MIN_GAUGE_PRESSURE) { pressureFloorEvents++; return MIN_GAUGE_PRESSURE; }
		return pressure;
	}

	/** Same derived pressure without advancing numerical-window diagnostics; safe for Query/probe consumers. */
	public double observedGaugePressure(int nodeId) {
		double pressure = rawGaugePressure(nodeId);
		if (pressure > MAX_GAUGE_PRESSURE) return MAX_GAUGE_PRESSURE;
		if (pressure < MIN_GAUGE_PRESSURE) return MIN_GAUGE_PRESSURE;
		return pressure;
	}

	private double rawGaugePressure(int nodeId) {
		if (nodeId == AMBIENT_NODE) return 0.0;
		AirNode node = sealed(nodeId);
		double available = Math.max(node.availableVolume(), MIN_AVAILABLE_VOLUME);
		return REFERENCE_PRESSURE * (node.standardVolume() / available - 1.0);
	}

	/**
	 * Advances orifice transport by one step. Flow follows the CONTAM power law
	 * {@code Q = Cd * A * sqrt(2 * dp / rho_air)}; the transferred standard volume is clamped
	 * to the equalization split, so the pressure difference decays monotonically and never
	 * overshoots equilibrium. Edges apply in list order with inventories refreshed in place.
	 */
	public void stepEdges(double dt) {
		stepEdges(dt, new NodeThermodynamics() {
			@Override public double gaugePressure(int nodeId) { return AirNetwork.this.gaugePressure(nodeId); }
			@Override public double density(int nodeId) { return AIR_DENSITY; }
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
			double sourcePressure = Math.max(firstPressure, secondPressure);
			double density = source == AMBIENT_NODE ? AIR_DENSITY : thermodynamics.density(source);
			double flow = edge.dischargeCoefficient() * edge.areaSquareMeters()
					* Math.sqrt(2.0 * delta / density);
			double transferCubicMeters = flow * dt * (REFERENCE_PRESSURE + sourcePressure) / REFERENCE_PRESSURE;
			long transferNanos = equalizationClampedTransfer(source, target, transferCubicMeters,
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
			double equilibriumNanos = standardVolumeNanos(target) * REFERENCE_PRESSURE / targetAbsolutePressure;
			transferCubicMeters = Math.min(transferCubicMeters,
					Math.max(0.0, (equilibriumNanos - standardVolumeNanos(target)) * AirNode.NANO_VOLUME));
		} else {
			transferCubicMeters = Math.min(transferCubicMeters, standardVolume(source));
		}
		long desired = AirNode.toNanos(transferCubicMeters);
		if (desired <= 0L) return 0L;
		if (source == AMBIENT_NODE) {
			long equilibrium = Math.round(standardVolumeNanos(target) * REFERENCE_PRESSURE / targetAbsolutePressure);
			return Math.min(desired, Math.max(0L, equilibrium - standardVolumeNanos(target)));
		}
		if (target == AMBIENT_NODE) {
			long equilibrium = Math.round(standardVolumeNanos(source) * REFERENCE_PRESSURE / sourceAbsolutePressure);
			return Math.min(desired, Math.max(0L, standardVolumeNanos(source) - equilibrium));
		}
		double sourceSlope = sourceAbsolutePressure / standardVolumeNanos(source);
		double targetSlope = targetAbsolutePressure / standardVolumeNanos(target);
		long equilibriumSourceNanos = Math.round((standardVolumeNanos(source) + standardVolumeNanos(target))
				* targetSlope / (sourceSlope + targetSlope));
		return Math.min(desired, Math.max(0L, standardVolumeNanos(source) - equilibriumSourceNanos));
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
		MassColumns before = liveMassColumns();
		target.setInventory(merged);
		for (int sourceNodeId : sourceNodeIds) {
			nodes.remove(sourceNodeId);
		}
		rewireEdges(targetNodeId, sourceNodeIds);
		finishMutation(before, GasInventory.EMPTY, GasInventory.EMPTY);
	}

	/**
	 * Splits a cavity into children with the given available volumes. The first child keeps
	 * the source id and its edges; later children get fresh ids. Shares are proportional to
	 * the available volumes and the last child receives the exact remainder.
	 */
	public int[] splitNode(int sourceNodeId, double... childAvailableVolumes) {
		AirNode source = sealed(sourceNodeId);
		if (childAvailableVolumes.length < 2) {
			throw new IllegalArgumentException("A split needs at least two children");
		}
		double availableSum = 0.0;
		for (double volume : childAvailableVolumes) {
			if (!(volume >= 0.0) || Double.isNaN(volume)) {
				throw new IllegalArgumentException("Child available volumes must be finite and non-negative");
			}
			availableSum += volume;
		}
		if (!(availableSum > 0.0)) throw new IllegalArgumentException("A split needs positive available volume");
		long[] weights = new long[childAvailableVolumes.length];
		for (int child = 0; child < childAvailableVolumes.length; child++) {
			weights[child] = childAvailableVolumes[child] == 0.0 ? 0L
					: Math.max(1L, AirNode.toNanos(childAvailableVolumes[child]));
		}
		List<GasInventory> partitions = source.inventory().partition(weights);
		MassColumns before = liveMassColumns();
		int[] childIds = new int[childAvailableVolumes.length];
		childIds[0] = sourceNodeId;
		for (int child = 0; child < childAvailableVolumes.length; child++) {
			if (child == 0) {
				source.setInventory(partitions.get(child));
				source.setAvailableVolume(childAvailableVolumes[child]);
			} else {
				int id = nextNodeId++;
				nodes.put(id, new AirNode(childAvailableVolumes[child], partitions.get(child)));
				childIds[child] = id;
			}
		}
		finishMutation(before, GasInventory.EMPTY, GasInventory.EMPTY);
		return childIds;
	}

	/** Vents a cavity's whole inventory to ambient and removes it together with its edges. */
	public void ventNode(int nodeId) {
		MassColumns before = liveMassColumns();
		AirNode node = nodes.remove(sealedId(nodeId));
		rewireEdges(-1, nodeId);
		finishMutation(before, GasInventory.EMPTY, node.inventory());
	}

	/**
	 * Rewires edges after node removal. A non-negative replacement adopts the edges of removed
	 * nodes (self loops dropped, parallel areas summed); a negative replacement drops every
	 * edge that touched a removed node, because the opening vanished with the node.
	 */
	private void rewireEdges(int replacement, int... removedNodes) {
		List<AirEdge> rewired = new ArrayList<>();
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
			boolean mergedIntoExisting = false;
			for (int i = 0; i < rewired.size(); i++) {
				AirEdge candidate = rewired.get(i);
				boolean samePair = (candidate.firstNode() == first && candidate.secondNode() == second)
						|| (candidate.firstNode() == second && candidate.secondNode() == first);
				if (!samePair) continue;
				rewired.set(i, new AirEdge(first, second,
						candidate.areaSquareMeters() + edge.areaSquareMeters(), edge.dischargeCoefficient()));
				mergedIntoExisting = true;
				break;
			}
			if (!mergedIntoExisting) {
				rewired.add(new AirEdge(first, second, edge.areaSquareMeters(), edge.dischargeCoefficient()));
			}
		}
		edges.clear();
		edges.addAll(rewired);
	}

	/**
	 * Replaces the whole node set with slot-ordered nodes (ids 1..n) and clears all edges.
	 * The caller's topology mapping is responsible for inventory conservation; the net
	 * difference between the removed and replaced sums books as drawn (growth, e.g. newly
	 * discovered cavities starting at ambient equilibrium) or vented (loss, e.g. a cavity
	 * that opened to ambient or vanished under liquid), keeping the ledger identity exact.
	 */
	public void remapNodes(double[] availableVolumes, long[] inventoriesNanos) {
		if (availableVolumes.length != inventoriesNanos.length) {
			throw new IllegalArgumentException("Slot arrays must have equal length");
		}
		for (int slot = 0; slot < availableVolumes.length; slot++) {
			if (!(availableVolumes[slot] >= 0.0) || Double.isNaN(availableVolumes[slot])
					|| Double.isInfinite(availableVolumes[slot])) {
				throw new IllegalArgumentException("Available volume must be finite and non-negative");
			}
			if (inventoriesNanos[slot] < 0L) {
				throw new IllegalArgumentException("Inventory cannot be negative");
			}
		}
		long oldSum = sealedInventoryNanos(), newSum = 0L;
		ArrayList<GasInventory> replacement = new ArrayList<>(inventoriesNanos.length);
		for (long inventory : inventoriesNanos) { replacement.add(registry.createAmbient(inventory)); newSum = Math.addExact(newSum, inventory); }
		GasInventory source = newSum > oldSum ? registry.createAmbient(newSum - oldSum) : GasInventory.EMPTY;
		GasInventory sink = oldSum > newSum ? aggregateNodes().splitTotal(oldSum - newSum).first() : GasInventory.EMPTY;
		remapNodes(availableVolumes, replacement, source, sink);
	}

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
		if (availableVolumes.length != inventories.size()) throw new IllegalArgumentException("Gas remap slots disagree");
		if (sourceDelta == null || sinkDelta == null || replacementEdges == null) {
			throw new IllegalArgumentException("Gas remap plan is incomplete");
		}
		for (GasInventory inventory : inventories) registry.requireCompatible(inventory);
		registry.requireCompatible(sourceDelta);
		registry.requireCompatible(sinkDelta);
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
		MassColumns before = liveMassColumns();
		requireExactRemapClosure(aggregateNodes(), aggregate(inventories), sourceDelta, sinkDelta);
		MassColumns after = massColumns(inventories);
		MassColumns source = massColumns(sourceDelta), sink = massColumns(sinkDelta);
		return new RemapPlan(this, replacementNodes, plannedEdges, inventories.size() + 1,
				massRoundoff.add(after.mass()).add(sink.mass()).subtract(before.mass()).subtract(source.mass()),
				residualRoundoff.add(after.residual()).add(sink.residual())
						.subtract(before.residual()).subtract(source.residual()),
				externalSource.add(sourceDelta), escapedSink.add(sinkDelta));
	}

	/** Publishes a previously validated remap using assignments only. */
	public void commitRemapNodes(RemapPlan plan) {
		if (plan == null || plan.owner != this) throw new IllegalArgumentException("Gas remap plan belongs to another network");
		nodes = plan.replacementNodes;
		edges = plan.replacementEdges;
		nextNodeId = plan.nextNodeId;
		massRoundoff = plan.massRoundoffAfter;
		residualRoundoff = plan.residualRoundoffAfter;
		externalSource = plan.externalSourceAfter;
		escapedSink = plan.escapedSinkAfter;
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
		if (availableVolumes.length != inventories.size() || source == null || sink == null
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
		nodes.clear(); edges.clear();
		for (int slot = 0; slot < inventories.size(); slot++) {
			nodes.put(slot + 1, new AirNode(availableVolumes[slot], inventories.get(slot)));
		}
		nextNodeId = inventories.size() + 1;
		externalSource = source; escapedSink = sink;
		massRoundoff = savedMassRoundoff; residualRoundoff = savedResidualRoundoff;
		if (!auditClosed()) {
			nodes.clear(); externalSource = GasAuditLedger.EMPTY; escapedSink = GasAuditLedger.EMPTY;
			massRoundoff = SignedDyadic.ZERO; residualRoundoff = SignedDyadic.ZERO; nextNodeId = 1;
			throw new IllegalArgumentException("Restored gas audit does not close");
		}
	}

	public long sealedInventoryNanos() {
		long sum = 0L;
		for (Map.Entry<Integer, AirNode> entry : nodes.entrySet()) sum += entry.getValue().standardVolumeNanos();
		return sum;
	}

	/** Immutable aggregate of every sealed component column; ambient is deliberately absent. */
	public GasInventory sealedInventory() { return aggregateNodes(); }

	public long ventedNanos() { return escapedSink.standardVolumeNanos(); }

	public long drawnFromAmbientNanos() { return externalSource.standardVolumeNanos(); }

	public GasAuditLedger externalSource() { return externalSource; }
	public GasAuditLedger escapedSink() { return escapedSink; }
	public SignedDyadic massRoundoff() { return massRoundoff; }
	public SignedDyadic residualRoundoff() { return residualRoundoff; }

	/** Full exact-column audit used by persistence and validation boundaries. */
	public boolean auditClosed() {
		TreeMap<String, ExactColumns> live = exactColumns(aggregateNodes());
		TreeMap<String, ExactColumns> source = exactColumns(externalSource);
		TreeMap<String, ExactColumns> sink = exactColumns(escapedSink);
		java.util.TreeSet<String> ids = new java.util.TreeSet<>(); ids.addAll(live.keySet()); ids.addAll(source.keySet()); ids.addAll(sink.keySet());
		for (String id : ids) if (!ExactColumns.add(live.get(id), sink.get(id)).equals(source.getOrDefault(id, ExactColumns.EMPTY))) {
			return false;
		}
		MassColumns liveMass = liveMassColumns(), sourceMass = massColumns(externalSource), sinkMass = massColumns(escapedSink);
		return liveMass.mass().add(sinkMass.mass()).equals(sourceMass.mass().add(massRoundoff))
				&& liveMass.residual().add(sinkMass.residual()).equals(sourceMass.residual().add(residualRoundoff));
	}

	/** Read-only exact-column differences for a rejected persistence boundary. */
	public String auditFailureReport() {
		TreeMap<String, ExactColumns> live = exactColumns(aggregateNodes());
		TreeMap<String, ExactColumns> source = exactColumns(externalSource);
		TreeMap<String, ExactColumns> sink = exactColumns(escapedSink);
		java.util.TreeSet<String> ids = new java.util.TreeSet<>();
		ids.addAll(live.keySet()); ids.addAll(source.keySet()); ids.addAll(sink.keySet());
		ArrayList<String> exactFailures = new ArrayList<>();
		for (String id : ids) {
			ExactColumns retainedAndEscaped = ExactColumns.add(live.get(id), sink.get(id));
			ExactColumns drawn = source.getOrDefault(id, ExactColumns.EMPTY);
			if (!retainedAndEscaped.equals(drawn)) {
				exactFailures.add(id + " retained+escaped=" + retainedAndEscaped + " drawn=" + drawn);
			}
		}
		MassColumns liveMass = liveMassColumns(), sourceMass = massColumns(externalSource);
		MassColumns sinkMass = massColumns(escapedSink);
		SignedDyadic retainedMass = liveMass.mass().add(sinkMass.mass());
		SignedDyadic auditedMass = sourceMass.mass().add(massRoundoff);
		SignedDyadic retainedResidual = liveMass.residual().add(sinkMass.residual());
		SignedDyadic auditedResidual = sourceMass.residual().add(residualRoundoff);
		return "exact=" + exactFailures
				+ ", massDelta=" + retainedMass.subtract(auditedMass)
				+ " (" + retainedMass.subtract(auditedMass).toDouble() + ")"
				+ ", residualDelta=" + retainedResidual.subtract(auditedResidual)
				+ " (" + retainedResidual.subtract(auditedResidual).toDouble() + ")"
				+ ", liveNodes=" + nodes.size();
	}

	public int pressureFloorEvents() { return pressureFloorEvents; }

	public int pressureCeilingEvents() { return pressureCeilingEvents; }

	public int transferClampEvents() { return transferClampEvents; }

	private int sealedId(int nodeId) {
		if (nodeId == AMBIENT_NODE || !nodes.containsKey(nodeId)) {
			throw new IllegalArgumentException("Unknown sealed gas node " + nodeId);
		}
		return nodeId;
	}

	private AirNode sealed(int nodeId) { return nodes.get(sealedId(nodeId)); }

	private GasInventory aggregateNodes() {
		GasInventory result = GasInventory.EMPTY;
		for (AirNode node : nodes.values()) result = result.merge(node.inventory());
		return result;
	}

	private static GasInventory aggregate(List<GasInventory> inventories) {
		GasInventory result = GasInventory.EMPTY;
		for (GasInventory inventory : inventories) result = result.merge(inventory);
		return result;
	}

	private void finishMutation(MassColumns before, GasInventory sourceDelta, GasInventory sinkDelta) {
		MassColumns after = liveMassColumns(), source = massColumns(sourceDelta), sink = massColumns(sinkDelta);
		massRoundoff = massRoundoff.add(after.mass()).add(sink.mass()).subtract(before.mass()).subtract(source.mass());
		residualRoundoff = residualRoundoff.add(after.residual()).add(sink.residual())
				.subtract(before.residual()).subtract(source.residual());
		externalSource = externalSource.add(sourceDelta); escapedSink = escapedSink.add(sinkDelta);
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
		return new TransferMutation(sourceNode, sourceAfter, targetNode, targetAfter,
				massRoundoff.add(after.mass()).add(sink.mass()).subtract(before.mass()).subtract(source.mass()),
				residualRoundoff.add(after.residual()).add(sink.residual())
						.subtract(before.residual()).subtract(source.residual()),
				externalSource.add(sourceDelta), escapedSink.add(sinkDelta));
	}

	private void commitTransferMutation(TransferMutation mutation) {
		if (mutation.sourceNode() != null) mutation.sourceNode().setInventory(mutation.sourceAfter());
		if (mutation.targetNode() != null) mutation.targetNode().setInventory(mutation.targetAfter());
		massRoundoff = mutation.massRoundoffAfter();
		residualRoundoff = mutation.residualRoundoffAfter();
		externalSource = mutation.externalSourceAfter();
		escapedSink = mutation.escapedSinkAfter();
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

	private static void requireExactRemapClosure(
			GasInventory before, GasInventory after, GasInventory source, GasInventory sink
	) {
		TreeMap<String, ExactColumns> left = exactColumns(before), right = exactColumns(after);
		mergeExact(left, exactColumns(source)); mergeExact(right, exactColumns(sink));
		if (!left.equals(right)) throw new IllegalArgumentException("Gas topology remap does not close component nanos/species");
	}

	private static TreeMap<String, ExactColumns> exactColumns(GasInventory inventory) {
		TreeMap<String, ExactColumns> result = new TreeMap<>();
		for (GasComponentAmount amount : inventory.components()) {
			TreeMap<String, Long> species = new TreeMap<>();
			for (GasComponentAmount.SpeciesAmount entry : amount.species()) species.put(entry.speciesId(), entry.amountQuanta());
			result.put(amount.componentId(), new ExactColumns(amount.standardVolumeNanos(), species));
		}
		return result;
	}

	private static TreeMap<String, ExactColumns> exactColumns(GasAuditLedger ledger) {
		TreeMap<String, ExactColumns> result = new TreeMap<>();
		for (GasAuditLedger.Entry amount : ledger.entries()) {
			TreeMap<String, Long> species = new TreeMap<>();
			for (GasComponentAmount.SpeciesAmount entry : amount.species()) species.put(entry.speciesId(), entry.amountQuanta());
			result.put(amount.componentId(), new ExactColumns(amount.standardVolumeNanos(), species));
		}
		return result;
	}

	private static void mergeExact(TreeMap<String, ExactColumns> target, TreeMap<String, ExactColumns> values) {
		for (Map.Entry<String, ExactColumns> entry : values.entrySet()) target.merge(entry.getKey(), entry.getValue(), ExactColumns::add);
	}

	private record MassColumns(SignedDyadic mass, SignedDyadic residual) { }
	public static final class RemapPlan {
		private final AirNetwork owner;
		private final TreeMap<Integer, AirNode> replacementNodes;
		private final ArrayList<AirEdge> replacementEdges;
		private final int nextNodeId;
		private final SignedDyadic massRoundoffAfter;
		private final SignedDyadic residualRoundoffAfter;
		private final GasAuditLedger externalSourceAfter;
		private final GasAuditLedger escapedSinkAfter;

		private RemapPlan(
				AirNetwork owner, TreeMap<Integer, AirNode> replacementNodes,
				ArrayList<AirEdge> replacementEdges, int nextNodeId,
				SignedDyadic massRoundoffAfter, SignedDyadic residualRoundoffAfter,
				GasAuditLedger externalSourceAfter, GasAuditLedger escapedSinkAfter
		) {
			this.owner = owner;
			this.replacementNodes = replacementNodes;
			this.replacementEdges = replacementEdges;
			this.nextNodeId = nextNodeId;
			this.massRoundoffAfter = massRoundoffAfter;
			this.residualRoundoffAfter = residualRoundoffAfter;
			this.externalSourceAfter = externalSourceAfter;
			this.escapedSinkAfter = escapedSinkAfter;
		}
	}
	private record TransferMutation(
			AirNode sourceNode, GasInventory sourceAfter,
			AirNode targetNode, GasInventory targetAfter,
			SignedDyadic massRoundoffAfter, SignedDyadic residualRoundoffAfter,
			GasAuditLedger externalSourceAfter, GasAuditLedger escapedSinkAfter
	) { }
	private record ExactColumns(long nanos, Map<String, Long> species) {
		private static final ExactColumns EMPTY = new ExactColumns(0L, Map.of());
		private ExactColumns { species = Map.copyOf(species); }
		private static ExactColumns add(ExactColumns first, ExactColumns second) {
			if (first == null) first = EMPTY; if (second == null) second = EMPTY;
			TreeMap<String, Long> species = new TreeMap<>(first.species);
			for (Map.Entry<String, Long> entry : second.species.entrySet()) species.merge(entry.getKey(), entry.getValue(), Math::addExact);
			return new ExactColumns(Math.addExact(first.nanos, second.nanos), species);
		}
	}
}
