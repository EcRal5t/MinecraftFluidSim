package mmm.simulation;

import mmm.fluid.FluidDomainContracts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mmm.MmmMod;
import mmm.api.RegionAvailabilityReason;
import mmm.block.FiniteHeatExchangerBlockEntity;
import mmm.block.FiniteHeatExchangerBlockItem;
import mmm.block.MmmBlocks;
import mmm.fluid.HostFluidState;
import mmm.item.MmmComponents;
import mmm.recovery.CrossDomainRecovery;
import mmm.simulation.persistence.FiniteDeviceJournalStore;
import mmm.simulation.persistence.FiniteDeviceJournalStore.Journal;
import mmm.thermal.*;
import mmm.transfer.ContainerTransferActor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

/** Region HEAD owns installed bodies; a typed WAL converges the world slot and item save domains. */
public final class FiniteHeatExchangerOperations {
	private static final String PROVIDER = "mmm:finite_heat_exchanger";
	private static final String DELIVERY_TAG = "mmm_device_delivery:";
	public enum FaultPoint { NONE, AFTER_PREPARED, AFTER_COMMIT_READY, AFTER_HEAD, AFTER_WORLD, AFTER_ITEM, AFTER_PERSIST }
	private final MinecraftServer server;
	private final FluidRegionManager regions;
	private final FluidRegionOperations operations;
	private final FluidWorldTargetResolver targets;
	private final MinecraftRegionAvailability availability;
	private final MinecraftPendingPhaseWorkBarrier phaseWork;
	private final CommittedWorldRegistries world;
	private final FluidSnapshotPublisher snapshots;
	private final FiniteDeviceJournalStore store;
	private final Map<UUID, Journal> pending = new LinkedHashMap<>();
	private final Map<UUID, String> reported = new LinkedHashMap<>();
	private boolean scanFailed;
	private FaultPoint fault = FaultPoint.NONE;

	FiniteHeatExchangerOperations(MinecraftServer server, Path root, FluidRegionManager regions,
			FluidRegionOperations operations, FluidWorldTargetResolver targets,
			MinecraftRegionAvailability availability, MinecraftPendingPhaseWorkBarrier phaseWork,
			CommittedWorldRegistries world, FluidSnapshotPublisher snapshots) {
		this.server=server; this.regions=regions; this.operations=operations; this.targets=targets;
		this.availability=availability; this.phaseWork=phaseWork; this.world=world; this.snapshots=snapshots;
		store=new FiniteDeviceJournalStore(root);
		try {
			for (var journal:store.discover()) {
				if (pending.values().stream().anyMatch(value -> value.regionId().equals(journal.regionId())))
					throw new IOException("Multiple finite device operations identify the same region");
				pending.put(journal.operationId(),journal);
			}
		} catch (IOException | RuntimeException failure) {
			scanFailed=true; operations.recordIssue("Finite device WAL discovery: "+failure.getMessage());
		}
	}

	public static InteractionResult place(UseOnContext context) {
		if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
		if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
		return MmmRuntimeRegistry.require(player.level().getServer()).finiteHeatExchangers()
				.install(player,context.getHand(),context.getClickedPos());
	}
	public static InteractionResult uninstall(ServerPlayer player, InteractionHand hand, BlockPos position) {
		return MmmRuntimeRegistry.require(player.level().getServer()).finiteHeatExchangers()
				.remove(player,hand,position,false);
	}
	public static InteractionResult inspect(ServerPlayer player,BlockPos position) {
		return MmmRuntimeRegistry.require(player.level().getServer()).finiteHeatExchangers().describe(player,position);
	}
	public static void breakDevice(ServerPlayer player, BlockPos position) {
		MmmRuntimeRegistry.require(player.level().getServer()).finiteHeatExchangers()
				.remove(player,InteractionHand.MAIN_HAND,position,true);
	}
	public boolean hasPending(UUID region) {
		return scanFailed || pending.values().stream().anyMatch(journal -> journal.regionId().equals(region));
	}
	public FaultPoint fault() { return fault; }
	public void setFault(FaultPoint value) { fault=java.util.Objects.requireNonNull(value); }

