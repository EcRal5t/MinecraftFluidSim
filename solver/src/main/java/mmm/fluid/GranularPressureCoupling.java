package mmm.fluid;

import mmm.granular.GranularParcelStore;

/** Finite parcel inertia in the liquid/gas pressure system, using the occupancy gradient and its transpose. */
final class GranularPressureCoupling {
	private final FluidDomain domain;
	private int count;
	private int[] rows = new int[0];
	private double[] gx = new double[0], gy = new double[0], gz = new double[0];
	private double[] boundaryX = new double[0], boundaryY = new double[0], boundaryZ = new double[0];
	private double[] inverseMass = new double[0];
	private final float[] liquidDiagonal;
	private float[] gasDiagonal;
	private static final int SUPPORT = 8, PRESSURE_SUPPORT = SUPPORT * 8;
	private int[] pressureCounts = new int[0];
	private GranularParcelStore supportSource;
	private long supportRevision;
	private long geometryRevision;
	private float supportDt;
	private int[] supportCounts = new int[0], supportCells = new int[0];
	private int[] fluidFaces = new int[0], fluidCounts = new int[0];
	private double[] fluidWeights = new double[0], wallBuoyancy = new double[0], initialY = new double[0];
	private double wallWork;
	private double[] supportX = new double[0], supportY = new double[0], supportZ = new double[0];


	GranularPressureCoupling(FluidDomain domain) {
		this.domain = domain;
		liquidDiagonal = new float[domain.cellCount]; gasDiagonal = new float[domain.cellCount + 1];
	}

	/** Pressure uses the same finite-volume face partition as the liquid flux. */
	private void prepareSupport(float dt) {
		GranularParcelStore source = domain.granularState == null ? null : domain.granularState.parcels;
		count = source == null ? 0 : source.count();
		if (source == null) { supportSource = null; return; }
		long revision = source.mutationRevision();
		if (supportSource == source && supportRevision == revision && supportDt == dt
				&& geometryRevision == domain.pressureSystem.solidFaces.revision()) return;
		supportSource = null;
		ensureCapacity(count);
		int[] cells = new int[8]; double[] x = new double[8], y = new double[8], z = new double[8];
		for (int parcel = 0; parcel < count; parcel++) {
			int entries = domain.pressureSystem.solidFaces.gradient(parcel, cells, x, y, z), base = parcel * SUPPORT;
			for (int slot = 0; slot < entries; slot++) {
				supportCells[base + slot] = cells[slot]; supportX[base + slot] = x[slot]; supportY[base + slot] = y[slot]; supportZ[base + slot] = z[slot];
			}
			supportCounts[parcel] = entries;
		}
		supportRevision = revision;
		geometryRevision = domain.pressureSystem.solidFaces.revision();
		supportDt = dt;
		supportSource = source;
	}

	/** Geometry and pressure row identities stay fixed throughout PCG. Negative rows encode gas nodes. */
	void prepare(float dt) {
		prepareSupport(dt); wallWork = 0;
		int gasNodes = domain.gasState.network.nodeCount();
		domain.projection.ensureGasCapacity(gasNodes);
		if (gasDiagonal.length <= gasNodes) gasDiagonal = new float[gasNodes + 1];
		double h = domain.config.cellSize(), cellVolume = h * h * h;
		for (int parcel = 0; parcel < count; parcel++) {
			var p = domain.granularState.parcels.parcel(parcel);
			inverseMass[parcel] = cellVolume / p.mass();
			boundaryX[parcel] = boundaryY[parcel] = boundaryZ[parcel] = 0;
			initialY[parcel] = p.velocityY(); prepareFluidSamples(parcel, p);
			int base = parcel * PRESSURE_SUPPORT;
			pressureCounts[parcel] = 0;
			final int targetParcel=parcel;
			for (int slot=0;slot<supportCounts[parcel];slot++) {
				int support=parcel*SUPPORT+slot;
				domain.pressureSystem.faces.forEachCell(supportCells[support],(flat,weight,fixed) -> {
					double x=supportX[support]*weight,y=supportY[support]*weight,z=supportZ[support]*weight;
					boundaryX[targetParcel]+=x*fixed;boundaryY[targetParcel]+=y*fixed;boundaryZ[targetParcel]+=z*fixed;
					if(flat<0)return;
					int row=flat<domain.cellCount?flat:-(flat-domain.cellCount);
					int end=base+pressureCounts[targetParcel],index=base;
					while(index<end&&rows[index]!=row)index++;
					if(index==end) {
						if(pressureCounts[targetParcel]==PRESSURE_SUPPORT)throw new IllegalStateException("Parcel pressure face support exceeded its microface capacity");
						pressureCounts[targetParcel]++;rows[index]=row;gx[index]=gy[index]=gz[index]=0;
					}
					gx[index]+=x;gy[index]+=y;gz[index]+=z;
				});
			}
			wallBuoyancy[parcel] = clippedBuoyancy(p);
			boundaryY[parcel] += wallBuoyancy[parcel];
			double scale = dt * inverseMass[parcel];
			double vx = p.velocityX() - scale * boundaryX[parcel];
			double vy = p.velocityY() - scale * boundaryY[parcel];
			double vz = p.velocityZ() - scale * boundaryZ[parcel];
			for (int index = base; index < base + pressureCounts[parcel]; index++) {
				add(rows[index], domain.projection.rhs, domain.projection.gasRhs,
						domain.config.density() / dt * (gx[index] * vx + gy[index] * vy + gz[index] * vz));
			}
		}
	}



