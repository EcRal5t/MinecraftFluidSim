package mmm.api.transfer;

import java.util.OptionalDouble;

/** A positive, explicitly unit-bearing amount transferred across a committed boundary. */
public sealed interface TransferQuantity permits TransferQuantity.Liquid, TransferQuantity.Granular, TransferQuantity.Gas {
	/** Liquid volume in 10^-9 cubic metres, with raw total internal energy when thermal truth moved. */
	record Liquid(long volumeNanos, OptionalDouble internalEnergyJoules) implements TransferQuantity {
		public Liquid {
			if (volumeNanos <= 0L || internalEnergyJoules == null
					|| (internalEnergyJoules.isPresent()
					&& !Double.isFinite(internalEnergyJoules.getAsDouble()))) {
				throw new IllegalArgumentException("Liquid transfer quantity is invalid");
			}
		}
	}

	/** Gas volume at the registry standard state, with total carried internal energy in joules. */
	record Gas(long standardVolumeNanos, double internalEnergyJoules) implements TransferQuantity {
		public Gas {
			if (standardVolumeNanos <= 0L || !Double.isFinite(internalEnergyJoules)) {
				throw new IllegalArgumentException("Gas transfer quantity is invalid");
			}
		}
	}

	/** Granular occupied volume in 2^-27 cubic metre quanta. */
	record Granular(long solidVolumeSvq) implements TransferQuantity {
		public Granular {
			if (solidVolumeSvq <= 0L) {
				throw new IllegalArgumentException("Granular transfer quantity is invalid");
			}
		}
	}
}