	private InteractionResult install(ServerPlayer player,InteractionHand hand,BlockPos position) {
		ServerLevel level=player.level();
		try {
			if (!level.getBlockState(position).is(MmmBlocks.FLUID_BOUNDARY))
				throw new IllegalArgumentException("请对流体围墙模块安装有限换热器");
			ItemStack source=player.getItemInHand(hand);
			if (!source.is(MmmBlocks.FINITE_HEAT_EXCHANGER.asItem()) || source.getCount()!=1)
				throw new IllegalArgumentException("每次安装一个有限换热器");
			FluidRegion region=portRegion(level,position);
			requireReady(level,region);
			ThermalRegistry registry=world.thermalRegistry();
			String encoded=source.get(MmmComponents.FINITE_THERMAL_DEVICE);
			FiniteThermalDeviceState body;
			if (encoded==null) {
				if (!ItemStack.isSameItemSameComponents(source,new ItemStack(MmmBlocks.FINITE_HEAT_EXCHANGER)))
					throw new IllegalArgumentException("空设备物品组件不完整");
				var definition=registry.finiteDevice(PROVIDER);
				double energy=new ThermalStateFunction(registry).internalEnergyAtTemperature(
						registry.materialHandle(definition.thermalMaterialId()),definition.bodyMassKilograms(),
						Map.of(),definition.initialTemperatureK());
				body=new FiniteThermalDeviceState(PROVIDER,Long.toString(position.asLong()),PROVIDER,
						new FiniteThermalBodyState(0,registry.fingerprint(),definition.thermalMaterialId(),definition.bodyMassUnits(),energy));
			} else {
				body=FiniteThermalDeviceItemCodec.decode(encoded);
				if (!PROVIDER.equals(body.providerId()) || !PROVIDER.equals(body.deviceTypeId())
						|| !ItemStack.isSameItemSameComponents(source,FiniteHeatExchangerBlockItem.createStack(body,profile())))
					throw new IllegalArgumentException("设备物品与当前世界能力不一致");
				body=new FiniteThermalDeviceState(PROVIDER,Long.toString(position.asLong()),PROVIDER,body.body());
			}
			requireAvailablePorts(region, position, registry.finiteDevice(body.deviceTypeId()), registry);
			var inventory=region.finiteThermalDevices(registry.fingerprint()).insertExternal(body);
			inventory.requireCompatible(registry);
			transact(player,hand,position,region,body,encoded==null?"":encoded,true,false,inventory);
			player.sendSystemMessage(Component.literal("有限换热器已安装"));
			return InteractionResult.SUCCESS;
		} catch (IOException | RuntimeException failure) { return fail(player,failure); }
	}

	private InteractionResult remove(ServerPlayer player,InteractionHand hand,BlockPos position,boolean drop) {
		try {
			ServerLevel level=player.level();
			if (!drop && !player.getItemInHand(hand).isEmpty()) throw new IllegalArgumentException("请空手潜行拆卸设备");
			if (!(level.getBlockEntity(position) instanceof FiniteHeatExchangerBlockEntity locator)
					|| locator.regionId()==null || locator.objectId()==null)
				throw new IllegalArgumentException("设备定位身份缺失，保留现场供恢复");
			FluidRegion region=regions.region(locator.regionId());
			requireReady(level,region);
			var inventory=region.finiteThermalDevices(world.thermalRegistry().fingerprint());
			var body=inventory.devices().stream().filter(device -> device.providerId().equals(PROVIDER)
					&& device.objectId().equals(locator.objectId())).findFirst()
					.orElseThrow(() -> new IllegalStateException("设备方块与区域库存不一致"));
			requireAvailablePorts(region, position, world.thermalRegistry().finiteDevice(body.deviceTypeId()), world.thermalRegistry());
			transact(player,hand,position,region,body,"",false,drop,inventory.extractExternal(PROVIDER,body.objectId()));
			player.sendSystemMessage(Component.literal("有限换热器已拆卸，保留质量与内能"));
			return InteractionResult.SUCCESS;
		} catch (IOException | RuntimeException failure) { return fail(player,failure); }
	}

