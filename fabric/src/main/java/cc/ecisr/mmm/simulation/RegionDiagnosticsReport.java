package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.application.RegionCatalog;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.GasPressureBoundary;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasAuditLedger;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.thermal.ThermalDisplaySnapshot;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Read-only operator diagnostics derived from committed runtime owners and the selected live region. */
public final class RegionDiagnosticsReport {
	private final FluidRegionManager regions;
	private final FluidRegionOperations operations;
	private final MinecraftTakeoverAdapter takeover;
	private final MinecraftDebugFixtures debugFixtures;
	private final CommittedWorldRegistries world;
	private final BooleanSupplier controllerFeatureEnabled;
	private final BooleanSupplier worldBridgeFeatureEnabled;
	private final BooleanSupplier configuredWorldBridgeFeatureEnabled;
	private final Supplier<List<String>> ownershipIssues;
	private final IntSupplier maximumPhaseOperationsPerStep;

	RegionDiagnosticsReport(
			FluidRegionManager regions,
			FluidRegionOperations operations,
			MinecraftTakeoverAdapter takeover,
			MinecraftDebugFixtures debugFixtures,
			CommittedWorldRegistries world,
			BooleanSupplier controllerFeatureEnabled,
			BooleanSupplier worldBridgeFeatureEnabled,
			BooleanSupplier configuredWorldBridgeFeatureEnabled,
			Supplier<List<String>> ownershipIssues,
			IntSupplier maximumPhaseOperationsPerStep
	) {
		this.regions = Objects.requireNonNull(regions, "Region manager");
		this.operations = Objects.requireNonNull(operations, "Region operations");
		this.takeover = Objects.requireNonNull(takeover, "Takeover adapter");
		this.debugFixtures = Objects.requireNonNull(debugFixtures, "Debug fixtures");
		this.world = Objects.requireNonNull(world, "Committed world registries");
		this.controllerFeatureEnabled = Objects.requireNonNull(controllerFeatureEnabled, "Controller feature gate");
		this.worldBridgeFeatureEnabled = Objects.requireNonNull(worldBridgeFeatureEnabled, "World bridge gate");
		this.configuredWorldBridgeFeatureEnabled = Objects.requireNonNull(
				configuredWorldBridgeFeatureEnabled, "Configured world bridge gate");
		this.ownershipIssues = Objects.requireNonNull(ownershipIssues, "Ownership issues");
		this.maximumPhaseOperationsPerStep = Objects.requireNonNull(
				maximumPhaseOperationsPerStep, "Phase operation budget");
	}

	public String ownership(MinecraftServer server) {
		StringBuilder report = new StringBuilder("worldbridge flag ")
				.append(worldBridgeFeatureEnabled.getAsBoolean() ? "on" : "off")
				.append("（配置 ")
				.append(configuredWorldBridgeFeatureEnabled.getAsBoolean() ? "on" : "off")
				.append("）");
		boolean any = false;
		for (FluidRegion region : regions.regions()) {
			if (!region.controllerManaged()) continue;
			any = true;
			report.append("\n").append(operations.ownershipSummary(server, region));
		}
		if (!any) report.append("\n没有控制器区域");
		for (String issue : ownershipIssues.get()) report.append("\n! ").append(issue);
		return report.toString();
	}

