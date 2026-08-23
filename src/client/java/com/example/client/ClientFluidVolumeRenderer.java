package com.example.client;

import com.example.RealWaterMod;
import com.example.fluid.FluidMaterialRenderSnapshot;
import com.example.fluid.FluidMaterialSurfaceMesher;
import com.example.network.FluidMaterialSnapshotLedger;
import com.example.network.FluidMaterialSnapshotPayload;
import com.example.network.FluidChemicalSnapshotLedger;
import com.example.network.FluidChemicalSnapshotPayload;
import com.example.network.FluidGranularSnapshotLedger;
import com.example.network.FluidGranularSnapshotPayload;
import com.example.chemistry.ChemicalDisplaySnapshot;
import com.example.network.FluidProtocol;
import com.example.network.FluidSnapshotPayload;
import com.example.network.FluidSnapshotLedger;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Rebuilds a translucent, current-state surface from the latest volume
 * snapshot. Unlike particles, this contains no history and cannot drift.
 */
public final class ClientFluidVolumeRenderer implements AutoCloseable {
	private static final int SURFACE_THRESHOLD = 8;
	private static final float ISO_LEVEL = 0.50F;
	private static final float MARKER_INTERIOR_LEVEL = 0.65F;
	private static final int[][] CUBE_TETRAHEDRA = {
			{0, 5, 1, 6}, {0, 1, 2, 6}, {0, 2, 3, 6},
			{0, 3, 7, 6}, {0, 7, 4, 6}, {0, 4, 5, 6}
	};
	private static final long SNAPSHOT_BLEND_NANOS = 200_000_000L;
	private static final float WATER_SIDE_RED = 0.025F;
	private static final float WATER_SIDE_GREEN = 0.27F;
	private static final float WATER_SIDE_BLUE = 0.78F;
	private static final float WATER_SIDE_ALPHA = 0.56F;
	private static final float WATER_TOP_RED = 0.10F;
	private static final float WATER_TOP_GREEN = 0.48F;
	private static final float WATER_TOP_BLUE = 0.98F;
	private static final float WATER_TOP_ALPHA = 0.68F;
	private static final float SOLID_RED = 1.0F;
	private static final float SOLID_GREEN = 0.42F;
	private static final float SOLID_BLUE = 0.08F;
	private static final float SOLID_ALPHA = 0.20F;
	private static final float GAS_RED = 0.55F;
	private static final float GAS_GREEN = 0.55F;
	private static final float GAS_BLUE = 0.58F;
	private static final float GAS_ALPHA = 0.18F;
	private static final float BOUNDS_ALPHA = 0.82F;

	private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	private final StagedVertexBuffer stagedBuffer = new StagedVertexBuffer(
			() -> "Real Water debug surface",
			RenderType.BIG_BUFFER_SIZE
	);
	private final FluidSnapshotLedger ledger = new FluidSnapshotLedger();
	private final FluidMaterialSnapshotLedger materialLedger = new FluidMaterialSnapshotLedger();
	private final FluidChemicalSnapshotLedger chemicalLedger = new FluidChemicalSnapshotLedger();
	private final FluidGranularSnapshotLedger granularLedger = new FluidGranularSnapshotLedger();
	private final Map<UUID, SnapshotTransition> latest = new LinkedHashMap<>();
	private final Map<UUID, MaterialFrame> latestMaterials = new LinkedHashMap<>();
	private final Map<UUID, GranularFrame> latestGranular = new LinkedHashMap<>();
	private volatile List<FluidSnapshotPayload> extracted = List.of();
	private volatile List<MaterialFrame> extractedMaterials = List.of();
	private volatile List<GranularFrame> extractedGranular = List.of();

	public synchronized void accept(FluidSnapshotPayload payload, int negotiatedVersion) {
		FluidSnapshotLedger.Decision decision = ledger.evaluate(payload, negotiatedVersion);
		switch (decision) {
			case CLEAR_ALL -> latest.clear();
			case REMOVE_REGION -> latest.remove(payload.regionId());
			case ACCEPT_SNAPSHOT -> {
				SnapshotTransition old = latest.get(payload.regionId());
				FluidSnapshotPayload from = old == null ? payload : old.to();
				latest.put(payload.regionId(), new SnapshotTransition(from, payload, System.nanoTime()));
			}
			case REJECT_PROTOCOL -> RealWaterMod.LOGGER.error(
					"Discarded Real Water snapshot protocol {} after negotiating {}",
					payload.protocolVersion(), negotiatedVersion
			);
			case REJECT_PALETTE -> RealWaterMod.LOGGER.error(
					"Discarded Real Water region {} with unsupported palette {}",
					payload.regionId(), payload.paletteVersion()
			);
			case IGNORE_STALE -> { }
		}
	}

	public synchronized void accept(FluidMaterialSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		FluidMaterialSnapshotLedger.Decision decision = materialLedger.evaluate(payload, negotiation);
		switch (decision) {
			case CLEAR_ALL -> latestMaterials.clear();
			case REMOVE_REGION -> latestMaterials.remove(payload.regionId());
			case ACCEPT_SNAPSHOT -> {
				FluidMaterialRenderSnapshot snapshot = payload.toRenderSnapshot();
				latestMaterials.put(payload.regionId(), new MaterialFrame(
						payload, snapshot, FluidMaterialSurfaceMesher.build(snapshot), null
				));
			}
			case REJECT_PROTOCOL -> RealWaterMod.LOGGER.error(
					"Discarded Real Water material snapshot protocol {} after negotiation", payload.protocolVersion());
			case REJECT_PALETTE -> RealWaterMod.LOGGER.error(
					"Discarded Real Water material region {} with unsupported palette {}",
					payload.regionId(), payload.paletteVersion());
			case IGNORE_STALE -> { }
		}
	}

