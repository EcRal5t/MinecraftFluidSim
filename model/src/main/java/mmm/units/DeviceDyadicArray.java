package mmm.units;

import java.math.BigInteger;
import java.util.List;

/** Lossless transfer columns: signed magnitude, little-endian unsigned 32-bit limbs and binary exponent. */
public record DeviceDyadicArray(int[] offsets, int[] signs, int[] exponents, int[] limbs) {
	public DeviceDyadicArray {
		offsets=offsets.clone(); signs=signs.clone(); exponents=exponents.clone(); limbs=limbs.clone();
		if(offsets.length!=signs.length+1||exponents.length!=signs.length||offsets[0]!=0||offsets[signs.length]!=limbs.length)
			throw new IllegalArgumentException("Dyadic column shapes differ");
		for(int i=0;i<signs.length;i++) {
			int start=offsets[i],end=offsets[i+1];
			if(start<0||end<start||end>limbs.length||end-start>(SignedDyadic.MAX_SIGNIFICAND_BITS+31)/32
					||signs[i]<-1||signs[i]>1||((start==end)!=(signs[i]==0))
					||start==end&&exponents[i]!=0||start<end&&(limbs[end-1]==0||(limbs[start]&1)==0))
				throw new IllegalArgumentException("Dyadic columns are not canonical");
		}
	}
	@Override public int[] offsets(){return offsets.clone();}
	@Override public int[] signs(){return signs.clone();}
	@Override public int[] exponents(){return exponents.clone();}
	@Override public int[] limbs(){return limbs.clone();}
	public int size(){return signs.length;}
	public static DeviceDyadicArray encode(List<SignedDyadic> values) {
		int[] offsets=new int[values.size()+1],signs=new int[values.size()],exponents=new int[values.size()];
		for(int i=0;i<values.size();i++)offsets[i+1]=Math.addExact(offsets[i],(values.get(i).significand().abs().bitLength()+31)/32);
		int[] limbs=new int[offsets[values.size()]];
		for(int i=0;i<values.size();i++) {
			var value=values.get(i); signs[i]=value.signum(); exponents[i]=value.exponent();
			var magnitude=value.significand().abs();
			for(int limb=offsets[i];limb<offsets[i+1];limb++){limbs[limb]=magnitude.intValue();magnitude=magnitude.shiftRight(32);}
		}
		return new DeviceDyadicArray(offsets,signs,exponents,limbs);
	}
	public SignedDyadic valueAt(int index) {
		BigInteger magnitude=BigInteger.ZERO;
		for(int limb=offsets[index+1]-1;limb>=offsets[index];limb--)
			magnitude=magnitude.shiftLeft(32).add(BigInteger.valueOf(Integer.toUnsignedLong(limbs[limb])));
		return new SignedDyadic(signs[index]<0?magnitude.negate():magnitude,exponents[index]);
	}
}
