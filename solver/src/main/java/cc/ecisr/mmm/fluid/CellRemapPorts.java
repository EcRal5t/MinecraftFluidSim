package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.IntBinaryOperator;

/** Prospective shared transfers between actual face-connected micro-void components. */
final class CellRemapPorts {
	static final class Port {
		final int face, donor, receiver, donorSupport, receiverSupport;
		int ordinal;
		SignedDyadic volume = SignedDyadic.ZERO;
		Port(int face, int donor, int receiver, int donorSupport, int receiverSupport) {
			this.face = face; this.donor = donor; this.receiver = receiver;
			this.donorSupport = donorSupport; this.receiverSupport = receiverSupport;
		}
	}
	private final FluidDomain domain;
	private final ConservativeLiquidFlux flux;
	private final SignedDyadic[] supply, after, capacity, reserved;
	private final SignedDyadic[] cellSupply;
	private final ArrayList<Port>[] outgoing, incoming;
	private final ArrayList<Port> ports = new ArrayList<>();
	private final boolean[] changed;
	private final int[] predecessor, queue, seen, sourceCounts;
	private final int[] starts, cells;
	private final long workLimit;
	private long work, searchWork, searchLimit;
	private int generation;

	@SuppressWarnings("unchecked")
	CellRemapPorts(FluidDomain domain, ConservativeLiquidFlux flux, BallisticLiquidRemap ballistic,
	               boolean geometryRemap, IntBinaryOperator shares) {
		this.domain = domain; this.flux = flux;
		// Linear passes are bounded by allocated inputs. Each face has <=4 ports and <=5 allocation
		// rounds: 8 patch tests + 5*4*(4 bounds + 8 moments) + 3*4 port insertions = 260 visits.
		// Initialization, output reduction and at most four component chemical inspections per group
		// add 2*cells + 11*nodes + 5*particles + arrivals + (3+260+8)*faces; nodes <=4*cells.
		workLimit = 1L + 46L * domain.cellCount + 5L * domain.particles.count() + ballistic.markers.size()
				+ 271L * flux.flux.length;
		spend(domain.cellCount + 1L);
		starts = new int[domain.cellCount + 1];
		for (int cell = 0; cell < domain.cellCount; cell++) starts[cell + 1] = starts[cell]
				+ CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell)).length;
		int count = starts[domain.cellCount];
		// At most four local components inspect each beginning group for its chemical share limit.
		spend(10L * count + domain.particles.count() + ballistic.markers.size() + 3L * flux.flux.length);
		cells = new int[count];
		for (int cell = 0; cell < domain.cellCount; cell++) Arrays.fill(cells, starts[cell], starts[cell + 1], cell);
		supply = new SignedDyadic[count]; Arrays.fill(supply, SignedDyadic.ZERO);
		capacity = supply.clone(); reserved = supply.clone(); after = supply.clone(); changed = new boolean[count];
		outgoing = (ArrayList<Port>[]) new ArrayList<?>[count]; incoming = (ArrayList<Port>[]) new ArrayList<?>[count];
		predecessor = new int[count]; queue = new int[count]; seen = new int[count]; sourceCounts = new int[domain.cellCount];
		for (int p = 0; p < domain.particles.count(); p++) {
			int cell = domain.particleCellIndex(p);
			if (geometryRemap || domain.gridToParticleStage.advectionCells()[cell]) {
				int node = node(cell, CellRemapMicroTopology.markerSupport(domain, p));
				supply[node] = supply[node].add(SignedDyadic.of(domain.particles.volume(p)));
				sourceCounts[cell]++;
			}
		}
		for (var marker : ballistic.markers) {
			int node = node(marker.cell(), CellRemapMicroTopology.markerSupport(domain, marker));
			reserved[node] = reserved[node].add(SignedDyadic.of(marker.volume()));
		}
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int[] components = CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell));
			if (components.length == 0) continue;
			SignedDyadic[] pores = CellRemapMicroTopology.capacities(domain.geometry.cutCells.mask(cell), flux.finalCapacity(cell));
			for (int i = 0; i < pores.length; i++) {
				int node = node(cell, components[i]);
				capacity[node] = pores[i].subtract(reserved[node]);
				if (capacity[node].signum() < 0) throw new MechanicalTimeRefinement(2, "Independent liquid arrival exceeds its open micro component");
			}
		}
		SignedDyadic[] remaining = supply.clone();
		for (int face = 0; face < flux.flux.length; face++) if (flux.exactFlux[face].signum() > 0) prepareFace(face, remaining);
		if (shares != null) limitShares(shares);
		// One whole graph visit and its two path traversals, plus the existing fixed search allowance.
		searchLimit = 3L * count + 3L * ports.size() + cc.ecisr.mmm.granular.GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP;
		spend(2L * ports.size());
		System.arraycopy(supply, 0, after, 0, count);
		for (var port : ports) { after[port.donor] = after[port.donor].subtract(port.volume); after[port.receiver] = after[port.receiver].add(port.volume); }
		for (int node = 0; node < count; node++) while (after[node].compareTo(capacity[node]) > 0) cancelIncoming(node);
		cellSupply = supplyByCell(); synchronizeFluxes();
	}
	private void synchronizeFluxes() {
		Arrays.fill(changed, false);
		SignedDyadic[] amounts = new SignedDyadic[flux.flux.length]; Arrays.fill(amounts, SignedDyadic.ZERO);
		for (var port : ports) if (port.volume.signum() > 0) {
			amounts[port.face] = amounts[port.face].add(port.volume); changed[port.donor] = changed[port.receiver] = true;
		}
		flux.adoptPortFluxes(cellSupply, amounts);
	}

	/** Increase retained inventory to a float value when possible; otherwise remove the smallest port. */
	void reduceChemical(int cell, int support, SignedDyadic retained, boolean homogeneous) {
		var available = outgoing[node(cell, support)];
		spendSearch(available == null ? 1 : available.size());
		Port selected = null; SignedDyadic amount = SignedDyadic.ZERO;
		if (homogeneous) {
			float upper = (float) retained.toDouble();
			if (SignedDyadic.of(upper).compareTo(retained) < 0) upper = Math.nextUp(upper);
			amount = SignedDyadic.of(upper).subtract(retained);
			if (amount.signum() > 0 && available != null) for (var port : available)
				if (port.volume.compareTo(amount) >= 0 && (selected == null || port.volume.compareTo(selected.volume) < 0)) selected = port;
		}
		if (selected == null && available != null) for (var port : available) if (port.volume.signum() > 0
				&& (selected == null || port.volume.compareTo(selected.volume) < 0)) { selected = port; amount = port.volume; }
		if (selected == null) throw new MechanicalTimeRefinement(2, "Chemical component has no representable exact volume carriers");
		spendSearch(after.length + (long) changed.length + ports.size() + flux.flux.length + domain.cellCount);
		selected.volume = selected.volume.subtract(amount);
		after[selected.donor] = after[selected.donor].add(amount); after[selected.receiver] = after[selected.receiver].subtract(amount);
		for (int node = 0; node < after.length; node++) while (after[node].compareTo(capacity[node]) > 0) cancelIncoming(node);
		synchronizeFluxes();
	}
	void chemicalWork(long amount) { spendSearch(amount); }

	boolean changed(int cell, int support) { return changed[node(cell, support)]; }
	int cellOf(int node) { return cells[node]; }
	SignedDyadic capacity(int cell, int support) { return capacity[node(cell, support)].add(reserved[node(cell, support)]); }
	SignedDyadic reserved(int cell, int support) { return reserved[node(cell, support)]; }
	ArrayList<Port> outgoing(int cell, int support) {
		var result = outgoing[node(cell, support)]; return result == null ? new ArrayList<>(0) : result;
	}
	private int node(int cell, int support) {
		if (support == 0) throw new IllegalStateException("Liquid port has no open component");
		int[] components = CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell));
		for (int i = 0; i < components.length; i++) if (components[i] == support) return starts[cell] + i;
		throw new IllegalStateException("Liquid port component differs from its collision mask");
	}
	private SignedDyadic[] supplyByCell() {
		spend(domain.cellCount + (long) supply.length);
		SignedDyadic[] result = new SignedDyadic[domain.cellCount]; Arrays.fill(result, SignedDyadic.ZERO);
		for (int node = 0; node < supply.length; node++) result[cells[node]] = result[cells[node]].add(supply[node]);
		return result;
	}
	private void prepareFace(int face, SignedDyadic[] remaining) {
		int donor = flux.donor(face), receiver = flux.receiver(face), axis = flux.axis[face];
		boolean positive = flux.velocity[face] >= 0;
		var candidates = new ArrayList<Port>(4);
		int[] donorComponents = CellRemapMicroTopology.components(domain.geometry.cutCells.mask(donor));
		int[] receiverComponents = CellRemapMicroTopology.components(domain.geometry.cutCells.mask(receiver));
		if (donorComponents.length == 1 && receiverComponents.length == 1) {
			var port = new Port(face, node(donor, donorComponents[0]), node(receiver, receiverComponents[0]), donorComponents[0], receiverComponents[0]);
			port.volume = min(flux.exactFlux[face], remaining[port.donor]); remaining[port.donor] = remaining[port.donor].subtract(port.volume);
			add(port); return;
		}
		spend(8);
		for (int bit = 0; bit < 8; bit++) if (((bit >>> axis & 1) != 0) == positive) {
			int a = CellRemapMicroTopology.support(domain.geometry.cutCells.mask(donor), bit);
			int b = CellRemapMicroTopology.support(domain.geometry.cutCells.mask(receiver), bit ^ 1 << axis);
			if (a == 0 || b == 0) continue;
			boolean duplicate = false;
			for (var port : candidates) duplicate |= port.donorSupport == a && port.receiverSupport == b;
			if (!duplicate) candidates.add(new Port(face, node(donor, a), node(receiver, b), a, b));
		}
		SignedDyadic unassigned = flux.exactFlux[face];
		int iterations = 0;
		while (unassigned.signum() > 0) {
			if (++iterations > candidates.size() + 1) throw new IllegalStateException("Liquid micro port allocation failed to progress");
			spend(4L * candidates.size()); SignedDyadic before = unassigned;
			SignedDyadic[] bounds = new SignedDyadic[candidates.size()]; double[] preference = new double[bounds.length];
			SignedDyadic available = SignedDyadic.ZERO;
			for (int i = 0; i < bounds.length; i++) {
				var port = candidates.get(i); bounds[i] = remaining[port.donor];
				boolean seen = false; for (int j = 0; j < i; j++) seen |= candidates.get(j).donor == port.donor;
				if (!seen) available = available.add(bounds[i]);
				spend(8); double[] moments = new double[4]; flux.sweptMoments(face, port.donorSupport, port.receiverSupport, moments);
				preference[i] = moments[0];
			}
			if (available.signum() == 0) break;
			SignedDyadic[] allocated = ExactVolumeAllocation.allocateCapped(min(unassigned, available), bounds, preference);
			for (int i = 0; i < allocated.length; i++) {
				var port = candidates.get(i); var accepted = min(allocated[i], remaining[port.donor]);
				port.volume = port.volume.add(accepted); remaining[port.donor] = remaining[port.donor].subtract(accepted);
				unassigned = unassigned.subtract(accepted);
			}
			if (unassigned.equals(before)) throw new IllegalStateException("Liquid micro port allocation made no inventory progress");
		}
		for (var port : candidates) add(port);
	}
	private void add(Port port) {
		spend(3); port.ordinal = ports.size(); ports.add(port);
		if (outgoing[port.donor] == null) outgoing[port.donor] = new ArrayList<>(6);
		if (incoming[port.receiver] == null) incoming[port.receiver] = new ArrayList<>(6);
		outgoing[port.donor].add(port); incoming[port.receiver].add(port);
	}
	private void limitShares(IntBinaryOperator shares) {
		for (int node = 0; node < outgoing.length; node++) if (outgoing[node] != null) {
			spend(2L * outgoing[node].size());
			var selected = new ArrayList<>(outgoing[node]); selected.sort((a, b) -> b.volume.compareTo(a.volume));
			spend(sourceCounts[cells[node]]);
			int available = shares.applyAsInt(cells[node], selected.getFirst().donorSupport) - 1;
			for (int i = Math.max(0, available); i < selected.size(); i++) selected.get(i).volume = SignedDyadic.ZERO;
		}
	}
	/** Cancel an incoming path back to actual component spare capacity; intermediate inventories stay unchanged. */
	private void cancelIncoming(int source) {
		if (++generation == 0) { spendSearch(seen.length); Arrays.fill(seen, 0); generation = 1; }
		seen[source] = generation;
		int head = 0, tail = 0, destination = -1; queue[tail++] = source;
		while (head < tail && destination < 0) {
			spendSearch(1); int current = queue[head++];
			if (incoming[current] == null) continue;
			for (var port : incoming[current]) {
				spendSearch(1); if (port.volume.signum() == 0 || seen[port.donor] == generation) continue;
				seen[port.donor] = generation; predecessor[port.donor] = port.ordinal; queue[tail++] = port.donor;
				if (after[port.donor].compareTo(capacity[port.donor]) < 0) { destination = port.donor; break; }
			}
		}
		if (destination < 0) throw new MechanicalTimeRefinement(2, "Liquid micro component has no connected final capacity");
		SignedDyadic amount = min(after[source].subtract(capacity[source]), capacity[destination].subtract(after[destination]));
		for (int node = destination; node != source; node = ports.get(predecessor[node]).receiver) {
			spendSearch(1); amount = min(amount, ports.get(predecessor[node]).volume);
		}
		for (int node = destination; node != source; node = ports.get(predecessor[node]).receiver) {
			spendSearch(1); var port = ports.get(predecessor[node]); port.volume = port.volume.subtract(amount);
		}
		after[source] = after[source].subtract(amount); after[destination] = after[destination].add(amount);
	}
	private void spend(long amount) {
		if (amount < 0 || amount > workLimit - work)
			throw new FluidSolverBackend.MechanicalWorkBudgetExceededException("Cut-cell liquid port capacity search exceeds its logical work budget");
		work += amount;
	}
	private void spendSearch(long amount) {
		if (amount < 0 || amount > searchLimit - searchWork)
			throw new FluidSolverBackend.MechanicalWorkBudgetExceededException("Cut-cell liquid capacity paths exceed their bounded search work");
		searchWork += amount;
	}
	private static SignedDyadic min(SignedDyadic a, SignedDyadic b) { return a.compareTo(b) <= 0 ? a : b; }
}
