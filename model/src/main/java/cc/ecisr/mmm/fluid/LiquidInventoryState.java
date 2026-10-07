package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;

/** Immutable extensive liquid quantities; floating-point carrier columns are their observations. */
public final class LiquidInventoryState {
	private final SignedDyadic[] volume, mass;
	private final SignedDyadic totalVolume, totalMass;
	private final long cpuBytes;

	public LiquidInventoryState(SignedDyadic[] volume, SignedDyadic[] mass) {
		if (volume == null || mass == null || volume.length != mass.length)
			throw new IllegalArgumentException("Liquid inventory columns disagree");
		this.volume = volume.clone(); this.mass = mass.clone();
		var v = SignedDyadic.ZERO; var m = SignedDyadic.ZERO; long bytes = 0;
		for (int p = 0; p < volume.length; p++) {
			bytes = Math.addExact(bytes, quantityBytes(this.volume[p]) + quantityBytes(this.mass[p]));
			if (!Float.isFinite(this.volume[p].toFloat())) throw new IllegalArgumentException("Liquid volume exceeds carrier observation range");
			v = v.add(this.volume[p]); m = m.add(this.mass[p]);
		}
		if (!Double.isFinite(v.toDouble()) || !Double.isFinite(m.toDouble()))
			throw new IllegalArgumentException("Liquid inventory total exceeds observation range");
		totalVolume = v; totalMass = m; cpuBytes = bytes;
	}

	public int count() { return volume.length; }
	public SignedDyadic volume(int marker) { return volume[marker]; }
	public SignedDyadic mass(int marker) { return mass[marker]; }
	public SignedDyadic totalVolume() { return totalVolume; }
	public SignedDyadic totalMass() { return totalMass; }
	public long cpuBytes() { return cpuBytes; }
	private static long quantityBytes(SignedDyadic value) {
		double observed = value == null ? Double.NaN : value.toDouble();
		if (!(observed > 0) || !Double.isFinite(observed)) throw new IllegalArgumentException("Liquid inventory quantity has no positive finite observation");
		return 40L + (value.significand().abs().bitLength() + 7L) / 8;
	}

	public void requireObservations(float[] positionVolume, double[] observedMass) {
		if (positionVolume == null || positionVolume.length != Math.multiplyExact(count(), 4)
				|| observedMass != null && observedMass.length != count())
			throw new IllegalArgumentException("Liquid inventory observations disagree with carrier count");
		for (int p = 0; p < count(); p++) {
			if (Float.floatToRawIntBits(volume[p].toFloat()) != Float.floatToRawIntBits(positionVolume[4 * p + 3])
					|| observedMass != null && Double.doubleToRawLongBits(mass[p].toDouble()) != Double.doubleToRawLongBits(observedMass[p]))
				throw new IllegalArgumentException("Liquid carrier observation differs from its extensive inventory");
		}
	}
}
