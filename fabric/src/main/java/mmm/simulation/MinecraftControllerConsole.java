package mmm.simulation;

import mmm.application.ControllerQueryUseCase;
import mmm.application.ThermalReservoirUseCase;
import mmm.application.command.ControllerSummaryCommand;
import mmm.application.result.ControllerSummaryResult;
import mmm.block.MmmBlocks;
import mmm.block.ThermalReservoirBlockEntity;
import mmm.controller.ControllerConsoleAction;
import mmm.controller.ControllerSummary;
import mmm.controller.LifecycleOperation;
import mmm.fluid.CellCarrierInventoryView;
import mmm.simulation.controller.FluidRegionFootprint;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalReservoirSummary;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/** Owns bounded Minecraft controller, reservoir and field-probe interactions. */
public final class MinecraftControllerConsole {
	private final FluidWorldTargetResolver targets;
	private final ControllerQueryUseCase controllerQueries;
	private final MinecraftRegionLifecycle regionLifecycle;
	private final ThermalReservoirUseCase thermalReservoirs;
	private final MinecraftRegionTick regionTick;
	private final ThermalRegistry thermalRegistry;
	private final WorldStartupCoordinator.Result startup;
	private final FluidRegionManager regions;
	private final BooleanSupplier authoritativeWritesReady;
	private final BiFunction<ServerLevel, FluidRegion, Boolean> ensureRegionAvailable;

	MinecraftControllerConsole(
			FluidWorldTargetResolver targets,
			ControllerQueryUseCase controllerQueries,
			MinecraftRegionLifecycle regionLifecycle,
			ThermalReservoirUseCase thermalReservoirs,
			MinecraftRegionTick regionTick,
			ThermalRegistry thermalRegistry,
			WorldStartupCoordinator.Result startup,
			FluidRegionManager regions,
			BooleanSupplier authoritativeWritesReady,
			BiFunction<ServerLevel, FluidRegion, Boolean> ensureRegionAvailable
	) {
		this.targets = Objects.requireNonNull(targets, "World target resolver");
		this.controllerQueries = Objects.requireNonNull(controllerQueries, "Controller query use case");
		this.regionLifecycle = Objects.requireNonNull(regionLifecycle, "Region lifecycle");
		this.thermalReservoirs = Objects.requireNonNull(thermalReservoirs, "Thermal reservoir use case");
		this.regionTick = Objects.requireNonNull(regionTick, "Region tick adapter");
		this.thermalRegistry = thermalRegistry;
		this.startup = Objects.requireNonNull(startup, "World startup result");
		this.regions = Objects.requireNonNull(regions, "Region manager");
		this.authoritativeWritesReady = Objects.requireNonNull(
				authoritativeWritesReady, "Authoritative-write readiness");
		this.ensureRegionAvailable = Objects.requireNonNull(ensureRegionAvailable, "Region availability adapter");
	}

