package mmm.fluid;

import java.util.Arrays;
import mmm.units.SignedDyadic;

/** Shared sparse volume incidence and its transpose for liquid capacity and finite gas storage. */
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
	private final int[] liquidStart;
	private int[] liquidLeftSupport, liquidRightSupport;
	private double[] liquidCoefficient;
	private int liquidEntries;
	private final double[] supportFill,supportDepth,wetPortArea,sideWetArea,columnBottomPressure,hydrostaticReference;
	private final boolean[] groundedSupport;
	private final int[] verticalLiquidContact;
	private FlipPressurePhaseGeometry supportGeometry;
	private long supportRevision=-1;
	private final int[] parent;
	private final boolean[] constrained, anchored;
	private final double[] seedPressureSum, seedPressureWeight;

	PressureFaceGraph(FluidDomain domain) {
		this.domain = domain;
		offsets[1] = domain.mac.velocityX.length; offsets[2] = offsets[1] + domain.mac.velocityY.length;
		faceCount = offsets[2] + domain.mac.velocityZ.length;
		firstCell = new int[faceCount]; secondCell = new int[faceCount]; axis = new int[faceCount]; localFace = new int[faceCount];
		start = new int[faceCount + 1]; liquidStart = new int[faceCount + 1];
		liquidLeftSupport = new int[4 * faceCount]; liquidRightSupport = new int[liquidLeftSupport.length];
		liquidCoefficient = new double[liquidLeftSupport.length]; inverseMass = new double[faceCount]; affine = new double[faceCount];
		pressureSupports = new int[domain.cellCount]; parent = new int[domain.cellCount];
		supportFill=new double[8*domain.cellCount];supportDepth=new double[supportFill.length];
		wetPortArea=new double[4*faceCount];sideWetArea=new double[8*faceCount];columnBottomPressure=new double[supportFill.length];hydrostaticReference=new double[supportFill.length];
		groundedSupport=new boolean[supportFill.length];verticalLiquidContact=new int[domain.mac.velocityY.length];
		constrained = new boolean[domain.cellCount]; anchored = constrained.clone();
		seedPressureSum = new double[domain.cellCount]; seedPressureWeight = new double[domain.cellCount];
		rows = new int[4 * faceCount]; coefficients = new double[rows.length];
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

	void classify() {
		prepareSupportGeometry();
		Arrays.fill(pressureSupports, 0);
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int mask = domain.geometry.cutCells.mask(cell); int[] components = CellRemapMicroTopology.components(mask);
			var capacities = CellRemapMicroTopology.capacities(mask, SignedDyadic.of(poreVolume(cell)));
			for (int j = 0; j < components.length; j++) {
				var volume = SignedDyadic.ZERO; long mass = 0;
				for (var stock : domain.liquidCells.stocksInCell(cell)) if (stock.key().supportMask() == components[j]) {
					volume = volume.add(stock.volume()); mass = Math.addExact(mass, stock.massUnits());
				}
				if (mass > 0 && capacities[j].signum() > 0 && !hasFiniteGas(cell, components[j])
						&& capacities[j].subtract(volume).toDouble() <= mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION)
					pressureSupports[cell] |= components[j];
			}
		}
		prepareHydrostaticReference();
		for(int cell=0;cell<domain.cellCount;cell++)domain.projection.pressureFluid[cell]=pressureSupports[cell]!=0;
	}
	private boolean hasFiniteGas(int cell, int support) {
		var frame = domain.gasState.frame;
		if (frame == null) return false;
		for (int bits = support; bits != 0; bits &= bits - 1) {
			int node = frame.nodeOfPiece(8 * cell + Integer.numberOfTrailingZeros(bits));
			if (node > 0 && domain.pressureBoundaryView.gasNodeHasContinuumState(node)) return true;
		}
		return false;
	}

	void rebuild(float dt) {
		classify();
		var p = domain.projection; int count = domain.gasState.network.nodeCount(); p.ensureGasCapacity(count);
		Arrays.fill(p.gasPressureUnknown, false); p.gasUnknownCount = p.fluidUnknownCount = 0;
		Arrays.fill(p.gasStorageCoefficient, 0); Arrays.fill(p.gasMatrixDiagonal, 0);
		if(gasPressureLoad.length<count+1)gasPressureLoad=new double[count+1];
		double head=domain.config.density()*domain.config.gravity()*domain.config.cellSize();
		Arrays.fill(gasPressureLoad,head);
		for (int node = 1; node <= count; node++) {
			p.gasPressureUnknown[node] = true; p.gasUnknownNodes[p.gasUnknownCount++] = node;
		}
		entries = liquidEntries = 0; Arrays.fill(inverseMass, 0); Arrays.fill(affine, 0);
		Arrays.fill(constrained, false); Arrays.fill(anchored, false);
		for (int c = 0; c < parent.length; c++) parent[c] = c;
		for (int f = 0; f < faceCount; f++) {
			start[f] = entries; liquidStart[f] = liquidEntries; int a = firstCell[f], b = secondCell[f]; if (a < 0) continue;
			double mass = faceMass(f); if (!(mass > 0)) continue;
			int d = axis[f]; boolean wallSupported=domain.cellMacStage.isUnresolvedWallFace(d,localFace[f]);
			double open = domain.pressureSystem.solidFaces.open(d, localFace[f]);
			if (!(open > 0)) continue;
			if(!wallSupported)inverseMass[f] = domain.unitScale.cubicMetersPerNormalizedVolume() / mass;
			final int face = f; double coefficient = open / domain.config.cellSize();
			int maskA = domain.geometry.cutCells.mask(a), maskB = domain.geometry.cutCells.mask(b), ports = 0;
			for (int micro = 0; micro < 8; micro++) if ((micro >>> d & 1) != 0
					&& (maskA & 1 << micro) == 0 && (maskB & 1 << (micro ^ 1 << d)) == 0) ports++;

			for (int micro = 0; micro < 8; micro++) if ((micro >>> d & 1) != 0
					&& (maskA & 1 << micro) == 0 && (maskB & 1 << (micro ^ 1 << d)) == 0) {
				int peer = micro ^ 1 << d;
				int supportA = CellRemapMicroTopology.support(maskA, micro), supportB = CellRemapMicroTopology.support(maskB, peer);
				int port=(micro>>>((d+1)%3)&1)+2*(micro>>>((d+2)%3)&1);
				double portArea = wetPortArea[4*f+port]*(open/(.25*ports));
				addLiquidPort(f, supportA, supportB, open / ports);
				if(wallSupported||!(portArea>0))continue;
				pressureAtSide(a,b,micro,peer,d,1,(row,weight,fixed)->add(face,row,portArea/domain.config.cellSize()*weight,fixed));
				pressureAtSide(b,a,peer,micro,d,1,(row,weight,fixed)->add(face,row,-portArea/domain.config.cellSize()*weight,fixed));
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
					liquidLoad=Math.max(liquidLoad,p.pressure[row]+head);
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
		start[faceCount] = entries; liquidStart[faceCount] = liquidEntries;
		for (int c = 0; c < parent.length; c++) if (constrained[c]) constrained[root(c)] = true;
		for (int c = 0; c < parent.length; c++) if (domain.domainFlowFields.isFluid(c)) {
			int group = root(c);
			if (!constrained[group] && !anchored[group]) { anchored[group] = true; anchored[c] = true; }
			else p.fluidUnknownCells[p.fluidUnknownCount++] = c;
		}
		for (int i = 0; i < entries; i++) if (rows[i] < domain.cellCount && anchored[rows[i]]) rows[i] = -1;
		domain.pressureAnchor = -1; domain.pressureReference = 0;
	}
	/** One face may contact several isolated gas pieces; weights follow its actual open microfaces. */
	void forEachSide(int cell, int neighbor, SideConsumer consumer) {
		int d = cell % domain.sizeX != neighbor % domain.sizeX ? 0
				: cell / domain.sizeX % domain.sizeY != neighbor / domain.sizeX % domain.sizeY ? 1 : 2;
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
			if(weight>0)pressureAtSide(cell,neighbor,micro,micro^(1<<d),d,weight,consumer);
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
		int node = domain.gasState.frame.nodeOfPiece(8 * cell + micro);
		if (node >= 0) { gasSide(cell,micro,axis,node,weight,capillary,hydroFraction,consumer); return; }
		int support = CellRemapMicroTopology.support(domain.geometry.cutCells.mask(cell), micro); double total = 0;
		for (int bit = 0; bit < 8; bit++) if ((support & 1 << bit) != 0 && domain.gasState.frame.nodeOfPiece(8 * cell + bit) >= 0)
			total += domain.gasState.frame.geometry().piece(8 * cell + bit).normalizedVolume();
		if (!(total > 0)) { consumer.accept(anchored[cell] ? -1 : cell, weight, capillary); return; }
		for (int bit = 0; bit < 8; bit++) if ((support & 1 << bit) != 0) {
			int owner = domain.gasState.frame.nodeOfPiece(8 * cell + bit); if (owner < 0) continue;
			gasSide(cell,micro,axis,owner,weight*domain.gasState.frame.geometry().piece(8*cell+bit).normalizedVolume()/total,capillary,hydroFraction,consumer);
		}
	}
	private double poreVolume(int cell) {
		return domain.geometry.cutCells.openCellVolume(cell) - (domain.granularCoupling == null ? 0
				: Math.scalb((double) domain.granularCoupling.solidSvq(cell), -24));
	}

	private void addLiquidPort(int face, int supportA, int supportB, double area) {
		for (int i = liquidStart[face]; i < liquidEntries; i++) if (liquidLeftSupport[i] == supportA && liquidRightSupport[i] == supportB) {
			liquidCoefficient[i] += area; return;
		}
		if (liquidEntries == liquidCoefficient.length) {
			liquidLeftSupport = Arrays.copyOf(liquidLeftSupport, 2 * liquidEntries);
			liquidRightSupport = Arrays.copyOf(liquidRightSupport, 2 * liquidEntries);
			liquidCoefficient = Arrays.copyOf(liquidCoefficient, 2 * liquidEntries);
		}
		liquidLeftSupport[liquidEntries] = supportA; liquidRightSupport[liquidEntries] = supportB;
		liquidCoefficient[liquidEntries++] = area;
	}
	double totalPortCoefficient(int cell, int neighbor, int d, int sourceSupport, int targetSupport) {
		if (neighbor < cell) { int swap = cell; cell = neighbor; neighbor = swap; swap = sourceSupport; sourceSupport = targetSupport; targetSupport = swap; }
		int x = neighbor % domain.sizeX, y = neighbor / domain.sizeX % domain.sizeY, z = neighbor / (domain.sizeX * domain.sizeY);
		int face = offsets[d] + (z * (domain.sizeY + (d == 1 ? 1 : 0)) + y) * (domain.sizeX + (d == 0 ? 1 : 0)) + x;
		for (int i = liquidStart[face]; i < liquidStart[face + 1]; i++)
			if (liquidLeftSupport[i] == sourceSupport && liquidRightSupport[i] == targetSupport) return liquidCoefficient[i];
		return 0;
	}

	private void gasSide(int cell,int micro,int axis,int node,double weight,double capillary,double hydroFraction,SideConsumer consumer) {
		boolean variable = node > 0 && domain.projection.gasPressureUnknown[node];
		double fixed = variable || node == 0 ? 0 : domain.pressureBoundaryView.gasPressureOfNode(node);
		int support=CellRemapMicroTopology.support(domain.geometry.cutCells.mask(cell),micro);
		int slot=8*cell+Integer.numberOfTrailingZeros(support);
		double hydrostatic=domain.liquidGrid.cellDensity[cell]*domain.config.gravity()*domain.config.cellSize()*supportDepth[slot]*hydroFraction;
		consumer.accept(variable ? domain.cellCount + node : -1, weight, fixed+capillary+hydrostatic);
	}
	/** Coarse inventory wet height, shared by pressure publication and face loading. */
	double hydrostaticOffset(int cell,int support){
		prepareSupportGeometry();double volume=0,depth=0;
		for(int component:CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell)))if((component&support)!=0){
			int slot=8*cell+Integer.numberOfTrailingZeros(component);double wet=supportFill[slot]*Integer.bitCount(component);
			volume+=wet;depth+=wet*supportDepth[slot];
		}
		return volume>0?domain.liquidGrid.cellDensity[cell]*domain.config.gravity()*domain.config.cellSize()*depth/volume:0;
	}
	private void prepareSupportGeometry(){
		var phase=domain.pressurePhaseGeometry;
		if(supportGeometry==phase&&supportRevision==phase.revision())return;
		Arrays.fill(supportFill,0);Arrays.fill(supportDepth,0);
		for(int cell=0;cell<domain.cellCount;cell++)for(int support:CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell))){
			double wet=0;
			for(int bits=support;bits!=0;bits&=bits-1){
				int micro=Integer.numberOfTrailingZeros(bits);wet+=.125*phase.liquidFractionExact(cell,micro);
			}
			int slot=8*cell+Integer.numberOfTrailingZeros(support);
			supportFill[slot]=Math.clamp(wet/(.125*Integer.bitCount(support)),0,1);
			double height=(support&0x33)!=0&&(support&0xcc)!=0?1:.5;
			supportDepth[slot]=.5*height*supportFill[slot];
		}
		prepareWetFaces();
		supportGeometry=phase;supportRevision=phase.revision();
	}
	private void prepareWetFaces(){
		var phase=domain.pressurePhaseGeometry;var cut=domain.geometry.cutCells;
		Arrays.fill(wetPortArea,0);Arrays.fill(sideWetArea,0);Arrays.fill(verticalLiquidContact,0);
		for(int face=0;face<faceCount;face++){
			int left=firstCell[face],right=secondCell[face],d=axis[face];if(left<0)continue;
			for(int a=0;a<2;a++)for(int b=0;b<2;b++){
				int port=a+2*b;
				int minus=phase.microAtFace(left,d,1,.5*a,.5*b),plus=phase.microAtFace(right,d,0,.5*a,.5*b);
				if((cut.mask(left)&1<<minus)!=0||(cut.mask(right)&1<<plus)!=0)continue;
				double leftArea=phase.liquidPortArea(left,d,1,a,b,cut),rightArea=phase.liquidPortArea(right,d,0,a,b,cut);
				double shared=phase.sharedLiquidPortArea(left,right,d,a,b,cut);
				sideWetArea[8*face+2*port]=leftArea;sideWetArea[8*face+2*port+1]=rightArea;
				wetPortArea[4*face+port]=Math.clamp(leftArea+rightArea-shared,0,.25);
				if(d==1&&shared>0)verticalLiquidContact[localFace[face]]|=1<<port;
			}
		}
	}
	/** A wall-supported vertical column uses the same coarse pressure head and contact area as C/CT. */
	private void prepareHydrostaticReference(){
		Arrays.fill(groundedSupport,false);Arrays.fill(columnBottomPressure,0);Arrays.fill(hydrostaticReference,0);
		var cut=domain.geometry.cutCells;var phase=domain.pressurePhaseGeometry;
		for(int z=0;z<domain.sizeZ;z++)for(int x=0;x<domain.sizeX;x++)for(int y=0;y<domain.sizeY;y++){
			int cell=domain.domainFlowFields.cellIndex(x,y,z),below=cell-domain.sizeX;
			int face=domain.domainFlowFields.yFaceIndex(x,y,z);
			for(int support:CellRemapMicroTopology.components(cut.mask(cell))){
				int slot=8*cell+Integer.numberOfTrailingZeros(support);if(!(supportFill[slot]>0))continue;
				boolean ground=false;
				if((support&0x33)==0)ground=true; // The lower half is fixed solid.
				else if(phase.liquidFaceArea(cell,1,0,cut)>0){
					if(y==0||domain.cellMacStage.isWallFace(1,face,null))ground=true;
					else for(int micro=0;micro<8;micro++)if((micro&2)==0&&(support&1<<micro)!=0){
						int port=(micro>>>2&1)+2*(micro&1);
						if((verticalLiquidContact[face]&1<<port)==0)continue;
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
					int port=(micro>>>2&1)+2*(micro&1);if((verticalLiquidContact[face]&1<<port)==0)continue;
					int upper=CellRemapMicroTopology.support(cut.mask(above),micro^2);
					if(upper!=0){int next=8*above+Integer.numberOfTrailingZeros(upper);if(groundedSupport[next]){upperPressure+=columnBottomPressure[next];upperWeight++;}}
				}
				if(upperWeight>0)upperPressure/=upperWeight;
				// A continuous supported liquid column carries its overburden through
				// the common pressure row. Ambient subcell voids retain real capacity
				// without turning an interior liquid layer into a second free surface.
				if(upperWeight>0&&domain.liquidGrid.cellStockMass[cell]>0&&!hasFiniteGas(cell,support))
					pressureSupports[cell]|=support;
				double weight=domain.liquidGrid.cellDensity[cell]*domain.config.gravity()*domain.config.cellSize();
				hydrostaticReference[slot]=weight*supportDepth[slot]+((pressureSupports[cell]&support)==support?upperPressure:0);
				columnBottomPressure[slot]=upperPressure+2*weight*supportDepth[slot];
			}
		}
	}
	/** Downward acceleration of a grounded column, or ordinary free fall for airborne liquid. */
	double gravityAcceleration(int face){
		prepareSupportGeometry();int a=firstCell[face],b=secondCell[face];if(a<0)return 0;
		var cut=domain.geometry.cutCells;double load=0;boolean supported=false;
		int ports=0;for(int micro=0;micro<8;micro++)if((micro&2)!=0&&(cut.mask(a)&1<<micro)==0&&(cut.mask(b)&1<<(micro^2))==0)ports++;
		if(ports==0)return 0;
		for(int micro=0;micro<8;micro++)if((micro&2)!=0&&(cut.mask(a)&1<<micro)==0&&(cut.mask(b)&1<<(micro^2))==0){
			int sa=CellRemapMicroTopology.support(cut.mask(a),micro),sb=CellRemapMicroTopology.support(cut.mask(b),micro^2);
			int ia=8*a+Integer.numberOfTrailingZeros(sa),ib=8*b+Integer.numberOfTrailingZeros(sb);
			if(!groundedSupport[ia]&&!groundedSupport[ib])continue;
			supported=true;int port=(micro>>>2&1)+2*(micro&1);
			double leftArea=(pressureSupports[a]&sa)==sa?wetPortArea[4*face+port]:sideWetArea[8*face+2*port];
			double rightArea=(pressureSupports[b]&sb)==sb?wetPortArea[4*face+port]:sideWetArea[8*face+2*port+1];
			load+=leftArea*hydrostaticReference[ia]-rightArea*hydrostaticReference[ib];
		}
		if(!supported)return -domain.config.gravity();
		double mass=faceMass(face);if(!(mass>0))return 0;
		double open=domain.pressureSystem.solidFaces.open(1,localFace[face]);
		return -domain.unitScale.cubicMetersPerNormalizedVolume()/mass*open/(.25*ports)*load/domain.config.cellSize();
	}
	private void add(int face, int row, double coefficient, double fixed) {
		affine[face] += coefficient * fixed;
		if (row < 0) return;
		for (int i = start[face]; i < entries; i++) if (rows[i] == row) { coefficients[i] += coefficient; return; }
		if (entries == rows.length) { rows = Arrays.copyOf(rows, entries * 2); coefficients = Arrays.copyOf(coefficients, rows.length); }
		rows[entries] = row; coefficients[entries++] = coefficient;
	}
	double fluidArea(int face){return domain.pressureSystem.solidFaces.open(axis[face],localFace[face]);}
	/** Seed each connected full-liquid volume with its contacting cavities' mean pressure. */
	void seedLiquidPressures(double[] pressure){
		Arrays.fill(seedPressureSum,0);Arrays.fill(seedPressureWeight,0);
		for(int face=0;face<faceCount;face++){
			double total=0,value=0;
			for(int i=start[face];i<start[face+1];i++)if(rows[i]>=domain.cellCount){
				double weight=Math.abs(coefficients[i])*domain.projection.gasStorageCoefficient[rows[i]-domain.cellCount];
				total+=weight;value+=weight*pressure[rows[i]];
			}
			if(total==0)continue;
			for(int i=start[face];i<start[face+1];i++)if(rows[i]>=0&&rows[i]<domain.cellCount){
				int group=root(rows[i]);double weight=Math.abs(coefficients[i])*total;
				seedPressureSum[group]+=weight*(value/total);seedPressureWeight[group]+=weight;
			}
		}
		// A uniform cavity pressure is already a static solution throughout a sealed
		// liquid component. Seeding only its surface cells creates an artificial
		// pressure front whose residual flow can subsequently dissipate into heat.
		for(int i=0;i<domain.projection.fluidUnknownCount;i++){
			int cell=domain.projection.fluidUnknownCells[i],group=root(cell);
			if(seedPressureWeight[group]>0)pressure[cell]=seedPressureSum[group]/seedPressureWeight[group];
		}
	}
	void pressureVelocity(double[] pressure, double[] velocity) {
		for (int f = 0; f < faceCount; f++) {
			double value = 0; for (int i = start[f]; i < start[f + 1]; i++) if (rows[i] >= 0) value += coefficients[i] * pressure[rows[i]];
			velocity[f] = inverseMass[f] * value;
		}
	}
	void addDivergence(double[] velocity, double[] result, double factor) {
		for (int f = 0; f < faceCount; f++) for (int i = start[f]; i < start[f + 1]; i++)
			if (rows[i] >= 0) result[rows[i]] += factor * coefficients[i] * velocity[f];
	}
	double faceMass(int f) {
		return switch (axis[f]) { case 0 -> domain.cellMacStage.massX[localFace[f]]; case 1 -> domain.cellMacStage.massY[localFace[f]]; default -> domain.cellMacStage.massZ[localFace[f]]; };
	}
	int rowAt(int entry) { return rows[entry]; }
	double coefficientAt(int entry) { return coefficients[entry]; }
	private int root(int c) { while (parent[c] != c) { parent[c] = parent[parent[c]]; c = parent[c]; } return c; }
	private void union(int a, int b) { a = root(a); b = root(b); if (a != b) parent[Math.max(a, b)] = Math.min(a, b); }
}
