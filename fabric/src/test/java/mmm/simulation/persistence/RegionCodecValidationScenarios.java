package mmm.simulation.persistence;

import mmm.simulation.TestHostAccess;
import mmm.fluid.FluidDomainContracts;

import mmm.simulation.RegionCheckpointFixtures;

import mmm.model.region.RegionExtension;
import mmm.model.region.RegionManifest;
import mmm.model.region.RegionSectionState;
import mmm.model.region.RegionStateLayout;

import mmm.fluid.HostFluidState;
import mmm.fluid.FluidDomainStateAssertions;
import mmm.fluid.FlipChemicalParticleState;
import mmm.fluid.FlipMaterialParticleState;
import mmm.fluid.FlipParticleState;
import mmm.fluid.FlipThermalParticleState;
import mmm.fluid.PendingPhaseMotionState;
import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.FluidMaterial;
import mmm.fluid.FluidMaterialRegistry;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.fluid.air.GasRegistry;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularLimits;
import mmm.granular.GranularRegistry;
import mmm.granular.GranularStateSnapshot;
import mmm.granular.SolidCompositionRecord;
import mmm.granular.SolidVolume;
import mmm.thermal.CoherentPhaseStore;
import mmm.thermal.FiniteThermalBodyState;
import mmm.thermal.FiniteThermalDeviceInventory;
import mmm.thermal.FiniteThermalDeviceState;
import mmm.thermal.FiniteThermalExchange;
import mmm.units.SignedDyadic;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalStateFunction;
import mmm.thermal.ThermalTransport;
import mmm.simulation.FluidRegion;
import mmm.simulation.FullCapabilityTestFixture;
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
	static void validateFarmlandJournal() throws Exception {
		var root=Files.createTempDirectory(Path.of("."),"farmland-wal-");
		var store=new FarmlandJournalStore(root);
		var water=new mmm.chemistry.ChemicalTransferLedger.Group("mmm:water",0.016,
				mmm.units.Mass.fromKilograms(2),-3,java.util.Map.of("mmm:co2",17L));
		var changes=List.of(new FarmlandJournalStore.Change(new net.minecraft.core.BlockPos(-3,64,5).asLong(),1,3,
				2_000_000,List.of(water),true,Math.nextUp(4123.0)),
				new FarmlandJournalStore.Change(new net.minecraft.core.BlockPos(-2,64,5).asLong(),6,7,
						1_000_000,List.of(new mmm.chemistry.ChemicalTransferLedger.Group("mmm:water",0.008,
						mmm.units.Mass.fromKilograms(1),0,java.util.Map.of())),false,0));
		var prepared=new FarmlandJournalStore.Journal(new UUID(503,509),new UUID(521,523),"minecraft:overworld",
				"c".repeat(64),8,9,-1,mmm.recovery.CrossDomainRecovery.JournalPhase.PREPARED,changes);
		store.write(prepared);
		var restarted=new FarmlandJournalStore(root);
		require(restarted.discover().equals(List.of(prepared)),"Farmland PREPARED changed actual water, species, raw U or plot identity");
		var base=farmlandDecision(prepared,8,0,List.of(mmm.recovery.CrossDomainRecovery.ExternalTruth.BEFORE,
				mmm.recovery.CrossDomainRecovery.ExternalTruth.BEFORE));
		require(base.outcome()==mmm.recovery.CrossDomainRecovery.Outcome.DISCARD_UNCOMMITTED,"Farmland changed world moisture before HEAD");
		var committed=prepared.committed(0x7139); restarted.write(committed);
		var decoded=new FarmlandJournalStore(root).discover().getFirst();
		require(decoded.equals(committed),"Farmland committed batch lost complete inventory or checksum");
		for(int mask=0;mask<4;mask++) {
			var truths=new ArrayList<mmm.recovery.CrossDomainRecovery.ExternalTruth>();
			for(int member=0;member<2;member++) truths.add((mask&(1<<member))==0?
					mmm.recovery.CrossDomainRecovery.ExternalTruth.BEFORE:mmm.recovery.CrossDomainRecovery.ExternalTruth.AFTER);
			var decision=farmlandDecision(decoded,9,0x7139,truths);
			require(decision.outcome()==mmm.recovery.CrossDomainRecovery.Outcome.CONVERGE_AFTER
					&& decision.beforeMembers().size()==2-Integer.bitCount(mask),"Farmland did not resume only missing batch members");
			for(int member:decision.beforeMembers()) truths.set(member,mmm.recovery.CrossDomainRecovery.ExternalTruth.AFTER);
			require(farmlandDecision(decoded,9,0x7139,truths).beforeMembers().isEmpty(),"Farmland replay applied a soil dose twice");
		}
		require(farmlandDecision(decoded,9,0x7139,List.of(mmm.recovery.CrossDomainRecovery.ExternalTruth.THIRD,
				mmm.recovery.CrossDomainRecovery.ExternalTruth.AFTER)).outcome()==mmm.recovery.CrossDomainRecovery.Outcome.LOCKED,
				"Farmland replaced a foreign block state");
		require(farmlandDecision(decoded,9,0x7139,List.of(mmm.recovery.CrossDomainRecovery.ExternalTruth.UNAVAILABLE,
				mmm.recovery.CrossDomainRecovery.ExternalTruth.AFTER)).outcome()==mmm.recovery.CrossDomainRecovery.Outcome.WAIT_UNAVAILABLE,
				"Farmland discarded an unloaded soil slot");
		require(farmlandDecision(decoded,9,0x7140,List.of(mmm.recovery.CrossDomainRecovery.ExternalTruth.AFTER,
				mmm.recovery.CrossDomainRecovery.ExternalTruth.AFTER)).reason()==mmm.recovery.CrossDomainRecovery.Reason.TARGET_CHECKSUM_MISMATCH,
				"Farmland accepted another operation's HEAD");
		require(decoded.protects("minecraft:overworld",changes.getFirst().position())
				&& !decoded.protects("minecraft:the_nether",changes.getFirst().position())
				&& !decoded.protects("minecraft:overworld",new net.minecraft.core.BlockPos(9,64,9).asLong()),
				"Restarted pending farmland guard did not retain exact dimension/plot identity");
		try {
			restarted.write(new FarmlandJournalStore.Journal(decoded.operationId(),new UUID(541,547),decoded.dimensionId(),decoded.profile(),
					8,9,0x7139,decoded.phase(),decoded.changes()));
			throw new AssertionError("Farmland WAL accepted operation identity replacement");
		} catch(java.io.IOException expected) { }
		require(restarted.discover().equals(List.of(decoded)),"Rejected farmland replacement changed recovery evidence");
		restarted.delete(decoded.operationId()); require(restarted.discover().isEmpty(),"Completed farmland WAL remained pending");
	}
	private static mmm.recovery.CrossDomainRecovery.Decision farmlandDecision(FarmlandJournalStore.Journal journal,
			long head,long checksum,List<mmm.recovery.CrossDomainRecovery.ExternalTruth> truths) {
		return mmm.recovery.CrossDomainRecovery.decide(new mmm.recovery.CrossDomainRecovery.Boundary(head,checksum,
				journal.baseGeneration(),journal.targetGeneration(),journal.targetChecksum()>=0,journal.targetChecksum(),journal.phase(),false),truths);
	}

	static void validateCellMacCodec() throws Exception {
		var template=populatedRegion(new UUID(31L,32L)).hostSnapshot().exportParticleState();
		var source=new HostFluidState(template.sizeX(),template.sizeY(),template.sizeZ(),CONFIG);
		source.sealDomainBoundary();
		source.restoreParticleState(template);
		// Keep the cell inventory unchanged while giving its liquid a spatially distinct saved layout.
		var occupancy=source.exportPhaseOccupancyState().copyLiquidMicroVolumes();
		int cell=1+source.sizeX()*(1+source.sizeY());
		Arrays.fill(occupancy,8*cell,8*cell+8,0);
		occupancy[8*cell]=occupancy[8*cell+7]=.125;
		source.restorePhaseOccupancyState(new mmm.inventory.PhaseOccupancyState(occupancy));
		var region=fixtureRegion(new UUID(31L,32L),source);
		var before=source.captureAuthoritativeState();
		var state=RegionCheckpointFixtures.capture(region,1L);
		var codec=new RegionStateCodec();
		var sections=new ArrayList<RegionSectionState>();
		for(var section:state.sections()) sections.add(codec.decodeSection(codec.encodeSection(section)));
		var decoded=new RegionState(codec.decodeManifest(codec.encodeManifest(state.manifest())),sections);
		decoded.validate();
		var restored=HostFluidState.restoreAuthoritativeState(restorer(decoded).reconstructAuthoritativeState(null,null,null,BuiltinGasDefinitions.createRegistry(),null));
		require(before.liquid().stocks().equals(restored.exportLiquidCellState().stocks()),"Cell/MAC codec changed liquid quantities");
		require(Arrays.equals(before.phaseOccupancy().copyLiquidMicroVolumes(),restored.exportPhaseOccupancyState().copyLiquidMicroVolumes()),
				"Cell/MAC codec changed saved micro phase occupancy");
		require(Arrays.equals(before.momentum().copyX(),restored.exportMacMomentum().copyX())
				&&Arrays.equals(before.momentum().copyY(),restored.exportMacMomentum().copyY())
				&&Arrays.equals(before.momentum().copyZ(),restored.exportMacMomentum().copyZ()),"Cell/MAC codec changed face momentum");
		require(Arrays.equals(before.copyCollisionMasks(),restored.exportParticleState().copySolidMask()),"Cell/MAC codec changed collision masks");
		var hotCopy=HostFluidState.restoreAuthoritativeState(before);
		require(before.liquid().rows()==hotCopy.exportLiquidCellState().rows(),"Normal handoff copied immutable liquid rows");
		require(Arrays.equals(before.momentum().copyY(),hotCopy.exportMacMomentum().copyY()),"Normal handoff changed momentum");
		require(Arrays.equals(before.phaseOccupancy().copyLiquidMicroVolumes(),hotCopy.exportPhaseOccupancyState().copyLiquidMicroVolumes()),
				"Normal handoff changed micro phase occupancy");
		validatePhaseOccupancyPayload();
		var malformed=codec.encodeSection(sections.getFirst());malformed.remove("StateEncoding");
		expectIOException(()->codec.decodeSection(malformed),"Legacy particle section was accepted as cell inventory");
	}


	private static void validatePhaseOccupancyPayload() throws Exception {
		var codec=new PhaseOccupancyCodec();
		double[] occupancy={.125,0,.03125,.0625,0,.125,.015625,0,
				0,.0625,.125,0,.015625,.03125,0,.125};
		byte[] encoded=codec.encode(occupancy);
		require(Arrays.equals(occupancy,codec.decode(encoded,2)),
				"Phase occupancy codec changed cell or micro ordering");
		expectIOException(()->codec.decode(encoded,1),"Phase occupancy accepted foreign section dimensions");
		expectIOException(()->codec.decode(Arrays.copyOf(encoded,encoded.length-1),2),"Truncated phase occupancy was accepted");
		expectIOException(()->codec.decode(Arrays.copyOf(encoded,encoded.length+1),2),"Trailing phase occupancy bytes were accepted");
		for(double invalid:new double[]{-.001,Math.nextUp(.125),Double.NaN,Double.POSITIVE_INFINITY}) {
			byte[] changed=encoded.clone();
			java.nio.ByteBuffer.wrap(changed).putDouble(8,invalid);
			expectIOException(()->codec.decode(changed,2),"Invalid persisted micro liquid volume was accepted");
		}
	}

	static void validateThermalCodec() throws Exception {
		validateFiniteDeviceJournal();
		for(var origin:List.of(new net.minecraft.core.BlockPos(15,16,15),new net.minecraft.core.BlockPos(0,16,0),
				new net.minecraft.core.BlockPos(16_777_213,16,16_777_213),new net.minecraft.core.BlockPos(-16_777_219,16,-16_777_219)))
			validateThermalCodec(origin);
	}

	private static void validateFiniteDeviceJournal() throws Exception {
		String fingerprint = "b".repeat(64);
		var device = new FiniteThermalDeviceState("mmm:finite_heat_exchanger", new UUID(307, 311).toString(),
				"mmm:finite_heat_exchanger", new FiniteThermalBodyState(7, fingerprint,
						"mmm:thermal/copper", mmm.units.Mass.fromKilograms(16), Math.nextUp(152_345.0)));
		String body = mmm.thermal.FiniteThermalDeviceItemCodec.encode(device);
		Path root = Files.createTempDirectory(Path.of("."), "finite-device-wal-");
		var store = new FiniteDeviceJournalStore(root);
		for (int operation = 0; operation < 3; operation++) {
			boolean placing = operation == 0, drop = operation == 2;
			var prepared = new FiniteDeviceJournalStore.Journal(new UUID(401, operation), new UUID(409, 419),
					"minecraft:overworld", new net.minecraft.core.BlockPos(-17, 64, 31).asLong(), 4, 5, -1,
					mmm.recovery.CrossDomainRecovery.JournalPhase.PREPARED, placing, drop,
					new UUID(421, 431), operation == 1, new UUID(433, operation), placing ? body : "", body, fingerprint);
			store.write(prepared);
			Path temporary=root.resolve("mmm/device-transfer").resolve(prepared.operationId()+".nbt.tmp");
			Files.writeString(temporary,"interrupted publication");
			require(store.discover().equals(List.of(prepared)), "Prepared device WAL changed place/remove/drop identity");
			var committed = prepared.committed(0x6789);
			store.write(committed);
			var decoded = store.discover().getFirst();
			require(decoded.equals(committed)
					&& mmm.thermal.FiniteThermalDeviceItemCodec.decode(decoded.bodyContents()).equals(device),
					"Committed device WAL changed checksum or complete body inventory");
			var base = deviceRecoveryDecision(prepared, 4, 0,
					mmm.recovery.CrossDomainRecovery.ExternalTruth.BEFORE, mmm.recovery.CrossDomainRecovery.ExternalTruth.BEFORE);
			require(base.outcome() == mmm.recovery.CrossDomainRecovery.Outcome.DISCARD_UNCOMMITTED,
					"Uncommitted device WAL did not preserve world and item before states");
			for (int alreadyApplied = 0; alreadyApplied < 2; alreadyApplied++) {
				var partial = deviceRecoveryDecision(decoded, 5, 0x6789,
						alreadyApplied == 0 ? mmm.recovery.CrossDomainRecovery.ExternalTruth.AFTER : mmm.recovery.CrossDomainRecovery.ExternalTruth.BEFORE,
						alreadyApplied == 1 ? mmm.recovery.CrossDomainRecovery.ExternalTruth.AFTER : mmm.recovery.CrossDomainRecovery.ExternalTruth.BEFORE);
				require(partial.outcome() == mmm.recovery.CrossDomainRecovery.Outcome.CONVERGE_AFTER
						&& partial.afterMembers().equals(List.of(alreadyApplied))
						&& partial.beforeMembers().equals(List.of(1 - alreadyApplied)),
						"Device recovery did not resume only the missing world/item member");
			}
			require(deviceRecoveryDecision(decoded, 5, 0x6789, mmm.recovery.CrossDomainRecovery.ExternalTruth.THIRD,
					mmm.recovery.CrossDomainRecovery.ExternalTruth.BEFORE).reason()
					== mmm.recovery.CrossDomainRecovery.Reason.TARGET_EXTERNAL_THIRD,
					"Device recovery accepted a foreign world or item state");
			require(deviceRecoveryDecision(decoded, 5, 0x6789, mmm.recovery.CrossDomainRecovery.ExternalTruth.AFTER,
					mmm.recovery.CrossDomainRecovery.ExternalTruth.UNAVAILABLE).outcome()
					== mmm.recovery.CrossDomainRecovery.Outcome.WAIT_UNAVAILABLE,
					"Device recovery discarded an unavailable player or world member");
			require(deviceRecoveryDecision(decoded, 5, 0x6790, mmm.recovery.CrossDomainRecovery.ExternalTruth.AFTER,
					mmm.recovery.CrossDomainRecovery.ExternalTruth.AFTER).reason()
					== mmm.recovery.CrossDomainRecovery.Reason.TARGET_CHECKSUM_MISMATCH,
					"Device recovery accepted a different committed HEAD");
			store.delete(decoded.operationId());
			require(store.discover().isEmpty(), "Completed device WAL was retained");
			Files.writeString(temporary,"interrupted first publication");
			require(store.discover().isEmpty(), "Unpublished device WAL locked recovery");
			Files.delete(temporary);
		}
	}

	private static mmm.recovery.CrossDomainRecovery.Decision deviceRecoveryDecision(
			FiniteDeviceJournalStore.Journal journal, long head, long checksum,
			mmm.recovery.CrossDomainRecovery.ExternalTruth world, mmm.recovery.CrossDomainRecovery.ExternalTruth item) {
		return mmm.recovery.CrossDomainRecovery.decide(new mmm.recovery.CrossDomainRecovery.Boundary(
				head, checksum, journal.baseGeneration(), journal.targetGeneration(), journal.targetChecksum() >= 0,
				journal.targetChecksum(), journal.phase(), false), List.of(world, item));
	}

	private static void validateThermalCodec(net.minecraft.core.BlockPos origin) throws Exception {
		FluidRegion templateRegion = populatedRegion(new UUID(27L, 28L));
		FluidRegion region = fixtureRegion(templateRegion.id(),TestHostAccess.editable(templateRegion),origin);
		FlipParticleState pure = TestHostAccess.editable(region).exportParticleState();
		float[] positions = pure.copyPositionVolume();
		double[] mass = new double[pure.particleCount()];
		double totalMass = 0.0;
		for (int marker = 0; marker < mass.length; marker++) {
			mass[marker] = positions[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			totalMass += mass[marker];
		}
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		TestHostAccess.editable(region).restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(), pure.totalVolume(), mmm.units.SolverUnitScale.forCellSize(pure.cellSize()).massUnitsForNormalized(totalMass), liquids.materials(), positions, pure.copyParticleVelocities(), pure.copySolidMask(), java.util.Arrays.stream(mass).mapToLong(m -> mmm.units.SolverUnitScale.forCellSize(pure.cellSize()).massUnitsForNormalized(m)).toArray(), new int[pure.particleCount()]));
		liquids = TestHostAccess.editable(region).materialRegistry();
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long activeSvq = GranularLimits.MAX_PARCEL_VOLUME_SVQ, depositSvq = activeSvq - 17L;
		double activeMass = 3.25, depositMass = 4.75;
		int depositCell = (5 * region.sizeY() + 3) * region.sizeX() + 5;
		GranularStateSnapshot granularFixture = new GranularStateSnapshot(
				region.sizeX(), region.sizeY(), region.sizeZ(), granular.fingerprint(),
				granular.materials().stream().map(mmm.granular.ParticulateMaterial::id).toList(),
				List.of(new GranularStateSnapshot.ActiveParcel(2.75F, 0.75F, 2.75F,
						0.125F, -0.25F, 0.0625F, activeSvq, mmm.units.Mass.fromKilograms(activeMass), sand, 0,
						2, mmm.granular.GranularParcelStore.FLAG_SUPPORTED, 0)),
				List.of(new GranularStateSnapshot.DepositGroup(depositCell, granular.material(sand).restPorosityQ(),
						sand, 0, depositSvq, mmm.units.Mass.fromKilograms(depositMass))), Math.addExact(activeSvq, depositSvq),
				mmm.units.Mass.fromKilograms(activeMass + depositMass));
		TestHostAccess.editable(region).restoreGranularState(granularFixture, granular, liquids, BuiltinFluidMaterials.createRegistry());
		ThermalRegistry thermal = thermalRegistry(liquids, granular);
		double insertionTemperatureK = Math.nextUp(300.0);
		TestHostAccess.editable(region).bindThermal(thermal, liquids, insertionTemperatureK);
		ThermalStateFunction thermalState = new ThermalStateFunction(thermal);
		FiniteThermalDeviceInventory expectedDevices = new FiniteThermalDeviceInventory(
				thermal.fingerprint(), List.of(new FiniteThermalDeviceState(
						"mmm:fabric_block", "mmm:persistence/4/5/6", "mmm:device/persistence",
						new FiniteThermalBodyState(7L, thermal.fingerprint(),
								"mmm:thermal/persistence_device", mmm.units.Mass.fromKilograms(2.0),
								thermalState.internalEnergyAtTemperature(
										thermal.materialHandle("mmm:thermal/persistence_device"),
										2.0, java.util.Map.of(), 350.0)))));
		expectedDevices.requireCompatible(thermal);
		var sourceBody=expectedDevices.devices().getFirst();
		var ordinaryWall=new FiniteThermalDeviceState("mmm:ordinary_wall", "123@minecraft:stone", sourceBody.deviceTypeId(), sourceBody.body());
		expectedDevices=expectedDevices.insertExternal(ordinaryWall);
		var emptyWallAudit=FiniteThermalDeviceInventory.empty(thermal.fingerprint()).insertExternal(ordinaryWall)
				.extractExternal(ordinaryWall.providerId(),ordinaryWall.objectId());
		var deviceCodec=new FiniteThermalDeviceCodec();
		var restoredEmpty=deviceCodec.decode(deviceCodec.encode(emptyWallAudit),thermal);
		require(restoredEmpty.equals(emptyWallAudit) && restoredEmpty.devices().isEmpty()
				&& restoredEmpty.audit().externalSource().equals(SignedDyadic.of(ordinaryWall.body().internalEnergyJ()))
				&& restoredEmpty.audit().externalSink().equals(SignedDyadic.of(ordinaryWall.body().internalEnergyJ())),
				"ordinary wall source/sink audit did not survive removal and recovery");
		region.restoreFiniteThermalDevices(expectedDevices, thermal, region.checkpointGeneration());
		TestHostAccess.editable(region).setThermalTemperatureInCell(2, 1, 2, 325.0);
		TestHostAccess.editable(region).setThermalTemperatureInCell(1, 1, 1, 340.0);
		TestHostAccess.editable(region).setThermalTemperatureInCell(5, 3, 5, 280.0);
		TestHostAccess.editable(region).addCoherentPhaseExternal(5, 2, 5, "mmm:thermal/persistence_solid",
				SolidVolume.SVQ_PER_CELL / 8L, mmm.units.Mass.fromKilograms(0.125), null, 6.25);
		PendingPhaseMotionState expectedPending = new PendingPhaseMotionState(
				region.sizeX() * region.sizeY() * region.sizeZ(), List.of(
				new PendingPhaseMotionState.Entry((1 * region.sizeY() + 1) * region.sizeX() + 1),
				new PendingPhaseMotionState.Entry((3 * region.sizeY() + 2) * region.sizeX() + 3)));
		TestHostAccess.editable(region).restorePendingPhaseMotionState(expectedPending);
		FlipThermalParticleState expected = TestHostAccess.editable(region).exportThermalParticleState();
		require(Double.doubleToRawLongBits(expected.insertionTemperatureK())
				== Double.doubleToRawLongBits(insertionTemperatureK), "configured insertion temperature was not captured");
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		var expectedGas = TestHostAccess.editable(region).captureAuthoritativeState().spatialGas();
		GranularStateSnapshot expectedGranular = TestHostAccess.editable(region).exportGranularState();
		double[] expectedOccupancy = TestHostAccess.editable(region).exportPhaseOccupancyState().copyLiquidMicroVolumes();

		RegionState state = RegionCheckpointFixtures.captureThermal(region, 1L);
		state.validate();
		assertMaterialStateEquals(TestHostAccess.editable(region).exportMaterialParticleState(), restorer(state).reconstructMaterial());
		assertGranularStateEquals(expectedGranular, restorer(state).reconstructGranular(granular));
		assertThermalStateEquals(expected, restorer(state).reconstructThermal(thermal));
		require(restorer(state).reconstructFiniteThermalDevices(thermal).equals(expectedDevices),
				"finite thermal device truth did not round-trip");
		require(restorer(state).reconstructPendingPhaseMotion().entries().equals(expectedPending.entries()),
				"pending phase-motion record did not round-trip");
		var complete = restorer(state).reconstructAuthoritativeState(null,granular,thermal,gas,BuiltinFluidMaterials.createRegistry());
		var restoredGas=complete.spatialGas();
		require(expectedGas.cells().equals(restoredGas.cells()) && Arrays.equals(expectedGas.internalEnergy(),restoredGas.internalEnergy())
				&& expectedGas.externalSource().equals(restoredGas.externalSource()) && expectedGas.escapedSink().equals(restoredGas.escapedSink()),
				"Spatial gas inventory, energy or audit did not round-trip");
		require(Arrays.equals(expectedOccupancy,complete.phaseOccupancy().copyLiquidMicroVolumes()),
				"Cold checkpoint reconstruction changed micro phase occupancy");
		HostFluidState restoredDomain = HostFluidState.restoreAuthoritativeState(complete);
		require(Arrays.equals(expectedOccupancy,restoredDomain.exportPhaseOccupancyState().copyLiquidMicroVolumes()),
				"Cold domain restore changed micro phase occupancy");
		require(TestHostAccess.editable(region).exportLiquidCellState().stocks().equals(restoredDomain.exportLiquidCellState().stocks()),"Cold restore changed liquid quantities");
		require(java.util.Arrays.equals(TestHostAccess.editable(region).exportMacMomentum().copyX(),restoredDomain.exportMacMomentum().copyX())
				&& java.util.Arrays.equals(TestHostAccess.editable(region).exportMacMomentum().copyY(),restoredDomain.exportMacMomentum().copyY())
				&& java.util.Arrays.equals(TestHostAccess.editable(region).exportMacMomentum().copyZ(),restoredDomain.exportMacMomentum().copyZ()),"Cold restore changed MAC momentum");
		assertGranularStateEquals(expectedGranular, restoredDomain.exportGranularState());
		assertThermalStateEquals(expected, restoredDomain.exportThermalParticleState());
		require(restoredDomain.exportPendingPhaseMotionState().entries().equals(expectedPending.entries()),
				"pending phase motion was not restored beside thermal inventory");

		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest decodedManifest = codec.decodeManifest(codec.encodeManifest(state.manifest()));
		require(decodedManifest.equals(state.manifest()) && decodedManifest.layout() == RegionStateLayout.THERMAL
				&& decodedManifest.hasThermalState(), "thermal region manifest did not round-trip");
		for (RegionSectionState section : state.sections()) {
			RegionSectionState decoded = codec.decodeSection(codec.encodeSection(section));
			require(decoded.layout() == RegionStateLayout.THERMAL && decoded.extensions().equals(section.extensions())
					&& decoded.liquidStocks().equals(section.liquidStocks()) && decoded.faceMomentum().equals(section.faceMomentum()),
					"thermal region section did not round-trip");
		}
		CoherentPhaseStore.Composition composition = new CoherentPhaseStore.Composition(List.of(
				new CoherentPhaseStore.Component("test:a", 7L),
				new CoherentPhaseStore.Component("test:b", 11L)), mmm.units.Mass.fromKilograms(0.25));
		ThermalRegionStateCodec thermalCodec = new ThermalRegionStateCodec();
		validateInsertionTemperaturePayload(thermalCodec, state);
		ThermalRegionStateCodec.SectionData composed = new ThermalRegionStateCodec.SectionData(
				new long[0], new long[0], List.of(new ThermalRegionStateCodec.CoherentEntry(
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
		var committed=files.read(region.id());
		require(Arrays.equals(expectedOccupancy,restorer(committed).reconstructPhaseOccupancy().copyLiquidMicroVolumes()),
				"Committed HEAD readback changed micro phase occupancy");
		assertThermalStateEquals(expected, restorer(committed).reconstructThermal(thermal));
		require(restorer(files.read(region.id())).reconstructFiniteThermalDevices(thermal).equals(expectedDevices),
				"published finite thermal device truth did not survive committed readback");
		require(restorer(files.read(region.id())).reconstructPendingPhaseMotion().entries().equals(expectedPending.entries()),
				"published pending phase motion did not survive committed readback");
		var removal=files.prepareRemoval(region.id(),1L,state.manifest().checksum(),2L,UUID.randomUUID(),
				"0".repeat(64),"0".repeat(64),10000L,64L*1024*1024);
		files.commitRemoval(removal);
		var quarantine=files.convergeRemovalQuarantine(region.id());
		var archived=new ActiveGenerationStore(null,granular,thermal,gas).readSlot(quarantine.resolve("payload"));
		require(restorer(archived).reconstructFiniteThermalDevices(thermal).equals(expectedDevices),
				"Removed region quarantine lost fixed wall inventory or thermal audit");
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

	/** A sealed, inventory-free cavity is durable vacuum, not a malformed gas thermal node. */
	private static void validateExplicitVacuumThermalCheckpoint() throws Exception {
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		FluidRegion vacuumRegion = emptyRegion(new UUID(271L, 272L));
		TestHostAccess.editable(vacuumRegion).sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(TestHostAccess.editable(vacuumRegion), registries.liquids());
		for (int z = 0; z < vacuumRegion.sizeZ(); z++) for (int y = 0; y < vacuumRegion.sizeY(); y++) {
			for (int x = 0; x < vacuumRegion.sizeX(); x++) TestHostAccess.editable(vacuumRegion).setSolid(x, y, z, true);
		}
		FullCapabilityTestFixture.bindFull(TestHostAccess.editable(vacuumRegion), registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		TestHostAccess.editable(vacuumRegion).setSolidMicroMask(1, 1, 1, 0xAA);
		int vacuumCell=1+vacuumRegion.sizeX()*(1+vacuumRegion.sizeY());
		require(TestHostAccess.editable(vacuumRegion).gasBoundary().nodeCount() == 1
				&& TestHostAccess.editable(vacuumRegion).gasBoundary().inventory(vacuumCell+1).isEmpty()
				&& TestHostAccess.editable(vacuumRegion).exactGasInternalEnergy().equals(SignedDyadic.ZERO),
				"vacuum checkpoint fixture did not contain one zero-U cavity");

		RegionState vacuumState = RegionCheckpointFixtures.captureThermal(vacuumRegion, 1L);
		Path root = Files.createTempDirectory(Path.of("."), "vacuum-thermal-region-");
		RegionGenerationStore store = new RegionGenerationStore(root, registries.chemical(), registries.granular(),
				registries.thermal(), registries.gas());
		store.publish(vacuumState);
		RegionCheckpointRestorer restored = restorer(store.read(vacuumRegion.id()));
		var gas=restored.reconstructSpatialGas(registries.gas(),registries.chemical());
		require(gas.cells().get(vacuumCell).isEmpty() && gas.energyAt(vacuumCell)==0.0,
				"durable vacuum checkpoint changed its zero-U gas cell");
	}

	static void validateGranularCodec() throws Exception {
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		FluidRegion region = populatedRegion(new UUID(23L, 24L));
		FlipParticleState pure = TestHostAccess.editable(region).exportParticleState();
		float[] positions = pure.copyPositionVolume();
		double[] liquidMass = new double[pure.particleCount()];
		double totalLiquidMass = 0.0;
		for (int marker = 0; marker < liquidMass.length; marker++) {
			liquidMass[marker] = positions[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			totalLiquidMass += liquidMass[marker];
		}
		TestHostAccess.editable(region).restoreMaterialParticleState(new FlipMaterialParticleState(pure.sizeX(), pure.sizeY(), pure.sizeZ(),
				pure.cellSize(), pure.particleCount(), pure.totalVolume(), mmm.units.SolverUnitScale.forCellSize(pure.cellSize()).massUnitsForNormalized(totalLiquidMass), BuiltinFluidMaterials.DEFAULT_PALETTE,
				positions, pure.copyParticleVelocities(), pure.copySolidMask(), java.util.Arrays.stream(liquidMass).mapToLong(m -> mmm.units.SolverUnitScale.forCellSize(pure.cellSize()).massUnitsForNormalized(m)).toArray(), new int[pure.particleCount()]));
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long activeSvq = GranularLimits.MAX_PARCEL_VOLUME_SVQ, depositSvq = activeSvq - 17L;
		double activeMass = 3.25, depositMass = 4.75;
		int depositCell = (5 * region.sizeY() + 3) * region.sizeX() + 5;
		GranularStateSnapshot fixture = new GranularStateSnapshot(region.sizeX(), region.sizeY(), region.sizeZ(),
				granular.fingerprint(), granular.materials().stream().map(mmm.granular.ParticulateMaterial::id).toList(),
				List.of(new GranularStateSnapshot.ActiveParcel(0.75F, 0.75F, 0.75F,
						0.125F, -0.25F, 0.0625F, activeSvq, mmm.units.Mass.fromKilograms(activeMass), sand, 0,
						2, mmm.granular.GranularParcelStore.FLAG_SUPPORTED, 0)),
				List.of(new GranularStateSnapshot.DepositGroup(depositCell, granular.material(sand).restPorosityQ(),
						sand, 0, depositSvq, mmm.units.Mass.fromKilograms(depositMass))), Math.addExact(activeSvq, depositSvq),
				mmm.units.Mass.fromKilograms(activeMass + depositMass));
		TestHostAccess.editable(region).restoreGranularState(fixture, granular, liquids, BuiltinFluidMaterials.createRegistry());
		GranularStateSnapshot expected = TestHostAccess.editable(region).exportGranularState();
		RegionState state = RegionCheckpointFixtures.captureGranular(region, 1L);
		state.validate();
		assertMaterialStateEquals(TestHostAccess.editable(region).exportMaterialParticleState(), restorer(state).reconstructMaterial());
		assertGranularStateEquals(expected, restorer(state).reconstructGranular(granular));

		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest decodedManifest = codec.decodeManifest(codec.encodeManifest(state.manifest()));
		require(decodedManifest.equals(state.manifest()) && decodedManifest.layout() == RegionStateLayout.GRANULAR
				&& !decodedManifest.hasChemicalState(), "granular region manifest did not round-trip");
		for (RegionSectionState section : state.sections()) {
			RegionSectionState decoded = codec.decodeSection(codec.encodeSection(section));
			require(decoded.layout() == RegionStateLayout.GRANULAR && decoded.key().equals(section.key())
					&& decoded.extensions().equals(section.extensions())
					&& decoded.liquidStocks().equals(section.liquidStocks()) && decoded.faceMomentum().equals(section.faceMomentum())
					&& Arrays.equals(decoded.solidMask(), section.solidMask())
					&& decoded.checksum() == section.checksum(),
					"granular region section did not round-trip");
		}
		GranularRegionStateCodec granularCodec = new GranularRegionStateCodec();
		var selectedParcel = new GranularStateSnapshot.ActiveParcel(.5F, .75F, .75F, 0, 0, 0, 3, mmm.units.Mass.fromKilograms(.001), sand, 0, 0, (byte) 0, 2);
		var selectedSection = new GranularRegionStateCodec.SectionData(
				List.of(new GranularRegionStateCodec.ActiveEntry(0, selectedParcel)), List.of());
		byte[] selectedBytes = granularCodec.encodeSection(selectedSection);
		require(granularCodec.decodeSection(selectedBytes, 1, 0).equals(selectedSection), "Section codec lost the actual noncanonical ceil selection");
		byte[] invalidSelection = selectedBytes.clone(); invalidSelection[invalidSelection.length - Integer.BYTES - 1] = (byte) 255;
		expectIOException(() -> granularCodec.decodeSection(invalidSelection, 1, 0), "Invalid persisted ceil selection was accepted");
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
		FlipParticleState reactivePure = TestHostAccess.editable(reactiveRegion).exportParticleState();
		float[] reactivePositions = reactivePure.copyPositionVolume();
		double[] reactiveLiquidMass = new double[reactivePure.particleCount()]; double reactiveTotalMass = 0.0;
		for (int marker = 0; marker < reactiveLiquidMass.length; marker++) {
			reactiveLiquidMass[marker] = reactivePositions[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			reactiveTotalMass += reactiveLiquidMass[marker];
		}
		TestHostAccess.editable(reactiveRegion).restoreMaterialParticleState(new FlipMaterialParticleState(reactivePure.sizeX(),
				reactivePure.sizeY(), reactivePure.sizeZ(), reactivePure.cellSize(), reactivePure.particleCount(),
				reactivePure.totalVolume(), mmm.units.SolverUnitScale.forCellSize(reactivePure.cellSize()).massUnitsForNormalized(reactiveTotalMass), BuiltinFluidMaterials.DEFAULT_PALETTE, reactivePositions,
				reactivePure.copyParticleVelocities(), reactivePure.copySolidMask(), java.util.Arrays.stream(reactiveLiquidMass).mapToLong(m -> mmm.units.SolverUnitScale.forCellSize(reactivePure.cellSize()).massUnitsForNormalized(m)).toArray(),
				new int[reactivePure.particleCount()]));
		TestHostAccess.editable(reactiveRegion).bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		SolidCompositionRecord composition = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(BuiltinChemicalDefinitions.NA_PLUS, 640L,
						BuiltinChemicalDefinitions.CL_MINUS, 640L), 0L);
		GranularStateSnapshot reactiveFixture = new GranularStateSnapshot(reactiveRegion.sizeX(), reactiveRegion.sizeY(),
				reactiveRegion.sizeZ(), granular.fingerprint(), BuiltinChemicalDefinitions.REGISTRY.fingerprint(),
				granular.materials().stream().map(mmm.granular.ParticulateMaterial::id).toList(),
				List.of(new GranularStateSnapshot.StableComposition(List.of(
						new GranularStateSnapshot.CompositionComponent(BuiltinChemicalDefinitions.CL_MINUS, 640L),
						new GranularStateSnapshot.CompositionComponent(BuiltinChemicalDefinitions.NA_PLUS, 640L)), 0L)),
				java.util.Map.of(BuiltinChemicalDefinitions.CL_MINUS, 640L, BuiltinChemicalDefinitions.NA_PLUS, 640L),
				List.of(new GranularStateSnapshot.ActiveParcel(0.75F, 0.75F, 0.75F, 0.0F, 0.0F, 0.0F,
						10L, mmm.units.Mass.fromKilograms(composition.totalMass(BuiltinChemicalDefinitions.REGISTRY)),
						granular.materialHandle(BuiltinGranularDefinitions.PRECIPITATE), 1, 0, (byte) 0, 0)), List.of(), 10L,
				mmm.units.Mass.fromKilograms(composition.totalMass(BuiltinChemicalDefinitions.REGISTRY)));
		TestHostAccess.editable(reactiveRegion).restoreGranularState(reactiveFixture, granular, liquids, BuiltinFluidMaterials.createRegistry());
		var removedReactive = TestHostAccess.editable(reactiveRegion).tryExtractExactGranular(1, 1, 1, 10L, 0, true);
		require(removedReactive.transferred()
				&& TestHostAccess.editable(reactiveRegion).tryInsertExactGranular(1, 1, 1, removedReactive.ledger(), 0, true).inserted(),
				"reactive granular audit fixture could not cross the region boundary");
		GranularStateSnapshot reactiveExpected = TestHostAccess.editable(reactiveRegion).exportGranularState();
		RegionState reactiveState = RegionCheckpointFixtures.captureGranular(reactiveRegion, 1L);
		reactiveState.validate();
		GranularStateSnapshot reactiveRoundTrip = restorer(reactiveState).reconstructGranular(granular);
		reactiveRoundTrip.validateSolidCompositionMasses(BuiltinChemicalDefinitions.REGISTRY);
		assertGranularStateEquals(reactiveExpected, reactiveRoundTrip);
		Path reactiveRoot = Files.createTempDirectory(Path.of("."), "reactive-granular-region-");
		RegionGenerationStore reactiveFiles = new RegionGenerationStore(
				reactiveRoot, BuiltinChemicalDefinitions.REGISTRY, granular);
		reactiveFiles.publish(reactiveState);
		TestHostAccess.editable(reactiveRegion).removeAllGranularExact();
		GranularStateSnapshot auditOnlyExpected = TestHostAccess.editable(reactiveRegion).exportGranularState();
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
		FlipParticleState pure = TestHostAccess.editable(region).exportParticleState();
		double[] mass = new double[pure.particleCount()];
		int[] material = new int[pure.particleCount()];
		double totalMass = 0.0;
		float[] positionVolume = pure.copyPositionVolume();
		for (int marker = 0; marker < pure.particleCount(); marker++) {
			mass[marker] = positionVolume[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			totalMass += mass[marker];
		}
		TestHostAccess.editable(region).restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(), pure.totalVolume(), mmm.units.SolverUnitScale.forCellSize(pure.cellSize()).massUnitsForNormalized(totalMass), BuiltinFluidMaterials.DEFAULT_PALETTE, positionVolume, pure.copyParticleVelocities(),
				pure.copySolidMask(), java.util.Arrays.stream(mass).mapToLong(m -> mmm.units.SolverUnitScale.forCellSize(pure.cellSize()).massUnitsForNormalized(m)).toArray(), material));
		TestHostAccess.editable(region).bindChemistry(chemistry);
		TestHostAccess.editable(region).setMarkerChemical(0, java.util.Map.of(
				BuiltinChemicalDefinitions.H2O, 123_457L,
				BuiltinChemicalDefinitions.NACL, 9_999L));
		var auditFixture = TestHostAccess.editable(region).chemicalLedgerForMarkerRange(0, 1);
		TestHostAccess.editable(region).chemistry().recordExternalSource(auditFixture);
		TestHostAccess.editable(region).chemistry().recordEscapedSink(auditFixture);
		FlipChemicalParticleState expected = TestHostAccess.editable(region).captureAuthoritativeState().chemicals();
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
					&& decoded.liquidStocks().equals(section.liquidStocks()) && decoded.faceMomentum().equals(section.faceMomentum()),
					"chemical region section did not round-trip");
		}

		Path root = Files.createTempDirectory(Path.of("."), "chemical-region-");
		RegionGenerationStore files = new RegionGenerationStore(root, chemistry);
		files.publish(state);
		assertChemicalStateEquals(expected, restorer(files.read(region.id())).reconstructChemical(chemistry));

		FluidRegion emptyRegion = emptyRegion(new UUID(21L, 22L));
		FlipParticleState emptyPure = TestHostAccess.editable(emptyRegion).exportParticleState();
		TestHostAccess.editable(emptyRegion).restoreMaterialParticleState(new FlipMaterialParticleState(
				emptyPure.sizeX(), emptyPure.sizeY(), emptyPure.sizeZ(), emptyPure.cellSize(), 0, 0.0, mmm.units.SolverUnitScale.forCellSize(emptyPure.cellSize()).massUnitsForNormalized(0.0),
				BuiltinFluidMaterials.DEFAULT_PALETTE, new float[0], new float[0], emptyPure.copySolidMask(), java.util.Arrays.stream(new double[0]).mapToLong(m -> mmm.units.SolverUnitScale.forCellSize(emptyPure.cellSize()).massUnitsForNormalized(m)).toArray(), new int[0]));
		TestHostAccess.editable(emptyRegion).bindChemistry(chemistry);
		FlipChemicalParticleState emptyExpected = TestHostAccess.editable(emptyRegion).exportChemicalParticleState();
		require(emptyExpected.particleCount() == 0 && emptyExpected.mixtures().isEmpty(),
				"empty chemistry handoff invented a parcel mixture");
		RegionState emptyState = RegionCheckpointFixtures.captureChemical(emptyRegion, 1L);
		emptyState.validate();
		RegionManifest emptyManifest = codec.decodeManifest(codec.encodeManifest(emptyState.manifest()));
		require(emptyManifest.chemicalMixtures().isEmpty(), "empty chemical manifest invented a mixture");
		assertChemicalStateEquals(emptyExpected, restorer(emptyState).reconstructChemical(chemistry));
		files.publish(emptyState);
		assertChemicalStateEquals(emptyExpected, restorer(files.read(emptyRegion.id())).reconstructChemical(chemistry));

		CompoundTag foreignFingerprint = codec.encodeManifest(state.manifest());
		foreignFingerprint.putString("RegistryFingerprint", "0".repeat(64));
		RegionManifest foreign = signedManifest(codec.decodeManifest(foreignFingerprint));
		expectIOException(() -> restorer(new RegionState(foreign, state.sections())).reconstructChemical(chemistry),
				"foreign chemical registry was accepted while decoding stored species handles");

		Path regionDir = root.toAbsolutePath().normalize().resolve("mmm").resolve("region-state")
				.resolve(region.id().toString());
		CompoundTag head = NbtIo.readCompressed(regionDir.resolve("HEAD.nbt"), NbtAccounter.unlimitedHeap());
		Path activeSlot = regionDir.resolve(head.getStringOr("Slot", ""));
		Path section = Files.list(activeSlot)
				.filter(path -> path.getFileName().toString().startsWith("section_"))
				.findFirst().orElseThrow();
		Files.delete(section);
		RegionGenerationStore.Discovery discovery = files.discover();
		require(discovery.manifests().stream().anyMatch(value -> value.regionId().equals(region.id())
				&& value.checkpointGeneration() == state.manifest().checkpointGeneration()),
				"Missing chemical payload erased the committed metadata owner");
		RegionGenerationStore.Discovery validated = files.discoverValidated();
		require(validated.manifests().stream().noneMatch(value -> value.regionId().equals(region.id()))
				&& validated.issues().containsKey(regionDir),
				"Strict discovery accepted a chemical generation with a missing section");
		expectIOException(() -> files.read(region.id()), "Complete chemical recovery accepted a missing section");
	}

	private static FlipMaterialParticleState materialCodecParticles(FlipParticleState pure, List<FluidMaterial> palette) {
		float[] positions = pure.copyPositionVolume();
		double[] mass = new double[pure.particleCount()];
		int[] materials = new int[pure.particleCount()];
		double totalMass = 0;
		for (int ordinal = 0; ordinal < pure.particleCount(); ordinal++) {
			materials[ordinal] = ordinal & 1;
			mass[ordinal] = positions[ordinal * 4 + 3] * palette.get(materials[ordinal]).referenceDensity();
			totalMass += mass[ordinal];
		}
		return new FlipMaterialParticleState(pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(),
				sumVolumes(positions), mmm.units.SolverUnitScale.forCellSize(pure.cellSize()).massUnitsForNormalized(totalMass), palette, positions, pure.copyParticleVelocities(), pure.copySolidMask(), java.util.Arrays.stream(mass).mapToLong(m -> mmm.units.SolverUnitScale.forCellSize(pure.cellSize()).massUnitsForNormalized(m)).toArray(), materials);
	}

	static void validateMaterialCodec() throws Exception {
		FluidRegion region = populatedRegion(new UUID(15L, 16L));
		List<FluidMaterial> palette = List.of(BuiltinFluidMaterials.WATER, BuiltinFluidMaterials.OIL);
		var input = materialCodecParticles(TestHostAccess.editable(region).exportParticleState(), palette);
		TestHostAccess.editable(region).restoreMaterialParticleState(input);
		TestHostAccess.editable(region).bindMaterialDefinitions(BuiltinFluidMaterials.createRegistry());
		// Point samples are derived from the committed shared MAC field after initialization.
		var authoritativeBefore=TestHostAccess.editable(region).captureAuthoritativeState();
		FlipMaterialParticleState expected = authoritativeBefore.materials();
		assertFixtureMaterialQuantities(input,authoritativeBefore);
		RegionState state = RegionCheckpointFixtures.captureMaterial(region, 1L);
		var canonicalRegion = canonicalCodecRegion(new UUID(15L, 16L));
		TestHostAccess.editable(canonicalRegion).restoreMaterialParticleState(materialCodecParticles(TestHostAccess.editable(canonicalRegion).exportParticleState(), palette));
		var canonical = RegionCheckpointFixtures.captureMaterial(canonicalRegion, 1L);
		canonical.validate();
		var repeated = RegionCheckpointFixtures.captureMaterial(canonicalRegion, 1L);
		require(canonical.manifest().equals(repeated.manifest()) && canonical.sections().stream().map(RegionSectionState::checksum).toList().equals(repeated.sections().stream().map(RegionSectionState::checksum).toList()),
				"Repeated material capture changed canonical cell/MAC bytes");
		state.validate();
		expectIOException(restorer(state)::reconstruct, "material state was silently reconstructed as pure water");
		assertMaterialStateEquals(expected, restorer(state).reconstructMaterial());

		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest decodedManifest = codec.decodeManifest(codec.encodeManifest(state.manifest()));
		require(decodedManifest.equals(state.manifest()), "material region manifest did not round-trip");
		for (RegionSectionState section : state.sections()) {
			RegionSectionState decoded = codec.decodeSection(codec.encodeSection(section));
			require(decoded.layout() == RegionStateLayout.MATERIAL && decoded.liquidStocks().equals(section.liquidStocks()) && decoded.faceMomentum().equals(section.faceMomentum())
					&& decoded.checksum() == section.checksum(), "material region section did not round-trip");
		}
		CompoundTag missingMass = codec.encodeSection(state.sections().getFirst());
		missingMass.getListOrEmpty("LiquidStocks").getCompoundOrEmpty(0).remove("MassUnits");
		expectIOException(() -> codec.decodeSection(missingMass), "material section missing marker mass was accepted");
		int changedIndex = 0;
		while (state.sections().get(changedIndex).markerCount() == 0) changedIndex++;
		RegionSectionState originalSection = state.sections().get(changedIndex);
		var changedStocks = new ArrayList<>(originalSection.liquidStocks());
		var changed = changedStocks.getFirst();
		changedStocks.set(0,new mmm.inventory.LiquidCellInventory.Stock(changed.key(),changed.volume(),Math.addExact(changed.massUnits(),1L),changed.mixture(),changed.internalEnergy()));
		RegionSectionState changedSection = signedSection(new RegionSectionState(RegionStateLayout.MATERIAL, originalSection.regionId(),
				originalSection.dimensionId(), originalSection.checkpointGeneration(), originalSection.sectionX(), originalSection.sectionY(),
				originalSection.sectionZ(), originalSection.localStartX(), originalSection.localStartY(), originalSection.localStartZ(),
				originalSection.sizeX(), originalSection.sizeY(), originalSection.sizeZ(), changedStocks,originalSection.faceMomentum(),
				originalSection.solidMask(), originalSection.extensions(), 0L));
		List<RegionSectionState> changedSections = new ArrayList<>(state.sections());
		changedSections.set(changedIndex, changedSection);
		List<RegionManifest.SectionDescriptor> changedDescriptors = new ArrayList<>(state.manifest().sections());
		for (int descriptor = 0; descriptor < changedDescriptors.size(); descriptor++) {
			RegionManifest.SectionDescriptor current = changedDescriptors.get(descriptor);
			if (current.key().equals(changedSection.key())) {
				changedDescriptors.set(descriptor, new RegionManifest.SectionDescriptor(current.sectionX(), current.sectionY(), current.sectionZ(),
						current.markerCount(), current.volumeBits(), changedSection.massUnits(), changedSection.checksum()));
			}
		}
		RegionManifest base = state.manifest();
		RegionManifest changedManifest = signedManifest(new RegionManifest(RegionStateLayout.MATERIAL, base.regionId(), base.dimensionId(), base.checkpointGeneration(),
				base.networkGeneration(), base.origin(), base.sizeX(), base.sizeY(), base.sizeZ(), base.solverConfig(),
				base.runtime(), base.footprint(), base.totalMarkerCount(), base.totalVolumeBits(),
				base.materialPalette(), Math.addExact(base.totalMassUnits(), 1L), base.materialLedgers(), changedDescriptors, base.extensions(), 0L));
		expectIOException(() -> new RegionState(changedManifest, changedSections).validate(),
				"rechecksummed marker-mass corruption escaped exact material ledgers");
		var pureRegion = populatedRegion(new UUID(17L, 18L));
		RegionState pureState = RegionCheckpointFixtures.capture(pureRegion, 1L);
		pureState.validate();
		assertParticleStateEquals(TestHostAccess.editable(pureRegion).exportParticleState(), restorer(pureState).reconstruct());
		RegionManifest pureManifest = pureState.manifest();
		var canonicalPure = RegionCheckpointFixtures.capture(canonicalCodecRegion(new UUID(17L, 18L)), 1L);
		canonicalPure.validate();
		var repeatedPure = RegionCheckpointFixtures.capture(canonicalCodecRegion(new UUID(17L, 18L)), 1L);
		require(canonicalPure.manifest().equals(repeatedPure.manifest()) && canonicalPure.sections().stream().map(RegionSectionState::checksum).toList().equals(repeatedPure.sections().stream().map(RegionSectionState::checksum).toList()),
				"Repeated pure capture changed canonical cell/MAC bytes");
		CompoundTag encodedPure = codec.encodeManifest(pureManifest);
		require(encodedPure.get("Materials") == null && encodedPure.getLongOr("MassUnits", -1L) == pureManifest.totalMassUnits(),
				"pure manifest changed its common mass column or acquired a material palette");

		Path root = Files.createTempDirectory(Path.of("."), "material-region-");
		RegionGenerationStore files = new RegionGenerationStore(root);
		files.publish(state);
		var committed=files.read(region.id());
		FlipMaterialParticleState restored = restorer(committed).reconstructMaterial();
		assertMaterialStateEquals(expected, restored);
		var authoritative=restorer(committed).reconstructAuthoritativeState(null,null,null,BuiltinGasDefinitions.createRegistry(),BuiltinFluidMaterials.createRegistry());
		assertFixtureMaterialQuantities(input,authoritative);
		require(region.recordPublishedCheckpoint(1L) && region.releaseSolverAfterCheckpoint(1L),
				"material controller fixture did not release its published solver");
		region.restoreAuthoritativeSolver(authoritative, 1L);
		FluidDomainStateAssertions.assertSameDurableState(authoritativeBefore,TestHostAccess.editable(region).captureAuthoritativeState(),"Material region cold restore");
		require(TestHostAccess.editable(region).hasMaterialState(), "material controller restore lost its bound registry");
		assertMaterialStateEquals(expected, TestHostAccess.editable(region).exportMaterialParticleState());
	}
	/** The input fixture supplies independent material IDs and per-cell quantities across palette remapping. */
	private static void assertFixtureMaterialQuantities(FlipMaterialParticleState input,mmm.fluid.FluidDomainState actual){
		var expectedMass=new java.util.TreeMap<String,Long>();var actualMass=new java.util.TreeMap<String,Long>();
		var expectedVolume=new java.util.TreeMap<String,mmm.units.SignedDyadic>();var actualVolume=new java.util.TreeMap<String,mmm.units.SignedDyadic>();
		float[] points=input.copyPositionVolume();long[] mass=input.copyParticleMassUnits();int[] handles=input.copyMaterialPaletteIndex();
		for(int i=0;i<input.particleCount();i++){
			int x=(int)Math.floor(points[4*i]/input.cellSize()),y=(int)Math.floor(points[4*i+1]/input.cellSize()),z=(int)Math.floor(points[4*i+2]/input.cellSize());
			String key=((z*input.sizeY()+y)*input.sizeX()+x)+":"+input.materials().get(handles[i]).id();
			expectedMass.merge(key,mass[i],Math::addExact);expectedVolume.merge(key,mmm.units.SignedDyadic.of(points[4*i+3]),mmm.units.SignedDyadic::add);
		}
		for(var stock:actual.liquid().physicalStocks()){
			String key=stock.key().cell()+":"+actual.materialPalette().get(stock.key().material()).id();
			actualMass.merge(key,stock.massUnits(),Math::addExact);actualVolume.merge(key,stock.volume(),mmm.units.SignedDyadic::add);
		}
		require(expectedMass.equals(actualMass)&&expectedVolume.equals(actualVolume),"Material codec changed fixture quantities or stable-ID palette mapping");
	}

}
