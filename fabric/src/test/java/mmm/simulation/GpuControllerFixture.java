package mmm.simulation;

import mmm.application.ContainerRecoveryCoordinator;
import mmm.fluid.BuiltinFluidMaterials;
import mmm.item.ContainerItemMapper;
import mmm.item.ContainerItemStacks;
import mmm.recovery.CrossDomainRecovery;
import mmm.simulation.controller.FluidBoundaryScanner;
import mmm.simulation.persistence.ContainerJournalFileStore;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalStateFunction;
import mmm.transfer.ContainerTransferActor;
import mmm.transfer.ContainerTransferJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Offline world adapter for the actual controller creation and container transaction. */
final class GpuControllerFixture {
    final FluidRegion region;
    final int[] portCell;
    final MinecraftContainerTransactionAdapter transactions;
    final ContainerRecoveryCoordinator recovery;
    final ContainerJournalFileStore journal;
    final Actor actor;
    final CommittedWorldRegistries world;

    private GpuControllerFixture(FluidRegion region, int[] portCell, MinecraftContainerTransactionAdapter transactions,
            ContainerRecoveryCoordinator recovery, ContainerJournalFileStore journal, Actor actor, CommittedWorldRegistries world) {
        this.region = region; this.portCell = portCell; this.transactions = transactions;
        this.recovery = recovery; this.journal = journal; this.actor = actor; this.world = world;
    }

