package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Actual marker inertia, independent cell-flux assembly, pressure work and finite gas storage. */
public final class MarkerPressureValidation {
	private record Layout(FluidDomain domain, MarkerVelocityTransfer transfer, MarkerPressureOperator operator,
	                      int[] cells) { }
	private MarkerPressureValidation() { }
	public static void main(String[] args) { validate(); System.out.println("MarkerPressureValidation PASS"); }
	public static void validate() {
		var free = layout(false); verifyIndependentFluxAndGram(free); projectFreeSurface(free);
		var closed = layout(true); verifyIndependentFluxAndGram(closed); closedPressureKernel(closed);
		finiteGasStorage();
		tinyMassPressureDiagonal();
	}

	private static Layout layout(boolean full) {
		var d = new FluidDomain(4, 4, 4, new FluidSolverConfig(.5F, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, .8F), 2048);
		for (int z = full ? 0 : 1; z < (full ? 4 : 3); z++) for (int y = full ? 0 : 1; y < (full ? 4 : 3); y++)
			for (int x = full ? 0 : 1; x < (full ? 4 : 3); x++) d.setVolume(x, y, z, full ? 1 : .6F);
		if (full) for(int marker=0;marker<d.particles.count();marker++)d.particles.setMass(marker,d.particles.mass(marker)*(1+.0137*marker));
		d.ensureGrid(); var j = new MarkerVelocityTransfer(); j.capture(d);
		int[] cells = java.util.stream.IntStream.range(0, d.cellCount).filter(d::isFluid).toArray();
		int[] cellRows = new int[d.cellCount]; Arrays.fill(cellRows, -1);
		for (int row = 0; row < cells.length; row++) cellRows[cells[row]] = row;
		int[][] first = new int[3][], second = new int[3][]; double[][] area = faces(j);
		for (int axis = 0; axis < 3; axis++) {
			first[axis] = new int[j.faceCount(axis)]; second[axis] = new int[j.faceCount(axis)];
			Arrays.fill(first[axis], -1); Arrays.fill(second[axis], -1);
			for (int z = 0; z < d.sizeZ; z++) for (int y = 0; y < d.sizeY; y++) for (int x = 0; x < d.sizeX; x++) {
				if ((axis == 0 && x == 0) || (axis == 1 && y == 0) || (axis == 2 && z == 0)) continue;
				int hi = d.cellIndex(x,y,z), lo = d.cellIndex(x-(axis==0?1:0), y-(axis==1?1:0), z-(axis==2?1:0));
				int face = axis == 0 ? d.xFaceIndex(x,y,z) : axis == 1 ? d.yFaceIndex(x,y,z) : d.zFaceIndex(x,y,z);
				first[axis][face] = cellRows[lo]; second[axis][face] = cellRows[hi];
				if (first[axis][face] != -1 || second[axis][face] != -1) area[axis][face] = .25 * d.geometry.cutCells.openFaceField(axis)[face];
			}
		}
		return new Layout(d, j, new MarkerPressureOperator(j, cells.length, first, second, area, new double[cells.length]), cells);
	}

