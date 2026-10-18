package mmm.fluid;

import mmm.thermal.LiquidEnergyColumn;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalParcelStore;
import mmm.chemistry.ChemicalTransferLedger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Low-level carrier, pressure-face, surface-tension and interpolation mechanics. */
abstract class FluidDomainMechanicalFields extends FluidDomainContracts {
	FluidDomainMechanicalFields(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		super(sizeX, sizeY, sizeZ, config, maximumParticleCount);
	}

	boolean hasAnySurfaceTension() {
		if (materialRegistry == null) return false;
		for (FluidMaterial material : materialRegistry.materials()) {
			if (material.surfaceTension() > 0.0) return true;
		}
		return false;
	}

	boolean hasAnyLiquidPairTension() {
		if (materialRegistry == null) return false;
		for (double tension : materialRegistry.liquidPairTensions().values()) {
			if (tension > 0.0) return true;
		}
		return false;
	}

	/**
	 * Liquid-gas surface tension for a pressure cell. Immiscible phases use the first registry material
	 * of the dominant phase; miscible composition-dependent tension is not modelled yet.
	 */
	double surfaceTensionAtCell(int cell) {
		if (materialRegistry == null) return 0.0;
		int phase = dominantPhase(cell);
		if (phase == NO_PHASE) return 0.0;
		for (int handle = 0; handle < materialRegistry.size(); handle++) {
			if (materialRegistry.phaseHandle(handle) == phase) {
				return materialRegistry.material(handle).surfaceTension();
			}
		}
		return 0.0;
	}

