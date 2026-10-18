package mmm.fluid;

/** Local interface inversion; sixteen bisections resolve position to about 10^-5 cell widths. */
final class PlicPlaneOffset {
	record Result(double offset, double targetVolume) { }
	private PlicPlaneOffset() { }
	static Result solve(int supportMask, float nx, float ny, float nz, double target) {
		if (supportMask <= 0 || supportMask > 255 || !Float.isFinite(nx) || !Float.isFinite(ny) || !Float.isFinite(nz)
				|| nx == 0 && ny == 0 && nz == 0 || !Double.isFinite(target) || target < 0
				|| target > Integer.bitCount(supportMask) * .125)
			throw new IllegalArgumentException("Invalid PLIC offset inversion input");
		double[] normal = {nx,ny,nz};
		var cdf = new MicroBoxPlaneVolume.SelectedVolume(supportMask,normal);
		double lower = Double.POSITIVE_INFINITY, upper = Double.NEGATIVE_INFINITY;
		for (int micro=0;micro<8;micro++) if ((supportMask & 1<<micro)!=0) {
			double low=0,high=0;
			for(int axis=0;axis<3;axis++) {
				double n=normal[axis]; int bit=micro>>>axis&1;
				low+=n*(bit+(n<0?1:0))*.5; high+=n*(bit+(n<0?0:1))*.5;
			}
			lower=Math.min(lower,low); upper=Math.max(upper,high);
		}
		double volume=target;
		if (volume==0) upper=lower;
		else if(volume==Integer.bitCount(supportMask)*.125) lower=upper;
		else for(int iteration=0;iteration<16;iteration++) {
			double middle=lower+(upper-lower)*.5;
			if(middle==lower || middle==upper) break;
			double measured = cdf.measure(middle);
            if (measured == volume) { lower = upper = middle; break; }
            if(measured < volume) lower=middle; else upper=middle;
		}
		double offset=lower+(upper-lower)*.5;
		return new Result(offset,volume);
	}
}
