package mmm.fluid;

import mmm.granular.GranularCouplingFields;
import mmm.inventory.PhaseOccupancyState;
import mmm.units.SignedDyadic;

/** One coarse PLIC per fixed open component, shared by transport and gas geometry. */
final class FlipPressurePhaseGeometry {
	static final double CELL_VOLUME_RESOLUTION = PhaseOccupancyState.VOLUME_RESOLUTION;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final double[] fraction;
	private final float[] normalX;
	private final float[] normalY;
	private final float[] normalZ;
	private final double[] planeOffset;
	private final PlicPlaneOffset.Result[] offsetBrackets;
	private final SignedDyadic[] poreVolumes;
	private final PlicComponentGeometry[] components;
	private final Object[] cellShapeIdentity;
	private byte[] collisionMasks;
	private long revision;
	private double[] microFraction, microPoreScale, microOffset;
	private float[] microNormalX, microNormalY, microNormalZ;
	private PlicPlaneOffset.Result[] microBracket;
	private PhaseOccupancyState microOccupancy;
	private final double[] faceX = new double[16], faceY = new double[16];
	private final double[] clippedX = new double[16], clippedY = new double[16];

	FlipPressurePhaseGeometry(int sizeX, int sizeY, int sizeZ) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) {
			throw new IllegalArgumentException("Pressure phase grid is too small");
		}
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		int cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.fraction = new double[cellCount];
		this.normalX = new float[cellCount];
		this.normalY = new float[cellCount];
		this.normalZ = new float[cellCount];
		this.planeOffset = new double[cellCount];
		this.offsetBrackets = new PlicPlaneOffset.Result[cellCount];
		this.poreVolumes = new SignedDyadic[cellCount];
		java.util.Arrays.fill(poreVolumes,SignedDyadic.ZERO);
		this.components = new PlicComponentGeometry[cellCount];
		this.cellShapeIdentity = new Object[cellCount];
		this.collisionMasks = new byte[cellCount];
	}

	boolean matchesShape(int x, int y, int z) { return sizeX == x && sizeY == y && sizeZ == z; }
	long revision() { return revision; }
	int collisionMask(int cell) { return Byte.toUnsignedInt(collisionMasks[cell]); }
	SignedDyadic poreVolume(int cell) { return poreVolumes[cell]; }
	boolean hasMicroOccupation() { return microFraction!=null; }
	void setExactPoreVolumes(FlipCutCellGeometry cut,long[] occupied) {
		for(int cell=0;cell<fraction.length;cell++)poreVolumes[cell]=SignedDyadic.of(Integer.bitCount(~cut.mask(cell)&255),-3).subtract(SignedDyadic.of(occupied[cell],-24));
	}

	/** The persistent phase occupation supplies both transport planes and gas contact geometry. */
	void rebuildOccupancy(PhaseOccupancyState state,FlipCutCellGeometry cut,long[] occupied) {
        double[] pore=new double[8*fraction.length];var exact=new SignedDyadic[fraction.length];
        for(int cell=0;cell<fraction.length;cell++){
            int mask=cut.mask(cell);exact[cell]=SignedDyadic.of(Integer.bitCount(~mask&255),-3).subtract(SignedDyadic.of(occupied[cell],-24));
            var caps=CellRemapMicroTopology.capacities(mask,exact[cell]);int j=0;
            for(int support:CellRemapMicroTopology.components(mask)){double amount=caps[j++].toDouble()/Integer.bitCount(support);for(int bit=0;bit<8;bit++)if((support&1<<bit)!=0)pore[8*cell+bit]=amount;}
        }
        rebuildOccupancy(state,cut,pore);System.arraycopy(exact,0,poreVolumes,0,exact.length);
    }
    void rebuildOccupancy(PhaseOccupancyState state,FlipCutCellGeometry cut,double[] pore) {
		rebuildOccupancy(state,cut,pore,null);
	}
	/** Identical local inventory and six-neighbour inputs share the same immutable PLIC plane. */
	void rebuildOccupancy(PhaseOccupancyState state,FlipCutCellGeometry cut,double[] pore,FlipPressurePhaseGeometry captured) {
		if(captured==this)throw new IllegalArgumentException("Occupancy reconstruction requires a distinct source");
        int count=8*fraction.length;
        if(microFraction==null||microFraction.length!=count){microFraction=new double[count];microPoreScale=new double[count];microOffset=new double[count];microNormalX=new float[count];microNormalY=new float[count];microNormalZ=new float[count];microBracket=new PlicPlaneOffset.Result[count];}
        java.util.Arrays.fill(microOffset,0);java.util.Arrays.fill(microNormalX,0);java.util.Arrays.fill(microNormalY,0);java.util.Arrays.fill(microNormalZ,0);java.util.Arrays.fill(microBracket,null);
        collisionMasks=cut.copyMicroMasks();java.util.Arrays.fill(components,null);java.util.Arrays.fill(offsetBrackets,null);
        for(int cell=0;cell<fraction.length;cell++){
            double total=0,liquid=0;for(int bit=0;bit<8;bit++){int piece=8*cell+bit;double capacity=pore[piece],amount=state.liquidVolume(piece);total+=capacity;liquid+=amount;microPoreScale[piece]=8*capacity;microFraction[piece]=0;}
            poreVolumes[cell]=SignedDyadic.of(total);fraction[cell]=total>0?Math.clamp(liquid/total,0,1):0;normalX[cell]=normalY[cell]=normalZ[cell]=0;planeOffset[cell]=0;
        }
		// Identity is compared only at the same cell; one token names this reconstruction batch.
		Object rebuiltShape=new Object();
		for(int cell=0;cell<fraction.length;cell++){
			int x=cell%sizeX,y=cell/sizeX%sizeY,z=cell/(sizeX*sizeY);double center=fraction[cell];
			if(captured!=null&&sameOccupancyInputs(cell,x,y,z,state,pore,captured)){
				copyCell(captured,cell);
				cellShapeIdentity[cell]=captured.cellShapeIdentity[cell];
				int p=8*cell;
				System.arraycopy(captured.microFraction,p,microFraction,p,8);
				System.arraycopy(captured.microNormalX,p,microNormalX,p,8);
				System.arraycopy(captured.microNormalY,p,microNormalY,p,8);
				System.arraycopy(captured.microNormalZ,p,microNormalZ,p,8);
				System.arraycopy(captured.microOffset,p,microOffset,p,8);
				System.arraycopy(captured.microBracket,p,microBracket,p,8);
				continue;
			}
			cellShapeIdentity[cell]=rebuiltShape;
			double nx=coarseSample(x-1,y,z,center)-coarseSample(x+1,y,z,center);
			double ny=coarseSample(x,y-1,z,center)-coarseSample(x,y+1,z,center);
			double nz=coarseSample(x,y,z-1,center)-coarseSample(x,y,z+1,center);
			double length=Math.sqrt(nx*nx+ny*ny+nz*nz);if(length==0){ny=1;length=1;}
			float fx=(float)(nx/length),fy=(float)(ny/length),fz=(float)(nz/length);
			for(int support:CellRemapMicroTopology.components(cut.mask(cell))){
				double amount=0,capacity=0;for(int bit=0;bit<8;bit++)if((support&1<<bit)!=0){int p=8*cell+bit;amount+=state.liquidVolume(p);capacity+=pore[p];}
				double value=capacity>0?Math.clamp(amount/capacity,0,1):0;
				if(value==0||value==1){for(int bit=0;bit<8;bit++)if((support&1<<bit)!=0)microFraction[8*cell+bit]=value;continue;}
				boolean unresolved = Math.min(amount, capacity - amount) <= CELL_VOLUME_RESOLUTION;
				float px = unresolved ? 0 : fx, py = unresolved ? 1 : fy, pz = unresolved ? 0 : fz;
				double target = value * Integer.bitCount(support) * .125;
				var plane = unresolved ? horizontalLayer(support, target) : PlicPlaneOffset.solve(support,px,py,pz,target);
				double offset=plane.offset();normalX[cell]=px;normalY[cell]=py;normalZ[cell]=pz;planeOffset[cell]=offset;offsetBrackets[cell]=plane;
				for(int bit=0;bit<8;bit++)if((support&1<<bit)!=0){
					int p=8*cell+bit;microNormalX[p]=px;microNormalY[p]=py;microNormalZ[p]=pz;microOffset[p]=offset;microBracket[p]=plane;
					microFraction[p]=unresolved ? Math.clamp(2 * offset - (bit >>> 1 & 1), 0, 1)
							: FlipFreeSurfaceGeometry.unitCubeLiquidFraction(.5*px,.5*py,.5*pz,offset-.5*(px*(bit&1)+py*(bit>>>1&1)+pz*(bit>>>2&1)));
				}
			}
		}
		microOccupancy=state;revision=Math.addExact(revision,1);
	}
	private boolean sameOccupancyInputs(int cell,int x,int y,int z,PhaseOccupancyState state,double[] pore,FlipPressurePhaseGeometry old){
		if(old.microOccupancy==null||collisionMask(cell)!=old.collisionMask(cell)||fraction[cell]!=old.fraction[cell])return false;
		for(int bit=0;bit<8;bit++)if(8*pore[8*cell+bit]!=old.microPoreScale[8*cell+bit])return false;
		for(int support:CellRemapMicroTopology.components(collisionMask(cell))){
			double current=0,previous=0;
			for(int bits=support;bits!=0;bits&=bits-1){int p=8*cell+Integer.numberOfTrailingZeros(bits);current+=state.liquidVolume(p);previous+=old.microOccupancy.liquidVolume(p);}
			if(current!=previous)return false;
		}
		double center=fraction[cell];
		if(center==0||center==1)return true;
		return coarseSample(x-1,y,z,center)==old.coarseSample(x-1,y,z,center)
				&&coarseSample(x+1,y,z,center)==old.coarseSample(x+1,y,z,center)
				&&coarseSample(x,y-1,z,center)==old.coarseSample(x,y-1,z,center)
				&&coarseSample(x,y+1,z,center)==old.coarseSample(x,y+1,z,center)
				&&coarseSample(x,y,z-1,center)==old.coarseSample(x,y,z-1,center)
				&&coarseSample(x,y,z+1,center)==old.coarseSample(x,y,z+1,center);
	}
	/** Unresolved small phases use a volume-preserving layer in their real fixed support. */
	private static PlicPlaneOffset.Result horizontalLayer(int support, double target) {
		double lower = Integer.bitCount(support & 0x33) * .125;
		double upper = Integer.bitCount(support & 0xcc) * .125;
		double offset = lower > 0 && target <= lower ? .5 * target / lower
				: .5 + .5 * (target - lower) / upper;
		return new PlicPlaneOffset.Result(offset, target);
	}
	private double coarseSample(int x,int y,int z,double fallback) {
		if(!inside(x,y,z))return fallback;
		int cell=cellIndex(x,y,z);return poreVolumes[cell].signum()>0?fraction[cell]:fallback;
	}
	void copyLiquidMicroVolumes(double[] target){
		for(int p=0;p<microFraction.length;p++)target[p]=microFraction[p]*microPoreScale[p]*.125;
	}

	/** Exact inputs read by dry-piece and microface construction, including absent gas pieces. */
	boolean sameCellGeometry(int cell, FlipPressurePhaseGeometry other) {
		if (collisionMask(cell) != other.collisionMask(cell) || !poreVolumes[cell].equals(other.poreVolumes[cell])) return false;
		if(cellShapeIdentity[cell]!=null&&cellShapeIdentity[cell]==other.cellShapeIdentity[cell])return true;
		for (int micro = 0; micro < 8; micro++) {
			if (Double.doubleToRawLongBits(liquidFractionExact(cell, micro)) != Double.doubleToRawLongBits(other.liquidFractionExact(cell, micro))
					|| Double.doubleToRawLongBits(planeOffset(cell, micro)) != Double.doubleToRawLongBits(other.planeOffset(cell, micro))
					|| !java.util.Objects.equals(offsetBracket(cell, micro), other.offsetBracket(cell, micro))) return false;
			for (int axis = 0; axis < 3; axis++) if (Float.floatToRawIntBits(planeNormal(cell, micro, axis))
					!= Float.floatToRawIntBits(other.planeNormal(cell, micro, axis))) return false;
		}
		return true;
	}

	/** A partial single-component PLIC reads the six neighbouring volume fractions. */
	boolean dependsOnNeighborFractions(int cell, PhaseLiquidGeometryEdit stock, float cellSize) {
		return fraction[cell] > 0 && fraction[cell] < 1;
	}

	/** Scalar-volume geometry entry for isolated geometric validation. */
	void rebuild(boolean[] solid, double[] volume, FlipCutCellGeometry cut,
	             GranularCouplingFields granular, boolean[] pressureFluid) {
		rebuildLiquidEdits(solid, volume, cut, granular, pressureFluid, 1,
				java.util.List.of(), null, null);
	}

	/** A captured frame supplies its read-only planes and the matching complete local stock. */
	void rebuildLiquidEdits(
			boolean[] solid, double[] reconstructedVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular, boolean[] pressureFluid,
			float cellSize, java.util.List<PhaseLiquidGeometryEdit> edits,
			FlipPressurePhaseGeometry captured, boolean[] sameLocalStock
	) {
		if (solid.length != fraction.length || reconstructedVolume.length != fraction.length
				|| pressureFluid.length != fraction.length || edits == null || !(cellSize > 0) || !Float.isFinite(cellSize)) {
			throw new IllegalArgumentException("Pressure phase arrays disagree with the cell count");
		}
		if (captured != null && (captured == this || !captured.matchesShape(sizeX, sizeY, sizeZ)
				|| sameLocalStock == null || sameLocalStock.length != fraction.length))
			throw new IllegalArgumentException("Captured PLIC requires a distinct matching frame and local stock checks");
		microFraction=microPoreScale=microOffset=null;microNormalX=microNormalY=microNormalZ=null;microBracket=null;
		microOccupancy=null;
		java.util.Arrays.fill(cellShapeIdentity,null);
		PhaseLiquidGeometryEdit[] overlay = edits.isEmpty() ? null : new PhaseLiquidGeometryEdit[fraction.length];
		for (var edit : edits) {
			if (edit == null || edit.cell() >= fraction.length || overlay[edit.cell()] != null
					|| edit.collisionMask() != cutCells.mask(edit.cell()))
				throw new IllegalArgumentException("Repeated PLIC liquid edit or mismatched collision mask");
			overlay[edit.cell()] = edit;
		}
		revision = Math.addExact(revision,1);
		collisionMasks = cutCells.copyMicroMasks();
		java.util.Arrays.fill(components,null);
		java.util.Arrays.fill(offsetBrackets,null);
		for (int cell = 0; cell < fraction.length; cell++) {
			double geometricOpen = cutCells.openCellVolume(cell);
			double openVolume = geometricOpen - (granular == null ? 0 : Math.scalb((double) granular.solidSvq(cell), -24));
			poreVolumes[cell] = solid[cell] ? SignedDyadic.ZERO : SignedDyadic.of(openVolume);
			double liquid = overlay != null && overlay[cell] != null ? overlay[cell].volume().toDouble() : reconstructedVolume[cell];
			fraction[cell] = solid[cell] || openVolume <= 0.0F ? 0.0F : clamp(liquid / openVolume, 0.0, 1.0);
			if (overlay != null && overlay[cell] != null && overlay[cell].volume().signum() > 0
					&& overlay[cell].volume().compareTo(poreVolumes[cell]) < 0)
				fraction[cell] = Math.clamp(fraction[cell],Double.MIN_VALUE,Math.nextDown(1.0));
			pressureFluid[cell] = false;
			normalX[cell] = normalY[cell] = normalZ[cell] = 0.0F; planeOffset[cell] = 0;
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int cell = cellIndex(x, y, z);
			PhaseLiquidGeometryEdit edit = overlay == null ? null : overlay[cell];
			if (solid[cell] || !(fraction[cell] > 0.0F) || fraction[cell] >= 1) continue;
			if (captured != null && sameLocalStock[cell]
					&& canReusePlane(captured, cell, x, y, z, solid, cutCells, edit, cellSize)) {
				copyCell(captured, cell);
				continue;
			}
			solveCell(cell, x, y, z, solid, cutCells, cellSize, edit);
		}
		for (int cell = 0; cell < fraction.length; cell++) {
			if (solid[cell] || !(fraction[cell] > 0.5F)) continue;
			if (fraction[cell] >= 1) pressureFluid[cell] = true;
			else pressureFluid[cell] = planeOffset[cell]
					> 0.5 * ((double) normalX[cell] + normalY[cell] + normalZ[cell]);
		}
	}


	private void solveCell(int cell, int x, int y, int z, boolean[] solid, FlipCutCellGeometry cutCells,
	                       float cellSize,
	                       PhaseLiquidGeometryEdit edit) {
		double center = fraction[cell];
		float nx = (float) -(sampleFraction(x + 1, y, z, center, solid) - sampleFraction(x - 1, y, z, center, solid));
		float ny = (float) -(sampleFraction(x, y + 1, z, center, solid) - sampleFraction(x, y - 1, z, center, solid));
		float nz = (float) -(sampleFraction(x, y, z + 1, center, solid) - sampleFraction(x, y, z - 1, center, solid));
		float gradientX = nx, gradientY = ny, gradientZ = nz;
		double lengthSquared = (double) nx * nx + (double) ny * ny + (double) nz * nz;
		if (lengthSquared == 0) {
			nx = airDirection(x + 1, y, z, solid) - airDirection(x - 1, y, z, solid);
			ny = airDirection(x, y + 1, z, solid) - airDirection(x, y - 1, z, solid);
			nz = airDirection(x, y, z + 1, solid) - airDirection(x, y, z - 1, solid);
			lengthSquared = (double) nx * nx + (double) ny * ny + (double) nz * nz;
		}
		if (lengthSquared == 0) ny = 1.0F;
		else {
			double inverseLength = 1.0 / Math.sqrt(lengthSquared);
			nx = (float) (nx * inverseLength); ny = (float) (ny * inverseLength); nz = (float) (nz * inverseLength);
		}
		normalX[cell] = nx; normalY[cell] = ny; normalZ[cell] = nz;
		var stock = edit;
		SignedDyadic open = poreVolumes[cell];
		if (stock == null) planeOffset[cell] = PlicPlaneOffset.solve(~cutCells.mask(cell)&255,nx,ny,nz,center * cutCells.openCellVolume(cell)).offset();
		else {
			components[cell] = PlicComponentGeometry.capture(stock, CellRemapMicroTopology.capacities(cutCells.mask(cell), open),
					x, y, z, cellSize, nx, ny, nz);
			if (stock.components().size() == 1) {
				int micro = Integer.numberOfTrailingZeros(stock.components().getFirst().supportMask());
				var plane = components[cell].plane(micro);
				normalX[cell]=plane.nx();normalY[cell]=plane.ny();normalZ[cell]=plane.nz();
				planeOffset[cell]=plane.offset();offsetBrackets[cell]=plane.bracket();
			}
		}
	}

	private void copyCell(FlipPressurePhaseGeometry captured, int cell) {
		normalX[cell] = captured.normalX[cell]; normalY[cell] = captured.normalY[cell]; normalZ[cell] = captured.normalZ[cell];
		planeOffset[cell] = captured.planeOffset[cell]; offsetBrackets[cell] = captured.offsetBrackets[cell];
		components[cell] = captured.components[cell];
	}

	/** Scalar arrays belong to this copy; immutable component certificates can be shared. */
	private FlipPressurePhaseGeometry(FlipPressurePhaseGeometry source) {
		sizeX = source.sizeX; sizeY = source.sizeY; sizeZ = source.sizeZ;
		fraction = source.fraction.clone();
		normalX = source.normalX.clone(); normalY = source.normalY.clone(); normalZ = source.normalZ.clone();
		planeOffset = source.planeOffset.clone(); offsetBrackets = source.offsetBrackets.clone();
		poreVolumes = source.poreVolumes.clone(); components = source.components.clone();
		cellShapeIdentity=source.cellShapeIdentity.clone();
		collisionMasks = source.collisionMasks.clone();
		if(source.microFraction!=null){microFraction=source.microFraction.clone();microPoreScale=source.microPoreScale.clone();
			microOffset=source.microOffset.clone();microNormalX=source.microNormalX.clone();microNormalY=source.microNormalY.clone();
			microNormalZ=source.microNormalZ.clone();microBracket=source.microBracket.clone();}
		revision = source.revision;
		microOccupancy=source.microOccupancy;
	}
	FlipPressurePhaseGeometry copy() { return new FlipPressurePhaseGeometry(this); }


	void rebuildEditedCells(FlipPressurePhaseGeometry captured, PhaseLiquidGeometryEdit edit,
	                         int[] cells, java.util.List<PhaseLiquidGeometryEdit> stock,
	                         FlipCutCellGeometry cut, boolean[] solid, float cellSize) {

		revision = Math.addExact(revision, 1);
		cellShapeIdentity[edit.cell()]=null;
		double open = poreVolumes[edit.cell()].toDouble();
		fraction[edit.cell()] = solid[edit.cell()] || open <= 0 ? 0 : clamp(edit.volume().toDouble() / open, 0, 1);
		for (int cell : cells) {
			cellShapeIdentity[cell]=null;
			int x = cell % sizeX, y = cell / sizeX % sizeY, z = cell / (sizeX * sizeY);
			normalX[cell] = normalY[cell] = normalZ[cell] = 0; planeOffset[cell] = 0;
			offsetBrackets[cell] = null; components[cell] = null;
			if (solid[cell] || !(fraction[cell] > 0) || fraction[cell] >= 1) continue;
			if (cell != edit.cell() && canReusePlane(captured, cell, x, y, z, solid, cut, stock.get(cell), cellSize)) copyCell(captured, cell);
			else solveCell(cell, x, y, z, solid, cut, cellSize, cell == edit.cell() ? edit : stock.get(cell));
		}
	}

	private boolean canReusePlane(FlipPressurePhaseGeometry captured, int cell, int x, int y, int z, boolean[] solid,
	                            FlipCutCellGeometry cut, PhaseLiquidGeometryEdit stock, float cellSize) {
		return collisionMask(cell) == captured.collisionMask(cell) && poreVolumes[cell].equals(captured.poreVolumes[cell])
				&& Double.doubleToRawLongBits(fraction[cell]) == Double.doubleToRawLongBits(captured.fraction[cell])
				&& (sameNeighbor(captured, x + 1, y, z, solid) && sameNeighbor(captured, x - 1, y, z, solid)
				&& sameNeighbor(captured, x, y + 1, z, solid) && sameNeighbor(captured, x, y - 1, z, solid)
				&& sameNeighbor(captured, x, y, z + 1, solid) && sameNeighbor(captured, x, y, z - 1, solid));
	}

	private boolean sameNeighbor(FlipPressurePhaseGeometry captured, int x, int y, int z, boolean[] solid) {
		if (!inside(x, y, z)) return true;
		int cell = cellIndex(x, y, z);
		return solid[cell] == (captured.collisionMask(cell) == 255) && (solid[cell]
				|| Double.doubleToRawLongBits(fraction[cell]) == Double.doubleToRawLongBits(captured.fraction[cell]));
	}

	static double ownMomentDirection(double first, double metricVolume, int coordinate, int support, int axis) {
		int low = 0;
		for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) low += 1 - (micro >>> axis & 1);
		return momentDirection(centeredMoment(first, metricVolume, coordinate), low, Integer.bitCount(support));
	}

	static double centeredMoment(double first, double metricVolume, int coordinate) {
		return Math.fma(-(coordinate + .5), metricVolume, first) / metricVolume;
	}

	static double momentDirection(double centre, int lowCount, int openCount) {
		double lower = lowCount == 0 ? 0 : -.5, upper = lowCount == openCount ? 0 : .5;
		double openCentre = .25 * (openCount - 2.0 * lowCount) / openCount;
		if (centre <= lower) return Double.POSITIVE_INFINITY;
		if (centre >= upper) return Double.NEGATIVE_INFINITY;
		return (openCentre - centre) / ((upper - centre) * (centre - lower));
	}

	float liquidFraction(int cell) { return (float) fraction[cell]; }
	float planeNormal(int cell, int axis) { return axis == 0 ? normalX[cell] : axis == 1 ? normalY[cell] : normalZ[cell]; }
	double planeOffset(int cell) { return planeOffset[cell]; }
	boolean hasComponentPlanes(int cell) { return microFraction!=null || components[cell] != null; }
	float planeNormal(int cell, int micro, int axis) {
		if(microFraction!=null){int piece=8*cell+micro;return axis==0?microNormalX[piece]:axis==1?microNormalY[piece]:microNormalZ[piece];}
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? planeNormal(cell,axis) : plane == null ? 0 : plane.normal(axis);
	}
	double planeOffset(int cell, int micro) {
		if(microFraction!=null)return microOffset[8*cell+micro];
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? planeOffset[cell] : plane == null ? 0 : plane.offset();
	}
	PlicPlaneOffset.Result offsetBracket(int cell,int micro) {
		if(microFraction!=null)return microBracket[8*cell+micro];
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? offsetBrackets[cell] : plane == null ? null : plane.bracket();
	}
	double liquidFractionExact(int cell, int micro) {
		if(microFraction!=null)return microFraction[8*cell+micro];
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? fraction[cell] : plane == null ? 0 : plane.fraction();
	}
	double componentPoreScale(int cell,int micro,double fallback) {
		if(microFraction!=null)return microPoreScale[8*cell+micro];
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? fallback : plane == null ? 0 : plane.porosity();
	}

	/** One-sided liquid contact area on a cell face. */
	double liquidFaceArea(int cell, int axis, int side, FlipCutCellGeometry cut) {
		double area=0;
		for(int a=0;a<2;a++)for(int b=0;b<2;b++){
			area+=liquidPortArea(cell,axis,side,a,b,cut);
		}
		return area;
	}
	double liquidPortArea(int cell,int axis,int side,int a,int b,FlipCutCellGeometry cut){
		double x=.5*a,y=.5*b;int micro=microAtFace(cell,axis,side,x,y);
		if((cut.mask(cell)&1<<micro)!=0||liquidFractionExact(cell,micro)<=0)return 0;
		if(liquidFractionExact(cell,micro)>=1)return .25;
		rectangle(x,y,.5);
		int count=clipFace(4,
				planeNormal(cell,micro,(axis+1)%3),planeNormal(cell,micro,(axis+2)%3),
				planeOffset(cell,micro)-side*planeNormal(cell,micro,axis));
		double twice=0;
		for(int i=1;i+1<count;i++)twice+=(faceX[i]-faceX[0])*(faceY[i+1]-faceY[0])-(faceY[i]-faceY[0])*(faceX[i+1]-faceX[0]);
		return .5*Math.abs(twice);
	}
	double sharedLiquidPortArea(int left,int right,int axis,int a,int b,FlipCutCellGeometry cut){
		int minus=microAtFace(left,axis,1,.5*a,.5*b),plus=microAtFace(right,axis,0,.5*a,.5*b);
		return (cut.mask(left)&1<<minus)!=0||(cut.mask(right)&1<<plus)!=0?0:wetRectangle(left,right,axis,.5*a,.5*b,.5);
	}

	double sharedLiquidFaceArea(int left, int right, int axis, FlipCutCellGeometry cut) {
		if (!(fraction[left] > 0) || !(fraction[right] > 0)) return 0;
		if (microFraction==null && cut.mask(left) == 0 && cut.mask(right) == 0) return wetRectangle(left, right, axis, 0, 0, 1);
		double area = 0;
		for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
			boolean open = switch (axis) {
				case 0 -> cut.microCellOpen(left, 1, a, b) && cut.microCellOpen(right, 0, a, b);
				case 1 -> cut.microCellOpen(left, b, 1, a) && cut.microCellOpen(right, b, 0, a);
				default -> cut.microCellOpen(left, a, b, 1) && cut.microCellOpen(right, a, b, 0);
			};
			if (open) area += wetRectangle(left, right, axis, .5 * a, .5 * b, .5);
		}
		return area;
	}

	private double wetRectangle(int left, int right, int axis, double x, double y, double span) {

		int minus = microAtFace(left,axis,1,x,y), plus = microAtFace(right,axis,0,x,y);
		if (liquidFractionExact(left,minus) == 0 || liquidFractionExact(right,plus) == 0) return 0;
		faceX[0] = faceX[3] = x; faceX[1] = faceX[2] = x + span;
		faceY[0] = faceY[1] = y; faceY[2] = faceY[3] = y + span;
		int tangent = (axis + 1) % 3, other = (axis + 2) % 3;
		int count = liquidFractionExact(left,minus) >= 1 ? 4 : clipFace(4, planeNormal(left,minus,tangent), planeNormal(left,minus,other),
				planeOffset(left,minus) - planeNormal(left,minus,axis));
		if (liquidFractionExact(right,plus) < 1) count = clipFace(count, planeNormal(right,plus,tangent), planeNormal(right,plus,other), planeOffset(right,plus));
		double twiceArea = 0;
		for (int i = 1; i + 1 < count; i++) twiceArea += (faceX[i] - faceX[0]) * (faceY[i + 1] - faceY[0])
				- (faceY[i] - faceY[0]) * (faceX[i + 1] - faceX[0]);
		return .5 * Math.abs(twiceArea);
	}

	private int clipFace(int count, double nx, double ny, double offset) {
		if (count == 0) return 0;
		int next = 0, previous = count - 1;
		double previousDistance = nx * faceX[previous] + ny * faceY[previous] - offset;
		for (int current = 0; current < count; current++) {
			double distance = nx * faceX[current] + ny * faceY[current] - offset;
			if ((distance <= 0) != (previousDistance <= 0)) {
				double t = previousDistance / (previousDistance - distance);
				clippedX[next] = faceX[previous] + t * (faceX[current] - faceX[previous]);
				clippedY[next++] = faceY[previous] + t * (faceY[current] - faceY[previous]);
			}
			if (distance <= 0) { clippedX[next] = faceX[current]; clippedY[next++] = faceY[current]; }
			previous = current; previousDistance = distance;
		}
		System.arraycopy(clippedX, 0, faceX, 0, next); System.arraycopy(clippedY, 0, faceY, 0, next);
		return next;
	}

	double liquidFractionExact(int cell) { return fraction[cell]; }
	int microAtFace(int cell,int axis,double coordinate,double tangent,double other) {
		int base = (tangent >= .5 ? 1 : 0) << ((axis+1)%3) | (other >= .5 ? 1 : 0) << ((axis+2)%3);
		if (coordinate >= 1) return base | 1 << axis;
		if (coordinate <= 0) return base;
		int mask = Byte.toUnsignedInt(collisionMasks[cell]);
		return (mask & 1 << base) == 0 ? base : base | 1 << axis;
	}
	private void rectangle(double x,double y,double span) {
		faceX[0]=faceX[3]=x;faceX[1]=faceX[2]=x+span;
		faceY[0]=faceY[1]=y;faceY[2]=faceY[3]=y+span;
	}

	float interfaceTheta(int fluid, int directionX, int directionY, int directionZ) {
		double distance;
		if (fraction[fluid] >= 1) distance = 0.5;
		else {
			float outward = normalX[fluid] * directionX + normalY[fluid] * directionY
					+ normalZ[fluid] * directionZ;
			if (!(outward > 0.0F)) return 1.0F;
			double centerProjection = 0.5 * ((double) normalX[fluid] + normalY[fluid] + normalZ[fluid]);
			distance = (planeOffset[fluid] - centerProjection) / outward;
		}
		int x = fluid % sizeX;
		int yz = fluid / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		int nx = x + directionX, ny = y + directionY, nz = z + directionZ;
		if (inside(nx, ny, nz)) distance += fraction[cellIndex(nx, ny, nz)];
		if (!(distance > 0.0F)) {
			throw new IllegalStateException("Pressure-cell centre does not lie inside its reconstructed liquid phase");
		}
		return (float) Math.min(distance, 1);
	}

	private double sampleFraction(int x, int y, int z, double fallback, boolean[] solid) {
		if (!inside(x, y, z)) return fallback;
		int cell = cellIndex(x, y, z);
		return solid[cell] ? fallback : fraction[cell];
	}

	private int airDirection(int x, int y, int z, boolean[] solid) {
		if (!inside(x, y, z)) return 0;
		int cell = cellIndex(x, y, z);
		return !solid[cell] && fraction[cell] <= 0.5F ? 1 : 0;
	}

	private boolean inside(int x, int y, int z) {
		return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
	}

	private int cellIndex(int x, int y, int z) {
		return (z * sizeY + y) * sizeX + x;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