	private static double[][] markers(MarkerVelocityTransfer j) { return new double[3][j.markerCount()]; }
	private static double[][] faces(MarkerVelocityTransfer j) {
		return new double[][]{new double[j.faceCount(0)],new double[j.faceCount(1)],new double[j.faceCount(2)]};
	}
	private static void near(double actual, double expected, double tolerance, String label) {
		if (!(Double.isFinite(actual) && Math.abs(actual-expected) <= tolerance)) throw new AssertionError(label+": "+actual+" != "+expected);
	}
	private static double dot(double[] a, double[] b) { double value=0; for (int i=0;i<a.length;i++) value+=a[i]*b[i]; return value; }
	private static double work(double[][] a, double[][] b) { double value=0; for(int axis=0;axis<3;axis++) value+=dot(a[axis],b[axis]); return value; }
	private static double kinetic(MarkerVelocityTransfer j, double[][] velocity) {
		double result=0; for(int axis=0;axis<3;axis++) for(int marker=0;marker<j.markerCount();marker++) result+=.5*j.massKilograms(marker)*velocity[axis][marker]*velocity[axis][marker]; return result;
	}
	private static double[] cellFlux(Layout l, double[][] velocity) {
		var mac=faces(l.transfer); l.transfer.apply(velocity,mac);return macFlux(l,mac);
	}
	private static double[] macFlux(Layout l, double[][] mac) {
		var d=l.domain; double[] result=new double[l.cells.length];
		for(int row=0;row<result.length;row++) {
			int cell=l.cells[row], x=cell%d.sizeX, y=cell/d.sizeX%d.sizeY, z=cell/(d.sizeX*d.sizeY);
			result[row]=.25*(d.pressureXFaceOpen(x+1,y,z)*mac[0][d.xFaceIndex(x+1,y,z)]-d.pressureXFaceOpen(x,y,z)*mac[0][d.xFaceIndex(x,y,z)]
					+d.pressureYFaceOpen(x,y+1,z)*mac[1][d.yFaceIndex(x,y+1,z)]-d.pressureYFaceOpen(x,y,z)*mac[1][d.yFaceIndex(x,y,z)]
					+d.pressureZFaceOpen(x,y,z+1)*mac[2][d.zFaceIndex(x,y,z+1)]-d.pressureZFaceOpen(x,y,z)*mac[2][d.zFaceIndex(x,y,z)]);
		}
		return result;
	}