	/** Rebuilds the cell-centred surface-tension pressure {@code sigma * kappa} used as the free-surface jump. */
	void rebuildSurfaceTensionPressure() {
		Arrays.fill(tension.surfaceTensionPressure, 0.0F);
		Arrays.fill(tension.wallSurfaceEnergyDensity, 0.0);
		if (!surfaceTensionActive) return;
		for (int index = 0; index < cellCount; index++) {
			if (geometry.solid[index]) {
				tension.surfaceTensionColor[index] = 0.0;
				continue;
			}
			double openVolume = domain().effectiveOpenCellVolume(index);
			tension.surfaceTensionColor[index] = openVolume > 0.0
					? Math.clamp(markerGrid.exactCellMarkerVolume[index] / openVolume, 0.0, 1.0)
					: 0.0;
		}
		smoothSurfaceTensionColor();
		rebuildSurfaceTensionNormals();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (geometry.solid[index] || markerGrid.markerCount[index] == 0) continue;
			double sigma = surfaceTensionAtCell(index);
			if (sigma <= 0.0) continue;
			double kappa = liquidGasMeanCurvature(x, y, z);
			tension.surfaceTensionPressure[index] = (float) (sigma * kappa);
			tension.wallSurfaceEnergyDensity[index] = -sigma
					* Math.cos(surfaceTensionContactAngleAtCell(index)) / config.cellSize();
		}
	}

	void smoothSurfaceTensionColor() {
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			double sum = 0.0;
			int count = 0;
			for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				int sx = x + dx, sy = y + dy, sz = z + dz;
				if (!inside(sx, sy, sz)) continue;
				int neighbor = cellIndex(sx, sy, sz);
				if (geometry.solid[neighbor]) continue;
				sum += tension.surfaceTensionColor[neighbor];
				count++;
			}
			tension.surfaceTensionSmoothed[index] = count == 0 ? tension.surfaceTensionColor[index] : sum / count;
		}
	}

	double surfaceTensionCentralDifference(int x, int y, int z, int dx, int dy, int dz) {
		int xPlus = Math.min(sizeX - 1, Math.max(0, x + dx));
		int xMinus = Math.min(sizeX - 1, Math.max(0, x - dx));
		int yPlus = Math.min(sizeY - 1, Math.max(0, y + dy));
		int yMinus = Math.min(sizeY - 1, Math.max(0, y - dy));
		int zPlus = Math.min(sizeZ - 1, Math.max(0, z + dz));
		int zMinus = Math.min(sizeZ - 1, Math.max(0, z - dz));
		return 0.5 * (tension.surfaceTensionSmoothed[cellIndex(xPlus, yPlus, zPlus)]
				- tension.surfaceTensionSmoothed[cellIndex(xMinus, yMinus, zMinus)]);
	}

	/** Unit interface normal at a cell centre, pointing from gas into liquid, computed once per cell into the cache. */
	void rebuildSurfaceTensionNormals() {
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (geometry.solid[index]) {
				tension.surfaceTensionNormalX[index] = 0.0;
				tension.surfaceTensionNormalY[index] = 0.0;
				tension.surfaceTensionNormalZ[index] = 1.0;
				continue;
			}
			double gradientX = surfaceTensionCentralDifference(x, y, z, 1, 0, 0);
			double gradientY = surfaceTensionCentralDifference(x, y, z, 0, 1, 0);
			double gradientZ = surfaceTensionCentralDifference(x, y, z, 0, 0, 1);
			double length = Math.sqrt(gradientX * gradientX + gradientY * gradientY + gradientZ * gradientZ);
			if (!(length > 0.0)) {
				tension.surfaceTensionNormalX[index] = 0.0;
				tension.surfaceTensionNormalY[index] = 0.0;
				tension.surfaceTensionNormalZ[index] = 1.0;
				continue;
			}
			double normalX = gradientX / length;
			double normalY = gradientY / length;
			double normalZ = gradientZ / length;
			tension.surfaceTensionNormalX[index] = normalX;
			tension.surfaceTensionNormalY[index] = normalY;
			tension.surfaceTensionNormalZ[index] = normalZ;
		}
	}

	double surfaceTensionContactAngleAtCell(int cell) {
		if (materialRegistry == null) return Math.PI / 2.0;
		int phase = dominantPhase(cell);
		if (phase == NO_PHASE) return Math.PI / 2.0;
		for (int handle = 0; handle < materialRegistry.size(); handle++) {
			if (materialRegistry.phaseHandle(handle) == phase) {
				return materialRegistry.material(handle).contactAngleRadians();
			}
		}
		return Math.PI / 2.0;
	}

	/** Liquid-gas curvature from complete volume columns, with smoothed normals for unresolved geometry. */
	double liquidGasMeanCurvature(int x, int y, int z) {
		double heightCurvature = HeightFunctionCurvature.compute(domain(), x, y, z);
		if (Double.isFinite(heightCurvature)) return heightCurvature;
		return smoothedSurfaceTensionCurvature(x, y, z, true);
	}

	/** Curvature of the smoothed colour support used by liquid-pair CSF forces. */
	double surfaceTensionMeanCurvature(int x, int y, int z) {
		return smoothedSurfaceTensionCurvature(x, y, z, false);
	}

	private double smoothedSurfaceTensionCurvature(int x, int y, int z, boolean liquidGasReflection) {
		double inverseTwoCell = 0.5 / config.cellSize();
		double nxPlus = surfaceTensionNeighborNormal(x, y, z, 1, 0, 0, 0, liquidGasReflection);
		double nxMinus = surfaceTensionNeighborNormal(x, y, z, -1, 0, 0, 0, liquidGasReflection);
		double nyPlus = surfaceTensionNeighborNormal(x, y, z, 0, 1, 0, 1, liquidGasReflection);
		double nyMinus = surfaceTensionNeighborNormal(x, y, z, 0, -1, 0, 1, liquidGasReflection);
		double nzPlus = surfaceTensionNeighborNormal(x, y, z, 0, 0, 1, 2, liquidGasReflection);
		double nzMinus = surfaceTensionNeighborNormal(x, y, z, 0, 0, -1, 2, liquidGasReflection);
		return -((nxPlus - nxMinus) + (nyPlus - nyMinus) + (nzPlus - nzMinus)) * inverseTwoCell;
	}

	/** Neighbor normal used in the divergence; a solid neighbour extends the liquid-gas normal constantly. */
	double surfaceTensionNeighborNormal(int x, int y, int z, int dx, int dy, int dz, int component) {
		return surfaceTensionNeighborNormal(x, y, z, dx, dy, dz, component, false);
	}

	private double surfaceTensionNeighborNormal(int x, int y, int z, int dx, int dy, int dz, int component,
			boolean liquidGasReflection) {
		int nx = x + dx, ny = y + dy, nz = z + dz;
		if (inside(nx, ny, nz) && geometry.solid[cellIndex(nx, ny, nz)]) {
			return cachedSurfaceTensionNormal(cellIndex(x, y, z), component);
		}
		int target = cellIndex(x, y, z), sample = cellIndex(Math.min(sizeX - 1, Math.max(0, nx)),
				Math.min(sizeY - 1, Math.max(0, ny)), Math.min(sizeZ - 1, Math.max(0, nz)));
		double value = cachedSurfaceTensionNormal(sample, component);
		if (liquidGasReflection && !inside(nx, ny, nz) && surfaceTensionContactAngleAtCell(target) == Math.PI / 2
				&& (tension.surfaceTensionColor[sample] == 0 || surfaceTensionContactAngleAtCell(sample) == Math.PI / 2)
				&& geometry.cutCells.mask(target) == 0 && geometry.cutCells.mask(sample) == 0
				&& domain().effectiveOpenCellVolume(target) == 1 && domain().effectiveOpenCellVolume(sample) == 1) {
			if (component == 0 && (nx < 0 || nx >= sizeX) || component == 1 && (ny < 0 || ny >= sizeY)
					|| component == 2 && (nz < 0 || nz >= sizeZ)) value = -value;
		}
		return value;
	}

	double cachedSurfaceTensionNormal(int cell, int component) {
		return switch (component) {
			case 0 -> tension.surfaceTensionNormalX[cell];
			case 1 -> tension.surfaceTensionNormalY[cell];
			default -> tension.surfaceTensionNormalZ[cell];
		};
	}

	float pressureDensityScale(int first, int second) {
		if (isFluid(first) && isFluid(second)) {
			return (float) (config.density() / materialFaceEffectiveDensity(first, second));
		}
		int fluid = isFluid(first) ? first : second;
		return (float) (config.density() / mechanicalDensity(fluid));
	}

	float carrierDensityScale(int first, int second) {
		boolean firstCarrier = markerGrid.markerCount[first] > 0;
		boolean secondCarrier = markerGrid.markerCount[second] > 0;
		if (firstCarrier && secondCarrier) {
			return (float) (config.density() / materialFaceEffectiveDensity(first, second));
		}
		int carrier = firstCarrier ? first : second;
		return (float) (config.density() / mechanicalDensity(carrier));
	}

	private double mechanicalDensity(int cell) {
		return thermalState == null || markerGrid.markerCount[cell] == 0
				? markerGrid.cellDensity[cell] : domain().derivedCarrierState().density(cell);
	}

	double materialFaceEffectiveDensity(int one, int two) {
		int first = Math.min(one, two), second = Math.max(one, two);
		double fraction = materialFaceDensityFraction(first, second);
		return fraction * mechanicalDensity(first) + (1.0 - fraction) * mechanicalDensity(second);
	}

	/** Thermal force and pressure inertia integrate the same two composition-density segments. */
	double thermalFaceRelativeBuoyancy(int one, int two, LiquidCarrierProperties properties) {
		int first = Math.min(one, two), second = Math.max(one, two);
		if (markerGrid.markerCount[first] == 0) return properties.relativeBuoyancy(second);
		if (markerGrid.markerCount[second] == 0) return properties.relativeBuoyancy(first);
		double fraction = materialFaceDensityFraction(first, second);
		double firstMassDensity = fraction * properties.density(first);
		double secondMassDensity = (1.0 - fraction) * properties.density(second);
		return (firstMassDensity * properties.relativeBuoyancy(first)
				+ secondMassDensity * properties.relativeBuoyancy(second)) / (firstMassDensity + secondMassDensity);
	}

	private double materialFaceDensityFraction(int first, int second) {
		double fraction = 0.5;
		int firstPhase = dominantPhase(first), secondPhase = dominantPhase(second);
		if (firstPhase != NO_PHASE && secondPhase != NO_PHASE && firstPhase != secondPhase) {
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
		int dominant = NO_PHASE;
		double dominantVolume = -1.0;
		for (int entry = markerGrid.cellPhaseHead[cell]; entry >= 0; entry = markerGrid.phaseEntryNext[entry]) {
			double candidateVolume = markerGrid.phaseEntryVolume[entry];
			int candidate = markerGrid.phaseEntryLabel[entry];
			if (candidateVolume > dominantVolume || (candidateVolume == dominantVolume && candidate < dominant)) {
				dominant = candidate;
				dominantVolume = candidateVolume;
			}
		}
		return dominant;
	}

	double phaseFraction(int cell, int phase) {
		if (markerGrid.exactCellMarkerVolume[cell] <= 0.0) return 0.0;
		for (int entry = markerGrid.cellPhaseHead[cell]; entry >= 0; entry = markerGrid.phaseEntryNext[entry]) {
			if (markerGrid.phaseEntryLabel[entry] == phase) return markerGrid.phaseEntryVolume[entry] / markerGrid.exactCellMarkerVolume[cell];
		}
		return 0.0;
	}

	float openFaceFromCell(int cell, int directionX, int directionY, int directionZ) {
		int x = cell % sizeX;
		int yz = cell / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		if (directionX < 0) return geometry.cutCells.xFaceOpen(x, y, z);
		if (directionX > 0) return geometry.cutCells.xFaceOpen(x + 1, y, z);
		if (directionY < 0) return geometry.cutCells.yFaceOpen(x, y, z);
		if (directionY > 0) return geometry.cutCells.yFaceOpen(x, y + 1, z);
		if (directionZ < 0) return geometry.cutCells.zFaceOpen(x, y, z);
		if (directionZ > 0) return geometry.cutCells.zFaceOpen(x, y, z + 1);
		throw new IllegalArgumentException("Pressure face direction must be axial");
	}

	float pressureOpenFaceFromCell(int cell, int neighbor, int dx, int dy, int dz) {
		int x = cell % sizeX, y = cell / sizeX % sizeY, z = cell / (sizeX * sizeY);
		if (dx != 0) return pressureXFaceOpen(x + (dx > 0 ? 1 : 0), y, z);
		if (dy != 0) return pressureYFaceOpen(x, y + (dy > 0 ? 1 : 0), z);
		return pressureZFaceOpen(x, y, z + (dz > 0 ? 1 : 0));
	}

	float pressureXFaceOpen(int x, int y, int z) {
		return pressureGranularCoupling == null ? geometry.cutCells.xFaceOpen(x, y, z)
				: pressureSystem.solidFaces.open(0, xFaceIndex(x, y, z));
	}
	float pressureYFaceOpen(int x, int y, int z) {
		return pressureGranularCoupling == null ? geometry.cutCells.yFaceOpen(x, y, z)
				: pressureSystem.solidFaces.open(1, yFaceIndex(x, y, z));
	}
	float pressureZFaceOpen(int x, int y, int z) {
		return pressureGranularCoupling == null ? geometry.cutCells.zFaceOpen(x, y, z)
				: pressureSystem.solidFaces.open(2, zFaceIndex(x, y, z));
	}



	float[] markerSlotPosition(int cellX, int cellY, int cellZ, int slot) {
		float h = config.cellSize();
		float radiusFraction = ConservativeMarkerSampling.DOMAIN_CONTACT_FRACTION;
		float ox = (slot & 1) == 0 ? 0.25F : 0.75F;
		float oy = (slot & 2) == 0 ? 0.25F : 0.75F;
		float oz = (slot & 4) == 0 ? 0.25F : 0.75F;
		if (cellX == 0 || geometry.solid[cellIndex(cellX - 1, cellY, cellZ)]) ox = Math.max(ox, radiusFraction);
		if (cellX + 1 == sizeX || geometry.solid[cellIndex(cellX + 1, cellY, cellZ)]) ox = Math.min(ox, 1.0F - radiusFraction);
		if (cellY == 0 || geometry.solid[cellIndex(cellX, cellY - 1, cellZ)]) oy = Math.max(oy, radiusFraction);
		if (cellY + 1 == sizeY || geometry.solid[cellIndex(cellX, cellY + 1, cellZ)]) oy = Math.min(oy, 1.0F - radiusFraction);
		if (cellZ == 0 || geometry.solid[cellIndex(cellX, cellY, cellZ - 1)]) oz = Math.max(oz, radiusFraction);
		if (cellZ + 1 == sizeZ || geometry.solid[cellIndex(cellX, cellY, cellZ + 1)]) oz = Math.min(oz, 1.0F - radiusFraction);
		float px = (cellX + ox) * h, py = (cellY + oy) * h, pz = (cellZ + oz) * h;
		if (Math.floor(px / h) != cellX || Math.floor(py / h) != cellY || Math.floor(pz / h) != cellZ
				|| CellRemapMicroTopology.positionMicro(h, px, py, pz) != slot)
			throw new IllegalArgumentException("Cell marker slot exceeds its binary32 coordinate representation");
		return new float[] {px, py, pz};
	}

	float exactVolumeInCell(int cellX, int cellY, int cellZ) { return (float) liquidCells.exactCellVolume(cellIndex(cellX, cellY, cellZ)).toDouble(); }
	mmm.units.SignedDyadic[] availableCellVolumes() {
		var room = new mmm.units.SignedDyadic[cellCount];
		for (int cell = 0; cell < cellCount; cell++) {
			room[cell] = mmm.units.SignedDyadic.of(domain().effectiveOpenCellVolume(cell)).subtract(liquidCells.exactCellVolume(cell));
			if (room[cell].signum() < 0) throw new IllegalStateException("Liquid exceeds cell capacity");
		}
		return room;
	}





	List<CellCandidate> nearbyCells(int centerX, int centerY, int centerZ, int maxRadius) {
		ensureGrid();
		List<CellCandidate> result = new ArrayList<>();
		int radiusSquared = maxRadius * maxRadius;
		for (int z = Math.max(0, centerZ - maxRadius); z <= Math.min(sizeZ - 1, centerZ + maxRadius); z++)
			for (int y = Math.max(0, centerY - maxRadius); y <= Math.min(sizeY - 1, centerY + maxRadius); y++)
				for (int x = Math.max(0, centerX - maxRadius); x <= Math.min(sizeX - 1, centerX + maxRadius); x++) {
					int dx = x - centerX, dy = y - centerY, dz = z - centerZ, index = cellIndex(x, y, z);
					if (dx * dx + dy * dy + dz * dz <= radiusSquared && !geometry.solid[index]) result.add(new CellCandidate(index, dx * dx + dy * dy + dz * dz));
				}
		result.sort(Comparator.comparingInt(CellCandidate::distanceSquared));
		return result;
	}

	void ensureGrid() {
		ensurePressureGeometry();
		if (velocityGridDirty) cellMacStage.rebuildVelocity();
	}
	void ensureInventoryGrid() { if (gridDirty) cellMacStage.rebuildInventory(); }
	void ensurePressureGeometry() {
		ensureInventoryGrid();
		if (pressureGeometryDirty) cellMacStage.rebuildGeometry();
	}
	long carrierMutationRevision() { return carrierMutationRevision; }
	boolean isFluid(int index) { return projection.pressureFluid[index]; }
	boolean isAir(int index) { return !geometry.solid[index] && !isFluid(index); }
	boolean carrierFaceActive(int first, int second, float openArea) {
		return openArea > 0.0F && !geometry.solid[first] && !geometry.solid[second]
				&& (markerGrid.markerCount[first] > 0 || markerGrid.markerCount[second] > 0);
	}
	boolean granularCarrierFaceActive(int axis, int x, int y, int z) {
		int ax = x - (axis == 0 ? 1 : 0), ay = y - (axis == 1 ? 1 : 0), az = z - (axis == 2 ? 1 : 0);
		if (!inside(ax, ay, az) || !inside(x, y, z)) return false;
		int first = cellIndex(ax, ay, az), second = cellIndex(x, y, z);
		float open = pressureOpenFaceFromCell(first, second, x - ax, y - ay, z - az);
		return open > 0 && !geometry.solid[first] && !geometry.solid[second]
				&& (isFluid(first) || isFluid(second) || carrierFaceActive(first, second, open));
	}

	boolean inside(int x, int y, int z) { return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ; }
	int cellIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	int xFaceIndex(int x, int y, int z) { return (z * sizeY + y) * (sizeX + 1) + x; }
	int yFaceIndex(int x, int y, int z) { return (z * (sizeY + 1) + y) * sizeX + x; }
	int zFaceIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	int cellIndexChecked(int x, int y, int z) { if (!inside(x, y, z)) throw new IndexOutOfBoundsException(); return cellIndex(x, y, z); }

	float sampleX(float[] field, float x, float y, float z) {
		float h = config.cellSize();
		return trilinearValid(field, mac.velocityValidX, sizeX + 1, sizeY, sizeZ,
				x / h, y / h - 0.5F, z / h - 0.5F);
	}
	float sampleY(float[] field, float x, float y, float z) {
		float h = config.cellSize();
		return trilinearValid(field, mac.velocityValidY, sizeX, sizeY + 1, sizeZ,
				x / h - 0.5F, y / h, z / h - 0.5F);
	}
	float sampleZ(float[] field, float x, float y, float z) {
		float h = config.cellSize();
		return trilinearValid(field, mac.velocityValidZ, sizeX, sizeY, sizeZ + 1,
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

	/** Shares the stencil while retaining each scalar float reduction in its original order. */
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
		if (materialRegistry == null) {
			if (portion.materialHandle() != 0) {
				throw new IllegalArgumentException("Material handle requires a bound registry");
			}
		} else {
			materialRegistry.material(portion.materialHandle());
		}
	}
	static float clamp01(float value) { return clamp(value, 0.0F, 1.0F); }
	static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
	static float lerp(float first, float second, float t) { return first + (second - first) * t; }

}