	public synchronized void accept(FluidChemicalSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		FluidChemicalSnapshotLedger.Decision decision = chemicalLedger.evaluate(payload, negotiation);
		FluidMaterialSnapshotPayload material = payload.material();
		switch (decision) {
			case CLEAR_ALL -> latestMaterials.clear();
			case REMOVE_REGION -> latestMaterials.remove(material.regionId());
			case ACCEPT_SNAPSHOT -> {
				FluidMaterialRenderSnapshot snapshot = material.toRenderSnapshot();
				latestMaterials.put(material.regionId(), new MaterialFrame(
						material, snapshot, FluidMaterialSurfaceMesher.build(snapshot), new ChemicalFrame(
								payload, payload.topSpecies(), payload.topWeights(), payload.residualWeights(),
								payload.ph(), payload.indicatorFraction())));
			}
			case REJECT_PROTOCOL -> RealWaterMod.LOGGER.error(
					"Discarded Real Water chemical snapshot protocol {} after negotiation", payload.protocolVersion());
			case REJECT_PALETTE -> RealWaterMod.LOGGER.error(
					"Discarded Real Water chemical region {} with unsupported material palette {}",
					material.regionId(), material.paletteVersion());
			case REJECT_REGISTRY -> RealWaterMod.LOGGER.error(
					"Discarded Real Water chemical region {} with a foreign registry fingerprint", material.regionId());
			case IGNORE_STALE -> { }
		}
	}

	public synchronized void accept(FluidGranularSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		FluidGranularSnapshotLedger.Decision decision = granularLedger.evaluate(payload, negotiation);
		FluidMaterialSnapshotPayload material = payload.material();
		switch (decision) {
			case CLEAR_ALL -> latestGranular.clear();
			case REMOVE_REGION -> latestGranular.remove(material.regionId());
			case ACCEPT_SNAPSHOT -> {
				FluidMaterialRenderSnapshot snapshot = material.toRenderSnapshot();
				FluidChemicalSnapshotPayload chemicalPayload = payload.toChemicalPayload();
				ChemicalFrame chemical = chemicalPayload == null ? null : new ChemicalFrame(chemicalPayload,
						chemicalPayload.topSpecies(), chemicalPayload.topWeights(), chemicalPayload.residualWeights(),
						chemicalPayload.ph(), chemicalPayload.indicatorFraction());
				MaterialFrame materialFrame = new MaterialFrame(material, snapshot,
						FluidMaterialSurfaceMesher.build(snapshot), chemical);
				latestGranular.put(material.regionId(), new GranularFrame(payload, materialFrame,
						payload.depositBulkFill(), payload.depositPorosity(), payload.depositTopMaterial()));
			}
			case REJECT_PROTOCOL -> RealWaterMod.LOGGER.error(
					"Discarded Real Water granular snapshot protocol {} after negotiation", payload.protocolVersion());
			case REJECT_PALETTE -> RealWaterMod.LOGGER.error(
					"Discarded Real Water granular region {} with an unsupported display palette", material.regionId());
			case REJECT_REGISTRY -> RealWaterMod.LOGGER.error(
					"Discarded Real Water granular region {} with a foreign registry fingerprint", material.regionId());
			case IGNORE_STALE -> { }
		}
	}

	public void extract(LevelExtractionContext context) {
		List<SnapshotTransition> transitions;
		synchronized (this) {
			transitions = List.copyOf(latest.values());
		}
		long now = System.nanoTime();
		extracted = transitions.stream()
				.map(transition -> interpolate(transition, now))
				.filter(payload -> payload.dimension().equals(context.level().dimension().identifier()))
				.toList();
		synchronized (this) {
			extractedMaterials = latestMaterials.values().stream()
					.filter(frame -> frame.payload().dimension().equals(context.level().dimension().identifier()))
					.toList();
			extractedGranular = latestGranular.values().stream()
					.filter(frame -> frame.payload().material().dimension().equals(context.level().dimension().identifier()))
					.toList();
		}
	}

	private static FluidSnapshotPayload interpolate(SnapshotTransition transition, long now) {
		FluidSnapshotPayload from = transition.from();
		FluidSnapshotPayload to = transition.to();
		float blend = Math.min(1.0F, Math.max(0.0F, (float) (now - transition.startedAtNanos()) / SNAPSHOT_BLEND_NANOS));
		if (blend >= 1.0F || !compatible(from, to)) return to;
		byte[] volume = new byte[to.volume().length];
		byte[] markers = new byte[to.markers().length];
		for (int i = 0; i < volume.length; i++) {
			int first = Byte.toUnsignedInt(from.volume()[i]);
			int second = Byte.toUnsignedInt(to.volume()[i]);
			volume[i] = (byte) Math.round(first + (second - first) * blend);
			markers[i] = from.markers()[i] != 0 || to.markers()[i] != 0 ? (byte) 1 : (byte) 0;
		}
		return new FluidSnapshotPayload(
				to.protocolVersion(), to.kind(), to.dimension(), to.regionId(), to.generation(), to.sequence(),
				to.paletteVersion(), to.origin(), to.cellSize(), to.sizeX(), to.sizeY(), to.sizeZ(),
				volume, markers, to.solids(), to.debugOverlays(), to.extensions()
		);
	}

	private static boolean compatible(FluidSnapshotPayload first, FluidSnapshotPayload second) {
		return first.regionId().equals(second.regionId())
				&& first.generation() == second.generation()
				&& first.paletteVersion() == second.paletteVersion()
				&& first.dimension().equals(second.dimension())
				&& first.origin().equals(second.origin())
				&& Float.compare(first.cellSize(), second.cellSize()) == 0
				&& first.sizeX() == second.sizeX()
				&& first.sizeY() == second.sizeY()
				&& first.sizeZ() == second.sizeZ()
				&& first.volume().length == second.volume().length
				&& first.markers().length == second.markers().length;
	}