    static GpuControllerFixture create(WorldStartupCoordinator.Result startup, CommittedWorldRegistries world,
            FluidRegionManager manager, FluidRegionOperations operations) throws IOException {
        initializeItems();
        var layout = ControllerDemoLayout.at(new BlockPos(16, 64, 16), Direction.SOUTH);
        Set<Long> shell = new HashSet<>();
        layout.shell().forEach(position -> shell.add(position.asLong()));
        var worldView = new FluidBoundaryScanner.ScanWorld() {
            public boolean isInsideBuildHeight(BlockPos p) { return p.getY() >= -64 && p.getY() < 320; }
            public boolean isChunkLoaded(int x, int z) { return true; }
            public boolean isBoundary(BlockPos p) { return shell.contains(p.asLong()); }
        };
        var scan = FluidBoundaryScanner.scan(layout.controller(), worldView, 512);
        if (!scan.succeeded()) throw new IllegalStateException("controller scan: " + scan.failure());
        var dimension = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("mmm", "gpu_controller"));
        var result = ControllerRegionCreation.create(manager, dimension, scan.footprint(), true, false,
                () -> null, candidate -> world.bindConfiguredState(candidate.domain(), startup.config().maximumThermalSubstepsPerStep()),
                operations::checkpoint);
        if (result.failure() != null) throw new IllegalStateException("controller creation: " + result.failure());
        FluidRegion region = result.region();
        var target = FluidWorldTargetResolver.resolvePortTarget(List.of(region), dimension, layout.port(),
                layout.portOutward(), layout.portOutward().getOpposite());
        if (target == null) throw new IllegalStateException("controller port did not resolve its interior");
        int[] cell = FluidWorldTargetResolver.cellAtInteriorBlock(region, target.interiorBlock());
        if (cell == null) throw new IllegalStateException("controller port interior cell is unavailable");
        var journal = new ContainerJournalFileStore(startup.worldRoot());
        var actor = new Actor(startup.worldRoot().resolve("controller-fixture-actor.txt"), operations, manager, world);
        var recovery = new ContainerRecoveryCoordinator(journal, actor, Map.of(), Set.of());
        var transactions = new MinecraftContainerTransactionAdapter(manager, operations, recovery,
                world.profile().profileFingerprint(), world.thermalRegistryFingerprint());
        return new GpuControllerFixture(region, cell, transactions, recovery, journal, actor, world);
    }

    /** The offline Java launcher performs vanilla bootstrap without Fabric's registry setup. */
    private static synchronized void initializeItems() {
        if (componentsInitialized) return;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
		try {
			registerOffline(net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE,
					false, mmm.item.MmmComponents::register);
			registerOffline(net.minecraft.core.registries.BuiltInRegistries.ITEM,
					true, mmm.item.MmmItems::register);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("Cannot initialize offline Minecraft container registries", failure);
		}
		initializeContainerDefaults();
		componentsInitialized = true;
	}

	/** Apply the registered vanilla and mod initializers for the actual container items used here. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void initializeContainerDefaults() {
		try {
			var source = net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS;
			var entries = net.minecraft.core.component.DataComponentInitializers.class.getDeclaredField("initializers");
			entries.setAccessible(true);
			var selected = new net.minecraft.core.component.DataComponentInitializers();
			for (Object entry : (java.util.List<?>) entries.get(source)) {
				var keyMethod = entry.getClass().getDeclaredMethod("key");
				var initializerMethod = entry.getClass().getDeclaredMethod("initializer");
				keyMethod.setAccessible(true); initializerMethod.setAccessible(true);
				var key = (net.minecraft.resources.ResourceKey<?>) keyMethod.invoke(entry);
				var id = key.identifier();
				if (!key.registryKey().equals(net.minecraft.core.registries.Registries.ITEM)
						|| !(id.getNamespace().equals("mmm") || id.equals(Items.BUCKET.builtInRegistryHolder().key().identifier())
						|| id.equals(Items.WATER_BUCKET.builtInRegistryHolder().key().identifier()))) continue;
				selected.add((net.minecraft.resources.ResourceKey) key,
						(net.minecraft.core.component.DataComponentInitializers.Initializer) initializerMethod.invoke(entry));
			}
			var lookup = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
					net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
			selected.build(lookup).forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("Cannot initialize offline container item defaults", failure);
		}
	}

	private static void registerOffline(net.minecraft.core.Registry<?> registry, boolean intrusive, Runnable register)
			throws ReflectiveOperationException {
		Class<?> mapped = net.minecraft.core.MappedRegistry.class;
		var frozen = mapped.getDeclaredField("frozen");
		var holders = mapped.getDeclaredField("unregisteredIntrusiveHolders");
		var tags = mapped.getDeclaredField("allTags");
		frozen.setAccessible(true); holders.setAccessible(true); tags.setAccessible(true);
		boolean originalFrozen = frozen.getBoolean(registry);
		Object originalHolders = holders.get(registry), originalTags = tags.get(registry);
		Class<?> tagSet = Class.forName("net.minecraft.core.MappedRegistry$TagSet");
		var unbound = tagSet.getDeclaredMethod("unbound");
		unbound.setAccessible(true);
		Object emptyTags = unbound.invoke(null);
		frozen.setBoolean(registry, false);
		if (intrusive) holders.set(registry, new java.util.IdentityHashMap<>());
		try {
			register.run();
		} catch (RuntimeException | Error failure) {
			try {
				holders.set(registry, originalHolders);
				frozen.setBoolean(registry, originalFrozen);
			} catch (ReflectiveOperationException cleanup) { failure.addSuppressed(cleanup); }
			throw failure;
		}
		try {
			tags.set(registry, emptyTags);
			registry.freeze();
		} finally {
			tags.set(registry, originalTags);
		}
	}

    private static boolean componentsInitialized;

    void pour() throws IOException {
        ItemStack water = new ItemStack(Items.WATER_BUCKET);
        world.attachDebugLiquidThermalContents(water);
        transfer(water);
        if (region.domain().unitScale().cubicMeters(region.domain().totalVolumeExact()) <= 0)
            throw new IllegalStateException("controller pour did not publish liquid inventory");
        var function = new ThermalStateFunction(world.thermalRegistry());
        int handle = world.thermalRegistry().materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id());
        for (var stock : region.domain().exportLiquidCellState().physicalStocks()) {
            double temperature = function.stateForInternalEnergy(handle, stock.massKilograms(),
                    stock.mixture() == null ? Map.of() : stock.mixture().stableAmounts(world.chemicalRegistry()),
                    stock.internalEnergy()).temperatureK();
            if (Math.abs(temperature - 293.15) > 1.0e-4)
                throw new IllegalStateException("controller water bucket temperature=" + temperature);
        }
    }

    void scoop() throws IOException { transfer(new ItemStack(Items.BUCKET)); }

    private void transfer(ItemStack before) throws IOException {
        var domain = region.domain();
        System.out.printf("stage=controller-container-input kind=%s cell=%d,%d,%d pending_phase=%s near_water=%.9g eligible_water=%.9g total_liquid=%.9g%n",
                before.getItem(), portCell[0], portCell[1], portCell[2], domain.hasPendingPhaseMotionWork(),
                domain.exactVolumeNear(portCell[0], portCell[1], portCell[2], 6, 0),
                domain.containerExtractableVolumeNear(portCell[0], portCell[1], portCell[2], 6, 0), domain.totalVolumeExact());
        actor.current = before.copy();
        actor.persist();
        var resolution = MinecraftContainerTransferPlan.resolve(transactions, world, region, portCell, before, false);
        if (!resolution.resolved()) throw new IllegalStateException("controller container plan: " + resolution.diagnostic());
        var result = resolution.plan().executeManagedForRegion(actor.identity, before);
        if (result.outcome() == null || !result.outcome().transferred())
            throw new IllegalStateException("controller container commit: " + (result.outcome() == null ? "no outcome" : result.outcome().message()));
        if (result.transactionId() == null || !journal.discover().transactions().contains(result.transactionId()))
            throw new IllegalStateException("controller container WAL was not retained until actor persistence");
        actor.current = result.outcome().stack().copy();
        transactions.completeCommitted(result.transactionId());
        if (recovery.hasPending(region.id()) || journal.discover().transactions().contains(result.transactionId()))
            throw new IllegalStateException("controller container WAL did not converge: " + actor.failure);
        if (!actor.read().equals(ContainerItemMapper.fingerprint(actor.current)))
            throw new IllegalStateException("controller actor after-item was not durably stored");
    }

    static final class Actor implements ContainerRecoveryCoordinator.RecoveryPort {
        final Path path;
        final FluidRegionOperations operations;
        final FluidRegionManager manager;
        final CommittedWorldRegistries world;
        final ContainerTransferActor.PlayerHand identity = ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN);
        ItemStack current;
        String failure;
        Actor(Path path, FluidRegionOperations operations, FluidRegionManager manager, CommittedWorldRegistries world) {
            this.path = path; this.operations = operations; this.manager = manager; this.world = world;
        }
        void persist() throws IOException {
            var value = ContainerItemMapper.fingerprint(current);
            try (var out = new java.io.DataOutputStream(Files.newOutputStream(path))) {
                out.writeUTF(value.kind().name()); out.writeLong(value.nanos()); out.writeLong(value.solidVolumeSvq());
                writeOptional(out, value.contents()); out.writeLong(value.massUnits());
                writeOptional(out, value.thermalContents()); writeOptional(out, value.profileFingerprint());
            }
            try (var file = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) {
                file.force(true);
            }
        }
        ContainerTransferJournal.ItemFingerprint read() throws IOException {
            try (var in = new java.io.DataInputStream(Files.newInputStream(path))) {
                return new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.valueOf(in.readUTF()),
                        in.readLong(), in.readLong(), readOptional(in), in.readLong(), readOptional(in), readOptional(in));
            }
        }
        private static void writeOptional(java.io.DataOutputStream out, String value) throws IOException {
            out.writeBoolean(value != null); if (value != null) out.writeUTF(value);
        }
        private static String readOptional(java.io.DataInputStream in) throws IOException {
            return in.readBoolean() ? in.readUTF() : null;
        }
        public void validate(ContainerTransferJournal journal) {
            journal.validateProfileFingerprint(world.profile().profileFingerprint());
            if (journal.requiresThermalRegistry()) journal.validateThermalRegistry(world.thermalRegistryFingerprint());
            if (journal.requiresChemicalRegistry()) journal.validateChemicalRegistry(world.chemicalRegistry());
            if (journal.requiresGranularRegistry()) journal.validateGranularRegistry(world.granularRegistry(), world.chemicalRegistry());
        }
        public long committedGeneration(UUID id) throws IOException { return operations.committedGeneration(id); }
        public long committedManifestChecksum(UUID id) throws IOException { return operations.committedManifestChecksum(id); }
        public CrossDomainRecovery.ExternalTruth classifyActor(ContainerTransferJournal journal) {
            var value = ContainerItemMapper.fingerprint(current);
            if (value.equals(journal.beforeItem())) return CrossDomainRecovery.ExternalTruth.BEFORE;
            if (value.equals(journal.afterItem())) return CrossDomainRecovery.ExternalTruth.AFTER;
            return CrossDomainRecovery.ExternalTruth.THIRD;
        }
        public ContainerRecoveryCoordinator.Convergence applyAfterItem(ContainerTransferJournal journal) {
            current = ContainerItemMapper.toStack(journal.afterItem());
            return ContainerRecoveryCoordinator.Convergence.APPLIED;
        }
        public void persistAfterItem(ContainerTransferActor ignored) throws IOException { persist(); }
        public CrossDomainRecovery.ExternalTruth verifyAfterItem(ContainerTransferJournal journal) {
            try { return read().equals(journal.afterItem())
                    ? CrossDomainRecovery.ExternalTruth.AFTER : CrossDomainRecovery.ExternalTruth.THIRD; }
            catch (IOException failure) { throw new IllegalStateException("actor readback failed", failure); }
        }
        public void adoptRecoveredCheckpoint(ContainerTransferJournal journal) throws IOException {
            var region = manager.region(journal.regionId());
            if (region == null) throw new IOException("controller region missing during recovery");
            operations.adoptCommittedContainerCheckpoint(region, journal.baseGeneration(), journal.targetGeneration());
        }
        public void publishCommitted(ContainerTransferJournal journal) { }
        public void report(ContainerRecoveryCoordinator.Report level, String message, Throwable cause) {
            if (level == ContainerRecoveryCoordinator.Report.ERROR) failure = message + (cause == null ? "" : ": " + cause);
        }
    }
}
