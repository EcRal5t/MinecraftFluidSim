package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;

/** Conservative liquid remap when active and cell-local solid representations exchange volume. */
final class GranularLiquidDisplacement {
	private GranularLiquidDisplacement() { }

	static long[] begin(FluidDomain domain) {
		if (!CellLiquidVolumeTransport.enabled() || domain.particles.count() == 0 || !domain.hasGranularInventory()) return null;
		domain.ensureGrid();
		domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		return domain.granularCoupling.copySolidSvq();
	}

	static FluidDomain.LiquidTransportStats commit(FluidDomain domain, long[] previous) {
		if (previous == null) return FluidDomain.LiquidTransportStats.INACTIVE;
		long[] next = domain.granularCoupling.copySolidSvq();
		if (Arrays.equals(previous, next)) return FluidDomain.LiquidTransportStats.INACTIVE;
		var budget = new Budget();
		long faces = (long) (domain.sizeX - 1) * domain.sizeY * domain.sizeZ
				+ (long) domain.sizeX * (domain.sizeY - 1) * domain.sizeZ
				+ (long) domain.sizeX * domain.sizeY * (domain.sizeZ - 1);
		// Inventory copies, cell preparation/clears/final checks, flux clear and both adjacency entries.
		budget.spend(6L * domain.cellCount + 3L * faces);
		var flux = new ConservativeLiquidFlux(domain.sizeX, domain.sizeY, domain.sizeZ);
		SignedDyadic[] volume = domain.markerGrid.exactCellVolumeInventory.clone(), supply = volume.clone();
		SignedDyadic[] capacity = new SignedDyadic[domain.cellCount], displaced = new SignedDyadic[domain.cellCount];
		long totalBefore = 0, totalAfter = 0;
		for (int c = 0; c < volume.length; c++) {
			totalBefore = Math.addExact(totalBefore, previous[c]); totalAfter = Math.addExact(totalAfter, next[c]);
			SignedDyadic open = SignedDyadic.of(domain.geometry.cutCells.openCellVolume(c));
			SignedDyadic old = open.subtract(solidVolume(previous[c])); capacity[c] = open.subtract(solidVolume(next[c]));
			if (volume[c].compareTo(old) > 0 || capacity[c].signum() < 0)
				throw new IllegalStateException("Solid representation remap requires a valid joint initial capacity at cell " + c);
			displaced[c] = next[c] > previous[c] ? ExactVolumeAllocation.allocate(solidVolume(next[c] - previous[c]),
					new SignedDyadic[] {volume[c], old.subtract(volume[c])})[0] : SignedDyadic.ZERO;
		}
		if (totalBefore != totalAfter) throw new IllegalStateException("Solid representation remap changed skeleton inventory");
		Arrays.fill(flux.exactFlux, SignedDyadic.ZERO);
		int[] head = new int[domain.cellCount], link = new int[2 * flux.flux.length]; Arrays.fill(head, -1);
		for (int f = 0; f < flux.flux.length; f++) {
			link[2 * f] = head[flux.left[f]]; head[flux.left[f]] = 2 * f;
			link[2 * f + 1] = head[flux.right[f]]; head[flux.right[f]] = 2 * f + 1;
		}
		int[] predecessor = new int[domain.cellCount], queue = new int[domain.cellCount];
		boolean changed = false;
		for (int source = 0; source < volume.length; source++) while (displaced[source].signum() > 0) {
			budget.spend(domain.cellCount);
			Arrays.fill(predecessor, -1); predecessor[source] = -2;
			int read = 0, written = 0, destination = -1; queue[written++] = source;
			while (read < written && destination < 0) {
				budget.spend(1);
				int current = queue[read++];
				if (supply[current].signum() == 0) continue;
				for (int edge = head[current]; edge >= 0; edge = link[edge]) {
					budget.spend(1);
					int f = edge / 2, neighbor = (edge & 1) == 0 ? flux.right[f] : flux.left[f];
					if (predecessor[neighbor] != -1 || !openFace(domain, flux, f)
							|| flux.exactFlux[f].signum() > 0 && flux.donor(f) != current) continue;
					predecessor[neighbor] = edge; queue[written++] = neighbor;
					if (volume[neighbor].compareTo(capacity[neighbor]) < 0) { destination = neighbor; break; }
				}
			}
			if (destination < 0) throw new IllegalStateException("Solid representation remap has no connected liquid displacement capacity");
			SignedDyadic amount = min(displaced[source], capacity[destination].subtract(volume[destination]));
			for (int node = destination; node != source;) {
				budget.spend(1);
				int edge = predecessor[node], f = edge / 2, donor = (edge & 1) == 0 ? flux.left[f] : flux.right[f];
				amount = min(amount, supply[donor]); node = donor;
			}
			for (int node = destination; node != source;) {
				budget.spend(1);
				int edge = predecessor[node], f = edge / 2, donor = (edge & 1) == 0 ? flux.left[f] : flux.right[f];
				flux.velocity[f] = (edge & 1) == 0 ? 1 : -1;
				flux.exactFlux[f] = flux.exactFlux[f].add(amount); supply[donor] = supply[donor].subtract(amount); node = donor;
			}
			volume[source] = volume[source].subtract(amount); volume[destination] = volume[destination].add(amount);
			displaced[source] = displaced[source].subtract(amount); changed = true;
		}
		for (int c = 0; c < volume.length; c++) if (volume[c].compareTo(capacity[c]) > 0)
			throw new IllegalStateException("Solid representation remap exceeds final joint capacity at cell " + c);
		if (!changed) return FluidDomain.LiquidTransportStats.INACTIVE;
		flux.geometricResult(volume, capacity);
		var candidate = new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(domain.cellCount), true);
		domain.coordinator.publishCellRemap(CarrierMutationPlan.wholesaleCount(domain.carrierMutationRevision(), domain.particles.count()), candidate);
		if (domain.cellLiquidVolumeTransport == null) domain.cellLiquidVolumeTransport = new CellLiquidVolumeTransport(domain);
		domain.cellLiquidVolumeTransport.publishedQuadrature = true;
		domain.cellLiquidVolumeTransport.momentAudit = candidate.momentAudit;
		return candidate.momentAudit.stats();
	}

	private static final class Budget {
		private long remaining = GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP;
		void spend(long operations) {
			if (operations < 0 || operations > remaining)
				throw new FluidSolverBackend.MechanicalWorkBudgetExceededException("Granular liquid displacement exceeds its representation-remap operation budget");
			remaining -= operations;
		}
	}

	private static SignedDyadic solidVolume(long svq) { return SignedDyadic.of((double) svq / SolidVolume.SVQ_PER_CELL); }
	private static SignedDyadic min(SignedDyadic first, SignedDyadic second) { return first.compareTo(second) <= 0 ? first : second; }
	private static boolean openFace(FluidDomain domain, ConservativeLiquidFlux flux, int face) {
		int c = flux.right[face], x = c % domain.sizeX, y = c / domain.sizeX % domain.sizeY, z = c / (domain.sizeX * domain.sizeY);
		return switch (flux.axis[face]) {
			case 0 -> domain.geometry.cutCells.xFaceOpen(x, y, z) > 0;
			case 1 -> domain.geometry.cutCells.yFaceOpen(x, y, z) > 0;
			default -> domain.geometry.cutCells.zFaceOpen(x, y, z) > 0;
		};
	}
}
