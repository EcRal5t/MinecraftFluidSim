package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.simulation.FullCapabilityTestFixture;

/**
 * Scenario coverage for the carrier mutation coordinator: append, swap-remove and wholesale
 * count transitions across the main column and the chemistry/thermal side columns, including
 * capacity-edge and stale-plan rejection leaving every column and the revision untouched.
 */
public final class CarrierMutationValidation {
	private CarrierMutationValidation() { }

	public static void main(String[] args) throws Exception {
		appendThenRemoveLastClosesLedgers();
		removeMiddleSwapsAllColumnsBitwise();
		sideColumnsSwapWithTheMainColumn();
		capacityEdgeRejectsBeforeAnyWrite();
		stalePlansRejectWithoutStateChange();
		wholesaleCountBindsRevisionAndBudget();
		readOnlyCellQueriesFollowCarrierEdits();
		System.out.println("Carrier mutation validation passed");
	}

	private static void readOnlyCellQueriesFollowCarrierEdits() {
		FluidDomain domain = emptyMaterialDomain(256);
		domain.setVolume(1, 1, 1, 1);
		domain.setVolume(2, 1, 1, 0.5F);
		for (int round = 0; round < 3; round++) {
			boolean dirty = domain.gridDirty;
			var before = domain.observeCellInventory(1, 1, 1);
			require(domain.gridDirty == dirty, "query preserves grid rebuild scheduling");
			domain.ensureGrid();
			var after = domain.observeCellInventory(1, 1, 1);
			require(java.util.Objects.equals(before, after), "indexed query matches the authoritative full scan");
			require(!domain.gridDirty, "indexed observation keeps the grid clean");
			domain.setVolume(1, 1, 1, round == 0 ? 0 : 0.75F);
		}
	}

	private static FluidSolverConfig config() {
		return new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	}

