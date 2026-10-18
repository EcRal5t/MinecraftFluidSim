package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import java.util.Arrays;
import mmm.units.SignedDyadic;

/** Shared sparse volume incidence and its transpose for liquid capacity and finite gas storage. */
final class PressureFaceGraph {
	interface SideConsumer { void accept(int row, double weight, double fixedPressure); }
	private final FluidDomain domain;
	final int[] offsets = new int[3];
	final int faceCount;
	final int[] firstCell, secondCell, axis, localFace, start, fullSupports;
	final double[] inverseMass, affine;
	private int[] rows;
	private double[] coefficients;
	private int entries;
	private final int[] liquidStart;
	private int[] liquidLeftSupport, liquidRightSupport;
	private double[] liquidCoefficient;
	private int liquidEntries;
	private LiquidCellInventory.Snapshot coefficientInventory;
	private Object coefficientFrame;
	private final int[] parent;
	private final boolean[] constrained, anchored;

	PressureFaceGraph(FluidDomain domain) {
		this.domain = domain;
		offsets[1] = domain.mac.velocityX.length; offsets[2] = offsets[1] + domain.mac.velocityY.length;
		faceCount = offsets[2] + domain.mac.velocityZ.length;
		firstCell = new int[faceCount]; secondCell = new int[faceCount]; axis = new int[faceCount]; localFace = new int[faceCount];
		start = new int[faceCount + 1]; liquidStart = new int[faceCount + 1];
		liquidLeftSupport = new int[4 * faceCount]; liquidRightSupport = new int[liquidLeftSupport.length];
		liquidCoefficient = new double[liquidLeftSupport.length]; inverseMass = new double[faceCount]; affine = new double[faceCount];
		fullSupports = new int[domain.cellCount]; parent = new int[domain.cellCount];
		constrained = new boolean[domain.cellCount]; anchored = constrained.clone();
		rows = new int[4 * faceCount]; coefficients = new double[rows.length];
		Arrays.fill(firstCell, -1); Arrays.fill(secondCell, -1);
		for (int a = 0; a < 3; a++) {
			int nx = domain.sizeX + (a == 0 ? 1 : 0), ny = domain.sizeY + (a == 1 ? 1 : 0), nz = domain.sizeZ + (a == 2 ? 1 : 0);
			for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
				int local = (z * ny + y) * nx + x, face = offsets[a] + local;
				axis[face] = a; localFace[face] = local;
				int coordinate = a == 0 ? x : a == 1 ? y : z, length = a == 0 ? domain.sizeX : a == 1 ? domain.sizeY : domain.sizeZ;
				if (coordinate == 0 || coordinate == length) continue;
				secondCell[face] = domain.cellIndex(x, y, z);
				firstCell[face] = domain.cellIndex(x - (a == 0 ? 1 : 0), y - (a == 1 ? 1 : 0), z - (a == 2 ? 1 : 0));
			}
		}
	}

	void classify() {
		Arrays.fill(fullSupports, 0);
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int mask = domain.geometry.cutCells.mask(cell); int[] components = CellRemapMicroTopology.components(mask);
			var capacities = CellRemapMicroTopology.capacities(mask, SignedDyadic.of(poreVolume(cell)));
			for (int j = 0; j < components.length; j++) {
				var volume = SignedDyadic.ZERO; long mass = 0;
				for (var stock : domain.liquidCells.stocksInCell(cell)) if (stock.key().supportMask() == components[j]) {
					volume = volume.add(stock.volume()); mass = Math.addExact(mass, stock.massUnits());
				}
				if (mass > 0 && capacities[j].signum() > 0
						&& capacities[j].subtract(volume).toDouble() <= mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION)
					fullSupports[cell] |= components[j];
			}
			domain.projection.pressureFluid[cell] = fullSupports[cell] != 0;
		}
	}

	void rebuild(float dt) {
		classify();
		var p = domain.projection; int count = domain.gasState.network.nodeCount(); p.ensureGasCapacity(count);
		Arrays.fill(p.gasPressureUnknown, false); p.gasUnknownCount = p.fluidUnknownCount = 0;
		Arrays.fill(p.gasStorageCoefficient, 0); Arrays.fill(p.gasMatrixDiagonal, 0);
		for (int node = 1; node <= count; node++) {
			p.gasPressureUnknown[node] = true; p.gasUnknownNodes[p.gasUnknownCount++] = node;
			if(domain.pressureBoundaryView.gasNodeHasContinuumState(node))
				p.gasStorageCoefficient[node] = domain.config.density() * domain.pressureBoundaryView.gasNodeVolumeCompliance(node)
						/ (dt * (double) dt * domain.unitScale.cubicMetersPerNormalizedVolume());
		}
		entries = liquidEntries = 0; Arrays.fill(inverseMass, 0); Arrays.fill(affine, 0);
		Arrays.fill(constrained, false); Arrays.fill(anchored, false);
		for (int c = 0; c < parent.length; c++) parent[c] = c;
		for (int f = 0; f < faceCount; f++) {
			start[f] = entries; liquidStart[f] = liquidEntries; int a = firstCell[f], b = secondCell[f]; if (a < 0) continue;
			double mass = faceMass(f); if (!(mass > 0)) continue;
			int d = axis[f]; double open = domain.pressureSystem.solidFaces.open(d, localFace[f]);
			if (!(open > 0)) continue;
			inverseMass[f] = domain.unitScale.cubicMetersPerNormalizedVolume() / mass;
			final int face = f; double coefficient = open / domain.config.cellSize();
			int maskA = domain.geometry.cutCells.mask(a), maskB = domain.geometry.cutCells.mask(b), ports = 0;
			for (int micro = 0; micro < 8; micro++) if ((micro >>> d & 1) != 0
					&& (maskA & 1 << micro) == 0 && (maskB & 1 << (micro ^ 1 << d)) == 0) ports++;

			for (int micro = 0; micro < 8; micro++) if ((micro >>> d & 1) != 0
					&& (maskA & 1 << micro) == 0 && (maskB & 1 << (micro ^ 1 << d)) == 0) {
				int peer = micro ^ 1 << d;
				int supportA = CellRemapMicroTopology.support(maskA, micro), supportB = CellRemapMicroTopology.support(maskB, peer);
				double portArea = open / ports;
				addLiquidPort(f, supportA, supportB, open / ports);
				pressureAtMicro(a, micro, 1, (row, weight, fixed) -> add(face, row, portArea / domain.config.cellSize() * weight, fixed));
				pressureAtMicro(b, peer, 1, (row, weight, fixed) -> add(face, row, -portArea / domain.config.cellSize() * weight, fixed));
			}
			int kept = start[f];
			for (int i = start[f]; i < entries; i++) if (coefficients[i] != 0) { rows[kept] = rows[i]; coefficients[kept++] = coefficients[i]; }
			entries = kept;
			int liquid = -1; boolean reference = false; double sum = 0;
			for (int i = start[f]; i < entries; i++) {
				int row = rows[i]; sum += coefficients[i];
				if (row >= domain.cellCount) reference = true;
				else if (liquid < 0) liquid = row; else union(liquid, row);
			}
			if (liquid >= 0 && (reference || Math.abs(sum) > 16 * Math.ulp(coefficient))) constrained[liquid] = true;
		}
		start[faceCount] = entries; liquidStart[faceCount] = liquidEntries;
		for (int c = 0; c < parent.length; c++) if (constrained[c]) constrained[root(c)] = true;
		for (int c = 0; c < parent.length; c++) if (domain.isFluid(c)) {
			int group = root(c);
			if (!constrained[group] && !anchored[group]) { anchored[group] = true; anchored[c] = true; }
			else p.fluidUnknownCells[p.fluidUnknownCount++] = c;
		}
		for (int i = 0; i < entries; i++) if (rows[i] < domain.cellCount && anchored[rows[i]]) rows[i] = -1;
		domain.pressureAnchor = -1; domain.pressureReference = 0;
		coefficientInventory = domain.liquidCells.snapshot(); coefficientFrame = domain.gasState.frame;
	}

	void forEachCell(int cell,SideConsumer consumer) {
		int mask=domain.geometry.cutCells.mask(cell),open=Integer.bitCount((~mask)&255);
		if(open==0)return;
		for(int micro=0;micro<8;micro++)if((mask&1<<micro)==0)pressureAtMicro(cell,micro,1.0/open,consumer);
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
			pressureAtMicro(cell, micro, 1.0 / ports, consumer);
		}
	}
	private void pressureAtMicro(int cell, int micro, double weight, SideConsumer consumer) {
		if ((fullSupports[cell] & 1 << micro) != 0) { consumer.accept(anchored[cell] ? -1 : cell, weight, -domain.tension.surfaceTensionPressure[cell]); return; }
		int node = domain.gasState.frame.nodeOfPiece(8 * cell + micro);
		if (node >= 0) { gasSide(cell, node, weight, consumer); return; }
		int support = CellRemapMicroTopology.support(domain.geometry.cutCells.mask(cell), micro); double total = 0;
		for (int bit = 0; bit < 8; bit++) if ((support & 1 << bit) != 0 && domain.gasState.frame.nodeOfPiece(8 * cell + bit) >= 0)
			total += domain.gasState.frame.geometry().piece(8 * cell + bit).normalizedVolume();
		if (!(total > 0)) { consumer.accept(anchored[cell] ? -1 : cell, weight, -domain.tension.surfaceTensionPressure[cell]); return; }
		for (int bit = 0; bit < 8; bit++) if ((support & 1 << bit) != 0) {
			int owner = domain.gasState.frame.nodeOfPiece(8 * cell + bit); if (owner < 0) continue;
			gasSide(cell, owner, weight * domain.gasState.frame.geometry().piece(8 * cell + bit).normalizedVolume() / total, consumer);
		}
	}
	private double poreVolume(int cell) {
		return domain.geometry.cutCells.openCellVolume(cell) - (domain.granularCoupling == null ? 0
				: Math.scalb((double) domain.granularCoupling.solidSvq(cell), -24));
	}

	void ensurePortCoefficients() {
		domain.ensureGrid();
		if (coefficientInventory == null || !domain.liquidCells.hasSameGeometry(coefficientInventory) || coefficientFrame != domain.gasState.frame)
			rebuild(domain.config.timeStep());
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

	private void gasSide(int cell, int node, double weight, SideConsumer consumer) {
		boolean variable = node > 0 && domain.projection.gasPressureUnknown[node];
		double fixed = variable || node == 0 ? 0 : domain.pressureBoundaryView.gasPressureOfNode(node);
		consumer.accept(variable ? domain.cellCount + node : -1, weight, fixed + partialPressureOffset(cell));
	}
	private double partialPressureOffset(int cell) {
		if (domain.markerGrid.markerCount[cell] == 0) return 0;
		return -domain.tension.surfaceTensionPressure[cell] * domain.pressurePhaseGeometry.liquidFraction(cell) + hydrostaticOffset(cell);
	}
	double hydrostaticOffset(int cell) {
		if (domain.markerGrid.markerCount[cell] == 0) return 0;
		var phase = domain.pressurePhaseGeometry;
		double nx = phase.planeNormal(cell, 0), ny = phase.planeNormal(cell, 1), nz = phase.planeNormal(cell, 2);
		double norm = Math.sqrt(nx * nx + ny * ny + nz * nz);
		double upward = norm > 0 ? Math.max(0, ny / norm) : 0;
		// Cell-scale centroid pressure of a partially filled layer; the shared PLIC supplies its orientation.
		return .5 * domain.markerGrid.cellDensity[cell]
				* domain.config.gravity() * domain.config.cellSize() * phase.liquidFraction(cell) * upward;
	}
	private void add(int face, int row, double coefficient, double fixed) {
		affine[face] += coefficient * fixed;
		if (row < 0) return;
		for (int i = start[face]; i < entries; i++) if (rows[i] == row) { coefficients[i] += coefficient; return; }
		if (entries == rows.length) { rows = Arrays.copyOf(rows, entries * 2); coefficients = Arrays.copyOf(coefficients, rows.length); }
		rows[entries] = row; coefficients[entries++] = coefficient;
	}
	double pressureDifference(int face,double[] pressure) {
		int a=firstCell[face],b=secondCell[face];if(a<0)return 0;
		double area=fluidArea(face);if(area>0){double sum=affine[face];for(int i=start[face];i<start[face+1];i++)sum+=coefficients[i]*pressure[rows[i]];return sum*domain.config.cellSize()/area;}
		double[] value={0};
		forEachSide(a,b,(row,weight,fixed)->value[0]+=weight*(fixed+(row<0?0:pressure[row])));
		forEachSide(b,a,(row,weight,fixed)->value[0]-=weight*(fixed+(row<0?0:pressure[row])));
		return value[0];
	}
	double fluidArea(int face){return domain.pressureSystem.solidFaces.open(axis[face],localFace[face]);}
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
