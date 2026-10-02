package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.LiquidEnergyColumn;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;

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
					? clamp((float) (markerGrid.exactCellMarkerVolume[index] / openVolume), 0.0F, 1.0F)
					: 0.0;
		}
		smoothSurfaceTensionColor();
		rebuildSurfaceTensionNormals();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (geometry.solid[index] || markerGrid.markerCount[index] == 0) continue;
			double sigma = surfaceTensionAtCell(index);
			if (sigma <= 0.0) continue;
			double kappa = surfaceTensionMeanCurvature(x, y, z);
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

	/** Mean curvature {@code kappa = -div(n)}; positive for a liquid sphere surrounded by gas. */
	double surfaceTensionMeanCurvature(int x, int y, int z) {
		double inverseTwoCell = 0.5 / config.cellSize();
		double nxPlus = surfaceTensionNeighborNormal(x, y, z, 1, 0, 0, 0);
		double nxMinus = surfaceTensionNeighborNormal(x, y, z, -1, 0, 0, 0);
		double nyPlus = surfaceTensionNeighborNormal(x, y, z, 0, 1, 0, 1);
		double nyMinus = surfaceTensionNeighborNormal(x, y, z, 0, -1, 0, 1);
		double nzPlus = surfaceTensionNeighborNormal(x, y, z, 0, 0, 1, 2);
		double nzMinus = surfaceTensionNeighborNormal(x, y, z, 0, 0, -1, 2);
		return -((nxPlus - nxMinus) + (nyPlus - nyMinus) + (nzPlus - nzMinus)) * inverseTwoCell;
	}

	/** Neighbor normal used in the divergence; a solid neighbour extends the liquid-gas normal constantly. */
	double surfaceTensionNeighborNormal(int x, int y, int z, int dx, int dy, int dz, int component) {
		int nx = x + dx, ny = y + dy, nz = z + dz;
		if (inside(nx, ny, nz) && geometry.solid[cellIndex(nx, ny, nz)]) {
			return cachedSurfaceTensionNormal(cellIndex(x, y, z), component);
		}
		return cachedSurfaceTensionNormal(cellIndex(Math.min(sizeX - 1, Math.max(0, nx)),
				Math.min(sizeY - 1, Math.max(0, ny)),
				Math.min(sizeZ - 1, Math.max(0, nz))), component);
	}

	double cachedSurfaceTensionNormal(int cell, int component) {
		return switch (component) {
			case 0 -> tension.surfaceTensionNormalX[cell];
			case 1 -> tension.surfaceTensionNormalY[cell];
			default -> tension.surfaceTensionNormalZ[cell];
		};
	}

	/**
	 * Divergence of the free-surface pressure-jump boundary velocity. This is the RHS source that makes
	 * the ghost-fluid projection consistent with {@code p_interface = sigma * kappa + p_gas} at every
	 * open air face; the gas term reads the sealed cavity that owns the air cell and stays exactly
	 * zero for ambient air, so the liquid-only path is bit-identical whenever no sealed cavity exists.
	 */
	float surfaceTensionBoundaryDivergence(int index, float dt) {
		if (!surfaceTensionActive && !gasState.pressureActive) return 0.0F;
		int x = index % sizeX;
		int yz = index / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		float inverseCell = 1.0F / config.cellSize();
		float scale = dt / (config.density() * config.cellSize());
		float result = 0.0F;
		if (x > 0) result += surfaceTensionBoundaryFace(index, cellIndex(x - 1, y, z), -1, 0, 0, scale, inverseCell);
		if (x + 1 < sizeX) result += surfaceTensionBoundaryFace(index, cellIndex(x + 1, y, z), 1, 0, 0, scale, inverseCell);
		if (y > 0) result += surfaceTensionBoundaryFace(index, cellIndex(x, y - 1, z), 0, -1, 0, scale, inverseCell);
		if (y + 1 < sizeY) result += surfaceTensionBoundaryFace(index, cellIndex(x, y + 1, z), 0, 1, 0, scale, inverseCell);
		if (z > 0) result += surfaceTensionBoundaryFace(index, cellIndex(x, y, z - 1), 0, 0, -1, scale, inverseCell);
		if (z + 1 < sizeZ) result += surfaceTensionBoundaryFace(index, cellIndex(x, y, z + 1), 0, 0, 1, scale, inverseCell);
		return result;
	}

	float surfaceTensionBoundaryFace(int fluid, int neighbor, int directionX, int directionY, int directionZ,
	                                         float scale, float inverseCell) {
		if (!isAir(neighbor)) return 0.0F;
		float openArea = pressureOpenFaceFromCell(fluid, neighbor, directionX, directionY, directionZ);
		if (openArea <= 0.0F) return 0.0F;
		PressureFaceCoupling coupling = pressureFaceCoupling(
				fluid, neighbor, directionX, directionY, directionZ);
		float gasPressure = domain().gasPressureOfAirCell(neighbor) - pressureReference;
		return -scale * inverseCell * coupling.firstDirichletRhs(openArea, gasPressure);
	}

	/**
	 * Unique oriented pressure-face algebra for matrix assembly, jump RHS and velocity projection.
	 * Open gas is Dirichlet and a finite sealed node may own a lumped pressure unknown, but neither
	 * has a spatial gas-inertia segment. A future spatial gas solve replaces only this one-sided
	 * metric construction, not the interface convention or jump algebra.
	 */
	PressureFaceCoupling pressureFaceCoupling(
			int first, int second, int directionX, int directionY, int directionZ
	) {
		boolean firstFluid = isFluid(first), secondFluid = isFluid(second);
		if (!firstFluid && !secondFluid) {
			throw new IllegalArgumentException("A pressure face must touch a pressure-fluid cell");
		}
		float densityScale = variableDensityActive ? pressureDensityScale(first, second) : 1.0F;
		if (firstFluid && secondFluid) return PressureFaceCoupling.centered(densityScale);
		int fluid = firstFluid ? first : second;
		int fluidDirectionX = firstFluid ? directionX : -directionX;
		int fluidDirectionY = firstFluid ? directionY : -directionY;
		int fluidDirectionZ = firstFluid ? directionZ : -directionZ;
		PressureFaceCoupling liquidToGas = PressureFaceCoupling.oneSided(
				densityScale,
				pressureInterfaceTheta(fluid, fluidDirectionX, fluidDirectionY, fluidDirectionZ),
				tension.surfaceTensionPressure[fluid]);
		return firstFluid ? liquidToGas : liquidToGas.reversed();
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

	float pressureOpenFaceFromCell(
			int cell, int neighbor, int directionX, int directionY, int directionZ
	) {
		float geometric = openFaceFromCell(cell, directionX, directionY, directionZ);
		if (pressureGranularCoupling == null || geometric <= 0.0F) return geometric;
		return geometric * pressureGranularCoupling.faceVoidScale(
				cell, geometry.cutCells.openCellVolume(cell), neighbor, geometry.cutCells.openCellVolume(neighbor));
	}

	float pressureXFaceOpen(int x, int y, int z) {
		float geometric = geometry.cutCells.xFaceOpen(x, y, z);
		if (pressureGranularCoupling == null || geometric <= 0.0F || x <= 0 || x >= sizeX) return geometric;
		int left = cellIndex(x - 1, y, z), right = cellIndex(x, y, z);
		return geometric * pressureGranularCoupling.faceVoidScale(
				left, geometry.cutCells.openCellVolume(left), right, geometry.cutCells.openCellVolume(right));
	}

	float pressureYFaceOpen(int x, int y, int z) {
		float geometric = geometry.cutCells.yFaceOpen(x, y, z);
		if (pressureGranularCoupling == null || geometric <= 0.0F || y <= 0 || y >= sizeY) return geometric;
		int below = cellIndex(x, y - 1, z), above = cellIndex(x, y, z);
		return geometric * pressureGranularCoupling.faceVoidScale(
				below, geometry.cutCells.openCellVolume(below), above, geometry.cutCells.openCellVolume(above));
	}

	float pressureZFaceOpen(int x, int y, int z) {
		float geometric = geometry.cutCells.zFaceOpen(x, y, z);
		if (pressureGranularCoupling == null || geometric <= 0.0F || z <= 0 || z >= sizeZ) return geometric;
		int back = cellIndex(x, y, z - 1), front = cellIndex(x, y, z);
		return geometric * pressureGranularCoupling.faceVoidScale(
				back, geometry.cutCells.openCellVolume(back), front, geometry.cutCells.openCellVolume(front));
	}

	void spawnCellVolume(int x, int y, int z, float amount) {
		int requiredParticles = particlesRequired(amount);
		if ((long) particles.count() + requiredParticles > maximumParticleCount) {
			throw new ParticleBudgetExceededException(requiredParticles, maximumParticleCount - particles.count());
		}
		float remaining = amount;
		int slot = firstOpenSlot(cellIndex(x, y, z), 0);
		while (remaining > 1.0E-6F) {
			if (slot < 0) throw new IllegalStateException("Cut-cell open volume was exhausted while spawning markers");
			float piece = Math.min(PARTICLE_VOLUME, remaining);
			spawnParticle(x, y, z, slot, piece);
			remaining -= piece;
			slot = firstOpenSlot(cellIndex(x, y, z), slot + 1);
		}
	}

	int firstOpenSlot(int cell, int first) {
		int mask = geometry.cutCells.mask(cell);
		for (int slot = first; slot < 8; slot++) if ((mask & 1 << slot) == 0) return slot;
		return -1;
	}

private void spawnParticle(int cellX, int cellY, int cellZ, int slot, float amount) {
spawnParticle(cellX, cellY, cellZ, slot, amount, 0);
}

private void spawnParticle(int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle) {
double mass = materialRegistry == null
? (double) amount * config.density()
: (double) amount * materialRegistry.material(materialHandle).referenceDensity();
spawnParticle(cellX, cellY, cellZ, slot, amount, materialHandle, mass);
}

private void spawnParticle(
int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle, double mass
) {
spawnParticle(cellX, cellY, cellZ, slot, amount, materialHandle, mass, null, false);
}

private void spawnParticle(
 int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle, double mass,
 ChemicalMixtureRecord chemicalMixture, boolean indicator
 ) {
	spawnParticle(cellX, cellY, cellZ, slot, amount, materialHandle, mass, chemicalMixture, indicator, true);
}

void spawnParticle(
 int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle, double mass,
 ChemicalMixtureRecord chemicalMixture, boolean indicator, boolean thermalExternal
 ) {
if (particles.count() >= maximumParticleCount) throw new ParticleBudgetExceededException(1, 0);
if (!(Float.isFinite(amount) && amount > 0.0F)) throw new IllegalArgumentException("Marker volume must be finite and positive");
if (!(Double.isFinite(mass) && mass > 0.0)) throw new IllegalArgumentException("Marker mass must be finite and positive");
if (materialRegistry != null) materialRegistry.material(materialHandle);
else if (materialHandle != 0) throw new IllegalArgumentException("Material handle requires a bound material registry");
if (chemicalParcels != null && chemicalMixture != null) {
chemicalParcels.requireCompatible(chemicalMixture, materialRegistry.material(materialHandle).id(), indicator);
}
float h = config.cellSize();
float radiusFraction = 0.45F;
float ox = (slot & 1) == 0 ? 0.25F : 0.75F;
float oy = (slot & 2) == 0 ? 0.25F : 0.75F;
float oz = (slot & 4) == 0 ? 0.25F : 0.75F;
if (cellX == 0 || geometry.solid[cellIndex(cellX - 1, cellY, cellZ)]) ox = Math.max(ox, radiusFraction);
if (cellX + 1 == sizeX || geometry.solid[cellIndex(cellX + 1, cellY, cellZ)]) ox = Math.min(ox, 1.0F - radiusFraction);
if (cellY == 0 || geometry.solid[cellIndex(cellX, cellY - 1, cellZ)]) oy = Math.max(oy, radiusFraction);
if (cellY + 1 == sizeY || geometry.solid[cellIndex(cellX, cellY + 1, cellZ)]) oy = Math.min(oy, 1.0F - radiusFraction);
if (cellZ == 0 || geometry.solid[cellIndex(cellX, cellY, cellZ - 1)]) oz = Math.max(oz, radiusFraction);
if (cellZ + 1 == sizeZ || geometry.solid[cellIndex(cellX, cellY, cellZ + 1)]) oz = Math.min(oz, 1.0F - radiusFraction);
coordinator.appendMarker(
CarrierMutationPlan.append(carrierMutationRevision(), particles.count()),
cellIndex(cellX, cellY, cellZ), (cellX + ox) * h, (cellY + oy) * h, (cellZ + oz) * h,
amount, materialHandle, mass, chemicalMixture, indicator, thermalExternal);
 }

private void spawnChemicalParticle(
int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle, double mass,
ChemicalMixtureRecord mixture, boolean indicator
) {
spawnParticle(cellX, cellY, cellZ, slot, amount, materialHandle, mass, mixture, indicator);
}

	/** Planned per-marker truncation for one cell, applied later without re-deciding anything. */
	record MarkerTruncation(
			int marker, float take, float newVolume, double newMass,
			ChemicalParcelStore.SplitPlan chemicalPlan, LiquidEnergyColumn.Truncation thermalPlan
	) { }

	record CellRemovalPlan(float removedVolume, List<MarkerTruncation> markers) { }

	CellRemovalPlan planRemoveParticlesInCell(
			int cellX, int cellY, int cellZ, float maximum,
			ChemicalTransferLedger.Builder removedChemistry,
			FluidDomain.RemovedMaterialLedger removedMaterials, FluidDomain.RemovedThermalEnergy removedThermal
	) {
		float h = config.cellSize(), removed = 0.0F;
		List<MarkerTruncation> plan = new ArrayList<>();
		for (int i = particles.count() - 1; i >= 0 && removed < maximum; i--) {
			if ((int) Math.floor(particles.positionX(i) / h) != cellX || (int) Math.floor(particles.positionY(i) / h) != cellY || (int) Math.floor(particles.positionZ(i) / h) != cellZ) continue;
			float previousVolume = particles.volume(i);
			float take = Math.min(previousVolume, maximum - removed);
			double previousMass = particles.mass(i);
			double previousTemperature = thermalState == null ? Double.NaN : domain().thermalMarkerTemperature(i);
			float newVolume = previousVolume - take;
			if (newVolume <= 1.0E-6F) {
				take = previousVolume;
				newVolume = 0.0F;
			}
			double newMass = previousMass * newVolume / previousVolume;
			ChemicalParcelStore.SplitPlan chemicalPlan = null;
			if (chemicalParcels != null) {
				chemicalPlan = chemicalParcels.planTruncate(
						i, newVolume, take, newMass, previousMass - newMass);
				if (removedChemistry != null) removedChemistry.add(
						materialRegistry.material(particles.materialHandle(i)).id(), chemicalParcels.indicator(i),
						take, unitScale.kilograms(previousMass - newMass), chemicalPlan.removed());
			}
			LiquidEnergyColumn.Truncation thermalPlan = domain().planThermalTruncate(i, newMass,
					chemicalPlan == null ? null : chemicalPlan.kept(), previousTemperature);
			if (removedMaterials != null) removedMaterials.add(
					particles.materialHandle(i), take, previousMass - newMass);
			if (removedThermal != null && thermalPlan != null) removedThermal.add(thermalPlan.removedEnergy());
			plan.add(new MarkerTruncation(i, take, newVolume, newMass, chemicalPlan, thermalPlan));
			removed += take;
		}
		return new CellRemovalPlan(removed, List.copyOf(plan));
	}

	void commitRemoveParticlesInCell(int cellX, int cellY, int cellZ, CellRemovalPlan plan) {
		for (MarkerTruncation entry : plan.markers()) {
			int i = entry.marker();
			if (chemicalParcels != null) {
				chemicalParcels.markDirtyCell(cellIndex(cellX, cellY, cellZ));
				if (entry.newVolume() > 0.0F) chemicalParcels.commitTruncate(i, entry.chemicalPlan());
			}
			particles.setVolume(i, entry.newVolume());
			particles.setMass(i, entry.newMass());
			domain().commitThermalTruncate(i, entry.thermalPlan());
			if (particles.volume(i) <= 1.0E-6F) removeParticle(i);
		}
	}

	/**
	 * Removes up to {@code amount} across the candidate cells as one joint audited removal: every
	 * cell is planned against the shared escaped-sink ledger first, the joint ledger is validated
	 * once, and only then are truncations applied and drained markers swept. A rejection on any
	 * cell therefore leaves every marker untouched.
	 */
	float removeVolumeAcrossCells(java.util.List<CellCandidate> candidates, float amount) {
		ChemicalTransferLedger.Builder removedChemistry = chemicalParcels == null ? null
				: ChemicalTransferLedger.builder(chemicalParcels.registry());
		record PlannedCell(int x, int y, int z, CellRemovalPlan plan) { }
		List<PlannedCell> planned = new ArrayList<>();
		float remaining = amount;
		for (CellCandidate candidate : candidates) {
			int index = candidate.index();
			int x = index % sizeX, yz = index / sizeX, y = yz % sizeY, z = yz / sizeY;
			CellRemovalPlan plan = planRemoveParticlesInCell(x, y, z, remaining, removedChemistry, null, null);
			if (!plan.markers().isEmpty()) planned.add(new PlannedCell(x, y, z, plan));
			remaining -= plan.removedVolume();
			if (remaining <= 1.0E-6F) break;
		}
		if (removedChemistry != null) chemicalParcels.requireCanRecordEscapedSink(removedChemistry.build());
		for (PlannedCell cell : planned) {
			int dirtyCell = cellIndex(cell.x(), cell.y(), cell.z());
			for (MarkerTruncation entry : cell.plan().markers()) {
				int i = entry.marker();
				if (chemicalParcels != null) {
					chemicalParcels.markDirtyCell(dirtyCell);
					if (entry.newVolume() > 0.0F) chemicalParcels.commitTruncate(i, entry.chemicalPlan());
				}
				particles.setVolume(i, entry.newVolume());
				particles.setMass(i, entry.newMass());
				domain().commitThermalTruncate(i, entry.thermalPlan());
			}
		}
		for (int marker = particles.count() - 1; marker >= 0; marker--) {
			if (particles.volume(marker) == 0.0F) removeParticle(marker);
		}
		if (removedChemistry != null) chemicalParcels.recordEscapedSink(removedChemistry.build());
		return amount - remaining;
	}

	/**
	 * Removes up to {@code maximum} from one cell. Every fallible chemical-sink check runs on the
	 * planned ledger before the first marker changes; the commit loop itself has no rejections.
	 */
	float removeParticlesInCell(int cellX, int cellY, int cellZ, float maximum) {
		ChemicalTransferLedger.Builder removedChemistry = chemicalParcels == null ? null
				: ChemicalTransferLedger.builder(chemicalParcels.registry());
		CellRemovalPlan plan = planRemoveParticlesInCell(cellX, cellY, cellZ, maximum, removedChemistry, null, null);
		if (removedChemistry != null) chemicalParcels.requireCanRecordEscapedSink(removedChemistry.build());
		commitRemoveParticlesInCell(cellX, cellY, cellZ, plan);
		if (removedChemistry != null) chemicalParcels.recordEscapedSink(removedChemistry.build());
		return plan.removedVolume();
	}

	float removeParticlesInCell(
			int cellX, int cellY, int cellZ, float maximum,
			ChemicalTransferLedger.Builder removedChemistry,
			FluidDomain.RemovedMaterialLedger removedMaterials, FluidDomain.RemovedThermalEnergy removedThermal
	) {
		CellRemovalPlan plan = planRemoveParticlesInCell(cellX, cellY, cellZ, maximum,
				removedChemistry, removedMaterials, removedThermal);
		commitRemoveParticlesInCell(cellX, cellY, cellZ, plan);
		return plan.removedVolume();
	}

	float removeParticlesInMicroMask(
			int cellX, int cellY, int cellZ, int microMask,
			ChemicalTransferLedger.Builder removedChemistry,
			FluidDomain.RemovedMaterialLedger removedMaterials, FluidDomain.RemovedThermalEnergy removedThermal
	) {
		float h = config.cellSize(), removed = 0.0F;
		for (int particle = particles.count() - 1; particle >= 0; particle--) {
			if ((int) Math.floor(particles.positionX(particle) / h) != cellX
					|| (int) Math.floor(particles.positionY(particle) / h) != cellY
					|| (int) Math.floor(particles.positionZ(particle) / h) != cellZ) continue;
			float localX = particles.positionX(particle) / h - cellX;
			float localY = particles.positionY(particle) / h - cellY;
			float localZ = particles.positionZ(particle) / h - cellZ;
			int microX = Math.min(1, Math.max(0, (int) Math.floor(localX * 2.0F)));
			int microY = Math.min(1, Math.max(0, (int) Math.floor(localY * 2.0F)));
			int microZ = Math.min(1, Math.max(0, (int) Math.floor(localZ * 2.0F)));
			int bit = 1 << (microX + 2 * (microY + 2 * microZ));
			if ((microMask & bit) == 0) continue;
			if (chemicalParcels != null && removedChemistry != null) removedChemistry.add(
					materialRegistry.material(particles.materialHandle(particle)).id(), chemicalParcels.indicator(particle),
					particles.volume(particle), unitScale.kilograms(particles.mass(particle)),
					chemicalParcels.record(particle));
			if (removedMaterials != null) removedMaterials.add(particles.materialHandle(particle),
					particles.volume(particle), particles.mass(particle));
			if (removedThermal != null && thermalState != null) {
				removedThermal.add(thermalState.liquidInternalEnergy(particle));
			}
			removed += particles.volume(particle);
			removeParticle(particle);
		}
		return removed;
	}

	int particleCountInCell(int cellX, int cellY, int cellZ) {
		float h = config.cellSize();
		int count = 0;
		for (int particle = 0; particle < particles.count(); particle++) {
			if ((int) Math.floor(particles.positionX(particle) / h) == cellX
					&& (int) Math.floor(particles.positionY(particle) / h) == cellY
					&& (int) Math.floor(particles.positionZ(particle) / h) == cellZ) count++;
		}
		return count;
	}

	float exactVolumeInCell(int cellX, int cellY, int cellZ) {
		float h = config.cellSize(), result = 0.0F;
		for (int particle = 0; particle < particles.count(); particle++) {
			if ((int) Math.floor(particles.positionX(particle) / h) == cellX
					&& (int) Math.floor(particles.positionY(particle) / h) == cellY
					&& (int) Math.floor(particles.positionZ(particle) / h) == cellZ) result += particles.volume(particle);
		}
		return result;
	}

	double[] exactCellVolumes() {
		double[] exact = new double[cellCount];
		float h = config.cellSize();
		for (int particle = 0; particle < particles.count(); particle++) {
			int x = (int) Math.floor(particles.positionX(particle) / h);
			int y = (int) Math.floor(particles.positionY(particle) / h);
			int z = (int) Math.floor(particles.positionZ(particle) / h);
			if (inside(x, y, z)) exact[cellIndex(x, y, z)] += particles.volume(particle);
		}
		return exact;
	}

	void removeParticle(int index) {
		coordinator.swapRemoveMarker(
				CarrierMutationPlan.swapRemove(carrierMutationRevision(), particles.count(), index), false);
	}

	double removeParticleInternalPhase(int index) {
		return coordinator.swapRemoveMarker(
				CarrierMutationPlan.swapRemove(carrierMutationRevision(), particles.count(), index), true);
	}

	void ensureParticleCapacity(int required) {
		if (required > maximumParticleCount) {
			throw new ParticleBudgetExceededException(required - particles.count(), maximumParticleCount - particles.count());
		}
		if (required <= particles.capacity()) return;
		int capacity = particles.ensureCapacity(required);
		markerGrid.ensureMarkerCapacity(capacity);
		if (thermalState != null) thermalState.ensureCapacity(capacity);
	}

	void setDefaultMaterialState(int particle) {
		particles.setMass(particle, (double) particles.volume(particle) * config.density());
		particles.setMaterialHandle(particle, 0);
		particles.setPhaseLabel(particle, 0);
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

	void ensureGrid() { if (gridDirty) particleToGridStage.rebuild(); }
	long carrierMutationRevision() { return carrierMutationRevision; }
	void bumpCarrierMutationRevision() { carrierMutationRevision++; }
	CarrierMutationCoordinator carrierMutations() { return coordinator; }
	boolean markerPositionAllowed(float x, float y, float z) {
		return !particleCollision.centerInSolid(x, y, z);
	}
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

	float pressureInterfaceTheta(int fluid, int directionX, int directionY, int directionZ) {
		return pressurePhaseGeometry.interfaceTheta(fluid, directionX, directionY, directionZ);
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

	void validateVolumeEdit(float amount, int maxRadius) {
		if (!Float.isFinite(amount) || amount < 0.0F) throw new IllegalArgumentException("amount must be finite and non-negative");
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
	}
	void validateMaterialPortion(FluidDomain.MaterialPortion portion) {
		if (portion == null || !(Double.isFinite(portion.volume()) && portion.volume() > 0.0
				&& portion.volume() <= Float.MAX_VALUE && (float) portion.volume() > 0.0F)
				|| !(Double.isFinite(portion.mass()) && portion.mass() > 0.0)) {
			throw new IllegalArgumentException("Material portion ledgers must be finite, positive and float-representable");
		}
		if (materialRegistry == null) {
			if (portion.materialHandle() != 0) {
				throw new IllegalArgumentException("Material handle requires a bound registry");
			}
		} else {
			materialRegistry.material(portion.materialHandle());
		}
	}
	static int particlesRequired(float amount) {
		if (amount <= 1.0E-6F) return 0;
		return (int) Math.ceil((amount - 1.0E-6F) / PARTICLE_VOLUME);
	}
	static float clamp01(float value) { return clamp(value, 0.0F, 1.0F); }
	static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
	static float lerp(float first, float second, float t) { return first + (second - first) * t; }

}
