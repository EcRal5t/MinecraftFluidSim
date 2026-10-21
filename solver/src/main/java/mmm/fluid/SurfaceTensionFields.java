package mmm.fluid;

import java.util.Arrays;

/**
 * Owns liquid-gas and liquid-pair interface fields, curvature and contact-angle
 * conditions. Local normals follow the volume gradient; the prescribed angle
 * enters once through the fixed-wall normal flux used by the curvature stencil.
 * All fields are rebuilt from committed inventory and geometry.
 */
final class SurfaceTensionFields {
	private final FluidDomain domain;
	final double[] columnHeights = new double[9];
	final float[] surfaceTensionPressure;
	final double[] surfaceTensionColor;
	final double[] surfaceTensionSmoothed;
	final double[] surfaceTensionNormalX;
	final double[] surfaceTensionNormalY;
	final double[] surfaceTensionNormalZ;
	private PairInterface pair;
	/** Pair preparation has independent scratch storage, allocated only for an active liquid pair. */
	private static final class PairInterface {
		final double[] color, first, second, normalX, normalY, normalZ, curvature;
		PairInterface(int count){
			color=new double[count];first=new double[count];second=new double[count];
			normalX=new double[count];normalY=new double[count];normalZ=new double[count];curvature=new double[count];
		}
	}

