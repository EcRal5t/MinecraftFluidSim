package mmm.fluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/** Floating-point convex clipping and microbox overlap volume. */
final class GasGeometryOverlap {
	private static final int[][] FACES={{0,2,6,4},{1,5,7,3},{0,4,5,1},{2,3,7,6},{0,1,3,2},{4,6,7,5}};
	private GasGeometryOverlap() { }
    record HalfSpace(double nx, double ny, double nz, double threshold) {
        HalfSpace(mmm.units.SignedDyadic nx, mmm.units.SignedDyadic ny,
                  mmm.units.SignedDyadic nz, mmm.units.SignedDyadic threshold) {
            this(nx.toDouble(), ny.toDouble(), nz.toDouble(), threshold.toDouble());
        }
    }
    static double normalizedVolume(int micro, List<HalfSpace> planes) {
        if (micro < 0 || micro > 7) throw new IllegalArgumentException("Invalid gas microbox");
        var selected = new ArrayList<HalfSpace>(planes.size());
        for (var plane : planes) {
            if (plane == null) continue;
            double scale = Math.max(Math.abs(plane.nx()), Math.max(Math.abs(plane.ny()), Math.abs(plane.nz())));
            if (scale == 0) { if (plane.threshold() > 0) return 0; else continue; }
            var normalized = new HalfSpace(plane.nx() / scale, plane.ny() / scale, plane.nz() / scale, plane.threshold() / scale);
            if (!selected.contains(normalized)) selected.add(normalized);
        }
        if (selected.isEmpty()) return .125;
        if (selected.size() == 1) return MicroBoxPlaneVolume.dryVolume(micro, selected.getFirst());
        List<List<double[]>> faces = box(micro);
        for (var plane : selected) {
            faces = clip(faces, new double[]{plane.nx(), plane.ny(), plane.nz()}, plane.threshold());
            if (faces.isEmpty()) return 0;
        }
        double[] centre = new double[3]; int vertices = 0;
        for (var face : faces) for (var point : face) {
            for (int a = 0; a < 3; a++) centre[a] += point[a];
            vertices++;
        }
        if (vertices == 0) return 0;
        for (int a = 0; a < 3; a++) centre[a] /= vertices;
        double volume = 0;
        for (var face : faces) for (int i = 1; i + 1 < face.size(); i++) {
            double[] a = difference(face.getFirst(), centre), b = difference(face.get(i), centre), c = difference(face.get(i + 1), centre);
            volume += Math.abs(dot(a, cross(b, c))) / 6;
        }
        return Math.clamp(volume, 0, .125);
    }
    static boolean hasPositiveOverlap(int micro, HalfSpace before, HalfSpace after) {
        if (before == null) return after == null || MicroBoxPlaneVolume.dryVolume(micro, after) > 0;
        if (after == null) return MicroBoxPlaneVolume.dryVolume(micro, before) > 0;
        return normalizedVolume(micro, List.of(before, after)) > 0;
    }
    private static double[] difference(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
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
			var output=new ArrayList<double[]>(); double[] previous=face.getLast(); double dp=distance(n,previous,threshold);
			for(var current:face) {
				double dc=distance(n,current,threshold);
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
    private static double distance(double[] normal, double[] point, double threshold) {
        double value = dot(normal, point) - threshold;
        double scale = Math.abs(normal[0] * point[0]) + Math.abs(normal[1] * point[1]) + Math.abs(normal[2] * point[2]) + Math.abs(threshold);
        return Math.abs(value) <= 8 * Math.ulp(scale) ? 0 : value;
    }
	private static double dot(double[] a,double[] b) { return Math.fma(a[0],b[0],Math.fma(a[1],b[1],a[2]*b[2])); }
	private static double dotDifference(double[] a,double[] p,double[] c) { return a[0]*(p[0]-c[0])+a[1]*(p[1]-c[1])+a[2]*(p[2]-c[2]); }
	private static double[] cross(double[] a,double[] b) { return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]}; }
}