	private InteractionResult describe(ServerPlayer player,BlockPos position) {
		try {
			if (!(player.level().getBlockEntity(position) instanceof FiniteHeatExchangerBlockEntity locator)
					|| locator.regionId()==null) throw new IllegalStateException("设备定位身份缺失");
			var region=regions.region(locator.regionId());
			if (region==null || !availability.ensureForInventoryDecrease(player.level(),region))
				throw new IllegalStateException("设备区域正在恢复或当前不可用");
			var registry=world.thermalRegistry();
			var device=region.finiteThermalDevices(registry.fingerprint()).devices().stream()
					.filter(value -> value.providerId().equals(PROVIDER) && value.objectId().equals(locator.objectId()))
					.findFirst().orElseThrow(() -> new IllegalStateException("设备方块与区域库存不一致"));
			var body=device.body();
			double temperature=new ThermalStateFunction(registry).stateForInternalEnergy(
					registry.materialHandle(body.thermalMaterialId()),body.massKilograms(),Map.of(),body.internalEnergyJ()).temperatureK();
			player.sendSystemMessage(Component.translatable("message.mmm.finite_heat_exchanger.state",
					String.format(java.util.Locale.ROOT,"%.3f",temperature),body.massKilograms(),
					mmm.units.UnitFormat.energy(body.internalEnergyJ()),body.generation()));
			return InteractionResult.SUCCESS;
		} catch (RuntimeException failure) { return fail(player,failure); }
	}

	private void requireReady(ServerLevel level,FluidRegion region) {
		if (region==null || !region.controllerManaged() || !region.dimension().equals(level.dimension()))
			throw new IllegalArgumentException("换热器端口需要连接已启用的控制器区域");
		if (!availability.ensure(level,region) || !phaseWork.settle(level,region))
			throw new IllegalStateException("区域当前暂停或正在恢复事务");
		if (!region.hasThermalState()) throw new IllegalStateException("当前世界未启用热模型");
	}
	private void requireAvailablePorts(FluidRegion region, BlockPos position, FiniteThermalDeviceDefinition definition, ThermalRegistry registry) {
		for (var port : ports(region, position, definition, registry)) {
			int cell = region.grid().cell(port.cellX(), port.cellY(), port.cellZ());
			if (cell < 0 || !region.groupAvailable(cell)) throw new IllegalStateException("换热器连接的活动组等待区块加载");
		}
	}
	private String profile() { return world.profile().profileFingerprint(); }
	private InteractionResult fail(ServerPlayer player,Exception failure) {
		player.sendSystemMessage(Component.literal("有限换热器："+failure.getMessage()));
		return InteractionResult.FAIL;
	}
	private void fault(FaultPoint point) throws IOException {
		if (fault==point) { fault=FaultPoint.NONE; throw new IOException("Injected finite device fault: "+point); }
	}

	private void transact(ServerPlayer player,InteractionHand hand,BlockPos position,FluidRegion region,
			FiniteThermalDeviceState body,String source,boolean placing,boolean drop,
			FiniteThermalDeviceInventory inventory) throws IOException {
		// Persist the exact external preimage before publishing recovery evidence.
		persistPlayer(player.getUUID(),hand==InteractionHand.OFF_HAND);
		player.level().save(null,true,false);
		long base=operations.committedGeneration(region.id());
		var journal=new Journal(UUID.randomUUID(),region.id(),region.dimension().identifier().toString(),position.asLong(),
				base,Math.addExact(base,1),-1,CrossDomainRecovery.JournalPhase.PREPARED,placing,drop,
				player.getUUID(),hand==InteractionHand.OFF_HAND,UUID.randomUUID(),source,
				FiniteThermalDeviceItemCodec.encode(body),profile());
		pending.put(journal.operationId(),journal); store.write(journal);
		fault(FaultPoint.AFTER_PREPARED);
		FluidRegion candidate=region.finiteDeviceCandidate(inventory,world.thermalRegistry());
		var staged=operations.stageCheckpoint(candidate,journal.targetGeneration());
		journal=journal.committed(staged.manifestChecksum());
		store.write(journal); pending.put(journal.operationId(),journal);
		fault(FaultPoint.AFTER_COMMIT_READY);
		operations.commitStagedLifecycleCheckpoint(staged);
		fault(FaultPoint.AFTER_HEAD);
		if (!recover(journal)) throw new IOException("设备事务等待外部存档恢复，WAL已保留");
		snapshots.sendToLevel(player.level(),region);
	}

