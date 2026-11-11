package mmm.simulation;

import mmm.fluid.FluidDomainContracts;

import mmm.application.RegionCatalog;
import mmm.fluid.HostFluidState;
import mmm.fluid.GasPressureBoundary;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasAuditLedger;
import mmm.simulation.controller.FluidRegionFootprint;
import mmm.thermal.ThermalAuditLedger;
import mmm.thermal.ThermalDisplaySnapshot;
import mmm.worldprofile.WorldCapability;
import mmm.units.SolverUnitScale;
import mmm.units.UnitFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Read-only operator diagnostics derived from committed runtime owners and the selected live region. */
public final class RegionDiagnosticsReport {
	private final Supplier<String> computeDescription;
	private final FluidRegionManager regions;
	private final FluidRegionOperations operations;
	private final MinecraftTakeoverAdapter takeover;
	private final MinecraftDebugFixtures debugFixtures;
	private final CommittedWorldRegistries world;
	private final BooleanSupplier controllerFeatureEnabled;
	private final BooleanSupplier worldBridgeFeatureEnabled;
	private final BooleanSupplier configuredWorldBridgeFeatureEnabled;
	private final Supplier<List<String>> ownershipIssues;

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
			Supplier<String> computeDescription
	) {
		this.computeDescription = Objects.requireNonNull(computeDescription);
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
	}

	public String ownership(MinecraftServer server) {
		StringBuilder report = new StringBuilder("worldbridge flag ")
				.append(worldBridgeFeatureEnabled.getAsBoolean() ? "on" : "off")
				.append("（配置 ")
				.append(configuredWorldBridgeFeatureEnabled.getAsBoolean() ? "on" : "off")
				.append("）");
		boolean any = false;
		for (FluidRegion region : regions.regions()) {
			if (!region.hasSelectedVolume()) continue;
			any = true;
			report.append("\n").append(operations.ownershipSummary(server, region));
		}
		if (!any) report.append("\n没有区域");
		for (String issue : ownershipIssues.get()) report.append("\n! ").append(issue);
		return report.toString();
	}

	public String status(MinecraftServer server) {
		return "[execution]\n  backend = " + computeDescription.get() + "\n" + statusDetails(server);
	}


	private String statusDetails(MinecraftServer server) {
		String takeoverLock = MinecraftTakeoverAdapter.lockDescription(server);
		RegionCatalog.BudgetUsage usage = regions.usage();
		FluidRegion region = regions.selected();
		if (region == null) {
			return "[region]\n  state = 没有活动流体域\n[resources]\n  regions = 0/" + regions.limits().maximumRegions()
					+ "\n[gates]\n  controller flag = " + (controllerFeatureEnabled.getAsBoolean() ? "on" : "off")
					+ "\n  bridge = " + (worldBridgeFeatureEnabled.getAsBoolean() ? "on" : "off")
					+ "\n[resources]\n  takeover lock = " + takeoverLock
					+ (operations.issues().isEmpty() ? "" : "\n  persistence issues = " + operations.issues().size())
					+ (ownershipIssues.get().isEmpty() ? "" : "\n  ownership issues = " + ownershipIssues.get().size())
					+ (debugFixtures.lastFailure() == null ? "" : "\n  fixture failure = " + debugFixtures.lastFailure());
		}
		RegionCatalog.BudgetUsage selectedUsage = regions.usage();
		if (!region.solverLoaded()) {
			return String.format(
					"[region]\n  state = UNLOADED/PAUSED\n  selected = %s\n[resources]\n  regions = %d/%d, reserved %d/%d cells\n[region]\n  controller = %s\n  availability = %s\n  origin = %s\n  grid = %dx%dx%d\n[resources]\n  checkpoint = %d, liquid %s, markers %d\n[gates]\n  controller = %s, bridge %s\n[resources]\n  takeover lock = %s%s",
					region.id().toString().substring(0, 8), selectedUsage.regionCount(), regions.limits().maximumRegions(),
					selectedUsage.reservedCells(), regions.limits().maximumTotalCells(), region.footprint().minimumInterior().toShortString(),
					region.availabilityFailure() == null ? "awaiting explicit run" : "blocked: " + region.availabilityFailure(),
					region.origin().toShortString(), region.sizeX(), region.sizeY(), region.sizeZ(), region.checkpointGeneration(),
					UnitFormat.volume(SolverUnitScale.forCellSize(region.solverConfig().cellSize()).cubicMeters(region.checkpointVolume())),
					region.checkpointMarkerCount(), controllerFeatureEnabled.getAsBoolean() ? "on" : "off",
					worldBridgeFeatureEnabled.getAsBoolean() ? "on" : "off", takeoverLock,
					operations.issues().isEmpty() ? "" : "\n  persistence issues = " + operations.issues().size());
		}
		FluidDomainContracts.StepStats lastStats = region.lastStats();
		if (region.deviceResident()) return residentStatus(region);
		String stats = lastStats == null
				? "尚未步进"
				: String.format("compute %.2f ms, substeps %d, PCG %d, residual %.4g, correction %+.5g J (absolute %.5g J), force/liquidPE/gas %+.5g/%+.5g/%+.5g J",
						region.lastStepMilliseconds(),lastStats.substeps(),lastStats.pressureIterations(),lastStats.pressureResidual(),lastStats.mechanicalCorrection().signedJoules(),lastStats.mechanicalCorrection().absoluteJoules(),
					lastStats.mechanicalCorrection().liquidForceJoules()+lastStats.mechanicalCorrection().granularForceJoules(),
					lastStats.mechanicalCorrection().transportPotentialJoules(),lastStats.mechanicalCorrection().gasCounterWorkJoules());
		HostFluidState domain = region.hostSnapshot();
		BlockPos origin = region.origin();
		double cellVolumes = domain.totalVolumeExact();
		FluidDomainContracts.VolumeDistribution distribution = domain.volumeDistribution(8.0F / 255.0F);
		FluidDomainContracts.SolidDistribution solids = domain.solidDistribution();
		FluidRegion.CollisionStats collision = region.lastCollisionStats();
		FluidRegion.EntityCouplingStats entityCoupling = region.lastEntityCoupling();
		FluidRegionFootprint footprint = region.footprint();
		String regionSource = footprint == null
				? "command"
				: "selection " + footprint.minimumInterior().toShortString() + ", pool shell "
						+ footprint.poolShellBlocks().size() + ", chunks " + footprint.requiredChunks().size();
		String availability = region.availabilityDescription();
		String gas = gasSummary(domain);
		String thermal = world.hasCapability(WorldCapability.THERMAL) ? thermalSummary(region, domain) : "";
		var activity = domain.activityStats();
		String carrierViscosity = carrierViscositySummary(lastStats) + viscousStressSummary(lastStats)
				+ String.format("\n[fluid]\n  activity groups = %d, sleeping %d, active/sleeping cells %d/%d, waiting groups/cells %d/%d",
						activity.groupCount(), activity.sleepingGroups(), activity.activeCells(), activity.sleepingCells(), activity.blockedGroups(), activity.blockedCells());
		return String.format(
				"[region]\n  state = %s%s\n  selected = %s\n[resources]\n  regions = %d/%d, reserved %d/%d cells\n[region]\n  source = %s\n  availability = %s\n  origin = %s\n  grid = %dx%dx%d\n[solver]\n  Minecraft interval = %d ticks, sim step %.6g s\n[fluid]\n  liquid = %s (%.2f normalized cells)\n  liquid stocks = %d in %d cells\n  visible = %d, dense %d\n[resources]\n  takeover = %d, lock %s\n[gates]\n  bridge = %s\n[fluid]\n  entities = %s/%d, liquid impulse [%.3g,%.3g,%.3g] kg·m/s\n  maxP(gauge) = %s\n  collision partial/full = %d/%d, last Δ%d, escaped %s%s\n[solver]\n  %s%s%s",
				region.lifecycle().state(), region.paused() ? "/PAUSED" : "",
				region.id().toString().substring(0, 8),
				usage.regionCount(), regions.limits().maximumRegions(),
				usage.reservedCells(), regions.limits().maximumTotalCells(),
				regionSource, availability,
				origin.toShortString(), domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				region.stepIntervalTicks(), domain.config().timeStep(),
				UnitFormat.volume(domain.unitScale().cubicMeters(cellVolumes)), cellVolumes,
				domain.particleCount(), domain.markerCellCount(),
				distribution.visibleCells(), distribution.denseCells(), takeover.pendingBlockCount(), takeoverLock,
				worldBridgeFeatureEnabled.getAsBoolean() ? "on" : "off",
				region.entityPhysicsEnabled() ? "on" : "off", region.lastAffectedEntities(),
				entityCoupling.impulseXKilogramMetresPerSecond(),
				entityCoupling.impulseYKilogramMetresPerSecond(),
				entityCoupling.impulseZKilogramMetresPerSecond(),
				UnitFormat.pressure(domain.maximumPressure()),
				solids.partialCells(), solids.fullCells(),
				collision.changedCells(), UnitFormat.volume(domain.unitScale().cubicMeters(collision.escapedCellVolume())),
				collision.markerBudgetLimited() ? " (marker budget)" : "", stats,
				(gas.isEmpty() ? "" : "\n[gas]\n  " + gas) + (thermal.isEmpty() ? "" : "\n[thermal]\n  " + thermal),
				carrierViscosity);
	}

	/** Operator status reads the adopted device generation without changing its execution owner. */
	static String residentStatus(FluidRegion region) {
		var inventory = region.inventorySummary();
		var stats = region.lastStats();
		StringBuilder text = new StringBuilder(String.format(
				"[region]\n  state = %s%s\n  selected = %s\n[solver]\n  GPU resident generation = %d\n[region]\n  origin = %s\n  grid = %dx%dx%d\n[solver]\n  Minecraft interval = %d ticks, sim step %.6g s\n[fluid]\n  liquid = %s, stocks %d\n  max speed = %.6g m/s\n[gas]\n  inventory = %d nodes, %s std\n[resources]\n  granular = %d parcels/%d deposits\n[fluid]\n  entities = %s/%d",
				region.lifecycle().state(), region.paused() ? "/PAUSED" : "", region.id().toString().substring(0, 8),
				region.solverGeneration(), region.origin().toShortString(), region.sizeX(), region.sizeY(), region.sizeZ(),
				region.stepIntervalTicks(), region.solverConfig().timeStep(), UnitFormat.volume(inventory.liquidVolumeCubicMetres()),
				region.liquidRowCount(), region.maximumSpeed(), inventory.gasNodeCount(),
				UnitFormat.volume(AirNetwork.cubicMeters(inventory.gasStandardVolumeNanos())),
				inventory.granularParcelCount(), inventory.granularDepositCount(),
				region.entityPhysicsEnabled() ? "on" : "off", region.lastAffectedEntities()));
		if (stats != null) text.append(String.format("\n[solver]\n  compute = %.2f ms, substeps %d, PCG %d, residual %.4g",
				region.lastStepMilliseconds(), stats.substeps(), stats.pressureIterations(), stats.pressureResidual()));
		if (inventory.thermalBound()) {
			text.append(String.format("\n[thermal]\n  T=%.2f..%.2f K U=%.6g J audit=%s pending-work=%s",
					inventory.minimumTemperatureK(), inventory.maximumTemperatureK(), inventory.totalInternalEnergyJoules(),
					region.thermalAuditClosed() ? "closed" : "FAILED", region.hasPendingPhaseMotionWork() ? "yes" : "no"));
			var heat = region.display().thermal();
			if (heat != null) for (var node : heat.gasNodes().stream().limit(4).toList()) {
				text.append(String.format("\n[gas]\n  node %d p=%+.0f Pa T=%.2f K", node.nodeId(),
						10.0 * node.gaugePressureDecaPa(), node.temperatureQ() / 16.0));
			}
		}
		if (region.availabilityFailure() != null) text.append("\n[region]\n  availability = ").append(region.availabilityFailure());
		return text.toString();
	}

	private static String viscousStressSummary(FluidDomainContracts.StepStats lastStats) {
		if (lastStats == null || lastStats.viscosity().stressRows() == 0) return "";
		var stats = lastStats.viscosity();
		return String.format("\n[solver]\n  viscosity bound = %.3g /s, dissipated %.3g J, wall impulse [%.3g,%.3g,%.3g] kg·m/s",
				stats.spectralBoundPerSecond(), stats.dissipatedJoules(),
				stats.boundaryImpulseX(), stats.boundaryImpulseY(), stats.boundaryImpulseZ());
	}

	/** Extrapolated constitutive samples from the last successful step. */
	private static String carrierViscositySummary(FluidDomainContracts.StepStats lastStats) {
		if (lastStats == null) return "";
		FluidDomainContracts.CarrierViscosityStats stats = lastStats.carrierViscosity();
		if (!stats.extrapolated()) return "";
		return String.format("\n[solver]\n  carrier-viscosity extrapolated = %d cells (%s)", stats.extrapolatedCells(), stats.sample());
	}

	public String chemistryInventory(ServerLevel level) {
		if (!world.hasCapability(WorldCapability.CHEMISTRY)) return "化学功能门本次启动未开启";
		FluidRegion region = selectedRegionIn(level);
		var host = region == null || !region.solverLoaded() ? null : region.hostSnapshot();
		if (host == null || !host.hasChemistryState()) {
			return "当前维度没有已加载且选中的化学区域";
		}
		var snapshot=host.exportLiquidCellState();
		long[] totals=new long[host.chemistry().registry().species().size()];long totalQuanta=0,residualUnits=0;
		for(var row:snapshot.rows())for(var stock:row)if(stock.mixture()!=null){
			var mixture=stock.mixture();residualUnits=Math.addExact(residualUnits,mixture.massResidualUnits());
			for(int entry=0;entry<mixture.entryCount();entry++){
				long amount=mixture.amountAt(entry);totals[mixture.speciesHandle(entry)]=Math.addExact(totals[mixture.speciesHandle(entry)],amount);
				totalQuanta=Math.addExact(totalQuanta,amount);
			}
		}
		StringBuilder text = new StringBuilder("region ").append(region.id())
				.append(" generation ").append(region.checkpointGeneration())
				.append(" fingerprint ").append(world.chemicalRegistry().fingerprint())
				.append(" totalQuanta ").append(totalQuanta)
				.append(" residualUnits 0x").append(Long.toUnsignedString(
						residualUnits, 16));
		for (int handle = 0; handle < totals.length; handle++) if (totals[handle] != 0L) {
			text.append('\n').append(world.chemicalRegistry().species(handle).id()).append('=').append(totals[handle]);
		}
		text.append("\nexternalSourceQuanta=").append(host.chemistry().externalSource().totalQuanta())
				.append(" escapedSinkQuanta=").append(host.chemistry().escapedSink().totalQuanta());
		return text.toString();
	}

	public String granularInventory(ServerLevel level) {
		if (!world.hasCapability(WorldCapability.GRANULAR)) return "颗粒功能门本次启动未开启";
		FluidRegion region = selectedRegionIn(level);
		var host = region == null || !region.solverLoaded() ? null : region.hostSnapshot();
		if (host == null || !host.hasGranularState()) {
			return "当前维度没有已加载且选中的颗粒区域";
		}
		var snapshot = host.exportGranularState();
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
				.append(host.lastGranularBoundaryImpulseX()).append(',')
				.append(host.lastGranularBoundaryImpulseY()).append(',')
				.append(host.lastGranularBoundaryImpulseZ()).append(']');
		for (int material = 0; material < materialSvq.length; material++) if (materialSvq[material] > 0L) {
			text.append('\n').append(snapshot.materialPalette().get(material)).append(" svq=")
					.append(materialSvq[material]).append(" mass=").append(materialMass[material]);
		}
		for (var species : snapshot.solidSpeciesTotals().entrySet()) {
			text.append('\n').append("solidSpecies ").append(species.getKey()).append('=').append(species.getValue());
		}
		return text.toString();
	}

	private String thermalSummary(FluidRegion region, HostFluidState domain) {
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
		FluidDomainContracts.ThermalPhaseStats condensed = domain.lastThermalPhaseStats();
		FluidDomainContracts.GasThermalPhaseStats gas = domain.lastGasThermalPhaseStats();
		String phase = condensed.operations() == 0 && gas.operations() == 0 ? "idle"
				: String.format("solid=%d gas=%d (boil %d/condense %d cells)",
						condensed.operations(), gas.operations(),
						gas.boiledCells(), gas.condensedCells());
		return String.format("thermal %s T=%s U=%.6g J source=%.6g sink=%.6g work=%.6g device=%.6g coherent=%d SVQ phase=%s pending-work=%s audit=%s",
				activity == null ? "unbound" : activity.state(), temperature, domain.totalInternalEnergy(),
				audit.externalSource().toDouble(), audit.externalSink().toDouble(),
				audit.mechanicalWork().toDouble(), audit.finiteDeviceExchange().toDouble(),
				domain.totalCoherentSolidVolumeSvq(), phase,
				domain.hasPendingPhaseMotionWork() ? "yes" : "no",
					audit.acceptsInventory(domain.exactInternalEnergy()) ? "closed" : "FAILED");
	}

	private static String gasSummary(HostFluidState domain) {
		GasPressureBoundary network = domain.gasBoundary();
		List<Integer> ids = network.nodeIds();
		if (ids.isEmpty()) return "";
		StringBuilder builder = new StringBuilder("gas ");
		for (int i = 0; i < ids.size() && i < 4; i++) {
			int id = ids.get(i);
			if (i > 0) builder.append("; ");
			builder.append(String.format("node %d p=%+.0f Pa T=%.2f K V0=%.2f m³",
					id, domain.gasNodeGaugePressurePa(id), domain.gasNodeTemperatureK(id), network.standardVolume(id)));
			var inventory = network.inventory(id);
			for (var component : inventory.components()) {
				builder.append(String.format(" %s=%.4g%%", component.componentId(),
						100.0 * component.standardVolumeNanos() / inventory.standardVolumeNanos()));
			}
		}
		if (ids.size() > 4) builder.append("; +").append(ids.size() - 4).append(" more");
		var componentIds = new java.util.TreeSet<String>();
		for (var entry : network.externalSource().entries()) componentIds.add(entry.componentId());
		for (var entry : network.escapedSink().entries()) componentIds.add(entry.componentId());
		builder.append(", turnover m³std (source/sink)");
		for (String componentId : componentIds) builder.append(String.format(" %s=%.6g/%.6g", componentId,
				AirNetwork.cubicMeters(componentNanos(network.externalSource(), componentId)),
				AirNetwork.cubicMeters(componentNanos(network.escapedSink(), componentId))));
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
