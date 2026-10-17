package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Floating-point microbox clipping; returned weights feed exact inventory allocation. */
final class GasGeometryOverlap {
	record HalfSpace(SignedDyadic nx,SignedDyadic ny,SignedDyadic nz,SignedDyadic threshold) {
		HalfSpace { Objects.requireNonNull(nx); Objects.requireNonNull(ny); Objects.requireNonNull(nz); Objects.requireNonNull(threshold); }
	}
	private static final int[][] AFFINE_PRODUCTS={{1,2,3},{4,5,6},{5,7,8},{6,8,9},{10,11,12},{11,13,14},{12,14,15},{13,16,17},{14,17,18},{15,18,19}};
	private GasGeometryOverlap() { }
	static ExactRational normalizedVolume(int micro,List<HalfSpace> planes) {
		if(planes.size()==1) return MicroBoxPlaneVolume.dryVolume(micro,planes.getFirst());
		return integrate(micro,planes,0)[0];
	}
	static ExactRational[] normalizedMoments(int micro,List<HalfSpace> planes) { return integrate(micro,planes,1); }
	static ExactRational[] normalizedSecondMoments(int micro,List<HalfSpace> planes) { return integrate(micro,planes,2); }
	static ExactRational unionVolume(int micro,List<List<HalfSpace>> supports) {
		double volume=0; for(var support:supports) volume+=normalizedVolume(micro,support).toDouble();
		return MicroBoxPlaneVolume.rational(volume);
	}
	static ExactRational unionOverlapVolume(int micro,List<List<HalfSpace>> before,List<List<HalfSpace>> after) {
		double volume=0;
		for(var a:before) for(var b:after) { var planes=new ArrayList<>(a); planes.addAll(b); volume+=normalizedVolume(micro,planes).toDouble(); }
		return MicroBoxPlaneVolume.rational(volume);
	}
	static boolean hasPositiveOverlap(int micro,HalfSpace before,HalfSpace after) {
		var planes=new ArrayList<HalfSpace>(2); if(before!=null) planes.add(before); if(after!=null) planes.add(after);
		return normalizedVolume(micro,planes).signum()>0;
	}
	static ExactRational maximumLinearValue(int micro,HalfSpace support,HalfSpace function) {
		double[] distances=new double[8],values=new double[8]; double maximum=0; boolean positive=false;
		for(int corner=0;corner<8;corner++) {
			distances[corner]=cornerDistance(micro,corner,support); values[corner]=cornerDistance(micro,corner,function);
			positive|=distances[corner]>0;
			if(distances[corner]>=0) maximum=Math.max(maximum,values[corner]);
		}
		if(!positive) return ExactRational.ZERO;
		for(int corner=0;corner<8;corner++) for(int axis=0;axis<3;axis++) if((corner&1<<axis)==0) {
			int peer=corner|1<<axis;
			if((distances[corner]<0)==(distances[peer]<0)) continue;
			double t=distances[corner]/(distances[corner]-distances[peer]);
			maximum=Math.max(maximum,Math.fma(t,values[peer]-values[corner],values[corner]));
		}
		return MicroBoxPlaneVolume.rational(maximum);
	}
	private static double cornerDistance(int micro,int corner,HalfSpace p) {
		return p.nx.toDouble()*((micro&1)+(corner&1))*.5+p.ny.toDouble()*((micro>>>1&1)+(corner>>>1&1))*.5
				+p.nz.toDouble()*((micro>>>2&1)+(corner>>>2&1))*.5-p.threshold.toDouble();
	}
	static ExactRational[] normalizedAffineSecondMoments(int micro,List<HalfSpace> planes,ExactRational[] coefficients) {
		if(coefficients==null || coefficients.length!=4) throw new IllegalArgumentException("Affine overlap requires four coefficients");
		int order=coefficients[1].isZero()&&coefficients[2].isZero()&&coefficients[3].isZero()?2:3;
		var moments=integrate(micro,planes,order); var result=new ExactRational[10];
		for(int k=0;k<10;k++) {
			double value=moments[k].toDouble()*coefficients[0].toDouble();
			for(int a=0;a<3;a++) if(!coefficients[a+1].isZero()) value=Math.fma(moments[AFFINE_PRODUCTS[k][a]].toDouble(),coefficients[a+1].toDouble(),value);
			result[k]=MicroBoxPlaneVolume.rational(value);
		}
		return result;
	}
	private static ExactRational[] integrate(int micro,List<HalfSpace> planes,int order) {
		return MicroBoxPlaneMoments.integrate(micro,planes,order);
	}
}
