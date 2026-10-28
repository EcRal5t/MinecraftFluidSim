package mmm.simulation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mmm.block.FluidPortBlock;
import mmm.block.MmmBlocks;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.controller.LifecycleOperation;
import mmm.fluid.FluidDomain;
import mmm.transfer.ChemicalContents;
import mmm.transfer.ContainerFluidLedger;
import mmm.transfer.FluidMixture;
import mmm.units.Mass;
import mmm.worldprofile.WorldCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** Runtime-owned demo regions and their normal persistent inventory operations. */
final class DemoTankOperations {
    enum Recipe { WATER, OIL_WATER, ACID_BASE }
    private final WorldStartupCoordinator.Result startup;
    private final CommittedWorldRegistries world;
    private final FluidRegionManager regions;
    private final FluidRegionOperations operations;
    private final MinecraftRegionLifecycle lifecycle;
    private final MinecraftRegionAvailability availability;
    private final MinecraftPendingPhaseWorkBarrier phaseWork;
    private final Map<UUID, FluidRegion> demos = new HashMap<>();

    DemoTankOperations(WorldStartupCoordinator.Result startup, CommittedWorldRegistries world,
            FluidRegionManager regions, FluidRegionOperations operations, MinecraftRegionLifecycle lifecycle,
            MinecraftRegionAvailability availability, MinecraftPendingPhaseWorkBarrier phaseWork) {
        this.startup = startup; this.world = world; this.regions = regions; this.operations = operations;
        this.lifecycle = lifecycle; this.availability = availability; this.phaseWork = phaseWork;
    }

    /** Called only after this runtime successfully created and admitted the demonstration region. */
    void register(FluidRegion region) {
        if (region == null || regions.region(region.id()) != region || region.footprint() == null
                || region.footprint().blockSizeX() != 2 || region.footprint().blockSizeY() != 2
                || region.footprint().blockSizeZ() != 2)
            throw new IllegalArgumentException("演示区域身份或尺寸不匹配");
        demos.put(region.id(), region);
    }

    /** Returns null on success; a failed durable stage preserves its published state for recovery. */
    String apply(ServerLevel level, FluidRegion region, Recipe recipe) {
        if (recipe == null) return "演示配方缺失";
        String error = check(level, region);
        if (error != null) return error;
        error = recipeCapabilityFailure(recipe);
        if (error != null) return error;
        if (!availability.ensureForInventoryDecrease(level, region)) return region.availabilityFailure();
        int[] port = recipe == Recipe.WATER ? null : portCell(level, region);
        if (recipe != Recipe.WATER && port == null) return "演示接口未连接到当前区域";
        try {
            region.setPaused(true);
            if (!phaseWork.settle(level, region)) return "无法结算待处理的相变运动";
            FluidDomain domain = prepareCandidate(world, region, recipe, port);
            var committed = lifecycle.commitCandidate(level, region, domain, null);
            if (!committed.committed() || region.availabilityReason() == mmm.api.RegionAvailabilityReason.HEAD_INVALID)
                return committed.message().getString();
            return null;
        } catch (RuntimeException failure) {
            return "演示配方未完成：" + failure.getMessage();
        }
    }

    String recipeCapabilityFailure(mmm.network.DemoPreset preset) {
        return recipeCapabilityFailure(switch (preset) {
            case WATER -> Recipe.WATER;
            case MATERIAL -> Recipe.OIL_WATER;
            case CHEMISTRY -> Recipe.ACID_BASE;
            default -> throw new IllegalArgumentException("Unknown tank recipe");
        });
    }
    private String recipeCapabilityFailure(Recipe recipe) {
        if (recipe != Recipe.WATER && !world.hasCapability(WorldCapability.MATERIALS)) return "演示需要材料功能";
        if (recipe == Recipe.ACID_BASE && !world.hasCapability(WorldCapability.CHEMISTRY)) return "酸碱演示需要化学功能";
        return null;
    }