	public String status(MinecraftServer server) {
		String takeoverLock = MinecraftTakeoverAdapter.lockDescription(server);
		RegionCatalog.BudgetUsage usage = regions.usage();
		FluidRegion region = regions.selected();
		if (region == null) {
			return "没有活动流体域 | regions 0/" + regions.limits().maximumRegions()
					+ " | controller flag " + (controllerFeatureEnabled.getAsBoolean() ? "on" : "off")
					+ ", bridge " + (worldBridgeFeatureEnabled.getAsBoolean() ? "on" : "off")
					+ " | takeover lock " + takeoverLock
					+ (operations.issues().isEmpty() ? "" : " | persistence issues " + operations.issues().size())
					+ (ownershipIssues.get().isEmpty() ? "" : " | ownership issues " + ownershipIssues.get().size())
					+ (debugFixtures.lastFailure() == null ? "" : " | " + debugFixtures.lastFailure());
		}
		RegionCatalog.BudgetUsage selectedUsage = regions.usage();
		if (!region.solverLoaded()) {
			return String.format(
					"UNLOADED/PAUSED | selected %s | regions %d/%d, reserved %d/%d cells, %d/%d markers | controller %s | %s | origin %s | grid %dx%dx%d | checkpoint %d, liquid %.3f cells, markers %d | controller flag %s, bridge %s | takeover lock %s%s",
					region.id().toString().substring(0, 8), selectedUsage.regionCount(), regions.limits().maximumRegions(),
					selectedUsage.reservedCells(), regions.limits().maximumTotalCells(), selectedUsage.reservedMarkers(),
					regions.limits().maximumTotalMarkers(), region.footprint().controllerPosition().toShortString(),
					region.availabilityFailure() == null ? "awaiting explicit run" : "blocked: " + region.availabilityFailure(),
					region.origin().toShortString(), region.sizeX(), region.sizeY(), region.sizeZ(), region.checkpointGeneration(),
					region.checkpointVolume(), region.checkpointMarkerCount(), controllerFeatureEnabled.getAsBoolean() ? "on" : "off",
					worldBridgeFeatureEnabled.getAsBoolean() ? "on" : "off", takeoverLock,
					operations.issues().isEmpty() ? "" : " | persistence issues " + operations.issues().size());
		}
		FluidDomain.StepStats lastStats = region.lastStats();
		String stats = lastStats == null
				? "尚未步进"
				: String.format("%.2f ms, substeps %d, PCG %d, residual %.4g%s",
						region.lastStepMilliseconds(), lastStats.substeps(), lastStats.pressureIterations(), lastStats.pressureResidual(),
						lastStats.reseededMerges() == 0 && lastStats.reseededSplits() == 0
								&& lastStats.reseedBudgetSkipped() == 0 && lastStats.reseedGeometrySkipped() == 0
								&& lastStats.reseedConservationSkipped() == 0
								? ""
								: String.format(", reseed split %d/merge %d, skipped budget %d/geometry %d/conservation %d, ledger %.3g",
										lastStats.reseededSplits(), lastStats.reseededMerges(),
										lastStats.reseedBudgetSkipped(), lastStats.reseedGeometrySkipped(),
										lastStats.reseedConservationSkipped(),
										lastStats.reseedLedgerError()));
		FluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		float cellVolumes = domain.totalVolume();
		FluidDomain.VolumeDistribution distribution = domain.volumeDistribution(8.0F / 255.0F);
		FluidDomain.SolidDistribution solids = domain.solidDistribution();
		FluidRegion.CollisionStats collision = region.lastCollisionStats();
		FluidRegion.EntityCouplingStats entityCoupling = region.lastEntityCoupling();
		FluidRegionFootprint footprint = region.footprint();
		String regionSource = footprint == null
				? "command"
				: "controller " + footprint.controllerPosition().toShortString() + ", boundary "
						+ footprint.boundaryBlocks().size() + ", chunks " + footprint.requiredChunks().size();
		String availability = region.availabilityDescription();
		String gas = gasSummary(domain);
		String thermal = world.hasCapability(WorldCapability.THERMAL) ? thermalSummary(region, domain) : "";
		String carrierViscosity = carrierViscositySummary(domain);
		return String.format(
				"%s%s | selected %s | regions %d/%d, reserved %d/%d cells, %d/%d markers | %s | %s | origin %s | grid %dx%dx%d | sim %.1f Hz | liquid %.2f blocks (%.2f cells) | particles %d/%d in %d cells | visible %d, dense %d | takeover %d, lock %s | bridge %s | entities %s/%d, liquid impulse [%.3g,%.3g,%.3g] kg·m/s | maxP %.1f | collision partial/full %d/%d, last Δ%d, escaped %.3f%s | %s%s%s",
				region.lifecycle().state(), region.paused() ? "/PAUSED" : "",
				region.id().toString().substring(0, 8),
				usage.regionCount(), regions.limits().maximumRegions(),
				usage.reservedCells(), regions.limits().maximumTotalCells(),
				usage.reservedMarkers(), regions.limits().maximumTotalMarkers(),
				regionSource, availability,
				origin.toShortString(), domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				20.0 / region.stepIntervalTicks(),
				cellVolumes / FluidRegionFactory.CELL_VOLUMES_PER_BLOCK, cellVolumes,
				domain.particleCount(), domain.maximumParticleCount(), domain.markerCellCount(),
				distribution.visibleCells(), distribution.denseCells(), takeover.pendingBlockCount(), takeoverLock,
				worldBridgeFeatureEnabled.getAsBoolean() ? "on" : "off",
				region.entityPhysicsEnabled() ? "on" : "off", region.lastAffectedEntities(),
				entityCoupling.impulseXKilogramMetresPerSecond(),
				entityCoupling.impulseYKilogramMetresPerSecond(),
				entityCoupling.impulseZKilogramMetresPerSecond(),
				domain.maximumPressure(),
				solids.partialCells(), solids.fullCells(),
				collision.changedCells(), collision.escapedCellVolume() / FluidRegionFactory.CELL_VOLUMES_PER_BLOCK,
				collision.markerBudgetLimited() ? " (marker budget)" : "", stats,
				(gas.isEmpty() ? "" : " | " + gas) + (thermal.isEmpty() ? "" : " | " + thermal),
				carrierViscosity);
	}

