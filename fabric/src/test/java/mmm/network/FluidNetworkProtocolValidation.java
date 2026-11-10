package mmm.network;

import mmm.api.RegionAvailabilityReason;
import mmm.api.query.FluidQueryResult;
import mmm.api.query.FluidQueryStatus;
import mmm.application.ControllerQueryUseCase;
import mmm.application.ThermalReservoirUseCase;
import mmm.application.command.ControllerSummaryCommand;
import mmm.application.port.ControllerSummaryPort;
import mmm.controller.ControllerConsoleAction;
import mmm.controller.ControllerConsoleSessions;
import mmm.controller.ControllerSummary;
import mmm.controller.LifecycleOperation;
import mmm.controller.LifecycleOperationPreview;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.simulation.TestProfiles;
import mmm.fluid.FluidMaterialRenderSnapshot;
import mmm.fluid.FluidSurfaceInput;
import mmm.fluid.FluidMaterialSurfaceMesher;
import mmm.granular.GranularDisplaySnapshot;
import mmm.thermal.ThermalReservoirSummary;
import mmm.thermal.ThermalRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Protocol negotiation, codec compatibility and stale-snapshot rejection matrix. */
public final class FluidNetworkProtocolValidation {
	private static final Identifier DIMENSION = Identifier.fromNamespaceAndPath("mmm", "protocol_test");
	private static final UUID REGION = new UUID(17L, 31L);

	private FluidNetworkProtocolValidation() { }

	public static void main(String[] args) {
		validateObservationCodecs();
		validateNegotiationMatrix();
		validateQueryAvailabilityReason();
		validateConfigurationCodecs();
		validateControllerConsoleCodecs();
		validateDemoWorkbenchCodecs();
		validateThermalReservoirCodecs();
		validateMaterialSnapshot();
		validateCompactSurface();
		validateChemicalSnapshot();
		validateGranularSnapshot();
		validateThermalSnapshot();
		System.out.println("Fluid network protocol validation passed");
	}

	private static void validateObservationCodecs() {
        UUID session=UUID.randomUUID(); var position=new BlockPos(1,2,3); String dimension="minecraft:overworld";
        var trendRequest=new ProcessTrendRequest(position,dimension,session,7,false);
        var trend=new ProcessTrendPayload(position,dimension,session,7,"液位","运行中",false,true,
                List.of(new ProcessTrendHistory.Point(.05,2,false),new ProcessTrendHistory.Point(.1,3,true)));
        var sampleRequest=new SampleAnalysisRequest(session,dimension,net.minecraft.world.InteractionHand.MAIN_HAND);
        var sample=new SampleAnalysisPayload(session,dimension,List.of("水"));
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {
            ProcessTrendRequest.CODEC.encode(buffer,trendRequest); require(trendRequest.equals(ProcessTrendRequest.CODEC.decode(buffer)),"trend request codec");
            ProcessTrendPayload.CODEC.encode(buffer,trend); require(trend.equals(ProcessTrendPayload.CODEC.decode(buffer)),"trend response codec");
            SampleAnalysisRequest.CODEC.encode(buffer,sampleRequest); require(sampleRequest.equals(SampleAnalysisRequest.CODEC.decode(buffer)),"sample request codec");
            SampleAnalysisPayload.CODEC.encode(buffer,sample); require(sample.equals(SampleAnalysisPayload.CODEC.decode(buffer)),"sample response codec");
        } finally { buffer.release(); }
    }

