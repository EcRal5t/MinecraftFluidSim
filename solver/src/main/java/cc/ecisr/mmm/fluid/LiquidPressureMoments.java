package cc.ecisr.mmm.fluid;

/** Analytic triangle moments of a copied PLIC face polygon, in physical SI coordinates. */
final class LiquidPressureMoments {
	private LiquidPressureMoments() { }

	static LiquidPressureGeometry.SurfaceMoments integrate(double[][] polygon, int axis, double coordinate,
	                                                        int cell, FluidDomain domain) {
		int n = polygon[0].length;
		if (n < 3) return LiquidPressureGeometry.SurfaceMoments.EMPTY;
		int[] index = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
		double h = domain.config.cellSize(), area = 0;
		double[] first = new double[3];
		for (int i = 1; i + 1 < n; i++) {
			double cross = (polygon[0][i] - polygon[0][0]) * (polygon[1][i + 1] - polygon[1][0])
					- (polygon[1][i] - polygon[1][0]) * (polygon[0][i + 1] - polygon[0][0]);
			double triangle = Math.abs(cross) * .5 * h * h;
			if (triangle == 0) continue;
			double[][] vertices = new double[3][3]; int[] slots = {0, i, i + 1};
			for (int v = 0; v < 3; v++) {
				vertices[v][axis] = coordinate * h;
				vertices[v][(axis + 1) % 3] = polygon[0][slots[v]] * h;
				vertices[v][(axis + 2) % 3] = polygon[1][slots[v]] * h;
			}
			area += triangle;
			for (int a = 0; a < 3; a++) first[a] += triangle * (vertices[0][a] + vertices[1][a] + vertices[2][a]) / 3;
		}
		if(area==0)return LiquidPressureGeometry.SurfaceMoments.EMPTY;
		double[] center={first[0]/area,first[1]/area,first[2]/area},second=new double[6];
		for(int i=1;i+1<n;i++) {
			double cross=(polygon[0][i]-polygon[0][0])*(polygon[1][i+1]-polygon[1][0])-(polygon[1][i]-polygon[1][0])*(polygon[0][i+1]-polygon[0][0]);
			double triangle=Math.abs(cross)*.5*h*h;double[][] vertices=new double[3][3];int[] slots={0,i,i+1};
			for(int v=0;v<3;v++) {
				vertices[v][axis]=coordinate*h-center[axis];
				vertices[v][(axis+1)%3]=polygon[0][slots[v]]*h-center[(axis+1)%3];
				vertices[v][(axis+2)%3]=polygon[1][slots[v]]*h-center[(axis+2)%3];
			}
			int entry=0;
			for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) {
				double sa = 0, sb = 0, diagonal = 0;
				for (int v = 0; v < 3; v++) { sa += vertices[v][a]; sb += vertices[v][b]; diagonal += vertices[v][a] * vertices[v][b]; }
				second[entry++] += triangle * (sa * sb + diagonal) / 12;
			}
		}
		var centroid=new LiquidPressureGeometry.Vec3(index[0]*h+center[0],index[1]*h+center[1],index[2]*h+center[2]);
		var central=new LiquidPressureGeometry.SecondMoment(second[0],second[1],second[2],second[3],second[4],second[5]);
		return new LiquidPressureGeometry.SurfaceMoments(area,centroid.scale(area),central.add(LiquidPressureGeometry.SecondMoment.outer(centroid,area)),centroid,central);
	}
}