	public void render(LevelRenderContext context) {
		List<FluidSnapshotPayload> states = extracted;
		List<MaterialFrame> materialStates = extractedMaterials;
		List<GranularFrame> granularStates = extractedGranular;
		if (states.isEmpty() && materialStates.isEmpty() && granularStates.isEmpty()) return;

		RenderPipeline pipeline = RenderPipelines.DEBUG_FILLED_BOX;
		VertexFormat format = pipeline.getVertexFormatBinding(0);
		if (format == null) return;
		PrimitiveTopology primitive = pipeline.getPrimitiveTopology();
		StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(
				format,
				primitive,
				primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null
		);

		VertexConsumer vertices = stagedBuffer.getVertexBuilder(draw);
		for (FluidSnapshotPayload state : states) buildGeometry(context, vertices, state);
		for (MaterialFrame state : materialStates) buildMaterialGeometry(context, vertices, state);
		for (GranularFrame state : granularStates) {
			buildMaterialGeometry(context, vertices, state.material());
			buildGranularGeometry(context, vertices, state);
		}
		stagedBuffer.upload();
		StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(draw);
		if (info != null) execute(Minecraft.getInstance(), info, pipeline);
		stagedBuffer.endFrame();
	}

	private static void buildGeometry(LevelRenderContext context, VertexConsumer vertices, FluidSnapshotPayload state) {
		PoseStack poses = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		poses.pushPose();
		poses.translate(-camera.x, -camera.y, -camera.z);
		Matrix4fc matrix = poses.last().pose();
		float h = state.cellSize();

		renderFluidSurface(matrix, vertices, state, h);
		if (state.debugOverlays()) for (int z = 0; z < state.sizeZ(); z++) {
			for (int y = 0; y < state.sizeY(); y++) for (int x = 0; x < state.sizeX(); x++) {
				if (state.solids()[index(state, x, y, z)] != 0) renderSolidCell(matrix, vertices, state, x, y, z, h);
			}
		}
		byte[] gasNodes = gasNodeMask(state);
		if (state.debugOverlays() && gasNodes != null) for (int z = 0; z < state.sizeZ(); z++) {
			for (int y = 0; y < state.sizeY(); y++) for (int x = 0; x < state.sizeX(); x++) {
				if (gasNodes[index(state, x, y, z)] != 0) renderGasCell(matrix, vertices, state, gasNodes, x, y, z, h);
			}
		}

		if (state.debugOverlays()) renderBounds(matrix, vertices, state);
		poses.popPose();
	}

	/** Per-cell sealed gas node ids from the snapshot extension, or null when absent. */
	private static byte[] gasNodeMask(FluidSnapshotPayload state) {
		for (FluidSnapshotPayload.Extension extension : state.extensions()) {
			if (extension.type() == FluidSnapshotPayload.EXTENSION_GAS_NODES
					&& extension.data().length == state.volume().length) {
				return extension.data();
			}
		}
		return null;
	}

	private static void renderGasCell(
			Matrix4fc matrix, VertexConsumer vertices, FluidSnapshotPayload state, byte[] gasNodes,
			int x, int y, int z, float h
	) {
		float minX = state.origin().getX() + x * h;
		float minY = state.origin().getY() + y * h;
		float minZ = state.origin().getZ() + z * h;
		float maxX = minX + h;
		float maxY = minY + h;
		float maxZ = minZ + h;
		if (!isGas(gasNodes, state, x - 1, y, z)) quadX(matrix, vertices, minX, minY, maxY, minZ, maxZ, false, GAS_RED, GAS_GREEN, GAS_BLUE, GAS_ALPHA);
		if (!isGas(gasNodes, state, x + 1, y, z)) quadX(matrix, vertices, maxX, minY, maxY, minZ, maxZ, true, GAS_RED, GAS_GREEN, GAS_BLUE, GAS_ALPHA);
		if (!isGas(gasNodes, state, x, y - 1, z)) quadY(matrix, vertices, minY, minX, maxX, minZ, maxZ, false, GAS_RED, GAS_GREEN, GAS_BLUE, GAS_ALPHA);
		if (!isGas(gasNodes, state, x, y + 1, z)) quadY(matrix, vertices, maxY, minX, maxX, minZ, maxZ, true, GAS_RED, GAS_GREEN, GAS_BLUE, GAS_ALPHA);
		if (!isGas(gasNodes, state, x, y, z - 1)) quadZ(matrix, vertices, minZ, minX, maxX, minY, maxY, false, GAS_RED, GAS_GREEN, GAS_BLUE, GAS_ALPHA);
		if (!isGas(gasNodes, state, x, y, z + 1)) quadZ(matrix, vertices, maxZ, minX, maxX, minY, maxY, true, GAS_RED, GAS_GREEN, GAS_BLUE, GAS_ALPHA);
	}

	private static boolean isGas(byte[] gasNodes, FluidSnapshotPayload state, int x, int y, int z) {
		return inside(state, x, y, z) && gasNodes[index(state, x, y, z)] != 0;
	}

	private static void buildMaterialGeometry(LevelRenderContext context, VertexConsumer vertices, MaterialFrame frame) {
		FluidMaterialSnapshotPayload state = frame.payload();
		PoseStack poses = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		poses.pushPose();
		poses.translate(-camera.x, -camera.y, -camera.z);
		Matrix4fc matrix = poses.last().pose();
		for (FluidMaterialSurfaceMesher.Triangle triangle : frame.mesh().outerTriangles()) {
			int material = FluidMaterialSurfaceMesher.dominantMaterialAt(frame.snapshot(), triangle.centroid());
			if (material < 0) continue;
			int color = chemicalArgb(frame, triangle.centroid(), state.materials().get(material).argb());
			emitMaterialTriangle(matrix, vertices, state, triangle, color, 0.62F);
		}
		for (FluidMaterialSurfaceMesher.InterfaceTriangle owned : frame.mesh().interfaceTriangles()) {
			int first = phaseArgb(state, owned.firstPhase());
			int second = phaseArgb(state, owned.secondPhase());
			int color = chemicalArgb(frame, owned.triangle().centroid(), blendArgb(first, second));
			emitMaterialTriangle(matrix, vertices, state, owned.triangle(), color, 0.48F);
		}
		poses.popPose();
	}

