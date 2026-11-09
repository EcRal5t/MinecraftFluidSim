package mmm.fluid;

import java.util.List;
import java.util.ArrayList;
import static mmm.fluid.HostFluidState.*;
import static mmm.fluid.FluidDomainContracts.*;

/** Rebuilds the liquid inventory grid, shared geometry, velocity and surface-tension fields. */
final class DomainFlowFields {
	private final HostFluidState domain;
	DomainFlowFields(HostFluidState domain) { this.domain = domain; }
	int dominantPhase(int cell) {
		int dominant = HostFluidState.NO_PHASE;
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
		int x = domain.grid.x(cell), y = domain.grid.y(cell), z = domain.grid.z(cell);
		if (directionX < 0) return domain.geometry.cutCells.xFaceOpen(x, y, z);
		if (directionX > 0) return domain.geometry.cutCells.xFaceOpen(x + 1, y, z);
		if (directionY < 0) return domain.geometry.cutCells.yFaceOpen(x, y, z);
		if (directionY > 0) return domain.geometry.cutCells.yFaceOpen(x, y + 1, z);
		if (directionZ < 0) return domain.geometry.cutCells.zFaceOpen(x, y, z);
		if (directionZ > 0) return domain.geometry.cutCells.zFaceOpen(x, y, z + 1);
		throw new IllegalArgumentException("Pressure face direction must be axial");
	}
	float pressureOpenFaceFromCell(int cell, int neighbor, int dx, int dy, int dz) {
		int x = domain.grid.x(cell), y = domain.grid.y(cell), z = domain.grid.z(cell);
		if (dx != 0) return pressureXFaceOpen(x + (dx > 0 ? 1 : 0), y, z);
		if (dy != 0) return pressureYFaceOpen(x, y + (dy > 0 ? 1 : 0), z);
		return pressureZFaceOpen(x, y, z + (dz > 0 ? 1 : 0));
	}
	float pressureXFaceOpen(int x, int y, int z) {
		return domain.granularCoupling == null ? domain.geometry.cutCells.xFaceOpen(x, y, z)
				: domain.pressureSystem.solidFaces.open(0, xFaceIndex(x, y, z));
	}
	float pressureYFaceOpen(int x, int y, int z) {
		return domain.granularCoupling == null ? domain.geometry.cutCells.yFaceOpen(x, y, z)
				: domain.pressureSystem.solidFaces.open(1, yFaceIndex(x, y, z));
	}
	float pressureZFaceOpen(int x, int y, int z) {
		return domain.granularCoupling == null ? domain.geometry.cutCells.zFaceOpen(x, y, z)
				: domain.pressureSystem.solidFaces.open(2, zFaceIndex(x, y, z));
	}
	float[] markerSlotPosition(int cellX, int cellY, int cellZ, int slot) {
		float h = domain.config.cellSize();
		float radiusFraction = ConservativeMarkerSampling.DOMAIN_CONTACT_FRACTION;
		float ox = (slot & 1) == 0 ? 0.25F : 0.75F;
		float oy = (slot & 2) == 0 ? 0.25F : 0.75F;
		float oz = (slot & 4) == 0 ? 0.25F : 0.75F;
		if (solidOrMissing(cellX-1,cellY,cellZ)) ox = Math.max(ox, radiusFraction);
		if (solidOrMissing(cellX+1,cellY,cellZ)) ox = Math.min(ox, 1.0F - radiusFraction);
		if (solidOrMissing(cellX,cellY-1,cellZ)) oy = Math.max(oy, radiusFraction);
		if (solidOrMissing(cellX,cellY+1,cellZ)) oy = Math.min(oy, 1.0F - radiusFraction);
		if (solidOrMissing(cellX,cellY,cellZ-1)) oz = Math.max(oz, radiusFraction);
		if (solidOrMissing(cellX,cellY,cellZ+1)) oz = Math.min(oz, 1.0F - radiusFraction);
		float px = (cellX + ox) * h, py = (cellY + oy) * h, pz = (cellZ + oz) * h;
		if (Math.floor(px / h) != cellX || Math.floor(py / h) != cellY || Math.floor(pz / h) != cellZ
				|| CellRemapMicroTopology.positionMicro(h, px, py, pz) != slot)
			throw new IllegalArgumentException("Cell marker slot exceeds its binary32 coordinate representation");
		return new float[] {px, py, pz};
	}
	private boolean solidOrMissing(int x,int y,int z) {
		int cell=domain.grid.cell(x,y,z);return cell<0||domain.geometry.cutCells.fullySolid(cell);
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
		return reachableCells(centerX, centerY, centerZ, maxRadius, false);
	}
	List<CellCandidate> editableNearbyCells(int x, int y, int z, int radius) {
		return reachableCells(x, y, z, radius, true);
	}
	private List<CellCandidate> reachableCells(int x, int y, int z, int radius, boolean editable) {
		int start = cellIndexChecked(x, y, z);
		var result = new ArrayList<CellCandidate>();
		if (domain.geometry.cutCells.fullySolid(start) || editable && !domain.groupAvailable(start)) return result;
		if (radius > 0 && domain.granularCoupling != null) domain.pressureSystem.prepareGeometry();
		boolean[] visited = new boolean[domain.cellCount];
		var pending = new java.util.ArrayDeque<int[]>();
		visited[start] = true; pending.add(new int[]{start, 0});
		int[] dx = {-1, 1, 0, 0, 0, 0}, dy = {0, 0, -1, 1, 0, 0}, dz = {0, 0, 0, 0, -1, 1};
		while (!pending.isEmpty()) {
			var current = pending.removeFirst(); int cell = current[0], distance = current[1];
			result.add(new CellCandidate(cell, distance * distance));
			if (distance >= radius) continue;
			int cx = domain.grid.x(cell), cy = domain.grid.y(cell), cz = domain.grid.z(cell);
			for (int direction = 0; direction < 6; direction++) {
				int nx = cx + dx[direction], ny = cy + dy[direction], nz = cz + dz[direction];
				if (!inside(nx, ny, nz)) continue;
				int next = cellIndex(nx, ny, nz);
				if (visited[next] || domain.geometry.cutCells.fullySolid(next) || editable && !domain.groupAvailable(next)
						|| pressureOpenFaceFromCell(cell, next, dx[direction], dy[direction], dz[direction]) <= 0) continue;
				visited[next] = true; pending.addLast(new int[]{next, distance + 1});
			}
		}
		return result;
	}

