package mmm.simulation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import mmm.MmmMod;
import mmm.api.RegionAvailabilityReason;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.fluid.FluidDomain;
import mmm.recovery.CrossDomainRecovery;
import mmm.simulation.persistence.FarmlandJournalStore;
import mmm.simulation.persistence.FarmlandJournalStore.Change;
import mmm.simulation.persistence.FarmlandJournalStore.Journal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmlandBlock;

/** Transfers real region water into finite vanilla soil slots through the existing region HEAD. */
public final class FarmlandMoistureOperations {
	public enum FaultPoint { NONE, AFTER_PREPARED, AFTER_COMMIT_READY, AFTER_HEAD, AFTER_WORLD, AFTER_PERSIST }
	private final MinecraftServer server;
	private final FluidRegionManager regions;
	private final FluidRegionOperations operations;
	private final MinecraftRegionAvailability availability;
	private final MinecraftPendingPhaseWorkBarrier phaseWork;
	private final CommittedWorldRegistries world;
	private final FluidSnapshotPublisher snapshots;
	private final FarmlandJournalStore store;
	private final Map<UUID,Journal> pending=new LinkedHashMap<>();
	private final Map<UUID,String> reported=new HashMap<>();
	private final Map<UUID,Scan> scans=new HashMap<>();
	private boolean scanFailed;
	private FaultPoint fault=FaultPoint.NONE;
	private BlockPos writingPosition;
	private ServerLevel writingLevel;
	private net.minecraft.world.level.block.state.BlockState writingState;
	private static final class Scan { long nextTick; long cursor; }

	FarmlandMoistureOperations(MinecraftServer server,Path root,FluidRegionManager regions,
			FluidRegionOperations operations,MinecraftRegionAvailability availability,
			MinecraftPendingPhaseWorkBarrier phaseWork,CommittedWorldRegistries world,FluidSnapshotPublisher snapshots) {
		this.server=server; this.regions=regions; this.operations=operations; this.availability=availability;
		this.phaseWork=phaseWork; this.world=world; this.snapshots=snapshots; store=new FarmlandJournalStore(root);
		try {
			for(var journal:store.discover()) {
				if(pending.values().stream().anyMatch(other->other.regionId().equals(journal.regionId())))
					throw new IOException("Multiple farmland WALs identify the same region");
				pending.put(journal.operationId(),journal);
			}
		} catch(IOException | RuntimeException failure) {
			scanFailed=true; operations.recordIssue("Farmland WAL discovery: "+failure.getMessage());
		}
	}
	public boolean hasPending(UUID id) {
		return scanFailed || pending.values().stream().anyMatch(journal->journal.regionId().equals(id));
	}
	public void setFault(FaultPoint point) { fault=Objects.requireNonNull(point); }
	/** Keep the durable before/after slot stable until its WAL is completed, including across chunk reload. */
	public boolean allowsBlockChange(ServerLevel level,BlockPos position,net.minecraft.world.level.block.state.BlockState state) {
		if(level==writingLevel && position.equals(writingPosition) && state.equals(writingState)) return true;
		if(scanFailed && (state.is(Blocks.FARMLAND) || level.getBlockState(position).is(Blocks.FARMLAND))) {
			for(var region:regions.regions()) {
				if(!region.controllerManaged() || !region.dimension().equals(level.dimension())) continue;
				var min=region.footprint().minimumInterior(); var max=region.footprint().maximumInterior();
				if(position.getX()>=min.getX()-1 && position.getX()<=max.getX()+1
						&& position.getY()>=min.getY()-1 && position.getY()<=max.getY()
						&& position.getZ()>=min.getZ()-1 && position.getZ()<=max.getZ()+1) return false;
			}
		}
		if(pending.isEmpty()) return true;
		String dimension=level.dimension().identifier().toString();
		return pending.values().stream().noneMatch(journal->journal.protects(dimension,position.asLong()));
	}
	private void fault(FaultPoint point) throws IOException {
		if(fault==point) { fault=FaultPoint.NONE; throw new IOException("Injected farmland fault: "+point); }
	}

