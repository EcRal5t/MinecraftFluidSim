package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidDomainState;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.fluid.FluidStepCandidate;
import cc.ecisr.mmm.fluid.FluidStepRequest;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasRegistry;

import java.util.List;

/** Replays controller injection through the default live step and the complete authoritative handoff. */

abstract class ControllerReplayValidationScenarios extends ProductionReplayValidationSupport {
	static void controllerPourSequenceWithoutGasSettles() {
		FluidDomain domain = new FluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		FluidDomain.VolumeAddition first = domain.addVolumeNearBudgeted(5, 0, 4, 128.0F, 6);
		require(first.addedVolume() == 128.0F && !first.markerBudgetLimited(),
				"gas-free control did not inject the first 16 BV source: " + first);
		for (int step = 0; step < 40; step++) domain.stepWithMechanicalAudit(false, 0);
		FluidDomain.VolumeAddition second = domain.addVolumeNearBudgeted(3, 3, 5, 112.0F, 6);
		require(second.addedVolume() == 112.0F && !second.markerBudgetLimited(),
				"gas-free control did not inject the second 14 BV source: " + second);
		double expectedVolume = domain.totalVolumeExact();
		double expectedMass = domain.totalMass();
		double maximumKinetic = 0.0, tailMaximumKinetic = 0.0, finalKinetic = 0.0;
		double initialMechanicalEnergy = Double.NaN, maximumMechanicalEnergy = 0.0;
		FluidDomain.MechanicalStepAudit maximumMechanicalAudit = null;
		int maximumMechanicalStep = 0;
		float maximumMechanicalPressure = 0.0F;
		FluidDomain.VolumeDistribution maximumMechanicalDistribution = null;
		double cumulativePressureWork = 0.0;
		double reportedVolumeChange = 0.0, reportedMassChange = 0.0;
		long reseededMerges = 0;
		for (int step = 0; step < 1_000; step++) {
			FluidDomain.StepStats stats = domain.stepWithMechanicalAudit(false, 0);
			FluidDomain.MechanicalStepAudit audit = stats.mechanicalAudit();
			if (step == 0) initialMechanicalEnergy = audit.initialParticleKineticEnergyJoules()
					+ audit.initialGravitationalPotentialEnergyJoules();
			finalKinetic = audit.finalParticleKineticEnergyJoules();
			double finalMechanicalEnergy = finalKinetic + audit.finalGravitationalPotentialEnergyJoules();
			if (finalMechanicalEnergy > maximumMechanicalEnergy) {
				maximumMechanicalEnergy = finalMechanicalEnergy;
				maximumMechanicalAudit = audit;
				maximumMechanicalStep = step + 1;
				maximumMechanicalPressure = domain.maximumPressure();
				maximumMechanicalDistribution = domain.volumeDistribution(8.0F / 255.0F);
			}
			cumulativePressureWork += audit.pressureProjectionWorkJoules();
			reportedVolumeChange += stats.reseedLedgerError();
			reportedMassChange += stats.reseedMassLedgerError();
			reseededMerges += stats.reseededMerges();
			maximumKinetic = Math.max(maximumKinetic, finalKinetic);
			if (step >= 800) tailMaximumKinetic = Math.max(tailMaximumKinetic, finalKinetic);
		}
		double volumeChange = domain.totalVolumeExact() - expectedVolume;
		double massChange = domain.totalMass() - expectedMass;
		double volumeRoundoff = floatingPointOperationBound(expectedVolume, reseededMerges, 24);
		double massRoundoff = floatingPointOperationBound(expectedMass, reseededMerges, 53);
		double reconciliationRoundoff = floatingPointOperationBound(
				Math.max(Math.abs(volumeChange), Math.abs(reportedVolumeChange)), 1_000, 53);
		require(Math.abs(volumeChange) <= volumeRoundoff && Math.abs(massChange) <= massRoundoff
				&& Math.abs(volumeChange - reportedVolumeChange) <= reconciliationRoundoff
				&& Math.abs(massChange - reportedMassChange) <= massRoundoff,
				"reported two-pour replay exceeded or bypassed its floating-point reseed ledger: volume="
						+ volumeChange + " (reported " + reportedVolumeChange + ", bound " + volumeRoundoff
						+ "), mass=" + massChange + " (reported " + reportedMassChange + ", bound "
						+ massRoundoff + "), merges=" + reseededMerges);
		require(cumulativePressureWork < 0.0,
				"reported two-pour pressure projection injected net kinetic energy: "
						+ cumulativePressureWork + " J");
		require(maximumMechanicalEnergy <= initialMechanicalEnergy,
				"reported two-pour replay created mechanical energy: initial/maximum="
						+ initialMechanicalEnergy + "/" + maximumMechanicalEnergy + " J at step "
						+ maximumMechanicalStep + ", pressure=" + maximumMechanicalPressure
						+ ", distribution=" + maximumMechanicalDistribution + ": " + maximumMechanicalAudit);
		System.out.printf("  controller pour gas-free control: K max/tail/final="
				+ "%.9g/%.9g/%.9g J, E initial/max=%.9g/%.9g J, pressure work=%+.9g J, speed=%.6g%n",
				maximumKinetic, tailMaximumKinetic, finalKinetic,
				initialMechanicalEnergy, maximumMechanicalEnergy,
				cumulativePressureWork, domain.maximumParticleSpeed());
	}