	void preparePhysicalState() {
		domain.pressureSystem.prepareGeometry();
		ensureVelocityGrid();
	}
	void ensureVelocityGrid() {
		ensureInventoryGrid();
		if (domain.velocityGridDirty) domain.cellMacStage.rebuildVelocity();
	}
	void ensureInventoryGrid() { domain.cellMacStage.rebuildInventory(); }
	long carrierMutationRevision() { return domain.carrierMutationRevision; }
	boolean isFluid(int index) { return domain.liquidGrid.stockCount[index]>0; }
	boolean isAir(int index) { return !domain.geometry.cutCells.fullySolid(index) && !isFluid(index); }
	boolean carrierFaceActive(int first, int second, float openArea) {
		return openArea > 0.0F && !domain.geometry.cutCells.fullySolid(first) && !domain.geometry.cutCells.fullySolid(second)
				&& (domain.liquidGrid.stockCount[first] > 0 || domain.liquidGrid.stockCount[second] > 0);
	}
	boolean granularCarrierFaceActive(int axis, int x, int y, int z) {
		int ax = x - (axis == 0 ? 1 : 0), ay = y - (axis == 1 ? 1 : 0), az = z - (axis == 2 ? 1 : 0);
		if (!inside(ax, ay, az) || !inside(x, y, z)) return false;
		int first = cellIndex(ax, ay, az), second = cellIndex(x, y, z);
		float open = pressureOpenFaceFromCell(first, second, x - ax, y - ay, z - az);
		return open > 0 && !domain.geometry.cutCells.fullySolid(first) && !domain.geometry.cutCells.fullySolid(second)
				&& (isFluid(first) || isFluid(second) || carrierFaceActive(first, second, open));
	}
	boolean inside(int x, int y, int z) { return domain.grid.cell(x,y,z)>=0; }
	int cellIndex(int x, int y, int z) { return domain.grid.cell(x,y,z); }
	int xFaceIndex(int x, int y, int z) { return domain.grid.faceAt(x,y,z,0)-domain.grid.faceOffset(0); }
	int yFaceIndex(int x, int y, int z) { return domain.grid.faceAt(x,y,z,1)-domain.grid.faceOffset(1); }
	int zFaceIndex(int x, int y, int z) { return domain.grid.faceAt(x,y,z,2)-domain.grid.faceOffset(2); }
	int cellIndexChecked(int x, int y, int z) { if (!inside(x, y, z)) throw new IndexOutOfBoundsException(); return cellIndex(x, y, z); }
	float sampleX(float[] field, float x, float y, float z) {
		float h = domain.config.cellSize();
		return sampleFace(field, domain.mac.velocityValidX,0, domain.sizeX + 1, domain.sizeY, domain.sizeZ,
				x / h, y / h - 0.5F, z / h - 0.5F);
	}
	float sampleY(float[] field, float x, float y, float z) {
		float h = domain.config.cellSize();
		return sampleFace(field, domain.mac.velocityValidY,1, domain.sizeX, domain.sizeY + 1, domain.sizeZ,
				x / h - 0.5F, y / h, z / h - 0.5F);
	}
	float sampleZ(float[] field, float x, float y, float z) {
		float h = domain.config.cellSize();
		return sampleFace(field, domain.mac.velocityValidZ,2, domain.sizeX, domain.sizeY, domain.sizeZ + 1,
				x / h - 0.5F, y / h - 0.5F, z / h);
	}
	private float sampleFace(float[] values,boolean[] valid,int axis,int nx,int ny,int nz,float x,float y,float z) {
		float cx=clamp(x,0,nx-1),cy=clamp(y,0,ny-1),cz=clamp(z,0,nz-1);
		int x0=(int)Math.floor(cx),y0=(int)Math.floor(cy),z0=(int)Math.floor(cz);
		float weighted=0,total=0;
		for(int dz=0;dz<2;dz++)for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++){
			int sx=Math.min(x0+dx,nx-1),sy=Math.min(y0+dy,ny-1),sz=Math.min(z0+dz,nz-1);
			float weight=(dx==0?1-(cx-x0):cx-x0)*(dy==0?1-(cy-y0):cy-y0)*(dz==0?1-(cz-z0):cz-z0);
			int face=domain.grid.faceAt(sx,sy,sz,axis);
			if(face<0||weight<=0)continue;
			int local=face-domain.grid.faceOffset(axis);
			if(valid[local]){weighted+=values[local]*weight;total+=weight;}
		}
		return total<=1.0E-8F?0:weighted/total;
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
	void validateMaterialPortion(FluidDomainContracts.MaterialPortion portion) {
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
