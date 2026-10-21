package mmm.fluid;

import java.util.Arrays;

/** Prepares wet supports, hydrostatic contact loads and sparse face incidence from committed geometry. */
final class PressureFaceGraph {
	interface SideConsumer { void accept(int row, double weight, double fixedPressure); }
	private final FluidDomain domain;
	final int[] offsets = new int[3];
	final int faceCount;
	final int[] firstCell, secondCell, axis, localFace, start, pressureSupports;
	final double[] inverseMass, affine;
	private int[] rows;
	private double[] coefficients;
	private int entries;
	double[] gasPressureLoad = new double[0];
	private final double[] supportFill,supportDepth,wetPortArea,sideWetArea,columnBottomPressure,hydrostaticReference;
	private final boolean[] groundedSupport;
	private final int[] verticalSupportContact;
	private final boolean[] wetFacesPrepared = new boolean[3];
	private FlipPressurePhaseGeometry supportGeometry;
	private long supportRevision=-1;
	private FlipPressurePhaseGeometry classifiedGeometry;
	private GasRuntimeGeometry classifiedGasFrame;
	private long classifiedGeometryRevision=-1,classifiedQuantityRevision=-1;
	private final int[] supportGasOwner;
	private final int[] parent;
	final boolean[] constrained;
	private final boolean[] anchored;
	private int assemblyFace;
	private double assemblyCoefficient;
	private final SideConsumer assemblySide = (row, weight, fixed) ->
			add(assemblyFace, row, assemblyCoefficient * weight, fixed);

