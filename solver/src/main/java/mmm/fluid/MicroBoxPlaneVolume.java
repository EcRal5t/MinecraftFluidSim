package mmm.fluid;

/** Floating-point half-space volumes in whole-cell units. */
final class MicroBoxPlaneVolume {
	private MicroBoxPlaneVolume() { }
	static final class SelectedVolume {
		private final BoxProjection projection;
		private final double[] shifts;
		SelectedVolume(int supportMask, double[] normal) {
			projection = new BoxProjection(normal, supportMask == 255 ? 1 : .5);
			shifts = new double[supportMask == 255 ? 1 : Integer.bitCount(supportMask)];
			if (supportMask == 255) shifts[0] = projection.shift(normal, 0);
			else for (int micro = 0, at = 0; micro < 8; micro++)
				if ((supportMask & 1 << micro) != 0) shifts[at++] = projection.shift(normal, micro);
		}
		double measure(double offset) {
			double scaledOffset = offset / projection.scale, sum = 0;
			for (double shift : shifts) sum += projection.measure(scaledOffset - shift);
			return sum;
		}
	}
	static double box(double[] n, double offset, int micro, double side) {
		BoxProjection projection = new BoxProjection(n, side);
		return projection.scale == 0 ? offset >= 0 ? side*side*side : 0
				: projection.measure(offset / projection.scale - projection.shift(n, micro));
	}
	/** Fixed plane direction and box size, shared by all offset inversion trials. */
	private static final class BoxProjection {
		private final double scale, side, volume, total, a, b, c;
		private final int dimensions;
		BoxProjection(double[] n, double side) {
			this.side = side;
			volume = side*side*side;
			scale = Math.max(Math.abs(n[0]),Math.max(Math.abs(n[1]),Math.abs(n[2])));
			double[] width = new double[3]; int count = 0;
			if (scale != 0) for (double value : n) if (value != 0) width[count++] = Math.abs(value / scale) * side;
			java.util.Arrays.sort(width,0,count);
			dimensions = count; a = width[0]; b = width[1]; c = width[2]; total = a+b+c;
		}
		double shift(double[] n, int micro) {
			double shift = 0;
			for (int axis = 0; axis < 3; axis++) {
				double coefficient = n[axis] / scale;
				shift += coefficient * ((micro >>> axis & 1) * side + (coefficient < 0 ? side : 0));
			}
			return shift;
		}
		double measure(double t) {
			if (t <= 0) return 0;
			if (t >= total) return volume;
			boolean complement = t > total*.5;
			if (complement) t = total-t;
			double f;
			if (dimensions == 1) f = t/a;
			else {
				if (dimensions == 2) f = t <= a ? (t/a)*(t/b)*.5 : (t-.5*a)/b;
				else {
					if (t <= a) f = (t/a)*(t/b)*(t/c)/6;
					else if (t <= b) f = (3*t*(t-a)+a*a)/(6*b*c);
					else if (t >= a+b) f = (t-.5*(a+b))/c;
					else {
						double u = t-b, v = Math.max(0,t-c);
						f = (3*t*(t-a)+a*a-(u*u*u+v*v*v)/a)/(6*b*c);
					}
				}
			}
			return Math.clamp(complement ? 1-f : f,0,1)*volume;
		}
	}
    /** First vertical moment of a clipped microbox in whole-cell coordinates.
     * Each horizontal-section area is quadratic between projected box vertices;
     * two-point Gauss integration therefore integrates y times area exactly. */
    static double verticalMoment(double nx,double ny,double nz,double offset,int micro) {
        double side=.5,low=side*(micro>>1&1);
        double[] horizontal={nx,0,nz};
        if(ny==0)return box(horizontal,offset,micro,side)*(low+.25);
        double[] cuts={low,low+side,0,0,0,0};int count=2;
        for(int x=0;x<2;x++)for(int z=0;z<2;z++) {
            double y=(offset-nx*side*((micro&1)+x)-nz*side*((micro>>2&1)+z))/ny;
            if(y>low&&y<low+side)cuts[count++]=y;
        }
        java.util.Arrays.sort(cuts,0,count);double moment=0;
        for(int i=1;i<count;i++) {
            double middle=.5*(cuts[i]+cuts[i-1]),half=.5*(cuts[i]-cuts[i-1]),q=half/Math.sqrt(3);
            double first=middle-q,second=middle+q;
            moment+=half/side*(first*box(horizontal,offset-ny*first,micro,side)
                    +second*box(horizontal,offset-ny*second,micro,side));
        }
        return moment;
    }
    static double wetVolume(int micro, GasGeometryOverlap.HalfSpace plane) {
        return box(new double[]{plane.nx(), plane.ny(), plane.nz()}, plane.threshold(), micro, .5);
    }
    static double dryVolume(int micro, GasGeometryOverlap.HalfSpace plane) {
        return box(new double[]{-plane.nx(), -plane.ny(), -plane.nz()}, -plane.threshold(), micro, .5);
    }
}
