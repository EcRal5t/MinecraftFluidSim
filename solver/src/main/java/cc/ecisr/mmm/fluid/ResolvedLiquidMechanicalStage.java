package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Advances the complete geometric liquid inventory through the shared phase operators. */
final class ResolvedLiquidMechanicalStage {
	private static final int MAXIMUM_REGIONS = 65_536, MAXIMUM_PAIRS = 4_194_304;
	private final FluidDomain domain;
	ResolvedLiquidMechanicalStage(FluidDomain domain) { this.domain = domain; }

	MechanicalStepPipeline.SubstepOutcome advance(float dt, boolean captureAudit, MechanicalAuditAccumulator audit) {
		if (domain.hasPendingPhaseVolumeWork() || domain.hasGranularInventory()
				|| domain.thermalState != null && domain.thermalState.coherentPhases().groupCount() != 0)
			throw new IllegalStateException("Resolved mechanics requires complete phase and solid geometry");
		var state = domain.liquidGeometryState;
		int[] controlCells = state.copyControlCells();
		LiquidGeometryStateMapper.requireCompatible(state, domain);
		if (domain.gasState.geometry == null) domain.gasTopologyCoordinator.refresh();
		if (domain.gasState.geometry == null) throw new GasTopologyBlockedException("Resolved liquid requires complete gas support");
		var requested = new LinkedHashMap<LiquidPhasePartition.Address, LiquidGeometryRefinement.Cell>();
		for (var cell : LiquidGeometryStateMapper.restore(state)) requested.putIfAbsent(LiquidPhasePartition.Address.of(cell), cell);
		for (var cell : LiquidGeometryStateMapper.restoreCells(domain.gasState.geometry.cells())) requested.putIfAbsent(LiquidPhasePartition.Address.of(cell), cell);
		var pressureBoundary = new PressureBoundaryView(domain);
		boolean gasAdvanced = false;
		var refinedControls = new java.util.LinkedHashSet<Integer>();
		for (;;) {
			var partition = new LiquidGeometryDomainPartition(domain.sizeX, domain.sizeY, domain.sizeZ,
					domain.config.cellSize(), domain.geometry.cutCells, List.copyOf(requested.values()), MAXIMUM_REGIONS);
			var input = new LiquidGeometryPhaseInput(state, partition, domain.gasState.geometry, MAXIMUM_REGIONS, refinedControls);
			var gasMatch = ResolvedGasGeometry.match(domain.gasState.geometry, input, MAXIMUM_PAIRS); var actualNodes = gasMatch.nodes();
			if (!gasAdvanced) { domain.gasTopologyCoordinator.advanceEdges(dt); gasAdvanced = true; }
			var pressureNodes = new ArrayList<LiquidControlVolumeProjection.GasNode>();
			int[] nodeRows = new int[domain.gasState.network.nodeCount() + 1]; Arrays.fill(nodeRows, LiquidPhaseMechanics.PRESCRIBED_GAS_PRESSURE); nodeRows[0] = -1;
			for (int node = 1; node < nodeRows.length; node++) if (pressureBoundary.gasNodeHasContinuumState(node)) {
				nodeRows[node] = pressureNodes.size();
				pressureNodes.add(new LiquidControlVolumeProjection.GasNode(pressureBoundary.gasPressureOfNode(node), pressureBoundary.gasNodeVolumeCompliance(node)));
			}
			var rows = actualNodes.clone(); for (int c = 0; c < rows.length; c++) if (input.fragments[c] < 0) rows[c] = nodeRows[actualNodes[c]];
			var properties = new LiquidGeometryCarrierProperties(domain, state, input);
			double initialKinetic = captureAudit ? domain.particleKineticEnergyJoules() : 0;
			var mechanics = new LiquidPhaseMechanics(input.mesh, input.fragments, properties.density, properties.dynamicViscosity,
					pressureNodes, rows, (face, gas) -> gas < 0 ? 0 : pressureBoundary.gasPressureOfNode(actualNodes[gas]), domain.config.viscousWallCondition());
			LiquidPhaseMechanics.Result result;
			try {
				LiquidPressureFaceEnergy surface = null;
				if (Arrays.stream(properties.surfaceCoefficient).anyMatch(value -> value != 0)) {
					var rates = new LiquidExactPolygon.Fraction[input.mesh.faces.size()]; Arrays.fill(rates, LiquidExactPolygon.Fraction.ZERO);
					var reference = new LiquidConformingVelocity(input.mesh, rates, input.cellVelocity, input.fragments, domain.config.viscousWallCondition());
					surface = new LiquidGeometricSurfaceEnergy(reference, properties.surfaceCoefficient).pressure;
				}
				result = mechanics.advance(input.cellVelocity, input.faceVelocity, properties.acceleration, dt,
						domain.config.pressureTolerance(), domain.config.pressureTolerance(), domain.config.maxPressureIterations(), surface);
			} catch (LiquidCharacteristicTimeResolutionRequired shorter) {
				throw new MechanicalTimeRefinement((int) Math.ceil(2.0 * domain.config.timeStep() / dt), shorter.getMessage());
			} catch (LiquidInterfaceResolutionRequired finer) {
				for (int c : finer.cells()) {
					if (input.fragments[c] >= 0) refinedControls.add(controlCells[input.fragments[c]]);
					var children = input.mechanicalSupport.get(c).stream().flatMap(cell -> LiquidGeometryRefinement.children(cell).stream()).toList();
					for (var child : children) {
						var address = LiquidPhasePartition.Address.of(child);
						if (requested.size() == MAXIMUM_REGIONS && !requested.containsKey(address))
							throw new IllegalStateException("Resolved mechanics exceeds its refinement budget", finer);
						requested.putIfAbsent(address, child);
					}
				}
				continue;
			}
			var interfaces = new ResolvedGasInterfaceMotion.Builder(gasMatch, MAXIMUM_REGIONS);
			LiquidGeometryMotionPlan motion;
			try {
				var field = result.conformingVelocity();
				var transport = new LiquidConformingTransport(input.mesh, field, result.extension().volumeRate(), dt, input.fragments);
				motion = new LiquidGeometryMotionPlan(input, field, transport, partition.leaves, MAXIMUM_REGIONS, interfaces);
			} catch (LiquidCharacteristicTimeResolutionRequired shorter) {
				throw new MechanicalTimeRefinement((int) Math.ceil(2.0 * domain.config.timeStep() / dt), shorter.getMessage());
			}
			var next = new LiquidGeometryPhaseInput(motion.geometry, partition, domain.gasState.geometry.sealedExterior(), MAXIMUM_REGIONS);
			var gas = new ResolvedGasMotionPlan(domain, next, motion.gasInterfaces, MAXIMUM_PAIRS);
			gas.publish(domain, motion);
			domain.lastPressureIterations = result.pressure().iterations();
			domain.lastPressureResidual = (float) result.pressure().fluxResidual();
			if (captureAudit) {
				var predictor = result.viscosity().cellVelocity().clone();
				double viscousKinetic = kinetic(input, properties, predictor);
				int local = 0;
				for (int c = 0; c < input.fragments.length; c++) if (input.fragments[c] >= 0) predictor[3 * local++ + 1] -= dt * domain.config.gravity();
				double gravityKinetic = kinetic(input, properties, predictor); local = 0;
				for (int c = 0; c < input.fragments.length; c++) if (input.fragments[c] >= 0) predictor[3 * local++ + 1] += dt * (properties.acceleration[3 * c + 1] + domain.config.gravity());
				double predictedKinetic = kinetic(input, properties, predictor), projectedKinetic = kinetic(input, properties, result.pressure().cellVelocity());
				audit.viscousWorkJoules += viscousKinetic - initialKinetic;
				audit.gravityWorkJoules += gravityKinetic - viscousKinetic;
				audit.thermalBuoyancyWorkJoules += predictedKinetic - gravityKinetic;
				audit.pressureProjectionWorkJoules += projectedKinetic - predictedKinetic;
				audit.volumeTransportTransferJoules += domain.particleKineticEnergyJoules() - projectedKinetic;
			}
			var viscous = result.viscosity(); var impulse = viscous.boundaryImpulse();
			return new MechanicalStepPipeline.SubstepOutcome(result.pressure().iterations(), null, false,
					new FluidDomain.ViscousStepStats(0, 0, impulse.x(), impulse.y(), impulse.z(), viscous.dissipatedJoules(), 0, 0, 0));
		}
	}
	private static double kinetic(LiquidGeometryPhaseInput input, LiquidGeometryCarrierProperties properties, double[] velocity) {
		double result = 0; int local = 0;
		for (int c = 0; c < input.fragments.length; c++) if (input.fragments[c] >= 0) {
			double squared = 0;
			for (int a = 0; a < 3; a++) squared += velocity[3 * local + a] * velocity[3 * local + a];
			result += 0.5 * properties.density[c] * input.mesh.cells.get(c).geometry().moments().volume() * squared; local++;
		}
		return result;
	}
}