	/** Replays the reported controller pours with the production gas/thermal state and backend handoff. */
	static void controllerPourSequencePreservesGasInventoryAcrossBackend() throws Exception {
		FullCapabilityTestFixture.Registries registries = loadProductionRegistries();
		FluidDomain domain = createEmptyFullyBoundController(registries);
		var initialSource = domain.gasBoundary().externalSource();
		var initialSink = domain.gasBoundary().escapedSink();
		long initialInventory = domain.gasBoundary().sealedInventoryNanos();
		require(domain.gasBoundary().nodeCount() == 1 && domain.gasBoundary().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"empty production controller did not bootstrap one closed gas owner");
		FluidDomain.VolumeAddition first = domain.addVolumeNearBudgeted(5, 0, 4, 128.0F, 6);
		require(first.addedVolume() == 128.0F && !first.markerBudgetLimited(),
				"production controller did not inject the first 16 BV source: " + first);

		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		long generation = 0L;
		FluidDomainState state = domain.captureAuthoritativeState();
		require(domain.gasBoundary().externalSource().equals(initialSource)
					&& domain.gasBoundary().escapedSink().equals(initialSink)
					&& domain.gasBoundary().sealedInventoryNanos() == initialInventory,
				"capturing the first reported pour changed gas inventory: " + gasLedgerDetail(domain));
		require(Math.abs(totalGasAvailableVolume(domain) - 48.0) <= 1.0E-12,
				"first reported pour did not compress the closed headspace to 48 m3");
		for (int step = 0; step < 40; step++) {
			FluidStepCandidate candidate;
			try {
				candidate = backend.evaluate(new FluidStepRequest(
						generation, state, List.of(), false, 0, true));
			} catch (RuntimeException failure) {
				throw new IllegalStateException("first reported controller pour failed at step " + step, failure);
			}
			domain = FluidDomain.restoreAuthoritativeState(candidate.state());
			require(domain.gasBoundary().externalSource().equals(initialSource)
						&& domain.gasBoundary().escapedSink().equals(initialSink)
						&& domain.gasBoundary().sealedInventoryNanos() == initialInventory
						&& domain.gasBoundary().auditClosed()
						&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"first reported controller pour changed gas inventory at step " + step);
			state = candidate.state();
			generation++;
		}

		FluidDomain.VolumeAddition second = domain.addVolumeNearBudgeted(3, 3, 5, 112.0F, 6);
		require(second.addedVolume() == 112.0F && !second.markerBudgetLimited(),
				"production controller did not inject the second 14 BV source: " + second);
		state = domain.captureAuthoritativeState();
		require(domain.gasBoundary().externalSource().equals(initialSource)
					&& domain.gasBoundary().escapedSink().equals(initialSink)
					&& domain.gasBoundary().sealedInventoryNanos() == initialInventory,
				"capturing the second reported pour changed gas inventory: " + gasLedgerDetail(domain));
		require(Math.abs(totalGasAvailableVolume(domain) - 34.0) <= 1.0E-12,
				"second reported pour did not compress the closed headspace to 34 m3");
		for (int step = 0; step < 1_000; step++) {
			FluidStepCandidate candidate;
			try {
				candidate = backend.evaluate(new FluidStepRequest(
						generation, state, List.of(), false, 0, true));
			} catch (RuntimeException failure) {
				FluidDomain diagnostic = FluidDomain.restoreAuthoritativeState(state);
				throw new IllegalStateException("second reported controller pour failed at step " + step
						+ "; input " + gasLedgerDetail(diagnostic), failure);
			}
			domain = FluidDomain.restoreAuthoritativeState(candidate.state());
			require(domain.gasBoundary().externalSource().equals(initialSource)
						&& domain.gasBoundary().escapedSink().equals(initialSink)
						&& domain.gasBoundary().sealedInventoryNanos() == initialInventory
						&& domain.gasBoundary().auditClosed()
						&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"second reported controller pour changed gas inventory at step " + step
							+ ": " + gasLedgerDetail(domain));
			state = candidate.state();
			generation++;
		}
		double finalAvailable = totalGasAvailableVolume(domain);
		double minimumGasPressure = Double.POSITIVE_INFINITY;
		int gasBearingNodes = 0;
		for (int node : domain.gasBoundary().nodeIds()) {
			if (domain.gasBoundary().inventory(node).isEmpty()) continue;
			gasBearingNodes++;
			double temperature = domain.gasNodeTemperatureK(node);
			double expectedPressure = AirNetwork.REFERENCE_PRESSURE
					* domain.gasBoundary().standardVolume(node) / domain.gasBoundary().availableVolume(node)
					* temperature / GasRegistry.STANDARD_TEMPERATURE_K - AirNetwork.REFERENCE_PRESSURE;
			double pressure = domain.gasNodeGaugePressurePa(node);
			require(Math.abs(pressure - expectedPressure) <= Math.max(1.0E-8, Math.abs(expectedPressure) * 1.0E-12),
					"compressed controller pressure did not match inventory/volume/temperature EoS");
			minimumGasPressure = Math.min(minimumGasPressure, pressure);
		}
		require(gasBearingNodes > 0 && minimumGasPressure > 0.0
				&& Math.abs(finalAvailable - 34.0) <= 1.0E-12,
				"settled controller did not retain a positively pressurized 34 m3 headspace");
		System.out.printf("  controller pour gas ledger: %.9f m3std in %.9f m3, "
				+ "minimum gauge pressure=%+.3f Pa across %d backend candidates%n",
				domain.gasBoundary().sealedInventoryNanos() * 1.0E-9, finalAvailable,
				minimumGasPressure, generation);
	}

