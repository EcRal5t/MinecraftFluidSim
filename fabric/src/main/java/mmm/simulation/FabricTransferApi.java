package mmm.simulation;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import mmm.api.StableId;
import mmm.api.transfer.*;
import mmm.fluid.HostFluidState;
import mmm.item.ContainerItemMapper;
import mmm.item.MmmComponents;
import mmm.application.ContainerRecoveryCoordinator;
import mmm.simulation.persistence.PublicTransferReceiptStore;
import mmm.simulation.persistence.PublicTransferReceiptStore.Binding;
import mmm.transfer.ContainerTransferActor;
import mmm.transfer.ContainerTransferJournal;
import mmm.transfer.ContainerTransferManifests;
import mmm.transfer.TransferActorState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;

/** Public bounded transfers through real dispenser inventories and the existing container coordinator. */
final class FabricTransferApi implements MmmTransferApi {
	private static final class StaleActor extends IllegalStateException { StaleActor(String detail) { super(detail); } }
	private record Actor(Binding binding,ServerLevel level,net.minecraft.world.Container inventory,ItemStack item,TransferActorState state) { }
	private record Prepared(TransferPlan view,Binding binding,ContainerTransferJournal settlement,
			long solverGeneration,MinecraftContainerTransferPlan operations) { }
	private final MinecraftServer server;
	private final FluidRegionManager regions;
	private final FluidWorldTargetResolver targets;
	private final WorldStartupCoordinator.Result startup;
	private final CommittedWorldRegistries world;
	private final MinecraftRegionAvailability availability;
	private final MinecraftPendingPhaseWorkBarrier phaseWork;
	private final MinecraftContainerTransactionAdapter transactions;
	private final ContainerRecoveryCoordinator recovery;
	private final PublicTransferReceiptStore store;
	private final FluidSnapshotPublisher snapshots;
	private final UUID sessionId=UUID.randomUUID();
	private final Map<String,ActorProvider> providers=new LinkedHashMap<>();
	private final Map<UUID,Prepared> plans=new LinkedHashMap<>();
	FabricTransferApi(MinecraftServer server,FluidRegionManager regions,WorldStartupCoordinator.Result startup,
			CommittedWorldRegistries world,MinecraftRegionAvailability availability,MinecraftPendingPhaseWorkBarrier phaseWork,
			MinecraftContainerTransactionAdapter transactions,ContainerRecoveryCoordinator recovery,
			PublicTransferReceiptStore store,FluidSnapshotPublisher snapshots) {
		this.server=server;this.regions=regions;this.targets=new FluidWorldTargetResolver(regions);this.startup=startup;this.world=world;this.availability=availability;
		this.phaseWork=phaseWork;this.transactions=transactions;this.recovery=recovery;this.store=store;this.snapshots=snapshots;
		providers.put("mmm:block_inventory",BlockInventoryAddress::fromObjectId);
		try {
			reconcilePendingReceipts(store, recovery);
		} catch(IOException failure) { mmm.MmmMod.LOGGER.error("Public transfer startup receipts unavailable",failure); }
	}
	static void reconcilePendingReceipts(PublicTransferReceiptStore store, ContainerRecoveryCoordinator recovery) throws IOException {
		if (recovery.hasStoreIssues()) return;
		for (var entry : store.entries()) {
			if (entry.state() == PublicTransferReceiptStore.State.PENDING
					&& recovery.journal(entry.request().operationId()).isEmpty()) {
				store.abortWithoutJournal(entry.request().operationId());
			}
		}
	}
	@Override public void registerActorProvider(String providerId,ActorProvider provider) {
		if(!server.isSameThread()) throw new IllegalStateException("Transfer actor providers require the server thread");
		StableId.require(providerId,128,"transfer actor provider");
		if(provider==null || providers.putIfAbsent(providerId,provider)!=null)
			throw new IllegalArgumentException("Transfer actor provider is null or already registered");
	}
	@Override public TransferResult registerActor(ActorRef ref) {
		if(!server.isSameThread()) return result(TransferStatus.WRONG_THREAD,"Transfer registration requires the server thread");
		try {
			if(ref==null || !startup.authoritativeWritesReady()) return result(TransferStatus.UNAVAILABLE,"World is not ready for durable transfers");
			store.requireReady();
			var address=resolve(ref);var level=level(address.dimensionId());var inventory=inventory(level,address);
			if(inventory==null) return result(TransferStatus.UNAVAILABLE,"Dispenser actor is not loaded");
			var unregistered=new ContainerTransferActor.BlockInventory(address.dimensionId(),address.blockX(),address.blockY(),address.blockZ(),address.slot());
			if(recovery.hasPending(new UUID(0,0),unregistered)) return result(TransferStatus.PENDING,"Dispenser slot has an unresolved container WAL");
			var item=inventory.getItem(address.slot());ContainerItemMapper.fingerprint(item);
			var binding=store.binding(ref);
			String encoded=item.get(MmmComponents.TRANSFER_ACTOR);
			TransferActorState state;
			if(binding!=null) {
				if(!binding.address().equals(address) || encoded==null) return result(TransferStatus.STALE_ACTOR,"Persistent actor cannot be rebound to another slot or inventory");
				state=TransferActorState.decode(encoded);
				if(!binding.instanceId().equals(state.instanceId())) return result(TransferStatus.STALE_ACTOR,"Block inventory incarnation changed");
			} else {
				state=encoded==null?new TransferActorState(UUID.randomUUID(),0):TransferActorState.decode(encoded);
				item.set(MmmComponents.TRANSFER_ACTOR,state.encode());inventory.setChanged();
				level.save(null,true,false);
				binding=new Binding(ref,address,state.instanceId());store.bind(binding);
			}
			return new TransferResult(TransferStatus.OK,null,null,state.generation(),"Persistent actor registered");
		} catch(StaleActor stale) { return result(TransferStatus.STALE_ACTOR,detail(stale)); }
		catch(IOException | RuntimeException invalid) { return result(TransferStatus.UNAVAILABLE,detail(invalid)); }
	}
	@Override public TransferResult plan(TransferRequest request) {
		if(!server.isSameThread()) return result(TransferStatus.WRONG_THREAD,"Transfer planning requires the server thread");
		try {
			if(request==null) return result(TransferStatus.INVALID_REQUEST,"Transfer request is missing");
			var existing=existing(request);if(existing!=null) return existing;
			if(!startup.authoritativeWritesReady()) return result(TransferStatus.UNAVAILABLE,"World is not ready for durable transfers");
			var actor=actor(request.actor());
			if(actor.state().generation()!=request.expectedActorGeneration()) return result(TransferStatus.STALE_ACTOR,"Actor generation changed");
			var region=regions.region(request.regionId());
			if(region==null || !region.dimension().identifier().toString().equals(request.dimensionId())) return result(TransferStatus.NO_REGION,"Requested region is absent");
			if(!region.hasSelectedVolume() || !region.solverLoaded() || region.availabilityFailure()!=null || availability.hasPending(region.id())
					|| region.hasPendingPhaseMotionWork(region.activityForBodies(region.entityBodyState().bodies()))) return result(TransferStatus.UNAVAILABLE,"Region is unloaded, locked, or has pending mechanical work");
			if(!actor.binding().address().dimensionId().equals(request.dimensionId())) return result(TransferStatus.INVALID_REQUEST,"Actor and region dimensions differ");
			var target=portTarget(actor,region,request);
			if(target==null) return result(TransferStatus.INVALID_REQUEST,
					"Block inventory must touch the outward side of a fluid port and target its adjacent interior");
			var edit=region.beginHostEdit();
			int[] cell=target.cell(request.quantity(),request.direction()==TransferRequest.Direction.EXTRACT,edit);
			if(cell==null) return result(TransferStatus.NOT_TRANSFERRED,"接口接触面没有可用库存或空间");
			var resolution=MinecraftContainerTransferPlan.resolveDirected(transactions,world,region,cell,edit,actor.item().copy(),request.direction()==TransferRequest.Direction.EXTRACT);
			if(!resolution.resolved()) return result(TransferStatus.NOT_TRANSFERRED,resolution.diagnostic());
			var operation=resolution.plan();
			boolean insert=request.direction()==TransferRequest.Direction.INSERT;
			if(operation.filled()!=insert || (operation.resourceKind()==ContainerTransferJournal.ResourceKind.GRANULAR_SVQ)!=(request.quantity() instanceof TransferQuantity.Granular)
					|| (operation.resourceKind()==ContainerTransferJournal.ResourceKind.GAS_STANDARD_NANOS)!=(request.quantity() instanceof TransferQuantity.Gas))
				return result(TransferStatus.INVALID_REQUEST,"Request direction or quantity does not match the actual container inventory");
			long maximum=amount(request.quantity());
			var candidate=region.lifecycleCandidate(edit,region.paused());
			var outcome=operation.executeExact(candidate,Math.min(maximum,operation.requestedAmount()),operation.baseAmount());
			operation.discardEdit();
			if(outcome==null || !outcome.transferred()) return result(TransferStatus.NOT_TRANSFERRED,outcome==null?"No inventory transferred":outcome.message());
			if(outcome.resourceAmount()>maximum) return result(TransferStatus.NOT_TRANSFERRED,"Container cannot settle the requested bounded amount");
			var before=ContainerItemMapper.fingerprint(actor.item());
			var after=ContainerItemMapper.fingerprint(world.bindActualContainerProfile(outcome.stack()));
			var settlement=journal(request,actor,region,before,after,ContainerItemMapper.fingerprint(operation.sourceStack()),outcome);
			var manifest=ContainerTransferManifests.from(settlement,world.resolveMaterial("mmm:water").referenceDensity());
			long tick=tick();plans.values().removeIf(value->value.view().expiresAtServerTick()<tick);
			if(plans.size()>=128) return result(TransferStatus.UNAVAILABLE,"Public transfer plan budget is full");
			var view=new TransferPlan(UUID.randomUUID(),sessionId,tick+200,request,actor.state().instanceId(),actor.state().generation(),region.checkpointGeneration(),manifest);
			plans.put(view.planId(),new Prepared(view,actor.binding(),settlement,region.solverGeneration(),operation));
			return new TransferResult(TransferStatus.OK,view,null,actor.state().generation(),"Transfer planned from actual inventory");
		} catch(StaleActor stale) { return result(TransferStatus.STALE_ACTOR,detail(stale)); }
		catch(IOException | RuntimeException invalid) { return result(TransferStatus.UNAVAILABLE,detail(invalid)); }
	}
	@Override public TransferResult commit(UUID planId) {
		if(!server.isSameThread()) return result(TransferStatus.WRONG_THREAD,"Transfer commit requires the server thread");
		var prepared=plans.remove(planId);
		if(prepared==null || prepared.view().expiresAtServerTick()<tick()) return result(TransferStatus.STALE_PLAN,"Transfer plan expired or belongs to a previous session");
		var request=prepared.view().request();
		try {
			var existing=existing(request);if(existing!=null) return existing;
			if(!startup.authoritativeWritesReady()) return result(TransferStatus.UNAVAILABLE,"World is not ready for durable transfers");
			var actor=actor(request.actor());var region=regions.region(request.regionId());
			if(!actor.binding().equals(prepared.binding()) || !actor.state().instanceId().equals(prepared.view().actorInstanceId())
					|| actor.state().generation()!=prepared.view().actorGeneration()
					|| !ContainerItemMapper.fingerprint(actor.item()).equals(prepared.settlement().beforeItem()))
				return result(TransferStatus.STALE_ACTOR,"Actor changed after planning");
			if(region==null || !region.solverLoaded() || !availability.ensure(actor.level(),region) || !phaseWork.settle(actor.level(),region))
				return result(TransferStatus.UNAVAILABLE,"Region is currently unavailable");
			if(region.checkpointGeneration()!=prepared.view().regionCheckpointGeneration() || region.solverGeneration()!=prepared.solverGeneration())
				return result(TransferStatus.STALE_PLAN,"Region changed after planning");
			var target=portTarget(actor,region,request);
			if(target==null) return result(TransferStatus.STALE_PLAN,"Dispenser or directed fluid port changed after planning");
			if(target.cell()==null) return result(TransferStatus.UNAVAILABLE,"Target activity group awaits loaded world chunks");
			var durableActor=(ContainerTransferActor.BlockInventory)prepared.settlement().actor();
			if(recovery.hasPending(region.id(),durableActor)) return result(TransferStatus.PENDING,"Region or actor has a pending transaction");
			new MinecraftContainerActorAdapter().persistAfterItem(server,durableActor);
			store.prepare(request,actor.binding(),prepared.settlement());
			var transaction=transactions.executePublic(request.operationId(),region,actor.level(),durableActor,actor.item().copy(),prepared.operations().sourceStack(),
					prepared.settlement().operation(),prepared.settlement().resourceKind(),Math.min(amount(request.quantity()),prepared.operations().requestedAmount()),
					prepared.operations().baseAmount(),(candidate,maximum,base)-> {
						var actual=prepared.operations().executeExact(candidate,maximum,base);
						if(actual!=null && actual.transferred()) {
							var after=ContainerItemMapper.fingerprint(world.bindActualContainerProfile(actual.stack()));
							var expected=prepared.settlement();
							var actualEnergy=actual.thermalEnergy()==null?null:new ContainerTransferJournal.ThermalTransfer(world.thermalRegistryFingerprint(),actual.thermalEnergy());
							if(!after.equals(expected.afterItem()) || actual.resourceAmount()!=amount(prepared.view().manifest().quantity())
									|| !java.util.Objects.equals(actualEnergy,expected.thermalTransfer()))
								throw new IllegalStateException("Private transfer settlement differs from the accepted plan");
						}
						return actual;
					});
			recovery.retryTransaction(request.operationId());
			if(!recovery.hasStoreIssues() && recovery.journal(request.operationId()).isEmpty() && store.entry(request.operationId()).state()==PublicTransferReceiptStore.State.PENDING)
				store.abortWithoutJournal(request.operationId());
			var result=receipt(request.operationId());
			if(result.status()==TransferStatus.COMMITTED) snapshots.sendToLevel(actor.level(),region);
			return result;
		} catch(StaleActor stale) { return result(TransferStatus.STALE_ACTOR,detail(stale)); }
		catch(IOException | RuntimeException failed) {
			try { if(!recovery.hasStoreIssues() && recovery.journal(request.operationId()).isEmpty()) store.abortWithoutJournal(request.operationId()); }
			catch(IOException preserved) { failed.addSuppressed(preserved); }
			return result(TransferStatus.UNAVAILABLE,detail(failed));
		}
	}
	@Override public TransferResult receipt(UUID operationId) {
		if(!server.isSameThread()) return result(TransferStatus.WRONG_THREAD,"Transfer receipt requires the server thread");
		try {
			var entry=store.entry(operationId);
			if(entry==null) return result(TransferStatus.RECEIPT_UNAVAILABLE,"Operation is unknown or outside the retained receipt range");
			if(entry.state()==PublicTransferReceiptStore.State.PENDING) return result(TransferStatus.PENDING,"Operation awaits container WAL recovery");
			var receipt=entry.receipt(world.resolveMaterial("mmm:water").referenceDensity());
			return new TransferResult(receipt.committed()?TransferStatus.COMMITTED:TransferStatus.NOT_COMMITTED,null,receipt,receipt.actorAfterGeneration(),"Durable operation receipt");
		} catch(IOException | RuntimeException invalid) { return result(TransferStatus.UNAVAILABLE,detail(invalid)); }
	}
	private TransferResult existing(TransferRequest request) throws IOException {
		var entry=store.entry(request.operationId());
		if(entry==null) return null;
		if(!entry.request().equals(request)) return result(TransferStatus.CONFLICT,"Operation UUID already identifies a different durable request");
		return receipt(request.operationId());
	}
	private BlockInventoryAddress resolve(ActorRef ref) {
		var provider=providers.get(ref.providerId());
		if(provider==null) throw new IllegalStateException("Transfer actor provider is not registered");
		var address=provider.resolve(ref.objectId());
		if(address==null) throw new IllegalStateException("Transfer actor provider is unavailable");
		return address;
	}
	private FluidWorldTargetResolver.TransferTarget portTarget(Actor actor,FluidRegion region,TransferRequest request) {
		var address=actor.binding().address();
		var source=new BlockPos(address.blockX(),address.blockY(),address.blockZ());
		var interior=BlockPos.containing(request.worldXMetres(),request.worldYMetres(),request.worldZMetres());
		var port=BlockPos.containing((source.getX()+interior.getX())*.5,(source.getY()+interior.getY())*.5,(source.getZ()+interior.getZ())*.5);
		if(!actor.level().hasChunkAt(port)||!actor.level().getBlockState(port).is(mmm.block.MmmBlocks.FLUID_PORT))return null;
		var outward=actor.level().getBlockState(port).getValue(mmm.block.FluidPortBlock.FACING);
		if(!port.relative(outward).equals(source))return null;
		var facing=outward.getOpposite();
		var target=targets.transferTargetAtBlock(actor.level(),port,facing);
		return target!=null&&target.region()==region&&target.interiorBlock().equals(
				BlockPos.containing(request.worldXMetres(),request.worldYMetres(),request.worldZMetres()))?target:null;
	}