	/**
	 * Carrier viscosity samples that took the nearest declared-domain endpoint. A nonzero count is not
	 * an error: it means granular drag ran outside the material's measured viscosity range.
	 */
	private static String carrierViscositySummary(FluidDomain domain) {
		FluidDomain.CarrierViscosityStats stats = domain.carrierViscosityStats();
		if (!stats.extrapolated()) return "";
		return String.format(" | carrier-viscosity extrapolated %d cells (%s)", stats.extrapolatedCells(), stats.sample());
	}

	public String chemistryInventory(ServerLevel level) {
		if (!world.hasCapability(WorldCapability.CHEMISTRY)) return "化学功能门本次启动未开启";
		FluidRegion region = selectedRegionIn(level);
		if (region == null || !region.solverLoaded() || !region.domain().hasChemistryState()) {
			return "当前维度没有已加载且选中的化学区域";
		}
		long[] totals = region.domain().chemicalParcels().speciesTotals(region.domain().particleCount());
		StringBuilder text = new StringBuilder("region ").append(region.id())
				.append(" generation ").append(region.checkpointGeneration())
				.append(" fingerprint ").append(world.chemicalRegistry().fingerprint())
				.append(" totalQuanta ").append(region.domain().chemicalParcels().totalQuanta())
				.append(" residualBits 0x").append(Long.toUnsignedString(
						region.domain().exportChemicalParticleState().residualLedgerBits(), 16));
		for (int handle = 0; handle < totals.length; handle++) if (totals[handle] != 0L) {
			text.append('\n').append(world.chemicalRegistry().species(handle).id()).append('=').append(totals[handle]);
		}
		text.append("\nexternalSourceQuanta=").append(region.domain().chemicalParcels().externalSource().totalQuanta())
				.append(" escapedSinkQuanta=").append(region.domain().chemicalParcels().escapedSink().totalQuanta());
		return text.toString();
	}

	public String granularInventory(ServerLevel level) {
		if (!world.hasCapability(WorldCapability.GRANULAR)) return "颗粒功能门本次启动未开启";
		FluidRegion region = selectedRegionIn(level);
		if (region == null || !region.solverLoaded() || !region.domain().hasGranularState()) {
			return "当前维度没有已加载且选中的颗粒区域";
		}
		var snapshot = region.domain().exportGranularState();
		long[] materialSvq = new long[snapshot.materialPalette().size()];
		double[] materialMass = new double[materialSvq.length];
		for (var parcel : snapshot.activeParcels()) {
			materialSvq[parcel.materialPaletteIndex()] = Math.addExact(
					materialSvq[parcel.materialPaletteIndex()], parcel.solidVolumeSvq());
			materialMass[parcel.materialPaletteIndex()] += parcel.mass();
		}
		for (var deposit : snapshot.depositGroups()) {
			materialSvq[deposit.materialPaletteIndex()] = Math.addExact(
					materialSvq[deposit.materialPaletteIndex()], deposit.solidVolumeSvq());
			materialMass[deposit.materialPaletteIndex()] += deposit.mass();
		}
		long sourceSvq = 0L, sinkSvq = 0L;
		for (var entry : snapshot.externalSource().entries()) sourceSvq = Math.addExact(sourceSvq, entry.solidVolumeSvq());
		for (var entry : snapshot.escapedSink().entries()) sinkSvq = Math.addExact(sinkSvq, entry.solidVolumeSvq());
		StringBuilder text = new StringBuilder("region ").append(region.id())
				.append(" generation ").append(region.checkpointGeneration())
				.append(" fingerprint ").append(snapshot.registryFingerprint())
				.append(" totalSvq ").append(snapshot.totalSolidVolumeSvq())
				.append(" totalMass ").append(snapshot.totalMass())
				.append(" active ").append(snapshot.activeParcels().size())
				.append(" depositGroups ").append(snapshot.depositGroups().size())
				.append(" sourceSvq ").append(sourceSvq).append(" sinkSvq ").append(sinkSvq)
				.append(" boundaryImpulse=[")
				.append(region.domain().lastGranularBoundaryImpulseX()).append(',')
				.append(region.domain().lastGranularBoundaryImpulseY()).append(',')
				.append(region.domain().lastGranularBoundaryImpulseZ()).append(']');
		for (int material = 0; material < materialSvq.length; material++) if (materialSvq[material] > 0L) {
			text.append('\n').append(snapshot.materialPalette().get(material)).append(" svq=")
					.append(materialSvq[material]).append(" mass=").append(materialMass[material]);
		}
		for (var species : snapshot.solidSpeciesTotals().entrySet()) {
			text.append('\n').append("solidSpecies ").append(species.getKey()).append('=').append(species.getValue());
		}
		return text.toString();
	}

