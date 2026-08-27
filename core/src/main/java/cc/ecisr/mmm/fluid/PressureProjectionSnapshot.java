package cc.ecisr.mmm.fluid;

import java.util.function.IntFunction;

/** Runtime-only proof that a pressure sample belongs to one projected carrier/geometry layout. */
public final class PressureProjectionSnapshot {
	public record CellIdentity(
			int markerCount,
			long markerVolumeBits,
			long markerMassBits,
			int solidMicroMask,
			long movingBlockedVolumeBits,
			long solidOccupancySvq,
			int adjacentGasLineage
	) {
		public CellIdentity {
			if (markerCount < 0 || solidOccupancySvq < 0L) {
				throw new IllegalArgumentException("Pressure cell identity is invalid");
			}
		}
	}

	public record Sample(
			long logicalGeneration,
			int cell,
			double gaugePressurePa,
			double absolutePressurePa,
			int adjacentGasLineage
	) { }

	private final long logicalGeneration;
	private final int[] markerCount;
	private final long[] markerVolumeBits;
	private final long[] markerMassBits;
	private final int[] solidMicroMask;
	private final long[] movingBlockedVolumeBits;
	private final long[] solidOccupancySvq;
	private final int[] adjacentGasLineage;
	private final float[] gaugePressurePa;

	private PressureProjectionSnapshot(
			long logicalGeneration, float[] gaugePressurePa, IntFunction<CellIdentity> identityAt
	) {
		if (logicalGeneration < 0L || gaugePressurePa == null || identityAt == null) {
			throw new IllegalArgumentException("Pressure projection snapshot dimensions are invalid");
		}
		int cells = gaugePressurePa.length;
		markerCount = new int[cells];
		markerVolumeBits = new long[cells];
		markerMassBits = new long[cells];
		solidMicroMask = new int[cells];
		movingBlockedVolumeBits = new long[cells];
		solidOccupancySvq = new long[cells];
		adjacentGasLineage = new int[cells];
		for (int cell = 0; cell < gaugePressurePa.length; cell++) {
			CellIdentity identity = identityAt.apply(cell);
			if (identity == null || !Float.isFinite(gaugePressurePa[cell])) {
				throw new IllegalArgumentException("Pressure projection snapshot contains invalid cell data");
			}
			markerCount[cell] = identity.markerCount();
			markerVolumeBits[cell] = identity.markerVolumeBits();
			markerMassBits[cell] = identity.markerMassBits();
			solidMicroMask[cell] = identity.solidMicroMask();
			movingBlockedVolumeBits[cell] = identity.movingBlockedVolumeBits();
			solidOccupancySvq[cell] = identity.solidOccupancySvq();
			adjacentGasLineage[cell] = identity.adjacentGasLineage();
		}
		this.logicalGeneration = logicalGeneration;
		this.gaugePressurePa = gaugePressurePa.clone();
	}

	public static PressureProjectionSnapshot capture(
			long logicalGeneration, float[] gaugePressurePa, IntFunction<CellIdentity> identityAt
	) {
		return new PressureProjectionSnapshot(logicalGeneration, gaugePressurePa, identityAt);
	}

	public long logicalGeneration() { return logicalGeneration; }

	public Sample requireCurrentCell(
			long currentLogicalGeneration, int cell, CellIdentity currentIdentity,
			double absoluteReferencePressurePa
	) {
		if (currentLogicalGeneration != logicalGeneration) {
			throw new IllegalStateException("Pressure projection belongs to another logical generation");
		}
		if (cell < 0 || cell >= gaugePressurePa.length || currentIdentity == null) {
			throw new IllegalArgumentException("Pressure projection cell is invalid");
		}
		if (markerCount[cell] == 0
				|| markerCount[cell] != currentIdentity.markerCount()
				|| markerVolumeBits[cell] != currentIdentity.markerVolumeBits()
				|| markerMassBits[cell] != currentIdentity.markerMassBits()
				|| solidMicroMask[cell] != currentIdentity.solidMicroMask()
				|| movingBlockedVolumeBits[cell] != currentIdentity.movingBlockedVolumeBits()
				|| solidOccupancySvq[cell] != currentIdentity.solidOccupancySvq()
				|| adjacentGasLineage[cell] != currentIdentity.adjacentGasLineage()) {
			throw new IllegalStateException("Pressure projection cell carrier or geometry changed");
		}
		if (!(Double.isFinite(absoluteReferencePressurePa) && absoluteReferencePressurePa > 0.0)) {
			throw new IllegalArgumentException("Absolute pressure reference is invalid");
		}
		double absolute = absoluteReferencePressurePa + gaugePressurePa[cell];
		if (!(Double.isFinite(absolute) && absolute > 0.0)) {
			throw new IllegalStateException("Projected absolute pressure is outside the physical domain");
		}
		return new Sample(logicalGeneration, cell, gaugePressurePa[cell], absolute,
				adjacentGasLineage[cell]);
	}
}