	/** Read-only companion to Query for cell-local solid/gas carriers. */
	public CarrierCellObservation observeCellCarriers(ServerLevel level, Vec3 interiorPoint) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Carrier observation is server-thread-only");
		FluidRegion region = targets.regionAtBlock(level, BlockPos.containing(interiorPoint));
		if (region == null || region.availabilityFailure() != null || !region.solverLoaded()) return null;
		double h = region.solverConfig().cellSize();
		int x = Mth.floor((interiorPoint.x - region.origin().getX()) / h);
		int y = Mth.floor((interiorPoint.y - region.origin().getY()) / h);
		int z = Mth.floor((interiorPoint.z - region.origin().getZ()) / h);
		if (x < 0 || x >= region.sizeX() || y < 0 || y >= region.sizeY() || z < 0 || z >= region.sizeZ()) {
			return null;
		}
		return new CarrierCellObservation(region.id(), region.networkGeneration(), x, y, z,
				region.domain().observeCellCarriers(x, y, z));
	}

	/** Returns the centre of the first liquid/granular/coherent cell along an already resolved interior ray. */
	public Vec3 firstCondensedProbePoint(ServerLevel level, Vec3 interiorPoint, Vec3 direction) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Probe traversal is server-thread-only");
		FluidRegion region = targets.regionAtBlock(level, BlockPos.containing(interiorPoint));
		if (region == null || region.availabilityFailure() != null || !region.solverLoaded()) return null;
		int[] cell = targets.firstCondensedCellAlong(region, interiorPoint, direction);
		if (cell == null) return null;
		double h = region.solverConfig().cellSize();
		return new Vec3(region.origin().getX() + (cell[0] + 0.5) * h,
				region.origin().getY() + (cell[1] + 0.5) * h,
				region.origin().getZ() + (cell[2] + 0.5) * h);
	}

	/** Snapshots bounded maintained ledgers without loading, waking, checkpointing or selecting. */
	public ControllerSummary controllerSummary(ServerPlayer player, BlockPos controllerPosition) {
		if (player == null || controllerPosition == null) return null;
		ServerLevel level = player.level();
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Controller summary is server-thread-only");
		if (player.distanceToSqr(controllerPosition.getX() + 0.5,
				controllerPosition.getY() + 0.5, controllerPosition.getZ() + 0.5) > 64.0) return null;
		if (!level.getBlockState(controllerPosition).is(MmmBlocks.FLUID_CONTROLLER)) return null;
		FluidRegion region = targets.controllerRegionAt(level, controllerPosition);
		if (region == null || !startup.profileProven()) return null;
		ControllerSummaryResult result = controllerQueries.query(
				new ControllerSummaryCommand(player.getUUID(), region.id()));
		return result.ok() ? result.summary() : null;
	}

	/** Re-resolves every client-supplied identity and consumes the one-use server token before acting. */
	public Component applyControllerConsoleAction(
			ServerPlayer player, UUID requestedRegionId, BlockPos controllerPosition,
			long summaryGeneration, ControllerConsoleAction action
	) {
		if (player == null || requestedRegionId == null || controllerPosition == null || action == null) {
			return Component.translatable("message.mmm.controller.request_identity_missing");
		}
		ServerLevel level = player.level();
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Controller actions are server-thread-only");
		double distanceSquared = player.distanceToSqr(controllerPosition.getX() + 0.5,
				controllerPosition.getY() + 0.5, controllerPosition.getZ() + 0.5);
		if (distanceSquared > 64.0) return Component.translatable("message.mmm.controller.too_far");
		if (!level.getBlockState(controllerPosition).is(MmmBlocks.FLUID_CONTROLLER)) {
			return Component.translatable("message.mmm.controller.target_gone");
		}
		FluidRegion region = targets.controllerRegionAt(level, controllerPosition);
		if (region == null || !region.id().equals(requestedRegionId)) {
			return Component.translatable("message.mmm.controller.owner_changed");
		}
		if (!controllerQueries.consumeActionToken(player.getUUID(), region.id(), summaryGeneration)) {
			return Component.translatable("message.mmm.controller.summary_expired");
		}

		return switch (action) {
			case SELECT -> regions.select(region.id())
					? null : Component.translatable("message.mmm.controller.region_gone");
			case REFRESH_AVAILABILITY -> ensureRegionAvailable.apply(level, region)
					? null : Component.translatable("message.mmm.controller.availability_still_blocked",
							region.availabilityFailure());
			case PAUSE -> regionLifecycle.changeRunIntent(player, region, true);
			case RESUME -> regionLifecycle.changeRunIntent(player, region, false);
		};
	}

	public MinecraftRegionLifecycle.PreviewResult previewControllerLifecycleOperation(
			ServerPlayer player, UUID requestedRegionId, BlockPos controllerPosition,
			long summaryGeneration, LifecycleOperation operation
	) {
		return regionLifecycle.preview(player, requestedRegionId, controllerPosition, summaryGeneration, operation);
	}

	public MinecraftRegionLifecycle.CommitResult confirmControllerLifecycleOperation(
			ServerPlayer player, UUID requestedRegionId, BlockPos controllerPosition,
			UUID nonce, LifecycleOperation operation
	) {
		return regionLifecycle.confirm(player, requestedRegionId, controllerPosition, nonce, operation);
	}

	public void forgetInteractiveViewer(UUID playerId) {
		controllerQueries.forgetViewer(playerId);
		regionLifecycle.forgetViewer(playerId);
		thermalReservoirs.forgetViewer(playerId);
	}

	/** Read-only tuning state; invalid persisted setpoints remain visible so a creative player can replace them. */
	public ThermalReservoirSummary thermalReservoirSummary(ServerPlayer player, BlockPos position) {
		if (player == null || position == null) return null;
		ServerLevel level = player.level();
		if (!level.getServer().isSameThread()) {
			throw new IllegalStateException("Thermal reservoir summary is server-thread-only");
		}
		if (player.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5) > 64.0
				|| !level.getBlockState(position).is(MmmBlocks.THERMAL_RESERVOIR)
				|| !(level.getBlockEntity(position) instanceof ThermalReservoirBlockEntity reservoir)
				|| thermalRegistry == null) return null;
		ThermalRegistry.TemperatureRange range = thermalRegistry.worldBoundaryTemperatureRange();
		double temperature = reservoir.temperatureK();
		return thermalReservoirs.summary(thermalTarget(player, position), temperature, range);
	}

	/** Revalidates a proposed setpoint, persists the world owner, then refreshes adjacent thermal regions. */
	public String applyThermalReservoirSetpoint(
			ServerPlayer player, BlockPos position, long summaryGeneration, double temperatureK
	) {
		if (player == null || position == null) return "请求缺少温度源身份";
		ServerLevel level = player.level();
		if (!level.getServer().isSameThread()) {
			throw new IllegalStateException("Thermal reservoir actions are server-thread-only");
		}
		if (player.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5) > 64.0) {
			return "玩家距离温度源过远";
		}
		ThermalReservoirBlockEntity reservoir = level.getBlockEntity(position)
				instanceof ThermalReservoirBlockEntity found ? found : null;
		boolean targetPresent = level.getBlockState(position).is(MmmBlocks.THERMAL_RESERVOIR)
				&& reservoir != null;
		ThermalReservoirUseCase.Decision authorization = thermalReservoirs.authorize(
				thermalTarget(player, position), summaryGeneration, player.isCreative(),
				authoritativeWritesReady.getAsBoolean(), targetPresent, thermalRegistry != null);
		if (authorization != ThermalReservoirUseCase.Decision.AUTHORIZED) {
			return switch (authorization) {
				case TOKEN_EXPIRED -> "summary 已过期，请刷新";
				case PERMISSION_DENIED -> "只有创造模式玩家可以修改温度源";
				case WRITE_NOT_READY -> authoritativeWriteFailure("修改温度源");
				case TARGET_MISSING -> "目标方块不再是温度源";
				case REGISTRY_UNAVAILABLE -> "world thermal registry 不可用";
				case TEMPERATURE_OUT_OF_RANGE, AUTHORIZED -> throw new IllegalStateException(
						"Unexpected thermal reservoir authorization result " + authorization);
			};
		}
		ThermalRegistry.TemperatureRange range = thermalRegistry.worldBoundaryTemperatureRange();
		if (thermalReservoirs.validateSetpoint(temperatureK, range)
				== ThermalReservoirUseCase.Decision.TEMPERATURE_OUT_OF_RANGE) {
			return String.format(Locale.ROOT,
					"温度必须是有限数值且位于 world thermal registry 的 [%.6f, %.6f] K",
					range.minimumK(), range.maximumK());
		}
		reservoir.setTemperatureK(temperatureK);
		refreshThermalReservoir(level, position);
		return null;
	}

	private static ThermalReservoirUseCase.Target thermalTarget(ServerPlayer player, BlockPos position) {
		return new ThermalReservoirUseCase.Target(player.getUUID(),
				player.level().dimension().identifier().toString(), position.getX(), position.getY(), position.getZ());
	}

	private void refreshThermalReservoir(ServerLevel level, BlockPos position) {
		for (FluidRegion region : regions.regions()) {
			FluidRegionFootprint footprint = region.footprint();
			if (footprint == null || !region.dimension().equals(level.dimension())
					|| !footprint.boundaryBlocks().contains(position.asLong())
					|| !region.solverLoaded() || !region.domain().hasThermalState()) continue;
			regionTick.refreshThermalBoundary(level, region);
		}
	}

	private String authoritativeWriteFailure(String operation) {
		if (!startup.authoritativeWritesReady() && startup.state() != WorldStartupCoordinator.State.READY) {
			return operation + "失败：world capability startup=" + startup.state()
					+ "；不可证明的 profile 不会创建或改写区域";
		}
		return operation + "失败：server.properties 未通过校验；原文件已保留，请修复后重启服务器";
	}

	public record CarrierCellObservation(
			UUID regionId, long regionNetworkGeneration, int cellX, int cellY, int cellZ,
			CellCarrierInventoryView carriers
	) {
		public CarrierCellObservation {
			if (regionId == null || regionNetworkGeneration <= 0L
					|| cellX < 0 || cellY < 0 || cellZ < 0) {
				throw new IllegalArgumentException("Carrier cell observation identity is invalid");
			}
		}
	}
}