	private static void buildGranularGeometry(LevelRenderContext context, VertexConsumer vertices, GranularFrame frame) {
		FluidGranularSnapshotPayload payload = frame.payload();
		FluidMaterialSnapshotPayload state = payload.material();
		PoseStack poses = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		poses.pushPose(); poses.translate(-camera.x, -camera.y, -camera.z);
		Matrix4fc matrix = poses.last().pose(); float h = state.cellSize();
		for (int z = 0; z < state.sizeZ(); z++) for (int y = 0; y < state.sizeY(); y++) {
			for (int x = 0; x < state.sizeX(); x++) {
				int cell = granularIndex(state, x, y, z); float fill = Byte.toUnsignedInt(frame.bulkFill()[cell]) / 255.0F;
				if (fill <= 0.0F) continue;
				int material = frame.topMaterial()[cell];
				int color = payload.granularMaterials().get(material).argb();
				float porosity = Byte.toUnsignedInt(frame.porosity()[cell]) / 255.0F;
				renderDepositCell(matrix, vertices, state, frame.bulkFill(), x, y, z, h, fill, color,
						0.72F + 0.18F * (1.0F - porosity));
			}
		}
		for (var sample : payload.activeParcels()) {
			float centerX = state.origin().getX() + sample.xQ() / 65_535.0F * state.sizeX() * h;
			float centerY = state.origin().getY() + sample.yQ() / 65_535.0F * state.sizeY() * h;
			float centerZ = state.origin().getZ() + sample.zQ() / 65_535.0F * state.sizeZ() * h;
			float half = Math.max(h / 64.0F, sample.diameterQ() / 255.0F * h * 0.5F);
			int color = payload.granularMaterials().get(sample.materialIndex()).argb();
			box(matrix, vertices, centerX - half, centerY - half, centerZ - half,
					centerX + half, centerY + half, centerZ + half,
					((color >>> 16) & 0xff) / 255.0F, ((color >>> 8) & 0xff) / 255.0F,
					(color & 0xff) / 255.0F, 0.92F);
		}
		poses.popPose();
	}

	private static void renderDepositCell(
			Matrix4fc matrix, VertexConsumer vertices, FluidMaterialSnapshotPayload state, byte[] bulk,
			int x, int y, int z, float h, float fill, int argb, float alpha
	) {
		float red = ((argb >>> 16) & 0xff) / 255.0F, green = ((argb >>> 8) & 0xff) / 255.0F,
				blue = (argb & 0xff) / 255.0F;
		float minX = state.origin().getX() + x * h, minY = state.origin().getY() + y * h,
				minZ = state.origin().getZ() + z * h;
		float maxX = minX + h, maxY = minY + h * fill, maxZ = minZ + h;
		float left = granularFill(state, bulk, x - 1, y, z) * h;
		if (left < h * fill) quadX(matrix, vertices, minX, minY + left, maxY, minZ, maxZ,
				false, red * 0.82F, green * 0.82F, blue * 0.82F, alpha);
		float right = granularFill(state, bulk, x + 1, y, z) * h;
		if (right < h * fill) quadX(matrix, vertices, maxX, minY + right, maxY, minZ, maxZ,
				true, red * 0.82F, green * 0.82F, blue * 0.82F, alpha);
		float back = granularFill(state, bulk, x, y, z - 1) * h;
		if (back < h * fill) quadZ(matrix, vertices, minZ, minX, maxX, minY + back, maxY,
				false, red * 0.86F, green * 0.86F, blue * 0.86F, alpha);
		float front = granularFill(state, bulk, x, y, z + 1) * h;
		if (front < h * fill) quadZ(matrix, vertices, maxZ, minX, maxX, minY + front, maxY,
				true, red * 0.86F, green * 0.86F, blue * 0.86F, alpha);
		if (fill < 1.0F || granularFill(state, bulk, x, y + 1, z) == 0.0F) {
			quadY(matrix, vertices, maxY, minX, maxX, minZ, maxZ, true, red, green, blue, alpha);
		}
		if (granularFill(state, bulk, x, y - 1, z) == 0.0F) {
			quadY(matrix, vertices, minY, minX, maxX, minZ, maxZ, false,
					red * 0.72F, green * 0.72F, blue * 0.72F, alpha);
		}
	}

	private static float granularFill(
			FluidMaterialSnapshotPayload state, byte[] bulk, int x, int y, int z
	) {
		if (x < 0 || x >= state.sizeX() || y < 0 || y >= state.sizeY() || z < 0 || z >= state.sizeZ()) return 0.0F;
		return Byte.toUnsignedInt(bulk[granularIndex(state, x, y, z)]) / 255.0F;
	}

	private static int granularIndex(FluidMaterialSnapshotPayload state, int x, int y, int z) {
		return (z * state.sizeY() + y) * state.sizeX() + x;
	}

