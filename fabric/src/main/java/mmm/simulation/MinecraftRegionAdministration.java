package mmm.simulation;

import mmm.application.ContainerRecoveryCoordinator;
import mmm.application.RegionApplicationService;
import mmm.application.command.CheckpointRegionCommand;
import mmm.application.result.CheckpointRegionResult;

import java.util.List;
import java.util.Objects;

/** Region catalog and durable checkpoint operations exposed to the audited administrator command adapter. */
public final class MinecraftRegionAdministration {
	private final FluidRegionManager regions;
	private final FluidRegionOperations operations;
	private final RegionApplicationService application;
	private final ContainerRecoveryCoordinator containerRecovery;

	MinecraftRegionAdministration(
			FluidRegionManager regions,
			FluidRegionOperations operations,
			RegionApplicationService application,
			ContainerRecoveryCoordinator containerRecovery
	) {
		this.regions = Objects.requireNonNull(regions, "Region manager");
		this.operations = Objects.requireNonNull(operations, "Region operations");
		this.application = Objects.requireNonNull(application, "Region application service");
		this.containerRecovery = Objects.requireNonNull(containerRecovery, "Container recovery coordinator");
	}

	public List<String> summaries() {
		return operations.summaries();
	}

	public SelectionResult select(String prefix) {
		return operations.select(prefix);
	}

	public String checkpointSelected() {
		FluidRegion selected = regions.selected();
		if (selected == null) return "没有选中区域";
		CheckpointRegionResult result = application.checkpoint(new CheckpointRegionCommand(
				selected.id(), selected.checkpointGeneration(), containerRecovery.hasPending(selected.id())));
		return switch (result.status()) {
			case COMMITTED -> "checkpoint " + result.checkpointGeneration() + " 已原子发布";
			case ALREADY_UNLOADED -> "区域已是 metadata-only UNLOADED；checkpoint "
					+ result.checkpointGeneration();
			case REGION_NOT_FOUND -> "没有选中区域";
			case NOT_PERSISTENT -> "开发命令区域不写入 region-state";
			case STALE_GENERATION -> "checkpoint generation 已改变；拒绝过期命令，当前为 "
					+ result.checkpointGeneration();
			case PENDING_CONTAINER_WAL -> "区域存在未完成或不可识别的容器转移 WAL；拒绝发布额外 checkpoint";
			case OWNERSHIP_BLOCKED, PERSISTENCE_FAILED -> result.diagnostic();
		};
	}

	public String recoverPreviousSelected(long expectedCurrentGeneration) {
		FluidRegion selected = regions.selected();
		if (selected == null) return "没有选中区域";
		if (containerRecovery.hasPending(selected.id())) {
			return "上一代恢复失败：区域存在未完成或不可识别的容器转移 WAL；禁止跨代回退";
		}
		return operations.recoverPreviousSelected(expectedCurrentGeneration);
	}
}