	private String thermalSummary(FluidRegion region, FluidDomain domain) {
		if (!domain.hasThermalState()) return "thermal missing";
		ThermalAuditLedger audit = domain.thermalAudit();
		ThermalActivity activity = region.thermalActivity();
		short[] temperatureQ = domain.copyThermalDisplaySnapshot().cellTemperatureQ();
		int minimumQ = Integer.MAX_VALUE, maximumQ = Integer.MIN_VALUE;
		for (short raw : temperatureQ) {
			int value = Short.toUnsignedInt(raw);
			if (value == ThermalDisplaySnapshot.EMPTY_TEMPERATURE_Q) continue;
			minimumQ = Math.min(minimumQ, value);
			maximumQ = Math.max(maximumQ, value);
		}
		String temperature = minimumQ == Integer.MAX_VALUE ? "empty"
				: minimumQ == maximumQ ? String.format("%.2f K", minimumQ / 16.0)
				: String.format("%.2f..%.2f K", minimumQ / 16.0, maximumQ / 16.0);
		FluidDomain.ThermalPhaseStats condensed = domain.lastThermalPhaseStats();
		FluidDomain.GasThermalPhaseStats gas = domain.lastGasThermalPhaseStats();
		int phaseBudget = maximumPhaseOperationsPerStep.getAsInt();
		String phase = condensed.operations() == 0 && gas.operations() == 0 ? "idle"
				: String.format("solid=%d gas=%d%s (boil %d/condense %d cells)",
						condensed.operations(), gas.operations(),
						condensed.operations() + gas.operations() == phaseBudget ? "/" + phaseBudget + " budget-hit" : "",
						gas.boiledCells(), gas.condensedCells());
		return String.format("thermal %s T=%s U=%.6g EU source=%.6g sink=%.6g work=%.6g device=%.6g coherent=%d SVQ phase=%s pending-work=%s audit=%s",
				activity == null ? "unbound" : activity.state(), temperature, domain.totalInternalEnergy(),
				audit.externalSource().toDouble(), audit.externalSink().toDouble(),
				audit.mechanicalWork().toDouble(), audit.finiteDeviceExchange().toDouble(),
				domain.totalCoherentSolidVolumeSvq(), phase,
				domain.hasPendingPhaseVolumeWork() ? "yes" : "no",
				domain.exactInternalEnergy().equals(audit.expectedInventory()) ? "closed" : "FAILED");
	}

	private static String gasSummary(FluidDomain domain) {		GasPressureBoundary network = domain.gasBoundary();
		List<Integer> ids = network.nodeIds();
		if (ids.isEmpty()) return "";
		StringBuilder builder = new StringBuilder("gas ");
		for (int i = 0; i < ids.size() && i < 4; i++) {
			int id = ids.get(i);
			if (i > 0) builder.append("; ");
			builder.append(String.format("node %d p=%+.0f Pa T=%.2f K V0=%.2f m³",
					id, domain.gasNodeGaugePressurePa(id), domain.gasNodeTemperatureK(id), network.standardVolume(id)));
			var vapor = network.inventory(id).component(BuiltinGasDefinitions.WATER_VAPOR);
			if (vapor != null) builder.append(String.format(" vapor=%.6g kg/%.6g m³std",
					vapor.mass(), AirNetwork.cubicMeters(vapor.standardVolumeNanos())));
		}
		if (ids.size() > 4) builder.append("; +").append(ids.size() - 4).append(" more");
		long airSource = componentNanos(network.externalSource(), BuiltinGasDefinitions.AIR);
		long airSink = componentNanos(network.escapedSink(), BuiltinGasDefinitions.AIR);
		long vaporSource = componentNanos(network.externalSource(), BuiltinGasDefinitions.WATER_VAPOR);
		long vaporSink = componentNanos(network.escapedSink(), BuiltinGasDefinitions.WATER_VAPOR);
		builder.append(String.format(", turnover air=%.2f/%.2f vapor=%.2f/%.2f m³std (source/sink)",
				AirNetwork.cubicMeters(airSource), AirNetwork.cubicMeters(airSink),
				AirNetwork.cubicMeters(vaporSource), AirNetwork.cubicMeters(vaporSink)));
		return builder.toString();
	}

	private FluidRegion selectedRegionIn(ServerLevel level) {
		FluidRegion selected = regions.selected();
		return selected != null && selected.dimension().equals(level.dimension()) ? selected : null;
	}

	private static long componentNanos(GasAuditLedger ledger, String componentId) {
		for (GasAuditLedger.Entry entry : ledger.entries()) {
			if (entry.componentId().equals(componentId)) return entry.standardVolumeNanos();
		}
		return 0L;
	}
}