	private void prepareFluidSamples(int parcel, GranularParcelStore.Parcel p) {
		double h = domain.config.cellSize(); var graph = domain.pressureSystem.faces;
		for (int axis = 0; axis < 3; axis++) {
			double x = p.x()/h - (axis==0?0:.5), y = p.y()/h - (axis==1?0:.5), z = p.z()/h - (axis==2?0:.5);
			int x0=(int)Math.floor(x),y0=(int)Math.floor(y),z0=(int)Math.floor(z), base=parcel*24+axis*8, entries=0;
			int nx=domain.sizeX+(axis==0?1:0),ny=domain.sizeY+(axis==1?1:0),nz=domain.sizeZ+(axis==2?1:0); double sum=0;
			for(int oz=0;oz<2;oz++)for(int oy=0;oy<2;oy++)for(int ox=0;ox<2;ox++) {
				int cx=x0+ox,cy=y0+oy,cz=z0+oz;
				if(cx<0||cy<0||cz<0||cx>=nx||cy>=ny||cz>=nz)continue;
				int local=(cz*ny+cy)*nx+cx,face=graph.offsets[axis]+local;
				if(graph.firstCell[face]<0||!(graph.faceMass(face)>0))continue;
				double weight=(ox==0?1-x+x0:x-x0)*(oy==0?1-y+y0:y-y0)*(oz==0?1-z+z0:z-z0)
						*domain.pressureSystem.solidFaces.fixedPoreOpen(axis,local);
				if(!(weight>0))continue;fluidFaces[base+entries]=face;fluidWeights[base+entries++]=weight;sum+=weight;
			}
			fluidCounts[3*parcel+axis]=entries;
			for(int i=0;i<entries;i++)fluidWeights[base+i]/=sum;
		}
	}
	private double clippedBuoyancy(GranularParcelStore.Parcel p) {
		double h=domain.config.cellSize(), y=p.y()/h;
		if(y>=.5 && y<=domain.sizeY-.5)return 0;
		var sample=domain.granularMechanicalStage.sample(p.x(),p.y(),p.z());
		return -sample.density()*sample.submergedFraction()*domain.config.gravity()*Math.scalb((double)p.solidVolumeSvq(),-24);
	}
	interface FluidContribution { void accept(int face,int row,double value); }
	/** Cfluid=DfixedPore+G I; its transpose returns the parcel pressure impulse to liquid. */
	void forEachFluidContribution(FluidContribution contribution) {
		for(int p=0;p<count;p++)for(int axis=0;axis<3;axis++) {
			double boundary=axis==0?boundaryX[p]:axis==1?boundaryY[p]-wallBuoyancy[p]:boundaryZ[p];
			for(int slot=p*24+axis*8,end=slot+fluidCounts[3*p+axis];slot<end;slot++) {
				int face=fluidFaces[slot];double weight=fluidWeights[slot];
				if(boundary!=0)contribution.accept(face,-1,weight*boundary);
				for(int item=p*PRESSURE_SUPPORT;item<p*PRESSURE_SUPPORT+pressureCounts[p];item++) {
					double g=axis==0?gx[item]:axis==1?gy[item]:gz[item];if(g==0)continue;
					int row=rows[item];contribution.accept(face,row>=0?row:domain.cellCount-row,weight*g);
				}
			}
		}
	}
	void recordBoundary(float dt,double[] pressure) {
		wallWork=0;double h3=domain.unitScale.cubicMetersPerNormalizedVolume();
		for(int p=0;p<count;p++) {
			double force=-h3*wallBuoyancy[p];
			domain.lastGranularBoundaryImpulseY-=dt*force;
			wallWork+=dt*force*.5*(initialY[p]+domain.granularState.parcels.parcel(p).velocityY());
		}
	}
	double wallWork() { return wallWork; }

