package mmm.simulation;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import mmm.api.motion.MovingStructureApi.*;
import mmm.fluid.FluidDomainContracts;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.FluidStateAccess;
import mmm.fluid.HostFluidState;
import mmm.thermal.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Checks that a structure carries the existing finite heat and surface inventories. */
public final class ExternalStructureThermalHandoffValidation {
	private static final UUID STRUCTURE = UUID.fromString("00000000-0000-0000-0000-000000000456");
	private static final String PROVIDER = "test:structure";
	private ExternalStructureThermalHandoffValidation() { }
	public static void main(String[] args) throws Exception {
		carryAndRestore();
		atomicRegionAdoption();
		restoredWallWithoutPorts();
		System.out.println("ExternalStructureThermalHandoffValidation passed");
	}
	private static void carryAndRestore() {
		String fingerprint = "a".repeat(64);
		var body = new FiniteThermalBodyState(7, fingerprint, "test:thermal", 1234, 98.125);
		var surface = new FixedSurfaceState(FiniteThermalDeviceDefinition.LocalFace.NORTH, "test:surface", 8,
				Map.of("test:species", 3L), Map.of("test:species", .25));
		var source = new BlockPos(10, 64, -4); var target = new BlockPos(12, 64, -4);
		var wall = new FiniteThermalDeviceState(OrdinaryThermalWalls.PROVIDER, source.asLong()+"@mmm:screen@X",
				"test:device", body, List.of(surface));
		var other = new FiniteThermalDeviceState("test:other", "unrelated", "test:device", body);
		var before = new FiniteThermalDeviceInventory(fingerprint, List.of(wall, other));
		check(!ExternalStructureThermalHandoff.hasCarried(before, PROVIDER, STRUCTURE), "fixed wall reported as carried");
		check(ExternalStructureThermalHandoff.plan(before, PROVIDER,
				handoff(HandoffKind.ASSEMBLE,new BlockPos(30,64,-4),"minecraft:stone")).equals(before),
				"block without thermal inventory created a wall");
		var assembled = ExternalStructureThermalHandoff.plan(before, PROVIDER,
				handoff(HandoffKind.ASSEMBLE, source, "mmm:screen"));
		var carried = assembled.devices().stream().filter(d -> d.providerId().equals(ExternalStructureThermalHandoff.PROVIDER)).findFirst().orElseThrow();
		check(ExternalStructureThermalHandoff.hasCarried(assembled, PROVIDER, STRUCTURE), "carried wall was not found");
		check(carried.body().equals(body) && carried.surfaces().equals(List.of(surface)), "carried wall changed contents");
		check(assembled.audit().equals(before.audit()) && assembled.exactInternalEnergy().equals(before.exactInternalEnergy()), "assembly changed thermal audit");
		var restored = ExternalStructureThermalHandoff.plan(assembled, PROVIDER,
				handoff(HandoffKind.DISASSEMBLE, target, "mmm:screen"));
		var fixed = restored.devices().stream().filter(d -> d.providerId().equals(OrdinaryThermalWalls.PROVIDER)).findFirst().orElseThrow();
		check(!ExternalStructureThermalHandoff.hasCarried(restored, PROVIDER, STRUCTURE), "restored wall remained carried");
		check(fixed.objectId().equals(target.asLong()+"@mmm:screen@X") && fixed.body().equals(body)
				&& fixed.surfaces().equals(List.of(surface)), "disassembly lost wall position, axis or inventory");
		check(restored.audit().equals(before.audit()) && restored.devices().contains(other), "disassembly changed unrelated inventory");
		var disassembly = handoff(HandoffKind.DISASSEMBLE, target, "mmm:screen");
		ExternalStructureThermalHandoff.validateRestoredScreenAxes(restored, disassembly, block -> "X");
		try { ExternalStructureThermalHandoff.validateRestoredScreenAxes(restored, disassembly, block -> "Y");
			throw new AssertionError("changed screen axis was accepted"); }
		catch (IllegalArgumentException expected) { }
		try { ExternalStructureThermalHandoff.plan(assembled, PROVIDER, handoff(HandoffKind.DISASSEMBLE, target, "minecraft:stone"));
			throw new AssertionError("changed block identity was accepted"); }
		catch (IllegalArgumentException expected) { }
		try { ExternalStructureThermalHandoff.plan(before, PROVIDER, handoff(HandoffKind.ASSEMBLE, source, "minecraft:stone"));
			throw new AssertionError("conflicting source block was accepted"); }
		catch (IllegalArgumentException expected) { }
		var occupied = new FiniteThermalDeviceState(OrdinaryThermalWalls.PROVIDER,target.asLong()+"@minecraft:stone","test:device",body);
		var occupiedInventory = new FiniteThermalDeviceInventory(fingerprint,List.of(carried,occupied,other));
		try { ExternalStructureThermalHandoff.plan(occupiedInventory, PROVIDER, handoff(HandoffKind.DISASSEMBLE, target, "mmm:screen"));
			throw new AssertionError("occupied target was accepted"); }
		catch (IllegalArgumentException expected) { }
	}
	private static void atomicRegionAdoption() throws Exception {
		var registries = FullCapabilityTestFixture.load(); var thermal = registries.thermal();
		var host = new HostFluidState(2,2,2,new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f), mmm.fluid.TestFluidDefinitions.load());
		host.bindMaterialRegistry(registries.liquids()); host.bindGasRegistry(registries.gas());
		host.bindThermal(thermal, registries.liquids(), 300);
		var dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse("test:thermal_handoff"));
		var region = new FluidRegion(UUID.randomUUID(), dimension, BlockPos.ZERO, host);
		var definition = thermal.finiteDevice("mmm:ordinary_stone_wall");
		double energy = new ThermalStateFunction(thermal).internalEnergyAtTemperature(thermal.materialHandle(definition.thermalMaterialId()),
				definition.bodyMassKilograms(), Map.of(), definition.initialTemperatureK());
		var body = new FiniteThermalBodyState(0,thermal.fingerprint(),definition.thermalMaterialId(),definition.bodyMassUnits(),energy);
		var position = new BlockPos(0,0,0);
		var wall = new FiniteThermalDeviceState(OrdinaryThermalWalls.PROVIDER,position.asLong()+"@minecraft:stone",definition.id(),body);
		var before = new FiniteThermalDeviceInventory(thermal.fingerprint(),List.of(wall));
		region.restoreFiniteThermalDevices(before,thermal,0);
		var contact = new FluidDomainContracts.ThermalPortRequest(0,0,0,0,.1,1,.1);
		region.replaceDevicePorts(List.of(new FluidStateAccess.DevicePort(wall.providerId(),wall.objectId(),contact)),List.of(new int[]{0}));
		var after = ExternalStructureThermalHandoff.plan(before,PROVIDER,handoff(HandoffKind.ASSEMBLE,position,"minecraft:stone"));
		long generation=region.solverGeneration(); var candidate=region.beginHostEdit(); candidate.setSolid(0,0,0,true);
		region.adoptStructureCandidate(generation,candidate,after);
		check(region.solverGeneration()==generation+1 && region.solidMicroMask(0,0,0)==255, "geometry was not adopted");
		check(region.finiteThermalDevices(thermal.fingerprint()).equals(after), "thermal inventory was not adopted with geometry");
		check(region.captureSolverState().finiteThermalPorts().isEmpty() && region.devicePortsDirty(), "old wall ports survived assembly");
	}
	private static Handoff handoff(HandoffKind kind,BlockPos pos,String blockId) {
		var pose=new Pose(0,0,0,true);
		var shape=new Structure(STRUCTURE,null,1,pose,pose,.05,List.of(new Piece("block",new Box(0,0,0,1,1,1))),false);
		return new Handoff(kind,shape,List.of(new FixedBlock("block",pos.getX(),pos.getY(),pos.getZ(),blockId)));
	}
	private static void restoredWallWithoutPorts() throws Exception {
		GpuControllerFixture.initializeItems();
		GpuControllerFixture.initializeIndustrialBlocks();
		var registries=FullCapabilityTestFixture.load(); var thermal=registries.thermal();
		var host=new HostFluidState(2,2,2,new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f),mmm.fluid.TestFluidDefinitions.load());
		host.bindMaterialRegistry(registries.liquids()); host.bindGasRegistry(registries.gas());
		host.bindThermal(thermal,registries.liquids(),300);
		for(int x=0;x<2;x++)for(int y=0;y<2;y++)for(int z=0;z<2;z++)host.setSolid(x,y,z,true);
		var region=new FluidRegion(UUID.randomUUID(),ResourceKey.create(Registries.DIMENSION,Identifier.parse("test:wall_refresh")),BlockPos.ZERO,host);
		var definition=thermal.finiteDevice("mmm:screen");
		double energy=new ThermalStateFunction(thermal).internalEnergyAtTemperature(thermal.materialHandle(definition.thermalMaterialId()),
				definition.bodyMassKilograms(),Map.of(),definition.initialTemperatureK());
		var body=new FiniteThermalBodyState(0,thermal.fingerprint(),definition.thermalMaterialId(),definition.bodyMassUnits(),energy);
		var pos=BlockPos.ZERO;
		var wall=new FiniteThermalDeviceState(OrdinaryThermalWalls.PROVIDER,pos.asLong()+"@mmm:screen@X",definition.id(),body);
		var before=new FiniteThermalDeviceInventory(thermal.fingerprint(),List.of(wall));
		var carried=ExternalStructureThermalHandoff.plan(before,PROVIDER,handoff(HandoffKind.ASSEMBLE,pos,"mmm:screen"));
		var restored=ExternalStructureThermalHandoff.plan(carried,PROVIDER,handoff(HandoffKind.DISASSEMBLE,pos,"mmm:screen"));
		region.restoreFiniteThermalDevices(restored,thermal,0);
		check(OrdinaryThermalWalls.ports(region,wall,thermal).isEmpty(),"fixture unexpectedly has a thermal port");
		var walls=new OrdinaryThermalWalls(thermal,new ThermalBoundaryResolver(thermal));
		var state=mmm.block.MmmBlocks.SCREEN.defaultBlockState().setValue(mmm.block.ScreenBlock.FACING,net.minecraft.core.Direction.EAST);
		walls.refresh(region,2,Map.of(pos,state));
		check(region.finiteThermalDevices(thermal.fingerprint()).equals(restored),"portless restored wall lost its inventory or audit");
		walls.refresh(region,2,Map.of());
		var removed=region.finiteThermalDevices(thermal.fingerprint());
		check(removed.devices().isEmpty() && removed.audit().externalSink().equals(restored.audit().externalSink().add(mmm.units.SignedDyadic.of(energy))),
				"removed wall did not transfer its energy to the sink");
	}
	private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
