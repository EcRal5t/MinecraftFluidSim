package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.ExactVolume;

/** Immutable extensive liquid quantities; floating-point carrier columns are their observations. */
public final class LiquidInventoryState {
	private final ExactVolume[] volume;
	private final SignedDyadic[] mass;
	private final ExactVolume totalVolume;
	private final SignedDyadic totalMass;
	private final long cpuBytes;

	public LiquidInventoryState(SignedDyadic[] volume, SignedDyadic[] mass) {
		this(volume == null ? null : java.util.Arrays.stream(volume).map(ExactVolume::of).toArray(ExactVolume[]::new), mass);
	}
	public LiquidInventoryState(ExactVolume[] volume, SignedDyadic[] mass) {
		if (volume == null || mass == null || volume.length != mass.length)
			throw new IllegalArgumentException("Liquid inventory columns disagree");
		this.volume = volume.clone(); this.mass = mass.clone();
		var v = ExactVolume.ZERO; var m = SignedDyadic.ZERO; long bytes = 0;
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
	public ExactVolume volume(int marker) { return volume[marker]; }
	public SignedDyadic mass(int marker) { return mass[marker]; }
	public ExactVolume totalVolume() { return totalVolume; }
	public SignedDyadic totalMass() { return totalMass; }
	public long cpuBytes() { return cpuBytes; }
	private static long quantityBytes(ExactVolume value) {
		double observed = value == null ? Double.NaN : value.toDouble();
		if (!(observed > 0) || !Double.isFinite(observed)) throw new IllegalArgumentException("Liquid volume has no positive finite observation");
		return 64L + (value.numerator().abs().bitLength() + value.denominator().bitLength() + 7L) / 8;
	}
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