	private static void validateDemoWorkbenchCodecs() {
		UUID player = new UUID(97, 101), session = new UUID(103, 107), otherPlayer = new UUID(109, 113);
		var tokens = new ControllerConsoleSessions();
		long stale = tokens.issue(player, session), generation = tokens.issue(player, session);
		require(!tokens.consume(player, session, stale) && !tokens.consume(otherPlayer, session, generation)
				&& tokens.consume(player, session, generation) && !tokens.consume(player, session, generation),
				"demo session token must reject stale, other-player and replay requests");
		for (DemoPreset preset : DemoPreset.values()) for (DemoAction action : DemoAction.values()) {
			for (UUID region : new UUID[] { null, REGION }) {
				var request = new DemoActionPayload(session, generation, region, preset, action);
				var buffer = new FriendlyByteBuf(Unpooled.buffer());
				try {
					DemoActionPayload.CODEC.encode(buffer, request);
					require(DemoActionPayload.CODEC.decode(buffer).equals(request), "demo action lost identity or selection");
				} finally { buffer.release(); }
			}
		}
		for (DemoPreset preset : DemoPreset.values()) for (boolean paused : new boolean[] { false, true }) {
			for (String failure : new String[] { null, "演示已改变，请重试" }) {
				var response = new DemoWorkbenchPayload(session, generation, generation + 1, DIMENSION.toString(),
						preset, REGION, paused, failure);
				var buffer = new FriendlyByteBuf(Unpooled.buffer());
				try {
					DemoWorkbenchPayload.CODEC.encode(buffer, response);
					var decoded = DemoWorkbenchPayload.CODEC.decode(buffer);
					require(decoded.equals(response) && decoded.requestGeneration() == generation,
							"demo response lost the originating request generation or state");
				} finally { buffer.release(); }
			}
		}
		var opening = new DemoWorkbenchPayload(session, 0, generation, DIMENSION.toString(), DemoPreset.WATER, null, true, null);
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			DemoWorkbenchPayload.CODEC.encode(buffer, opening);
			require(DemoWorkbenchPayload.CODEC.decode(buffer).equals(opening), "empty demo opening did not round-trip");
		} finally { buffer.release(); }
		var launch = new DemoWorkbenchPayload(session, 0, generation, DIMENSION.toString(), DemoPreset.CHEMISTRY,
				REGION, true, "performance pause", true, true);
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			DemoWorkbenchPayload.CODEC.encode(buffer, launch);
			require(DemoWorkbenchPayload.CODEC.decode(buffer).equals(launch), "demo launch lost display initialization");
		} finally { buffer.release(); }
	}

	private static void validateThermalReservoirCodecs() {
		UUID player = new UUID(67L, 71L);
		ThermalReservoirUseCase reservoirs = new ThermalReservoirUseCase();
		ThermalReservoirUseCase.Target target = new ThermalReservoirUseCase.Target(
				player, DIMENSION.toString(), 2, 3, 4);
		ThermalRegistry.TemperatureRange range = new ThermalRegistry.TemperatureRange(250.0, 600.0);
		long old = reservoirs.summary(target, 293.15, range).summaryGeneration();
		long current = reservoirs.summary(target, 293.15, range).summaryGeneration();
		require(reservoirs.authorize(target, old, true, true, true, true)
				== ThermalReservoirUseCase.Decision.TOKEN_EXPIRED
				&& reservoirs.authorize(target, current, true, true, true, true)
				== ThermalReservoirUseCase.Decision.AUTHORIZED
				&& reservoirs.authorize(target, current, true, true, true, true)
				== ThermalReservoirUseCase.Decision.TOKEN_EXPIRED,
				"thermal reservoir stale/replay token semantics changed");
		long denied = reservoirs.summary(target, 293.15, range).summaryGeneration();
		require(reservoirs.authorize(target, denied, false, true, true, true)
				== ThermalReservoirUseCase.Decision.PERMISSION_DENIED
				&& reservoirs.authorize(target, denied, true, true, true, true)
				== ThermalReservoirUseCase.Decision.TOKEN_EXPIRED,
				"thermal reservoir denied action did not consume its disclosed token");
		require(reservoirs.validateSetpoint(250.0, range) == ThermalReservoirUseCase.Decision.AUTHORIZED
				&& reservoirs.validateSetpoint(600.0, range) == ThermalReservoirUseCase.Decision.AUTHORIZED
				&& reservoirs.validateSetpoint(Double.NaN, range)
				== ThermalReservoirUseCase.Decision.TEMPERATURE_OUT_OF_RANGE,
				"thermal reservoir setpoint range semantics changed");

		ThermalReservoirSummary summary = new ThermalReservoirSummary(5L, DIMENSION.toString(),
				2, 3, 4, 293.15, true, 250.0, 600.0);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		ThermalReservoirSummaryPayload decoded;
		try {
			ThermalReservoirSummaryPayload.CODEC.encode(buffer, new ThermalReservoirSummaryPayload(summary));
			decoded = ThermalReservoirSummaryPayload.CODEC.decode(buffer);
		} finally {
			buffer.release();
		}
		require(decoded.summary().equals(summary), "thermal reservoir summary did not round-trip exactly");
		require(decoded.requestGeneration() == 0 && decoded.position().equals(new BlockPos(2, 3, 4)),
				"initial reservoir opening acquired a request token or lost its position");
		for (ThermalReservoirSummaryPayload response : List.of(
				new ThermalReservoirSummaryPayload(new BlockPos(2, 3, 4), 3L, summary),
				new ThermalReservoirSummaryPayload(new BlockPos(2, 3, 4), 3L, null))) {
			buffer = new FriendlyByteBuf(Unpooled.buffer());
			try {
				ThermalReservoirSummaryPayload.CODEC.encode(buffer, response);
				require(ThermalReservoirSummaryPayload.CODEC.decode(buffer).equals(response),
						"reservoir response lost its request token or disappeared-target state");
			} finally { buffer.release(); }
		}

		ThermalReservoirSetpointPayload setpoint = new ThermalReservoirSetpointPayload(
				new BlockPos(2, 3, 4), 5L, 310.0);
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		ThermalReservoirSetpointPayload decodedSetpoint;
		try {
			ThermalReservoirSetpointPayload.CODEC.encode(buffer, setpoint);
			decodedSetpoint = ThermalReservoirSetpointPayload.CODEC.decode(buffer);
		} finally {
			buffer.release();
		}
		require(decodedSetpoint.equals(setpoint),
				"thermal reservoir setpoint identity/token did not round-trip exactly");
	}

	private static void validateControllerConsoleCodecs() {
		UUID otherPlayer = new UUID(41L, 43L);
		ControllerSummary.Capacity full = ControllerSummary.Capacity.available(8L, 16L);
		ControllerSummary summary = new ControllerSummary(7L, REGION, DIMENSION.toString(), "a".repeat(64),
				11L, 13L, new ControllerSummary.Footprint("区域", 2, 3, 4, 5, 6, 7, 180L, 4), true,
				ControllerSummary.OperatorIntent.RUNNING, ControllerSummary.MotionState.REDUCED,
				ControllerSummary.ThermalState.SLEEP,
				new ControllerSummary.Availability(RegionAvailabilityReason.CHUNK_MISSING, "required chunk missing"),
				new ControllerSummary.Usage(full, full, full, full, full,
						ControllerSummary.Capacity.unavailable(1_024L)),
				new ControllerSummary.Inventory(true, 0.75, 741.0, 123L, 456L, 7.5,
						789L, 8.5, 321L, 0.42, true, 91_000.0,
						ControllerSummary.TemperatureRange.available(278.5, 341.25)),
				new ControllerSummary.Gates(true, false, true, true, false),
				new ControllerSummary.Diagnostics(2.5, 17, 1.0E-6, "bounded diagnostic"));
		UUID otherRegion = new UUID(47L, 53L);
		UUID invalidRegion = new UUID(71L, 73L);
		ControllerQueryUseCase queries = new ControllerQueryUseCase(regionId -> {
			if (regionId.equals(REGION) || regionId.equals(otherRegion)) {
				return Optional.of(controllerView(regionId, summary));
			}
			if (regionId.equals(invalidRegion)) {
				ControllerSummaryPort.View view = controllerView(regionId, summary);
				return Optional.of(new ControllerSummaryPort.View(view.regionId(), view.dimensionId(),
						view.profileFingerprint(), 0L, view.networkGeneration(), view.footprint(), view.selected(),
						view.operatorIntent(), view.motionState(), view.thermalState(), view.availability(),
						view.usage(), view.inventory(), view.gates(), view.diagnostics()));
			}
			return Optional.empty();
		});
		var old = queries.query(new ControllerSummaryCommand(otherPlayer, REGION));
		var current = queries.query(new ControllerSummaryCommand(otherPlayer, REGION));
		require(old.ok() && current.ok()
				&& !queries.consumeActionToken(otherPlayer, REGION, old.summary().summaryGeneration()),
				"stale controller token was accepted");
		require(queries.consumeActionToken(otherPlayer, REGION, current.summary().summaryGeneration()),
				"stale token invalidated the current controller token");
		require(!queries.consumeActionToken(otherPlayer, REGION, current.summary().summaryGeneration()),
				"controller token replay was accepted");
		var isolated = queries.query(new ControllerSummaryCommand(otherPlayer, otherRegion));
		require(!queries.consumeActionToken(new UUID(59L, 61L), otherRegion,
				isolated.summary().summaryGeneration())
				&& queries.consumeActionToken(otherPlayer, otherRegion, isolated.summary().summaryGeneration()),
				"controller token was not isolated by player and region");
		long beforeMissing = queries.query(new ControllerSummaryCommand(otherPlayer, REGION))
				.summary().summaryGeneration();
		require(!queries.query(new ControllerSummaryCommand(otherPlayer, new UUID(79L, 83L))).ok(),
				"missing controller unexpectedly produced a bounded summary");
		boolean invalidRejected = false;
		try {
			queries.query(new ControllerSummaryCommand(otherPlayer, invalidRegion));
		} catch (IllegalArgumentException expected) {
			invalidRejected = true;
		}
		require(invalidRejected, "invalid bounded controller view was projected into a summary");
		long afterMissing = queries.query(new ControllerSummaryCommand(otherPlayer, REGION))
				.summary().summaryGeneration();
		require(afterMissing == beforeMissing + 1L,
				"failed controller read or projection consumed an undisclosed action token generation");

		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		ControllerSummaryPayload decoded;
		try {
			ControllerSummaryPayload.CODEC.encode(buffer, new ControllerSummaryPayload(summary));
			decoded = ControllerSummaryPayload.CODEC.decode(buffer);
		} finally { buffer.release(); }
		require(decoded.summary().equals(summary), "controller summary payload did not round-trip exactly");
		require(decoded.requestGeneration() == 0 && decoded.regionId().equals(REGION),
				"initial controller opening acquired a request token or lost its identity");
		for (ControllerSummaryPayload response : List.of(
				new ControllerSummaryPayload(REGION, 7L, current.summary()),
				new ControllerSummaryPayload(REGION, 7L, current.summary(), "request failed"),
				new ControllerSummaryPayload(REGION, 7L, null))) {
			buffer = new FriendlyByteBuf(Unpooled.buffer());
			try {
				ControllerSummaryPayload.CODEC.encode(buffer, response);
				require(ControllerSummaryPayload.CODEC.decode(buffer).equals(response),
						"controller response lost its original token or disappeared-target state");
			} finally { buffer.release(); }
		}

		ControllerActionPayload action = new ControllerActionPayload(REGION, new BlockPos(2, 3, 4), 7L,
				ControllerConsoleAction.PAUSE);
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		ControllerActionPayload decodedAction;
		try {
			ControllerActionPayload.CODEC.encode(buffer, action);
			decodedAction = ControllerActionPayload.CODEC.decode(buffer);
		} finally { buffer.release(); }
		require(decodedAction.equals(action), "controller action identity/token did not round-trip exactly");

		LifecycleOperationRequestPayload request = LifecycleOperationRequestPayload.preview(
				REGION, new BlockPos(2, 3, 4), 7L, LifecycleOperation.EMPTY_CONDENSED);
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		LifecycleOperationRequestPayload decodedRequest;
		try {
			LifecycleOperationRequestPayload.CODEC.encode(buffer, request);
			decodedRequest = LifecycleOperationRequestPayload.CODEC.decode(buffer);
		} finally { buffer.release(); }
		require(decodedRequest.equals(request), "lifecycle preview request did not round-trip exactly");

		LifecycleOperationPreview preview = new LifecycleOperationPreview(new UUID(67L, 71L), otherPlayer,
				REGION, 11L, LifecycleOperation.VENT_SEALED_GAS,
				LifecycleOperationPreview.PermissionDecision.BUILTIN_ADMIN_GRANTED, 200L,
				new LifecycleOperationPreview.Removal(0L, 0.0, 0L, 0L, 0.0, 0L, 0.0,
						321L, 0.42, 99L, true, 12_345.0),
				new LifecycleOperationPreview.GasState(321L, 0.42, 99L, 2, true), 17,
				List.of(LifecycleOperationPreview.Blocker.FINITE_DEVICE_NOT_EMPTY,
						LifecycleOperationPreview.Blocker.REGION_RUNNING));
		LifecycleOperationPreviewPayload previewPayload = new LifecycleOperationPreviewPayload(
				new BlockPos(2, 3, 4), 7L, preview, current.summary());
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		LifecycleOperationPreviewPayload decodedPreview;
		try {
			LifecycleOperationPreviewPayload.CODEC.encode(buffer, previewPayload);
			decodedPreview = LifecycleOperationPreviewPayload.CODEC.decode(buffer);
		} finally { buffer.release(); }
		require(decodedPreview.equals(previewPayload), "lifecycle preview quantities/blockers did not round-trip exactly");
		require(decodedPreview.requestGeneration() == 7L
				&& decodedPreview.refreshedSummary().summaryGeneration() == current.summary().summaryGeneration()
				&& decodedPreview.requestGeneration() != decodedPreview.refreshedSummary().summaryGeneration(),
				"lifecycle response confused the request token with the next action token");
		LifecycleOperationRequestPayload confirm = LifecycleOperationRequestPayload.confirm(REGION,
				new BlockPos(2, 3, 4), 19L, preview.operation(), preview.nonce());
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			LifecycleOperationRequestPayload.CODEC.encode(buffer, confirm);
			require(LifecycleOperationRequestPayload.CODEC.decode(buffer).equals(confirm),
					"lifecycle confirm nonce did not round-trip exactly");
		} finally { buffer.release(); }
	}

	private static ControllerSummaryPort.View controllerView(UUID regionId, ControllerSummary summary) {
		return new ControllerSummaryPort.View(regionId, summary.dimensionId(), summary.profileFingerprint(),
				summary.checkpointGeneration(), summary.networkGeneration(), summary.footprint(), summary.selected(),
				summary.operatorIntent(), summary.motionState(), summary.thermalState(), summary.availability(),
				summary.usage(), summary.inventory(), summary.gates(), summary.diagnostics());
	}

	private static void validateQueryAvailabilityReason() {
		FluidQueryResult unavailable = FluidQueryResult.unavailable(
				RegionAvailabilityReason.BUDGET, "runtime admission budget is insufficient");
		require(unavailable.status() == FluidQueryStatus.REGION_UNAVAILABLE
				&& unavailable.availabilityReason() == RegionAvailabilityReason.BUDGET
				&& unavailable.observation() == null,
				"Query availability lost its stable reason or exposed partial state");
		require(FluidQueryResult.absent(FluidQueryStatus.NO_REGION, "no region").availabilityReason() == null,
				"non-availability Query failure acquired an availability reason");
	}

	private static void validateNegotiationMatrix() {
		FluidProtocol.Offer offer = FluidProtocol.serverOffer();
		require(FluidProtocol.serverOffer(TestProfiles.pure()).equals(FluidProtocol.materialServerOffer())
				&& FluidProtocol.serverOffer(TestProfiles.materials()).equals(FluidProtocol.materialServerOffer())
				&& FluidProtocol.serverOffer(TestProfiles.chemical(mmm.fluid.TestFluidDefinitions.load().chemistry()))
						.equals(FluidProtocol.thermalServerOffer(true, false))
				&& FluidProtocol.serverOffer(TestProfiles.granular(null, mmm.fluid.TestFluidDefinitions.load().granular()))
						.equals(FluidProtocol.granularServerOffer(false))
				&& FluidProtocol.serverOffer(TestProfiles.granular(mmm.fluid.TestFluidDefinitions.load().chemistry(),
						mmm.fluid.TestFluidDefinitions.load().granular())).equals(FluidProtocol.thermalServerOffer(true, true)),
				"world capability profiles did not select exact semantic server layouts");
		FluidProtocol.Negotiation accepted = FluidProtocol.negotiate(offer);
		require((offer.requiredCapabilities() & FluidProtocol.CAP_DEMO_WORKBENCH) != 0
				&& !FluidProtocol.validateResponse(offer, new FluidProtocolResponsePayload(true, FluidProtocol.Layout.MATERIAL,
						FluidProtocol.REQUIRED_CAPABILITIES & ~FluidProtocol.CAP_DEMO_WORKBENCH, "")).accepted(),
				"demo workbench protocol must reject a client that lacks its capability");
		require((offer.requiredCapabilities() & FluidProtocol.CAP_CONTROLLER_FAILURES) != 0
				&& !FluidProtocol.validateResponse(offer, new FluidProtocolResponsePayload(true, FluidProtocol.Layout.MATERIAL,
						FluidProtocol.REQUIRED_CAPABILITIES & ~FluidProtocol.CAP_CONTROLLER_FAILURES, "")).accepted(),
				"controller failure responses require a compatible client");
		require(accepted.accepted() && accepted.selectedLayout() == FluidProtocol.Layout.MATERIAL
				&& accepted.capabilities() == (FluidProtocol.REQUIRED_CAPABILITIES | FluidProtocol.CAP_MATERIAL_SNAPSHOT),
				"matching layout/capability offer was not selected exactly");
		ProtocolSessions sessions = new ProtocolSessions();
		UUID viewer = new UUID(41L, 43L);
		sessions.accept(viewer, accepted);
		require(sessions.isNegotiated(viewer) && sessions.isNegotiated(viewer, FluidProtocol.Layout.MATERIAL)
				&& !sessions.isNegotiated(viewer, FluidProtocol.Layout.CHEMICAL),
				"runtime-owned protocol session lost its negotiated layout");
		sessions.remove(viewer);
		require(!sessions.isNegotiated(viewer), "removed protocol session remained negotiated");

		long unknown = 1L << 40;
		FluidProtocol.Negotiation missingRequired = FluidProtocol.negotiate(
				new FluidProtocol.Offer(FluidProtocol.Layout.MATERIAL, unknown, 0L)
		);
		require(!missingRequired.accepted() && missingRequired.failure().contains("0x"),
				"unknown required capability was accepted");
		try {
			sessions.accept(viewer, missingRequired);
			throw new AssertionError("rejected negotiation became a runtime session");
		} catch (IllegalArgumentException expected) {
			// expected
		}
		FluidProtocol.Negotiation ignoredOptional = FluidProtocol.negotiate(
				new FluidProtocol.Offer(FluidProtocol.Layout.MATERIAL, FluidProtocol.REQUIRED_CAPABILITIES, unknown)
		);
		require(ignoredOptional.accepted() && (ignoredOptional.capabilities() & unknown) == 0L,
				"unknown optional capability was not safely ignored");

		FluidProtocol.Negotiation forgedLayout = FluidProtocol.validateResponse(
				offer, new FluidProtocolResponsePayload(true, FluidProtocol.Layout.CHEMICAL,
						FluidProtocol.REQUIRED_CAPABILITIES, "")
		);
		require(!forgedLayout.accepted(), "client-selected unoffered layout was accepted");
		FluidProtocol.Negotiation missingResponseCapability = FluidProtocol.validateResponse(
				offer, new FluidProtocolResponsePayload(true, FluidProtocol.Layout.MATERIAL,
						FluidProtocol.CAP_REGION_IDENTITY, "")
		);
		require(!missingResponseCapability.accepted(), "response omitted a required capability");
		FluidProtocol.Offer futureOptionalOffer = new FluidProtocol.Offer(
				FluidProtocol.Layout.MATERIAL, FluidProtocol.REQUIRED_CAPABILITIES, unknown
		);
		FluidProtocol.Negotiation forgedOptionalCapability = FluidProtocol.validateResponse(
				futureOptionalOffer,
				new FluidProtocolResponsePayload(true, FluidProtocol.Layout.MATERIAL,
						FluidProtocol.REQUIRED_CAPABILITIES | unknown, "")
		);
		require(!forgedOptionalCapability.accepted(), "client selected a capability unsupported by this server");
	}

	private static void validateThermalSnapshot() {
		FluidProtocol.Offer offer = FluidProtocol.thermalServerOffer(false, false);
		FluidProtocol.Negotiation negotiation = FluidProtocol.negotiate(offer);
		long required = FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_THERMAL_SNAPSHOT;
		require(negotiation.accepted() && negotiation.selectedLayout() == FluidProtocol.Layout.THERMAL
				&& (negotiation.capabilities() & required) == required,
				"thermal layout did not negotiate material+thermal capabilities strictly");
		FluidProtocol.Negotiation omitted = FluidProtocol.validateResponse(offer,
				new FluidProtocolResponsePayload(true, FluidProtocol.Layout.THERMAL,
						FluidProtocol.REQUIRED_CAPABILITIES | FluidProtocol.CAP_MATERIAL_SNAPSHOT, ""));
		require(!omitted.accepted(), "thermal response omitted the thermal capability");

		FluidThermalSnapshotPayload payload = thermalSnapshot(11L, 17L, "e".repeat(64));
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		FluidThermalSnapshotPayload decoded;
		try {
			FluidThermalSnapshotPayload.CODEC.encode(buffer, payload);
			decoded = FluidThermalSnapshotPayload.CODEC.decode(buffer);
		} finally { buffer.release(); }
		require(decoded.material().generation() == 11L && decoded.material().sequence() == 17L
				&& decoded.registryFingerprint().equals(payload.registryFingerprint())
				&& Arrays.equals(decoded.cellTemperatureQ(), payload.cellTemperatureQ())
				&& Arrays.equals(decoded.coherentFraction(), payload.coherentFraction())
				&& Arrays.equals(decoded.debugFlags(), payload.debugFlags())
				&& decoded.gasNodes().equals(payload.gasNodes()),
				"thermal display payload did not round-trip");

		FluidThermalSnapshotLedger ledger = new FluidThermalSnapshotLedger();
		require(ledger.evaluate(thermalSnapshot(1L, 1L, "e".repeat(64)), negotiation)
				== FluidThermalSnapshotLedger.Decision.ACCEPT_SNAPSHOT, "first thermal snapshot was rejected");
		require(ledger.evaluate(thermalSnapshot(1L, 1L, "e".repeat(64)), negotiation)
				== FluidThermalSnapshotLedger.Decision.IGNORE_STALE, "duplicate thermal sequence was accepted");
		require(ledger.evaluate(thermalSnapshot(1L, 2L, "f".repeat(64)), negotiation)
				== FluidThermalSnapshotLedger.Decision.REJECT_REGISTRY, "foreign thermal fingerprint was accepted");
		require(ledger.evaluate(FluidThermalSnapshotPayload.removeRegion(
				FluidMaterialSnapshotPayload.removeRegion(DIMENSION, REGION, 1L, 3L)), negotiation)
				== FluidThermalSnapshotLedger.Decision.REMOVE_REGION, "thermal tombstone was rejected");
		require(ledger.evaluate(FluidThermalSnapshotPayload.clearAll(), negotiation)
				== FluidThermalSnapshotLedger.Decision.CLEAR_ALL, "thermal clear-all was rejected");

		FluidProtocol.Negotiation granularNegotiation = FluidProtocol.negotiate(
				FluidProtocol.thermalServerOffer(true, true));
		FluidGranularSnapshotPayload granular = granularSnapshot(2L, 1L, "c".repeat(64), true);
		FluidThermalSnapshotPayload composed = thermalSnapshot(
				granular.material(), 2L, 1L, "e".repeat(64), null, granular);
		require(new FluidThermalSnapshotLedger().evaluate(composed, granularNegotiation)
				== FluidThermalSnapshotLedger.Decision.ACCEPT_SNAPSHOT,
				"thermal layout rejected composable granular/chemical display layers");
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidThermalSnapshotPayload.CODEC.encode(buffer, composed);
			decoded = FluidThermalSnapshotPayload.CODEC.decode(buffer);
			require(decoded.granularLayer().material() == decoded.material()
					&& decoded.material().sameSurface(composed.material())
					&& Arrays.equals(decoded.granularLayer().chemical().topSpecies(), granular.chemical().topSpecies())
					&& buffer.readableBytes() == 0, "composed thermal codec must share its complete material base");
		} finally { buffer.release(); }

		FluidGranularSnapshotPayload defaultGranular = defaultSizedGranularSnapshot();
		FluidChemicalSnapshotPayload chemical = chemicalSnapshot(2L, 1L, "a".repeat(64));
		FluidThermalSnapshotPayload chemicalThermal = thermalSnapshot(materialSnapshot(2L, 1L),
				2L, 1L, "e".repeat(64), chemical, null);
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidThermalSnapshotPayload.CODEC.encode(buffer, chemicalThermal);
			decoded = FluidThermalSnapshotPayload.CODEC.decode(buffer);
			require(decoded.chemicalLayer().material() == decoded.material()
					&& Arrays.equals(decoded.chemicalLayer().ph(), chemical.ph()) && buffer.readableBytes() == 0,
					"thermal chemical layer shares equivalent material snapshots through the codec");
		} finally { buffer.release(); }
		int cells = 9_216; short[] temperatures = new short[cells];
		Arrays.fill(temperatures, (short) (300 * 16));
		FluidThermalSnapshotPayload defaultSized = new FluidThermalSnapshotPayload(FluidProtocol.Layout.THERMAL,
				defaultGranular.material(), "e".repeat(64), temperatures, new byte[cells], new byte[cells],
				List.of(new FluidThermalSnapshotPayload.GasNodeDisplay(1, 300 * 16, 0)), null, defaultGranular);
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidThermalSnapshotPayload.CODEC.encode(buffer, defaultSized);
			System.out.println("Composed thermal snapshot bytes: " + buffer.writerIndex());
			require(buffer.writerIndex() <= 300 * 1024,
					"default 9,216-cell composed thermal snapshot exceeded 300 KiB: " + buffer.writerIndex());
		} finally { buffer.release(); }
	}

	private static void validateGranularSnapshot() {
		FluidProtocol.Offer offer = FluidProtocol.granularServerOffer(false);
		FluidProtocol.Negotiation negotiation = FluidProtocol.negotiate(offer);
		long required = FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_GRANULAR_SNAPSHOT;
		require(negotiation.accepted() && negotiation.selectedLayout() == FluidProtocol.Layout.GRANULAR
				&& (negotiation.capabilities() & required) == required
				&& (negotiation.capabilities() & FluidProtocol.CAP_CHEMICAL_SNAPSHOT) == 0L,
				"granular layout did not negotiate material+granular capabilities strictly");
		FluidProtocol.Negotiation omitted = FluidProtocol.validateResponse(offer,
				new FluidProtocolResponsePayload(true, FluidProtocol.Layout.GRANULAR,
						FluidProtocol.REQUIRED_CAPABILITIES | FluidProtocol.CAP_MATERIAL_SNAPSHOT, ""));
		require(!omitted.accepted(), "granular response omitted the granular capability");
        var oldClient=FluidProtocol.validateResponse(offer,new FluidProtocolResponsePayload(true,FluidProtocol.Layout.GRANULAR,
                offer.requiredCapabilities() & ~FluidProtocol.CAP_GRANULAR_FIXED_MASKS,""));
        require(!oldClient.accepted(),"granular client without fixed masks was accepted");

		FluidGranularSnapshotPayload payload = granularSnapshot(9L, 13L, "c".repeat(64), false);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer()); FluidGranularSnapshotPayload decoded;
		try {
			FluidGranularSnapshotPayload.CODEC.encode(buffer, payload);
			decoded = FluidGranularSnapshotPayload.CODEC.decode(buffer);
		} finally { buffer.release(); }
		require(decoded.material().generation() == 9L && decoded.material().sequence() == 13L
				&& decoded.registryFingerprint().equals(payload.registryFingerprint())
				&& decoded.granularMaterials().equals(payload.granularMaterials())
				&& Arrays.equals(decoded.fixedMasks(), payload.fixedMasks())
				&& Arrays.equals(decoded.depositBulkFill(), payload.depositBulkFill())
				&& Arrays.equals(decoded.depositPorosity(), payload.depositPorosity())
				&& Arrays.equals(decoded.depositTopMaterial(), payload.depositTopMaterial())
				&& decoded.activeParcels().equals(payload.activeParcels()) && decoded.chemical() == null,
				"granular display payload did not round-trip");

		FluidGranularSnapshotLedger ledger = new FluidGranularSnapshotLedger();
		require(ledger.evaluate(granularSnapshot(1L, 1L, "c".repeat(64), false), negotiation)
				== FluidGranularSnapshotLedger.Decision.ACCEPT_SNAPSHOT, "first granular snapshot was rejected");
		require(ledger.evaluate(granularSnapshot(1L, 1L, "c".repeat(64), false), negotiation)
				== FluidGranularSnapshotLedger.Decision.IGNORE_STALE, "duplicate granular sequence was accepted");
		require(ledger.evaluate(granularSnapshot(1L, 2L, "d".repeat(64), false), negotiation)
				== FluidGranularSnapshotLedger.Decision.REJECT_REGISTRY, "foreign granular fingerprint was accepted");
		require(ledger.evaluate(FluidGranularSnapshotPayload.removeRegion(
				FluidMaterialSnapshotPayload.removeRegion(DIMENSION, REGION, 1L, 3L)), negotiation)
				== FluidGranularSnapshotLedger.Decision.REMOVE_REGION, "granular tombstone was rejected");
		require(ledger.evaluate(granularSnapshot(1L, 2L, "c".repeat(64), false), negotiation)
				== FluidGranularSnapshotLedger.Decision.IGNORE_STALE, "granular tombstone allowed resurrection");
		require(ledger.evaluate(FluidGranularSnapshotPayload.clearAll(), negotiation)
				== FluidGranularSnapshotLedger.Decision.CLEAR_ALL, "granular clear-all was rejected");

		FluidProtocol.Negotiation chemicalNegotiation = FluidProtocol.negotiate(FluidProtocol.granularServerOffer(true));
		require((chemicalNegotiation.capabilities() & FluidProtocol.CAP_CHEMICAL_SNAPSHOT) != 0L,
				"chemical granular layout omitted the chemical display capability");
		FluidGranularSnapshotLedger chemicalLedger = new FluidGranularSnapshotLedger();
		require(chemicalLedger.evaluate(granularSnapshot(1L, 1L, "c".repeat(64), true), chemicalNegotiation)
				== FluidGranularSnapshotLedger.Decision.ACCEPT_SNAPSHOT,
				"granular chemical layer was rejected");
		require(new FluidGranularSnapshotLedger().evaluate(
				granularSnapshot(1L, 1L, "c".repeat(64), false), chemicalNegotiation)
				== FluidGranularSnapshotLedger.Decision.REJECT_PROTOCOL,
				"chemical granular negotiation accepted an omitted chemical layer");

		FluidGranularSnapshotPayload defaultSized = defaultSizedGranularSnapshot();
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidGranularSnapshotPayload.CODEC.encode(buffer, defaultSized);
			require(buffer.writerIndex() <= 512 * 1024,
					"default 9,216-cell granular snapshot exceeded 512 KiB: " + buffer.writerIndex());
		} finally { buffer.release(); }
	}

	private static void validateChemicalSnapshot() {
		FluidProtocol.Offer offer = FluidProtocol.chemicalServerOffer();
		FluidProtocol.Negotiation negotiation = FluidProtocol.negotiate(offer);
		long required = FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_CHEMICAL_SNAPSHOT;
		require(negotiation.accepted() && negotiation.selectedLayout() == FluidProtocol.Layout.CHEMICAL
				&& (negotiation.capabilities() & required) == required,
				"chemical layout did not negotiate material+chemistry capabilities strictly");
		FluidProtocol.Negotiation omitted = FluidProtocol.validateResponse(offer,
				new FluidProtocolResponsePayload(true, FluidProtocol.Layout.CHEMICAL,
						FluidProtocol.REQUIRED_CAPABILITIES | FluidProtocol.CAP_MATERIAL_SNAPSHOT, ""));
		require(!omitted.accepted(), "chemical response omitted the chemistry capability");

		FluidChemicalSnapshotPayload payload = chemicalSnapshot(7L, 11L, "a".repeat(64));
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		FluidChemicalSnapshotPayload decoded;
		try {
			FluidChemicalSnapshotPayload.CODEC.encode(buffer, payload);
			decoded = FluidChemicalSnapshotPayload.CODEC.decode(buffer);
		} finally { buffer.release(); }
		require(decoded.material().generation() == 7L && decoded.material().sequence() == 11L
				&& decoded.registryFingerprint().equals(payload.registryFingerprint())
				&& decoded.species().equals(payload.species())
				&& Arrays.equals(decoded.topSpecies(), payload.topSpecies())
				&& Arrays.equals(decoded.topWeights(), payload.topWeights())
				&& Arrays.equals(decoded.residualWeights(), payload.residualWeights())
				&& Arrays.equals(decoded.ph(), payload.ph()),
				"chemical display payload did not round-trip");
		for (int cell = 0; cell < decoded.residualWeights().length; cell++) {
			int sum = Byte.toUnsignedInt(decoded.residualWeights()[cell]);
			for (int slot = 0; slot < 4; slot++) sum += Byte.toUnsignedInt(decoded.topWeights()[cell * 4 + slot]);
			require(sum == 255, "chemical Top-4 quantization did not close");
		}

		FluidChemicalSnapshotLedger ledger = new FluidChemicalSnapshotLedger();
		require(ledger.evaluate(chemicalSnapshot(1L, 1L, "a".repeat(64)), negotiation)
				== FluidChemicalSnapshotLedger.Decision.ACCEPT_SNAPSHOT, "first chemical snapshot was rejected");
		require(ledger.evaluate(chemicalSnapshot(1L, 1L, "a".repeat(64)), negotiation)
				== FluidChemicalSnapshotLedger.Decision.IGNORE_STALE, "duplicate chemical sequence was accepted");
		require(ledger.evaluate(chemicalSnapshot(1L, 2L, "b".repeat(64)), negotiation)
				== FluidChemicalSnapshotLedger.Decision.REJECT_REGISTRY, "foreign chemical fingerprint was accepted");
		require(ledger.evaluate(FluidChemicalSnapshotPayload.removeRegion(
				FluidMaterialSnapshotPayload.removeRegion(DIMENSION, REGION, 1L, 3L)), negotiation)
				== FluidChemicalSnapshotLedger.Decision.REMOVE_REGION, "chemical tombstone was rejected");
		require(ledger.evaluate(chemicalSnapshot(1L, 2L, "a".repeat(64)), negotiation)
				== FluidChemicalSnapshotLedger.Decision.IGNORE_STALE, "chemical tombstone allowed resurrection");
		require(ledger.evaluate(FluidChemicalSnapshotPayload.clearAll(), negotiation)
				== FluidChemicalSnapshotLedger.Decision.CLEAR_ALL, "chemical clear-all was rejected");

		FluidChemicalSnapshotPayload defaultSized = defaultSizedChemicalSnapshot();
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidChemicalSnapshotPayload.CODEC.encode(buffer, defaultSized);
			require(buffer.writerIndex() <= 512 * 1024,
					"default 9,216-cell chemical snapshot exceeded 512 KiB: " + buffer.writerIndex());
		} finally { buffer.release(); }
	}

	private static void validateConfigurationCodecs() {
		FluidProtocolOfferPayload offer = new FluidProtocolOfferPayload(FluidProtocol.serverOffer());
		FluidProtocolOfferPayload decodedOffer;
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidProtocolOfferPayload.CODEC.encode(buffer, offer);
			decodedOffer = FluidProtocolOfferPayload.CODEC.decode(buffer);
		} finally {
			buffer.release();
		}
		require(decodedOffer.equals(offer), "configuration offer codec changed fields");

		FluidProtocolResponsePayload response = FluidProtocolResponsePayload.from(FluidProtocol.negotiate(offer.offer()));
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidProtocolResponsePayload.CODEC.encode(buffer, response);
			require(FluidProtocolResponsePayload.CODEC.decode(buffer).equals(response),
					"configuration response codec changed fields");
		} finally {
			buffer.release();
		}
	}

	private static void validateCompactSurface() {
		for(int grid:new int[]{2,64}) {
			int cells=grid*grid*grid;var fluids=new mmm.fluid.FluidMaterialRegistry(List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material()));
			float[][] volume={new float[cells]};Arrays.fill(volume[0],.75f);float[] planes=new float[4*cells],micro=new float[8*cells];
			for(int c=0;c<cells;c++){planes[4*c+1]=1;planes[4*c+3]=.75f;for(int m=0;m<8;m++)micro[8*c+m]=(m&2)==0?.125f:.0625f;}
			long start=System.nanoTime();var snapshot=mmm.fluid.FluidSurfaceProjection.fromDeviceFields(grid,grid,grid,.5f,fluids,volume,planes,micro,new byte[cells]);
			var payload=FluidMaterialSnapshotPayload.snapshot(DIMENSION,REGION,1,1,BlockPos.ZERO,snapshot,false);
			var buffer=new FriendlyByteBuf(Unpooled.buffer());
			try{
				FluidMaterialSnapshotPayload.CODEC.encode(buffer,payload);int bytes=buffer.writerIndex();var decoded=FluidMaterialSnapshotPayload.CODEC.decode(buffer);
				require(decoded.geometry().equals(snapshot.geometry()),"Compact geometry did not round-trip");
				require(decoded.geometry().materialAt(.1,.3,.1)==0&&decoded.geometry().materialAt(.1,.4,.1)==-1,"Compact surface point query differs");
				if(grid==2)require(!FluidMaterialSurfaceMesher.build(decoded.toRenderSnapshot()).outerTriangles().isEmpty(),"Compact client mesh is empty");
				System.out.printf("Compact surface grid=%d virtual_pieces=%d bytes=%d projection_codec_ms=%.3f%n",grid,snapshot.geometry().pieces().size(),bytes,(System.nanoTime()-start)*1e-6);
			}finally{buffer.release();}
		}
	}

	private static void validateMaterialSnapshot() {
		FluidProtocol.Offer offer = FluidProtocol.materialServerOffer();
		FluidProtocol.Negotiation negotiation = FluidProtocol.negotiate(offer);
		require(negotiation.accepted() && negotiation.selectedLayout() == FluidProtocol.Layout.MATERIAL
				&& (negotiation.capabilities() & FluidProtocol.CAP_MATERIAL_SNAPSHOT) != 0L,
				"material capability was not negotiated explicitly");
		FluidProtocol.Negotiation omittedCapability = FluidProtocol.validateResponse(
				offer, new FluidProtocolResponsePayload(true, FluidProtocol.Layout.MATERIAL,
						FluidProtocol.REQUIRED_CAPABILITIES, ""));
		require(!omittedCapability.accepted(), "material response omitted the required capability");

		FluidMaterialSnapshotPayload payload = materialSnapshot(4L, 9L);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		FluidMaterialSnapshotPayload decoded;
		try {
			long encodeStart = System.nanoTime();
			FluidMaterialSnapshotPayload.CODEC.encode(buffer, payload);
			long encodeNanos = System.nanoTime() - encodeStart;
			System.out.printf("Local material snapshot: pieces=%d bytes=%d encode_us=%.3f%n",
					payload.geometry().pieces().size(), buffer.writerIndex(), encodeNanos / 1_000.0);
			decoded = FluidMaterialSnapshotPayload.CODEC.decode(buffer);
		} finally {
			buffer.release();
		}
		require(decoded.layout() == FluidProtocol.Layout.MATERIAL && decoded.generation() == 4L && decoded.sequence() == 9L
				&& decoded.materials().equals(payload.materials()) && decoded.phaseGroups().equals(payload.phaseGroups()),
				"material identity/palette did not round-trip");
		require(decoded.geometry().equals(payload.geometry())
				&& Arrays.deepEquals(decoded.materialFractions(), payload.materialFractions())
				&& Arrays.deepEquals(decoded.phaseFractions(), payload.phaseFractions()),
				"material reconstruction fields did not round-trip");
		require(decoded.extensions().size() == 1 && decoded.extensions().getFirst().type() == 91
				&& Arrays.equals(decoded.extensions().getFirst().data(), new byte[] {4, 5, 6}),
				"material unknown optional section was not length-delimited");
		FluidMaterialRenderSnapshot renderSnapshot = decoded.toRenderSnapshot();
		require(renderSnapshot.materials().size() == decoded.materials().size()
				&& renderSnapshot.geometry().equals(decoded.geometry()),
				"material payload did not rebuild CPU mesher fields");
		FluidMaterialSurfaceMesher.Mesh mesh = FluidMaterialSurfaceMesher.build(renderSnapshot);
		require(!mesh.outerTriangles().isEmpty() && mesh.overlappingCategoryTriangles() == 0,
				"material payload lost its local clipped surfaces");
		FluidSurfaceInput.Plane thin = decoded.geometry().pieces().stream().filter(piece -> piece.cell() == 1).findFirst().orElseThrow().clips().getFirst();
		require(Double.doubleToRawLongBits(thin.offset()) == Double.doubleToRawLongBits(1.0e-10),
				"material codec rounded an unresolved thin layer");
		FluidSurfaceInput changed = new FluidSurfaceInput(2, 2, 2, 0.5F, List.of());
		FluidMaterialSnapshotPayload changedGeometry = new FluidMaterialSnapshotPayload(payload.layout(), payload.kind(),
				payload.dimension(), payload.regionId(), payload.generation(), payload.sequence(), payload.origin(),
				payload.cellSize(), payload.sizeX(), payload.sizeY(), payload.sizeZ(), payload.materials(),
				payload.phaseGroups(), changed, payload.materialFractions(), payload.phaseFractions(),
				payload.debugOverlays(), payload.extensions());
		require(!payload.sameSurface(changedGeometry), "material cache ignored changed local geometry");
		FluidMaterialSnapshotPayload generated = FluidMaterialSnapshotPayload.snapshot(
				DIMENSION, REGION, 5L, 1L, decoded.origin(), renderSnapshot, false);
		require(generated.geometry().equals(decoded.geometry())
				&& Arrays.deepEquals(generated.materialFractions(), decoded.materialFractions())
				&& Arrays.deepEquals(generated.phaseFractions(), decoded.phaseFractions()),
				"server material snapshot factory changed quantized reconstruction fields");

		FluidMaterialSnapshotPayload base = defaultSizedChemicalSnapshot().material();
		List<FluidSurfaceInput.Piece> pieces = new ArrayList<>();
		for (int cell = 0; cell < 9_216; cell++) for (int micro = 0; micro < 8; micro++) {
			pieces.add(new FluidSurfaceInput.Piece(cell, micro, 0, 0,
					List.of(new FluidSurfaceInput.Plane(0.2, 1, 0.1, 0.9))));
		}
		FluidMaterialSnapshotPayload large = new FluidMaterialSnapshotPayload(base.layout(), base.kind(),
				base.dimension(), base.regionId(), base.generation(), base.sequence(), base.origin(),
				base.cellSize(), base.sizeX(), base.sizeY(), base.sizeZ(), base.materials(), base.phaseGroups(),
				new FluidSurfaceInput(24, 16, 24, 0.5F, pieces),
				base.materialFractions(), base.phaseFractions(), false, List.of());
		long encodeStart = System.nanoTime();
		var parts = FluidSnapshotPartPayload.split(large);
		long encodeNanos = System.nanoTime() - encodeStart;
		int bytes = parts.getFirst().totalBytes();
		require(bytes > 1 << 20 && parts.size() > 1, "large surface did not exercise snapshot transport splitting");
		var assembler = new FluidSnapshotPartPayload.Assembler();
		for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
			buffer = new FriendlyByteBuf(Unpooled.buffer());
			try {
				FluidSnapshotPartPayload.CODEC.encode(buffer, parts.get(partIndex));
				require(buffer.writerIndex() < 1 << 20, "snapshot transport part exceeds Minecraft payload limit");
				var assembled = assembler.accept(FluidSnapshotPartPayload.CODEC.decode(buffer));
				if (partIndex + 1 < parts.size()) require(assembled == null, "partial surface changed the displayed frame");
				else require(assembled instanceof FluidMaterialSnapshotPayload complete && complete.sameSurface(large),
						"large local geometry did not survive transport assembly");
			} finally { buffer.release(); }
		}
		System.out.printf("Large local material snapshot: cells=9216 pieces=%d bytes=%d parts=%d encode_us=%.3f%n",
				pieces.size(), bytes, parts.size(), encodeNanos / 1_000.0);

		FluidMaterialSnapshotLedger ledger = new FluidMaterialSnapshotLedger();
		require(ledger.evaluate(materialSnapshot(1L, 1L), negotiation)
				== FluidMaterialSnapshotLedger.Decision.ACCEPT_SNAPSHOT, "first material snapshot was rejected");
		require(ledger.evaluate(materialSnapshot(1L, 1L), negotiation)
				== FluidMaterialSnapshotLedger.Decision.IGNORE_STALE, "duplicate material sequence was accepted");
		require(ledger.evaluate(FluidMaterialSnapshotPayload.removeRegion(DIMENSION, REGION, 1L, 2L), negotiation)
				== FluidMaterialSnapshotLedger.Decision.REMOVE_REGION, "material tombstone was rejected");
		require(ledger.evaluate(materialSnapshot(1L, 1L), negotiation)
				== FluidMaterialSnapshotLedger.Decision.IGNORE_STALE, "material tombstone allowed delayed resurrection");
		require(ledger.evaluate(materialSnapshot(2L, 2L), omittedCapability)
				== FluidMaterialSnapshotLedger.Decision.REJECT_PROTOCOL, "material snapshot bypassed capability negotiation");
		require(ledger.evaluate(FluidMaterialSnapshotPayload.clearAll(), negotiation)
				== FluidMaterialSnapshotLedger.Decision.CLEAR_ALL, "material clear-all was rejected");
	}

	private static FluidMaterialSnapshotPayload materialSnapshot(long generation, long sequence) {
		byte[][] fractions = {
				{(byte) 255, (byte) 200, (byte) 128, 0, (byte) 255, (byte) 100, 10, 1},
				{0, 55, 127, (byte) 255, 0, (byte) 155, (byte) 245, (byte) 254}
		};
		return new FluidMaterialSnapshotPayload(
				FluidProtocol.Layout.MATERIAL, FluidMaterialSnapshotPayload.Kind.SNAPSHOT,
				DIMENSION, REGION, generation, sequence, new BlockPos(2, 3, 4), 0.5F, 2, 2, 2,
				List.of(
						new FluidMaterialSnapshotPayload.MaterialEntry("mmm:water", 1_000.0, "mmm:aqueous", 0xff197ae8),
						new FluidMaterialSnapshotPayload.MaterialEntry("mmm:oil", 800.0, "mmm:oil", 0xffc89a32)),
				List.of("mmm:aqueous", "mmm:oil"),
				materialGeometry(), fractions, fractions, false,
				List.of(new FluidMaterialSnapshotPayload.Extension(91, new byte[] {4, 5, 6}))
		);
	}

	private static FluidSurfaceInput materialGeometry() {
		return new FluidSurfaceInput(2, 2, 2, 0.5F, List.of(
				new FluidSurfaceInput.Piece(0, 0, 0, 0,
						List.of(new FluidSurfaceInput.Plane(0.2, 1, 0.1, 0.35))),
				new FluidSurfaceInput.Piece(1, 0, 0, 0,
						List.of(new FluidSurfaceInput.Plane(0, 1, 0, 1.0e-10))),
				new FluidSurfaceInput.Piece(0, 1, 1, 1,
						List.of(new FluidSurfaceInput.Plane(0, 1, 0, 0.4)))));
	}

	private static FluidChemicalSnapshotPayload chemicalSnapshot(long generation, long sequence, String fingerprint) {
		short[] top = new short[8 * 4]; Arrays.fill(top, (short) -1);
		byte[] weights = new byte[top.length];
		for (int cell = 0; cell < 8; cell++) { top[cell * 4] = 0; weights[cell * 4] = (byte) 255; }
		byte[] ph = new byte[16]; Arrays.fill(ph, (byte) 255); Arrays.fill(ph, 0, 8, (byte) 70);
		return new FluidChemicalSnapshotPayload(FluidProtocol.Layout.CHEMICAL, materialSnapshot(generation, sequence), fingerprint,
				List.of(new FluidChemicalSnapshotPayload.SpeciesEntry("mmm:h2o", 0xff197ae8)),
				top, weights, new byte[8], ph);
	}

	private static FluidThermalSnapshotPayload thermalSnapshot(long generation, long sequence, String fingerprint) {
		return thermalSnapshot(materialSnapshot(generation, sequence), generation, sequence, fingerprint, null, null);
	}

	private static FluidThermalSnapshotPayload thermalSnapshot(
			FluidMaterialSnapshotPayload material, long generation, long sequence, String fingerprint,
			FluidChemicalSnapshotPayload chemical, FluidGranularSnapshotPayload granular
	) {
		short[] temperature = new short[8]; Arrays.fill(temperature, (short) (300 * 16));
		temperature[7] = (short) FluidThermalSnapshotPayload.EMPTY_TEMPERATURE_Q;
		byte[] coherent = new byte[8]; coherent[0] = (byte) 128;
		byte[] flags = new byte[8]; flags[1] = 1;
		return new FluidThermalSnapshotPayload(FluidProtocol.Layout.THERMAL, material, fingerprint, temperature, coherent, flags,
				List.of(new FluidThermalSnapshotPayload.GasNodeDisplay(1, 310 * 16, -123)), chemical, granular);
	}

	private static FluidChemicalSnapshotPayload defaultSizedChemicalSnapshot() {
		int cells = 9_216;
		byte[] full = new byte[cells]; Arrays.fill(full, (byte) 255);
		byte[] markers = new byte[cells]; Arrays.fill(markers, (byte) 1);
		FluidMaterialSnapshotPayload material = new FluidMaterialSnapshotPayload(FluidProtocol.Layout.MATERIAL,
				FluidMaterialSnapshotPayload.Kind.SNAPSHOT, DIMENSION, REGION, 1L, 1L, BlockPos.ZERO,
				0.5F, 24, 16, 24, List.of(new FluidMaterialSnapshotPayload.MaterialEntry(
				"mmm:water", 1_000.0, "mmm:aqueous", 0xff197ae8)),
				List.of("mmm:aqueous"), new FluidSurfaceInput(24, 16, 24, 0.5F, List.of()),
				new byte[][] {full}, new byte[][] {full}, false, List.of());
		short[] top = new short[cells * 4]; Arrays.fill(top, (short) -1);
		byte[] weights = new byte[cells * 4];
		for (int cell = 0; cell < cells; cell++) { top[cell * 4] = 0; weights[cell * 4] = (byte) 255; }
		byte[] ph = new byte[cells]; Arrays.fill(ph, (byte) 70);
		return new FluidChemicalSnapshotPayload(FluidProtocol.Layout.CHEMICAL, material, "a".repeat(64),
				List.of(new FluidChemicalSnapshotPayload.SpeciesEntry("mmm:h2o", 0xff197ae8)),
				top, weights, new byte[cells], ph);
	}

	private static FluidGranularSnapshotPayload granularSnapshot(
			long generation, long sequence, String fingerprint, boolean withChemical
	) {
		byte[] bulk = {(byte) 160, 0, 0, 0, 0, 0, 0, 0};
		byte[] porosity = {(byte) 102, 0, 0, 0, 0, 0, 0, 0};
        byte[] masks = {(byte) 0xfa, 0, 0, 0, 0, 0, 0, 0};
		short[] top = {0, -1, -1, -1, -1, -1, -1, -1};
		FluidChemicalSnapshotPayload chemical = withChemical
				? chemicalSnapshot(generation, sequence, "a".repeat(64)) : null;
		FluidGranularSnapshotPayload.ChemicalLayer layer = chemical == null ? null
				: new FluidGranularSnapshotPayload.ChemicalLayer(chemical.registryFingerprint(), chemical.species(),
				chemical.topSpecies(), chemical.topWeights(), chemical.residualWeights(), chemical.ph());
		return new FluidGranularSnapshotPayload(FluidProtocol.Layout.GRANULAR, materialSnapshot(generation, sequence), fingerprint,
				List.of(new GranularDisplaySnapshot.MaterialEntry("mmm:fine_sand", 0xffc2a065)),
				masks, bulk, porosity, top, List.of(new GranularDisplaySnapshot.ParcelSample(12_000, 24_000,
				36_000, 32, 0)), layer);
	}

	private static FluidGranularSnapshotPayload defaultSizedGranularSnapshot() {
		FluidChemicalSnapshotPayload chemical = defaultSizedChemicalSnapshot(); int cells = 9_216;
		byte[] bulk = new byte[cells], porosity = new byte[cells]; short[] top = new short[cells];
		Arrays.fill(top, (short) -1); bulk[0] = (byte) 128; porosity[0] = (byte) 102; top[0] = 0;
		return new FluidGranularSnapshotPayload(FluidProtocol.Layout.GRANULAR, chemical.material(), "c".repeat(64),
				List.of(new GranularDisplaySnapshot.MaterialEntry("mmm:fine_sand", 0xffc2a065)),
				new byte[bulk.length], bulk, porosity, top, List.of(new GranularDisplaySnapshot.ParcelSample(1, 2, 3, 16, 0)),
				new FluidGranularSnapshotPayload.ChemicalLayer(chemical.registryFingerprint(), chemical.species(),
						chemical.topSpecies(), chemical.topWeights(), chemical.residualWeights(), chemical.ph()));
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