	void rebuildDiagonal() {
		rebuildDiagonal(parcel -> 1);
	}

	void rebuildDiagonal(java.util.function.IntToDoubleFunction inertiaRatio) {
		java.util.Arrays.fill(liquidDiagonal, 0); java.util.Arrays.fill(gasDiagonal, 0);
		for (int parcel = 0; parcel < count; parcel++) for (int index = parcel * PRESSURE_SUPPORT; index < parcel * PRESSURE_SUPPORT + pressureCounts[parcel]; index++) {
			double value = domain.config.density() * inverseMass[parcel] * inertiaRatio.applyAsDouble(parcel)
					* (gx[index] * gx[index] + gy[index] * gy[index] + gz[index] * gz[index]);
			int row = rows[index];
			if (row == Integer.MAX_VALUE) continue;
			if (row >= 0) liquidDiagonal[row] += (float) value;
			else gasDiagonal[-row] += (float) value;
		}
	}
	float liquidDiagonal(int cell) { return liquidDiagonal[cell]; }
	float gasDiagonal(int node) { return gasDiagonal[node]; }

	void apply(float[] source, float[] gasSource, float[] result, float[] gasResult) {
		for (int parcel = 0; parcel < count; parcel++) {
			double x = 0, y = 0, z = 0;
			for (int index = parcel * PRESSURE_SUPPORT; index < parcel * PRESSURE_SUPPORT + pressureCounts[parcel]; index++) {
				double pressure = value(rows[index], source, gasSource);
				x += gx[index] * pressure; y += gy[index] * pressure; z += gz[index] * pressure;
			}
			double scale = domain.config.density() * inverseMass[parcel];
			for (int index = parcel * PRESSURE_SUPPORT; index < parcel * PRESSURE_SUPPORT + pressureCounts[parcel]; index++) {
				add(rows[index], result, gasResult, scale * (gx[index] * x + gy[index] * y + gz[index] * z));
			}
		}
	}

	void apply(double[] source, double[] gasSource, double[] result, double[] gasResult) {
		for (int parcel = 0; parcel < count; parcel++) {
			double x = 0, y = 0, z = 0;
			for (int index = parcel * PRESSURE_SUPPORT; index < parcel * PRESSURE_SUPPORT + pressureCounts[parcel]; index++) {
				double pressure = value(rows[index], source, gasSource);
				x += gx[index] * pressure; y += gy[index] * pressure; z += gz[index] * pressure;
			}
			double scale = domain.config.density() * inverseMass[parcel];
			for (int index = parcel * PRESSURE_SUPPORT; index < parcel * PRESSURE_SUPPORT + pressureCounts[parcel]; index++) {
				add(rows[index], result, gasResult, scale * (gx[index] * x + gy[index] * y + gz[index] * z));
			}
		}
	}

	void project(float dt) {
		project(dt, null, null);
	}

	void project(float dt, double[] liquidPressure, double[] gasPressure) {
		for (int parcel = 0; parcel < count; parcel++) {
			var p = domain.granularState.parcels.parcel(parcel);
			double x = boundaryX[parcel], y = boundaryY[parcel], z = boundaryZ[parcel];
			for (int index = parcel * PRESSURE_SUPPORT; index < parcel * PRESSURE_SUPPORT + pressureCounts[parcel]; index++) {
				double pressure = liquidPressure == null
						? value(rows[index], domain.projection.pressure, domain.projection.gasPressure)
						: value(rows[index], liquidPressure, gasPressure);
				x += gx[index] * pressure; y += gy[index] * pressure; z += gz[index] * pressure;
			}
			double scale = dt * inverseMass[parcel];
			double vx = p.velocityX() - scale * x, vy = p.velocityY() - scale * y, vz = p.velocityZ() - scale * z;
			domain.granularState.parcels.overwrite(parcel, new GranularParcelStore.Parcel(p.x(), p.y(), p.z(),
					(float) vx, (float) vy, (float) vz, p.solidVolumeSvq(), p.massUnits(), p.materialHandle(),
					p.solidCompositionHandle(), p.contactAge(), p.flags()));
			domain.lastGranularBoundaryImpulseX += p.mass() * (vx - (float) vx);
			domain.lastGranularBoundaryImpulseY += p.mass() * (vy - (float) vy);
			domain.lastGranularBoundaryImpulseZ += p.mass() * (vz - (float) vz);
		}
		recordBoundary(dt, liquidPressure);
	}