	PressureFaceGraph(FluidDomain domain, boolean[] anchored) {
		this.domain = domain;
		offsets[1] = domain.mac.velocityX.length; offsets[2] = offsets[1] + domain.mac.velocityY.length;
		faceCount = offsets[2] + domain.mac.velocityZ.length;
		firstCell = new int[faceCount]; secondCell = new int[faceCount]; axis = new int[faceCount]; localFace = new int[faceCount];
		start=new int[faceCount+1];inverseMass=new double[faceCount];affine=new double[faceCount];
		supportGasOwner=new int[8*domain.cellCount];Arrays.fill(supportGasOwner,-1);
		pressureSupports = new int[domain.cellCount]; parent = new int[domain.cellCount];
		supportFill=new double[8*domain.cellCount];supportDepth=new double[supportFill.length];
		wetPortArea=new double[4*faceCount];sideWetArea=new double[8*faceCount];columnBottomPressure=new double[supportFill.length];hydrostaticReference=new double[supportFill.length];
		groundedSupport=new boolean[supportFill.length];verticalSupportContact=new int[domain.mac.velocityY.length];
		constrained = new boolean[domain.cellCount]; this.anchored=anchored;
		rows = new int[64]; coefficients = new double[rows.length];
		Arrays.fill(firstCell, -1); Arrays.fill(secondCell, -1);
		for (int a = 0; a < 3; a++) {
			int nx = domain.sizeX + (a == 0 ? 1 : 0), ny = domain.sizeY + (a == 1 ? 1 : 0), nz = domain.sizeZ + (a == 2 ? 1 : 0);
			for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
				int local = (z * ny + y) * nx + x, face = offsets[a] + local;
				axis[face] = a; localFace[face] = local;
				int coordinate = a == 0 ? x : a == 1 ? y : z, length = a == 0 ? domain.sizeX : a == 1 ? domain.sizeY : domain.sizeZ;
				if (coordinate == 0 || coordinate == length) continue;
				secondCell[face] = domain.domainFlowFields.cellIndex(x, y, z);
				firstCell[face] = domain.domainFlowFields.cellIndex(x - (a == 0 ? 1 : 0), y - (a == 1 ? 1 : 0), z - (a == 2 ? 1 : 0));
			}
		}
	}

	void classify(){
		var phase=domain.pressureSystem.phaseGeometry();var frame=domain.gasState.frame;
		boolean geometryChanged=classifiedGeometry!=phase||classifiedGeometryRevision!=phase.revision()
				||classifiedQuantityRevision!=domain.cellMacStage.quantityRevision();
		boolean ownersChanged=classifiedGasFrame==null||classifiedGasFrame.topology()!=frame.topology();
		if(!geometryChanged&&!ownersChanged)return;
		if(geometryChanged){
			prepareSupportGeometry();
			Arrays.fill(pressureSupports, 0);
			// Gas geometry already owns the exact pore-minus-stock endpoint. Pressure
			// classification reads that same preparation instead of settling it again.
			// Sub-resolution dry capacity shares liquid continuum pressure. Its gas
			// inventory, thermodynamic volume and energy remain in the retained frame.
			for (var component : domain.gasState.frame.capacity().components()) {
				int cell = component.cell(), support = component.supportMask();
				if (component.poreVolumeNormalized().signum() <= 0
						|| component.availableVolumeNormalized().toDouble() > mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION) continue;
				boolean hasMass = false;
				if (support == (~domain.geometry.cutCells.mask(cell) & 255)) {
					hasMass = domain.liquidGrid.cellStockMass[cell] > 0;
				} else {
					for (var stock : domain.liquidCells.stocksInCell(cell)) {
						if (stock.key().supportMask() == support && stock.massUnits() > 0) { hasMass = true; break; }
					}
				}
				if (hasMass) pressureSupports[cell] |= support;
			}
			prepareHydrostaticReference();
			classifiedGeometry=phase;classifiedGeometryRevision=phase.revision();
			classifiedQuantityRevision=domain.cellMacStage.quantityRevision();
		}
		if(geometryChanged||ownersChanged){
			Arrays.fill(supportGasOwner,-1);
			for(var component:frame.capacity().components()){
				int cell=component.cell(),support=component.supportMask();
				supportGasOwner[8*cell+Integer.numberOfTrailingZeros(support)]=frame.topology().nodeOfComponent(cell,support);
			}
		}
		classifiedGasFrame=frame;
	}
	void rebuild(float[] previousLiquidPressure) {
		classify();
		// Incidence storage follows occupied supports, rather than every dry face
		// in the enclosing region. General cut supports grow through the same append path.
		int reserve=Math.max(64,6*domain.liquidCells.stocks().size());
		if(rows.length<reserve){rows=new int[reserve];coefficients=new double[reserve];}
		prepareWetFaces(0);
		prepareWetFaces(2);
		int count=domain.gasState.network.nodeCount();
		if(gasPressureLoad.length<count+1)gasPressureLoad=new double[count+1];
		double head=domain.config.density()*domain.config.gravity()*domain.config.cellSize();
		Arrays.fill(gasPressureLoad,head);
		entries=0; Arrays.fill(inverseMass, 0); Arrays.fill(affine, 0);
		Arrays.fill(constrained, false);
		for (int c = 0; c < parent.length; c++) parent[c] = c;
		for (int f = 0; f < faceCount; f++) {
			start[f]=entries; int a = firstCell[f], b = secondCell[f]; if (a < 0) continue;
			double mass = faceMass(f); if (!(mass > 0)) continue;
			int d = axis[f]; boolean wallSupported=domain.cellMacStage.isUnresolvedWallFace(d,localFace[f]);
			double open = domain.pressureSystem.solidFaces.open(d, localFace[f]);
			if (!(open > 0)) continue;
			if(!wallSupported)inverseMass[f] = domain.unitScale.cubicMetersPerNormalizedVolume() / mass;
			double coefficient = open / domain.config.cellSize();
			int maskA = domain.geometry.cutCells.mask(a), maskB = domain.geometry.cutCells.mask(b), ports = 0;
			for (int micro = 0; micro < 8; micro++) if ((micro >>> d & 1) != 0
					&& (maskA & 1 << micro) == 0 && (maskB & 1 << (micro ^ 1 << d)) == 0) ports++;

			for (int micro = 0; micro < 8; micro++) if ((micro >>> d & 1) != 0
					&& (maskA & 1 << micro) == 0 && (maskB & 1 << (micro ^ 1 << d)) == 0) {
				int peer = micro ^ 1 << d;
				int port=(micro>>>((d+1)%3)&1)+2*(micro>>>((d+2)%3)&1);
				double portArea = wetPortArea[4*f+port]*(open/(.25*ports));
				if(wallSupported||!(portArea>0))continue;
				assemblyFace = f;
				assemblyCoefficient = portArea / domain.config.cellSize();
				pressureAtSide(a,b,micro,peer,d,1,assemblySide);
				assemblyCoefficient = -assemblyCoefficient;
				pressureAtSide(b,a,peer,micro,d,1,assemblySide);
			}
			if(wallSupported)continue;
			int kept = start[f];
			for (int i = start[f]; i < entries; i++) if (coefficients[i] != 0) { rows[kept] = rows[i]; coefficients[kept++] = coefficients[i]; }
			entries = kept;
			int liquid = -1; boolean reference = false; double sum = 0,liquidLoad=Double.NEGATIVE_INFINITY;
			for (int i = start[f]; i < entries; i++) {
				int row = rows[i]; sum += coefficients[i];
				if (row >= domain.cellCount) reference = true;
				else {
					liquidLoad=Math.max(liquidLoad,previousLiquidPressure[row]+referencePressure(row,pressureSupports[row])+domain.tension.surfaceTensionPressure[row]+head);
					if (liquid < 0) liquid = row; else union(liquid, row);
				}
			}
			// Reuse this face's row classification while the committed liquid
			// pressure is still present; only gas-contact faces need a second pass.
			if(reference&&liquid>=0)for(int i=start[f];i<entries;i++)if(rows[i]>=domain.cellCount) {
				int node=rows[i]-domain.cellCount;gasPressureLoad[node]=Math.max(gasPressureLoad[node],liquidLoad);
			}
			if (liquid >= 0 && (reference || Math.abs(sum) > 16 * Math.ulp(coefficient))) constrained[liquid] = true;
		}
		start[faceCount]=entries;
		for(int cell=0;cell<parent.length;cell++)if(constrained[cell])constrained[root(cell)]=true;
	}
	void excludeAnchoredRows(){
		for (int i = 0; i < entries; i++) if (rows[i] < domain.cellCount && anchored[rows[i]]) rows[i] = -1;
	}

	/** One face may contact several isolated gas pieces; weights follow its actual open microfaces. */
	void forEachSide(int cell, int neighbor, SideConsumer consumer,java.util.function.DoubleConsumer referenceConsumer) {
		int d = cell % domain.sizeX != neighbor % domain.sizeX ? 0
				: cell / domain.sizeX % domain.sizeY != neighbor / domain.sizeX % domain.sizeY ? 1 : 2;
		prepareWetFaces(d);
		int side = neighbor > cell ? 1 : 0, mask = domain.geometry.cutCells.mask(cell), peerMask = domain.geometry.cutCells.mask(neighbor);
		int ports = 0;
		for (int micro = 0; micro < 8; micro++) if ((micro >>> d & 1) == side && (mask & 1 << micro) == 0 && (peerMask & 1 << (micro ^ 1 << d)) == 0) ports++;
		if (ports == 0) return;
		for (int micro = 0; micro < 8; micro++) {
			if ((micro >>> d & 1) != side || (mask & 1 << micro) != 0 || (peerMask & 1 << (micro ^ 1 << d)) != 0) continue;
			int right=Math.max(cell,neighbor);
			int x=right%domain.sizeX,y=right/domain.sizeX%domain.sizeY,z=right/(domain.sizeX*domain.sizeY);
			int face=offsets[d]+(z*(domain.sizeY+(d==1?1:0))+y)*(domain.sizeX+(d==0?1:0))+x;
			int port=(micro>>>((d+1)%3)&1)+2*(micro>>>((d+2)%3)&1);
			// The solid's complementary share sees the same liquid contact fraction.
			double weight=wetPortArea[4*face+port]/(.25*ports);
			if(weight>0){
				int support=CellRemapMicroTopology.support(mask,micro);
				double reference=hydrostaticReference[8*cell+Integer.numberOfTrailingZeros(support)];
				pressureAtSide(cell,neighbor,micro,micro^(1<<d),d,weight,
						(row,w,fixed)->consumer.accept(row,w,fixed+reference));
				referenceConsumer.accept(weight*reference);
			}
		}
	}
	private void pressureAtSide(int cell,int neighbor,int micro,int peer,int axis,double weight,SideConsumer consumer){
		int support=CellRemapMicroTopology.support(domain.geometry.cutCells.mask(cell),micro);
		int other=CellRemapMicroTopology.support(domain.geometry.cutCells.mask(neighbor),peer);
		double fill=supportFill[8*cell+Integer.numberOfTrailingZeros(support)];
		double peerFill=supportFill[8*neighbor+Integer.numberOfTrailingZeros(other)];
		// -grad(p_mix) + J_face grad(f), p_mix=pi+f*J, gives
		// -grad(pi)-f_face grad(J). Equal J cancels at a static interface;
		// unequal wet-side J retains the shape-changing capillary load. A dry
		// neighbor extends its contacting liquid's J across the same face.
		double jump=fill>0?domain.tension.surfaceTensionPressure[cell]:peerFill>0?domain.tension.surfaceTensionPressure[neighbor]:0;
		int right=Math.max(cell,neighbor),x=right%domain.sizeX,y=right/domain.sizeX%domain.sizeY,z=right/(domain.sizeX*domain.sizeY);
		int face=offsets[axis]+(z*(domain.sizeY+(axis==1?1:0))+y)*(domain.sizeX+(axis==0?1:0))+x;
		int port=(micro>>>((axis+1)%3)&1)+2*(micro>>>((axis+2)%3)&1);
		double area=wetPortArea[4*face+port];
		double hydroFraction=area>0?sideWetArea[8*face+2*port+(cell>neighbor?1:0)]/area:0;
		pressureAtMicro(cell,micro,axis,weight,.5*(fill+peerFill)*jump,hydroFraction,consumer);
	}
	private void pressureAtMicro(int cell,int micro,int axis,double weight,double capillary,double hydroFraction,SideConsumer consumer) {
		if ((pressureSupports[cell] & 1 << micro) != 0) { consumer.accept(anchored[cell] ? -1 : cell, weight, capillary); return; }
		int support=CellRemapMicroTopology.support(domain.geometry.cutCells.mask(cell),micro);
		int node=supportGasOwner[8*cell+Integer.numberOfTrailingZeros(support)];
		if(node>=0)gasSide(cell,micro,axis,node,weight,capillary,hydroFraction,consumer);
		else consumer.accept(anchored[cell]?-1:cell,weight,capillary);
	}

	private void gasSide(int cell,int micro,int axis,int node,double weight,double capillary,double hydroFraction,SideConsumer consumer) {
		boolean variable = node > 0 && domain.pressureSystem.gasPressureUnknown[node];
		double fixed = variable || node == 0 ? 0 : domain.pressureBoundaryView.gasPressureOfNode(node);
		int support=CellRemapMicroTopology.support(domain.geometry.cutCells.mask(cell),micro);
		int slot=8*cell+Integer.numberOfTrailingZeros(support);
		double ownHead=groundedSupport[slot]?domain.liquidGrid.cellDensity[cell]*domain.config.gravity()*domain.config.cellSize()*supportDepth[slot]:0;
		// The vertical hydrostatic column is solved analytically. Its top liquid
		// support has zero residual load; an enclosed cavity still receives the
		// actual pressure of the liquid lying above it.
		double residualHead=ownHead*(axis==1?1:hydroFraction)-hydrostaticReference[slot];
		consumer.accept(variable ? domain.cellCount + node : -1, weight, fixed+capillary+residualHead);
	}
	/** Coarse inventory wet height, shared by pressure publication and face loading. */
	double hydrostaticOffset(int cell,int support){
		prepareSupportGeometry();double volume=0,depth=0;
		for(int component:CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell)))if((component&support)!=0){
			int slot=8*cell+Integer.numberOfTrailingZeros(component);double wet=supportFill[slot]*Integer.bitCount(component);
			volume+=wet;if(groundedSupport[slot])depth+=wet*supportDepth[slot];
		}
		return volume>0?domain.liquidGrid.cellDensity[cell]*domain.config.gravity()*domain.config.cellSize()*depth/volume:0;
	}
	/** The hydrostatic part of a liquid pressure unknown; gas rows retain actual gauge pressure. */
	double referencePressure(int cell,int support){
		double volume=0,pressure=0;
		for(int component:CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell)))if((component&support)!=0){
			int slot=8*cell+Integer.numberOfTrailingZeros(component);double wet=supportFill[slot]*Integer.bitCount(component);
			volume+=wet;pressure+=wet*hydrostaticReference[slot];
		}
		return volume>0?pressure/volume:0;
	}
	private void prepareSupportGeometry(){
		var phase=domain.pressureSystem.phaseGeometry();
		if(supportGeometry==phase&&supportRevision==phase.revision())return;
		Arrays.fill(supportFill,0);Arrays.fill(supportDepth,0);
		for(int cell=0;cell<domain.cellCount;cell++)for(int support:CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell))){
			int micro=Integer.numberOfTrailingZeros(support),slot=8*cell+micro;
			if(phase.hasComponentPlanes(cell))supportFill[slot]=phase.componentLiquidFraction(cell,micro);
			else{
				double wet=0;for(int bits=support;bits!=0;bits&=bits-1)wet+=.125*phase.liquidFractionExact(cell,Integer.numberOfTrailingZeros(bits));
				supportFill[slot]=Math.clamp(wet/(.125*Integer.bitCount(support)),0,1);
			}
			double height=(support&0x33)!=0&&(support&0xcc)!=0?1:.5;
			supportDepth[slot]=.5*height*supportFill[slot];
		}
		Arrays.fill(wetFacesPrepared, false);
		// Pressure observations need vertical support and column head. Horizontal
		// contact areas are prepared when forces or matrix assembly first use them.
		prepareWetFaces(1);
		supportGeometry=phase;supportRevision=phase.revision();
	}
	private void prepareWetFaces(int d){
		if (wetFacesPrepared[d]) return;
		var phase=domain.pressureSystem.phaseGeometry();var cut=domain.geometry.cutCells;
		int first = offsets[d], end = d == 2 ? faceCount : offsets[d + 1];
		Arrays.fill(wetPortArea, 4 * first, 4 * end, 0);
		Arrays.fill(sideWetArea, 8 * first, 8 * end, 0);
		if (d == 1) Arrays.fill(verticalSupportContact, 0);
		for(int face=first;face<end;face++){
			int left=firstCell[face],right=secondCell[face];if(left<0)continue;
			if(!(phase.liquidFractionExact(left)>0)&&!(phase.liquidFractionExact(right)>0))continue;
			if(cut.mask(left)==0&&cut.mask(right)==0){
				double lf=phase.liquidFractionExact(left),rf=phase.liquidFractionExact(right);
				if((lf>=1||lf==0)&&(rf>=1||rf==0)){
					for(int port=0;port<4;port++){
						wetPortArea[4*face+port]=.25;
						sideWetArea[8*face+2*port]=lf>=1?.25:0;sideWetArea[8*face+2*port+1]=rf>=1?.25:0;
					}
					if(d==1&&lf>=1&&rf>=1)verticalSupportContact[localFace[face]]=15;
					continue;
				}
			}
			for(int a=0;a<2;a++)for(int b=0;b<2;b++){
				int port=a+2*b;
				int minus=phase.microAtFace(left,d,1,.5*a,.5*b),plus=phase.microAtFace(right,d,0,.5*a,.5*b);
				if((cut.mask(left)&1<<minus)!=0||(cut.mask(right)&1<<plus)!=0)continue;
				double leftArea=phase.liquidPortArea(left,d,1,a,b,cut),rightArea=phase.liquidPortArea(right,d,0,a,b,cut);
				double shared=leftArea==0||rightArea==0?0:leftArea==.25?rightArea:rightArea==.25?leftArea
						:phase.sharedLiquidPortArea(left,right,d,a,b,cut);
				double geometricArea=Math.clamp(leftArea+rightArea-shared,0,.25);
				// The pressure area shares the two control volumes' actual liquid inertia.
				// Thin films retain geometric contact without carrying a full-face pressure load.
				double fill=phase.componentLiquidFraction(left,minus)+phase.componentLiquidFraction(right,plus);
				double pressureArea=Math.min(geometricArea,.25*fill);
				double scale=geometricArea>0?pressureArea/geometricArea:0;
				sideWetArea[8*face+2*port]=leftArea*scale;sideWetArea[8*face+2*port+1]=rightArea*scale;
				wetPortArea[4*face+port]=pressureArea;
				if(d==1&&(shared>0||unresolvedVerticalGap(left,minus,right,plus,.25+.5*b,.25+.5*a)))
					verticalSupportContact[localFace[face]]|=1<<port;
			}
		}
		wetFacesPrepared[d] = true;
	}
	/** Fixed-open pores carry column weight across unrepresented thin dry layers.
	 * This changes the known hydrostatic load only; wet areas, capacities and C/CT stay physical. */
	private boolean unresolvedVerticalGap(int lower,int lowerMicro,int upper,int upperMicro,double x,double z) {
		double top=verticalLiquidLimit(lower,lowerMicro,x,z,true);
		double bottom=verticalLiquidLimit(upper,upperMicro,x,z,false);
		return 1-top+bottom<=FluidSolverConfig.UNRESOLVED_LAYER_FRACTION;
	}
	private double verticalLiquidLimit(int cell,int micro,double x,double z,boolean high) {
		var phase=domain.pressureSystem.phaseGeometry();
		if(!(phase.liquidFractionExact(cell,micro)>0))return Double.NaN;
		double low=(micro>>>1&1)*.5,upper=low+.5;
		if(phase.liquidFractionExact(cell,micro)>=1)return high?upper:low;
		double ny=phase.planeNormal(cell,micro,1);
		double offset=phase.planeOffset(cell,micro)-phase.planeNormal(cell,micro,0)*x-phase.planeNormal(cell,micro,2)*z;
		if(ny>0)upper=Math.min(upper,offset/ny);
		else if(ny<0)low=Math.max(low,offset/ny);
		else if(offset<0)return Double.NaN;
		return upper<low?Double.NaN:high?upper:low;
	}
	private boolean unresolvedWallGap(int cell,int support) {
		for(int micro=0;micro<8;micro++)if((micro&2)==0&&(support&1<<micro)!=0) {
			double x=(micro&1)*.5+.25,z=(micro>>>2&1)*.5+.25;
			if(verticalLiquidLimit(cell,micro,x,z,false)<=FluidSolverConfig.UNRESOLVED_LAYER_FRACTION)return true;
		}
		return false;
	}

	/** Column head follows liquid contact and the shared unresolved thin-gap support relation. */
	private void prepareHydrostaticReference(){
		Arrays.fill(groundedSupport,false);Arrays.fill(columnBottomPressure,0);Arrays.fill(hydrostaticReference,0);
		var cut=domain.geometry.cutCells;var phase=domain.pressureSystem.phaseGeometry();
		for(int z=0;z<domain.sizeZ;z++)for(int x=0;x<domain.sizeX;x++)for(int y=0;y<domain.sizeY;y++){
			int cell=domain.domainFlowFields.cellIndex(x,y,z),below=cell-domain.sizeX;
			int face=domain.domainFlowFields.yFaceIndex(x,y,z);
			for(int support:CellRemapMicroTopology.components(cut.mask(cell))){
				int slot=8*cell+Integer.numberOfTrailingZeros(support);if(!(supportFill[slot]>0))continue;
				boolean ground=false;
				if((support&0x33)==0)ground=true; // The lower half is fixed solid.
				else {
					if(y==0||domain.cellMacStage.isWallFace(1,face,null))
						ground=phase.liquidFaceArea(cell,1,0,cut)>0||unresolvedWallGap(cell,support);
					else for(int micro=0;micro<8;micro++)if((micro&2)==0&&(support&1<<micro)!=0){
						int port=(micro>>>2&1)+2*(micro&1);
						if((verticalSupportContact[face]&1<<port)==0)continue;
						int lower=CellRemapMicroTopology.support(cut.mask(below),micro|2);
						if(lower!=0&&groundedSupport[8*below+Integer.numberOfTrailingZeros(lower)])ground=true;
					}
				}
				groundedSupport[slot]=ground;
			}
		}
		for(int z=0;z<domain.sizeZ;z++)for(int x=0;x<domain.sizeX;x++)for(int y=domain.sizeY-1;y>=0;y--){
			int cell=domain.domainFlowFields.cellIndex(x,y,z),above=cell+domain.sizeX;
			int face=domain.domainFlowFields.yFaceIndex(x,y+1,z);
			for(int support:CellRemapMicroTopology.components(cut.mask(cell))){
				int slot=8*cell+Integer.numberOfTrailingZeros(support);if(!groundedSupport[slot])continue;
				double upperPressure=0,upperWeight=0;
				if(y+1<domain.sizeY)for(int micro=0;micro<8;micro++)if((micro&2)!=0&&(support&1<<micro)!=0){
					int port=(micro>>>2&1)+2*(micro&1);if((verticalSupportContact[face]&1<<port)==0)continue;
					int upper=CellRemapMicroTopology.support(cut.mask(above),micro^2);
					if(upper!=0){int next=8*above+Integer.numberOfTrailingZeros(upper);if(groundedSupport[next]){upperPressure+=columnBottomPressure[next];upperWeight++;}}
				}
				if(upperWeight>0)upperPressure/=upperWeight;
				double weight=domain.liquidGrid.cellDensity[cell]*domain.config.gravity()*domain.config.cellSize();
				hydrostaticReference[slot]=weight*supportDepth[slot]+upperPressure;
				columnBottomPressure[slot]=upperPressure+2*weight*supportDepth[slot];
			}
		}
	}
	/** A grounded column's dry normal face has no independent liquid motion. */
	boolean isGroundedDryNormalFace(int local){
		int face=offsets[1]+local,a=firstCell[face],b=secondCell[face];if(a<0)return false;
		for(int port=0;port<4;port++)if(wetPortArea[4*face+port]>0)return false;
		for(int side=0;side<2;side++){
			int cell=side==0?a:b;
			for(int support:CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell))){
				int slot=8*cell+Integer.numberOfTrailingZeros(support);
				if(supportFill[slot]>0&&groundedSupport[slot])return true;
			}
		}
		return false;
	}
	/** Vertical contact supports carry their own mass and hydrostatic support.
	 * Disconnected liquid elsewhere in a cut cell cannot accelerate this face. */
	double freeFallAcceleration(int face){
		int a=firstCell[face],b=secondCell[face];if(a<0)return 0;
		int maskA=domain.geometry.cutCells.mask(a),maskB=domain.geometry.cutCells.mask(b);
		if(maskA==0&&maskB==0){
			double ma=domain.liquidGrid.cellStockMass[a],mb=domain.liquidGrid.cellStockMass[b],mass=ma+mb;
			return mass>0?-domain.config.gravity()*((groundedSupport[8*a]?0:ma)+(groundedSupport[8*b]?0:mb))/mass:0;
		}
		int contacts=(~maskA&0xcc)&((~maskB&0x33)<<2);
		double mass=0,free=0;
		for(int side=0;side<2;side++){
			int cell=side==0?a:b,contact=side==0?contacts:contacts>>>2;
			for(var stock:domain.liquidCells.physicalStocksInCell(cell)){
				int support=stock.key().supportMask(),count=Integer.bitCount(support&contact);
				if(count==0)continue;
				double share=stock.massUnits()*(double)count/Integer.bitCount(support);
				mass+=share;if(!groundedSupport[8*cell+Integer.numberOfTrailingZeros(support)])free+=share;
			}
		}
		return mass>0?-domain.config.gravity()*free/mass:0;
	}
	/** Horizontal depth-integrated gravity force; the pressure solve handles only its residual. */
	double barotropicAcceleration(int face){
		int a=firstCell[face],b=secondCell[face],d=axis[face];if(a<0||d==1||!(faceMass(face)>0))return 0;
		prepareWetFaces(d);
		var cut=domain.geometry.cutCells;double load=0;int ports=0;
		for(int micro=0;micro<8;micro++)if((micro>>>d&1)!=0&&(cut.mask(a)&1<<micro)==0&&(cut.mask(b)&1<<(micro^(1<<d)))==0){
			ports++;int sa=CellRemapMicroTopology.support(cut.mask(a),micro),sb=CellRemapMicroTopology.support(cut.mask(b),micro^(1<<d));
			int port=(micro>>>((d+1)%3)&1)+2*(micro>>>((d+2)%3)&1);
			load+=wetPortArea[4*face+port]*(hydrostaticReference[8*a+Integer.numberOfTrailingZeros(sa)]-hydrostaticReference[8*b+Integer.numberOfTrailingZeros(sb)]);
		}
		if(ports==0)return 0;
		return domain.unitScale.cubicMetersPerNormalizedVolume()/faceMass(face)
				*domain.pressureSystem.solidFaces.open(d,localFace[face])/(.25*ports)*load/domain.config.cellSize();
	}
	private void add(int face, int row, double coefficient, double fixed) {
		affine[face] += coefficient * fixed;
		if (row < 0) return;
		for (int i = start[face]; i < entries; i++) if (rows[i] == row) { coefficients[i] += coefficient; return; }
		if (entries == rows.length) { rows = Arrays.copyOf(rows, entries * 2); coefficients = Arrays.copyOf(coefficients, rows.length); }
		rows[entries] = row; coefficients[entries++] = coefficient;
	}
	double pressurePortArea(int face,int port){return wetPortArea[4*face+port];}
	double fluidArea(int face){return domain.pressureSystem.solidFaces.open(axis[face],localFace[face]);}
	double faceMass(int f) {
		return switch (axis[f]) { case 0 -> domain.cellMacStage.massX[localFace[f]]; case 1 -> domain.cellMacStage.massY[localFace[f]]; default -> domain.cellMacStage.massZ[localFace[f]]; };
	}
	int rowAt(int entry) { return rows[entry]; }
	double coefficientAt(int entry) { return coefficients[entry]; }
	int root(int c) { while (parent[c] != c) { parent[c] = parent[parent[c]]; c = parent[c]; } return c; }
	private void union(int a, int b) { a = root(a); b = root(b); if (a != b) parent[Math.max(a, b)] = Math.min(a, b); }
}