    /** Builds a complete private replacement before publishing any inventory changes. */
    static FluidDomain prepareCandidate(CommittedWorldRegistries world, FluidRegion region, Recipe recipe, int[] port) {
        return prepareCandidate(world, region, recipe, port, 1_000_000L);
    }

    static FluidDomain prepareCandidate(CommittedWorldRegistries world, FluidRegion region, Recipe recipe, int[] port,
            long reagentQuanta) {
        FluidDomain domain;
        try (var captured = region.captureSolverState()) { domain = FluidDomain.restoreAuthoritativeState(captured.exportState()); }
        domain.commitOperatorClear(domain.planOperatorClear());
        domain.commitOperatorVentSealedGas(domain.planOperatorVentSealedGas());
        populate(world, region.lifecycleCandidate(domain, true), recipe, port, reagentQuanta);
        return domain;
    }

    /** Plans actual external-source container transfers on a private region candidate. */
    static void populate(CommittedWorldRegistries world, FluidRegion candidate, Recipe recipe, int[] port) {
        populate(world, candidate, recipe, port, 1_000_000L);
    }

    private static void populate(CommittedWorldRegistries world, FluidRegion candidate, Recipe recipe, int[] port,
            long reagentQuanta) {
        if (recipe == null || recipe != Recipe.WATER && (port == null || port.length != 3))
            throw new IllegalArgumentException("演示配方或接口缺失");
        FluidDomain domain = candidate.domain();
        restoreAmbientGas(world, domain);
        if (recipe == Recipe.WATER) return;
        int[] far = {candidate.sizeX() - 1 - port[0], port[1], candidate.sizeZ() - 1 - port[2]};
        if (recipe == Recipe.OIL_WATER) {
            var oil = new FluidMixture(List.of(new FluidMixture.Component("mmm:oil",
                ContainerFluidLedger.NANOS_PER_BLOCK, Mass.fromKilograms(world.resolveMaterial("mmm:oil").referenceDensity()))));
            for (int bucket = 0; bucket < 2; bucket++) {
                requireFull(MinecraftContainerTransferOperations.pourIntoCell(world, candidate, port,
                    ContainerFluidLedger.NANOS_PER_BLOCK, 6, domain.materialRegistry().handle("mmm:water"), true, null));
                requireFull(MinecraftContainerTransferOperations.pourMaterialIntoCell(world, candidate, far, oil, 6, true, null));
            }
        } else {
            requireFull(MinecraftContainerTransferOperations.pourChemicalIntoCell(world, candidate, port,
                reagent(world, "mmm:hcl", reagentQuanta), 6, true, null));
            requireFull(MinecraftContainerTransferOperations.pourChemicalIntoCell(world, candidate, far,
                reagent(world, "mmm:naoh", reagentQuanta), 6, true, null));
        }
    }

    /** Refill every finite cavity from the ambient source at its current open volume. */
    private static void restoreAmbientGas(CommittedWorldRegistries world, FluidDomain domain) {
        var gas = domain.gasBoundary();
        if (!gas.topologyInitialized()) domain.bootstrapGasState(
                world.hasCapability(WorldCapability.CHEMISTRY) ? world.chemicalRegistry().fingerprint() : "");
        for (int node : gas.nodeIds()) {
            long target = mmm.fluid.air.AirNetwork.nanos(gas.availableVolume(node));
            long current = gas.standardVolumeNanos(node);
            if (current == target && (current == 0 || Math.abs(domain.gasNodeTemperatureK(node)
                    - mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K) < 1e-8)) continue;
            if (domain.hasThermalState()) {
                if (current > 0) domain.editGas(node, null, current, mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K, true);
                if (target > 0) domain.editGas(node, null, target, mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K, false);
            } else {
                domain.setGasNodeStandardVolume(node, 0);
                domain.setGasNodeStandardVolume(node, gas.availableVolume(node));
            }
        }
    }