	private static int chemicalArgb(MaterialFrame frame, FluidMaterialSurfaceMesher.Point point, int materialArgb) {
		ChemicalFrame chemical = frame.chemical();
		if (chemical == null) return materialArgb;
		FluidChemicalSnapshotPayload payload = chemical.payload();
		FluidMaterialSnapshotPayload state = frame.payload();
		int x = Math.max(0, Math.min(state.sizeX() - 1, (int) Math.floor(point.x() / state.cellSize())));
		int y = Math.max(0, Math.min(state.sizeY() - 1, (int) Math.floor(point.y() / state.cellSize())));
		int z = Math.max(0, Math.min(state.sizeZ() - 1, (int) Math.floor(point.z() / state.cellSize())));
		int cell = (z * state.sizeY() + y) * state.sizeX() + x;
		short[] indices = chemical.topSpecies(); byte[] weights = chemical.topWeights();
		int red = ((materialArgb >>> 16) & 0xff) * Byte.toUnsignedInt(chemical.residualWeights()[cell]);
		int green = ((materialArgb >>> 8) & 0xff) * Byte.toUnsignedInt(chemical.residualWeights()[cell]);
		int blue = (materialArgb & 0xff) * Byte.toUnsignedInt(chemical.residualWeights()[cell]);
		int weightTotal = Byte.toUnsignedInt(chemical.residualWeights()[cell]);
		for (int slot = 0; slot < ChemicalDisplaySnapshot.TOP_SPECIES; slot++) {
			int offset = cell * ChemicalDisplaySnapshot.TOP_SPECIES + slot;
			int species = indices[offset], weight = Byte.toUnsignedInt(weights[offset]);
			if (species < 0 || weight == 0) continue;
			int argb = payload.species().get(species).argb();
			red += ((argb >>> 16) & 0xff) * weight;
			green += ((argb >>> 8) & 0xff) * weight;
			blue += (argb & 0xff) * weight;
			weightTotal += weight;
		}
		if (weightTotal > 0) { red /= weightTotal; green /= weightTotal; blue /= weightTotal; }
		int indicator = Byte.toUnsignedInt(chemical.indicatorFraction()[cell]);
		if (indicator > 0) {
			double value = Byte.toUnsignedInt(chemical.ph()[cell]) * 14.0 / 255.0;
			int phColor = value < 7.0 ? 0xffff3048 : value > 7.0 ? 0xff305cff : 0xff35d06f;
			double blend = indicator / 255.0 * Math.min(1.0, Math.abs(value - 7.0) / 7.0 + 0.25);
			red = (int) Math.round(red * (1.0 - blend) + ((phColor >>> 16) & 0xff) * blend);
			green = (int) Math.round(green * (1.0 - blend) + ((phColor >>> 8) & 0xff) * blend);
			blue = (int) Math.round(blue * (1.0 - blend) + (phColor & 0xff) * blend);
		}
		return 0xff000000 | red << 16 | green << 8 | blue;
	}

	private static int phaseArgb(FluidMaterialSnapshotPayload state, int phase) {
		String group = state.phaseGroups().get(phase);
		for (FluidMaterialSnapshotPayload.MaterialEntry material : state.materials()) {
			if (material.phaseGroup().equals(group)) return material.argb();
		}
		return 0xffff00ff;
	}

	private static int blendArgb(int first, int second) {
		int red = (((first >>> 16) & 0xff) + ((second >>> 16) & 0xff)) / 2;
		int green = (((first >>> 8) & 0xff) + ((second >>> 8) & 0xff)) / 2;
		int blue = ((first & 0xff) + (second & 0xff)) / 2;
		return 0xff000000 | red << 16 | green << 8 | blue;
	}

	private static void emitMaterialTriangle(
			Matrix4fc matrix, VertexConsumer vertices, FluidMaterialSnapshotPayload state,
		FluidMaterialSurfaceMesher.Triangle triangle, int argb, float alpha
	) {
		FluidMaterialSurfaceMesher.Point normal = triangle.normal();
		double length = Math.sqrt(normal.x() * normal.x() + normal.y() * normal.y() + normal.z() * normal.z());
		float vertical = length <= 1.0E-9 ? 0.0F : (float) Math.abs(normal.y() / length);
		float light = 0.78F + 0.22F * vertical;
		float red = ((argb >>> 16) & 0xff) / 255.0F * light;
		float green = ((argb >>> 8) & 0xff) / 255.0F * light;
		float blue = (argb & 0xff) / 255.0F * light;
		emitMaterialVertex(matrix, vertices, state, triangle.a(), red, green, blue, alpha);
		emitMaterialVertex(matrix, vertices, state, triangle.b(), red, green, blue, alpha);
		emitMaterialVertex(matrix, vertices, state, triangle.c(), red, green, blue, alpha);
		emitMaterialVertex(matrix, vertices, state, triangle.c(), red, green, blue, alpha);
	}

	private static void emitMaterialVertex(
			Matrix4fc matrix, VertexConsumer vertices, FluidMaterialSnapshotPayload state,
			FluidMaterialSurfaceMesher.Point point, float red, float green, float blue, float alpha
	) {
		vertex(vertices, matrix,
				state.origin().getX() + (float) point.x(), state.origin().getY() + (float) point.y(),
				state.origin().getZ() + (float) point.z(), red, green, blue, alpha);
	}

	/** Extracts the 0.5 density isosurface with marching tetrahedra. */
	private static void renderFluidSurface(Matrix4fc matrix, VertexConsumer vertices, FluidSnapshotPayload state, float h) {
		Vector3f[] positions = new Vector3f[8];
		for (int i = 0; i < positions.length; i++) positions[i] = new Vector3f();
		float[] values = new float[8];
		int nodeSizeX = state.sizeX() + 2, nodeSizeY = state.sizeY() + 2, nodeSizeZ = state.sizeZ() + 2;
		float[] scalarNodes = new float[nodeSizeX * nodeSizeY * nodeSizeZ];
		for (int nodeZ = 0; nodeZ < nodeSizeZ; nodeZ++) for (int nodeY = 0; nodeY < nodeSizeY; nodeY++) for (int nodeX = 0; nodeX < nodeSizeX; nodeX++) {
			scalarNodes[(nodeZ * nodeSizeY + nodeY) * nodeSizeX + nodeX] = scalar(state, nodeX - 1, nodeY - 1, nodeZ - 1);
		}
		for (int z = -1; z < state.sizeZ(); z++) for (int y = -1; y < state.sizeY(); y++) for (int x = -1; x < state.sizeX(); x++) {
			setSurfaceNode(positions, values, 0, state, scalarNodes, nodeSizeX, nodeSizeY, x, y, z, h);
			setSurfaceNode(positions, values, 1, state, scalarNodes, nodeSizeX, nodeSizeY, x + 1, y, z, h);
			setSurfaceNode(positions, values, 2, state, scalarNodes, nodeSizeX, nodeSizeY, x + 1, y + 1, z, h);
			setSurfaceNode(positions, values, 3, state, scalarNodes, nodeSizeX, nodeSizeY, x, y + 1, z, h);
			setSurfaceNode(positions, values, 4, state, scalarNodes, nodeSizeX, nodeSizeY, x, y, z + 1, h);
			setSurfaceNode(positions, values, 5, state, scalarNodes, nodeSizeX, nodeSizeY, x + 1, y, z + 1, h);
			setSurfaceNode(positions, values, 6, state, scalarNodes, nodeSizeX, nodeSizeY, x + 1, y + 1, z + 1, h);
			setSurfaceNode(positions, values, 7, state, scalarNodes, nodeSizeX, nodeSizeY, x, y + 1, z + 1, h);
			boolean crosses = false;
			boolean first = values[0] >= ISO_LEVEL;
			for (int i = 1; i < values.length; i++) if ((values[i] >= ISO_LEVEL) != first) { crosses = true; break; }
			if (!crosses) continue;
			for (int[] tetrahedron : CUBE_TETRAHEDRA) renderTetrahedron(matrix, vertices, positions, values, tetrahedron);
		}
	}

