package mmm.fluid;

import java.util.Arrays;

/** One capacity/gas pressure operator with the same face inertia and explicit granular couplings. */
final class PressureSystem {
	private final FluidDomain domain;
	private final GranularPressureCoupling granular;
	private final GranularImplicitDrag drag;
	private final PressureLinearSolver solver;
	private final PressureLinearSolver.Operator linearOperator;
	final GranularFaceVolumes solidFaces;
	final PressureFaceGraph faces;
	private final double[] velocityWork;
	private double[] pressure = new double[0];
	private float duration;
	private PressureBoundaryView.Compression[] gasCompression = new PressureBoundaryView.Compression[0];
	private double[] gasStorageRhs = new double[0], gasTargetVolume = new double[0];
	private double[] point = new double[0], trial = new double[0], nonlinearProduct = new double[0], nonlinearRhs = new double[0];
	private final java.util.HashMap<Long, mmm.units.SignedDyadic> gasReservations = new java.util.HashMap<>();
	private double liquidResidual, gasVolumeResidual;

	PressureSystem(FluidDomain domain) {
		this.domain = domain;
		solidFaces = new GranularFaceVolumes(domain); faces = new PressureFaceGraph(domain);
		granular = new GranularPressureCoupling(domain); drag = new GranularImplicitDrag(domain, granular);
		velocityWork = new double[faces.faceCount];
		linearOperator = new PressureLinearSolver.Operator() {
			@Override public void buildRhs(double[] result) {
				Arrays.fill(result, 0); readVelocity(velocityWork);
				faces.addDivergence(velocityWork, result, -domain.config.density() / duration);
				var p = domain.projection;
				for (int i = 0; i < p.fluidUnknownCount; i++) { int cell = p.fluidUnknownCells[i]; result[cell] += p.rhs[cell]; }
				for (int i = 0; i < p.gasUnknownCount; i++) { int node = p.gasUnknownNodes[i];
					result[domain.cellCount + node] += p.gasRhs[node]; }
				addStorageRhs(result, duration);
			}
			@Override public void applyMatrix(double[] source, double[] result) {
				Arrays.fill(result, 0); faces.pressureVelocity(source, velocityWork);
				faces.addDivergence(velocityWork, result, domain.config.density());
				var p = domain.projection;
				for (int i = 0; i < p.gasUnknownCount; i++) { int node = p.gasUnknownNodes[i]; p.gasMatrixInput[node] = source[domain.cellCount + node]; }
				Arrays.fill(p.gasMatrixProduct, 0);
				granular.apply(source, p.gasMatrixInput, result, p.gasMatrixProduct);
				for (int i = 0; i < p.gasUnknownCount; i++) { int node = p.gasUnknownNodes[i];
					result[domain.cellCount + node] += p.gasMatrixProduct[node] + p.gasStorageCoefficient[node] * source[domain.cellCount + node]; }
			}
		};
		solver = new PressureLinearSolver(domain, linearOperator);
	}
	PressureProjectionResult solveProjection(float dt) {
		duration = dt; domain.ensureGrid(); faces.rebuild(dt);
		var p = domain.projection; Arrays.fill(p.rhs, 0); Arrays.fill(p.gasRhs, 0);
		Arrays.fill(p.pressure, 0); Arrays.fill(p.gasPressure, 0); Arrays.fill(p.divergence, 0);
		prepareGasCompression();
		granular.prepare(dt); faces.addRelativeSolidCoupling(granular);
		// Fixed interface loads and the paired parcel reaction join the same prediction.
		for (int f = 0; f < faces.faceCount; f++) {
			int a = faces.axis[f], local = faces.localFace[f];
			float[] velocity = a == 0 ? domain.mac.velocityX : a == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
			velocity[local] += dt * faces.inverseMass[f] * faces.affine[f];
		}
		buildDiagonal(); drag.prepare(dt); solver.reset();
		pressure = drag.active() ? drag.pressure() : solver.pressure();
		int size = pressure.length;
		point = new double[size]; trial = new double[size]; nonlinearProduct = new double[size]; nonlinearRhs = new double[size];
		for (int i = 0; i < p.gasUnknownCount; i++) {
			int node = p.gasUnknownNodes[i];
			pressure[domain.cellCount + node] = gasCompression[node].pressure() - mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE;
		}
		int total = 0; double residual = Double.POSITIVE_INFINITY;
		for (int iteration = 0; iteration < 8; iteration++) {
			System.arraycopy(pressure, 0, point, 0, size);
			linearizeGas(point); buildDiagonal(); granular.rebuildDiagonal(drag::parcelInertiaRatio);
			PressureLinearSolver.Operator operator = drag.active() ? drag : linearOperator;
			double before = nonlinearResidual(operator, point);
			var result = drag.active() ? drag.solvePressure(domain.config.pressureTolerance())
					: solver.solve(granular, domain.config.pressureTolerance());
			total += result.iterations();
			pressure = drag.active() ? drag.pressure() : solver.pressure();
			if (!result.converged()) { publishPressure(); return new PressureProjectionResult(total, result.residualRms(), false); }
			System.arraycopy(pressure, 0, trial, 0, size);
			double scale = 1;
			for (int i = 0; i < p.gasUnknownCount; i++) {
				int row = domain.cellCount + p.gasUnknownNodes[i];
				double absolute = point[row] + mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE;
				if (trial[row] + mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE <= 0)
					scale = Math.min(scale, .8 * absolute / (point[row] - trial[row]));
			}
			for (int search = 0; search < 12; search++) {
				for (int row = 0; row < size; row++) pressure[row] = point[row] + scale * (trial[row] - point[row]);
				residual = nonlinearResidual(operator, pressure);
				if (residual <= Math.max(domain.config.pressureTolerance(), 2 * result.residualRms()) || residual < before) break;
				scale *= .5;
			}
			if (Boolean.getBoolean("mmm.pressure.trace")) System.out.printf("GAS_NEWTON i=%d before=%g after=%g liquid=%g gasVolume=%g linear=%g scale=%g%n",
					iteration, before, residual, liquidResidual, gasVolumeResidual, result.residualRms(), scale);
			if (liquidResidual <= Math.max(domain.config.pressureTolerance(), 2 * result.residualRms())
					&& gasVolumeResidual <= FlipPressurePhaseGeometry.CELL_VOLUME_RESOLUTION) {
				for (int i = 0; i < p.gasUnknownCount; i++) {
					int node = p.gasUnknownNodes[i]; gasTargetVolume[node] = gasCompression[node]
							.atPressure(pressure[domain.cellCount + node] + mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE).volume();
				}
				publishPressure(); return new PressureProjectionResult(total, residual, true);
			}
		}
		publishPressure();
		return new PressureProjectionResult(total, residual, false);
	}
	private void prepareGasCompression() {
		gasReservations.clear();
		int count = domain.gasState.network.nodeCount();
		gasCompression = new PressureBoundaryView.Compression[count + 1];
		gasStorageRhs = new double[count + 1]; gasTargetVolume = new double[count + 1];
		for (int i = 0; i < domain.projection.gasUnknownCount; i++) {
			int node = domain.projection.gasUnknownNodes[i];
			gasCompression[node] = domain.pressureBoundaryView.prepareCompression(node);
		}
	}
	private double gasFactor() {
		return domain.config.density() / (duration * (double) duration * domain.unitScale.cubicMetersPerNormalizedVolume());
	}
	private void linearizeGas(double[] point) {
		var p = domain.projection; double factor = gasFactor();
		for (int i = 0; i < p.gasUnknownCount; i++) {
			int node = p.gasUnknownNodes[i]; double gauge = point[domain.cellCount + node];
			var state = gasCompression[node].atPressure(gauge + mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE);
			p.gasStorageCoefficient[node] = factor * state.compliance();
			gasStorageRhs[node] = factor * (state.volume() - gasCompression[node].volume() + state.compliance() * gauge);
		}
	}
	private double nonlinearResidual(PressureLinearSolver.Operator operator, double[] source) {
		operator.applyMatrix(source, nonlinearProduct); operator.buildRhs(nonlinearRhs);
		var p = domain.projection; double norm = 0, factor = gasFactor();
		for (int i = 0; i < p.fluidUnknownCount; i++) {
			int row = p.fluidUnknownCells[i]; norm = Math.hypot(norm, nonlinearProduct[row] - nonlinearRhs[row]);
		}
		liquidResidual = norm / Math.sqrt(Math.max(1, p.fluidUnknownCount + p.gasUnknownCount));
		gasVolumeResidual = 0;
		for (int i = 0; i < p.gasUnknownCount; i++) {
			int node = p.gasUnknownNodes[i], row = domain.cellCount + node;
			var state = gasCompression[node].atPressure(source[row] + mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE);
			double residual = nonlinearProduct[row] - nonlinearRhs[row] - p.gasStorageCoefficient[node] * source[row]
					+ gasStorageRhs[node] + factor * (gasCompression[node].volume() - state.volume());
			norm = Math.hypot(norm, residual);
			gasVolumeResidual = Math.max(gasVolumeResidual, Math.abs(residual) * duration * duration / domain.config.density());
		}
		return norm / Math.sqrt(Math.max(1, p.fluidUnknownCount + p.gasUnknownCount));
	}

