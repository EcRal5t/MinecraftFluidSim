package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasGeometryState;
import cc.ecisr.mmm.thermal.ConductionInventory;
import cc.ecisr.mmm.thermal.ThermalContactExchange;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Complete shared-face conduction candidate on the submitted liquid, gas and wall geometry. */
final class ResolvedLiquidConductionPlan {
	private static final int MAXIMUM_REGIONS = 65_536;
	private final FluidDomain owner;
	private final LiquidGeometryState liquid;
	private final GasGeometryState gas;
	private final AirNetwork network;
	private final ThermalDomainState thermal;
	private final byte[] collision;
	private final double[] boundary;
	private final ConductionInventory.Plan energy;
	private final ThermalTransport.Stats stats;

	ResolvedLiquidConductionPlan(FluidDomain domain, double dt) {
		owner = domain; liquid = domain.liquidGeometryState; gas = domain.gasState.geometry;
		network = domain.gasState.network; thermal = domain.thermalState;
		if (liquid == null || gas == null || thermal == null || domain.hasGranularInventory()
				|| thermal.coherentPhases().groupCount() != 0 || domain.hasPendingPhaseVolumeWork())
			throw new IllegalStateException("Resolved conduction requires complete condensed and gas geometry");
		LiquidGeometryStateMapper.requireCompatible(liquid, domain);
		collision = domain.geometry.cutCells.copyMicroMasks();
		boundary = domain.thermalBoundaryTemperatureK == null ? null : domain.thermalBoundaryTemperatureK.clone();
		var requests = new LinkedHashMap<LiquidPhasePartition.Address, LiquidGeometryRefinement.Cell>();
		for (var cell : LiquidGeometryStateMapper.restore(liquid)) requests.putIfAbsent(LiquidPhasePartition.Address.of(cell), cell);
		for (var cell : LiquidGeometryStateMapper.restoreCells(gas.cells())) requests.putIfAbsent(LiquidPhasePartition.Address.of(cell), cell);
		if (boundary != null) {
			int span = LiquidGeometryDomainPartition.rootSpan(domain.sizeX, domain.sizeY, domain.sizeZ), level = Integer.numberOfTrailingZeros(span);
			for (int cell = 0; cell < domain.cellCount; cell++) {
				boolean configured = false; for (int d = 0; d < 6; d++) configured |= !Double.isNaN(boundary[6 * cell + d]);
				if (!configured || domain.geometry.cutCells.openCellVolume(cell) == 0) continue;
				int x = cell % domain.sizeX, y = cell / domain.sizeX % domain.sizeY, z = cell / (domain.sizeX * domain.sizeY);
				var region = new LiquidGeometryRefinement.Cell(level, x, y, z, LiquidPolyhedron.box(x / (double) span, y / (double) span,
						z / (double) span, (x + 1.0) / span, (y + 1.0) / span, (z + 1.0) / span));
				requests.putIfAbsent(LiquidPhasePartition.Address.of(region), region);
			}
		}
		var partition = new LiquidGeometryDomainPartition(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), collision, List.copyOf(requests.values()), MAXIMUM_REGIONS);
		var phase = new LiquidGeometryPhaseInput(liquid, partition, gas, MAXIMUM_REGIONS);
		var captured = thermal.prepareConduction(domain.thermalMarkers, network);
		var support = LiquidGeometryConductionSupport.map(phase, captured, domain.thermalMarkers, thermal.registry(), network, thermal.gas().ambientTemperatureK());
		var prescribed = new double[phase.mesh.faces.size()]; Arrays.fill(prescribed, Double.NaN);
		if (boundary != null) for (int f = 0; f < prescribed.length; f++) {
			var face = phase.mesh.faces.get(f);
			if (face.second() == -2) prescribed[f] = wallTemperature(face.surface());
		}
		var result = new ControlVolumeConduction(phase.mesh, support.bodies().size(), support.portions())
				.advanceInventories(support.bodies(), prescribed, dt, observationPrecision(support.bodies(), captured), domain.config.maxSolverIterations());
		energy = captured.plan(Arrays.copyOf(result.energyChange(), support.finiteInventories()), result.reservoirEnergy(), result.roundoffBound());
		double maximumTemperature = 0, maximumTransfer = 0;
		for (int p = 0; p < support.finiteInventories(); p++) {
			maximumTemperature = Math.max(maximumTemperature, Math.abs(result.temperature()[p] - support.bodies().get(p).temperatureK()));
			maximumTransfer = Math.max(maximumTransfer, Math.abs(result.energyChange()[p]));
		}
		stats = new ThermalTransport.Stats(1, phase.mesh.faces.size(), maximumTemperature, maximumTransfer, energy.roundoffResidual(), result.reservoirEnergy());
	}
	ThermalTransport.Stats publish() {
		if (owner.liquidGeometryState != liquid || owner.gasState.geometry != gas || owner.gasState.network != network || owner.thermalState != thermal
				|| !Arrays.equals(collision, owner.geometry.cutCells.copyMicroMasks()) || !Arrays.equals(boundary, owner.thermalBoundaryTemperatureK))
			throw new IllegalStateException("Resolved conduction geometry changed after planning");
		thermal.commitConduction(energy, owner.thermalMarkers, network); return stats;
	}
	private double[] observationPrecision(List<ThermalContactExchange.Body> bodies, ConductionInventory captured) {
		var half = SignedDyadic.of(.5); var quantities = new double[bodies.size()];
		System.arraycopy(thermal.liquid().copyEnergies(owner.particles.count()), 0, quantities, 0, owner.particles.count());
		for (int node = 1; node <= network.nodeCount(); node++) {
			int ordinal = captured.gasOrdinal(node); if (ordinal >= 0) quantities[ordinal] = thermal.gas().nodeEnergy(node, network);
		}
		var precision = new double[bodies.size()];
		for (int p = 0; p < bodies.size(); p++) {
			var body = bodies.get(p);
			var bound = body.finite() ? SignedDyadic.of(body.heatCapacityEuPerK()).multiply(SignedDyadic.of(Math.ulp(body.temperatureK())))
					.add(SignedDyadic.of(Math.ulp(quantities[p]))).multiply(half) : SignedDyadic.ZERO;
			precision[p] = Math.nextUp(bound.toDouble());
			if (!Double.isFinite(precision[p])) throw new IllegalStateException("Conduction energy observation precision overflow");
		}
		return precision;
	}
	private double wallTemperature(LiquidPolyhedron.Surface surface) {
		int axis = -1; var normal = surface.areaVector();
		for (int a = 0; a < 3; a++) if (normal.coordinate(a) != 0) {
			if (axis >= 0) throw new IllegalStateException("Thermal wall field requires an axis-aligned collision surface"); axis = a;
		}
		if (axis < 0) throw new IllegalStateException("Thermal wall surface is empty");
		int[] coordinates = new int[3];
		for (int a = 0; a < 3; a++) {
			double position = surface.centroid().coordinate(a) / owner.config.cellSize();
			coordinates[a] = a == axis && normal.coordinate(axis) > 0 ? (int) Math.ceil(position) - 1 : (int) Math.floor(position);
		}
		if (coordinates[0] < 0 || coordinates[0] >= owner.sizeX || coordinates[1] < 0 || coordinates[1] >= owner.sizeY || coordinates[2] < 0 || coordinates[2] >= owner.sizeZ)
			throw new IllegalStateException("Thermal wall surface leaves its field support");
		int cell = (coordinates[2] * owner.sizeY + coordinates[1]) * owner.sizeX + coordinates[0];
		return boundary[6 * cell + 2 * axis + (normal.coordinate(axis) > 0 ? 1 : 0)];
	}
}