	private static void verifyIndependentFluxAndGram(Layout l) {
		var j=l.transfer; var c=l.operator; int n=c.rowCount();
		if(c.unsupportedFaceCount()!=0) throw new AssertionError("Fixture has unrepresented pressure faces");
		var v=markers(j); for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) v[a][i]=Math.sin(.37*i+a);
		double[] actual=new double[n]; c.volumeRate(v,actual); var expected=cellFlux(l,v);
		for(int r=0;r<n;r++) near(actual[r],expected[r],2e-15,"Independent cell flux");
		// Independent dense C columns come from MAC divergence of basis marker velocities.
		double[][] dense=new double[n][3*j.markerCount()]; var basis=markers(j);
		for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) {
			basis[a][i]=1; var column=cellFlux(l,basis); basis[a][i]=0;
			for(int r=0;r<n;r++) dense[r][a*j.markerCount()+i]=column[r];
		}
		double[] p=new double[n]; for(int r=0;r<n;r++) p[r]=Math.cos(.73*r);
		var force=markers(j); c.force(p,force); near(work(v,force),dot(actual,p),3e-14,"Pressure virtual work");
		for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) {
			double expectedForce=0; for(int r=0;r<n;r++) expectedForce+=dense[r][a*j.markerCount()+i]*p[r];
			near(force[a][i],expectedForce,2e-15,"Independent adjoint force");
		}
		double[] diagonal=new double[n]; c.diagonal(diagonal);
		for(int col=0;col<n;col++) {
			double[] unit=new double[n], applied=new double[n]; unit[col]=1; c.apply(unit,applied);
			for(int r=0;r<n;r++) {
				double expectedGram=0; for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) expectedGram+=dense[r][a*j.markerCount()+i]*dense[col][a*j.markerCount()+i]/j.massKilograms(i);
				near(applied[r],expectedGram,3e-17,"Independent pressure inertia");
			}
			near(diagonal[col],applied[col],3e-17,"Merged row diagonal");
		}
	}

	private static void projectFreeSurface(Layout l) {
		var j=l.transfer; var c=l.operator; int n=c.rowCount(); double dt=.001;
		var v=markers(j); for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) v[a][i]=Math.sin(.37*i+a);
		double[] rhs=new double[n]; c.volumeRate(v,rhs); for(int r=0;r<n;r++) rhs[r]/=-dt;
		double[][] matrix=new double[n][n];
		for(int col=0;col<n;col++) { double[] unit=new double[n], value=new double[n]; unit[col]=1;c.apply(unit,value);for(int r=0;r<n;r++) matrix[r][col]=value[r]; }
		// Independent small Cholesky oracle; no diagonal regularization or invented inertia.
		for(int r=0;r<n;r++) for(int col=0;col<=r;col++) {
			double sum=matrix[r][col]; for(int k=0;k<col;k++) sum-=matrix[r][k]*matrix[col][k];
			if(r==col) { if(!(sum>0)) throw new AssertionError("Free-surface pressure lost rank");matrix[r][col]=Math.sqrt(sum); }
			else matrix[r][col]=sum/matrix[col][col];
		}
		double[] p=rhs.clone(); for(int r=0;r<n;r++) {for(int k=0;k<r;k++) p[r]-=matrix[r][k]*p[k];p[r]/=matrix[r][r];}
		for(int r=n-1;r>=0;r--) {for(int k=r+1;k<n;k++) p[r]-=matrix[k][r]*p[k];p[r]/=matrix[r][r];}
		var force=markers(j); c.force(p,force); var next=markers(j); var midpoint=markers(j);
		for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) {next[a][i]=v[a][i]+dt*force[a][i]/j.massKilograms(i);midpoint[a][i]=.5*(v[a][i]+next[a][i]);}
		double[] residual=new double[n]; c.volumeRate(next,residual);
		for(double value:residual) near(value,0,8e-16,"Actual marker continuity");
		for(int a=0;a<3;a++) {double impulse=0;for(int i=0;i<j.markerCount();i++) impulse+=j.massKilograms(i)*(next[a][i]-v[a][i]);near(impulse,0,2e-12,"Closed pressure surface impulse");}
		double change=kinetic(j,next)-kinetic(j,v); near(change,dt*work(midpoint,force),5e-13,"Finite pressure impulse work");
		double[] hp=new double[n];c.apply(p,hp);near(change,-.5*dt*dt*dot(p,hp),5e-13,"Projection dissipation");
		for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) next[a][i]=(float)next[a][i];
		var roundedFlux=cellFlux(l,next); for(double value:roundedFlux) near(value,0,2e-8,"Float marker publication continuity");
		float[][] published=new float[][]{new float[j.faceCount(0)],new float[j.faceCount(1)],new float[j.faceCount(2)]};j.publish(next,published,faces(j));
		var mac=faces(j);j.apply(next,mac);
		for(int a=0;a<3;a++)for(int face=0;face<j.faceCount(a);face++) {
			if(published[a][face]!=(float)mac[a][face])throw new AssertionError("MAC publication differs from actual float markers");
			mac[a][face]=published[a][face];
		}
		for(double value:macFlux(l,mac))near(value,0,8e-8,"Published MAC continuity");
	}

	private static void closedPressureKernel(Layout l) {
		double[] p=new double[l.operator.rowCount()], result=new double[p.length];Arrays.fill(p,1e200);l.operator.apply(p,result);
		for(double value:result) near(value,0,1e-17,"Closed constant-pressure kernel");
		var force=markers(l.transfer);l.operator.force(p,force);for(double[] axis:force)for(double value:axis)near(value,0,1e-15,"Closed constant-pressure force");
	}

	private static void finiteGasStorage() {
		var l=layout(false); var j=l.transfer; int[][] first=new int[3][],second=new int[3][];var area=faces(j);
		for(int a=0;a<3;a++){first[a]=new int[j.faceCount(a)];second[a]=new int[j.faceCount(a)];Arrays.fill(first[a],-1);Arrays.fill(second[a],-1);}
		int face=l.domain.xFaceIndex(2,1,1);first[0][face]=0;second[0][face]=1;area[0][face]=.25;
		double dt=.001, compliance=2e-6, storage=compliance/(dt*dt), gasInitial=1500;
		var c=new MarkerPressureOperator(j,2,first,second,area,new double[]{0,storage});
		var v=markers(j);Arrays.fill(v[0],.2);double[] rate=new double[2];c.volumeRate(v,rate);
		double[] unit={1,0}, h=new double[2];c.apply(unit,h);double gram=h[0];
		double gasNew=gasInitial, liquidNew=gasNew-rate[0]/(dt*gram);
		var force=markers(j);c.force(new double[]{liquidNew,gasNew},force);
		for(int a=0;a<3;a++)for(int i=0;i<j.markerCount();i++)v[a][i]+=dt*force[a][i]/j.massKilograms(i);
		c.volumeRate(v,rate);near(rate[0],0,2e-16,"Liquid/gas shared flux");near(rate[1]+compliance*(gasNew-gasInitial)/dt,0,2e-16,"Finite gas storage sign");
		c.apply(new double[]{liquidNew,gasNew},h);near(h[0],-.05/dt,2e-12,"SI liquid RHS");near(h[1],.05/dt+storage*gasInitial,2e-12,"SI finite gas RHS");
		// Prescribed liquid pressure, finite gas: incoming liquid compresses the gas.
		first[0][face]=-1;second[0][face]=0;
		var gas=new MarkerPressureOperator(j,1,first,second,area,new double[]{storage});
		v=markers(j);Arrays.fill(v[0],.2);double[] gasRate=new double[1],response=new double[1];gas.volumeRate(v,gasRate);gas.apply(new double[]{1},response);
		double compressedPressure=-gasRate[0]/(dt*response[0]);
		if(!(compressedPressure>0))throw new AssertionError("Incoming liquid did not raise gas pressure");
		gas.force(new double[]{compressedPressure},force);
		for(int a=0;a<3;a++)for(int i=0;i<j.markerCount();i++)v[a][i]+=dt*force[a][i]/j.massKilograms(i);
		gas.volumeRate(v,gasRate);near(gasRate[0]+compliance*compressedPressure/dt,0,2e-16,"Gas compression storage equation");
		// Nonzero prescribed liquid pressure and capillary jump balance the initial gas pressure.
		var knownFaceLoad=faces(j);knownFaceLoad[0][face]=.25*(120-20);
		var knownForce=markers(j);j.transpose(knownFaceLoad,knownForce);v=markers(j);
		for(int a=0;a<3;a++)for(int i=0;i<j.markerCount();i++)v[a][i]=dt*knownForce[a][i]/j.massKilograms(i);
		gas.volumeRate(v,gasRate);gas.apply(new double[]{1},response);
		double balancedPressure=(-gasRate[0]/dt+storage*100)/response[0];near(balancedPressure,100,2e-13,"Known pressure and jump balance");
		gas.force(new double[]{balancedPressure},force);
		for(int a=0;a<3;a++)for(int i=0;i<j.markerCount();i++)near(v[a][i]+dt*force[a][i]/j.massKilograms(i),0,2e-16,"Balanced gas velocity");
	}

	private static void tinyMassPressureDiagonal() {
		var d=new FluidDomain(4,4,4,new FluidSolverConfig(.5F,1000,0,.001F,400,1e-5F,.45F,.002F,.8F),2048);
		d.restoreMaterialParticleState(new FlipMaterialParticleState(4,4,4,.5F,2,.25,125,
				java.util.List.of(new FluidMaterial("mmm:water",1000,"mmm:water",0,Math.PI/2),new FluidMaterial("mmm:tiny",8e-180,"mmm:tiny",0,Math.PI/2)),
				new float[]{.75F,.75F,.75F,.125F,.5F,.75F,.75F,.125F},new float[6],new byte[64],new double[]{125,1e-180},new int[]{0,1}));
		var j=new MarkerVelocityTransfer();j.capture(d);
		int[][] first=new int[3][],second=new int[3][];var area=faces(j);
		for(int a=0;a<3;a++){first[a]=new int[j.faceCount(a)];second[a]=new int[j.faceCount(a)];Arrays.fill(first[a],-1);Arrays.fill(second[a],-1);}
		int low=d.xFaceIndex(1,1,1),high=d.xFaceIndex(2,1,1);first[0][low]=0;second[0][high]=0;area[0][low]=area[0][high]=.25;
		var c=new MarkerPressureOperator(j,1,first,second,area,new double[]{0});
		double[] diagonal=new double[1], applied=new double[1];c.diagonal(diagonal);c.apply(new double[]{1},applied);
		if(!(diagonal[0]>0&&Double.isFinite(diagonal[0])&&Double.doubleToRawLongBits(diagonal[0])==Double.doubleToRawLongBits(applied[0])))throw new AssertionError("Tiny actual mass lost pressure rank");
		var force=markers(j);c.force(new double[]{1},force);near(force[0][0],0,0,"Major carrier cancellation");
		if(force[0][1]*force[0][1]!=0)throw new AssertionError("Tiny-mass fixture does not expose square-first underflow");
	}
}