	static double totalGasAvailableVolume(FluidDomain domain) {
		double result = 0.0;
		for (int node : domain.gasBoundary().nodeIds()) result += domain.gasBoundary().availableVolume(node);
		return result;
	}

	/** Replays the thermal-off world path: restore 34 m3std, empty liquid, then pour at the boundary. */
	static void controllerEmptyThenBoundaryPourPreservesGasAcrossBackend() throws Exception {
		FullCapabilityTestFixture.Registries registries = loadProductionRegistries();
		FluidDomain domain = new FluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		FluidDomain.VolumeAddition first = domain.addVolumeNearBudgeted(5, 0, 4, 128.0F, 6);
		FluidDomain.VolumeAddition second = domain.addVolumeNearBudgeted(3, 3, 5, 112.0F, 6);
		require(first.addedVolume() == 128.0F && second.addedVolume() == 112.0F,
				"thermal-off restore fixture did not establish 30 BV before gas bootstrap");
		domain.bindGasRegistry(registries.gas());
		domain.bootstrapGasState("");
		long retainedInventory = domain.gasBoundary().sealedInventoryNanos();
		require(retainedInventory == 34_000_000_000L && domain.gasBoundary().auditClosed(),
				"thermal-off restore fixture did not bootstrap the reported 34 m3std inventory");
		var source = domain.gasBoundary().externalSource();
		var sink = domain.gasBoundary().escapedSink();
		domain.commitOperatorClear(domain.planOperatorClear());
		require(domain.totalVolumeExact() == 0.0F
				&& Math.abs(totalGasAvailableVolume(domain) - 64.0) <= 1.0E-12
				&& domain.gasBoundary().sealedInventoryNanos() == retainedInventory,
				"operator empty did not retain and expand the finite closed gas inventory");

		FluidDomain.VolumeAddition boundaryPour = domain.addVolumeNearBudgeted(6, 0, 0, 128.0F, 6);
		require(boundaryPour.addedVolume() == 128.0F && !boundaryPour.markerBudgetLimited(),
				"thermal-off boundary pour did not inject the complete 16 BV source");
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		FluidDomainState state = domain.captureAuthoritativeState();
		long generation = 0L;
		int maximumNodes = domain.gasBoundary().nodeCount();
		for (int step = 0; step < 200; step++) {
			FluidStepCandidate candidate;
			try {
				candidate = backend.evaluate(new FluidStepRequest(
						generation, state, List.of(), false, 0, true));
			} catch (RuntimeException failure) {
				throw new IllegalStateException("thermal-off empty/boundary-pour replay failed at step " + step,
						failure);
			}
			domain = FluidDomain.restoreAuthoritativeState(candidate.state());
			maximumNodes = Math.max(maximumNodes, domain.gasBoundary().nodeCount());
			require(domain.gasBoundary().sealedInventoryNanos() == retainedInventory
						&& domain.gasBoundary().externalSource().equals(source)
						&& domain.gasBoundary().escapedSink().equals(sink)
						&& domain.gasBoundary().auditClosed()
						&& Math.abs(totalGasAvailableVolume(domain) - 48.0) <= 1.0E-12,
					"thermal-off empty/boundary-pour replay changed gas truth at step " + step
							+ ": " + gasLedgerDetail(domain));
			state = candidate.state();
			generation++;
		}
		System.out.printf("  thermal-off empty/boundary-pour gas lineage: %.9f m3std in %.9f m3, "
				+ "maxNodes=%d across %d backend candidates%n",
				retainedInventory * 1.0E-9, totalGasAvailableVolume(domain), maximumNodes, generation);
	}

