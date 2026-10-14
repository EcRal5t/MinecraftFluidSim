package mmm.granular;

import java.util.Arrays;
import java.util.function.IntToLongFunction;

/** Bounded integral floor/ceil allocation of all active parcels against a fixed cell capacity. */
public final class GranularIntegerAllocation {
	public enum Status { FEASIBLE, INFEASIBLE, WORK_LIMIT }
	public record Result(Status status, int[] masks, int operations) { }

	private GranularIntegerAllocation() { }

	public static Result plan(GranularKernel kernel, GranularParcelStore parcels,
			GranularCouplingFields.PositionSource positions, long[] fixed,
			IntToLongFunction capacity, int operationLimit) {
		if (kernel == null || parcels == null || positions == null || fixed == null || capacity == null
				|| operationLimit <= 0 || operationLimit > GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP
				|| fixed.length != kernel.cellCount())
			throw new IllegalArgumentException("Granular integer allocation input is incomplete");
		parcels.requireGeometry(kernel);
		int count = parcels.count(), operations = 0;
		long[] room = new long[fixed.length], seedRoom = new long[fixed.length];
		for (int cell = 0; cell < room.length; cell++) {
			if (++operations > operationLimit) return new Result(Status.WORK_LIMIT, null, operations);
			if (fixed[cell] < 0) throw new IllegalArgumentException("Fixed granular occupancy is negative");
			long cap = capacity.applyAsLong(cell);
			if (cap < fixed[cell]) return new Result(Status.INFEASIBLE, null, operations);
			room[cell] = cap - fixed[cell];
		}
		int[] cells = new int[Math.multiplyExact(count, 8)], eligible = new int[count], demand = new int[count];
		int[] masks = new int[count], seed = new int[count];
		for (int parcel = 0; parcel < count; parcel++) {
			var inventory = parcels.parcel(parcel);
			var position = positions.position(parcel, inventory);
			if (position == null) throw new IllegalArgumentException("Granular allocation endpoint is missing");
			kernel.capture(position.x(), position.y(), position.z(), inventory.solidVolumeSvq());
			seed[parcel] = position.ceilMask() < 0 ? kernel.canonicalMask() : position.ceilMask();
			kernel.requireMask(seed[parcel]);
			long residual = inventory.solidVolumeSvq();
			for (int slot = 0; slot < kernel.size(); slot++) {
				if (++operations > operationLimit) return new Result(Status.WORK_LIMIT, null, operations);
				int cell = kernel.cell(slot); long floor = kernel.floor(slot);
				cells[8 * parcel + slot] = cell;
				if (room[cell] < floor) return new Result(Status.INFEASIBLE, null, operations);
				room[cell] -= floor; residual -= floor;
				if (kernel.ceil(slot) > floor) eligible[parcel] |= 1 << slot;
			}
			demand[parcel] = Math.toIntExact(residual);
		}
		System.arraycopy(room, 0, seedRoom, 0, room.length);
		boolean seedFits = true;
		for (int parcel = 0; parcel < count; parcel++) for (int bits = seed[parcel]; bits != 0; bits &= bits - 1) {
			if (++operations > operationLimit) return new Result(Status.WORK_LIMIT, null, operations);
			int cell = cells[8 * parcel + Integer.numberOfTrailingZeros(bits)];
			if (seedRoom[cell] == 0) seedFits = false;
			else seedRoom[cell]--;
		}
		if (seedFits) return new Result(Status.FEASIBLE, seed, operations);

		// Unit parcel-to-cell edges select ceil. Reverse selected edges relocate earlier allocations.
		int[] head = new int[room.length], next = new int[cells.length];
		Arrays.fill(head, -1);
		for (int parcel = 0; parcel < count; parcel++) for (int bits = eligible[parcel]; bits != 0; bits &= bits - 1) {
			if (++operations > operationLimit) return new Result(Status.WORK_LIMIT, null, operations);
			int edge = 8 * parcel + Integer.numberOfTrailingZeros(bits), cell = cells[edge];
			next[edge] = head[cell]; head[cell] = edge;
		}
		int[] queue = new int[count], seen = new int[count], parent = new int[count];
		int[] added = new int[count], removed = new int[count];
		int generation = 0;
		for (int root = 0; root < count; root++) for (int remaining = demand[root]; remaining > 0; remaining--) {
			int first = 0, last = 1, freeEdge = -1;
			queue[0] = root; seen[root] = ++generation; parent[root] = -1;
			while (first < last && freeEdge < 0) {
				int parcel = queue[first++];
				for (int bits = eligible[parcel] & ~masks[parcel]; bits != 0; bits &= bits - 1) {
					if (++operations > operationLimit) return new Result(Status.WORK_LIMIT, null, operations);
					int edge = 8 * parcel + Integer.numberOfTrailingZeros(bits), cell = cells[edge];
					if (room[cell] > 0) { freeEdge = edge; break; }
					for (int reverse = head[cell]; reverse >= 0; reverse = next[reverse]) {
						if (++operations > operationLimit) return new Result(Status.WORK_LIMIT, null, operations);
						int owner = reverse / 8;
						if ((masks[owner] & 1 << (reverse % 8)) == 0 || seen[owner] == generation) continue;
						seen[owner] = generation; parent[owner] = parcel; added[owner] = edge; removed[owner] = reverse;
						queue[last++] = owner;
					}
				}
			}
			if (freeEdge < 0) return new Result(Status.INFEASIBLE, null, operations);
			room[cells[freeEdge]]--;
			int parcel = freeEdge / 8;
			masks[parcel] |= 1 << (freeEdge % 8);
			while (parent[parcel] >= 0) {
				if (++operations > operationLimit) return new Result(Status.WORK_LIMIT, null, operations);
				masks[parcel] &= ~(1 << (removed[parcel] % 8));
				int edge = added[parcel]; masks[edge / 8] |= 1 << (edge % 8);
				parcel = parent[parcel];
			}
		}
		return new Result(Status.FEASIBLE, masks, operations);
	}
}
