package com.example.fluid.air;

/**
 * State of one gas-network node (a sealed cavity).
 *
 * <p>The physical truth is the standard-volume inventory: the volume the
 * node's gas would occupy at the reference pressure, kept as exact
 * nanovolume quanta so merges, splits, orifice transport and venting conserve
 * the ledger without floating-point drift. Available volume is derived
 * numerical state refreshed from the owning grid each step; gauge pressure is
 * computed by {@link AirNetwork} and never stored here.</p>
 */
final class AirNode {
	/** One nanovolume quantum in m³. */
	static final double NANO_VOLUME = 1.0E-9;

	private long standardVolumeNanos;
	private double availableVolume;

	AirNode(double availableVolume, long standardVolumeNanos) {
		setAvailableVolume(availableVolume);
		if (standardVolumeNanos < 0L) throw new IllegalArgumentException("Gas inventory cannot be negative");
		this.standardVolumeNanos = standardVolumeNanos;
	}

	long standardVolumeNanos() { return standardVolumeNanos; }

	void setStandardVolumeNanos(long value) {
		if (value < 0L) throw new IllegalArgumentException("Gas inventory cannot be negative");
		this.standardVolumeNanos = value;
	}

	double standardVolume() { return standardVolumeNanos * NANO_VOLUME; }

	double availableVolume() { return availableVolume; }

	void setAvailableVolume(double value) {
		if (!(value >= 0.0) || Double.isNaN(value) || Double.isInfinite(value)) {
			throw new IllegalArgumentException("Available volume must be finite and non-negative");
		}
		this.availableVolume = value;
	}

	static long toNanos(double cubicMeters) {
		if (!(cubicMeters >= 0.0) || Double.isNaN(cubicMeters) || cubicMeters > Long.MAX_VALUE * NANO_VOLUME) {
			throw new IllegalArgumentException("Volume is outside the nanovolume ledger range: " + cubicMeters);
		}
		return Math.round(cubicMeters / NANO_VOLUME);
	}
}