	void addBoundaryVelocity(double[] vector, int offset, double dt) {
		for (int parcel = 0; parcel < count; parcel++) {
			vector[offset + 3 * parcel] -= dt * inverseMass[parcel] * boundaryX[parcel];
			vector[offset + 3 * parcel + 1] -= dt * inverseMass[parcel] * boundaryY[parcel];
			vector[offset + 3 * parcel + 2] -= dt * inverseMass[parcel] * boundaryZ[parcel];
		}
	}

	void addPressureVelocity(double[] vector, int offset, double[] pressure) {
		for (int parcel = 0; parcel < count; parcel++) for (int index = parcel * PRESSURE_SUPPORT; index < parcel * PRESSURE_SUPPORT + pressureCounts[parcel]; index++) {
			int row = rows[index];
			if (row == Integer.MAX_VALUE) continue;
			double scale = inverseMass[parcel] * pressure[row >= 0 ? row : domain.cellCount - row];
			vector[offset + 3 * parcel] -= scale * gx[index];
			vector[offset + 3 * parcel + 1] -= scale * gy[index];
			vector[offset + 3 * parcel + 2] -= scale * gz[index];
		}
	}

	void addVelocityDivergence(double[] vector, int offset, double[] result, double scale) {
		for (int parcel = 0; parcel < count; parcel++) for (int index = parcel * PRESSURE_SUPPORT; index < parcel * PRESSURE_SUPPORT + pressureCounts[parcel]; index++) {
			int row = rows[index];
			if (row == Integer.MAX_VALUE) continue;
			result[row >= 0 ? row : domain.cellCount - row] -= scale * (gx[index] * vector[offset + 3 * parcel]
					+ gy[index] * vector[offset + 3 * parcel + 1] + gz[index] * vector[offset + 3 * parcel + 2]);
		}
	}

	private static double value(int row, float[] liquid, float[] gas) {
		return row == Integer.MAX_VALUE ? 0 : row >= 0 ? liquid[row] : gas[-row];
	}
	private static void add(int row, float[] liquid, float[] gas, double value) {
		if (row == Integer.MAX_VALUE) return;
		if (row >= 0) liquid[row] += (float) value; else gas[-row] += (float) value;
	}

	private static double value(int row, double[] liquid, double[] gas) {
		return row == Integer.MAX_VALUE ? 0 : row >= 0 ? liquid[row] : gas[-row];
	}
	private static void add(int row, double[] liquid, double[] gas, double value) {
		if (row == Integer.MAX_VALUE) return;
		if (row >= 0) liquid[row] += value; else gas[-row] += value;
	}
	private void ensureCapacity(int requested) {
		if (inverseMass.length >= requested) return;
		rows = new int[requested * PRESSURE_SUPPORT]; gx = new double[requested * PRESSURE_SUPPORT];
		gy = new double[requested * PRESSURE_SUPPORT]; gz = new double[requested * PRESSURE_SUPPORT];
		inverseMass = new double[requested]; boundaryX = new double[requested];
		boundaryY = new double[requested]; boundaryZ = new double[requested];
		pressureCounts = new int[requested];supportCounts = new int[requested]; supportCells = new int[requested * SUPPORT];
		fluidFaces = new int[requested * 24]; fluidWeights = new double[fluidFaces.length]; fluidCounts = new int[requested * 3];
		wallBuoyancy = new double[requested]; initialY = new double[requested];
		supportX = new double[requested * SUPPORT]; supportY = new double[requested * SUPPORT];
		supportZ = new double[requested * SUPPORT];
	}
}