	private static void setSurfaceNode(
			Vector3f[] positions, float[] values, int slot, FluidSnapshotPayload state,
			float[] scalarNodes, int nodeSizeX, int nodeSizeY,
			int x, int y, int z, float h
	) {
		positions[slot].set(
				state.origin().getX() + (x + 0.5F) * h,
				state.origin().getY() + (y + 0.5F) * h,
				state.origin().getZ() + (z + 0.5F) * h
		);
		values[slot] = scalarNodes[((z + 1) * nodeSizeY + (y + 1)) * nodeSizeX + (x + 1)];
	}

	private static float scalar(FluidSnapshotPayload state, int x, int y, int z) {
		if (!inside(state, x, y, z) || isSolid(state, x, y, z)) return 0.0F;
		float weightedDensity = 0.0F;
		float weightSum = 0.0F;
		for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
			int sampleX = x + dx, sampleY = y + dy, sampleZ = z + dz;
			if (!inside(state, sampleX, sampleY, sampleZ) || isSolid(state, sampleX, sampleY, sampleZ)) continue;
			float weight = kernelWeight(dx) * kernelWeight(dy) * kernelWeight(dz);
			int raw = Byte.toUnsignedInt(state.volume()[index(state, sampleX, sampleY, sampleZ)]);
			float density = raw < SURFACE_THRESHOLD ? 0.0F : raw / 255.0F;
			weightedDensity += density * weight;
			weightSum += weight;
		}
		float density = weightSum <= 1.0E-6F ? 0.0F : weightedDensity / weightSum;
		if (state.markers()[index(state, x, y, z)] != 0) density = Math.max(density, MARKER_INTERIOR_LEVEL);
		return density;
	}

	private static float kernelWeight(int offset) {
		return offset == 0 ? 2.0F : 1.0F;
	}

	private static void renderTetrahedron(
			Matrix4fc matrix, VertexConsumer vertices, Vector3f[] positions, float[] values, int[] tetrahedron
	) {
		int[] inside = new int[4], outside = new int[4];
		int insideCount = 0, outsideCount = 0;
		for (int vertex : tetrahedron) {
			if (values[vertex] >= ISO_LEVEL) inside[insideCount++] = vertex;
			else outside[outsideCount++] = vertex;
		}
		if (insideCount == 0 || insideCount == 4) return;
		if (insideCount == 1 || insideCount == 3) {
			int lone = insideCount == 1 ? inside[0] : outside[0];
			int[] others = insideCount == 1 ? outside : inside;
			emitSurfaceTriangle(matrix, vertices,
					isoPoint(positions, values, lone, others[0]),
					isoPoint(positions, values, lone, others[1]),
					isoPoint(positions, values, lone, others[2]));
		} else {
			Vector3f ac = isoPoint(positions, values, inside[0], outside[0]);
			Vector3f ad = isoPoint(positions, values, inside[0], outside[1]);
			Vector3f bc = isoPoint(positions, values, inside[1], outside[0]);
			Vector3f bd = isoPoint(positions, values, inside[1], outside[1]);
			emitSurfaceTriangle(matrix, vertices, ac, ad, bd);
			emitSurfaceTriangle(matrix, vertices, ac, bd, bc);
		}
	}

	private static Vector3f isoPoint(Vector3f[] positions, float[] values, int first, int second) {
		float denominator = values[second] - values[first];
		float t = Math.abs(denominator) < 1.0E-6F ? 0.5F : (ISO_LEVEL - values[first]) / denominator;
		return new Vector3f(positions[first]).lerp(positions[second], t);
	}

	private static void emitSurfaceTriangle(Matrix4fc matrix, VertexConsumer vertices, Vector3f a, Vector3f b, Vector3f c) {
		Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
		float vertical = normal.lengthSquared() < 1.0E-10F ? 0.0F : Math.abs(normal.y) / normal.length();
		float r = lerp(WATER_SIDE_RED, WATER_TOP_RED, vertical);
		float g = lerp(WATER_SIDE_GREEN, WATER_TOP_GREEN, vertical);
		float blue = lerp(WATER_SIDE_BLUE, WATER_TOP_BLUE, vertical);
		float alpha = lerp(WATER_SIDE_ALPHA, WATER_TOP_ALPHA, vertical);
		vertex(vertices, matrix, a.x, a.y, a.z, r, g, blue, alpha);
		vertex(vertices, matrix, b.x, b.y, b.z, r, g, blue, alpha);
		vertex(vertices, matrix, c.x, c.y, c.z, r, g, blue, alpha);
		vertex(vertices, matrix, c.x, c.y, c.z, r, g, blue, alpha);
	}

	private static float lerp(float first, float second, float t) {
		return first + (second - first) * t;
	}

	private static void renderFluidCell(
			Matrix4fc matrix, VertexConsumer vertices, FluidSnapshotPayload state,
			int x, int y, int z, float h, float fill
	) {
		float minX = state.origin().getX() + x * h;
		float minY = state.origin().getY() + y * h;
		float minZ = state.origin().getZ() + z * h;
		float maxX = minX + h;
		float maxY = minY + h * fill;
		float maxZ = minZ + h;

		float leftHeight = neighborFill(state, x - 1, y, z) * h;
		if (leftHeight < h * fill) quadX(matrix, vertices, minX, minY + leftHeight, maxY, minZ, maxZ, false, WATER_SIDE_RED, WATER_SIDE_GREEN, WATER_SIDE_BLUE, WATER_SIDE_ALPHA);
		float rightHeight = neighborFill(state, x + 1, y, z) * h;
		if (rightHeight < h * fill) quadX(matrix, vertices, maxX, minY + rightHeight, maxY, minZ, maxZ, true, WATER_SIDE_RED, WATER_SIDE_GREEN, WATER_SIDE_BLUE, WATER_SIDE_ALPHA);
		float backHeight = neighborFill(state, x, y, z - 1) * h;
		if (backHeight < h * fill) quadZ(matrix, vertices, minZ, minX, maxX, minY + backHeight, maxY, false, WATER_SIDE_RED, WATER_SIDE_GREEN, WATER_SIDE_BLUE, WATER_SIDE_ALPHA);
		float frontHeight = neighborFill(state, x, y, z + 1) * h;
		if (frontHeight < h * fill) quadZ(matrix, vertices, maxZ, minX, maxX, minY + frontHeight, maxY, true, WATER_SIDE_RED, WATER_SIDE_GREEN, WATER_SIDE_BLUE, WATER_SIDE_ALPHA);

		if (!hasVisibleFluid(state, x, y + 1, z)) {
			quadY(matrix, vertices, maxY, minX, maxX, minZ, maxZ, true, WATER_TOP_RED, WATER_TOP_GREEN, WATER_TOP_BLUE, WATER_TOP_ALPHA);
		}
		if (!hasVisibleFluid(state, x, y - 1, z)) {
			quadY(matrix, vertices, minY, minX, maxX, minZ, maxZ, false, WATER_SIDE_RED, WATER_SIDE_GREEN, WATER_SIDE_BLUE, WATER_SIDE_ALPHA);
		}
	}

	private static void renderSolidCell(
			Matrix4fc matrix, VertexConsumer vertices, FluidSnapshotPayload state,
			int x, int y, int z, float h
	) {
		float minX = state.origin().getX() + x * h;
		float minY = state.origin().getY() + y * h;
		float minZ = state.origin().getZ() + z * h;
		float maxX = minX + h;
		float maxY = minY + h;
		float maxZ = minZ + h;
		if (!isSolid(state, x - 1, y, z)) quadX(matrix, vertices, minX, minY, maxY, minZ, maxZ, false, SOLID_RED, SOLID_GREEN, SOLID_BLUE, SOLID_ALPHA);
		if (!isSolid(state, x + 1, y, z)) quadX(matrix, vertices, maxX, minY, maxY, minZ, maxZ, true, SOLID_RED, SOLID_GREEN, SOLID_BLUE, SOLID_ALPHA);
		if (!isSolid(state, x, y - 1, z)) quadY(matrix, vertices, minY, minX, maxX, minZ, maxZ, false, SOLID_RED, SOLID_GREEN, SOLID_BLUE, SOLID_ALPHA);
		if (!isSolid(state, x, y + 1, z)) quadY(matrix, vertices, maxY, minX, maxX, minZ, maxZ, true, SOLID_RED, SOLID_GREEN, SOLID_BLUE, SOLID_ALPHA);
		if (!isSolid(state, x, y, z - 1)) quadZ(matrix, vertices, minZ, minX, maxX, minY, maxY, false, SOLID_RED, SOLID_GREEN, SOLID_BLUE, SOLID_ALPHA);
		if (!isSolid(state, x, y, z + 1)) quadZ(matrix, vertices, maxZ, minX, maxX, minY, maxY, true, SOLID_RED, SOLID_GREEN, SOLID_BLUE, SOLID_ALPHA);
	}

	private static void renderBounds(Matrix4fc matrix, VertexConsumer vertices, FluidSnapshotPayload state) {
		float minX = state.origin().getX();
		float minY = state.origin().getY();
		float minZ = state.origin().getZ();
		float maxX = minX + state.sizeX() * state.cellSize();
		float maxY = minY + state.sizeY() * state.cellSize();
		float maxZ = minZ + state.sizeZ() * state.cellSize();
		float thickness = 0.035F;
		float r = 0.95F, g = 0.85F, b = 0.18F;

		box(matrix, vertices, minX, minY, minZ, maxX, minY + thickness, minZ + thickness, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, minY, maxZ - thickness, maxX, minY + thickness, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, maxY - thickness, minZ, maxX, maxY, minZ + thickness, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, maxY - thickness, maxZ - thickness, maxX, maxY, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, minY, minZ, minX + thickness, maxY, minZ + thickness, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, maxX - thickness, minY, minZ, maxX, maxY, minZ + thickness, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, minY, maxZ - thickness, minX + thickness, maxY, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, maxX - thickness, minY, maxZ - thickness, maxX, maxY, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, minY, minZ, minX + thickness, minY + thickness, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, maxX - thickness, minY, minZ, maxX, minY + thickness, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, maxY - thickness, minZ, minX + thickness, maxY, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, maxX - thickness, maxY - thickness, minZ, maxX, maxY, maxZ, r, g, b, BOUNDS_ALPHA);
	}

	private static float neighborFill(FluidSnapshotPayload state, int x, int y, int z) {
		if (!inside(state, x, y, z) || isSolid(state, x, y, z)) return 0.0F;
		return Byte.toUnsignedInt(state.volume()[index(state, x, y, z)]) / 255.0F;
	}

	private static boolean hasVisibleFluid(FluidSnapshotPayload state, int x, int y, int z) {
		return inside(state, x, y, z)
				&& !isSolid(state, x, y, z)
				&& Byte.toUnsignedInt(state.volume()[index(state, x, y, z)]) >= SURFACE_THRESHOLD;
	}

	private static boolean isSolid(FluidSnapshotPayload state, int x, int y, int z) {
		return inside(state, x, y, z) && state.solids()[index(state, x, y, z)] != 0;
	}

	private static boolean inside(FluidSnapshotPayload state, int x, int y, int z) {
		return x >= 0 && x < state.sizeX() && y >= 0 && y < state.sizeY() && z >= 0 && z < state.sizeZ();
	}

	private static int index(FluidSnapshotPayload state, int x, int y, int z) {
		return (z * state.sizeY() + y) * state.sizeX() + x;
	}

	private static void box(Matrix4fc m, VertexConsumer v, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float r, float g, float b, float a) {
		quadZ(m, v, maxZ, minX, maxX, minY, maxY, true, r, g, b, a);
		quadZ(m, v, minZ, minX, maxX, minY, maxY, false, r, g, b, a);
		quadX(m, v, minX, minY, maxY, minZ, maxZ, false, r, g, b, a);
		quadX(m, v, maxX, minY, maxY, minZ, maxZ, true, r, g, b, a);
		quadY(m, v, maxY, minX, maxX, minZ, maxZ, true, r, g, b, a);
		quadY(m, v, minY, minX, maxX, minZ, maxZ, false, r, g, b, a);
	}

	private static void quadX(Matrix4fc m, VertexConsumer v, float x, float minY, float maxY, float minZ, float maxZ, boolean positive, float r, float g, float b, float a) {
		if (positive) {
			vertex(v, m, x, minY, maxZ, r, g, b, a); vertex(v, m, x, minY, minZ, r, g, b, a); vertex(v, m, x, maxY, minZ, r, g, b, a); vertex(v, m, x, maxY, maxZ, r, g, b, a);
		} else {
			vertex(v, m, x, minY, minZ, r, g, b, a); vertex(v, m, x, minY, maxZ, r, g, b, a); vertex(v, m, x, maxY, maxZ, r, g, b, a); vertex(v, m, x, maxY, minZ, r, g, b, a);
		}
	}

	private static void quadY(Matrix4fc m, VertexConsumer v, float y, float minX, float maxX, float minZ, float maxZ, boolean positive, float r, float g, float b, float a) {
		if (positive) {
			vertex(v, m, minX, y, maxZ, r, g, b, a); vertex(v, m, maxX, y, maxZ, r, g, b, a); vertex(v, m, maxX, y, minZ, r, g, b, a); vertex(v, m, minX, y, minZ, r, g, b, a);
		} else {
			vertex(v, m, minX, y, minZ, r, g, b, a); vertex(v, m, maxX, y, minZ, r, g, b, a); vertex(v, m, maxX, y, maxZ, r, g, b, a); vertex(v, m, minX, y, maxZ, r, g, b, a);
		}
	}

	private static void quadZ(Matrix4fc m, VertexConsumer v, float z, float minX, float maxX, float minY, float maxY, boolean positive, float r, float g, float b, float a) {
		if (positive) {
			vertex(v, m, minX, minY, z, r, g, b, a); vertex(v, m, maxX, minY, z, r, g, b, a); vertex(v, m, maxX, maxY, z, r, g, b, a); vertex(v, m, minX, maxY, z, r, g, b, a);
		} else {
			vertex(v, m, maxX, minY, z, r, g, b, a); vertex(v, m, minX, minY, z, r, g, b, a); vertex(v, m, minX, maxY, z, r, g, b, a); vertex(v, m, maxX, maxY, z, r, g, b, a);
		}
	}

	private static void vertex(VertexConsumer consumer, Matrix4fc matrix, float x, float y, float z, float r, float g, float b, float a) {
		consumer.addVertex(matrix, x, y, z).setColor(r, g, b, a);
	}

	private static void execute(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
				RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX
		);
		RenderTarget target = client.gameRenderer.mainRenderTarget();
		GpuTextureView color = target.getColorTextureView();
		if (color == null) return;
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> RealWaterMod.MOD_ID + " volume surface",
				color,
				Optional.empty(),
				target.getDepthTextureView(),
				OptionalDouble.empty()
		)) {
			pass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.setVertexBuffer(0, info.vertexBuffer().slice());
			pass.setIndexBuffer(info.indexBuffer(), info.indexType());
			pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
		}
	}

	@Override
	public void close() {
		clear();
		stagedBuffer.close();
	}

	public synchronized void clear() {
		latest.clear();
		ledger.clear();
		latestMaterials.clear();
		materialLedger.clear();
		chemicalLedger.clear();
		latestGranular.clear();
		granularLedger.clear();
		extracted = List.of();
		extractedMaterials = List.of();
		extractedGranular = List.of();
	}

	private record SnapshotTransition(FluidSnapshotPayload from, FluidSnapshotPayload to, long startedAtNanos) { }
	private record MaterialFrame(
			FluidMaterialSnapshotPayload payload,
			FluidMaterialRenderSnapshot snapshot,
			FluidMaterialSurfaceMesher.Mesh mesh,
			ChemicalFrame chemical
	) { }
	private record ChemicalFrame(
			FluidChemicalSnapshotPayload payload, short[] topSpecies, byte[] topWeights,
			byte[] residualWeights, byte[] ph, byte[] indicatorFraction
	) { }
	private record GranularFrame(
			FluidGranularSnapshotPayload payload, MaterialFrame material,
			byte[] bulkFill, byte[] porosity, short[] topMaterial
	) { }
}
