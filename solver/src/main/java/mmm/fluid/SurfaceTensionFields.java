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
	final double[] surfaceTensionPairCurvature;
	final double[] surfaceTensionPairFirstSupport;
	final double[] surfaceTensionNormalX;
	final double[] surfaceTensionNormalY;
	final double[] surfaceTensionNormalZ;

	SurfaceTensionFields(FluidDomain domain) {
		this.domain = domain;
		int cellCount = domain.cellCount;
		this.surfaceTensionPressure = new float[cellCount];
		this.surfaceTensionColor = new double[cellCount];
		this.surfaceTensionSmoothed = new double[cellCount];
		this.surfaceTensionPairCurvature = new double[cellCount];
		this.surfaceTensionPairFirstSupport = new double[cellCount];
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
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = domain.domainFlowFields.cellIndex(x, y, z);
			double sum = 0.0;
			int count = 0;
			for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				int sx = x + dx, sy = y + dy, sz = z + dz;
				if (!domain.domainFlowFields.inside(sx, sy, sz)) continue;
				int neighbor = domain.domainFlowFields.cellIndex(sx, sy, sz);
				if (domain.geometry.solid[neighbor]) continue;
				sum += surfaceTensionColor[neighbor];
				count++;
			}
			surfaceTensionSmoothed[index] = count == 0 ? surfaceTensionColor[index] : sum / count;
		}
	}
	double surfaceTensionCentralDifference(int x, int y, int z, int dx, int dy, int dz) {
		int xPlus = Math.min(domain.sizeX - 1, Math.max(0, x + dx));
		int xMinus = Math.min(domain.sizeX - 1, Math.max(0, x - dx));
		int yPlus = Math.min(domain.sizeY - 1, Math.max(0, y + dy));
		int yMinus = Math.min(domain.sizeY - 1, Math.max(0, y - dy));
		int zPlus = Math.min(domain.sizeZ - 1, Math.max(0, z + dz));
		int zMinus = Math.min(domain.sizeZ - 1, Math.max(0, z - dz));
		return 0.5 * (surfaceTensionSmoothed[domain.domainFlowFields.cellIndex(xPlus, yPlus, zPlus)]
				- surfaceTensionSmoothed[domain.domainFlowFields.cellIndex(xMinus, yMinus, zMinus)]);
	}
	void rebuildSurfaceTensionNormals() {
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = domain.domainFlowFields.cellIndex(x, y, z);
			if (domain.geometry.solid[index]) {
				surfaceTensionNormalX[index] = 0.0;
				surfaceTensionNormalY[index] = 0.0;
				surfaceTensionNormalZ[index] = 1.0;
				continue;
			}
			double gradientX = surfaceTensionCentralDifference(x, y, z, 1, 0, 0);
			double gradientY = surfaceTensionCentralDifference(x, y, z, 0, 1, 0);
			double gradientZ = surfaceTensionCentralDifference(x, y, z, 0, 0, 1);
			double length = Math.sqrt(gradientX * gradientX + gradientY * gradientY + gradientZ * gradientZ);
			if (!(length > 0.0)) {
				surfaceTensionNormalX[index] = 0.0;
				surfaceTensionNormalY[index] = 0.0;
				surfaceTensionNormalZ[index] = 1.0;
				continue;
			}
			double normalX = gradientX / length;
			double normalY = gradientY / length;
			double normalZ = gradientZ / length;

			// Young's condition belongs to the wall flux, not the measured cell normal.
			surfaceTensionNormalX[index] = normalX;
			surfaceTensionNormalY[index] = normalY;
			surfaceTensionNormalZ[index] = normalZ;
		}
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
		return smoothedSurfaceTensionCurvature(x, y, z, true);
	}
	double surfaceTensionMeanCurvature(int x, int y, int z) {
		return smoothedSurfaceTensionCurvature(x, y, z, false);
	}
	private double smoothedSurfaceTensionCurvature(int x, int y, int z, boolean liquidGasReflection) {
		double inverseTwoCell = 0.5 / domain.config.cellSize();
		double nxPlus = surfaceTensionNeighborNormal(x, y, z, 1, 0, 0, 0, liquidGasReflection);
		double nxMinus = surfaceTensionNeighborNormal(x, y, z, -1, 0, 0, 0, liquidGasReflection);
		double nyPlus = surfaceTensionNeighborNormal(x, y, z, 0, 1, 0, 1, liquidGasReflection);
		double nyMinus = surfaceTensionNeighborNormal(x, y, z, 0, -1, 0, 1, liquidGasReflection);
		double nzPlus = surfaceTensionNeighborNormal(x, y, z, 0, 0, 1, 2, liquidGasReflection);
		double nzMinus = surfaceTensionNeighborNormal(x, y, z, 0, 0, -1, 2, liquidGasReflection);
		return -((nxPlus - nxMinus) + (nyPlus - nyMinus) + (nzPlus - nzMinus)) * inverseTwoCell;
	}
	private double surfaceTensionNeighborNormal(int x, int y, int z, int dx, int dy, int dz, int component,
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
				double local = cachedSurfaceTensionNormal(target,component);
				boolean touching = fixedLiquidContact(target,x,y,z,axis,dx+dy+dz>0?1:0) > 0;
				double wallNormal = component == axis ? (touching ? (dx+dy+dz)*Math.cos(surfaceTensionContactAngleAtCell(target)) : 0) : local;
				double ghost = 2*wallNormal-local;
				return open*cachedSurfaceTensionNormal(sample,component)+(1-open)*ghost;
			}
		}
		if (domain.domainFlowFields.inside(nx, ny, nz) && domain.geometry.solid[domain.domainFlowFields.cellIndex(nx, ny, nz)]) {
			return cachedSurfaceTensionNormal(domain.domainFlowFields.cellIndex(x, y, z), component);
		}
		int sample = domain.domainFlowFields.cellIndex(Math.min(domain.sizeX - 1, Math.max(0, nx)),
				Math.min(domain.sizeY - 1, Math.max(0, ny)), Math.min(domain.sizeZ - 1, Math.max(0, nz)));
		return cachedSurfaceTensionNormal(sample, component);
	}
	private double cachedSurfaceTensionNormal(int cell, int component) {
		return switch (component) {
			case 0 -> surfaceTensionNormalX[cell];
			case 1 -> surfaceTensionNormalY[cell];
			default -> surfaceTensionNormalZ[cell];
		};
	}
}
