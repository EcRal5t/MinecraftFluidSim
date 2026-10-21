package mmm.fluid;

import mmm.fluid.air.GasPartitionWeights;
import java.util.List;
import java.util.Arrays;
import java.math.BigInteger;
import mmm.units.SignedDyadic;

/** Splits physical gas control volumes with the same bounded geometric shares as inventory. */
final class GasTopologyVolumePartition {
	private GasTopologyVolumePartition() { }

	/** Uses floating-point overlap weights and a shared conservative remainder. */
	static double[] partition(double total, List<Double> weights) {
		return partition(total, GasPartitionWeights.of(weights.stream().mapToDouble(Double::doubleValue).toArray()));
	}
	static double[] partition(double total, GasPartitionWeights weights) {
		try { return weights.partitionControlVolume(total); }
		catch (GasPartitionWeights.UnrepresentableVolumeException unavailable) {
			throw new GasTopologyBlockedException("Gas topology volume partition unavailable: " + unavailable.getMessage(), unavailable);
		}
	}
    static SignedDyadic[] partition(SignedDyadic total,GasPartitionWeights weights){return weights.partitionExact(total);}

    /** Signed receiver deformation is apportioned on its existing exact control-volume lattice. */
    static SignedDyadic[] partitionChange(SignedDyadic change,SignedDyadic[] controls){
        int exponent=change.exponent();
        for(var control:controls)if(control.signum()>0)exponent=Math.min(exponent,control.exponent());
        var total=BigInteger.ZERO;var weights=new BigInteger[controls.length];
        for(int i=0;i<controls.length;i++){
            if(controls[i].signum()<0)throw new IllegalArgumentException("Negative inherited gas control volume");
            weights[i]=controls[i].significand().shiftLeft(controls[i].signum()==0?0:controls[i].exponent()-exponent);
            total=total.add(weights[i]);
        }
        if(total.signum()==0)throw new IllegalStateException("Gas deformation has no inherited control volume");
        var amount=change.significand().abs().shiftLeft(change.exponent()-exponent);
        if(change.signum()<0){
            var remaining=total.subtract(amount);if(remaining.signum()<=0)throw new IllegalArgumentException("Gas compression exhausted actual receiver capacity");
            BigInteger smallest=null;for(var weight:weights)if(weight.signum()>0&&(smallest==null||weight.compareTo(smallest)<0))smallest=weight;
            // Refine the quantity lattice until every positive control retains a positive
            // share of the actual final volume, including a tiny source in a large chamber.
            var retained=remaining.multiply(smallest);var required=total.shiftLeft(1);
            int shift=Math.max(0,required.bitLength()-retained.bitLength());
            if(retained.shiftLeft(shift).compareTo(required)<0)shift++;
            if(shift>0){
                exponent=Math.subtractExact(exponent,shift);amount=amount.shiftLeft(shift);total=total.shiftLeft(shift);
                for(int i=0;i<weights.length;i++)weights[i]=weights[i].shiftLeft(shift);
            }
        }
        var parts=new BigInteger[controls.length];var remainders=new BigInteger[controls.length];var assigned=BigInteger.ZERO;
        for(int i=0;i<controls.length;i++){
            var quotient=amount.multiply(weights[i]).divideAndRemainder(total);
            parts[i]=quotient[0];remainders[i]=quotient[1];assigned=assigned.add(parts[i]);
        }
        int remainder=amount.subtract(assigned).intValueExact();
        if(remainder>0){
            Integer[] order=new Integer[controls.length];Arrays.setAll(order,i->i);
            Arrays.sort(order,(a,b)->{int compare=remainders[b].compareTo(remainders[a]);return compare!=0?compare:Integer.compare(a,b);});
            for(int i=0;i<remainder;i++)parts[order[i]]=parts[order[i]].add(BigInteger.ONE);
        }
        var result=new SignedDyadic[controls.length];
        for(int i=0;i<result.length;i++)result[i]=new SignedDyadic(change.signum()<0?parts[i].negate():parts[i],exponent);
        return result;
    }
}