	private Actor actor(ActorRef ref) throws IOException {
		var binding=store.binding(ref);if(binding==null) throw new IllegalStateException("Transfer actor is not registered");
		var address=resolve(ref);if(!address.equals(binding.address())) throw new StaleActor("Persistent actor address cannot be rebound");
		var level=level(address.dimensionId());var inventory=inventory(level,address);
		if(inventory==null) {
			var position=new BlockPos(address.blockX(),address.blockY(),address.blockZ());
			if(level!=null && level.hasChunkAt(position)
					&& level.getBlockEntity(position) instanceof mmm.block.MaterialBoxBlockEntity box && box.transferBusy())
				throw new IllegalStateException("Material box transaction is in progress");
			if(level!=null && level.hasChunkAt(position)) throw new StaleActor("Dispenser actor no longer exists");
			throw new IllegalStateException("Dispenser actor is not loaded");
		}
		var item=inventory.getItem(address.slot());
		try { ContainerItemMapper.fingerprint(item); }
		catch(RuntimeException changed) { throw new StaleActor("Registered container was removed or replaced"); }
		TransferActorState state;
		try { state=TransferActorState.decode(item.get(MmmComponents.TRANSFER_ACTOR)); }
		catch(RuntimeException changed) { throw new StaleActor("Dispenser inventory identity is missing or invalid"); }
		if(!state.instanceId().equals(binding.instanceId())) throw new StaleActor("Block inventory incarnation changed");
		return new Actor(binding,level,inventory,item,state);
	}
	private static net.minecraft.world.Container inventory(ServerLevel level,BlockInventoryAddress address) {
		var position=new BlockPos(address.blockX(),address.blockY(),address.blockZ());
		return level!=null && level.hasChunkAt(position) && level.getBlockEntity(position) instanceof net.minecraft.world.Container inventory
				&& IndustrialOperations.inventoryAvailable(inventory)?inventory:null;
	}
	private ServerLevel level(String dimension) {
		for(var level:server.getAllLevels()) if(level.dimension().identifier().toString().equals(dimension)) return level;
		return null;
	}
	private static long amount(TransferQuantity quantity) {
		return quantity instanceof TransferQuantity.Liquid liquid?liquid.volumeNanos():quantity instanceof TransferQuantity.Gas gas?gas.standardVolumeNanos():((TransferQuantity.Granular)quantity).solidVolumeSvq();
	}
	private ContainerTransferJournal journal(TransferRequest request,Actor actor,FluidRegion region,
			ContainerTransferJournal.ItemFingerprint before,ContainerTransferJournal.ItemFingerprint after,
			ContainerTransferJournal.ItemFingerprint source,
			MinecraftContainerTransferOperations.TransferOutcome outcome) {
		var address=actor.binding().address();var kind=request.quantity() instanceof TransferQuantity.Granular?
				ContainerTransferJournal.ResourceKind.GRANULAR_SVQ:request.quantity() instanceof TransferQuantity.Gas?ContainerTransferJournal.ResourceKind.GAS_STANDARD_NANOS:ContainerTransferJournal.ResourceKind.FLUID_NANOS;
		var external=new ContainerTransferActor.BlockInventory(address.dimensionId(),address.blockX(),address.blockY(),address.blockZ(),address.slot(),actor.state().instanceId(),actor.state().generation());
		return new ContainerTransferJournal(request.operationId(),region.id(),request.dimensionId(),region.checkpointGeneration(),Math.addExact(region.checkpointGeneration(),1),
				request.direction()==TransferRequest.Direction.INSERT?ContainerTransferJournal.Operation.INSERT:ContainerTransferJournal.Operation.EXTRACT,
				kind,kind!=ContainerTransferJournal.ResourceKind.GRANULAR_SVQ?outcome.resourceAmount():-1,kind==ContainerTransferJournal.ResourceKind.GRANULAR_SVQ?outcome.resourceAmount():-1,
				outcome.thermalEnergy()==null?null:new ContainerTransferJournal.ThermalTransfer(world.thermalRegistryFingerprint(),outcome.thermalEnergy()),
				external,before,after,0,ContainerTransferJournal.Phase.REGION_COMMITTED,source);
	}
	private long tick() { return Integer.toUnsignedLong(server.getTickCount()); }
	private static TransferResult result(TransferStatus status,String detail) { return new TransferResult(status,null,null,-1,detail==null||detail.isBlank()?status.name():detail); }
	private static String detail(Exception failure) { return failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage(); }
}