	/** false retains the region at its transaction barrier; operator pause never replenishes soil. */
	public boolean tickAvailable(ServerLevel level,FluidRegion region) {
		if(hasPending(region.id())) return false;
		if(region.paused() || !region.controllerManaged() || !region.solverLoaded()) return true;
		var scan=scans.computeIfAbsent(region.id(),ignored->new Scan());
		long tick=level.getGameTime(); if(tick<scan.nextTick) return true; scan.nextTick=tick+20;
		var footprint=region.footprint(); var min=footprint.minimumInterior(); var max=footprint.maximumInterior();
		int sx=max.getX()-min.getX()+3, sy=max.getY()-min.getY()+2, sz=max.getZ()-min.getZ()+3;
		long count=(long)sx*sy*sz; var plots=new ArrayList<BlockPos>();
		for(int checked=0;checked<Math.min(256L,count) && plots.size()<16;checked++) {
			long index=scan.cursor++%count;
			var position=new BlockPos(min.getX()-1+(int)(index%sx),min.getY()-1+(int)(index/sx%sy),min.getZ()-1+(int)(index/sx/sy));
			if(!level.hasChunkAt(position) || !MinecraftGroupAvailability.boxAvailable(region,new net.minecraft.world.phys.AABB(position).inflate(1))) continue;
			var state=level.getBlockState(position);
			if(state.is(Blocks.FARMLAND) && state.getValue(FarmlandBlock.MOISTURE)<7
					&& !region.domain().waterContacts(faces(region,position)).isEmpty()) plots.add(position);
		}
		if(plots.isEmpty()) return true;
		try {
			if(!availability.ensure(level,region) || !phaseWork.settle(level,region)) return false;
			FluidDomain candidate=FluidDomain.restoreAuthoritativeState(region.domain().captureAuthoritativeState());
			var changes=new ArrayList<Change>();
			for(var position:plots) {
				var state=level.getBlockState(position); if(!state.is(Blocks.FARMLAND)) continue;
				int before=state.getValue(FarmlandBlock.MOISTURE);
				var faces=faces(region,position);
				long available=Math.round(candidate.waterContacts(faces).stream().mapToDouble(FluidDomain.WaterContact::waterVolumeCubicMetres).sum()*1e9);
				int levels=(int)Math.min(7-before,available/FarmlandJournalStore.NANOS_PER_LEVEL);
				if(levels<=0) continue;
				var moved=candidate.extractContactWater(faces,levels*FarmlandJournalStore.NANOS_PER_LEVEL);
				if(!moved.transferred()) continue;
				List<ChemicalTransferLedger.Group> water=moved.ledger().groups();
				if(water.isEmpty()) water=List.of(new ChemicalTransferLedger.Group("mmm:water",
						moved.portions().stream().mapToDouble(portion->portion.volume()).sum(),
						moved.portions().stream().mapToLong(portion->portion.massUnits()).sum(),0,Map.of()));
				changes.add(new Change(position.asLong(),before,before+levels,moved.actualNanos(),water,
						candidate.hasThermalState(),moved.internalEnergy()));
			}
			if(changes.isEmpty()) return true;
			// One flush publishes every external preimage before its recovery record.
			level.save(null,true,false);
			long base=operations.committedGeneration(region.id());
			var journal=new Journal(UUID.randomUUID(),region.id(),level.dimension().identifier().toString(),
					world.profile().profileFingerprint(),base,Math.addExact(base,1),-1,CrossDomainRecovery.JournalPhase.PREPARED,changes);
			pending.put(journal.operationId(),journal); store.write(journal); fault(FaultPoint.AFTER_PREPARED);
			var staged=operations.stageContainerCheckpoint(region,candidate,journal.targetGeneration(),region.paused());
			journal=journal.committed(staged.manifestChecksum()); store.write(journal); pending.put(journal.operationId(),journal);
			fault(FaultPoint.AFTER_COMMIT_READY); operations.commitStagedLifecycleCheckpoint(staged); fault(FaultPoint.AFTER_HEAD);
			region.adoptLifecycleCandidateDomain(candidate,journal.targetGeneration(),region.paused());
			if(!recover(journal)) return false;
			snapshots.sendToLevel(level,region); return true;
		} catch(IOException | RuntimeException failure) {
			region.setAvailabilityFailure(hasPending(region.id())?RegionAvailabilityReason.PENDING_WAL:RegionAvailabilityReason.NUMERICAL_PAUSE,
					"Farmland transfer: "+failure.getMessage());
			MmmMod.LOGGER.error("Farmland transfer for region {} stopped: {}",region.id(),failure.getMessage());
			return false;
		}
	}