	private static FluidDomain emptyMaterialDomain(int maximumParticles) {
		FluidDomain domain = new FluidDomain(4, 4, 4, config(), maximumParticles);
		domain.sealDomainBoundary();
		FluidMaterialRegistry registry = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				4, 4, 4, 0.5F, 0, 0.0, 0.0, registry.materials(),
				new float[0], new float[0], new byte[64], new double[0], new int[0]));
		return domain;
	}

	private static FluidDomain fullCapabilityDomain(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = emptyMaterialDomain(4_096);
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		return domain;
	}

	private static void appendThenRemoveLastClosesLedgers() {
		FluidDomain domain = emptyMaterialDomain(64);
		long revision = domain.carrierMutationRevision();
		int ordinal = domain.carrierMutations().appendMarker(
				CarrierMutationPlan.append(revision, domain.particles.count()),
				domain.cellIndex(1, 1, 1), 0.6F, 0.6F, 0.6F, 0.125F, 0, 125.0, null, false, false);
		require(ordinal == 0 && domain.particles.count() == 1, "append did not create the first ordinal");
		require(domain.carrierMutationRevision() == revision + 1, "append did not advance the revision");
		require(domain.totalVolumeExact() == 0.125 && domain.totalMass() == 125.0, "append ledger mismatch");
		require(domain.gridDirty, "append did not invalidate the marker grid");
		domain.ensureGrid();

		double removedEnergy = domain.carrierMutations().swapRemoveMarker(
				CarrierMutationPlan.swapRemove(domain.carrierMutationRevision(), 1, 0), false);
		require(removedEnergy == 0.0, "external append must not report internal energy");
		require(domain.particles.count() == 0 && domain.totalVolumeExact() == 0.0
						&& domain.totalMass() == 0.0,
				"remove-last did not close the exact ledgers");
		require(domain.carrierMutationRevision() == revision + 2, "remove did not advance the revision exactly once");
	}

	private static void removeMiddleSwapsAllColumnsBitwise() {
		FluidDomain domain = emptyMaterialDomain(4_096);
		domain.addVolumeNearBudgeted(1, 1, 1, 0.5F, 1);
		domain.addVolumeNearBudgeted(2, 2, 2, 0.5F, 1);
		require(domain.particles.count() == 8, "fixture did not create eight markers");
		int removed = 3, last = 7;
		float lastX = domain.particles.positionX(last), lastY = domain.particles.positionY(last),
				lastZ = domain.particles.positionZ(last), lastV = domain.particles.volume(last);
		double lastMass = domain.particles.mass(last);
		int lastHandle = domain.particles.materialHandle(last);
		float untouchedX = domain.particles.positionX(0), untouchedV = domain.particles.volume(0);

		domain.carrierMutations().swapRemoveMarker(
				CarrierMutationPlan.swapRemove(domain.carrierMutationRevision(), 8, removed), false);

		require(domain.particles.count() == 7, "swap-remove did not shrink the count");
		require(Float.floatToRawIntBits(domain.particles.positionX(removed)) == Float.floatToRawIntBits(lastX)
						&& Float.floatToRawIntBits(domain.particles.positionY(removed)) == Float.floatToRawIntBits(lastY)
						&& Float.floatToRawIntBits(domain.particles.positionZ(removed)) == Float.floatToRawIntBits(lastZ)
						&& Float.floatToRawIntBits(domain.particles.volume(removed)) == Float.floatToRawIntBits(lastV)
						&& Double.doubleToRawLongBits(domain.particles.mass(removed)) == Double.doubleToRawLongBits(lastMass)
						&& domain.particles.materialHandle(removed) == lastHandle,
				"swap-remove did not move the last marker's main columns bitwise");
		require(Float.floatToRawIntBits(domain.particles.positionX(0)) == Float.floatToRawIntBits(untouchedX)
						&& Float.floatToRawIntBits(domain.particles.volume(0)) == Float.floatToRawIntBits(untouchedV),
				"swap-remove disturbed an unrelated marker");
	}

	private static void sideColumnsSwapWithTheMainColumn() throws Exception {
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		FluidDomain domain = fullCapabilityDomain(registries);
		domain.addVolumeNearBudgeted(1, 1, 1, 0.5F, 1);
		domain.addVolumeNearBudgeted(2, 2, 2, 0.5F, 1);
		int removed = 3, last = domain.particles.count() - 1;
		ChemicalMixtureRecord removedRecord = domain.chemicalParcels.record(removed);
		ChemicalMixtureRecord lastRecord = domain.chemicalParcels.record(last);
		double removedMarkerEnergy = thermalLiquidEnergy(domain, removed);
		double lastEnergy = thermalLiquidEnergy(domain, last);
		long speciesBefore = domain.totalChemicalSpeciesQuanta();
		cc.ecisr.mmm.units.SignedDyadic energyBefore = domain.exactInternalEnergy();

		double removedEnergy = domain.carrierMutations().swapRemoveMarker(
				CarrierMutationPlan.swapRemove(domain.carrierMutationRevision(),
						domain.particles.count(), removed), true);

		require(removedEnergy == removedMarkerEnergy,
				"internal swap-remove did not return the removed marker's energy");
		require(thermalLiquidEnergy(domain, removed) == lastEnergy,
				"swap-remove did not move the thermal energy column with the marker");
		require(sameMixture(domain.chemicalParcels.record(removed), lastRecord),
				"swap-remove did not move the chemistry record with the marker");
		require(domain.totalChemicalSpeciesQuanta() == speciesBefore - removedRecord.totalQuanta(),
				"swap-remove did not subtract exactly the removed marker's species ledger");
		require(domain.exactInternalEnergy().equals(
						energyBefore.subtract(cc.ecisr.mmm.units.SignedDyadic.of(removedEnergy))),
				"swap-remove did not remove exactly the returned marker energy");
	}

	private static boolean sameMixture(ChemicalMixtureRecord a, ChemicalMixtureRecord b) {
		if (a.totalQuanta() != b.totalQuanta() || a.entryCount() != b.entryCount()) return false;
		for (int entry = 0; entry < a.entryCount(); entry++) {
			if (a.speciesHandle(entry) != b.speciesHandle(entry) || a.amountAt(entry) != b.amountAt(entry)) {
				return false;
			}
		}
		return true;
	}

	private static double thermalLiquidEnergy(FluidDomain domain, int marker) {
		return domain.thermalState.liquidInternalEnergy(marker);
	}

	private static void capacityEdgeRejectsBeforeAnyWrite() {
		FluidDomain domain = emptyMaterialDomain(1);
		domain.carrierMutations().appendMarker(
				CarrierMutationPlan.append(domain.carrierMutationRevision(), 0),
				domain.cellIndex(1, 1, 1), 0.6F, 0.6F, 0.6F, 0.125F, 0, 125.0, null, false, false);
		long revision = domain.carrierMutationRevision();
		try {
			domain.carrierMutations().appendMarker(
					CarrierMutationPlan.append(revision, 1),
					domain.cellIndex(1, 1, 1), 0.6F, 0.6F, 0.6F, 0.125F, 0, 125.0, null, false, false);
			throw new AssertionError("append beyond the marker budget was accepted");
		} catch (FluidDomain.ParticleBudgetExceededException expected) {
			require(domain.particles.count() == 1 && domain.carrierMutationRevision() == revision,
					"budget rejection changed the carrier state");
		}
	}

	private static void stalePlansRejectWithoutStateChange() throws Exception {
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		FluidDomain domain = fullCapabilityDomain(registries);
		domain.addVolumeNearBudgeted(1, 1, 1, 0.5F, 1);
		long staleRevision = domain.carrierMutationRevision();
		int count = domain.particles.count();
		domain.carrierMutations().appendMarker(
				CarrierMutationPlan.append(staleRevision, count),
				domain.cellIndex(2, 2, 2), 0.6F, 0.6F, 0.6F, 0.125F, 0, 125.0, null, false, false);
		require(domain.particles.count() == count + 1, "control append failed");
		try {
			domain.carrierMutations().appendMarker(
					CarrierMutationPlan.append(staleRevision, count),
					domain.cellIndex(2, 2, 2), 0.6F, 0.6F, 0.6F, 0.125F, 0, 125.0, null, false, false);
			throw new AssertionError("stale append plan was accepted");
		} catch (IllegalStateException expected) {
			require(domain.particles.count() == count + 1,
					"stale plan rejection changed the marker count");
		}
		try {
			domain.carrierMutations().swapRemoveMarker(
					CarrierMutationPlan.swapRemove(staleRevision, count, 0), false);
			throw new AssertionError("stale swap-remove plan was accepted");
		} catch (IllegalStateException expected) {
			require(domain.particles.count() == count + 1,
					"stale remove rejection changed the marker count");
		}
	}

	private static void wholesaleCountBindsRevisionAndBudget() throws Exception {
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		FluidDomain domain = fullCapabilityDomain(registries);
		domain.addVolumeNearBudgeted(1, 1, 1, 0.5F, 1);
		int count = domain.particles.count();
		long staleRevision = domain.carrierMutationRevision();
		domain.carrierMutations().appendMarker(
				CarrierMutationPlan.append(staleRevision, count),
				domain.cellIndex(2, 2, 2), 0.6F, 0.6F, 0.6F, 0.125F, 0, 125.0, null, false, false);
		require(domain.particles.count() == count + 1, "control append failed");
		try {
			domain.carrierMutations().adoptWholesaleCount(
					CarrierMutationPlan.wholesaleCount(staleRevision, count), 0);
			throw new AssertionError("wholesale adoption accepted a stale plan");
		} catch (IllegalStateException expected) {
			require(domain.particles.count() == count + 1, "stale wholesale rejection changed the count");
		}
		long revision = domain.carrierMutationRevision();
		domain.carrierMutations().adoptWholesaleCount(
				CarrierMutationPlan.wholesaleCount(revision, count + 1), 2);
		require(domain.particles.count() == 2 && domain.carrierMutationRevision() == revision + 1,
				"wholesale adoption did not commit exactly once");
		try {
			domain.carrierMutations().adoptWholesaleCount(
					CarrierMutationPlan.wholesaleCount(domain.carrierMutationRevision(), 2),
					domain.maximumParticleCount() + 1);
			throw new AssertionError("wholesale adoption accepted a count beyond the budget");
		} catch (IllegalArgumentException expected) {
			require(domain.particles.count() == 2, "budget rejection changed the count");
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