	/**
	 * Replays the reported two-pour controller trajectory under the default feature gates, where
	 * the closed headspace is isothermal and bootstrap completes on the empty shell.
	 *
	 * <p>The headspace must stay a single sealed owner throughout. A marker-free cell enclosed by
	 * marker-bearing neighbours is an unsampled liquid cell, not a resolved cavity; treating it as
	 * one splits off a pocket that carries a share of the headspace inventory and is then crushed
	 * to zero capacity by the next advection, which pauses the region for good.</p>
	 */
	static void controllerPoursKeepSingleSealedGasOwner() {
		FluidDomain domain = new FluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(
				domain, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
		domain.step(false, 0);
		long inventory = domain.gasBoundary().sealedInventoryNanos();
		require(inventory == 64_000_000_000L && domain.gasBoundary().nodeCount() == 1,
				"empty default-gate controller did not bootstrap one closed gas owner: "
						+ gasLedgerDetail(domain));

		FluidDomain.VolumeAddition first = domain.addVolumeNearBudgeted(2, 0, 0, 128.0F, 6);
		require(first.addedVolume() == 128.0F && !first.markerBudgetLimited(),
				"reported first pour did not inject the complete 16 BV source: " + first);
		for (int step = 0; step < 60; step++) domain.step(false, 0);
		require(Math.abs(totalGasAvailableVolume(domain) - 48.0) <= 1.0E-12,
				"first reported pour did not compress the closed headspace to 48 m3");

		FluidDomain.VolumeAddition second = domain.addVolumeNearBudgeted(5, 3, 5, 112.0F, 6);
		require(second.addedVolume() == 112.0F && !second.markerBudgetLimited(),
				"reported second pour did not inject the complete 14 BV source: " + second);
		int maximumNodes = domain.gasBoundary().nodeCount();
		for (int step = 0; step < 200; step++) {
			domain.step(false, 0);
			maximumNodes = Math.max(maximumNodes, domain.gasBoundary().nodeCount());
			require(domain.gasBoundary().auditClosed(),
					"reported two-pour replay opened the gas ledger at step " + step
							+ ": " + gasLedgerDetail(domain));
		}
		require(domain.gasBoundary().nodeCount() == 1,
				"reported two-pour replay left the closed headspace split across "
						+ domain.gasBoundary().nodeCount() + " owners, peak " + maximumNodes
						+ ": " + gasLedgerDetail(domain));
		require(domain.gasBoundary().sealedInventoryNanos() == inventory,
				"reported two-pour replay changed sealed gas inventory: " + gasLedgerDetail(domain));
		require(Math.abs(totalGasAvailableVolume(domain) - 34.0) <= 1.0E-12,
				"settled controller did not retain a 34 m3 headspace");
		System.out.printf("  controller two-pour headspace: %.9f m3std in %.9f m3, peakNodes=%d%n",
				inventory * 1.0E-9, totalGasAvailableVolume(domain), maximumNodes);
	}

	static String gasLedgerDetail(FluidDomain domain) {
		StringBuilder nodes = new StringBuilder();
		for (int node : domain.gasBoundary().nodeIds()) nodes.append(" node").append(node)
				.append('=').append(domain.gasBoundary().inventory(node));
		return "nodes=" + domain.gasBoundary().nodeCount()
				+ " sealed=" + domain.gasBoundary().sealedInventoryNanos()
				+ " source=" + domain.gasBoundary().externalSource().standardVolumeNanos()
				+ "/" + domain.gasBoundary().externalSource().entries()
				+ " sink=" + domain.gasBoundary().escapedSink().standardVolumeNanos()
				+ "/" + domain.gasBoundary().escapedSink().entries()
				+ " massRoundoff=" + domain.gasBoundary().massRoundoff()
				+ " residualRoundoff=" + domain.gasBoundary().residualRoundoff()
				+ " audit=" + domain.gasBoundary().auditClosed() + nodes;
	}

}