	/** Four actual-height sides and the top at the solver's fixed collision resolution. */
	static List<FluidDomain.WaterContactFace> faces(FluidRegion region,BlockPos soil) {
		double x=soil.getX()-region.origin().getX(),y=soil.getY()-region.origin().getY(),z=soil.getZ()-region.origin().getZ();
		return List.of(new FluidDomain.WaterContactFace(0,x,y,y+15.0/16,z,z+1,-1),
				new FluidDomain.WaterContactFace(0,x+1,y,y+15.0/16,z,z+1,1),
				new FluidDomain.WaterContactFace(2,z,x,x+1,y,y+15.0/16,-1),
				new FluidDomain.WaterContactFace(2,z+1,x,x+1,y,y+15.0/16,1),
				new FluidDomain.WaterContactFace(1,y+1,z,z+1,x,x+1,1));
	}
	public void recoverIndexed() {
		scans.keySet().removeIf(id->regions.region(id)==null);
		for(var journal:List.copyOf(pending.values())) {
			try { recover(journal); }
			catch(IOException | RuntimeException failure) {
				var region=regions.region(journal.regionId());
				if(region!=null) region.setAvailabilityFailure(RegionAvailabilityReason.PENDING_WAL,"Farmland WAL: "+failure.getMessage());
				String message=failure.getMessage();
				if(!Objects.equals(reported.put(journal.operationId(),message),message))
					MmmMod.LOGGER.error("Farmland WAL {} preserved: {}",journal.operationId(),message);
			}
		}
	}
	private boolean recover(Journal journal) throws IOException {
		if(!journal.profile().equals(world.profile().profileFingerprint())) throw new IOException("Farmland WAL world profile differs");
		var region=regions.region(journal.regionId()); if(region==null) throw new IOException("Farmland WAL region is absent");
		ServerLevel level=null;
		for(var candidate:server.getAllLevels()) if(candidate.dimension().identifier().toString().equals(journal.dimensionId())) level=candidate;
		var truths=truths(level,journal);
		var boundary=new CrossDomainRecovery.Boundary(operations.committedGeneration(region.id()),operations.committedManifestChecksum(region.id()),
				journal.baseGeneration(),journal.targetGeneration(),journal.targetChecksum()>=0,journal.targetChecksum(),journal.phase(),false);
		var decision=CrossDomainRecovery.decide(boundary,truths);
		switch(decision.outcome()) {
			case WAIT_UNAVAILABLE -> { return false; }
			case LOCKED, ROLLBACK_BEFORE -> throw new IOException("Farmland recovery: "+decision.reason());
			case DISCARD_UNCOMMITTED -> { finish(journal,region); return true; }
			case CONVERGE_AFTER -> {
				for(int index:decision.beforeMembers()) {
					var change=journal.changes().get(index); var position=BlockPos.of(change.position());
					writingLevel=level; writingPosition=position; writingState=level.getBlockState(position).setValue(FarmlandBlock.MOISTURE,change.after());
					try { level.setBlock(position,writingState,3); }
					finally { writingLevel=null; writingPosition=null; writingState=null; }
				}
				fault(FaultPoint.AFTER_WORLD); level.save(null,true,false);
				if(!truths(level,journal).stream().allMatch(truth->truth==CrossDomainRecovery.ExternalTruth.AFTER))
					throw new IOException("Farmland durable world readback differs");
				fault(FaultPoint.AFTER_PERSIST);
				operations.adoptCommittedContainerCheckpoint(region,journal.baseGeneration(),journal.targetGeneration());
				finish(journal,region); return true;
			}
		}
		throw new AssertionError();
	}
	private static List<CrossDomainRecovery.ExternalTruth> truths(ServerLevel level,Journal journal) {
		var result=new ArrayList<CrossDomainRecovery.ExternalTruth>();
		for(var change:journal.changes()) {
			var position=BlockPos.of(change.position()); boolean available=level!=null && level.hasChunkAt(position);
			int moisture=available && level.getBlockState(position).is(Blocks.FARMLAND)?level.getBlockState(position).getValue(FarmlandBlock.MOISTURE):-1;
			result.add(CrossDomainRecovery.classify(available,moisture==change.before(),moisture==change.after(),true));
		}
		return List.copyOf(result);
	}
	private void finish(Journal journal,FluidRegion region) throws IOException {
		store.delete(journal.operationId()); pending.remove(journal.operationId()); reported.remove(journal.operationId());
		region.setAvailabilityFailure(null); region.wakeForActivity();
	}
}