    /** Explicitly removes only a region registered by this runtime; block cleanup belongs to its creator. */
    String remove(ServerPlayer player, FluidRegion region) {
        if (player == null || !player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_ADMIN))
            return "移除演示水箱需要管理员权限";
        String error = check(player.level(), region);
        if (error != null) return error;
        BlockPos controller = region.footprint().controllerPosition();
        if (player.distanceToSqr(controller.getX() + .5, controller.getY() + .5, controller.getZ() + .5) > 64.0)
            return "玩家距离演示控制器过远";
        error = clear(player.level(), region);
        if (error != null) return error;
        var removed = lifecycle.removeDemo(player, region);
        if (!removed.committed()) return removed.message().getString();
        demos.remove(region.id());
        return null;
    }

    private String clear(ServerLevel level, FluidRegion region) {
        if (!availability.ensureForInventoryDecrease(level, region)) return region.availabilityFailure();
        region.setPaused(true);
        if (!phaseWork.settle(level, region)) return "无法结算待处理的相变运动";
        for (var operation : List.of(LifecycleOperation.EMPTY_CONDENSED, LifecycleOperation.VENT_SEALED_GAS)) {
            var result = lifecycle.commitInventory(level, region, operation);
            if (!result.committed() || region.availabilityReason() == mmm.api.RegionAvailabilityReason.HEAD_INVALID)
                return result.message().getString();
        }
        return null;
    }

    private String check(ServerLevel level, FluidRegion region) {
        if (level == null || region == null || demos.get(region.id()) != region || regions.region(region.id()) != region
                || !region.dimension().equals(level.dimension())) return "该区域不是本次运行创建的演示水箱";
        if (!level.getServer().isSameThread()) throw new IllegalStateException("演示水箱只能在服务器线程操作");
        if (!startup.authoritativeWritesReady()) return "世界存档尚未准备好";
        if (availability.hasPending(region.id()) || operations.ownershipLockDescription(region) != null)
            return "演示水箱存在未完成的事务";
        if (!level.getBlockState(region.footprint().controllerPosition()).is(MmmBlocks.FLUID_CONTROLLER))
            return "演示控制器已改变";
        return null;
    }

    private static int[] portCell(ServerLevel level, FluidRegion region) {
        for (long packed : region.footprint().boundaryBlocks()) {
            BlockPos position = BlockPos.of(packed);
            var state = level.getBlockState(position);
            if (!state.is(MmmBlocks.FLUID_PORT)) continue;
            var outward = state.getValue(FluidPortBlock.FACING);
            var target = FluidWorldTargetResolver.resolvePortTarget(List.of(region), level.dimension(),
                    position, outward, outward.getOpposite());
            if (target != null) return FluidWorldTargetResolver.cellAtInteriorBlock(region, target.interiorBlock());
        }
        return null;
    }

    private static ChemicalContents reagent(CommittedWorldRegistries world, String species, long reagentQuanta) {
        var chemistry = world.chemicalRegistry();
        var amounts = Map.of("mmm:h2o", 50_000_000_000L, species, reagentQuanta);
        long mass = Mass.fromKilograms(1000.0);
        var withoutResidual = ChemicalMixtureRecord.fromStableAmounts(chemistry, amounts, 0);
        var mixture = ChemicalMixtureRecord.fromStableAmounts(chemistry, amounts,
                Math.subtractExact(mass, withoutResidual.speciesMassUnits(chemistry)));
        var ledger = new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water", 1.0, mass,
                mixture.massResidualUnits(), mixture.stableAmounts(chemistry))));
        return ChemicalContents.fromLedger(ledger, chemistry, ContainerFluidLedger.NANOS_PER_BLOCK);
    }

    private static void requireFull(MinecraftContainerTransferOperations.TransferOutcome outcome) {
        if (outcome == null || !outcome.transferred() || outcome.resourceAmount() != ContainerFluidLedger.NANOS_PER_BLOCK)
            throw new IllegalStateException(outcome == null ? "容器没有注入" : outcome.message());
    }
}
