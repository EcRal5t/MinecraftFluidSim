package mmm.fluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mmm.chemistry.ExactRational;

/** Double-precision convex clipping and tetrahedral moments about the cell centre. */
final class MicroBoxPlaneMoments {
	private static final int[][] AXES={{},{0},{1},{2},{0,0},{0,1},{0,2},{1,1},{1,2},{2,2},
			{0,0,0},{0,0,1},{0,0,2},{0,1,1},{0,1,2},{0,2,2},{1,1,1},{1,1,2},{1,2,2},{2,2,2}};
	private static final int[][] FACES={{0,2,6,4},{1,5,7,3},{0,4,5,1},{2,3,7,6},{0,1,3,2},{4,6,7,5}};
	private MicroBoxPlaneMoments() { }
	static ExactRational[] integrate(int micro,GasGeometryOverlap.HalfSpace plane,int order) {
		return integrate(micro,List.of(plane),order);
	}
	static ExactRational[] integrate(int micro,List<GasGeometryOverlap.HalfSpace> planes,int order) {
		if(micro<0 || micro>7 || order<0 || order>3) throw new IllegalArgumentException("Invalid clipped box integral");
		List<List<double[]>> faces=box(micro);
		for(var plane:planes) {
			faces=clip(faces,new double[]{plane.nx().toDouble(),plane.ny().toDouble(),plane.nz().toDouble()},plane.threshold().toDouble());
			if(faces.isEmpty()) break;
		}
		int count=order==3?20:order==2?10:4;
		double[] moments=new double[count];
		if(!faces.isEmpty()) {
			double[] centre=new double[3]; int vertices=0;
			for(var face:faces) for(var point:face) { for(int a=0;a<3;a++) centre[a]+=point[a]; vertices++; }
			for(int a=0;a<3;a++) centre[a]/=vertices;
			for(var face:faces) for(int i=1;i+1<face.size();i++)
				addTetra(moments,new double[][]{centre,face.getFirst(),face.get(i),face.get(i+1)},order);
		}
		var result=new ExactRational[count];
		for(int i=0;i<count;i++) result[i]=MicroBoxPlaneVolume.rational(moments[i]);
		return result;
	}
	private static List<List<double[]>> box(int micro) {
		double[][] points=new double[8][3];
		for(int p=0;p<8;p++) for(int a=0;a<3;a++) points[p][a]=((micro>>>a&1)+(p>>>a&1))*.5;
		var faces=new ArrayList<List<double[]>>(6);
		for(var indices:FACES) { var face=new ArrayList<double[]>(4); for(int i:indices) face.add(points[i]); faces.add(face); }
		return faces;
	}
	private static List<List<double[]>> clip(List<List<double[]>> faces,double[] n,double threshold) {
		double scale=Math.max(Math.abs(n[0]),Math.max(Math.abs(n[1]),Math.abs(n[2])));
		if(scale==0) return threshold<=0?faces:List.of();
		for(int a=0;a<3;a++) n[a]/=scale; threshold/=scale;
		var result=new ArrayList<List<double[]>>(faces.size()+1); var cap=new ArrayList<double[]>();
		for(var face:faces) {
			var output=new ArrayList<double[]>(); double[] previous=face.getLast(); double dp=dot(n,previous)-threshold;
			for(var current:face) {
				double dc=dot(n,current)-threshold;
				if((dp<0)!=(dc<0)) {
					double t=dp/(dp-dc); double[] point=new double[3];
					for(int a=0;a<3;a++) point[a]=Math.fma(t,current[a]-previous[a],previous[a]);
					output.add(point); cap.add(point);
				}
				if(dc>=0) output.add(current);
				previous=current; dp=dc;
			}
			if(output.size()>=3) result.add(output);
		}
		if(cap.size()>=3) {
			double[] centre=new double[3]; for(var p:cap) for(int a=0;a<3;a++) centre[a]+=p[a]/cap.size();
			int axis=Math.abs(n[0])<Math.abs(n[1])?0:1; if(Math.abs(n[2])<Math.abs(n[axis])) axis=2;
			double[] seed=new double[3]; seed[axis]=1; double[] u=cross(n,seed),v=cross(n,u);
			cap.sort(Comparator.comparingDouble(p->Math.atan2(dotDifference(v,p,centre),dotDifference(u,p,centre))));
			result.add(cap);
		}
		return result;
	}
	private static void addTetra(double[] moments,double[][] p,int order) {
		double[][] d=new double[3][3]; for(int i=0;i<3;i++) for(int a=0;a<3;a++) d[i][a]=p[i+1][a]-p[0][a];
		double volume=Math.abs(dot(d[0],cross(d[1],d[2])))/6;
		moments[0]+=volume; if(volume==0 || order==0) return;
		double[][] x=new double[4][3]; double[] sums=new double[3];
		for(int i=0;i<4;i++) for(int a=0;a<3;a++) { x[i][a]=p[i][a]-.5; sums[a]+=x[i][a]; }
		for(int k=1;k<moments.length;k++) {
			int[] axes=AXES[k]; int a=axes[0]; double value;
			if(axes.length==1) value=sums[a]/4;
			else {
				int b=axes[1]; double ab=0; for(int i=0;i<4;i++) ab+=x[i][a]*x[i][b];
				if(axes.length==2) value=(sums[a]*sums[b]+ab)/20;
				else {
					int c=axes[2]; double ac=0,bc=0,abc=0;
					for(int i=0;i<4;i++) { ac+=x[i][a]*x[i][c]; bc+=x[i][b]*x[i][c]; abc+=x[i][a]*x[i][b]*x[i][c]; }
					value=(sums[a]*sums[b]*sums[c]+ab*sums[c]+ac*sums[b]+bc*sums[a]+2*abc)/120;
				}
			}
			moments[k]+=volume*value;
		}
	}
	private static double dot(double[] a,double[] b) { return Math.fma(a[0],b[0],Math.fma(a[1],b[1],a[2]*b[2])); }
	private static double dotDifference(double[] a,double[] p,double[] c) { return a[0]*(p[0]-c[0])+a[1]*(p[1]-c[1])+a[2]*(p[2]-c[2]); }
	private static double[] cross(double[] a,double[] b) { return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]}; }
}
