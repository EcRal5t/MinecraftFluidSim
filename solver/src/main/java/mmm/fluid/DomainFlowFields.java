package mmm.fluid;

import java.util.List;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import static mmm.fluid.FluidDomain.*;

/** Rebuilds the liquid inventory grid, shared geometry, velocity and surface-tension fields. */
final class DomainFlowFields {
	private final FluidDomain domain;
	DomainFlowFields(FluidDomain domain) { this.domain = domain; }
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
		int phase = dominantPhase(cell);
		if (phase == FluidDomain.NO_PHASE) return 0.0;
		for (int handle = 0; handle < domain.materialRegistry.size(); handle++) {
			if (domain.materialRegistry.phaseHandle(handle) == phase) {
				return domain.materialRegistry.material(handle).surfaceTension();
			}
		}
		return 0.0;
	}
	void rebuildSurfaceTensionPressure() {
		Arrays.fill(domain.tension.surfaceTensionPressure, 0.0F);
		if (!domain.surfaceTensionActive) return;
		for (int index = 0; index < domain.cellCount; index++) {
			if (domain.geometry.solid[index]) {
				domain.tension.surfaceTensionColor[index] = 0.0;
				continue;
			}
			double openVolume = domain.domainCollision.effectiveOpenCellVolume(index);
			domain.tension.surfaceTensionColor[index] = openVolume > 0.0
					? Math.clamp(domain.liquidGrid.cellStockVolume[index] / openVolume, 0.0, 1.0)
					: 0.0;
		}
		smoothSurfaceTensionColor();
		rebuildSurfaceTensionNormals();
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (domain.geometry.solid[index] || domain.liquidGrid.stockCount[index] == 0) continue;
			double sigma = surfaceTensionAtCell(index);
			if (sigma <= 0.0) continue;
			double kappa = liquidGasMeanCurvature(x, y, z);
			domain.tension.surfaceTensionPressure[index] = (float) (sigma * kappa);
		}
	}
	void smoothSurfaceTensionColor() {
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = cellIndex(x, y, z);
			double sum = 0.0;
			int count = 0;
			for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				int sx = x + dx, sy = y + dy, sz = z + dz;
				if (!inside(sx, sy, sz)) continue;
				int neighbor = cellIndex(sx, sy, sz);
				if (domain.geometry.solid[neighbor]) continue;
				sum += domain.tension.surfaceTensionColor[neighbor];
				count++;
			}
			domain.tension.surfaceTensionSmoothed[index] = count == 0 ? domain.tension.surfaceTensionColor[index] : sum / count;
		}
	}
	double surfaceTensionCentralDifference(int x, int y, int z, int dx, int dy, int dz) {
		int xPlus = Math.min(domain.sizeX - 1, Math.max(0, x + dx));
		int xMinus = Math.min(domain.sizeX - 1, Math.max(0, x - dx));
		int yPlus = Math.min(domain.sizeY - 1, Math.max(0, y + dy));
		int yMinus = Math.min(domain.sizeY - 1, Math.max(0, y - dy));
		int zPlus = Math.min(domain.sizeZ - 1, Math.max(0, z + dz));
		int zMinus = Math.min(domain.sizeZ - 1, Math.max(0, z - dz));
		return 0.5 * (domain.tension.surfaceTensionSmoothed[cellIndex(xPlus, yPlus, zPlus)]
				- domain.tension.surfaceTensionSmoothed[cellIndex(xMinus, yMinus, zMinus)]);
	}
	void rebuildSurfaceTensionNormals() {
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (domain.geometry.solid[index]) {
				domain.tension.surfaceTensionNormalX[index] = 0.0;
				domain.tension.surfaceTensionNormalY[index] = 0.0;
				domain.tension.surfaceTensionNormalZ[index] = 1.0;
				continue;
			}
			double gradientX = surfaceTensionCentralDifference(x, y, z, 1, 0, 0);
			double gradientY = surfaceTensionCentralDifference(x, y, z, 0, 1, 0);
			double gradientZ = surfaceTensionCentralDifference(x, y, z, 0, 0, 1);
			double length = Math.sqrt(gradientX * gradientX + gradientY * gradientY + gradientZ * gradientZ);
			if (!(length > 0.0)) {
				domain.tension.surfaceTensionNormalX[index] = 0.0;
				domain.tension.surfaceTensionNormalY[index] = 0.0;
				domain.tension.surfaceTensionNormalZ[index] = 1.0;
				continue;
			}
			double normalX = gradientX / length;
			double normalY = gradientY / length;
			double normalZ = gradientZ / length;
			if (domain.liquidGrid.stockCount[index] > 0) {
				// Fixed-face area differences point from the wall into the open fluid.
				double wx = fixedLiquidContact(index,x,y,z,0,0)-fixedLiquidContact(index,x,y,z,0,1);
				double wy = fixedLiquidContact(index,x,y,z,1,0)-fixedLiquidContact(index,x,y,z,1,1);
				double wz = fixedLiquidContact(index,x,y,z,2,0)-fixedLiquidContact(index,x,y,z,2,1);
				double wallLength = Math.sqrt(wx*wx+wy*wy+wz*wz);
				if (wallLength > 0) {
					wx /= wallLength; wy /= wallLength; wz /= wallLength;
					double dot = normalX*wx+normalY*wy+normalZ*wz;
					double tx = normalX-dot*wx, ty = normalY-dot*wy, tz = normalZ-dot*wz;
					double tangentLength = Math.sqrt(tx*tx+ty*ty+tz*tz);
					if (tangentLength > 0) {
						double angle = surfaceTensionContactAngleAtCell(index), sine = Math.sin(angle), cosine = Math.cos(angle);
						// Stored interface normals point inward: n = sin(theta)*t - cos(theta)*n_wall.
						normalX = sine*tx/tangentLength-cosine*wx;
						normalY = sine*ty/tangentLength-cosine*wy;
						normalZ = sine*tz/tangentLength-cosine*wz;
					}
				}
			}
			domain.tension.surfaceTensionNormalX[index] = normalX;
			domain.tension.surfaceTensionNormalY[index] = normalY;
			domain.tension.surfaceTensionNormalZ[index] = normalZ;
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
		int phase = dominantPhase(cell);
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
	double surfaceTensionNeighborNormal(int x, int y, int z, int dx, int dy, int dz, int component) {
		return surfaceTensionNeighborNormal(x, y, z, dx, dy, dz, component, false);
	}
	private double surfaceTensionNeighborNormal(int x, int y, int z, int dx, int dy, int dz, int component,
			boolean liquidGasReflection) {
		int nx = x + dx, ny = y + dy, nz = z + dz;
		if (liquidGasReflection) {
			int target = cellIndex(x,y,z), axis = dx != 0 ? 0 : dy != 0 ? 1 : 2;
			double open = axis == 0 ? domain.geometry.cutCells.xFaceOpen(x+(dx>0?1:0),y,z)
					: axis == 1 ? domain.geometry.cutCells.yFaceOpen(x,y+(dy>0?1:0),z)
					: domain.geometry.cutCells.zFaceOpen(x,y,z+(dz>0?1:0));
			if (!inside(nx,ny,nz)) open = 0;
			if (open < 1) {
				int sample = cellIndex(Math.clamp(nx,0,domain.sizeX-1),Math.clamp(ny,0,domain.sizeY-1),Math.clamp(nz,0,domain.sizeZ-1));
				double local = cachedSurfaceTensionNormal(target,component);
				boolean touching = fixedLiquidContact(target,x,y,z,axis,dx+dy+dz>0?1:0) > 0;
				double wallNormal = component == axis ? (touching ? (dx+dy+dz)*Math.cos(surfaceTensionContactAngleAtCell(target)) : 0) : local;
				double ghost = 2*wallNormal-local;
				return open*cachedSurfaceTensionNormal(sample,component)+(1-open)*ghost;
			}
		}
		if (inside(nx, ny, nz) && domain.geometry.solid[cellIndex(nx, ny, nz)]) {
			return cachedSurfaceTensionNormal(cellIndex(x, y, z), component);
		}
		int target = cellIndex(x, y, z), sample = cellIndex(Math.min(domain.sizeX - 1, Math.max(0, nx)),
				Math.min(domain.sizeY - 1, Math.max(0, ny)), Math.min(domain.sizeZ - 1, Math.max(0, nz)));
		double value = cachedSurfaceTensionNormal(sample, component);
		return value;
	}
	double cachedSurfaceTensionNormal(int cell, int component) {
		return switch (component) {
			case 0 -> domain.tension.surfaceTensionNormalX[cell];
			case 1 -> domain.tension.surfaceTensionNormalY[cell];
			default -> domain.tension.surfaceTensionNormalZ[cell];
		};
	}
	float pressureDensityScale(int first, int second) {
		if (isFluid(first) && isFluid(second)) {
			return (float) (domain.config.density() / materialFaceEffectiveDensity(first, second));
		}
		int fluid = isFluid(first) ? first : second;
		return (float) (domain.config.density() / mechanicalDensity(fluid));
	}
	float carrierDensityScale(int first, int second) {
		boolean firstCarrier = domain.liquidGrid.stockCount[first] > 0;
		boolean secondCarrier = domain.liquidGrid.stockCount[second] > 0;
		if (firstCarrier && secondCarrier) {
			return (float) (domain.config.density() / materialFaceEffectiveDensity(first, second));
		}
		int carrier = firstCarrier ? first : second;
		return (float) (domain.config.density() / mechanicalDensity(carrier));
	}
	private double mechanicalDensity(int cell) {
		return domain.thermalState == null || domain.liquidGrid.stockCount[cell] == 0
				? domain.liquidGrid.cellDensity[cell] : domain.domainPhaseInventory.derivedCarrierState().density(cell);
	}
	double materialFaceEffectiveDensity(int one, int two) {
		int first = Math.min(one, two), second = Math.max(one, two);
		double fraction = materialFaceDensityFraction(first, second);
		return fraction * mechanicalDensity(first) + (1.0 - fraction) * mechanicalDensity(second);
	}
	double thermalFaceRelativeBuoyancy(int one, int two, LiquidCarrierProperties properties) {
		int first = Math.min(one, two), second = Math.max(one, two);
		if (domain.liquidGrid.stockCount[first] == 0) return properties.relativeBuoyancy(second);
		if (domain.liquidGrid.stockCount[second] == 0) return properties.relativeBuoyancy(first);
		double fraction = materialFaceDensityFraction(first, second);
		double firstMassDensity = fraction * properties.density(first);
		double secondMassDensity = (1.0 - fraction) * properties.density(second);
		return (firstMassDensity * properties.relativeBuoyancy(first)
				+ secondMassDensity * properties.relativeBuoyancy(second)) / (firstMassDensity + secondMassDensity);
	}
	private double materialFaceDensityFraction(int first, int second) {
		double fraction = 0.5;
		int firstPhase = dominantPhase(first), secondPhase = dominantPhase(second);
		if (firstPhase != FluidDomain.NO_PHASE && secondPhase != FluidDomain.NO_PHASE && firstPhase != secondPhase) {
			double firstSigned = phaseFraction(first, firstPhase) - 0.5;
			double secondSigned = phaseFraction(second, firstPhase) - 0.5;
			double denominator = firstSigned - secondSigned;
			if (firstSigned > 0.0 && secondSigned < 0.0) {
				fraction = firstSigned / denominator;
			}
		}
		return fraction;
	}
	int dominantPhase(int cell) {
		int dominant = FluidDomain.NO_PHASE;
		double dominantVolume = -1.0;
		for (int entry = domain.liquidGrid.cellPhaseHead[cell]; entry >= 0; entry = domain.liquidGrid.phaseEntryNext[entry]) {
			double candidateVolume = domain.liquidGrid.phaseEntryVolume[entry];
			int candidate = domain.liquidGrid.phaseEntryLabel[entry];
			if (candidateVolume > dominantVolume || (candidateVolume == dominantVolume && candidate < dominant)) {
				dominant = candidate;
				dominantVolume = candidateVolume;
			}
		}
		return dominant;
	}
	double phaseFraction(int cell, int phase) {
		if (domain.liquidGrid.cellStockVolume[cell] <= 0.0) return 0.0;
		for (int entry = domain.liquidGrid.cellPhaseHead[cell]; entry >= 0; entry = domain.liquidGrid.phaseEntryNext[entry]) {
			if (domain.liquidGrid.phaseEntryLabel[entry] == phase) return domain.liquidGrid.phaseEntryVolume[entry] / domain.liquidGrid.cellStockVolume[cell];
		}
		return 0.0;
	}
	float openFaceFromCell(int cell, int directionX, int directionY, int directionZ) {
		int x = cell % domain.sizeX;
		int yz = cell / domain.sizeX;
		int y = yz % domain.sizeY;
		int z = yz / domain.sizeY;
		if (directionX < 0) return domain.geometry.cutCells.xFaceOpen(x, y, z);
		if (directionX > 0) return domain.geometry.cutCells.xFaceOpen(x + 1, y, z);
		if (directionY < 0) return domain.geometry.cutCells.yFaceOpen(x, y, z);
		if (directionY > 0) return domain.geometry.cutCells.yFaceOpen(x, y + 1, z);
		if (directionZ < 0) return domain.geometry.cutCells.zFaceOpen(x, y, z);
		if (directionZ > 0) return domain.geometry.cutCells.zFaceOpen(x, y, z + 1);
		throw new IllegalArgumentException("Pressure face direction must be axial");
	}
	float pressureOpenFaceFromCell(int cell, int neighbor, int dx, int dy, int dz) {
		int x = cell % domain.sizeX, y = cell / domain.sizeX % domain.sizeY, z = cell / (domain.sizeX * domain.sizeY);
		if (dx != 0) return pressureXFaceOpen(x + (dx > 0 ? 1 : 0), y, z);
		if (dy != 0) return pressureYFaceOpen(x, y + (dy > 0 ? 1 : 0), z);
		return pressureZFaceOpen(x, y, z + (dz > 0 ? 1 : 0));
	}
	float pressureXFaceOpen(int x, int y, int z) {
		return domain.pressureGranularCoupling == null ? domain.geometry.cutCells.xFaceOpen(x, y, z)
				: domain.pressureSystem.solidFaces.open(0, xFaceIndex(x, y, z));
	}
	float pressureYFaceOpen(int x, int y, int z) {
		return domain.pressureGranularCoupling == null ? domain.geometry.cutCells.yFaceOpen(x, y, z)
				: domain.pressureSystem.solidFaces.open(1, yFaceIndex(x, y, z));
	}
	float pressureZFaceOpen(int x, int y, int z) {
		return domain.pressureGranularCoupling == null ? domain.geometry.cutCells.zFaceOpen(x, y, z)
				: domain.pressureSystem.solidFaces.open(2, zFaceIndex(x, y, z));
	}
	float[] markerSlotPosition(int cellX, int cellY, int cellZ, int slot) {
		float h = domain.config.cellSize();
		float radiusFraction = ConservativeMarkerSampling.DOMAIN_CONTACT_FRACTION;
		float ox = (slot & 1) == 0 ? 0.25F : 0.75F;
		float oy = (slot & 2) == 0 ? 0.25F : 0.75F;
		float oz = (slot & 4) == 0 ? 0.25F : 0.75F;
		if (cellX == 0 || domain.geometry.solid[cellIndex(cellX - 1, cellY, cellZ)]) ox = Math.max(ox, radiusFraction);
		if (cellX + 1 == domain.sizeX || domain.geometry.solid[cellIndex(cellX + 1, cellY, cellZ)]) ox = Math.min(ox, 1.0F - radiusFraction);
		if (cellY == 0 || domain.geometry.solid[cellIndex(cellX, cellY - 1, cellZ)]) oy = Math.max(oy, radiusFraction);
		if (cellY + 1 == domain.sizeY || domain.geometry.solid[cellIndex(cellX, cellY + 1, cellZ)]) oy = Math.min(oy, 1.0F - radiusFraction);
		if (cellZ == 0 || domain.geometry.solid[cellIndex(cellX, cellY, cellZ - 1)]) oz = Math.max(oz, radiusFraction);
		if (cellZ + 1 == domain.sizeZ || domain.geometry.solid[cellIndex(cellX, cellY, cellZ + 1)]) oz = Math.min(oz, 1.0F - radiusFraction);
		float px = (cellX + ox) * h, py = (cellY + oy) * h, pz = (cellZ + oz) * h;
		if (Math.floor(px / h) != cellX || Math.floor(py / h) != cellY || Math.floor(pz / h) != cellZ
				|| CellRemapMicroTopology.positionMicro(h, px, py, pz) != slot)
			throw new IllegalArgumentException("Cell marker slot exceeds its binary32 coordinate representation");
		return new float[] {px, py, pz};
	}
	float exactVolumeInCell(int cellX, int cellY, int cellZ) { return (float) domain.liquidCells.exactCellVolume(cellIndex(cellX, cellY, cellZ)).toDouble(); }
	mmm.units.SignedDyadic[] availableCellVolumes() {
		var room = new mmm.units.SignedDyadic[domain.cellCount];
		for (int cell = 0; cell < domain.cellCount; cell++) {
			room[cell] = mmm.units.SignedDyadic.of(domain.domainCollision.effectiveOpenCellVolume(cell)).subtract(domain.liquidCells.exactCellVolume(cell));
			if (room[cell].signum() < 0) throw new IllegalStateException("Liquid exceeds cell capacity");
		}
		return room;
	}
	List<CellCandidate> nearbyCells(int centerX, int centerY, int centerZ, int maxRadius) {
		List<CellCandidate> result = new ArrayList<>();
		int radiusSquared = maxRadius * maxRadius;
		for (int z = Math.max(0, centerZ - maxRadius); z <= Math.min(domain.sizeZ - 1, centerZ + maxRadius); z++)
			for (int y = Math.max(0, centerY - maxRadius); y <= Math.min(domain.sizeY - 1, centerY + maxRadius); y++)
				for (int x = Math.max(0, centerX - maxRadius); x <= Math.min(domain.sizeX - 1, centerX + maxRadius); x++) {
					int dx = x - centerX, dy = y - centerY, dz = z - centerZ, index = cellIndex(x, y, z);
					if (dx * dx + dy * dy + dz * dz <= radiusSquared && !domain.geometry.solid[index]) result.add(new CellCandidate(index, dx * dx + dy * dy + dz * dz));
				}
		result.sort(Comparator.comparingInt(CellCandidate::distanceSquared));
		return result;
	}
	void ensureGrid() {
		ensurePressureGeometry();
		ensureVelocityGrid();
	}
	void ensureVelocityGrid() {
		ensureInventoryGrid();
		if (domain.velocityGridDirty) domain.cellMacStage.rebuildVelocity();
	}
	void ensureInventoryGrid() { if (domain.gridDirty) domain.cellMacStage.rebuildInventory(); }
	void ensurePressureGeometry() {
		ensureInventoryGrid();
		if (domain.pressureGeometryDirty) domain.cellMacStage.rebuildGeometry();
	}
	long carrierMutationRevision() { return domain.carrierMutationRevision; }
	boolean isFluid(int index) { return domain.projection.pressureFluid[index]; }
	boolean isAir(int index) { return !domain.geometry.solid[index] && !isFluid(index); }
	boolean carrierFaceActive(int first, int second, float openArea) {
		return openArea > 0.0F && !domain.geometry.solid[first] && !domain.geometry.solid[second]
				&& (domain.liquidGrid.stockCount[first] > 0 || domain.liquidGrid.stockCount[second] > 0);
	}
	boolean granularCarrierFaceActive(int axis, int x, int y, int z) {
		int ax = x - (axis == 0 ? 1 : 0), ay = y - (axis == 1 ? 1 : 0), az = z - (axis == 2 ? 1 : 0);
		if (!inside(ax, ay, az) || !inside(x, y, z)) return false;
		int first = cellIndex(ax, ay, az), second = cellIndex(x, y, z);
		float open = pressureOpenFaceFromCell(first, second, x - ax, y - ay, z - az);
		return open > 0 && !domain.geometry.solid[first] && !domain.geometry.solid[second]
				&& (isFluid(first) || isFluid(second) || carrierFaceActive(first, second, open));
	}
	boolean inside(int x, int y, int z) { return x >= 0 && x < domain.sizeX && y >= 0 && y < domain.sizeY && z >= 0 && z < domain.sizeZ; }
	int cellIndex(int x, int y, int z) { return (z * domain.sizeY + y) * domain.sizeX + x; }
	int xFaceIndex(int x, int y, int z) { return (z * domain.sizeY + y) * (domain.sizeX + 1) + x; }
	int yFaceIndex(int x, int y, int z) { return (z * (domain.sizeY + 1) + y) * domain.sizeX + x; }
	int zFaceIndex(int x, int y, int z) { return (z * domain.sizeY + y) * domain.sizeX + x; }
	int cellIndexChecked(int x, int y, int z) { if (!inside(x, y, z)) throw new IndexOutOfBoundsException(); return cellIndex(x, y, z); }
	float sampleX(float[] field, float x, float y, float z) {
		float h = domain.config.cellSize();
		return trilinearValid(field, domain.mac.velocityValidX, domain.sizeX + 1, domain.sizeY, domain.sizeZ,
				x / h, y / h - 0.5F, z / h - 0.5F);
	}
	float sampleY(float[] field, float x, float y, float z) {
		float h = domain.config.cellSize();
		return trilinearValid(field, domain.mac.velocityValidY, domain.sizeX, domain.sizeY + 1, domain.sizeZ,
				x / h - 0.5F, y / h, z / h - 0.5F);
	}
	float sampleZ(float[] field, float x, float y, float z) {
		float h = domain.config.cellSize();
		return trilinearValid(field, domain.mac.velocityValidZ, domain.sizeX, domain.sizeY, domain.sizeZ + 1,
				x / h - 0.5F, y / h - 0.5F, z / h);
	}
	static float trilinearValid(
			float[] values, boolean[] valid, int nx, int ny, int nz, float x, float y, float z
	) {
		float cx = clamp(x, 0.0F, nx - 1.0F), cy = clamp(y, 0.0F, ny - 1.0F), cz = clamp(z, 0.0F, nz - 1.0F);
		int x0 = (int) Math.floor(cx), y0 = (int) Math.floor(cy), z0 = (int) Math.floor(cz);
		int x1 = Math.min(x0 + 1, nx - 1), y1 = Math.min(y0 + 1, ny - 1), z1 = Math.min(z0 + 1, nz - 1);
		float fx = cx - x0, fy = cy - y0, fz = cz - z0;
		float weighted = 0.0F, weightSum = 0.0F;
		for (int offsetZ = 0; offsetZ <= 1; offsetZ++) {
			int sampleZ = offsetZ == 0 ? z0 : z1;
			float weightZ = offsetZ == 0 ? 1.0F - fz : fz;
			for (int offsetY = 0; offsetY <= 1; offsetY++) {
				int sampleY = offsetY == 0 ? y0 : y1;
				float weightY = offsetY == 0 ? 1.0F - fy : fy;
				for (int offsetX = 0; offsetX <= 1; offsetX++) {
					int sampleX = offsetX == 0 ? x0 : x1;
					float weight = (offsetX == 0 ? 1.0F - fx : fx) * weightY * weightZ;
					int index = (sampleZ * ny + sampleY) * nx + sampleX;
					if (!valid[index] || weight <= 0.0F) continue;
					weighted += values[index] * weight;
					weightSum += weight;
				}
			}
		}
		return weightSum <= 1.0E-8F ? 0.0F : weighted / weightSum;
	}
	static void trilinearValidPair(
			float[] values, float[] previous, boolean[] valid, int nx, int ny, int nz, float x, float y, float z, float[] result
	) {
		float cx = clamp(x, 0.0F, nx - 1.0F), cy = clamp(y, 0.0F, ny - 1.0F), cz = clamp(z, 0.0F, nz - 1.0F);
		int x0 = (int) Math.floor(cx), y0 = (int) Math.floor(cy), z0 = (int) Math.floor(cz);
		int x1 = Math.min(x0 + 1, nx - 1), y1 = Math.min(y0 + 1, ny - 1), z1 = Math.min(z0 + 1, nz - 1);
		float fx = cx - x0, fy = cy - y0, fz = cz - z0;
		float weighted = 0.0F, oldWeighted = 0.0F, weightSum = 0.0F;
		for (int offsetZ = 0; offsetZ <= 1; offsetZ++) {
			int sampleZ = offsetZ == 0 ? z0 : z1;
			float weightZ = offsetZ == 0 ? 1.0F - fz : fz;
			for (int offsetY = 0; offsetY <= 1; offsetY++) {
				int sampleY = offsetY == 0 ? y0 : y1;
				float weightY = offsetY == 0 ? 1.0F - fy : fy;
				for (int offsetX = 0; offsetX <= 1; offsetX++) {
					int sampleX = offsetX == 0 ? x0 : x1;
					float weight = (offsetX == 0 ? 1.0F - fx : fx) * weightY * weightZ;
					int index = (sampleZ * ny + sampleY) * nx + sampleX;
					if (!valid[index] || weight <= 0.0F) continue;
					weighted += values[index] * weight;
					oldWeighted += previous[index] * weight;
					weightSum += weight;
				}
			}
		}
		result[0] = weightSum <= 1.0E-8F ? 0.0F : weighted / weightSum;
		result[1] = weightSum <= 1.0E-8F ? 0.0F : oldWeighted / weightSum;
	}
	void validateVolumeEdit(float amount, int maxRadius) {
		if (!Float.isFinite(amount) || amount < 0.0F) throw new IllegalArgumentException("amount must be finite and non-negative");
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
	}
	void validateMaterialPortion(FluidDomain.MaterialPortion portion) {
		if (portion == null || !(Double.isFinite(portion.volume()) && portion.volume() > 0.0
				&& portion.volume() <= Float.MAX_VALUE)
				|| portion.massUnits() < 0L) {
			throw new IllegalArgumentException("Material portion volume must be finite and positive, with nonnegative mass");
		}
		if (domain.materialRegistry == null) {
			if (portion.materialHandle() != 0) {
				throw new IllegalArgumentException("Material handle requires a bound registry");
			}
		} else {
			domain.materialRegistry.material(portion.materialHandle());
		}
	}
	static float clamp01(float value) { return clamp(value, 0.0F, 1.0F); }
	static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
	static float lerp(float first, float second, float t) { return first + (second - first) * t; }
}
