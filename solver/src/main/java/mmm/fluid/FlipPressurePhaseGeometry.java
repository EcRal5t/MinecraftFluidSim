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
	private byte[] collisionMasks;
	private long revision;
	/** Immutable local geometry; unchanged cells share their fractions and component planes. */
	static final class OccupancyCell {
		private final double[] fractions;
		private final PlicComponentGeometry.Plane[] planes;
		private final PlicComponentGeometry.Plane uniform;
		OccupancyCell(double[] fractions,PlicComponentGeometry.Plane[] planes){this.fractions=fractions;this.planes=planes;uniform=null;}
		OccupancyCell(PlicComponentGeometry.Plane uniform){this.uniform=uniform;fractions=null;planes=null;}
		double fraction(int micro){return uniform==null?fractions[micro]:(uniform.support()&1<<micro)==0?0:uniform.fraction();}
		PlicComponentGeometry.Plane plane(int micro){return uniform==null?planes[micro]:(uniform.support()&1<<micro)==0?null:uniform;}
	}
	private static final OccupancyCell EMPTY_OPEN=new OccupancyCell(new PlicComponentGeometry.Plane(255,0,1,0,0,0,null));
	private static final OccupancyCell FULL_OPEN=new OccupancyCell(new PlicComponentGeometry.Plane(255,1,1,0,0,0,null));
	private static final OccupancyCell CLOSED=new OccupancyCell(new PlicComponentGeometry.Plane(0,0,0,0,0,0,null));
	private OccupancyCell[] occupancyCells;
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
		this.collisionMasks = new byte[cellCount];
	}

	boolean matchesShape(int x, int y, int z) { return sizeX == x && sizeY == y && sizeZ == z; }
	long revision() { return revision; }
	int collisionMask(int cell) { return Byte.toUnsignedInt(collisionMasks[cell]); }
	SignedDyadic poreVolume(int cell) { return poreVolumes[cell]; }
	boolean hasMicroOccupation() { return occupancyCells!=null; }
	PhaseOccupancyState occupancyState() { return microOccupancy; }
	OccupancyCell occupancyCell(int cell) { return occupancyCells==null?null:occupancyCells[cell]; }
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
		occupancyCells=new OccupancyCell[fraction.length];
        collisionMasks=cut.snapshot().masksView();java.util.Arrays.fill(components,null);java.util.Arrays.fill(offsetBrackets,null);
        for(int cell=0;cell<fraction.length;cell++){
            double total=0,liquid=0;for(int bit=0;bit<8;bit++){int piece=8*cell+bit;total+=pore[piece];liquid+=state.liquidVolume(piece);}
            poreVolumes[cell]=SignedDyadic.of(total);fraction[cell]=total>0?Math.clamp(liquid/total,0,1):0;normalX[cell]=normalY[cell]=normalZ[cell]=0;planeOffset[cell]=0;
        }
		buildOccupancyCells(state,cut,pore,captured,null);
		microOccupancy=state;revision=Math.addExact(revision,1);
	}
	/** Accepted fixed-support quantities prepare phase and gas geometry together, without a micro-pore column. */
	void rebuildInventory(FlipCutCellGeometry cut,mmm.inventory.LiquidCellInventory.Snapshot cells,
	                      GasComponentCapacity.Prepared capacity,FlipPressurePhaseGeometry captured){
		occupancyCells=new OccupancyCell[fraction.length];collisionMasks=capacity.masks;
		java.util.Arrays.fill(components,null);java.util.Arrays.fill(offsetBrackets,null);
		System.arraycopy(capacity.pores,0,poreVolumes,0,fraction.length);
		double[] liquid=new double[8*fraction.length];
		for(int cell=0;cell<fraction.length;cell++){
			double total=0,amount=0;
			for(int at=capacity.offsets[cell];at<capacity.offsets[cell+1];at++){
				var component=capacity.components.get(at);int support=component.supportMask();
				double piecePore=component.poreVolumeNormalized().toDouble()/Integer.bitCount(support),pore=0,stock=0;
				for(int bits=support;bits!=0;bits&=bits-1)pore+=piecePore;
				for(var entry:cells.rows().get(cell))if(entry.key().supportMask()==support)stock+=entry.volumeFraction();
				double value=pore>0?Math.clamp(stock/pore,0,1):0;
				for(int bits=support;bits!=0;bits&=bits-1){int piece=8*cell+Integer.numberOfTrailingZeros(bits);
					liquid[piece]=value*piecePore;total+=piecePore;amount+=liquid[piece];}
			}
			if(capacity.offsets[cell+1]-capacity.offsets[cell]>1){
				total=amount=0;for(int micro=0;micro<8;micro++){total+=microPore(cell,micro,null,capacity);amount+=liquid[8*cell+micro];}
			}
			fraction[cell]=total>0?Math.clamp(amount/total,0,1):0;
			normalX[cell]=normalY[cell]=normalZ[cell]=0;planeOffset[cell]=0;
		}
		microOccupancy=new PhaseOccupancyState(liquid);
		buildOccupancyCells(microOccupancy,cut,null,captured,capacity);revision=Math.addExact(revision,1);
	}
	private double microPore(int cell,int micro,double[] pore,GasComponentCapacity.Prepared capacity){
		if(pore!=null)return pore[8*cell+micro];
		for(int at=capacity.offsets[cell];at<capacity.offsets[cell+1];at++){
			var component=capacity.components.get(at);if((component.supportMask()&1<<micro)!=0)
				return component.poreVolumeNormalized().toDouble()/Integer.bitCount(component.supportMask());
		}
		return 0;
	}
	private void buildOccupancyCells(PhaseOccupancyState state,FlipCutCellGeometry cut,double[] pore,
	                                 FlipPressurePhaseGeometry captured,GasComponentCapacity.Prepared capacity){
		for(int cell=0;cell<fraction.length;cell++){
			int x=cell%sizeX,y=cell/sizeX%sizeY,z=cell/(sizeX*sizeY),support=(~collisionMask(cell))&255;
			double fill=fraction[cell];
			if(support==0){occupancyCells[cell]=CLOSED;continue;}
			double porosity=poreVolumes[cell].toDouble()/(Integer.bitCount(support)*.125);
			if(fill==0||fill==1){
				occupancyCells[cell]=support==255&&porosity==1?(fill==0?EMPTY_OPEN:FULL_OPEN)
						:new OccupancyCell(new PlicComponentGeometry.Plane(support,fill,porosity,0,0,0,null));
				continue;
			}
			if(captured!=null&&sameOccupancyInputs(cell,x,y,z,state,pore,captured,capacity)){
				copyCell(captured,cell);occupancyCells[cell]=captured.occupancyCells[cell];continue;
			}
			float fallback=(float)fill;
			float gx=(float)coarseSample(x-1,y,z,fallback)-(float)coarseSample(x+1,y,z,fallback);
			float gy=(float)coarseSample(x,y-1,z,fallback)-(float)coarseSample(x,y+1,z,fallback);
			float gz=(float)coarseSample(x,y,z-1,fallback)-(float)coarseSample(x,y,z+1,fallback);
			int axis=1;if(Math.abs(gx)>Math.abs(gy))axis=0;
			if(Math.abs(gz)>Math.abs(axis==0?gx:gy))axis=2;
			float gradient=axis==0?gx:axis==1?gy:gz,sign=gradient<0?-1:1;
			int low=axis==0?0x55:axis==1?0x33:0x0f,firstBits=sign>0?low:(low^255);
			int first=Integer.bitCount(support&firstBits),second=Integer.bitCount(support&(firstBits^255));
			double target=fill*Integer.bitCount(support)*.125,firstVolume=first*.125;
			double depth=target<=firstVolume&&first>0?target/(.25*first)
					:.5+(second>0?(target-firstVolume)/(.25*second):0);
			double cutPosition=sign>0?depth:1-depth;
			float nx=axis==0?sign:0,ny=axis==1?sign:0,nz=axis==2?sign:0;
			var bracket=new PlicPlaneOffset.Result(sign*cutPosition,target);
			normalX[cell]=nx;normalY[cell]=ny;normalZ[cell]=nz;planeOffset[cell]=bracket.offset();offsetBrackets[cell]=bracket;
			var plane=new PlicComponentGeometry.Plane(support,fill,porosity,nx,ny,nz,bracket);
			var fractions=new double[8];var planes=new PlicComponentGeometry.Plane[8];
			for(int bit=0;bit<8;bit++)if((support&1<<bit)!=0){
				double origin=.5*((bit>>>axis)&1);
				double liquidDepth=sign>0?Math.clamp(cutPosition-origin,0,.5):Math.clamp(origin+.5-cutPosition,0,.5);
				fractions[bit]=2*liquidDepth;planes[bit]=plane;
			}
			occupancyCells[cell]=new OccupancyCell(fractions,planes);
		}
	}

	private boolean sameOccupancyInputs(int cell,int x,int y,int z,PhaseOccupancyState state,double[] pore,FlipPressurePhaseGeometry old,
	                                    GasComponentCapacity.Prepared capacity){
		if(old.microOccupancy==null||collisionMask(cell)!=old.collisionMask(cell)||fraction[cell]!=old.fraction[cell])return false;
		for(int bit=0;bit<8;bit++)if(8*microPore(cell,bit,pore,capacity)!=old.componentPoreScale(cell,bit,0))return false;
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

	private double coarseSample(int x,int y,int z,double fallback) {
		if(!inside(x,y,z))return fallback;
		int cell=cellIndex(x,y,z);return poreVolumes[cell].signum()>0?fraction[cell]:fallback;
	}
	void copyLiquidMicroVolumes(double[] target){
		for(int cell=0;cell<fraction.length;cell++)for(int bit=0;bit<8;bit++)
			target[8*cell+bit]=liquidFractionExact(cell,bit)*componentPoreScale(cell,bit,0)*.125;
	}

	/** Exact inputs read by dry-piece and microface construction, including absent gas pieces. */
	boolean sameCellGeometry(int cell, FlipPressurePhaseGeometry other) {
		if (collisionMask(cell) != other.collisionMask(cell) || !poreVolumes[cell].equals(other.poreVolumes[cell])) return false;
		if(occupancyCells!=null&&other.occupancyCells!=null&&occupancyCells[cell]==other.occupancyCells[cell])return true;
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
		occupancyCells=null;
		microOccupancy=null;
		PhaseLiquidGeometryEdit[] overlay = edits.isEmpty() ? null : new PhaseLiquidGeometryEdit[fraction.length];
		for (var edit : edits) {
			if (edit == null || edit.cell() >= fraction.length || overlay[edit.cell()] != null
					|| edit.collisionMask() != cutCells.mask(edit.cell()))
				throw new IllegalArgumentException("Repeated PLIC liquid edit or mismatched collision mask");
			overlay[edit.cell()] = edit;
		}
		revision = Math.addExact(revision,1);
		collisionMasks = cutCells.snapshot().masksView();
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
	                       float cellSize, PhaseLiquidGeometryEdit edit) {
		double fill=fraction[cell];float fallback=(float)fill;
		float gx=(float)sampleFraction(x-1,y,z,fallback,solid)-(float)sampleFraction(x+1,y,z,fallback,solid);
		float gy=(float)sampleFraction(x,y-1,z,fallback,solid)-(float)sampleFraction(x,y+1,z,fallback,solid);
		float gz=(float)sampleFraction(x,y,z-1,fallback,solid)-(float)sampleFraction(x,y,z+1,fallback,solid);
		int axis=1;if(Math.abs(gx)>Math.abs(gy))axis=0;
		if(Math.abs(gz)>Math.abs(axis==0?gx:gy))axis=2;
		float sign=(axis==0?gx:axis==1?gy:gz)<0?-1:1;
		int support=(~cutCells.mask(cell))&255,low=axis==0?0x55:axis==1?0x33:0x0f;
		int firstBits=sign>0?low:(low^255),first=Integer.bitCount(support&firstBits),second=Integer.bitCount(support&(firstBits^255));
		double target=fill*Integer.bitCount(support)*.125,firstVolume=first*.125;
		double depth=target<=firstVolume&&first>0?target/(.25*first):.5+(second>0?(target-firstVolume)/(.25*second):0);
		double coordinate=sign>0?depth:1-depth;
		float nx=axis==0?sign:0,ny=axis==1?sign:0,nz=axis==2?sign:0;
		var bracket=new PlicPlaneOffset.Result(sign*coordinate,target);
		normalX[cell]=nx;normalY[cell]=ny;normalZ[cell]=nz;planeOffset[cell]=bracket.offset();offsetBrackets[cell]=bracket;
		if(edit!=null){
			double porosity=poreVolumes[cell].toDouble()/(Integer.bitCount(support)*.125);
			components[cell]=PlicComponentGeometry.single(support,fill,porosity,nx,ny,nz,bracket);
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
		collisionMasks = source.collisionMasks.clone();
		if(source.occupancyCells!=null)occupancyCells=source.occupancyCells.clone();
		revision = source.revision;
		microOccupancy=source.microOccupancy;
	}
	FlipPressurePhaseGeometry copy() { return new FlipPressurePhaseGeometry(this); }


	void rebuildEditedCells(FlipPressurePhaseGeometry captured, PhaseLiquidGeometryEdit edit,
	                         int[] cells, java.util.List<PhaseLiquidGeometryEdit> stock,
	                         FlipCutCellGeometry cut, boolean[] solid, float cellSize) {

		revision = Math.addExact(revision, 1);
		double open = poreVolumes[edit.cell()].toDouble();
		fraction[edit.cell()] = solid[edit.cell()] || open <= 0 ? 0 : clamp(edit.volume().toDouble() / open, 0, 1);
		for (int cell : cells) {
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


	float liquidFraction(int cell) { return (float) fraction[cell]; }
	float planeNormal(int cell, int axis) { return axis == 0 ? normalX[cell] : axis == 1 ? normalY[cell] : normalZ[cell]; }
	double planeOffset(int cell) { return planeOffset[cell]; }
	boolean hasComponentPlanes(int cell) { return occupancyCells!=null || components[cell] != null; }
	float planeNormal(int cell, int micro, int axis) {
		if(occupancyCells!=null){var plane=occupancyCells[cell].plane(micro);return plane==null?0:plane.normal(axis);}
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? planeNormal(cell,axis) : plane == null ? 0 : plane.normal(axis);
	}
	double planeOffset(int cell, int micro) {
		if(occupancyCells!=null){var plane=occupancyCells[cell].plane(micro);return plane==null?0:plane.offset();}
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? planeOffset[cell] : plane == null ? 0 : plane.offset();
	}
	PlicPlaneOffset.Result offsetBracket(int cell,int micro) {
		if(occupancyCells!=null){var plane=occupancyCells[cell].plane(micro);return plane==null?null:plane.bracket();}
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? offsetBrackets[cell] : plane == null ? null : plane.bracket();
	}
	double liquidFractionExact(int cell, int micro) {
		if(occupancyCells!=null)return occupancyCells[cell].fraction(micro);
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? fraction[cell] : plane == null ? 0 : plane.fraction();
	}
	double componentLiquidFraction(int cell,int micro) {
		var plane=occupancyCells!=null?occupancyCells[cell].plane(micro):components[cell]==null?null:components[cell].plane(micro);
		return plane==null?occupancyCells==null&&components[cell]==null?fraction[cell]:0:plane.fraction();
	}
	PlicComponentGeometry.Plane componentPlane(int cell,int micro) {
		if(occupancyCells!=null)return occupancyCells[cell].plane(micro);
		int support=CellRemapMicroTopology.support(collisionMask(cell),micro);
		if(components[cell]!=null){var plane=components[cell].plane(micro);
			return plane!=null?plane:new PlicComponentGeometry.Plane(support,0,0,0,0,0,null);
		}
		double open=Integer.bitCount(~collisionMask(cell)&255)*.125;
		return new PlicComponentGeometry.Plane(support,fraction[cell],open>0?poreVolumes[cell].toDouble()/open:0,
				normalX[cell],normalY[cell],normalZ[cell],offsetBrackets[cell]);
	}
	double componentPoreScale(int cell,int micro,double fallback) {
		if(occupancyCells!=null){var plane=occupancyCells[cell].plane(micro);return plane==null?0:plane.porosity();}
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
		return GasFaceIntersection.halfPlaneRectangleArea(x,y,.5,
				planeNormal(cell,micro,(axis+1)%3),planeNormal(cell,micro,(axis+2)%3),
				planeOffset(cell,micro)-side*planeNormal(cell,micro,axis));
	}
	double sharedLiquidPortArea(int left,int right,int axis,int a,int b,FlipCutCellGeometry cut){
		int minus=microAtFace(left,axis,1,.5*a,.5*b),plus=microAtFace(right,axis,0,.5*a,.5*b);
		return (cut.mask(left)&1<<minus)!=0||(cut.mask(right)&1<<plus)!=0?0:wetRectangle(left,right,axis,.5*a,.5*b,.5);
	}

	double sharedLiquidFaceArea(int left, int right, int axis, FlipCutCellGeometry cut) {
		if (!(fraction[left] > 0) || !(fraction[right] > 0)) return 0;
		if (occupancyCells==null && cut.mask(left) == 0 && cut.mask(right) == 0) return wetRectangle(left, right, axis, 0, 0, 1);
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

	private double wetRectangle(int left,int right,int axis,double x,double y,double span){
		int minus=microAtFace(left,axis,1,x,y),plus=microAtFace(right,axis,0,x,y);
		double first=liquidFractionExact(left,minus),second=liquidFractionExact(right,plus);
		if(first==0||second==0)return 0;
		int tangent=(axis+1)%3,other=(axis+2)%3;
		if(first>=1){
			if(second>=1)return span*span;
			return GasFaceIntersection.halfPlaneRectangleArea(x,y,span,planeNormal(right,plus,tangent),planeNormal(right,plus,other),planeOffset(right,plus));
		}
		if(second>=1)return GasFaceIntersection.halfPlaneRectangleArea(x,y,span,planeNormal(left,minus,tangent),planeNormal(left,minus,other),
				planeOffset(left,minus)-planeNormal(left,minus,axis));
		rectangle(x,y,span);
		int count=clipFace(4,planeNormal(left,minus,tangent),planeNormal(left,minus,other),
				planeOffset(left,minus)-planeNormal(left,minus,axis));
		count=clipFace(count,planeNormal(right,plus,tangent),planeNormal(right,plus,other),planeOffset(right,plus));
		double twiceArea=0;
		for(int i=1;i+1<count;i++)twiceArea+=(faceX[i]-faceX[0])*(faceY[i+1]-faceY[0])
				-(faceY[i]-faceY[0])*(faceX[i+1]-faceX[0]);
		return .5*Math.abs(twiceArea);
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