	/** May be retried after either external member was saved; HEAD and checksum select the sole outcome. */
	private boolean recover(Journal journal) throws IOException {
		if (!profile().equals(journal.profile())) throw new IOException("Finite device WAL world profile differs");
		FluidRegion region=regions.region(journal.regionId());
		if (region==null) throw new IOException("Finite device WAL region is absent");
		var truths=truths(journal,false);
		long head=operations.committedGeneration(journal.regionId());
		var boundary=new CrossDomainRecovery.Boundary(head,operations.committedManifestChecksum(journal.regionId()),
				journal.baseGeneration(),journal.targetGeneration(),journal.targetChecksum()>=0,journal.targetChecksum(),journal.phase(),false);
		var decision=CrossDomainRecovery.decide(boundary,truths);
		switch(decision.outcome()) {
			case DISCARD_UNCOMMITTED -> { finish(journal,region); return true; }
			case WAIT_UNAVAILABLE -> { return false; }
			case LOCKED, ROLLBACK_BEFORE -> throw new IOException("Finite device recovery: "+decision.reason());
			case CONVERGE_AFTER -> {
				ServerLevel level=level(journal.dimensionId());
				if (decision.beforeMembers().contains(0)) applyWorld(journal,level);
				fault(FaultPoint.AFTER_WORLD);
				if (decision.beforeMembers().contains(1)) applyItem(journal,level);
				fault(FaultPoint.AFTER_ITEM);
				level.save(null,true,false);
				if (!journal.drop()) persistPlayer(journal.playerId(),journal.offHand());
				if (!truths(journal,true).stream().allMatch(value -> value==CrossDomainRecovery.ExternalTruth.AFTER))
					throw new IOException("Finite device durable readback differs from target");
				fault(FaultPoint.AFTER_PERSIST);
				operations.adoptCommittedContainerCheckpoint(region,journal.baseGeneration(),journal.targetGeneration());
				finish(journal,region); return true;
			}
		}
		throw new AssertionError();
	}
	private void finish(Journal journal,FluidRegion region) throws IOException {
		store.delete(journal.operationId()); pending.remove(journal.operationId()); reported.remove(journal.operationId());
		if (journal.drop()) {
			var level=level(journal.dimensionId());
			if (level!=null && level.getEntity(journal.dropId()) instanceof ItemEntity item) releaseCompletedDelivery(item);
		}
		region.setAvailabilityFailure(null); region.wakeForActivity();
	}
	private List<CrossDomainRecovery.ExternalTruth> truths(Journal journal,boolean readback) {
		ServerLevel level=level(journal.dimensionId()); BlockPos position=BlockPos.of(journal.position());
		boolean loaded=level!=null && level.hasChunkAt(position);
		boolean boundary=loaded && level.getBlockState(position).is(MmmBlocks.FLUID_BOUNDARY);
		var body=FiniteThermalDeviceItemCodec.decode(journal.bodyContents());
		boolean device=loaded && level.getBlockState(position).is(MmmBlocks.FINITE_HEAT_EXCHANGER)
				&& level.getBlockEntity(position) instanceof FiniteHeatExchangerBlockEntity locator
				&& journal.regionId().equals(locator.regionId()) && body.objectId().equals(locator.objectId());
		var worldTruth=CrossDomainRecovery.classify(loaded,journal.placing()?boundary:device,journal.placing()?device:boundary,readback);
		ItemStack current=null;
		boolean itemAvailable;
		if (journal.drop()) {
			var entity=loaded?level.getEntity(journal.dropId()):null;
			itemAvailable=loaded && level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(position));
			if(entity instanceof ItemEntity item) current=item.getItem();
			else if(entity==null) current=ItemStack.EMPTY;
		} else {
			ServerPlayer player=server.getPlayerList().getPlayer(journal.playerId());
			itemAvailable=player!=null;
			if(player!=null) current=player.getItemInHand(journal.offHand()?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND);
		}
		boolean before=current!=null && ItemStack.isSameItemSameComponents(current,beforeItem(journal)) && current.getCount()==beforeItem(journal).getCount();
		boolean after=current!=null && ItemStack.isSameItemSameComponents(current,afterItem(journal)) && current.getCount()==afterItem(journal).getCount();
		return List.of(worldTruth,CrossDomainRecovery.classify(itemAvailable,before,after,readback));
	}
	private ItemStack beforeItem(Journal journal) {
		if (!journal.placing()) return ItemStack.EMPTY;
		return journal.sourceContents().isEmpty()?new ItemStack(MmmBlocks.FINITE_HEAT_EXCHANGER):
				FiniteHeatExchangerBlockItem.createStack(FiniteThermalDeviceItemCodec.decode(journal.sourceContents()),journal.profile());
	}
	private ItemStack afterItem(Journal journal) {
		return journal.placing()?ItemStack.EMPTY:
				FiniteHeatExchangerBlockItem.createStack(FiniteThermalDeviceItemCodec.decode(journal.bodyContents()),journal.profile());
	}
	private void applyWorld(Journal journal,ServerLevel level) throws IOException {
		BlockPos position=BlockPos.of(journal.position());
		if (!level.setBlock(position,(journal.placing()?MmmBlocks.FINITE_HEAT_EXCHANGER:MmmBlocks.FLUID_BOUNDARY).defaultBlockState(),3))
			throw new IOException("World rejected finite device slot replacement");
		if(journal.placing()) {
			if (!(level.getBlockEntity(position) instanceof FiniteHeatExchangerBlockEntity locator))
				throw new IOException("Finite device locator was not created");
			locator.bind(journal.regionId(),FiniteThermalDeviceItemCodec.decode(journal.bodyContents()).objectId());
		}
	}
	private void applyItem(Journal journal,ServerLevel level) throws IOException {
		if(journal.drop()) {
			BlockPos position=BlockPos.of(journal.position());
			var entity=new ItemEntity(level,position.getX()+.5,position.getY()+.5,position.getZ()+.5,afterItem(journal));
			entity.setUUID(journal.dropId());
			entity.addTag(DELIVERY_TAG+journal.operationId());
			entity.setNeverPickUp(); entity.setUnlimitedLifetime(); entity.setInvulnerable(true);
			entity.setNoGravity(true); entity.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
			if(!level.addFreshEntity(entity)) throw new IOException("World rejected finite device drop");
		} else {
			var player=server.getPlayerList().getPlayer(journal.playerId());
			player.setItemInHand(journal.offHand()?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND,afterItem(journal));
			player.getInventory().setChanged(); player.containerMenu.broadcastChanges();
		}
	}
	private void persistPlayer(UUID player,boolean offHand) throws IOException {
		new MinecraftContainerActorAdapter().persistAfterItem(server,ContainerTransferActor.player(player,
				offHand?ContainerTransferActor.Hand.OFF:ContainerTransferActor.Hand.MAIN));
	}
	private ServerLevel level(String dimension) {
		for(var level:server.getAllLevels()) if(level.dimension().identifier().toString().equals(dimension)) return level;
		return null;
	}
	public void recoverIndexed() {
		for(var journal:List.copyOf(pending.values())) {
			try { recover(journal); }
			catch(IOException | RuntimeException failure) {
				String message=failure.getMessage();
				if(!java.util.Objects.equals(reported.put(journal.operationId(),message),message))
					MmmMod.LOGGER.error("Finite device WAL {} preserved: {}",journal.operationId(),message);
			}
		}
	}

	/** A persisted delivery marker also finishes the crash window after WAL deletion and before unlock. */
	public void releaseCompletedDelivery(ItemEntity item) {
		if (scanFailed) return;
		for (String tag:List.copyOf(item.entityTags())) {
			if (!tag.startsWith(DELIVERY_TAG)) continue;
			UUID operation=UUID.fromString(tag.substring(DELIVERY_TAG.length()));
			if (pending.containsKey(operation)) return;
			item.setNoPickUpDelay(); item.setExtendedLifetime(); item.setInvulnerable(false);
			item.setNoGravity(false); item.removeTag(tag);
		}
	}

	private FluidRegion portRegion(ServerLevel level,BlockPos position) {
		FluidRegion result=null;
		for(var port:world.thermalRegistry().finiteDevice(PROVIDER).ports()) {
			Direction direction=Direction.valueOf(port.localFace().name());
			FluidRegion candidate=targets.regionAtBlock(level,position.relative(direction));
			if(candidate==null) continue;
			if(result!=null && result!=candidate) throw new IllegalArgumentException("换热器各端口需连接同一控制器区域");
			result=candidate;
		}
		return result;
	}

	/** Installed finite bodies join the cells sharing their heat inventory before scheduling. */
	void refreshActivityConnections(FluidRegion region) {
		if (!region.solverLoaded() || !region.devicePortsDirty()) return;
		var connections = new ArrayList<int[]>();
		var devicePorts = new ArrayList<mmm.fluid.FluidStateAccess.DevicePort>();
		if (world.thermalOrNull() != null) {
			var registry = world.thermalRegistry();
			for (var device : region.finiteThermalDevices(registry.fingerprint()).devices()) {
				List<mmm.fluid.FluidDomainContracts.ThermalPortRequest> requests;
				if (device.providerId().equals(PROVIDER)) requests = ports(region, BlockPos.of(Long.parseLong(device.objectId())),
						registry.finiteDevice(device.deviceTypeId()), registry);
				else if (device.providerId().equals(OrdinaryThermalWalls.PROVIDER)) requests = OrdinaryThermalWalls.ports(region, device, registry);
				else continue;
				for (var port : requests) devicePorts.add(new mmm.fluid.FluidStateAccess.DevicePort(device.providerId(),device.objectId(),port));
				connections.add(requests.stream().mapToInt(port ->
						region.grid().cell(port.cellX(), port.cellY(), port.cellZ())).distinct().toArray());
			}
		}
		region.replaceDevicePorts(devicePorts,connections);
	}

	private List<FluidDomainContracts.ThermalPortRequest> ports(FluidRegion region,BlockPos position,
			FiniteThermalDeviceDefinition definition,ThermalRegistry registry) {
		var requests=new ArrayList<FluidDomainContracts.ThermalPortRequest>();
		double h=region.solverConfig().cellSize();
		double conductivity=registry.material(registry.materialHandle(definition.thermalMaterialId())).conductivityWPerMeterK();
		for(var port:definition.ports()) {
			Direction direction=Direction.valueOf(port.localFace().name());
			BlockPos neighbor=position.relative(direction);
			if(region.footprint()!=null && !region.footprint().containsInterior(neighbor)) continue;
			int axis=direction.getAxis()==Direction.Axis.X?0:direction.getAxis()==Direction.Axis.Y?1:2;
			boolean positive=direction.getAxisDirection()==Direction.AxisDirection.POSITIVE;
			for(int a=0;a<(int)Math.round(1/h);a++) for(int b=0;b<(int)Math.round(1/h);b++) {
				double[] point={neighbor.getX()+.5,neighbor.getY()+.5,neighbor.getZ()+.5};
				point[axis]=positive?neighbor.get(axis==0?Direction.Axis.X:axis==1?Direction.Axis.Y:Direction.Axis.Z)+h*.5:
						neighbor.get(axis==0?Direction.Axis.X:axis==1?Direction.Axis.Y:Direction.Axis.Z)+1-h*.5;
				point[(axis+1)%3]=((axis+1)%3==0?neighbor.getX():(axis+1)%3==1?neighbor.getY():neighbor.getZ())+(a+.5)*h;
				point[(axis+2)%3]=((axis+2)%3==0?neighbor.getX():(axis+2)%3==1?neighbor.getY():neighbor.getZ())+(b+.5)*h;
				int x=(int)Math.floor((point[0]-region.origin().getX())/h),y=(int)Math.floor((point[1]-region.origin().getY())/h),z=(int)Math.floor((point[2]-region.origin().getZ())/h);
				if(region.grid().cell(x,y,z)<0) continue;
				int face=axis*2+(positive?0:1);
				requests.add(new FluidDomainContracts.ThermalPortRequest(x,y,z,face,port.wallThicknessMeters(),conductivity,port.fluidFilmThicknessMeters()));
			}
		}
		return List.copyOf(requests);
	}
}
