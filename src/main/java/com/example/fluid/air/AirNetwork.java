package com.example.fluid.air;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Conservative gas region network with an isothermal equation of state and
 * finite-rate orifice transport (M5).
 *
 * <p>Inventories are standard-state volumes in exact nanovolume quanta. Every
 * quantum originates from the ambient reservoir, so the ledger identity
 * {@code sealed + vented == drawnFromAmbient} holds exactly in long arithmetic
 * across node creation, injection, orifice transport, merging, splitting and
 * venting. Pressures are derived gauge pressures in solver units (pascal) with
 * ambient at exactly zero; ambient is id {@link #AMBIENT_NODE} with unbounded
 * inventory and never stores state. This type is runtime-only: it is not a
 * wire or save format.</p>
 */
public final class AirNetwork {
	/** Reference (ambient) absolute pressure in Pa; gauge zero matches the solver convention. */
	public static final double REFERENCE_PRESSURE = 101_325.0;
	/** Numerical ceiling for cavity gauge pressure; guards the fully-filled cavity singularity. */
	public static final double MAX_GAUGE_PRESSURE = 20.0 * REFERENCE_PRESSURE;
	/** Numerical floor for cavity gauge pressure; a cavity cannot be pulled below near-vacuum. */
	public static final double MIN_GAUGE_PRESSURE = -0.99 * REFERENCE_PRESSURE;
	/** Available-volume regularisation (m³) for the isothermal state equation. */
	public static final double MIN_AVAILABLE_VOLUME = 1.0E-4;
	/** Constant air density (kg/m³) used by orifice flow; refined in M9. */
	public static final double AIR_DENSITY = 1.2;
	/** The ambient reservoir: gauge pressure zero, unbounded inventory, never stored as a node. */
	public static final int AMBIENT_NODE = 0;

	private final TreeMap<Integer, AirNode> nodes = new TreeMap<>();
	private final List<AirEdge> edges = new ArrayList<>();
	private int nextNodeId = 1;
	private long ventedNanos;
	private long drawnFromAmbientNanos;
	private int pressureFloorEvents;
	private int pressureCeilingEvents;
	private int transferClampEvents;

	/** Creates a sealed cavity node; the initial inventory is booked as drawn from ambient. */
	public int addNode(double availableVolume, double standardVolume) {
		int id = nextNodeId++;
		AirNode node = new AirNode(availableVolume, AirNode.toNanos(standardVolume));
		nodes.put(id, node);
		drawnFromAmbientNanos += node.standardVolumeNanos();
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

	/**
	 * Explicitly injects or removes inventory (developer presets and tests). Increases are
	 * booked as drawn from ambient and decreases as vented, keeping the ledger identity exact.
	 */
	public void setStandardVolume(int nodeId, double standardVolume) {
		AirNode node = sealed(nodeId);
		long target = AirNode.toNanos(standardVolume);
		long current = node.standardVolumeNanos();
		node.setStandardVolumeNanos(target);
		if (target > current) drawnFromAmbientNanos += target - current;
		else ventedNanos += current - target;
	}

	/** Adds an orifice edge; parallel openings between the same pair of nodes sum their areas. */
	public void addOrMergeEdge(int firstNode, int secondNode, double areaSquareMeters, double dischargeCoefficient) {
		if (firstNode == secondNode) throw new IllegalArgumentException("An edge needs two distinct nodes");
		int existing = findEdge(firstNode, secondNode);
		if (existing >= 0) {
			AirEdge edge = edges.get(existing);
			edges.set(existing, new AirEdge(firstNode, secondNode,
					edge.areaSquareMeters() + areaSquareMeters, dischargeCoefficient));
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
		if (nodeId == AMBIENT_NODE) return 0.0;
		AirNode node = sealed(nodeId);
		double available = Math.max(node.availableVolume(), MIN_AVAILABLE_VOLUME);
		double pressure = REFERENCE_PRESSURE * (node.standardVolume() / available - 1.0);
		if (pressure > MAX_GAUGE_PRESSURE) { pressureCeilingEvents++; return MAX_GAUGE_PRESSURE; }
		if (pressure < MIN_GAUGE_PRESSURE) { pressureFloorEvents++; return MIN_GAUGE_PRESSURE; }
		return pressure;
	}

	/**
	 * Advances orifice transport by one step. Flow follows the CONTAM power law
	 * {@code Q = Cd * A * sqrt(2 * dp / rho_air)}; the transferred standard volume is clamped
	 * to the equalization split, so the pressure difference decays monotonically and never
	 * overshoots equilibrium. Edges apply in list order with inventories refreshed in place.
	 */
	public void stepEdges(double dt) {
		if (!(dt > 0.0) || Double.isNaN(dt)) throw new IllegalArgumentException("dt must be positive");
		for (int i = 0; i < edges.size(); i++) {
			AirEdge edge = edges.get(i);
			double firstPressure = gaugePressure(edge.firstNode());
			double secondPressure = gaugePressure(edge.secondNode());
			if (firstPressure == secondPressure) continue;
			int source = firstPressure > secondPressure ? edge.firstNode() : edge.secondNode();
			int target = firstPressure > secondPressure ? edge.secondNode() : edge.firstNode();
			double delta = Math.abs(firstPressure - secondPressure);
			double sourcePressure = Math.max(firstPressure, secondPressure);
			double flow = edge.dischargeCoefficient() * edge.areaSquareMeters()
					* Math.sqrt(2.0 * delta / AIR_DENSITY);
			double transferCubicMeters = flow * dt * (REFERENCE_PRESSURE + sourcePressure) / REFERENCE_PRESSURE;
			long transferNanos = equalizationClampedTransfer(source, target, transferCubicMeters);
			if (transferNanos > 0L) moveInventory(source, target, transferNanos);
		}
	}

	/**
	 * Caps the transfer at the equalization split: a node pair ends at the shared pressure
	 * {@code P0 * (V0_a + V0_b) / (V_a + V_b)}; an ambient edge ends at exactly gauge zero.
	 * The raw flow is pre-capped to the movable inventory so the nanovolume conversion cannot
	 * overflow on extreme pressure or step-size inputs.
	 */
	private long equalizationClampedTransfer(int source, int target, double transferCubicMeters) {
		if (source == AMBIENT_NODE) {
			double deficit = Math.max(availableVolume(target), MIN_AVAILABLE_VOLUME) - standardVolume(target);
			transferCubicMeters = Math.min(transferCubicMeters, Math.max(0.0, deficit));
		} else {
			transferCubicMeters = Math.min(transferCubicMeters, standardVolume(source));
		}
		long desired = AirNode.toNanos(transferCubicMeters);
		if (desired <= 0L) return 0L;
		if (source == AMBIENT_NODE) {
			return Math.min(desired, Math.max(0L, gaugeZeroInventoryNanos(target)
					- standardVolumeNanos(target)));
		}
		if (target == AMBIENT_NODE) {
			return Math.min(desired, Math.max(0L, standardVolumeNanos(source)
					- gaugeZeroInventoryNanos(source)));
		}
		AirNode sourceNode = sealed(source);
		AirNode targetNode = sealed(target);
		double availableSum = sourceNode.availableVolume() + targetNode.availableVolume();
		if (availableSum <= 0.0) return 0L;
		long totalNanos = sourceNode.standardVolumeNanos() + targetNode.standardVolumeNanos();
		long equilibriumSourceNanos = AirNode.toNanos(
				sourceNode.availableVolume() * (totalNanos * AirNode.NANO_VOLUME) / availableSum);
		return Math.min(desired, Math.max(0L, sourceNode.standardVolumeNanos() - equilibriumSourceNanos));
	}

	/** Inventory at which the node reaches exactly gauge zero: {@code V0 == V_avail}. */
	private long gaugeZeroInventoryNanos(int nodeId) {
		return AirNode.toNanos(Math.max(availableVolume(nodeId), MIN_AVAILABLE_VOLUME));
	}

	private void moveInventory(int source, int target, long nanos) {
		if (source == AMBIENT_NODE) {
			AirNode targetNode = sealed(target);
			targetNode.setStandardVolumeNanos(targetNode.standardVolumeNanos() + nanos);
			drawnFromAmbientNanos += nanos;
			return;
		}
		AirNode sourceNode = sealed(source);
		long actual = Math.min(nanos, sourceNode.standardVolumeNanos());
		if (actual < nanos) transferClampEvents++;
		sourceNode.setStandardVolumeNanos(sourceNode.standardVolumeNanos() - actual);
		if (target == AMBIENT_NODE) {
			ventedNanos += actual;
			return;
		}
		AirNode targetNode = sealed(target);
		targetNode.setStandardVolumeNanos(targetNode.standardVolumeNanos() + actual);
	}

	/**
	 * Merges source cavities into the target. Inventories sum exactly; edges touching a source
	 * are rewired to the target, dropping self loops and summing parallel areas.
	 */
	public void mergeNodes(int targetNodeId, int... sourceNodeIds) {
		if (sourceNodeIds.length == 0) throw new IllegalArgumentException("A merge needs at least one source");
		AirNode target = sealed(targetNodeId);
		for (int sourceNodeId : sourceNodeIds) {
			if (sourceNodeId == targetNodeId) throw new IllegalArgumentException("Cannot merge a node into itself");
			AirNode source = sealed(sourceNodeId);
			target.setStandardVolumeNanos(target.standardVolumeNanos() + source.standardVolumeNanos());
			nodes.remove(sourceNodeId);
		}
		rewireEdges(targetNodeId, sourceNodeIds);
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
		long totalNanos = source.standardVolumeNanos();
		int[] childIds = new int[childAvailableVolumes.length];
		childIds[0] = sourceNodeId;
		long assigned = 0L;
		for (int child = 0; child < childAvailableVolumes.length; child++) {
			long share;
			if (child == childAvailableVolumes.length - 1) {
				share = totalNanos - assigned;
			} else {
				share = (long) Math.floor(totalNanos * childAvailableVolumes[child] / availableSum);
				assigned += share;
			}
			if (child == 0) {
				source.setStandardVolumeNanos(share);
				source.setAvailableVolume(childAvailableVolumes[child]);
			} else {
				int id = nextNodeId++;
				nodes.put(id, new AirNode(childAvailableVolumes[child], share));
				childIds[child] = id;
			}
		}
		return childIds;
	}

	/** Vents a cavity's whole inventory to ambient and removes it together with its edges. */
	public void ventNode(int nodeId) {
		AirNode node = nodes.remove(sealedId(nodeId));
		ventedNanos += node.standardVolumeNanos();
		rewireEdges(-1, nodeId);
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

	public long sealedInventoryNanos() {
		long sum = 0L;
		for (Map.Entry<Integer, AirNode> entry : nodes.entrySet()) sum += entry.getValue().standardVolumeNanos();
		return sum;
	}

	public long ventedNanos() { return ventedNanos; }

	public long drawnFromAmbientNanos() { return drawnFromAmbientNanos; }

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
}