	SurfaceTensionFields(FluidDomain domain) {
		this.domain = domain;
		int cellCount = domain.cellCount;
		this.surfaceTensionPressure = new float[cellCount];
		this.surfaceTensionColor = new double[cellCount];
		this.surfaceTensionSmoothed = new double[cellCount];
		this.surfaceTensionNormalX = new double[cellCount];
		this.surfaceTensionNormalY = new double[cellCount];
		this.surfaceTensionNormalZ = new double[cellCount];
	}
	boolean hasAnySurfaceTension() {
		if (domain.materialRegistry == null) return false;
		for (FluidMaterial material : domain.materialRegistry.materials()) {
			if (material.surfaceTension() > 0.0) return true;
		}
		return false;
	}
	boolean hasAnyLiquidPairTension() {
		if (domain.materialRegistry == null) return false;
		for (double tension : domain.materialRegistry.liquidPairTensions().values()) {
			if (tension > 0.0) return true;
		}
		return false;
	}
	double surfaceTensionAtCell(int cell) {
		if (domain.materialRegistry == null) return 0.0;
		int phase = domain.domainFlowFields.dominantPhase(cell);
		if (phase == FluidDomain.NO_PHASE) return 0.0;
		for (int handle = 0; handle < domain.materialRegistry.size(); handle++) {
			if (domain.materialRegistry.phaseHandle(handle) == phase) {
				return domain.materialRegistry.material(handle).surfaceTension();
			}
		}
		return 0.0;
	}
	void rebuildSurfaceTensionPressure() {
		Arrays.fill(surfaceTensionPressure, 0.0F);
		if (!domain.surfaceTensionActive) return;
		for (int index = 0; index < domain.cellCount; index++) {
			if (domain.geometry.solid[index]) {
				surfaceTensionColor[index] = 0.0;
				continue;
			}
			double openVolume = domain.domainCollision.effectiveOpenCellVolume(index);
			surfaceTensionColor[index] = openVolume > 0.0
					? Math.clamp(domain.liquidGrid.cellStockVolume[index] / openVolume, 0.0, 1.0)
					: 0.0;
		}
		smoothSurfaceTensionColor();
		rebuildSurfaceTensionNormals();
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = domain.domainFlowFields.cellIndex(x, y, z);
			if (domain.geometry.solid[index] || domain.liquidGrid.stockCount[index] == 0) continue;
			double sigma = surfaceTensionAtCell(index);
			if (sigma <= 0.0) continue;
			double kappa = liquidGasMeanCurvature(x, y, z);
			surfaceTensionPressure[index] = (float) (sigma * kappa);
		}
	}
	void smoothSurfaceTensionColor() {
		smooth(surfaceTensionColor,surfaceTensionSmoothed);
	}
	private void smooth(double[] color,double[] smoothed) {
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = domain.domainFlowFields.cellIndex(x, y, z);
			double sum = 0.0;
			int count = 0;
			for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				int sx = x + dx, sy = y + dy, sz = z + dz;
				if (!domain.domainFlowFields.inside(sx, sy, sz)) continue;
				int neighbor = domain.domainFlowFields.cellIndex(sx, sy, sz);
				if (domain.geometry.solid[neighbor]) continue;
				sum += color[neighbor];
				count++;
			}
			smoothed[index] = count == 0 ? color[index] : sum / count;
		}
	}
	private double centralDifference(double[] color,double[] companion,int x,int y,int z,int dx,int dy,int dz) {
		int xPlus = Math.min(domain.sizeX - 1, Math.max(0, x + dx));
		int xMinus = Math.min(domain.sizeX - 1, Math.max(0, x - dx));
		int yPlus = Math.min(domain.sizeY - 1, Math.max(0, y + dy));
		int yMinus = Math.min(domain.sizeY - 1, Math.max(0, y - dy));
		int zPlus = Math.min(domain.sizeZ - 1, Math.max(0, z + dz));
		int zMinus = Math.min(domain.sizeZ - 1, Math.max(0, z - dz));
		int plus=domain.domainFlowFields.cellIndex(xPlus,yPlus,zPlus),minus=domain.domainFlowFields.cellIndex(xMinus,yMinus,zMinus);
		return companion==null ? color[plus]-color[minus] : companion[minus]*color[plus]-companion[plus]*color[minus];
	}
	void rebuildSurfaceTensionNormals() {
		rebuildNormals(surfaceTensionSmoothed,null,surfaceTensionNormalX,surfaceTensionNormalY,surfaceTensionNormalZ);
	}
	private void rebuildNormals(double[] color,double[] companion,double[] normalX,double[] normalY,double[] normalZ) {
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = domain.domainFlowFields.cellIndex(x, y, z);
			if (domain.geometry.solid[index]) {
				normalX[index] = normalY[index] = 0.0;
				normalZ[index] = 1.0;
				continue;
			}
			double gradientX = centralDifference(color,companion,x,y,z,1,0,0);
			double gradientY = centralDifference(color,companion,x,y,z,0,1,0);
			double gradientZ = centralDifference(color,companion,x,y,z,0,0,1);
			double length = Math.sqrt(gradientX * gradientX + gradientY * gradientY + gradientZ * gradientZ);
			if (!(length > 0.0)) {
				normalX[index] = normalY[index] = 0.0;
				normalZ[index] = 1.0;
				continue;
			}
			// Young's condition belongs to the wall flux, not the measured cell normal.
			normalX[index] = gradientX/length;
			normalY[index] = gradientY/length;
			normalZ[index] = gradientZ/length;
		}
	}
	void prepareLiquidPair(int firstPhase,int secondPhase){
		if(pair==null)pair=new PairInterface(domain.cellCount);
		for(int cell=0;cell<domain.cellCount;cell++)pair.color[cell]=domain.geometry.solid[cell]?0:domain.domainFlowFields.phaseFraction(cell,firstPhase);
		smooth(pair.color,pair.first);
		for(int cell=0;cell<domain.cellCount;cell++)pair.color[cell]=domain.geometry.solid[cell]?0:domain.domainFlowFields.phaseFraction(cell,secondPhase);
		smooth(pair.color,pair.second);
		rebuildNormals(pair.second,pair.first,pair.normalX,pair.normalY,pair.normalZ);
		Arrays.fill(pair.curvature,0);
		for(int z=0;z<domain.sizeZ;z++)for(int y=0;y<domain.sizeY;y++)for(int x=0;x<domain.sizeX;x++){
			int cell=domain.domainFlowFields.cellIndex(x,y,z);
			if(!domain.geometry.solid[cell]&&domain.liquidGrid.stockCount[cell]>0)
				pair.curvature[cell]=curvature(x,y,z,false,pair.normalX,pair.normalY,pair.normalZ);
		}
	}
	/** Multiphase CSF pressure load: sigma kappa (alpha_A grad alpha_B - alpha_B grad alpha_A). */
	double liquidPairPressureDifference(int first,int second,double sigma){
		double gradient=pair.first[first]*pair.second[second]-pair.first[second]*pair.second[first];
		return sigma*.5*(pair.curvature[first]+pair.curvature[second])*gradient;
	}
	private double fixedLiquidContact(int cell,int x,int y,int z,int axis,int side) {
		var cut = domain.geometry.cutCells;
		double open = axis == 0 ? cut.xFaceOpen(x+side,y,z)
				: axis == 1 ? cut.yFaceOpen(x,y+side,z) : cut.zFaceOpen(x,y,z+side);
		if (open >= 1 || domain.pressurePhaseGeometry == null) return 0;
		return (1-open)*domain.pressurePhaseGeometry.liquidFaceArea(cell,axis,side,cut);
	}
	double surfaceTensionContactAngleAtCell(int cell) {
		if (domain.materialRegistry == null) return Math.PI / 2.0;
		int phase = domain.domainFlowFields.dominantPhase(cell);
		if (phase == FluidDomain.NO_PHASE) return Math.PI / 2.0;
		for (int handle = 0; handle < domain.materialRegistry.size(); handle++) {
			if (domain.materialRegistry.phaseHandle(handle) == phase) {
				return domain.materialRegistry.material(handle).contactAngleRadians();
			}
		}
		return Math.PI / 2.0;
	}
	double liquidGasMeanCurvature(int x, int y, int z) {
		double heightCurvature = HeightFunctionCurvature.compute(domain, x, y, z);
		if (Double.isFinite(heightCurvature)) return heightCurvature;
		return curvature(x,y,z,true,surfaceTensionNormalX,surfaceTensionNormalY,surfaceTensionNormalZ);
	}
	double surfaceTensionMeanCurvature(int x, int y, int z) {
		return curvature(x,y,z,false,surfaceTensionNormalX,surfaceTensionNormalY,surfaceTensionNormalZ);
	}
	private double curvature(int x,int y,int z,boolean liquidGasReflection,double[] normalX,double[] normalY,double[] normalZ) {
		double inverseTwoCell = 0.5 / domain.config.cellSize();
		double nxPlus = neighborNormal(normalX,x,y,z,1,0,0,0,liquidGasReflection);
		double nxMinus = neighborNormal(normalX,x,y,z,-1,0,0,0,liquidGasReflection);
		double nyPlus = neighborNormal(normalY,x,y,z,0,1,0,1,liquidGasReflection);
		double nyMinus = neighborNormal(normalY,x,y,z,0,-1,0,1,liquidGasReflection);
		double nzPlus = neighborNormal(normalZ,x,y,z,0,0,1,2,liquidGasReflection);
		double nzMinus = neighborNormal(normalZ,x,y,z,0,0,-1,2,liquidGasReflection);
		return -((nxPlus - nxMinus) + (nyPlus - nyMinus) + (nzPlus - nzMinus)) * inverseTwoCell;
	}
	private double neighborNormal(double[] normal,int x,int y,int z,int dx,int dy,int dz,int component,
			boolean liquidGasReflection) {
		int nx = x + dx, ny = y + dy, nz = z + dz;
		if (liquidGasReflection) {
			int target = domain.domainFlowFields.cellIndex(x,y,z), axis = dx != 0 ? 0 : dy != 0 ? 1 : 2;
			double open = axis == 0 ? domain.geometry.cutCells.xFaceOpen(x+(dx>0?1:0),y,z)
					: axis == 1 ? domain.geometry.cutCells.yFaceOpen(x,y+(dy>0?1:0),z)
					: domain.geometry.cutCells.zFaceOpen(x,y,z+(dz>0?1:0));
			if (!domain.domainFlowFields.inside(nx,ny,nz)) open = 0;
			if (open < 1) {
				int sample = domain.domainFlowFields.cellIndex(Math.clamp(nx,0,domain.sizeX-1),Math.clamp(ny,0,domain.sizeY-1),Math.clamp(nz,0,domain.sizeZ-1));
				double local = normal[target];
				boolean touching = fixedLiquidContact(target,x,y,z,axis,dx+dy+dz>0?1:0) > 0;
				double wallNormal = component == axis ? (touching ? (dx+dy+dz)*Math.cos(surfaceTensionContactAngleAtCell(target)) : 0) : local;
				double ghost = 2*wallNormal-local;
				return open*normal[sample]+(1-open)*ghost;
			}
		}
		if (domain.domainFlowFields.inside(nx, ny, nz) && domain.geometry.solid[domain.domainFlowFields.cellIndex(nx, ny, nz)]) {
			return normal[domain.domainFlowFields.cellIndex(x,y,z)];
		}
		int sample = domain.domainFlowFields.cellIndex(Math.min(domain.sizeX - 1, Math.max(0, nx)),
				Math.min(domain.sizeY - 1, Math.max(0, ny)), Math.min(domain.sizeZ - 1, Math.max(0, nz)));
		return normal[sample];
	}
}
