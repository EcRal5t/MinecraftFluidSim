package mmm.fluid;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.fluid.air.GasRegistry;
import mmm.thermal.ThermalTransport;
import mmm.simulation.FullCapabilityTestFixture;

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
		defaultInsertionsPreflightStoredThermalState();
		explicitInsertionsPreflightStoredThermalState();
		explicitInsertionsPreflightCompleteThermalLedger();
		readOnlyCellQueriesFollowCarrierEdits();
		volumeObservationRoundsTheExactInventoryOnce();
		insertionNeverRoundsCapacityUp();
		insertionNeverRoundsTheRequestUp();
		independentWorkFinishesBeforeRejection();
		System.out.println("Carrier mutation validation passed");
	}

	private static void cellVolumeReplacementPreflightsAllOwners() throws Exception {
		cellReplacementRequiresRepresentableGeometry();
		cellReplacementPreflightsGasTopology();
		cellReplacementRefreshesAfterPartialExtraction();
		cellFillUsesEveryOpenSlot();
		FluidDomain domain = fullCapabilityDomain(FullCapabilityTestFixture.load());
		domain.setVolume(1, 1, 1, .25F);
		domain.setVolume(2, 2, 2, .125F);
		int survivor = domain.particles.count() - 1;
		var mixture = domain.chemicalParcels.record(survivor);
		double energy = domain.thermalState.liquidInternalEnergy(survivor);
		float position = domain.particles.positionX(survivor);
		float positionY = domain.particles.positionY(survivor), positionZ = domain.particles.positionZ(survivor);
		long beforeQ = domain.chemicalParcels.totalQuanta();
		long sourceQ = domain.chemicalParcels.externalSource().totalQuanta();
		long sinkQ = domain.chemicalParcels.escapedSink().totalQuanta();
		domain.setVolume(1, 1, 1, .5F);
		int retained = -1;
		for (int p = 0; p < domain.particleCount(); p++) if (domain.particles.positionX(p) == position
				&& domain.particles.positionY(p) == positionY && domain.particles.positionZ(p) == positionZ) retained = p;
		require(domain.particles.count() == 16 && retained >= 0
				&& domain.chemicalParcels.record(retained).equals(mixture)
				&& domain.thermalState.liquidInternalEnergy(retained) == energy,
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
		var registries = FullCapabilityTestFixture.load();
		for (int scene = 0; scene < 3; scene++) {
			boolean blocked = scene == 0, partial = scene != 2;
			var extraction = new FluidDomain(3, 3, 3,
					new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1e-5F, .4F, .002F, 0), 32);
			byte[] masks = new byte[27]; java.util.Arrays.fill(masks, (byte) 255);
			int[] cells = {9, 10, 12, 13}; for (int c : cells) masks[c] = 0;
			float volume = blocked ? Math.nextDown(1F) : .25F; double mass = 1000 * (double) volume;
			extraction.restoreMaterialParticleState(new FlipMaterialParticleState(3, 3, 3, .5F, 4, 4 * (double) volume, 4 * mass,
					registries.liquids().materials(), new float[]{.25F, .25F, .75F, volume, .75F, .25F, .75F, volume,
							.25F, .75F, .75F, volume, .75F, .75F, .75F, volume}, new float[12], masks,
					new double[]{mass, mass, mass, mass}, new int[4]));
			extraction.bindGranular(registries.granular(), registries.liquids(), registries.materials());
			int sand = registries.granular().materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND);
			extraction.granularState.parcels.add(new mmm.granular.GranularParcelStore.Parcel(.5F, .5F, .75F, 0, 0, 0, 3, .001, sand, 0, 0, (byte) 0, 13));
			extraction.markGranularExternalChange(13); extraction.sealDomainBoundary();
			FullCapabilityTestFixture.bindFull(extraction, registries, 300, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
			extraction.ensureInventoryGrid();
			var before = replacementState(extraction); var parcel = extraction.granularState.parcels.parcel(0);
			var dynamics = extraction.granularDepositDynamics.captureState(); var sink = extraction.granularState.escapedSink;
			long revision = extraction.granularState.parcels.mutationRevision(), gasRevision = extraction.gasState.network.revision();
			var frame = extraction.gasState.frame; var gas = extraction.exportGasState(registries.chemical().fingerprint());
			double activeEnergy = extraction.thermalState.granularColumn().activeEnergy(0);
			if (blocked) {
				var remaining = extraction.granularState.parcels.prepareOverwrite(0, new mmm.granular.GranularParcelStore.Parcel(
						parcel.x(), parcel.y(), parcel.z(), 0, 0, 0, 2, .001 * (2.0 / 3), sand, 0, 0, (byte) 0));
				require(extraction.granularCoupling.parcelShareAtCell(FluidDomainStepCoordinator.parcelPosition(parcel), 3, 10) == 0
						&& extraction.granularCoupling.parcelShareAtCell(FluidDomainStepCoordinator.parcelPosition(remaining), 2, 10) == 1
						&& frame.nodeCount() == 1 && !gas.nodes().getFirst().inventory().isEmpty(),
						"Partial granular extraction fixture must increase one solid share and eliminate its finite gas support");
				try { extraction.tryExtractExactGranular(1, 1, 1, 1, 0, true); throw new AssertionError("Partial extraction erased finite gas stock"); }
				catch (GasTopologyBlockedException expected) {
					require(expected.getMessage().contains("no positive successor"), "Granular extraction lost its geometric refusal cause");
				}
				requireUnchanged(extraction, before);
				var afterDynamics = extraction.granularDepositDynamics.captureState();
				require(extraction.granularState.parcels.parcel(0).equals(parcel) && extraction.granularState.parcels.mutationRevision() == revision
						&& extraction.granularState.escapedSink == sink && afterDynamics.logicalStep() == dynamics.logicalStep()
						&& java.util.Arrays.equals(afterDynamics.stableAge(), dynamics.stableAge()) && java.util.Arrays.equals(afterDynamics.queuedColumns(), dynamics.queuedColumns())
						&& extraction.thermalState.granularColumn().activeEnergy(0) == activeEnergy && extraction.gasState.frame == frame
						&& extraction.gasState.network.revision() == gasRevision && !extraction.gridDirty,
						"Rejected partial granular extraction changed source, dirty state, thermal or gas owners");
			} else {
				var result = extraction.tryExtractExactGranular(1, 1, 1, partial ? 1 : 3, 0, true);
				require(result.transferred() && result.ledger().totalSolidVolumeSvq() == (partial ? 1 : 3)
						&& extraction.granularState.parcels.count() == (partial ? 1 : 0)
						&& extraction.thermalState.granularColumn().activeCount() == (partial ? 1 : 0) && extraction.gasState.frame != frame
						&& extraction.gasState.network.revision() > gasRevision && extraction.gasState.network.auditClosed()
						&& extraction.exactInternalEnergy().equals(extraction.thermalState.auditLedger().expectedInventory())
						&& extraction.thermalState.auditLedger().externalSink().subtract(((mmm.thermal.ThermalAuditLedger) before.thermalAudit).externalSink())
								.equals(mmm.units.SignedDyadic.of(result.internalEnergy())),
						"Granular extraction failed to publish its gas/thermal successor and close exported energy");
				var captured = extraction.captureAuthoritativeState(); var restored = FluidDomain.restoreAuthoritativeState(captured);
				require(restored.totalGranularSolidVolumeSvq() == (partial ? 2 : 0)
						&& restored.exactInternalEnergy().equals(extraction.exactInternalEnergy())
						&& restored.exportGasState(registries.chemical().fingerprint()).nodes().equals(captured.gas().nodes()),
						"Successful granular extraction did not preserve its published gas/thermal successor after restore");
			}
		}
	}

	private static void cellReplacementRequiresRepresentableGeometry() {
		for (float h : new float[] {Float.MIN_VALUE, 3 * Float.MIN_VALUE, .1F, .5F, 1F}) {
			var d = new FluidDomain(2, 2, 2, new FluidSolverConfig(h, 1000, 0, .05F, 100, 1e-5F, .4F, .002F, 0));
			byte[] masks = new byte[8]; java.util.Arrays.fill(masks, (byte) 255); masks[0] = 126;
			d.restoreParticleState(new FlipParticleState(2, 2, 2, h, 0, 0, new float[0], new float[0], masks));
			var before = replacementState(d);
			if (h == Float.MIN_VALUE || h == 3 * Float.MIN_VALUE) {
				try { d.setVolume(0, 0, 0, .25F); throw new AssertionError("Unrepresentable micro geometry was published"); }
				catch (IllegalArgumentException expected) { }
				requireUnchanged(d, before);
				if (h == 3 * Float.MIN_VALUE) {
					byte[] incomingMasks = new byte[8]; java.util.Arrays.fill(incomingMasks, (byte) 255); incomingMasks[7] = 126;
					float coordinate = 5 * Float.MIN_VALUE;
					var incoming = new FlipParticleState(2, 2, 2, h, 1, .125F,
							new float[]{coordinate, coordinate, coordinate, .125F}, new float[3], incomingMasks);
					try { d.restoreParticleState(incoming); throw new AssertionError("Inexact half-cell metric changed a restored marker's micro component"); }
					catch (IllegalArgumentException expected) { }
					requireUnchanged(d, before);
					require(java.util.Arrays.equals(d.exportParticleState().copyCollisionMicroMasks(), masks),
							"Rejected half-cell metric changed restored collision geometry");
				}
			} else {
				d.setVolume(0, 0, 0, .25F);
				require(d.particleCount() == 2 && d.particleCellIndex(0) == 0 && d.particleCellIndex(1) == 0
						&& CellRemapMicroTopology.markerSupport(d, 0) == 1 && CellRemapMicroTopology.markerSupport(d, 1) == 128,
						"Representable marker slots changed their cell or fixed component");
				d.exportParticleState();
			}
		}
	}

	private static FluidDomain replacementGasCompartments(boolean bootstrap) {
		var d = new FluidDomain(5, 3, 3, new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1e-5F, .4F, .002F, 0));
		byte[] masks = new byte[45]; java.util.Arrays.fill(masks, (byte) 255);
		masks[21] = masks[23] = 0;
		d.restoreParticleState(new FlipParticleState(5, 3, 3, .5F, 0, 0, new float[0], new float[0], masks));
		d.sealDomainBoundary();
		if (bootstrap) d.bootstrapGasState("");
		return d;
	}

	private static void cellReplacementRefreshesAfterPartialExtraction() {
		for (boolean warmRejectedPreview : new boolean[]{true, false}) {
			var domain = replacementGasWithFullNeighbour();
			var network = domain.gasState.network;
			long gasRevision = network.revision(), carrierRevision = domain.carrierMutationRevision();
			var gas = domain.exportGasState("");
			var stale = warmRejectedPreview ? null : CellVolumeReplacementPlan.build(domain, 0, 0, 0, .75F, false);
			if (warmRejectedPreview) {
				var before = replacementState(domain);
				try { domain.setVolume(0, 0, 0, 1); throw new AssertionError("Full replacement crushed finite gas"); }
				catch (GasTopologyBlockedException expected) { }
				requireUnchanged(domain, before);
				requireReplacementGasUnchanged(domain, network, gasRevision, gas);
			}
			require(domain.removeVolumeNear(1, 0, 0, .0625F, 0) == .0625F
					&& domain.particleCount() == 16 && domain.carrierMutationRevision() == carrierRevision
					&& network.revision() > gasRevision && network.nodeCount() == 2
					&& network.availableVolume(1) == .5 && network.availableVolume(2) == .0625
					&& domain.airNodeAt(0, 0, 0) == 1 && domain.airNodeAt(1, 0, 0) == 2
					&& network.inventory(1).equals(gas.nodes().getFirst().inventory()) && network.inventory(2).isEmpty()
					&& network.edgeCount() == 1 && network.edges().getFirst().dischargeCoefficient() == .65
					&& network.auditClosed()
					&& domain.totalVolumeExact() == 1.4375,
					"Partial extraction must retain marker ordinals and publish its gas successor");
			long extractionRevision = network.revision(); var extractedGas = domain.exportGasState("");
			if (stale != null) {
				var before = replacementState(domain);
				try { domain.carrierMutations().replaceCellVolume(stale); throw new AssertionError("Replacement accepted an outdated liquid inventory"); }
				catch (IllegalStateException expected) {
					require(expected.getMessage().contains("changed after planning"), "Stale inventory rejection lost its cause");
				}
				requireUnchanged(domain, before);
				requireReplacementGasUnchanged(domain, network, extractionRevision, extractedGas, .5);
			}
			// A fresh public preview reads the newly created space directly, before any capture refresh.
			domain.setVolume(0, 0, 0, 1);
			var after = domain.captureAuthoritativeState().gas();
			require(domain.totalVolumeExact() == 1.9375 && network.availableVolume(1) == .0625
					&& after.nodes().size() == 1 && after.nodes().getFirst().inventory().equals(gas.nodes().getFirst().inventory())
					&& after.externalSource().equals(gas.externalSource()) && after.escapedSink().equals(gas.escapedSink())
					&& network.auditClosed(), "Fresh replacement preview reused the partial extraction's old liquid volume");
		}
		for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
			var rotated = replacementGasWithFullNeighbour(true, axis, sign);
			int[] source = new int[3], receiver = new int[3];
			source[axis] = sign > 0 ? 0 : 1; receiver[axis] = 1 - source[axis];
			var originalGas = rotated.exportGasState("");
			var rotatedNetwork = rotated.gasState.network;
			var trapped = replacementState(rotated); long trappedRevision = rotatedNetwork.revision();
			try { rotated.setVolume(source[0], source[1], source[2], 1); throw new AssertionError("Piston escaped through a full neighbour"); }
			catch (GasTopologyBlockedException expected) { }
			requireUnchanged(rotated, trapped);
			require(rotatedNetwork.revision() == trappedRevision && rotated.exportGasState("").nodes().equals(originalGas.nodes()),
					"Rejected rotated piston changed gas stock or revision");
			require(rotated.removeVolumeNear(receiver[0], receiver[1], receiver[2], .0625F, 0) == .0625F,
					"Rotated piston fixture changed the extracted amount");
			int loaded = rotated.airNodeAt(source[0], source[1], source[2]), vacuum = rotated.airNodeAt(receiver[0], receiver[1], receiver[2]);
			require(loaded > 0 && vacuum > 0 && loaded != vacuum && rotatedNetwork.nodeCount() == 2
					&& rotatedNetwork.availableVolume(loaded) == .5 && rotatedNetwork.availableVolume(vacuum) == .0625
					&& rotatedNetwork.inventory(loaded).equals(originalGas.nodes().getFirst().inventory()) && rotatedNetwork.inventory(vacuum).isEmpty(),
					"Rotated partial extraction lost finite-rate ownership");
			rotated.setVolume(source[0], source[1], source[2], 1);
			var result = rotated.exportGasState("");
			require(rotated.totalVolumeExact() == 1.9375 && rotatedNetwork.nodeCount() == 1 && rotatedNetwork.availableVolume(1) == .0625
					&& result.nodes().getFirst().inventory().equals(originalGas.nodes().getFirst().inventory())
					&& result.externalSource().equals(originalGas.externalSource()) && result.escapedSink().equals(originalGas.escapedSink())
					&& result.massRoundoff().equals(originalGas.massRoundoff()) && result.residualRoundoff().equals(originalGas.residualRoundoff())
					&& rotated.airNodeAt(source[0], source[1], source[2]) == -1 && rotated.airNodeAt(receiver[0], receiver[1], receiver[2]) == 1
					&& rotatedNetwork.auditClosed(), "Rotated or reflected piston changed the physical destination or inventory");
		}
		// Thermal binding uses h=.5 m; preserve the same raw fractions and piston geometry.
		var raw = replacementGasWithFullNeighbour().exportParticleState();
		float[] positions = raw.copyPositionVolume();
		for (int marker = 0; marker < raw.particleCount(); marker++) for (int axis = 0; axis < 3; axis++) positions[4 * marker + axis] *= .5F;
		var heated = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 0, .01F, 200, 1e-5F, .5F, 1e-4F, 0));
		heated.restoreParticleState(new FlipParticleState(2, 2, 2, .5F, 16, 1.5F, positions, new float[48], raw.copyCollisionMicroMasks()));
		heated.sealDomainBoundary(); heated.bootstrapGasState("");
		var liquids = new FluidMaterialRegistry(java.util.List.of(new FluidMaterial("mmm:water", 1000, "mmm:water", 0, Math.PI / 2)));
		var thermal = new mmm.thermal.ThermalRegistry(java.util.List.of(
				new mmm.thermal.ThermalMaterialDefinition("test:piston_water", mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,
						"mmm:water", 300, 0, 0, java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1, 10000, 4000))),
				new mmm.thermal.ThermalMaterialDefinition("test:piston_air", mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,
						"mmm:air", 300, 0, 0, java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1, 10000, 718)))));
		heated.bindThermal(thermal, liquids, 300);
		var originalThermalGas = heated.exportGasState("");
		heated.removeVolumeNear(1, 0, 0, .0625F, 0);
		var gasNetwork = heated.gasState.network; var thermalStock = gasNetwork.sealedInventory();
		double initialEnergy = heated.thermalState.gasNodeEnergy(1, gasNetwork);
		var audit = heated.thermalAudit();
		var compression = new mmm.thermal.GasThermalModel(thermal, gasNetwork.registry()).compressAdiabatically(
				gasNetwork.inventory(1), initialEnergy, gasNetwork.availableVolume(1) + gasNetwork.availableVolume(2), gasNetwork.availableVolume(2));
		require((gasNetwork.availableVolume(1) + gasNetwork.availableVolume(2)) * 8 == .5625
				&& gasNetwork.availableVolume(2) * 8 == .0625 && compression.workIntoGas() > 0,
				"Piston work oracle omitted the original receiver vacuum capacity");
		heated.setVolume(0, 0, 0, 1);
		require(gasNetwork.nodeCount() == 1 && gasNetwork.availableVolume(1) == .0078125
				&& gasNetwork.sealedInventory().equals(thermalStock) && gasNetwork.auditClosed()
				&& heated.exportGasState("").externalSource().equals(originalThermalGas.externalSource())
				&& heated.exportGasState("").escapedSink().equals(originalThermalGas.escapedSink())
				&& Double.doubleToRawLongBits(heated.thermalState.gasNodeEnergy(1, gasNetwork)) == Double.doubleToRawLongBits(compression.finalInternalEnergy())
				&& heated.thermalAudit().mechanicalWork().subtract(audit.mechanicalWork()).equals(mmm.units.SignedDyadic.of(compression.finalInternalEnergy()).subtract(mmm.units.SignedDyadic.of(initialEnergy)))
				&& heated.exactInternalEnergy().equals(heated.thermalAudit().expectedInventory()),
				"Quasistatic piston must retain the receiver vacuum control volume and jointly book compression work");
		var disconnected = replacementGasWithFullNeighbour(false);
		disconnected.removeVolumeNear(1, 0, 0, .0625F, 0);
		var before = replacementState(disconnected); var frame = disconnected.gasState.frame;
		var network = disconnected.gasState.network; var stock = network.sealedInventory(); long revision = network.revision();
		try { disconnected.setVolume(0, 0, 0, 1); throw new AssertionError("Gas crossed a liquid-filled face without a spatial path"); }
		catch (GasTopologyBlockedException expected) { }
		requireUnchanged(disconnected, before);
		require(disconnected.gasState.frame == frame && network.revision() == revision && network.sealedInventory().equals(stock),
				"Rejected disconnected gas displacement changed geometry or inventory");
	}

	private static FluidDomain replacementGasWithFullNeighbour() {
		return replacementGasWithFullNeighbour(true);
	}

	private static FluidDomain replacementGasWithFullNeighbour(boolean connected) {
		return replacementGasWithFullNeighbour(connected, 0, 1);
	}

	private static FluidDomain replacementGasWithFullNeighbour(boolean connected, int axis, int sign) {
		byte[] masks = new byte[8]; java.util.Arrays.fill(masks, (byte) 255); masks[0] = masks[1 << axis] = 0;
		float[] positions = new float[64];
		for (int cell = 0; cell < 2; cell++) for (int slot = 0; slot < 8; slot++) {
			int base = 4 * (8 * cell + slot);
			// The liquid in cell 0 lies to its left. Reverse the full neighbour's x ordering so
			// extracting its final marker exposes dry support at the same shared face.
			float along = !connected ? cell + .25F + .5F * (slot & 1)
					: cell == 0 ? .25F : cell + .25F + .5F * (1 - (slot & 1));
			positions[base + axis] = sign > 0 ? along : 2 - along;
			positions[base + (axis + 1) % 3] = .25F + .5F * (slot >> 1 & 1);
			positions[base + (axis + 2) % 3] = .25F + .5F * (slot >> 2 & 1);
			positions[base + 3] = cell == 0 ? .0625F : .125F;
		}
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(1, 1000, 0, .01F, 200, 1e-5F, .5F, 1e-4F, 0));
		domain.restoreParticleState(new FlipParticleState(2, 2, 2, 1, 16, 1.5F, positions, new float[48], masks));
		domain.sealDomainBoundary(); domain.bootstrapGasState("");
		return domain;
	}

	private static void requireReplacementGasUnchanged(
			FluidDomain domain, mmm.fluid.air.AirNetwork network, long revision,
			mmm.fluid.air.GasStateSnapshot expected) {
		requireReplacementGasUnchanged(domain, network, revision, expected, .5);
	}

	private static void requireReplacementGasUnchanged(
			FluidDomain domain, mmm.fluid.air.AirNetwork network, long revision,
			mmm.fluid.air.GasStateSnapshot expected, double availableVolume) {
		var actual = domain.exportGasState("");
		require(domain.gasState.network == network && network.revision() == revision && network.availableVolume(1) == availableVolume
				&& domain.gasBoundary.previousSealedNodeCount() == expected.nodes().size() && domain.gasState.pressureActive
				&& java.util.Arrays.equals(actual.pieceNodeOrdinal(), expected.pieceNodeOrdinal())
				&& actual.nodes().equals(expected.nodes()) && actual.externalSource().equals(expected.externalSource())
				&& actual.escapedSink().equals(expected.escapedSink()) && actual.massRoundoff().equals(expected.massRoundoff())
				&& actual.residualRoundoff().equals(expected.residualRoundoff()), "Rejected replacement changed gas owners or topology");
		for (int cell = 0; cell < 8; cell++) require(domain.geometry.cutCells.mask(cell) == (cell < 2 ? 0 : 255),
				"Rejected replacement changed collision geometry");
	}

	private static void cellReplacementPreflightsGasTopology() {
		var d = replacementGasCompartments(true);
		d.setVolume(1, 1, 1, .5F); d.captureAuthoritativeState();
		int left = d.airNodeAt(1, 1, 1), right = d.airNodeAt(3, 1, 1);
		var network = d.gasState.network;
		require(network.availableVolume(left) == .0625 && network.standardVolumeNanos(left) == 125000000
				&& d.gasNodeGaugePressurePa(left) == 101325, "Partial replacement failed to compress its own gas cavity");
		var state = replacementState(d); var source = network.externalSource(); var sink = network.escapedSink();
		long gasRevision = network.revision();
		try { d.setVolume(1, 1, 1, 1); throw new AssertionError("Replacement moved trapped gas through a solid partition"); }
		catch (GasTopologyBlockedException expected) { }
		requireUnchanged(d, state);
		require(d.gasState.network == network && network.revision() == gasRevision
				&& network.externalSource() == source && network.escapedSink() == sink
				&& network.standardVolumeNanos(left) == 125000000 && network.standardVolumeNanos(right) == 125000000,
				"Rejected replacement changed trapped gas or its audit");
		var stale = CellVolumeReplacementPlan.build(d, 1, 1, 1, .25F, false);
		d.setGasNodeStandardVolume(left, .15); state = replacementState(d); gasRevision = network.revision();
		try { d.carrierMutations().replaceCellVolume(stale); throw new AssertionError("Replacement accepted a stale gas revision"); }
		catch (IllegalStateException expected) { }
		requireUnchanged(d, state); require(network.revision() == gasRevision, "Stale rejection changed gas revision");
		stale = CellVolumeReplacementPlan.build(d, 1, 1, 1, .25F, false);
		d.setSolidMicroMask(2, 1, 1, 254); state = replacementState(d);
		try { d.carrierMutations().replaceCellVolume(stale); throw new AssertionError("Replacement accepted changed topology masks"); }
		catch (IllegalStateException expected) { }
		requireUnchanged(d, state);
		var vacuum = replacementGasCompartments(true);
		vacuum.setGasNodeStandardVolume(vacuum.airNodeAt(1, 1, 1), 0);
		vacuum.setVolume(1, 1, 1, 1); vacuum.captureAuthoritativeState();
		require(vacuum.totalVolumeExact() == 1 && vacuum.gasState.network.nodeCount() == 1,
				"Replacement rejected an empty cavity");
		var initial = replacementGasCompartments(false); initial.setVolume(1, 1, 1, 1);
		require(initial.totalVolumeExact() == 1 && initial.gasState.frame == null,
				"Replacement required gas inventory before topology initialization");
		var cut = new FluidDomain(2, 2, 2, d.config);
		byte[] masks = new byte[8]; java.util.Arrays.fill(masks, (byte)255); masks[0] = 126;
		cut.restoreParticleState(new FlipParticleState(2, 2, 2, .5F, 0, 0, new float[0], new float[0], masks));
		cut.granularCoupling = new mmm.granular.GranularCouplingFields(2, 2, 2, .5F);
		long[] occupied = new long[8]; occupied[0] = 1; cut.granularCoupling.setFixedCellSvq(occupied);
		float amount = .25F - Math.scalb(1F, -24);
		cut.carrierMutations().replaceCellVolume(CellVolumeReplacementPlan.build(cut, 0, 0, 0, amount, true));
		require(cut.particleCount() == 2 && cut.particles.volume(0) == .125F - Math.scalb(1F, -24)
				&& cut.particles.volume(1) == .125F, "Full fill violated the shared component SVQ capacities");
		var corner = new FluidDomain(2, 2, 2, d.config);
		corner.restoreParticleState(new FlipParticleState(2, 2, 2, .5F, 0, 0, new float[0], new float[0], masks));
		corner.granularCoupling = new mmm.granular.GranularCouplingFields(2, 2, 2, .5F);
		occupied[0] = 4194303; corner.granularCoupling.setFixedCellSvq(occupied);
		corner.carrierMutations().replaceCellVolume(CellVolumeReplacementPlan.build(corner, 0, 0, 0, Math.scalb(1F, -24), true));
		require(corner.particleCount() == 1 && corner.particles.volume(0) == Math.scalb(1F, -24)
				&& CellRemapMicroTopology.markerSupport(corner, 0) == 128, "One-SVQ fill entered a zero-capacity component");
	}

	private static void cellFillUsesEveryOpenSlot() {
		FluidDomain domain = emptyMaterialDomain(1024);
		domain.bindGranular(mmm.granular.BuiltinGranularDefinitions.createRegistry(),
				domain.materialRegistry, BuiltinFluidMaterials.createRegistry());
		int sand = domain.granularState.registry.materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND);
		long grain = mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		require(domain.tryInsertExactGranular(1, 1, 1, sand, 0, grain,
				2600 * grain / (double) mmm.granular.SolidVolume.SVQ_PER_CELL).inserted(), "initial grain was rejected");
		for (int c = 0; c < domain.cellCount; c++) domain.setVolume(c % 4, c / 4 % 4, c / 16, 1);
		for (int c = 0; c < domain.cellCount; c++) {
			var sum = mmm.units.SignedDyadic.ZERO;
			double[] moment = new double[3]; int count = 0;
			for (int p = 0; p < domain.particles.count(); p++) if (domain.particleCellIndex(p) == c) {
				float v = domain.particles.volume(p); sum = sum.add(mmm.units.SignedDyadic.of(v)); count++;
				moment[0] += v * (double) domain.particles.positionX(p);
				moment[1] += v * (double) domain.particles.positionY(p);
				moment[2] += v * (double) domain.particles.positionZ(p);
			}
			require(count == 8 && sum.equals(mmm.units.SignedDyadic.of(domain.effectiveOpenCellVolume(c))),
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
		require(partial.particles.count() == 8 && partial.particles.volume(0) == .025F
				&& partial.particles.positionX(0) == .625F && partial.particles.positionX(1) == .875F
				&& partial.particles.positionY(1) == .525F, "partial fill differs from its horizontal-layer quadrature");
	}

	private static void cellReplacementRejectsInvalidStoredThermalState() throws Exception {
		FluidDomain domain = emptyMaterialDomain(64); domain.setVolume(1, 1, 1, .001F);
		var registries = FullCapabilityTestFixture.load(); domain.bindGasRegistry(registries.gas());
		var definitions = registries.thermal().materials().stream().map(material ->
				material.carrierKind() != mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID ? material
				: new mmm.thermal.ThermalMaterialDefinition(
				material.id(), material.carrierKind(), material.carrierId(),
				300, 0, 0, java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1, 1000,
						Double.MAX_VALUE / 8)))).toList();
		domain.bindThermal(new mmm.thermal.ThermalRegistry(definitions, java.util.List.of(), java.util.List.of(),
				java.util.List.of(), java.util.List.of(), "", "", registries.gas().fingerprint()), domain.materialRegistry, 300);
		require(domain.thermalState.temperature(0, domain.thermalMarkerContext()) == 300, "initial thermal owner was invalid");
		double modeled = domain.thermalState.liquid().modeledEnergy(0, 125, null, 300, (mmm.chemistry.ChemicalRegistry) null);
		require(Double.isFinite(modeled), "invalid-inversion fixture did not have finite modeled energy");
		ReplacementState before = replacementState(domain);
		try { domain.setVolume(1, 1, 1, 1); throw new AssertionError("finite U with unrepresentable caloric owner was accepted"); }
		catch (IllegalArgumentException expected) { }
		requireUnchanged(domain, before);
	}

	private static void defaultInsertionsPreflightStoredThermalState() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		FluidDomain invalid = emptyMaterialDomain(64); invalid.setVolume(1, 1, 1, .001F);
		invalid.bindChemistry(registries.chemical()); invalid.bindGasRegistry(registries.gas());
		var definitions = registries.thermal().materials().stream().map(material ->
				material.carrierKind() != mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID ? material
				: new mmm.thermal.ThermalMaterialDefinition(material.id(), material.carrierKind(), material.carrierId(),
				300, 0, 0, java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1, 1000,
						Double.MAX_VALUE / 8)))).toList();
		invalid.bindThermal(new mmm.thermal.ThermalRegistry(definitions, registries.thermal().speciesOffsets(),
				java.util.List.of(), java.util.List.of(), java.util.List.of(), registries.chemical().fingerprint(), "",
				registries.gas().fingerprint()), invalid.materialRegistry, 300);
		require(invalid.thermalState.temperature(0, invalid.thermalMarkerContext()) == 300, "initial insertion owner was invalid");
		var record = invalid.chemicalParcels.tryDefaultRecord(invalid.materialRegistry.material(0).id(),
				.125F, invalid.config.cellSize(), 125, false);
		var ledger = mmm.chemistry.ChemicalTransferLedger.builder(registries.chemical())
				.add(invalid.materialRegistry.material(0).id(), false, .125, invalid.unitScale.kilograms(125), record).build();
		// The mixed batch's first small carrier is valid; the second carrier overflows m*cv at finite U.
		for (var insertion : java.util.List.<java.util.function.BooleanSupplier>of(
				() -> invalid.addVolumeNearBudgeted(1, 1, 1, .125F, 0).addedVolume() > 0,
				() -> invalid.tryInsertExactMixture(1, 1, 1, java.util.List.of(
						new FluidDomain.MaterialPortion(0, .001F, (double) .001F * 1000),
						new FluidDomain.MaterialPortion(1, .125, 106.25)), 0, true).transferred(),
				() -> invalid.tryInsertExactChemical(1, 1, 1, ledger, 0, true).transferred())) {
			ReplacementState before = replacementState(invalid);
			require(!insertion.getAsBoolean(), "default insertion accepted a finite U with overflowing heat capacity");
			requireUnchanged(invalid, before);
		}
		FluidDomain valid = fullCapabilityDomain(registries);
		var water = valid.chemicalParcels.tryDefaultRecord(valid.materialRegistry.material(0).id(),
				.13F, valid.config.cellSize(), (double) .13F * 1000, false);
		var chemical = mmm.chemistry.ChemicalTransferLedger.builder(registries.chemical())
				.add(valid.materialRegistry.material(0).id(), false, .13F, valid.unitScale.kilograms((double) .13F * 1000), water).build();
		for (Runnable insertion : java.util.List.<Runnable>of(
				() -> require(valid.addVolumeNearBudgeted(1, 1, 1, .13F, 0).addedVolume() == .13F, "default volume insertion failed"),
				() -> require(valid.tryInsertExactMixture(1, 1, 1, java.util.List.of(
						new FluidDomain.MaterialPortion(1, .13F, (double) .13F * 850)), 0, true).transferred(), "default material insertion failed"),
				() -> require(valid.tryInsertExactChemical(1, 1, 1, chemical, 0, true).transferred(), "default chemical insertion failed"))) {
			int first = valid.particles.count(); long beforeQ = valid.chemicalParcels.totalQuanta();
			long sourceQ = valid.chemicalParcels.externalSource().totalQuanta();
			var sourceU = valid.thermalState.auditLedger().externalSource();
			insertion.run();
			var addedU = mmm.units.SignedDyadic.ZERO;
			for (int p = first; p < valid.particles.count(); p++) {
				addedU = addedU.add(mmm.units.SignedDyadic.of(valid.thermalState.liquidInternalEnergy(p)));
				require(valid.thermalState.temperature(p, valid.thermalMarkerContext()) > 0, "default insertion stored invalid temperature");
			}
			require(valid.thermalState.auditLedger().externalSource().subtract(sourceU).equals(addedU)
					&& valid.exactInternalEnergy().equals(valid.thermalState.auditLedger().expectedInventory()),
					"default source did not retain the exact sum of actual stored energies");
			require(valid.chemicalParcels.totalQuanta() - beforeQ == valid.chemicalParcels.externalSource().totalQuanta() - sourceQ,
					"default insertion chemical source did not close");
		}
	}

	private static void explicitInsertionsPreflightStoredThermalState() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		FluidDomain domain = emptyMaterialDomain(64); domain.setVolume(2, 2, 2, .001F);
		domain.bindChemistry(registries.chemical()); domain.bindGasRegistry(registries.gas());
		var definitions = registries.thermal().materials().stream().map(material ->
				material.carrierKind() != mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID ? material
				: new mmm.thermal.ThermalMaterialDefinition(material.id(), material.carrierKind(), material.carrierId(),
				200, material.carrierId().equals("mmm:water") ? 0x1p61 : material.carrierId().equals("mmm:oil") ? -0x1p61 : 0,
				0, java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1, 1000, 1)))).toList();
		var offsets = registries.thermal().speciesOffsets().stream().map(offset -> new mmm.thermal.ThermalSpeciesOffset(
				offset.id(), offset.speciesId(), offset.hostThermalMaterialId(), mmm.chemistry.ExactRational.ZERO)).toList();
		var registry = new mmm.thermal.ThermalRegistry(definitions, offsets,
				java.util.List.of(), java.util.List.of(), java.util.List.of(), registries.chemical().fingerprint(), "", registries.gas().fingerprint());
		domain.bindThermal(registry, domain.materialRegistry, 300);
		require(domain.thermalState.temperature(0, domain.thermalMarkerContext()) > 0, "initial explicit-insertion owner was invalid");
		var pieces = java.util.List.of(new FluidDomain.MaterialPortion(0, .125, 8), new FluidDomain.MaterialPortion(1, .125, 8));
		var common = new mmm.thermal.ThermalStateFunction(registry).stateForInventory(java.util.List.of(
				new mmm.thermal.ThermalStateFunction.InventoryPortion(registry.materialHandle("mmm:water"), 1, java.util.Map.of()),
				new mmm.thermal.ThermalStateFunction.InventoryPortion(registry.materialHandle("mmm:oil"), 1, java.util.Map.of())), -398);
		require(common.temperatureK() == 1 && common.heatCapacityEuPerK() == 2,
				"explicit fixture did not have an exact positive common-temperature request");
		var builder = mmm.chemistry.ChemicalTransferLedger.builder(registries.chemical());
		for (var piece : pieces) builder.add(domain.materialRegistry.material(piece.materialHandle()).id(), false,
				piece.volume(), domain.unitScale.kilograms(piece.mass()), domain.chemicalParcels.defaultRecord(
						domain.materialRegistry.material(piece.materialHandle()).id(), .125F, .5F, 8, false));
		var chemical = builder.build();
		// At requested T=1, water U rounds to 2^61-256: T(U)=200-256=-56 despite finite U.
		for (var insertion : java.util.List.<java.util.function.BooleanSupplier>of(
				() -> domain.tryInsertExactMixture(1, 1, 1, pieces, 0, true, -398).transferred(),
				() -> domain.tryInsertExactChemical(1, 1, 1, chemical, 0, true, -398).transferred())) {
			ReplacementState before = replacementState(domain);
			require(!insertion.getAsBoolean(), "explicit insertion accepted an invalid rounded carrier energy");
			requireUnchanged(domain, before);
		}
		// T=100 rounds both carriers back to their references, whose T(U)=200 is valid.
		for (Runnable insertion : java.util.List.<Runnable>of(
				() -> require(domain.tryInsertExactMixture(1, 1, 1, pieces, 0, true, -200).transferred(), "valid explicit material rounding failed"),
				() -> require(domain.tryInsertExactChemical(1, 1, 1, chemical, 0, true, -200).transferred(), "valid explicit chemical rounding failed"))) {
			int first = domain.particleCount(); var before = domain.thermalState.auditLedger(); var oldU = domain.exactInternalEnergy();
			var oldGasU = domain.thermalState.exactGasEnergy();
			insertion.run(); var after = domain.thermalState.auditLedger();
			var stored = mmm.units.SignedDyadic.ZERO;
			for (int p = first; p < domain.particleCount(); p++) {
				stored = stored.add(mmm.units.SignedDyadic.of(domain.thermalState.liquidInternalEnergy(p)));
				require(domain.thermalState.temperature(p, domain.thermalMarkerContext()) == 200, "accepted explicit energy did not preserve a valid stored owner");
			}
			var requested = mmm.units.SignedDyadic.of(-200);
			var gasDelta = domain.thermalState.exactGasEnergy().subtract(oldGasU);
			var gasRoundoff = gasDelta.subtract(after.mechanicalWork().subtract(before.mechanicalWork()));
			require(stored.equals(mmm.units.SignedDyadic.ZERO) && after.externalSource().subtract(before.externalSource()).equals(requested)
					&& after.roundoffResidual().subtract(before.roundoffResidual()).equals(stored.subtract(requested).add(gasRoundoff))
					&& domain.exactInternalEnergy().subtract(oldU).equals(stored.add(gasDelta))
					&& domain.exactInternalEnergy().equals(after.expectedInventory()), "valid explicit source/roundoff did not close actual stored energy");
		}
	}

	private static void explicitInsertionsPreflightCompleteThermalLedger() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		var huge = new mmm.units.SignedDyadic(java.math.BigInteger.ONE, 40_000);
		var zero = mmm.units.SignedDyadic.ZERO;
		for (boolean expectedSumOverflow : new boolean[] {false, true}) {
			FluidDomain domain = fullCapabilityDomain(registries);
			domain.setVolume(2, 2, 2, .001F);
			var actual = domain.exactInternalEnergy();
			// Both ledgers close exactly; the second requires cancellation before a bounded final sum.
			var ledger = expectedSumOverflow
					? new mmm.thermal.ThermalAuditLedger(huge, zero, huge, actual, zero, zero)
					: new mmm.thermal.ThermalAuditLedger(zero, huge, huge, actual, zero, zero);
			require(ledger.expectedInventory().equals(actual), "restored explicit audit did not close inventory");
			domain.thermalState.restoreAuditLedger(ledger);
			var mixture = domain.chemicalParcels.defaultRecord("mmm:water", .125F, .5F, 125, false);
			double energy = domain.thermalState.liquid().modeledEnergy(0, 125, mixture,
					GasRegistry.STANDARD_TEMPERATURE_K, domain.chemicalParcels.registry());
			var packet = mmm.chemistry.ChemicalTransferLedger.builder(registries.chemical())
					.add("mmm:water", false, .125, domain.unitScale.kilograms(125), mixture).build();
			var insertions = new java.util.ArrayList<Runnable>(java.util.List.of(
					() -> domain.tryInsertExactMixture(1, 1, 1, java.util.List.of(new FluidDomain.MaterialPortion(0, .125, 125)), 0, true, energy),
					() -> domain.tryInsertExactChemical(1, 1, 1, packet, 0, true, energy)));
			if (expectedSumOverflow) insertions.addAll(java.util.List.of(
					() -> domain.tryInsertExactMixture(1, 1, 1, java.util.List.of(new FluidDomain.MaterialPortion(0, .125, 125)), 0, true),
					() -> domain.tryInsertExactChemical(1, 1, 1, packet, 0, true),
					() -> domain.addVolumeNearBudgeted(1, 1, 1, .125F, 0),
					() -> domain.setVolume(3, 3, 3, .5F)));
			for (Runnable insertion : insertions) {
				if (expectedSumOverflow) {
					int count = domain.particleCount(); insertion.run();
					require(domain.particleCount() > count && domain.thermalState.auditLedger().expectedInventory().equals(domain.exactInternalEnergy()),
							"representable final audit was rejected or failed to close actual insertion energy");
				} else {
					ReplacementState before = replacementState(domain);
					insertion.run();
					requireUnchanged(domain, before);
				}
			}
		}
		for (boolean operator : new boolean[]{false, true}) {
			FluidDomain cleared = fullCapabilityDomain(registries); cleared.setVolume(2, 2, 2, .001F);
			cleared.commitOperatorVentSealedGas(cleared.planOperatorVentSealedGas());
			var initial = cleared.exactInternalEnergy();
			cleared.thermalState.restoreAuditLedger(new mmm.thermal.ThermalAuditLedger(huge, zero, zero, huge.negate(), zero, initial));
			if (operator) cleared.commitOperatorClear(cleared.planOperatorClear());
			else cleared.setVolume(2, 2, 2, 0);
			require(cleared.particleCount() == 0 && cleared.thermalState.auditLedger().expectedInventory().equals(cleared.exactInternalEnergy()),
					"clear rejected representable final cancellation or lost the exact sink energy");
		}
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
				&& after.thermalAudit == before.thermalAudit, "rejected carrier edit changed owners, revisions or audit objects");
	}

	private static void independentWorkFinishesBeforeRejection() {
		int[] visits = new int[8_192];
		var first = new IllegalStateException("first batch rejection");
		var last = new AssertionError("last batch rejection");
		try {
			mmm.compute.CpuWorkBatch.run(visits.length, (from, until) -> {
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
		mmm.compute.CpuWorkBatch.run(visits.length, (from, until) -> { for (int index = from; index < until; index++) { visits[index]++; } });
		for (int visit : visits) require(visit == 1, "independent work missed or repeated an index after rejection");
	}

	private static void insertionNeverRoundsTheRequestUp() {
		FluidDomain domain = emptyMaterialDomain(1024);
		for (int c = 0; c < domain.cellCount; c++) domain.setVolume(c % 4, c / 4 % 4, c / 16, (c % 13 + 1) / 17.0F);
		for (int round = 0; round < 12; round++) {
			var before = mmm.units.SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count());
			float request = 1.3F;
			var receipt = domain.addVolumeNearBudgeted(1, 1, 1, request, 10);
			var inserted = mmm.units.SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count()).subtract(before);
			require(inserted.compareTo(mmm.units.SignedDyadic.of(request)) <= 0,
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
			var actual = mmm.units.SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count());
			require(actual.compareTo(mmm.units.SignedDyadic.of(1)) <= 0 && actual.toDouble() >= 1 - 8 * Math.ulp(1.0F),
					"operator insertion rounded a physical capacity gap upward");
		}
	}

	private static void volumeObservationRoundsTheExactInventoryOnce() {
		float[] values = new float[258]; values[0] = 1;
		java.util.Arrays.fill(values, 1, values.length, Math.scalb(1.0F, -60));
		var exact = mmm.units.SignedDyadic.ZERO;
		for (float value : values) exact = exact.add(mmm.units.SignedDyadic.of(value));
		require(exact.toDouble() == Math.nextUp(1.0), "wide-range volume fixture missed its accumulated small parcels");
		float[] positions = new float[values.length * 4]; double[] masses = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			positions[4 * i] = positions[4 * i + 1] = positions[4 * i + 2] = i == 0 ? 0.125F : 0.625F;
			positions[4 * i + 3] = values[i]; masses[i] = 1000 * (double) values[i];
		}
		FluidDomain domain = emptyMaterialDomain(512);
		var exactMass = mmm.units.SignedDyadic.ZERO;
		for (double mass : masses) exactMass = exactMass.add(mmm.units.SignedDyadic.of(mass));
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(4, 4, 4, 0.5F, values.length,
				exact.toDouble(), exactMass.toDouble(), domain.materialRegistry.materials(),
				positions, new float[values.length * 3], new byte[64], masses, new int[values.length]));
		require(domain.totalVolumeExact() == exact.toDouble() && domain.exportMaterialParticleState().totalVolume() == exact.toDouble(),
				"material observation or handoff dropped small physical parcel volumes");
		require(domain.totalMass() == exactMass.toDouble() && domain.exportMaterialParticleState().totalMass() == exactMass.toDouble(),
				"material observation or handoff dropped small physical parcel masses");
		float[] varied = new float[1024]; var oracle = mmm.units.SignedDyadic.ZERO;
		for (int i = 0; i < varied.length; i++) {
			varied[i] = Float.intBitsToFloat((i % 255 << 23) | (i * 15485863 & 0x7fffff) | (i % 3 == 0 ? Integer.MIN_VALUE : 0));
			oracle = oracle.add(mmm.units.SignedDyadic.of(varied[i]));
		}
		require(mmm.units.SignedDyadic.sum(varied, varied.length).equals(oracle),
				"binary32 inventory bins missed cancellation, subnormal or extreme finite values");
		float[] parcels = {0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, Float.MIN_VALUE, 0.125F, Float.MIN_VALUE, 0, -0.0F};
		int[] heads = {0, -1, 8, 9}, next = {1, 2, 3, 4, 5, 6, 7, -1, -1, 10, -1, -1, -1};
		require(ExactCellVolumeSums.cellUnitExponent(parcels, heads[0], next) == -3
				&& ExactCellVolumeSums.cellUnitExponent(parcels, heads[2], next) == -149,
				"a distant subnormal parcel widened another cell's integer lattice");
		var cells = ExactCellVolumeSums.sum(parcels, parcels.length, heads, next);
		for (int cell = 0; cell < heads.length; cell++) {
			var expected = mmm.units.SignedDyadic.ZERO;
			for (int p = heads[cell]; p >= 0; p = next[p]) expected = expected.add(mmm.units.SignedDyadic.of(parcels[p]));
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
		mmm.units.SignedDyadic energyBefore = domain.exactInternalEnergy();

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
						energyBefore.subtract(mmm.units.SignedDyadic.of(removedEnergy))),
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
		restoreRequiresConnectedOpenCapacity();
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

	private static void restoreRequiresConnectedOpenCapacity() throws Exception {
		var config = new FluidSolverConfig(.5F,1000,0,.01F,200,1e-5F,.5F,1e-4F,0);
		var d = new FluidDomain(2,2,2,config,16); d.setVolume(1,1,1,.25F);
		byte[] masks = new byte[8]; java.util.Arrays.fill(masks,(byte)255); masks[0]=126;
		var before = d.exportParticleState(); long revision = d.carrierMutationRevision();
		try {
			d.restoreParticleState(new FlipParticleState(2,2,2,Math.nextUp(.5F),1,.125F,
					new float[]{.125F,.125F,.125F,.125F},new float[3],new byte[8]));
			throw new AssertionError("Restore silently changed physical volume and mass through another cell metric");
		} catch (IllegalArgumentException expected) {
			require(d.carrierMutationRevision()==revision && java.util.Arrays.equals(before.copyPositionVolume(),d.exportParticleState().copyPositionVolume()),
					"Metric-rejected restore changed the original stock");
		}
		try {
			new FlipParticleState(2,2,2,Float.POSITIVE_INFINITY,0,0,new float[0],new float[0],new byte[8]);
			throw new AssertionError("Particle handoff accepted an infinite cell metric");
		} catch (IllegalArgumentException expected) { }
		for (float[] points : java.util.List.of(new float[]{.125F,.125F,.125F,.25F},
				new float[]{.125F,.125F,.125F,.125F,.125F,.125F,.125F,Float.MIN_VALUE})) {
			var invalid = new FlipParticleState(2,2,2,.5F,points.length/4,points.length==4?.25F:.125F,
					points,new float[3*(points.length/4)],masks);
			try { d.restoreParticleState(invalid); throw new AssertionError("Restore accepted over-capacity connected liquid stock"); }
			catch (IllegalArgumentException expected) { }
			var after = d.exportParticleState();
			require(d.carrierMutationRevision()==revision && java.util.Arrays.equals(before.copyPositionVolume(),after.copyPositionVolume())
					&& java.util.Arrays.equals(before.copyParticleVelocities(),after.copyParticleVelocities())
					&& java.util.Arrays.equals(before.copyCollisionMicroMasks(),after.copyCollisionMicroMasks()),
					"Capacity-rejected restore changed original marker or collision state");
		}
		d.restoreParticleState(new FlipParticleState(2,2,2,.5F,2,.25F,
				new float[]{.125F,.125F,.125F,.125F,.375F,.375F,.375F,.125F},new float[6],masks));
		require(d.totalVolumeExact()==.25,"Restore rejected legitimate separately filled micro components");
		d.restoreParticleState(new FlipParticleState(2,2,2,.5F,1,1,new float[]{.125F,.125F,.125F,1},new float[3],new byte[8]));
		require(d.totalVolumeExact()==1,"Restore limited a connected component to one microbox");
		var floatGrid = new FluidDomain(4,4,4,new FluidSolverConfig(.1F,1000,0,.01F,200,1e-5F,.5F,1e-4F,0),16);
		byte[] floatMasks=new byte[64];java.util.Arrays.fill(floatMasks,(byte)255);floatMasks[42]=126;
		floatGrid.restoreParticleState(new FlipParticleState(4,4,4,.1F,1,.125F,new float[]{.25F,.25F,.25F,.125F},new float[3],floatMasks));
		require(CellRemapMicroTopology.markerSupport(floatGrid,0)==128,"Restore changed actual binary32 micro-face ownership");

		var material = fullCapabilityDomain(FullCapabilityTestFixture.load()); material.setVolume(1,1,1,.25F);
		ReplacementState materialBefore=replacementState(material); byte[] originalMasks=material.geometry.cutCells.copyMicroMasks();
		byte[] materialMasks=originalMasks.clone();
		materialMasks[0]=126;
		var mixed = new FlipMaterialParticleState(4,4,4,.5F,2,.1875,178.125,material.materialRegistry.materials(),
				new float[]{.125F,.125F,.125F,.09375F,.125F,.125F,.125F,.09375F},new float[6],materialMasks,
				new double[]{93.75,84.375},new int[]{0,1});
		try { material.restoreMaterialParticleState(mixed); throw new AssertionError("Restore gave each material an independent component capacity"); }
		catch (IllegalArgumentException expected) { }
		requireUnchanged(material,materialBefore);
		require(java.util.Arrays.equals(material.geometry.cutCells.copyMicroMasks(),originalMasks),
				"Capacity-rejected material restore changed collision geometry");
		var builtin=mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY;
		var waterOnly=new mmm.chemistry.ChemicalRegistry(builtin.species(),builtin.phases().stream()
				.filter(phase->phase.materialId().equals("mmm:water")).toList(),builtin.reactions());
		var emptyIncoming=new FlipMaterialParticleState(4,4,4,.5F,0,0,0,material.materialRegistry.materials(),
				new float[0],new float[0],new byte[64],new double[0],new int[0]);
		var incompatible=FlipChemicalParticleState.capture(emptyIncoming,waterOnly,java.util.List.of(),new byte[0],
				mmm.chemistry.ChemicalTransferLedger.EMPTY,mmm.chemistry.ChemicalTransferLedger.EMPTY);
		try { material.restoreChemicalParticleState(incompatible,waterOnly); throw new AssertionError("Restore accepted unsupported chemical material phases"); }
		catch (IllegalArgumentException expected) { }
		requireUnchanged(material,materialBefore);
		var valid=material.exportChemicalParticleState();
		var restored=new FluidDomain(4,4,4,material.config(),material.maximumParticleCount());
		restored.restoreChemicalParticleState(valid,material.chemicalParcels.registry());
		require(restored.totalVolumeExact()==material.totalVolumeExact() && restored.totalMass()==material.totalMass()
				&& java.util.Arrays.equals(restored.chemicalParcels.speciesTotals(restored.particles.count()),valid.copySpeciesTotals())
				&& java.util.Arrays.equals(restored.exportChemicalParticleState().materials().copyPositionVolume(),valid.materials().copyPositionVolume()),
				"Prepared chemical restore changed valid carrier inventory or positions");
	}

	private static void chemicalRemapPublishesTheFinalBudget() {
		var registry = mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY;
		var store = new mmm.chemistry.ChemicalParcelStore(registry,
				new mmm.units.SolverUnitScale(1), 4, 64);
		long limit = mmm.chemistry.ChemicalLimits.MAX_REGION_TOTAL_QUANTA;
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