	/** EOS volumes join liquid donors in one fixed-support pore allocation. */
	GasVolumeProposal prepareGasReservations(int[] cells, int[] supportMasks, mmm.units.SignedDyadic[] predictedLiquid,
	                            mmm.units.SignedDyadic[] futurePore) {
		gasReservations.clear();
		if (domain.projection.gasUnknownCount == 0) return null;
		if (cells.length != supportMasks.length || cells.length != predictedLiquid.length || cells.length != futurePore.length)
			throw new IllegalArgumentException("Gas reservation component columns differ");
		var vertices = new java.util.ArrayList<GasSupport>();
		int[] atPiece = new int[8 * domain.cellCount]; java.util.Arrays.fill(atPiece, -1);
		for (int j = 0; j < cells.length; j++) {
			int cell = cells[j], support = supportMasks[j];
			var owners = new java.util.TreeMap<Integer, Double>();
			for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
				int node = domain.gasState.frame.nodeOfPiece(8 * cell + micro);
				if (node > 0 && node < gasTargetVolume.length && gasTargetVolume[node] > 0)
					owners.merge(node, domain.gasState.frame.geometry().piece(8 * cell + micro).normalizedVolume(), Double::sum);
				atPiece[8 * cell + micro] = vertices.size();
			}
			var dry = futurePore[j].subtract(predictedLiquid[j]);
			if (dry.signum() < 0) dry = mmm.units.SignedDyadic.ZERO;
			vertices.add(new GasSupport(cell, support, futurePore[j], dry, owners));
		}
		int[] connected = new int[vertices.size()];
		for (int v = 0; v < connected.length; v++) connected[v] = v;
		int[] strides = {1, domain.sizeX, domain.sizeX * domain.sizeY}, sizes = {domain.sizeX, domain.sizeY, domain.sizeZ};
		for (int v = 0; v < vertices.size(); v++) {
			var vertex = vertices.get(v); int cell = vertex.cell();
			int[] coordinate = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
			for (int axis = 0; axis < 3; axis++) if (coordinate[axis] + 1 < sizes[axis])
				for (int micro = 0; micro < 8; micro++) if ((micro >>> axis & 1) != 0 && (vertex.support() & 1 << micro) != 0) {
					int peer = atPiece[8 * (cell + strides[axis]) + (micro ^ 1 << axis)];
					if (peer >= 0) connected[gasRoot(connected, v)] = gasRoot(connected, peer);
				}
		}
		var dryBudgets = new java.util.HashMap<Integer, mmm.units.SignedDyadic>();
		var nodeGroups = new java.util.TreeMap<Integer, java.util.TreeSet<Integer>>();
		for (int v = 0; v < vertices.size(); v++) {
			var vertex = vertices.get(v); int group = gasRoot(connected, v);
			var liquid = mmm.units.SignedDyadic.ZERO;
			for (var stock : domain.liquidCells.stocksInCell(vertex.cell())) if (stock.key().supportMask() == vertex.support()) liquid = liquid.add(stock.volume());
			dryBudgets.merge(group, vertex.pore().subtract(liquid), mmm.units.SignedDyadic::add);
			nodeGroups.computeIfAbsent(group, ignored -> new java.util.TreeSet<>()).addAll(vertex.owners().keySet());
		}
		var nodeVolumes = new mmm.units.SignedDyadic[gasTargetVolume.length];
		java.util.Arrays.fill(nodeVolumes, mmm.units.SignedDyadic.ZERO);
		for (var entry : nodeGroups.entrySet()) {
			if (entry.getValue().isEmpty()) continue;
			var total = mmm.units.SignedDyadic.ZERO; int largest = -1;
			for (int node : entry.getValue()) {
				nodeVolumes[node] = mmm.units.SignedDyadic.of(gasTargetVolume[node] / domain.unitScale.cubicMetersPerNormalizedVolume());
				total = total.add(nodeVolumes[node]);
				if (largest < 0 || nodeVolumes[node].compareTo(nodeVolumes[largest]) > 0) largest = node;
			}
			var budget = dryBudgets.get(entry.getKey());
			if (total.compareTo(budget) <= 0) continue;
			if (budget.signum() <= 0) throw new IllegalStateException("Finite gas inventory has no open physical capacity");
			// A closed pore component fixes its total dry volume exactly. Only the EOS spatial
			// prediction is rescaled; the largest share receives the exact floating remainder.
			double scale = budget.toDouble() / total.toDouble(); var remaining = budget;
			for (int node : entry.getValue()) if (node != largest) {
				nodeVolumes[node] = mmm.units.SignedDyadic.of(nodeVolumes[node].toDouble() * scale);
				remaining = remaining.subtract(nodeVolumes[node]);
			}
			nodeVolumes[largest] = remaining;
		}
		int[] queue = new int[vertices.size()]; int tail = 0;
		for (int v = 0; v < vertices.size(); v++) if (!vertices.get(v).owners().isEmpty()) queue[tail++] = v;
		for (int head = 0; head < tail; head++) {
			var source = vertices.get(queue[head]); int cell = source.cell();
			int[] coordinate = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
			for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
				if (coordinate[axis] + sign < 0 || coordinate[axis] + sign >= sizes[axis]) continue;
				int peerCell = cell + sign * strides[axis], receiver = -1, ports = 0;
				for (int micro = 0; micro < 8; micro++) if ((micro >>> axis & 1) == (sign > 0 ? 1 : 0)
						&& (source.support() & 1 << micro) != 0) {
					int peer = atPiece[8 * peerCell + (micro ^ 1 << axis)];
					if (peer < 0 || receiver >= 0 && peer != receiver) continue;
					receiver = peer; ports++;
				}
				// Opening a full fixed face can extend a chamber into newly exposed dry space.
				// A partial fixed aperture remains a finite-rate gas-network connection.
				if (ports != 4 || receiver < 0) continue;
				var target = vertices.get(receiver);
				if (!(target.dry() > 0) || !target.owners().isEmpty()) continue;
				target.owners().putAll(source.owners()); queue[tail++] = receiver;
			}
		}
		var candidates = new java.util.TreeMap<Integer, java.util.ArrayList<Integer>>();
		for (int v = 0; v < vertices.size(); v++) for (int node : vertices.get(v).owners().keySet())
			candidates.computeIfAbsent(node, ignored -> new java.util.ArrayList<>()).add(v);
		int count = candidates.size(), ordinal = 0;
		int[] nodeIds = new int[count];
		var volumes = new mmm.units.SignedDyadic[count];
		var minimumVolumes = new mmm.units.SignedDyadic[count];
		int[][] receivers = new int[count][]; double[][] preferences = new double[count][];
		for (var entry : candidates.entrySet()) {
			int node = entry.getKey(); nodeIds[ordinal] = node; volumes[ordinal] = nodeVolumes[node];
			if (volumes[ordinal].signum() <= 0) throw new IllegalStateException("Finite gas inventory has no positive volume proposal");
			// The EOS volume is a soft first-order prediction. The allocator may compress only
			// the unavailable residual; this representable floor keeps the finite node present.
			minimumVolumes[ordinal] = mmm.units.SignedDyadic.of(Math.ulp(volumes[ordinal].toDouble()));
			if (minimumVolumes[ordinal].compareTo(volumes[ordinal]) > 0) minimumVolumes[ordinal] = volumes[ordinal];
			receivers[ordinal] = new int[entry.getValue().size()]; preferences[ordinal] = new double[receivers[ordinal].length];
			for (int i = 0; i < receivers[ordinal].length; i++) {
				int v = entry.getValue().get(i); var support = vertices.get(v); receivers[ordinal][i] = v;
				double sum = support.owners().values().stream().mapToDouble(Double::doubleValue).sum();
				preferences[ordinal][i] = support.dry() * support.owners().get(node) / sum;
			}
			// A filled old support remains a legal receiver: the shared graph can reverse the
			// liquid that would erase its finite cavity, rather than inventing remote drainage.
			if (java.util.Arrays.stream(preferences[ordinal]).sum() == 0)
				for (int i = 0; i < receivers[ordinal].length; i++) {
					var support = vertices.get(receivers[ordinal][i]);
					preferences[ordinal][i] = support.owners().get(node);
				}
			ordinal++;
		}
		return new GasVolumeProposal(nodeIds, volumes, minimumVolumes, receivers, preferences);
	}
	void acceptGasReservations(int[] cells, int[] supportMasks, mmm.units.SignedDyadic[] amounts) {
		if (cells.length != supportMasks.length || cells.length != amounts.length)
			throw new IllegalArgumentException("Accepted gas component columns differ");
		gasReservations.clear();
		for (int i = 0; i < cells.length; i++) if (amounts[i].signum() > 0) {
			gasReservations.put((long) cells[i] << 8 | supportMasks[i], amounts[i]);
			gasReservations.merge((long) cells[i] << 8, amounts[i], mmm.units.SignedDyadic::add);
		}
	}

	private static int gasRoot(int[] parent, int item) {
		while (parent[item] != item) { parent[item] = parent[parent[item]]; item = parent[item]; }
		return item;
	}
	private record GasSupport(int cell, int support, mmm.units.SignedDyadic pore, mmm.units.SignedDyadic dryCapacity,
	                          java.util.TreeMap<Integer, Double> owners) {
		double dry() { return dryCapacity.toDouble(); }
	}
	mmm.units.SignedDyadic gasReservedVolume(int cell, int supportMask) {
		return gasReservations.getOrDefault((long) cell << 8 | supportMask, mmm.units.SignedDyadic.ZERO);
	}


	void projectVelocities(float dt) {
		if (drag.active()) { drag.project(); return; }
		faces.pressureVelocity(pressure, velocityWork);
		for (int f = 0; f < faces.faceCount; f++) {
			int a = faces.axis[f], local = faces.localFace[f];
			float[] velocity = a == 0 ? domain.mac.velocityX : a == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
			velocity[local] += dt * velocityWork[f];
		}
		var p = domain.projection;
		for (int i = 0; i < p.gasUnknownCount; i++) { int node = p.gasUnknownNodes[i]; p.gasMatrixInput[node] = pressure[domain.cellCount + node]; }
		granular.project(dt, pressure, p.gasMatrixInput);
	}
	private void publishPressure() {
		var p = domain.projection;
		for (int i = 0; i < p.fluidUnknownCount; i++) { int cell = p.fluidUnknownCells[i]; p.pressure[cell] = (float) pressure[cell]; }
		for (int i = 0; i < p.gasUnknownCount; i++) { int node = p.gasUnknownNodes[i]; p.gasPressure[node] = (float) pressure[domain.cellCount + node]; }
	}
	private void buildDiagonal() {
		var p = domain.projection; double h2 = domain.config.cellSize() * (double) domain.config.cellSize();
		double[][] neighbors = {p.matrixNegativeX, p.matrixPositiveX, p.matrixNegativeY, p.matrixPositiveY, p.matrixNegativeZ, p.matrixPositiveZ};
		for (var column : neighbors) Arrays.fill(column, 0); Arrays.fill(p.matrixDiagonal, 0);
		System.arraycopy(p.gasStorageCoefficient, 0, p.gasMatrixDiagonal, 0, p.gasStorageCoefficient.length);
		for (int f = 0; f < faces.faceCount; f++) {
			double response = domain.config.density() * faces.inverseMass[f];
			for (int i = faces.start[f]; i < faces.start[f + 1]; i++) {
				int row = faces.rowAt(i); if (row < 0) continue;
				double c = faces.coefficientAt(i), diagonal = response * c * c;
				if (row < domain.cellCount) p.matrixDiagonal[row] += diagonal * h2;
				else p.gasMatrixDiagonal[row - domain.cellCount] += diagonal;
				if (row >= domain.cellCount) continue;
				for (int j = faces.start[f]; j < faces.start[f + 1]; j++) {
					int other = faces.rowAt(j); if (other < 0 || other >= domain.cellCount || other == row) continue;
					int direction=-1,delta=other-row;
					if(Math.abs(delta)==1 && other/domain.sizeX==row/domain.sizeX)direction=delta>0?1:0;
					else if(Math.abs(delta)==domain.sizeX && other/(domain.sizeX*domain.sizeY)==row/(domain.sizeX*domain.sizeY))direction=delta>0?3:2;
					else if(Math.abs(delta)==domain.sizeX*domain.sizeY)direction=delta>0?5:4;
					if(direction>=0)neighbors[direction][row] -= response * c * faces.coefficientAt(j) * h2;
				}
			}
		}
	}
	private void readVelocity(double[] target) {
		float[][] fields = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		for (int a = 0; a < 3; a++) for (int i = 0; i < fields[a].length; i++) target[faces.offsets[a] + i] = fields[a][i];
	}
	void addStorageRhs(double[] result, double dt) {
		var p = domain.projection;
		for (int i = 0; i < p.fluidUnknownCount; i++) {
			int cell = p.fluidUnknownCells[i]; result[cell] -= domain.config.density() * faces.capacityRoom[cell] / (dt * dt);
		}
		for (int i = 0; i < p.gasUnknownCount; i++) {
			int node = p.gasUnknownNodes[i]; result[domain.cellCount + node] += gasStorageRhs[node];
		}
	}
	double wallBoundaryWork() { return granular.wallWork(); }
	boolean hasLiquidPressure(int cell) { return domain.isFluid(cell); }
	double liquidPressure(int cell) { return pressure.length > cell ? pressure[cell] : 0; }
	double gasPressure(int node) { return pressure.length > domain.cellCount + node ? pressure[domain.cellCount + node] : domain.pressureBoundaryView.gasPressureOfNode(node); }

	/** Gauge pressure at the retained liquid support, including a partially filled cell's gas contact. */
	double liquidPressureAt(int cell, int support) {
		domain.ensurePressureGeometry();
		if ((faces.fullSupports[cell] & support) == support) return domain.projection.pressure[cell];
		var frame = domain.gasState.frame; double total = 0, weighted = 0;
		for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
			int node = frame.nodeOfPiece(8 * cell + micro); if (node < 0) continue;
			double volume = frame.geometry().piece(8 * cell + micro).normalizedVolume();
			total += volume;
			if (node > 0) weighted += volume * domain.pressureBoundaryView.gasPressureOfNode(node);
		}
		return (total > 0 ? weighted / total : domain.projection.pressure[cell])
				+ domain.tension.surfaceTensionPressure[cell] + faces.hydrostaticOffset(cell);
	}
}
