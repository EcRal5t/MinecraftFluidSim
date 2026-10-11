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
		chemicalRemapPublishesTheFinalBudget();
		cellVolumeReplacementPreflightsAllOwners();
		readOnlyCellQueriesFollowCarrierEdits();
		volumeObservationRoundsTheExactInventoryOnce();
		insertionNeverRoundsCapacityUp();
		insertionNeverRoundsTheRequestUp();
		independentWorkFinishesBeforeRejection();
		System.out.println("Carrier mutation validation passed");
	}

	private static void cellVolumeReplacementPreflightsAllOwners() throws Exception {
		cellFillUsesEveryOpenSlot();
		FluidDomain domain = fullCapabilityDomain(FullCapabilityTestFixture.load());
		domain.setVolume(1, 1, 1, .25F);
		domain.setVolume(2, 2, 2, .125F);
		int survivor = domain.particles.count() - 1;
		var mixture = domain.chemicalParcels.record(survivor);
		double energy = domain.thermalState.liquidInternalEnergy(survivor);
		float position = domain.particles.positionX(survivor);
		long beforeQ = domain.chemicalParcels.totalQuanta();
		long sourceQ = domain.chemicalParcels.externalSource().totalQuanta();
		long sinkQ = domain.chemicalParcels.escapedSink().totalQuanta();
		domain.setVolume(1, 1, 1, .5F);
		require(domain.particles.count() == 5 && domain.particles.positionX(0) == position
				&& domain.chemicalParcels.record(0).equals(mixture)
				&& domain.thermalState.liquidInternalEnergy(0) == energy,
				"cell replacement misplaced a surviving marker or its side columns during descending deletion");
		require(domain.chemicalParcels.totalQuanta() - beforeQ
				== domain.chemicalParcels.externalSource().totalQuanta() - sourceQ
				- (domain.chemicalParcels.escapedSink().totalQuanta() - sinkQ),
				"cell replacement chemical source/sink did not close");
		require(domain.exactInternalEnergy().equals(domain.thermalState.auditLedger().expectedInventory()),
				"cell replacement did not book the exact sum of stored incoming and removed internal energies");
		for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MIN_VALUE}) {
			ReplacementState before = replacementState(domain);
			try { domain.setVolume(1, 1, 1, invalid); throw new AssertionError("unrepresentable cell replacement was accepted"); }
			catch (IllegalArgumentException expected) { }
			requireUnchanged(domain, before);
		}
		var plan = CellVolumeReplacementPlan.build(domain, 1, 1, 1, .25F, false);
		domain.chemicalParcels.recordExternalSource(domain.chemicalParcels.externalSource());
		ReplacementState staleState = replacementState(domain);
		try { domain.carrierMutations().replaceCellVolume(plan); throw new AssertionError("stale chemical audit was accepted"); }
		catch (IllegalStateException expected) { }
		requireUnchanged(domain, staleState);
		cellReplacementRejectsInvalidStoredThermalState();
	}

	private static void cellFillUsesEveryOpenSlot() {
		FluidDomain domain = emptyMaterialDomain(1024);
		domain.bindGranular(cc.ecisr.mmm.granular.BuiltinGranularDefinitions.createRegistry(),
				domain.materialRegistry, BuiltinFluidMaterials.createRegistry());
		int sand = domain.granularState.registry.materialHandle(cc.ecisr.mmm.granular.BuiltinGranularDefinitions.FINE_SAND);
		long grain = cc.ecisr.mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		require(domain.tryInsertExactGranular(1, 1, 1, sand, 0, grain,
				2600 * grain / (double) cc.ecisr.mmm.granular.SolidVolume.SVQ_PER_CELL).inserted(), "initial grain was rejected");
		for (int c = 0; c < domain.cellCount; c++) domain.setVolume(c % 4, c / 4 % 4, c / 16, 1);
		for (int c = 0; c < domain.cellCount; c++) {
			var sum = cc.ecisr.mmm.units.SignedDyadic.ZERO;
			double[] moment = new double[3]; int count = 0;
			for (int p = 0; p < domain.particles.count(); p++) if (domain.particleCellIndex(p) == c) {
				float v = domain.particles.volume(p); sum = sum.add(cc.ecisr.mmm.units.SignedDyadic.of(v)); count++;
				moment[0] += v * (double) domain.particles.positionX(p);
				moment[1] += v * (double) domain.particles.positionY(p);
				moment[2] += v * (double) domain.particles.positionZ(p);
			}
			require(count == 8 && sum.equals(cc.ecisr.mmm.units.SignedDyadic.of(domain.effectiveOpenCellVolume(c))),
					"full available capacity lost an open slot or exact total volume");
			for (int axis = 0; axis < 3; axis++) {
				double center = ((axis == 0 ? c % 4 : axis == 1 ? c / 4 % 4 : c / 16) + .5) * .5;
				require(moment[axis] == sum.toDouble() * center, "uniform scalar pore fill displaced its first moment");
			}
		}
		for (int open : new int[] {3, 5, 7}) {
			FluidDomain cut = emptyMaterialDomain(64);
			cut.setSolidMicroMask(1, 1, 1, 255 ^ ((1 << open) - 1)); cut.setVolume(1, 1, 1, 1);
			require(cut.particles.count() == open, "full cut-cell fill omitted an open micro slot");
			for (int p = 0; p < open; p++) require(cut.particles.volume(p) == .125F, "cut-cell fill changed exact uniform weights");
		}
		FluidDomain partial = emptyMaterialDomain(64); partial.setVolume(1, 1, 1, .2F);
		require(partial.particles.count() == 2 && partial.particles.volume(0) == .125F
				&& partial.particles.positionX(0) == .625F && partial.particles.positionX(1) == .875F
				&& partial.particles.positionY(1) == .625F, "partial fill changed its sequential open-slot geometry");
	}

	private static void cellReplacementRejectsInvalidStoredThermalState() throws Exception {
		FluidDomain domain = emptyMaterialDomain(64); domain.setVolume(1, 1, 1, .001F);
		var registries = FullCapabilityTestFixture.load(); domain.bindGasRegistry(registries.gas());
		var definitions = registries.thermal().materials().stream().map(material ->
				material.carrierKind() != cc.ecisr.mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID ? material
				: new cc.ecisr.mmm.thermal.ThermalMaterialDefinition(
				material.id(), material.carrierKind(), material.carrierId(),
				300, 0, 0, java.util.List.of(new cc.ecisr.mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1, 1000,
						Double.MAX_VALUE / 8)))).toList();
		domain.bindThermal(new cc.ecisr.mmm.thermal.ThermalRegistry(definitions, java.util.List.of(), java.util.List.of(),
				java.util.List.of(), java.util.List.of(), "", "", registries.gas().fingerprint()), domain.materialRegistry, 300);
		require(domain.thermalState.temperature(0, domain.thermalMarkerContext()) == 300, "initial thermal owner was invalid");
		double modeled = domain.thermalState.liquid().modeledEnergy(0, 125, null, 300, (cc.ecisr.mmm.chemistry.ChemicalRegistry) null);
		require(Double.isFinite(modeled), "invalid-inversion fixture did not have finite modeled energy");
		ReplacementState before = replacementState(domain);
		try { domain.setVolume(1, 1, 1, 1); throw new AssertionError("finite U with unrepresentable caloric owner was accepted"); }
		catch (IllegalArgumentException expected) { }
		requireUnchanged(domain, before);
	}

	private record ReplacementState(String owners, Object source, Object sink, Object thermalAudit) { }
	private static ReplacementState replacementState(FluidDomain domain) {
		StringBuilder state = new StringBuilder().append(domain.carrierMutationRevision()).append(':').append(domain.particles.count());
		for (int p = 0; p < domain.particles.count(); p++) {
			state.append(':').append(domain.particles.positionX(p)).append(':').append(domain.particles.positionY(p))
					.append(':').append(domain.particles.positionZ(p)).append(':').append(domain.particles.volume(p)).append(':').append(domain.particles.mass(p))
					.append(':').append(domain.particles.velocityX(p)).append(':').append(domain.particles.velocityY(p)).append(':').append(domain.particles.velocityZ(p))
					.append(':').append(domain.particles.materialHandle(p));
			if (domain.chemicalParcels != null) state.append(':').append(domain.chemicalParcels.mixtureHandle(p)).append(':').append(domain.chemicalParcels.record(p));
			if (domain.thermalState != null) state.append(':').append(domain.thermalState.liquidInternalEnergy(p));
		}
		if (domain.chemicalParcels != null) state.append(':').append(domain.chemicalParcels.mutationRevision()).append(':').append(domain.chemicalParcels.totalQuanta())
				.append(':').append(java.util.Arrays.toString(domain.chemicalParcels.dirtyCells()));
		if (domain.thermalState != null) state.append(':').append(domain.thermalState.thermalRevision());
		return new ReplacementState(state.toString(), domain.chemicalParcels == null ? null : domain.chemicalParcels.externalSource(),
				domain.chemicalParcels == null ? null : domain.chemicalParcels.escapedSink(), domain.thermalState == null ? null : domain.thermalState.auditLedger());
	}
	private static void requireUnchanged(FluidDomain domain, ReplacementState before) {
		ReplacementState after = replacementState(domain);
		require(after.owners.equals(before.owners) && after.source == before.source && after.sink == before.sink
				&& after.thermalAudit == before.thermalAudit, "rejected cell replacement changed owners, revisions or audit objects");
	}

	private static void independentWorkFinishesBeforeRejection() {
		int[] visits = new int[8_192];
		var first = new IllegalStateException("first batch rejection");
		var last = new AssertionError("last batch rejection");
		try {
			cc.ecisr.mmm.compute.CpuWorkBatch.run(visits.length, (from, until) -> {
				for (int index = from; index < until; index++) {
					if (index == 0) throw first;
					if (index == visits.length - 1) throw last;
					visits[index]++;
				}
			});
			throw new AssertionError("worker rejection was lost");
		} catch (IllegalStateException rejected) {
			require(rejected == first, "worker rejection changed identity or canonical batch order");
			if (Runtime.getRuntime().availableProcessors() > 3)
				require(visits[visits.length - 2] == 1, "rejection returned while another batch was unfinished");
			if (Runtime.getRuntime().availableProcessors() > 5)
				require(visits[visits.length / 2 - 1] == 1, "rejection returned before an independent batch finished");
		}
		java.util.Arrays.fill(visits, 0);
		cc.ecisr.mmm.compute.CpuWorkBatch.run(visits.length, (from, until) -> { for (int index = from; index < until; index++) { visits[index]++; } });
		for (int visit : visits) require(visit == 1, "independent work missed or repeated an index after rejection");
	}

	private static void insertionNeverRoundsTheRequestUp() {
		FluidDomain domain = emptyMaterialDomain(1024);
		for (int c = 0; c < domain.cellCount; c++) domain.setVolume(c % 4, c / 4 % 4, c / 16, (c % 13 + 1) / 17.0F);
		for (int round = 0; round < 12; round++) {
			var before = cc.ecisr.mmm.units.SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count());
			float request = 1.3F;
			var receipt = domain.addVolumeNearBudgeted(1, 1, 1, request, 10);
			var inserted = cc.ecisr.mmm.units.SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count()).subtract(before);
			require(inserted.compareTo(cc.ecisr.mmm.units.SignedDyadic.of(request)) <= 0,
					"cross-cell float subtraction increased the requested source inventory");
			require(Math.abs(inserted.toDouble() - receipt.addedVolume()) <= 0.5 * Math.ulp(receipt.addedVolume()),
					"insertion receipt lost more than its final binary32 rounding");
		}
	}

	private static void insertionNeverRoundsCapacityUp() {
		float edge = Math.nextDown(Math.nextDown(Math.nextDown(0.375F)));
		for (float initial : new float[] {edge, Math.scalb(1.0F, -60)}) {
			FluidDomain domain = emptyMaterialDomain(64);
			domain.carrierMutations().appendMarker(CarrierMutationPlan.append(domain.carrierMutationRevision(), 0),
					domain.cellIndex(1, 1, 1), 0.625F, 0.625F, 0.625F, initial, 0, 1000.0 * initial, null, false, false);
			domain.addVolumeNearBudgeted(1, 1, 1, 1, 0);
			var actual = cc.ecisr.mmm.units.SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count());
			require(actual.compareTo(cc.ecisr.mmm.units.SignedDyadic.of(1)) <= 0 && actual.toDouble() >= 1 - 8 * Math.ulp(1.0F),
					"operator insertion rounded a physical capacity gap upward");
		}
	}

	private static void volumeObservationRoundsTheExactInventoryOnce() {
		float[] values = new float[258]; values[0] = 1;
		java.util.Arrays.fill(values, 1, values.length, Math.scalb(1.0F, -60));
		var exact = cc.ecisr.mmm.units.SignedDyadic.ZERO;
		for (float value : values) exact = exact.add(cc.ecisr.mmm.units.SignedDyadic.of(value));
		require(exact.toDouble() == Math.nextUp(1.0), "wide-range volume fixture missed its accumulated small parcels");
		float[] positions = new float[values.length * 4]; double[] masses = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			positions[4 * i] = positions[4 * i + 1] = positions[4 * i + 2] = i == 0 ? 0.125F : 0.625F;
			positions[4 * i + 3] = values[i]; masses[i] = 1000 * (double) values[i];
		}
		FluidDomain domain = emptyMaterialDomain(512);
		var exactMass = cc.ecisr.mmm.units.SignedDyadic.ZERO;
		for (double mass : masses) exactMass = exactMass.add(cc.ecisr.mmm.units.SignedDyadic.of(mass));
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(4, 4, 4, 0.5F, values.length,
				exact.toDouble(), exactMass.toDouble(), domain.materialRegistry.materials(),
				positions, new float[values.length * 3], new byte[64], masses, new int[values.length]));
		require(domain.totalVolumeExact() == exact.toDouble() && domain.exportMaterialParticleState().totalVolume() == exact.toDouble(),
				"material observation or handoff dropped small physical parcel volumes");
		require(domain.totalMass() == exactMass.toDouble() && domain.exportMaterialParticleState().totalMass() == exactMass.toDouble(),
				"material observation or handoff dropped small physical parcel masses");
		float[] varied = new float[1024]; var oracle = cc.ecisr.mmm.units.SignedDyadic.ZERO;
		for (int i = 0; i < varied.length; i++) {
			varied[i] = Float.intBitsToFloat((i % 255 << 23) | (i * 15485863 & 0x7fffff) | (i % 3 == 0 ? Integer.MIN_VALUE : 0));
			oracle = oracle.add(cc.ecisr.mmm.units.SignedDyadic.of(varied[i]));
		}
		require(cc.ecisr.mmm.units.SignedDyadic.sum(varied, varied.length).equals(oracle),
				"binary32 inventory bins missed cancellation, subnormal or extreme finite values");
		float[] parcels = {0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, Float.MIN_VALUE, 0.125F, Float.MIN_VALUE, 0, -0.0F};
		int[] heads = {0, -1, 8, 9}, next = {1, 2, 3, 4, 5, 6, 7, -1, -1, 10, -1, -1, -1};
		require(ExactCellVolumeSums.cellUnitExponent(parcels, heads[0], next) == -3
				&& ExactCellVolumeSums.cellUnitExponent(parcels, heads[2], next) == -149,
				"a distant subnormal parcel widened another cell's integer lattice");
		var cells = ExactCellVolumeSums.sum(parcels, parcels.length, heads, next);
		for (int cell = 0; cell < heads.length; cell++) {
			var expected = cc.ecisr.mmm.units.SignedDyadic.ZERO;
			for (int p = heads[cell]; p >= 0; p = next[p]) expected = expected.add(cc.ecisr.mmm.units.SignedDyadic.of(parcels[p]));
			require(cells[cell].equals(expected), "cell-local integer lattice changed the independent exact volume oracle");
		}
		for (float invalid : new float[] {-1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
			parcels[12] = invalid;
			ExactCellVolumeSums.sum(parcels, 12, heads, next); // Unused backing capacity is outside the valid count.
			try {
				ExactCellVolumeSums.sum(parcels, 13, heads, next);
				throw new AssertionError("an unindexed active parcel escaped volume validation");
			} catch (IllegalArgumentException expected) { }
		}
	}

	private static void readOnlyCellQueriesFollowCarrierEdits() {
		FluidDomain domain = emptyMaterialDomain(256);
		domain.setVolume(1, 1, 1, 1);
		domain.setVolume(2, 1, 1, 0.5F);
		for (int round = 0; round < 3; round++) {
			for (int particle = 0; particle < domain.particles.count(); particle++)
				domain.particles.setVelocityX(particle, round + 0.5F);
			boolean dirty = domain.gridDirty;
			var before = domain.observeCellInventory(1, 1, 1);
			require(domain.gridDirty == dirty, "query preserves grid rebuild scheduling");
			float[] previousVelocity = domain.mac.velocityX.clone();
			domain.volume(1, 1, 1);
			require(!domain.gridDirty && domain.velocityGridDirty, "inventory query prepares geometry and leaves velocity pending");
			require(java.util.Arrays.equals(previousVelocity, domain.mac.velocityX), "inventory query changed the velocity grid");
			FluidDomain reference = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			reference.ensureGrid();
			domain.ensureGrid();
			require(!domain.velocityGridDirty, "mechanical read left velocity pending");
			require(java.util.Arrays.equals(reference.mac.velocityX, domain.mac.velocityX)
					&& java.util.Arrays.equals(reference.mac.velocityY, domain.mac.velocityY)
					&& java.util.Arrays.equals(reference.mac.velocityZ, domain.mac.velocityZ),
					"deferred velocity transfer differs from a complete restored rebuild");
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

	private static void chemicalRemapPublishesTheFinalBudget() {
		var registry = cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY;
		var store = new cc.ecisr.mmm.chemistry.ChemicalParcelStore(registry,
				new cc.ecisr.mmm.units.SolverUnitScale(1), 4, 64);
		long limit = cc.ecisr.mmm.chemistry.ChemicalLimits.MAX_REGION_TOTAL_QUANTA;
		var small = new ChemicalMixtureRecord(new int[] {0}, new long[] {1}, 0);
		var large = new ChemicalMixtureRecord(new int[] {0}, new long[] {limit - 1}, 0);
		store.assign(0, small, false); store.assign(1, large, true); store.markDirtyCell(9);
		int first = store.mixtureHandle(0), second = store.mixtureHandle(1);
		var replacements = new ChemicalMixtureRecord[] {large, small}; boolean[] indicators = {true, false};
		var plan = store.planMarkerRemap(2, replacements, indicators);
		replacements[0] = small; indicators[0] = false;
		store.commitMarkerRemap(plan);
		require(store.totalQuanta() == limit && store.record(0).equals(large) && store.indicator(0)
				&& store.mixtureHandle(0) == first && store.mixtureHandle(1) == second
				&& java.util.Arrays.equals(store.dirtyCells(), new int[] {9}),
				"whole chemical remap lost the final budget, immutable plan, handles or dirty cells");
		long revision = store.mutationRevision();
		try {
			store.planMarkerRemap(2, new ChemicalMixtureRecord[] {large, large}, new boolean[2]);
			throw new AssertionError("over-budget chemical remap was accepted");
		} catch (IllegalArgumentException expected) {
			require(store.mutationRevision() == revision && store.totalQuanta() == limit,
					"chemical remap preflight changed the store");
		}
		var stale = store.planMarkerRemap(2, new ChemicalMixtureRecord[] {small}, new boolean[1]);
		store.updateIndicator(0, false);
		try {
			store.commitMarkerRemap(stale); throw new AssertionError("stale chemical remap was accepted");
		} catch (IllegalStateException expected) {
			require(store.pool().liveCount() == 2 && store.totalQuanta() == limit, "stale chemical remap changed inventory");
		}
		store.commitMarkerRemap(store.planMarkerRemap(2, new ChemicalMixtureRecord[] {large}, new boolean[] {true}));
		require(store.pool().liveCount() == 1 && !store.isAssigned(1) && store.totalQuanta() == limit - 1,
				"chemical remap shrink did not release surplus handles");
		store.commitMarkerRemap(store.planMarkerRemap(1, new ChemicalMixtureRecord[] {large, small}, new boolean[2]));
		require(store.pool().liveCount() == 2 && store.totalQuanta() == limit && store.isAssigned(1),
				"chemical remap growth did not adopt aligned handles");
		store.commitMarkerRemap(store.planMarkerRemap(2, new ChemicalMixtureRecord[0], new boolean[0]));
		require(store.pool().liveCount() == 0 && store.totalQuanta() == 0, "empty chemical remap retained inventory");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
