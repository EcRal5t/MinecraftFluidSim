package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.simulation.RegionCheckpointFixtures;

import cc.ecisr.mmm.model.region.RegionExtension;
import cc.ecisr.mmm.model.region.RegionManifest;
import cc.ecisr.mmm.model.region.RegionSectionState;
import cc.ecisr.mmm.model.region.RegionStateLayout;

import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FlipChemicalParticleState;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FlipThermalParticleState;
import cc.ecisr.mmm.fluid.PendingPhaseVolumeState;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.granular.GranularStateSnapshot;
import cc.ecisr.mmm.granular.SolidCompositionRecord;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;
import cc.ecisr.mmm.thermal.FiniteThermalBodyState;
import cc.ecisr.mmm.thermal.FiniteThermalDeviceInventory;
import cc.ecisr.mmm.thermal.FiniteThermalDeviceState;
import cc.ecisr.mmm.thermal.FiniteThermalExchange;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.simulation.FluidRegion;
import cc.ecisr.mmm.simulation.FullCapabilityTestFixture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

abstract class RegionCodecValidationScenarios extends RegionPersistenceValidationSupport {
	static void validateThermalCodec() throws Exception {
		validateGasLineageCodec();
		FluidRegion region = populatedRegion(new UUID(27L, 28L));
		FlipParticleState pure = region.domain().exportParticleState();
		float[] positions = pure.copyPositionVolume();
		double[] mass = new double[pure.particleCount()];
		double totalMass = 0.0;
		for (int marker = 0; marker < mass.length; marker++) {
			mass[marker] = positions[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			totalMass += mass[marker];
		}
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		region.domain().restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(), pure.totalVolume(),
				totalMass, liquids.materials(), positions, pure.copyParticleVelocities(), pure.copySolidMask(),
				mass, new int[pure.particleCount()]));
		liquids = region.domain().materialRegistry();
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long activeSvq = GranularLimits.MAX_PARCEL_VOLUME_SVQ, depositSvq = activeSvq - 17L;
		double activeMass = 3.25, depositMass = 4.75;
		int depositCell = (5 * region.sizeY() + 3) * region.sizeX() + 5;
		GranularStateSnapshot granularFixture = new GranularStateSnapshot(
				region.sizeX(), region.sizeY(), region.sizeZ(), granular.fingerprint(),
				granular.materials().stream().map(cc.ecisr.mmm.granular.ParticulateMaterial::id).toList(),
				List.of(new GranularStateSnapshot.ActiveParcel(0.75F, 0.75F, 0.75F,
						0.125F, -0.25F, 0.0625F, activeSvq, activeMass, sand, 0,
						2, cc.ecisr.mmm.granular.GranularParcelStore.FLAG_SUPPORTED)),
				List.of(new GranularStateSnapshot.DepositGroup(depositCell, granular.material(sand).restPorosityQ(),
						sand, 0, depositSvq, depositMass)), Math.addExact(activeSvq, depositSvq),
				Double.doubleToRawLongBits(activeMass + depositMass));
		region.domain().restoreGranularState(granularFixture, granular, liquids, BuiltinFluidMaterials.createRegistry());
		ThermalRegistry thermal = thermalRegistry(liquids, granular);
		double insertionTemperatureK = Math.nextUp(300.0);
		region.domain().bindThermal(thermal, liquids, insertionTemperatureK);
		ThermalStateFunction thermalState = new ThermalStateFunction(thermal);
		FiniteThermalDeviceInventory expectedDevices = new FiniteThermalDeviceInventory(
				thermal.fingerprint(), List.of(new FiniteThermalDeviceState(
						"mmm:fabric_block", "mmm:persistence/4/5/6", "mmm:device/persistence",
						new FiniteThermalBodyState(7L, thermal.fingerprint(),
								"mmm:thermal/persistence_device", 2.0,
								thermalState.internalEnergyAtTemperature(
										thermal.materialHandle("mmm:thermal/persistence_device"),
										2.0, java.util.Map.of(), 350.0)))));
		expectedDevices.requireCompatible(thermal);
		region.restoreFiniteThermalDevices(expectedDevices, thermal, region.checkpointGeneration());
		region.domain().setThermalTemperatureInCell(2, 1, 2, 325.0);
		region.domain().setThermalTemperatureInCell(1, 1, 1, 340.0);
		region.domain().setThermalTemperatureInCell(5, 3, 5, 280.0);
		region.domain().addCoherentPhaseExternal(3, 2, 3, "mmm:thermal/persistence_solid",
				SolidVolume.SVQ_PER_CELL / 8L, 0.125, null, 6.25);
		SignedDyadic fluidBeforeDeviceExchange = region.domain().exactInternalEnergy();
		SignedDyadic deviceBeforeExchange = expectedDevices.exactInternalEnergy();
		SignedDyadic fluidRoundoffBefore = region.domain().thermalAudit().roundoffResidual();
		SignedDyadic deviceRoundoffBefore = expectedDevices.audit().roundoffResidual();
		List<FiniteThermalExchange.Contact> deviceContacts = region.domain().finiteThermalContacts(List.of(
				new FluidDomain.ThermalContactRequest(0, 2, 1, 2, 10.0),
				new FluidDomain.ThermalContactRequest(1, 1, 1, 1, 5.0)));
		FiniteThermalExchange.Plan deviceExchange = FiniteThermalExchange.plan(thermal,
				expectedDevices.devices().getFirst().body(), deviceContacts,
				1.0, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		region.lifecycle().setOperatorPaused(false, 1);
		require(region.commitFiniteThermalExchange("mmm:fabric_block", "mmm:persistence/4/5/6",
				deviceExchange, List.of(
						new FluidRegion.FiniteThermalCellBinding(0, 2, 1, 2),
						new FluidRegion.FiniteThermalCellBinding(1, 1, 1, 1)), thermal),
				"finite thermal device exchange was not committed");
		expectedDevices = region.finiteThermalDevices(thermal.fingerprint());
		SignedDyadic combinedDelta = region.domain().exactInternalEnergy().subtract(fluidBeforeDeviceExchange)
				.add(expectedDevices.exactInternalEnergy().subtract(deviceBeforeExchange));
		SignedDyadic combinedRoundoff = region.domain().thermalAudit().roundoffResidual()
				.subtract(fluidRoundoffBefore)
				.add(expectedDevices.audit().roundoffResidual().subtract(deviceRoundoffBefore));
		require(deviceExchange.after().generation() == 8L
				&& region.domain().exactInternalEnergy().equals(region.domain().thermalAudit().expectedInventory())
				&& expectedDevices.exactInternalEnergy().equals(expectedDevices.audit().expectedInventory())
				&& combinedDelta.equals(combinedRoundoff),
				"finite device/body/cell exchange did not close its combined audit");
		PendingPhaseVolumeState expectedPending = new PendingPhaseVolumeState(
				region.sizeX() * region.sizeY() * region.sizeZ(), List.of(
				new PendingPhaseVolumeState.Entry((1 * region.sizeY() + 1) * region.sizeX() + 1,
						SignedDyadic.of(Math.scalb(17.0, -24))),
				new PendingPhaseVolumeState.Entry((3 * region.sizeY() + 2) * region.sizeX() + 3,
						SignedDyadic.of(Math.scalb(-9.0, -24)))));
		region.domain().restorePendingPhaseVolumeState(expectedPending);
		FlipThermalParticleState expected = region.domain().exportThermalParticleState();
		require(Double.doubleToRawLongBits(expected.insertionTemperatureK())
				== Double.doubleToRawLongBits(insertionTemperatureK), "configured insertion temperature was not captured");
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		GasStateSnapshot expectedGas = region.domain().exportGasState("");
		GranularStateSnapshot expectedGranular = region.domain().exportGranularState();

		RegionState state = RegionCheckpointFixtures.captureThermal(region, 1L);
		state.validate();
		assertMaterialStateEquals(region.domain().exportMaterialParticleState(), restorer(state).reconstructMaterial());
		assertGranularStateEquals(expectedGranular, restorer(state).reconstructGranular(granular));
		assertGasStateEquals(expectedGas, restorer(state).reconstructGas(gas, null));
		assertThermalStateEquals(expected, restorer(state).reconstructThermal(thermal));
		require(restorer(state).reconstructFiniteThermalDevices(thermal).equals(expectedDevices),
				"finite thermal device truth did not round-trip");
		require(restorer(state).reconstructPendingPhaseVolume().entries().equals(expectedPending.entries()),
				"pending phase-volume record did not round-trip");
		FluidDomain restoredDomain = new FluidDomain(region.sizeX(), region.sizeY(), region.sizeZ(), CONFIG, 512);
		restoredDomain.sealDomainBoundary();
		restoredDomain.restoreMaterialParticleState(restorer(state).reconstructMaterial());
		restoredDomain.restoreGranularState(restorer(state).reconstructGranular(granular), granular,
				restoredDomain.materialRegistry(), BuiltinFluidMaterials.createRegistry());
		restoredDomain.restoreGasState(restorer(state).reconstructGas(gas, null), gas);
		restoredDomain.restoreThermalParticleState(restorer(state).reconstructThermal(thermal), thermal);
		restoredDomain.restorePendingPhaseVolumeState(restorer(state).reconstructPendingPhaseVolume());
		assertGranularStateEquals(expectedGranular, restoredDomain.exportGranularState());
		assertThermalStateEquals(expected, restoredDomain.exportThermalParticleState());
		require(restoredDomain.exportPendingPhaseVolumeState().entries().equals(expectedPending.entries()),
				"pending phase volume was not restored beside thermal inventory");

		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest decodedManifest = codec.decodeManifest(codec.encodeManifest(state.manifest()));
		require(decodedManifest.equals(state.manifest()) && decodedManifest.layout() == RegionStateLayout.THERMAL
				&& decodedManifest.hasThermalState(), "thermal region manifest did not round-trip");
		for (RegionSectionState section : state.sections()) {
			RegionSectionState decoded = codec.decodeSection(codec.encodeSection(section));
			require(decoded.layout() == RegionStateLayout.THERMAL && decoded.extensions().equals(section.extensions())
					&& Arrays.equals(decoded.markerOrdinals(), section.markerOrdinals()),
					"thermal region section did not round-trip");
		}
		CoherentPhaseStore.Composition composition = new CoherentPhaseStore.Composition(List.of(
				new CoherentPhaseStore.Component("test:a", 7L),
				new CoherentPhaseStore.Component("test:b", 11L)), Double.doubleToRawLongBits(0.25));
		ThermalRegionStateCodec thermalCodec = new ThermalRegionStateCodec();
		validateInsertionTemperaturePayload(thermalCodec, state);
		ThermalRegionStateCodec.SectionData composed = new ThermalRegionStateCodec.SectionData(
				new long[0], new long[0], new long[0], List.of(new ThermalRegionStateCodec.CoherentEntry(
				3, "test:solid", 8L, Double.doubleToRawLongBits(2.0), composition,
				Double.doubleToRawLongBits(5.0))), SignedDyadic.of(5.0));
		ThermalRegionStateCodec.SectionData decodedComposed = thermalCodec.decodeSection(
				thermalCodec.encodeSection(composed), 0, 0, 0);
		require(decodedComposed.coherentGroups().equals(composed.coherentGroups())
				&& decodedComposed.energy().equals(composed.energy()),
				"coherent composition did not round-trip the thermal section payload");

		Path root = Files.createTempDirectory(Path.of("."), "thermal-region-");
		RegionGenerationStore files = new RegionGenerationStore(root, null, granular, thermal, gas);
		files.publish(state);
		assertThermalStateEquals(expected, restorer(files.read(region.id())).reconstructThermal(thermal));
		require(restorer(files.read(region.id())).reconstructFiniteThermalDevices(thermal).equals(expectedDevices),
				"published finite thermal device truth did not survive committed readback");
		require(restorer(files.read(region.id())).reconstructPendingPhaseVolume().entries().equals(expectedPending.entries()),
				"published pending phase volume did not survive committed readback");
		expectIOException(() -> restorer(state).reconstructThermal(thermalRegistry(new FluidMaterialRegistry(
				List.of(BuiltinFluidMaterials.WATER)))), "foreign thermal registry was accepted");

		RegionSectionState original = state.sections().stream()
				.filter(section -> section.markerCount() > 0).findFirst().orElseThrow();
		CompoundTag missingThermal = codec.encodeSection(original);
		missingThermal.put("Extensions", new net.minecraft.nbt.ListTag());
		RegionSectionState unsignedMissing = codec.decodeSection(missingThermal);
		List<RegionSectionState> brokenSections = new ArrayList<>(state.sections());
		brokenSections.set(brokenSections.indexOf(original), signedSection(unsignedMissing));
		expectIOException(() -> new RegionState(state.manifest(), brokenSections).validate(),
				"section missing its required thermal payload was accepted");

		List<RegionExtension> withoutBuoyancy = state.manifest().extensions().stream()
				.filter(extension -> !ThermalBuoyancyCodec.MANIFEST_EXTENSION.equals(extension.type())).toList();
		expectIOException(() -> new RegionState(manifestWithExtensions(state.manifest(), withoutBuoyancy),
				state.sections()).validate(), "thermal region without buoyancy identity was accepted");

		validateExplicitVacuumThermalCheckpoint();
	}

