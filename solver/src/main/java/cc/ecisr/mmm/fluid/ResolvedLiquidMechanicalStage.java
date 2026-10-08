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
		var localInventory = new LiquidInventoryRepartitionPlan(domain);
		domain.carrierMutations().publishGeometricRepartition(localInventory);
		var inventoryStats = localInventory.changed() ? new FluidDomain.InventoryRepartitionStats(1, localInventory.maximumRelativeMassError,
				localInventory.maximumTemperatureChange, localInventory.momentumChange[0].rounded(), localInventory.momentumChange[1].rounded(),
				localInventory.momentumChange[2].rounded(), localInventory.kineticChange.rounded()) : FluidDomain.InventoryRepartitionStats.INACTIVE;
		if (captureAudit) audit.carrierInventoryTransferJoules += localInventory.kineticChange.rounded();
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
			var physical = new LiquidMechanicalFaceInput(input.mesh, input.fragments, actualNodes, rows, properties.surfaceCoefficient,
					(face, gas) -> gas < 0 ? 0 : pressureBoundary.gasPressureOfNode(actualNodes[gas]));
			var density = select(properties.density, physical.phaseCells); var viscosity = select(properties.dynamicViscosity, physical.phaseCells);
			var velocity = vectors(input.cellVelocity, physical.phaseCells); var acceleration = vectors(properties.acceleration, physical.phaseCells);
			var mechanics = new LiquidPhaseMechanics(physical, density, viscosity, pressureNodes, domain.config.viscousWallCondition());
			LiquidPhaseMechanics.Result result;
			try {
				LiquidPressureFaceEnergy surface = null;
				if (Arrays.stream(physical.surfaceCoefficient).anyMatch(value -> value != 0)) {
					var rates = new LiquidExactPolygon.Fraction[physical.mesh.faces.size()]; Arrays.fill(rates, LiquidExactPolygon.Fraction.ZERO);
					var reference = new LiquidConformingVelocity(physical.mesh, rates, new double[velocity.length], physical.fragments, domain.config.viscousWallCondition());
					surface = new LiquidGeometricSurfaceEnergy(reference, physical.surfaceCoefficient).pressure;
				}
				result = mechanics.advance(velocity, physical.faceVelocity(velocity), acceleration, dt,
						domain.config.pressureTolerance(), domain.config.pressureTolerance(), domain.config.maxSolverIterations(), surface);
			} catch (LiquidCharacteristicTimeResolutionRequired shorter) {
				throw new MechanicalTimeRefinement(shorter.minimumSubsteps(domain.config.timeStep(), dt), shorter.getMessage());
			} catch (LiquidInterfaceResolutionRequired finer) {
				for (int resolved : finer.cells()) {
					int c = physical.phaseCells[resolved];
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
			var interfaces = new ResolvedGasInterfaceMotion.Builder(gasMatch, physical, MAXIMUM_REGIONS);
			LiquidGeometryMotionPlan motion;
			FluidDomain.GeometryStepStats observations;
			try {
				var field = result.conformingVelocity();
				double velocityError = field.maximumFittingVelocityErrorMetresPerSecond;
				var reference = new LiquidConformingTransport(physical.mesh, field, result.extension().volumeRate(), dt, physical.fragments);
				int precision = LiquidCoordinateProjection.fractionalBits(physical.mesh, dt, domain.config.pressureTolerance());
				LiquidConformingTransport transport;
				for (;;) {
					try {
						transport = reference.representCoordinates(precision);
						result = result.observeRates(transport.volumeRate());
						field = result.conformingVelocity(); transport = transport.withField(field); break;
					} catch (LiquidCoordinateResolutionRequired finer) {
						if (precision >= 1074) throw finer;
						precision = Math.min(1074, Math.max(precision + 1, 2 * precision));
					}
				}
				observations = new FluidDomain.GeometryStepStats(1, Math.max(velocityError, field.maximumFittingVelocityErrorMetresPerSecond), transport.maximumPositionRoundoffMetres,
						transport.maximumCoordinateErrorMetres, transport.maximumRateRepresentationErrorPerSecond, inventoryStats);
				motion = new LiquidGeometryMotionPlan(input, physical, field, transport, partition.leaves, MAXIMUM_REGIONS, interfaces);
			} catch (LiquidCharacteristicTimeResolutionRequired shorter) {
				throw new MechanicalTimeRefinement(shorter.minimumSubsteps(domain.config.timeStep(), dt), shorter.getMessage());
			}
			var next = new LiquidGeometryPhaseInput(motion.geometry, partition, domain.gasState.geometry.sealedExterior(), MAXIMUM_REGIONS, java.util.Set.of());
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
					new FluidDomain.ViscousStepStats(0, 0, impulse.x(), impulse.y(), impulse.z(), viscous.dissipatedJoules(), 0, 0, 0), observations);
		}
	}
	private static double[] select(double[] values, int[] cells) {
		var result = new double[cells.length]; for (int c = 0; c < result.length; c++) result[c] = values[cells[c]]; return result;
	}
	private static double[] vectors(double[] values, int[] cells) {
		var result = new double[3 * cells.length]; for (int c = 0; c < cells.length; c++) System.arraycopy(values, 3 * cells[c], result, 3 * c, 3); return result;
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
