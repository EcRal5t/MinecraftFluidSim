package cc.ecisr.mmm.api.transfer;

import java.util.OptionalDouble;

/** A positive, explicitly unit-bearing amount transferred across a committed boundary. */
public sealed interface TransferQuantity permits TransferQuantity.Liquid, TransferQuantity.Granular {
	/** Liquid volume in 10^-9 cubic metres, with raw total internal energy when thermal truth moved. */
	record Liquid(long volumeNanovolumeQuanta, OptionalDouble internalEnergyJoules) implements TransferQuantity {
		public Liquid {
			if (volumeNanovolumeQuanta <= 0L || internalEnergyJoules == null
					|| (internalEnergyJoules.isPresent()
					&& !Double.isFinite(internalEnergyJoules.getAsDouble()))) {
				throw new IllegalArgumentException("Liquid transfer quantity is invalid");
			}
		}
	}

	/** Granular occupied volume in 2^-27 block-volume quanta. */
	record Granular(long solidVolumeQuanta) implements TransferQuantity {
		public Granular {
			if (solidVolumeQuanta <= 0L) {
				throw new IllegalArgumentException("Granular transfer quantity is invalid");
			}
		}
	}
}