	private static void validateInsertionTemperaturePayload(ThermalRegionStateCodec codec, RegionState state) throws Exception {
		byte[] payload = state.manifest().extensions().stream()
				.filter(extension -> ThermalRegionStateCodec.MANIFEST_EXTENSION.equals(extension.type()))
				.findFirst().orElseThrow().payload();
		int temperatureOffset;
		try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload))) {
			input.readInt(); input.readInt(); input.readInt(); input.readUTF();
			temperatureOffset = payload.length - input.available();
		}
		for (double temperature : new double[]{Double.MIN_NORMAL, Math.nextUp(300.0), Double.MAX_VALUE}) {
			byte[] candidate = payload.clone();
			java.nio.ByteBuffer.wrap(candidate).putLong(temperatureOffset, Double.doubleToRawLongBits(temperature));
			var decoded = codec.decodeManifest(candidate);
			require(Double.doubleToRawLongBits(decoded.insertionTemperatureK()) == Double.doubleToRawLongBits(temperature)
					&& Arrays.equals(candidate, codec.encodeManifest(decoded)), "thermal insertion temperature raw bits changed");
		}
		for (double invalid : new double[]{-0.0, 0.0, Double.MIN_VALUE, Double.NaN,
				Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
			byte[] candidate = payload.clone();
			java.nio.ByteBuffer.wrap(candidate).putLong(temperatureOffset, Double.doubleToRawLongBits(invalid));
			expectIOException(() -> codec.decodeManifest(candidate), "invalid insertion temperature was accepted");
		}
		byte[] previousVersion = payload.clone();
		java.nio.ByteBuffer.wrap(previousVersion).putInt(Integer.BYTES, 2);
		expectIOException(() -> codec.decodeManifest(previousVersion), "previous thermal payload schema was accepted");
	}

	private static void validateGasLineageCodec() throws Exception {
		var network = new cc.ecisr.mmm.fluid.air.AirNetwork();
		network.addNode(1.0, 1.0);
		network.addNode(2.0, 2.0);
		GasStateSnapshot snapshot = GasStateSnapshot.capture(3, 2, 1,
				new int[]{2, 0, 0, 1, 0, 0}, new int[]{2, 2, -1, 1, 1, 0}, network, "");
		require(snapshot.nodes().get(0).inventory().equals(network.inventory(2))
				&& snapshot.nodes().get(1).inventory().equals(network.inventory(1))
				&& Arrays.equals(snapshot.cellLineageOrdinal(), new int[]{1, 1, -1, 2, 2, 0}),
				"canonical gas ordering separated partial-cell lineage from its inventory");
		GasRegionStateCodec codec = new GasRegionStateCodec();
		byte[] payload = codec.encodeSection(new GasRegionStateCodec.SectionData(
				snapshot.cellNodeOrdinal(), snapshot.cellLineageOrdinal()));
		var restored = codec.decodeSection(payload, 6, 2);
		require(Arrays.equals(restored.cellNodeOrdinal(), snapshot.cellNodeOrdinal())
				&& Arrays.equals(restored.cellLineageOrdinal(), snapshot.cellLineageOrdinal()),
				"gas section lost interface lineage or unowned/ambient distinction");
		expectIOException(() -> codec.decodeSection(Arrays.copyOf(payload, payload.length - 4), 6, 2),
				"gas section accepted missing lineage");
		byte[] wrongOwner = payload.clone();
		java.nio.ByteBuffer.wrap(wrongOwner).putInt(8 + 6 * Integer.BYTES, 2);
		expectIOException(() -> codec.decodeSection(wrongOwner, 6, 2),
				"gas section accepted membership/lineage owner mismatch");
	}

	/** A sealed, inventory-free cavity is durable vacuum, not a malformed gas thermal node. */
	private static void validateExplicitVacuumThermalCheckpoint() throws Exception {
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		FluidRegion vacuumRegion = emptyRegion(new UUID(271L, 272L));
		vacuumRegion.domain().sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(vacuumRegion.domain(), registries.liquids());
		for (int z = 0; z < vacuumRegion.sizeZ(); z++) for (int y = 0; y < vacuumRegion.sizeY(); y++) {
			for (int x = 0; x < vacuumRegion.sizeX(); x++) vacuumRegion.domain().setSolid(x, y, z, true);
		}
		FullCapabilityTestFixture.bindFull(vacuumRegion.domain(), registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		vacuumRegion.domain().setSolidMicroMask(1, 1, 1, 0xAA);
		vacuumRegion.domain().synchronizeGasTopologyForPersistence();
		require(vacuumRegion.domain().gasBoundary().nodeCount() == 1
				&& vacuumRegion.domain().gasBoundary().inventory(1).isEmpty()
				&& vacuumRegion.domain().exactGasInternalEnergy().equals(SignedDyadic.ZERO),
				"vacuum checkpoint fixture did not contain one zero-U cavity");

		RegionState vacuumState = RegionCheckpointFixtures.captureThermal(vacuumRegion, 1L);
		Path root = Files.createTempDirectory(Path.of("."), "vacuum-thermal-region-");
		RegionGenerationStore store = new RegionGenerationStore(root, registries.chemical(), registries.granular(),
				registries.thermal(), registries.gas());
		store.publish(vacuumState);
		RegionCheckpointRestorer restored = restorer(store.read(vacuumRegion.id()));
		FlipThermalParticleState thermal = restored.reconstructThermal(
				registries.thermal(), registries.gas(), registries.chemical());
		require(thermal.copyGasNodeEnergy().length == 1 && thermal.copyGasNodeEnergy()[0] == 0.0,
				"durable vacuum checkpoint changed its zero-U gas node");
	}

	static void validateGranularCodec() throws Exception {
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		FluidRegion region = populatedRegion(new UUID(23L, 24L));
		FlipParticleState pure = region.domain().exportParticleState();
		float[] positions = pure.copyPositionVolume();
		double[] liquidMass = new double[pure.particleCount()];
		double totalLiquidMass = 0.0;
		for (int marker = 0; marker < liquidMass.length; marker++) {
			liquidMass[marker] = positions[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			totalLiquidMass += liquidMass[marker];
		}
		region.domain().restoreMaterialParticleState(new FlipMaterialParticleState(pure.sizeX(), pure.sizeY(), pure.sizeZ(),
				pure.cellSize(), pure.particleCount(), pure.totalVolume(), totalLiquidMass, BuiltinFluidMaterials.DEFAULT_PALETTE,
				positions, pure.copyParticleVelocities(), pure.copySolidMask(), liquidMass, new int[pure.particleCount()]));
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long activeSvq = GranularLimits.MAX_PARCEL_VOLUME_SVQ, depositSvq = activeSvq - 17L;
		double activeMass = 3.25, depositMass = 4.75;
		int depositCell = (5 * region.sizeY() + 3) * region.sizeX() + 5;
		GranularStateSnapshot fixture = new GranularStateSnapshot(region.sizeX(), region.sizeY(), region.sizeZ(),
				granular.fingerprint(), granular.materials().stream().map(cc.ecisr.mmm.granular.ParticulateMaterial::id).toList(),
				List.of(new GranularStateSnapshot.ActiveParcel(0.75F, 0.75F, 0.75F,
						0.125F, -0.25F, 0.0625F, activeSvq, activeMass, sand, 0,
						2, cc.ecisr.mmm.granular.GranularParcelStore.FLAG_SUPPORTED)),
				List.of(new GranularStateSnapshot.DepositGroup(depositCell, granular.material(sand).restPorosityQ(),
						sand, 0, depositSvq, depositMass)), Math.addExact(activeSvq, depositSvq),
				Double.doubleToRawLongBits(activeMass + depositMass));
		region.domain().restoreGranularState(fixture, granular, liquids, BuiltinFluidMaterials.createRegistry());
		GranularStateSnapshot expected = region.domain().exportGranularState();
		RegionState state = RegionCheckpointFixtures.captureGranular(region, 1L);
		state.validate();
		assertMaterialStateEquals(region.domain().exportMaterialParticleState(), restorer(state).reconstructMaterial());
		assertGranularStateEquals(expected, restorer(state).reconstructGranular(granular));

		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest decodedManifest = codec.decodeManifest(codec.encodeManifest(state.manifest()));
		require(decodedManifest.equals(state.manifest()) && decodedManifest.layout() == RegionStateLayout.GRANULAR
				&& !decodedManifest.hasChemicalState(), "granular region manifest did not round-trip");
		for (RegionSectionState section : state.sections()) {
			RegionSectionState decoded = codec.decodeSection(codec.encodeSection(section));
			require(decoded.layout() == RegionStateLayout.GRANULAR && decoded.key().equals(section.key())
					&& decoded.extensions().equals(section.extensions())
					&& Arrays.equals(decoded.markerOrdinals(), section.markerOrdinals())
					&& Arrays.equals(decoded.solidMask(), section.solidMask())
					&& decoded.checksum() == section.checksum(),
					"granular region section did not round-trip");
		}
		GranularRegionStateCodec granularCodec = new GranularRegionStateCodec();
		byte[] unsupportedManifestPayload = state.manifest().extensions().stream()
				.filter(extension -> extension.type().equals(GranularRegionStateCodec.MANIFEST_EXTENSION))
				.findFirst().orElseThrow().payload();
		java.nio.ByteBuffer.wrap(unsupportedManifestPayload).putInt(Integer.BYTES, 1);
		expectIOException(() -> granularCodec.decodeManifest(unsupportedManifestPayload),
				"non-current granular manifest payload was accepted");
		byte[] unsupportedSectionPayload = state.sections().stream().flatMap(section -> section.extensions().stream())
				.filter(extension -> extension.type().equals(GranularRegionStateCodec.SECTION_EXTENSION))
				.findFirst().orElseThrow().payload();
		java.nio.ByteBuffer.wrap(unsupportedSectionPayload).putInt(Integer.BYTES, 1);
		expectIOException(() -> granularCodec.decodeSection(unsupportedSectionPayload,
				GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION),
				"non-current granular section payload was accepted");

		Path root = Files.createTempDirectory(Path.of("."), "granular-region-");
		RegionGenerationStore files = new RegionGenerationStore(root, null, granular);
		files.publish(state);
		RegionState restored = files.read(region.id());
		assertGranularStateEquals(expected, restorer(restored).reconstructGranular(granular));

		FluidRegion reactiveRegion = populatedRegion(new UUID(25L, 26L));
		FlipParticleState reactivePure = reactiveRegion.domain().exportParticleState();
		float[] reactivePositions = reactivePure.copyPositionVolume();
		double[] reactiveLiquidMass = new double[reactivePure.particleCount()]; double reactiveTotalMass = 0.0;
		for (int marker = 0; marker < reactiveLiquidMass.length; marker++) {
			reactiveLiquidMass[marker] = reactivePositions[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			reactiveTotalMass += reactiveLiquidMass[marker];
		}
		reactiveRegion.domain().restoreMaterialParticleState(new FlipMaterialParticleState(reactivePure.sizeX(),
				reactivePure.sizeY(), reactivePure.sizeZ(), reactivePure.cellSize(), reactivePure.particleCount(),
				reactivePure.totalVolume(), reactiveTotalMass, BuiltinFluidMaterials.DEFAULT_PALETTE, reactivePositions,
				reactivePure.copyParticleVelocities(), reactivePure.copySolidMask(), reactiveLiquidMass,
				new int[reactivePure.particleCount()]));
		reactiveRegion.domain().bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		SolidCompositionRecord composition = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(BuiltinChemicalDefinitions.NA_PLUS, 640L,
						BuiltinChemicalDefinitions.CL_MINUS, 640L), 0.0);
		GranularStateSnapshot reactiveFixture = new GranularStateSnapshot(reactiveRegion.sizeX(), reactiveRegion.sizeY(),
				reactiveRegion.sizeZ(), granular.fingerprint(), BuiltinChemicalDefinitions.REGISTRY.fingerprint(),
				granular.materials().stream().map(cc.ecisr.mmm.granular.ParticulateMaterial::id).toList(),
				List.of(new GranularStateSnapshot.StableComposition(List.of(
						new GranularStateSnapshot.CompositionComponent(BuiltinChemicalDefinitions.CL_MINUS, 640L),
						new GranularStateSnapshot.CompositionComponent(BuiltinChemicalDefinitions.NA_PLUS, 640L)), 0L)),
				java.util.Map.of(BuiltinChemicalDefinitions.CL_MINUS, 640L, BuiltinChemicalDefinitions.NA_PLUS, 640L),
				List.of(new GranularStateSnapshot.ActiveParcel(0.75F, 0.75F, 0.75F, 0.0F, 0.0F, 0.0F,
						10L, composition.totalMass(BuiltinChemicalDefinitions.REGISTRY),
						granular.materialHandle(BuiltinGranularDefinitions.PRECIPITATE), 1, 0, (byte) 0)), List.of(), 10L,
				Double.doubleToRawLongBits(composition.totalMass(BuiltinChemicalDefinitions.REGISTRY)));
		reactiveRegion.domain().restoreGranularState(reactiveFixture, granular, liquids, BuiltinFluidMaterials.createRegistry());
		var removedReactive = reactiveRegion.domain().tryExtractExactGranular(1, 1, 1, 10L, 0, true);
		require(removedReactive.transferred()
				&& reactiveRegion.domain().tryInsertExactGranular(1, 1, 1, removedReactive.ledger(), 0, true).inserted(),
				"reactive granular audit fixture could not cross the region boundary");
		GranularStateSnapshot reactiveExpected = reactiveRegion.domain().exportGranularState();
		RegionState reactiveState = RegionCheckpointFixtures.captureGranular(reactiveRegion, 1L);
		reactiveState.validate();
		GranularStateSnapshot reactiveRoundTrip = restorer(reactiveState).reconstructGranular(granular);
		reactiveRoundTrip.validateSolidCompositionMasses(BuiltinChemicalDefinitions.REGISTRY);
		assertGranularStateEquals(reactiveExpected, reactiveRoundTrip);
		Path reactiveRoot = Files.createTempDirectory(Path.of("."), "reactive-granular-region-");
		RegionGenerationStore reactiveFiles = new RegionGenerationStore(
				reactiveRoot, BuiltinChemicalDefinitions.REGISTRY, granular);
		reactiveFiles.publish(reactiveState);
		reactiveRegion.domain().removeAllGranularExact();
		GranularStateSnapshot auditOnlyExpected = reactiveRegion.domain().exportGranularState();
		require(auditOnlyExpected.solidCompositions().isEmpty() && auditOnlyExpected.requiresChemistry(),
				"reactive clear fixture did not retain audit-only chemistry identity");
		RegionState auditOnlyState = RegionCheckpointFixtures.captureGranular(reactiveRegion, 2L);
		auditOnlyState.validate();
		GranularStateSnapshot auditOnlyRoundTrip = restorer(auditOnlyState).reconstructGranular(granular);
		auditOnlyRoundTrip.validateAgainst(granular, BuiltinChemicalDefinitions.REGISTRY);
		assertGranularStateEquals(auditOnlyExpected, auditOnlyRoundTrip);
		reactiveFiles.publish(auditOnlyState);

		CompoundTag unknownLayout = codec.encodeManifest(state.manifest());
		unknownLayout.putString("Layout", "unknown");
		expectIOException(() -> codec.decodeManifest(unknownLayout), "unknown region-state layout was accepted");
		CompoundTag missingGranular = codec.encodeSection(state.sections().getFirst());
		missingGranular.put("Extensions", new net.minecraft.nbt.ListTag());
		RegionSectionState unsignedMissing = codec.decodeSection(missingGranular);
		RegionSectionState signedMissing = signedSection(unsignedMissing);
		List<RegionSectionState> brokenSections = new ArrayList<>(state.sections());
		brokenSections.set(0, signedMissing);
		expectIOException(() -> new RegionState(state.manifest(), brokenSections).validate(),
				"section missing its required granular payload was accepted");
	}

	static void validateChemicalCodec() throws Exception {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		FluidRegion region = populatedRegion(new UUID(19L, 20L));
		FlipParticleState pure = region.domain().exportParticleState();
		double[] mass = new double[pure.particleCount()];
		int[] material = new int[pure.particleCount()];
		double totalMass = 0.0;
		float[] positionVolume = pure.copyPositionVolume();
		for (int marker = 0; marker < pure.particleCount(); marker++) {
			mass[marker] = positionVolume[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			totalMass += mass[marker];
		}
		region.domain().restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(), pure.totalVolume(),
				totalMass, BuiltinFluidMaterials.DEFAULT_PALETTE, positionVolume, pure.copyParticleVelocities(),
				pure.copySolidMask(), mass, material));
		region.domain().bindChemistry(chemistry);
		region.domain().setMarkerChemical(0, java.util.Map.of(
				BuiltinChemicalDefinitions.H2O, 123_457L,
				BuiltinChemicalDefinitions.NACL, 9_999L), true);
		var auditFixture = region.domain().chemicalLedgerForMarkerRange(0, 1);
		region.domain().chemicalParcels().recordExternalSource(auditFixture);
		region.domain().chemicalParcels().recordEscapedSink(auditFixture);
		FlipChemicalParticleState expected = region.domain().exportChemicalParticleState();
		RegionState state = RegionCheckpointFixtures.captureChemical(region, 1L);
		state.validate();
		expectIOException(restorer(state)::reconstruct, "chemical state was silently reconstructed as pure water");
		expectIOException(restorer(state)::reconstructMaterial, "chemical state was silently reconstructed as material-only state");
		assertChemicalStateEquals(expected, restorer(state).reconstructChemical(chemistry));

		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest manifest = codec.decodeManifest(codec.encodeManifest(state.manifest()));
		require(manifest.equals(state.manifest())
				&& Arrays.equals(manifest.chemicalSpeciesTotals(), state.manifest().chemicalSpeciesTotals()),
				"chemical region manifest did not round-trip");
		for (RegionSectionState section : state.sections()) {
			RegionSectionState decoded = codec.decodeSection(codec.encodeSection(section));
			require(decoded.layout() == RegionStateLayout.CHEMICAL
					&& Arrays.equals(decoded.chemicalMixtureIndex(), section.chemicalMixtureIndex())
					&& Arrays.equals(decoded.indicator(), section.indicator())
					&& Arrays.equals(decoded.chemicalSpeciesTotals(), section.chemicalSpeciesTotals())
					&& decoded.chemicalResidualLedgerBits() == section.chemicalResidualLedgerBits(),
					"chemical region section did not round-trip");
		}

		Path root = Files.createTempDirectory(Path.of("."), "chemical-region-");
		RegionGenerationStore files = new RegionGenerationStore(root, chemistry);
		files.publish(state);
		assertChemicalStateEquals(expected, restorer(files.read(region.id())).reconstructChemical(chemistry));

		FluidRegion emptyRegion = emptyRegion(new UUID(21L, 22L));
		FlipParticleState emptyPure = emptyRegion.domain().exportParticleState();
		emptyRegion.domain().restoreMaterialParticleState(new FlipMaterialParticleState(
				emptyPure.sizeX(), emptyPure.sizeY(), emptyPure.sizeZ(), emptyPure.cellSize(), 0, 0.0, 0.0,
				BuiltinFluidMaterials.DEFAULT_PALETTE, new float[0], new float[0], emptyPure.copySolidMask(),
				new double[0], new int[0]));
		emptyRegion.domain().bindChemistry(chemistry);
		FlipChemicalParticleState emptyExpected = emptyRegion.domain().exportChemicalParticleState();
		require(emptyExpected.particleCount() == 0 && emptyExpected.mixtures().isEmpty(),
				"empty chemistry handoff invented a parcel mixture");
		RegionState emptyState = RegionCheckpointFixtures.captureChemical(emptyRegion, 1L);
		emptyState.validate();
		RegionManifest emptyManifest = codec.decodeManifest(codec.encodeManifest(emptyState.manifest()));
		require(emptyManifest.chemicalMixtures().isEmpty(), "empty chemical manifest invented a mixture");
		assertChemicalStateEquals(emptyExpected, restorer(emptyState).reconstructChemical(chemistry));
		files.publish(emptyState);
		assertChemicalStateEquals(emptyExpected, restorer(files.read(emptyRegion.id())).reconstructChemical(chemistry));

		CompoundTag missingIndicator = codec.encodeSection(state.sections().stream()
				.filter(section -> section.markerCount() > 0).findFirst().orElseThrow());
		missingIndicator.remove("Indicator");
		expectIOException(() -> codec.decodeSection(missingIndicator), "chemical section missing indicator bits was accepted");
		CompoundTag foreignFingerprint = codec.encodeManifest(state.manifest());
		foreignFingerprint.putString("RegistryFingerprint", "0".repeat(64));
		RegionManifest foreign = signedManifest(codec.decodeManifest(foreignFingerprint));
		expectIOException(() -> restorer(new RegionState(foreign, state.sections())).reconstructChemical(chemistry),
				"foreign chemical registry was accepted without authoritative reconstruction");

		Path regionDir = root.toAbsolutePath().normalize().resolve("mmm").resolve("region-state")
				.resolve(region.id().toString());
		CompoundTag head = NbtIo.readCompressed(regionDir.resolve("HEAD.nbt"), NbtAccounter.unlimitedHeap());
		Path activeSlot = regionDir.resolve(head.getStringOr("Slot", ""));
		Path section = Files.list(activeSlot)
				.filter(path -> path.getFileName().toString().startsWith("section_"))
				.findFirst().orElseThrow();
		Files.delete(section);
		RegionGenerationStore.Discovery discovery = files.discover();
		require(discovery.manifests().stream().noneMatch(value -> value.regionId().equals(region.id()))
				&& discovery.issues().containsKey(regionDir),
				"discovery admitted a chemical manifest whose committed section was missing");
	}

	static void validateMaterialCodec() throws Exception {
		FluidRegion region = populatedRegion(new UUID(15L, 16L));
		List<FluidMaterial> palette = List.of(
				new FluidMaterial("mmm:water", 1_000.0, "mmm:water", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:oil", 800.0, "mmm:oil", 0.0, Math.PI / 2.0)
		);
		FlipParticleState pureParticles = region.domain().exportParticleState();
		float[] positions = pureParticles.copyPositionVolume();
		double[] markerMass = new double[pureParticles.particleCount()];
		int[] materialIndices = new int[pureParticles.particleCount()];
		double totalMass = 0.0;
		for (int ordinal = 0; ordinal < pureParticles.particleCount(); ordinal++) {
			int material = ordinal & 1;
			materialIndices[ordinal] = material;
			markerMass[ordinal] = positions[ordinal * 4 + 3] * palette.get(material).referenceDensity();
			totalMass += markerMass[ordinal];
		}
		FlipMaterialParticleState expected = new FlipMaterialParticleState(pureParticles.sizeX(), pureParticles.sizeY(), pureParticles.sizeZ(),
				pureParticles.cellSize(), pureParticles.particleCount(), sumVolumes(positions), totalMass, palette, positions,
				pureParticles.copyParticleVelocities(), pureParticles.copySolidMask(), markerMass, materialIndices);
		region.domain().restoreMaterialParticleState(expected);
		RegionState state = RegionCheckpointFixtures.captureMaterial(region, 1L);
		List<Long> sectionChecksums = state.sections().stream().map(RegionSectionState::checksum).toList();
		// Manifest config includes the explicit viscous wall condition; marker sections are unchanged.
		require(state.manifest().checksum() == 2_824_632_206L
				&& sectionChecksums.equals(List.of(
				1_197_279_484L, 3_443_687_331L, 3_791_385_648L, 3_117_281_787L)),
				"material region canonical bytes changed: manifest=" + state.manifest().checksum()
						+ " sections=" + sectionChecksums);
		state.validate();
		expectIOException(restorer(state)::reconstruct, "material state was silently reconstructed as pure water");
		assertMaterialStateEquals(expected, restorer(state).reconstructMaterial());

		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest decodedManifest = codec.decodeManifest(codec.encodeManifest(state.manifest()));
		require(decodedManifest.equals(state.manifest()), "material region manifest did not round-trip");
		for (RegionSectionState section : state.sections()) {
			RegionSectionState decoded = codec.decodeSection(codec.encodeSection(section));
			require(decoded.layout() == RegionStateLayout.MATERIAL && Arrays.equals(decoded.markerMass(), section.markerMass())
					&& Arrays.equals(decoded.materialPaletteIndex(), section.materialPaletteIndex())
					&& decoded.checksum() == section.checksum(), "material region section did not round-trip");
		}
		CompoundTag missingMass = codec.encodeSection(state.sections().getFirst());
		missingMass.remove("MassBits");
		expectIOException(() -> codec.decodeSection(missingMass), "material section missing marker mass was accepted");
		int changedIndex = 0;
		while (state.sections().get(changedIndex).markerCount() == 0) changedIndex++;
		RegionSectionState originalSection = state.sections().get(changedIndex);
		double[] changedMass = originalSection.markerMass();
		changedMass[0] += 1.0;
		RegionSectionState changedSection = signedSection(new RegionSectionState(RegionStateLayout.MATERIAL, originalSection.regionId(),
				originalSection.dimensionId(), originalSection.checkpointGeneration(), originalSection.sectionX(), originalSection.sectionY(),
				originalSection.sectionZ(), originalSection.localStartX(), originalSection.localStartY(), originalSection.localStartZ(),
				originalSection.sizeX(), originalSection.sizeY(), originalSection.sizeZ(), originalSection.markerOrdinals(),
				originalSection.positionVolume(), originalSection.velocities(), changedMass, originalSection.materialPaletteIndex(),
				originalSection.solidMask(), originalSection.extensions(), 0L));
		List<RegionSectionState> changedSections = new ArrayList<>(state.sections());
		changedSections.set(changedIndex, changedSection);
		List<RegionManifest.SectionDescriptor> changedDescriptors = new ArrayList<>(state.manifest().sections());
		for (int descriptor = 0; descriptor < changedDescriptors.size(); descriptor++) {
			RegionManifest.SectionDescriptor current = changedDescriptors.get(descriptor);
			if (current.key().equals(changedSection.key())) {
				changedDescriptors.set(descriptor, new RegionManifest.SectionDescriptor(current.sectionX(), current.sectionY(), current.sectionZ(),
						current.markerCount(), current.volumeBits(), Double.doubleToRawLongBits(sum(changedMass)), changedSection.checksum()));
			}
		}
		RegionManifest base = state.manifest();
		RegionManifest changedManifest = signedManifest(new RegionManifest(RegionStateLayout.MATERIAL, base.regionId(), base.dimensionId(), base.checkpointGeneration(),
				base.networkGeneration(), base.origin(), base.sizeX(), base.sizeY(), base.sizeZ(), base.solverConfig(),
				base.reservedMarkerCapacity(), base.runtime(), base.footprint(), base.totalMarkerCount(), base.totalVolumeBits(),
				base.materialPalette(), base.totalMassBits(), base.materialLedgers(), changedDescriptors, base.extensions(), 0L));
		expectIOException(() -> new RegionState(changedManifest, changedSections).validate(),
				"rechecksummed marker-mass corruption escaped exact material ledgers");
		RegionState pureState = RegionCheckpointFixtures.capture(populatedRegion(new UUID(17L, 18L)), 1L);
		RegionManifest pureManifest = pureState.manifest();
		List<Long> pureSectionChecksums = pureState.sections().stream().map(RegionSectionState::checksum).toList();
		require(pureManifest.checksum() == 1_511_261_536L
				&& pureSectionChecksums.equals(List.of(
				857_091_072L, 3_709_730_368L, 2_608_476_652L, 3_235_751_298L)),
				"pure region canonical bytes changed: manifest=" + pureManifest.checksum()
						+ " sections=" + pureSectionChecksums);
		CompoundTag encodedPure = codec.encodeManifest(pureManifest);
		require(encodedPure.get("Materials") == null && encodedPure.get("MassBits") == null,
				"pure manifest acquired material fields");

		Path root = Files.createTempDirectory(Path.of("."), "material-region-");
		RegionGenerationStore files = new RegionGenerationStore(root);
		files.publish(state);
		FlipMaterialParticleState restored = restorer(files.read(region.id())).reconstructMaterial();
		assertMaterialStateEquals(expected, restored);
		require(region.recordPublishedCheckpoint(1L) && region.releaseSolverAfterCheckpoint(1L),
				"material controller fixture did not release its published solver");
		region.restoreMaterialSolver(restored, 1L);
		require(region.domain().hasMaterialState(), "material controller restore lost its bound registry");
		assertMaterialStateEquals(expected, region.domain().exportMaterialParticleState());
	}

}
