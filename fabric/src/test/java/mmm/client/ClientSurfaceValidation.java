package mmm.client;

import mmm.network.FluidChemicalSnapshotPayload;
import mmm.network.FluidMaterialSnapshotPayload;
import mmm.network.FluidProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.platform.NativeImage;
import mmm.granular.GranularDisplaySnapshot;
import mmm.network.FluidGranularSnapshotPayload;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.world.phys.Vec3;

/** Surface reuse and display-only colour updates, exercised without a running game. */
public final class ClientSurfaceValidation {
	public static void main(String[] args) {
		interactiveResponses();
		var store = new ClientRegionSnapshotStore();
		var negotiation = FluidProtocol.negotiate(FluidProtocol.materialServerOffer());
		var first = material(1, (byte) 255);
		store.accept(first, negotiation);
		var mesh = store.surfaceMesh(first, first.toRenderSnapshot());
		require(!mesh.outerTriangles().isEmpty(), "Fixture has a visible surface");
		var second = material(2, (byte) 255);
		store.accept(second, negotiation);
		require(store.surfaceMesh(second, second.toRenderSnapshot()) == mesh, "Unchanged geometry reuses its mesh");
		var changed = material(3, (byte) 128);
		store.accept(changed, negotiation);
		require(store.surfaceMesh(changed, changed.toRenderSnapshot()) != mesh, "Changed volume rebuilds geometry");
		store.clear();
		store.accept(second, negotiation);
		require(store.surfaceMesh(second, second.toRenderSnapshot()) != mesh, "Clearing releases the previous surface");
		var red = MaterialSurfaceGeometry.prepare(first, first.toRenderSnapshot(), mesh, chemical(first, 0xffff0000), null, composition());
		var blue = MaterialSurfaceGeometry.prepare(first, first.toRenderSnapshot(), mesh, chemical(first, 0xff0000ff), null, composition());
		require(red.size() == mesh.outerTriangles().size() && blue.size() == red.size(), "Colour preparation preserves triangles");
		for (int i = 0; i < red.size(); i++) {
			require(red.get(i).triangle().equals(blue.get(i).triangle()) && red.get(i).argb() == 0xffff0000
					&& blue.get(i).argb() == 0xff0000ff, "Composition updates colour on reused geometry");
		}
		var initial = new ClientRegionSnapshotStore.MaterialFrame(first, first.toRenderSnapshot(), mesh,
				chemical(first, 0xffff0000), new short[] {4800,4800,4800,4800,4800,4800,4800,4800}, composition());
		var ph = initial.recolor(new FluidDisplaySettings.View(FluidDisplaySettings.Mode.PH, 273.15, 373.15));
		require(ph.mesh() == initial.mesh() && ph.triangles().getFirst().argb() == 0xff35d06f,
				"Mode changes preserve geometry and show exact neutral pH");
		var thermal = initial.recolor(new FluidDisplaySettings.View(FluidDisplaySettings.Mode.TEMPERATURE, 273.15, 373.15));
		var wider = thermal.recolor(new FluidDisplaySettings.View(FluidDisplaySettings.Mode.TEMPERATURE, 273.15, 473.15));
		require(thermal.mesh() == mesh && wider.mesh() == mesh
				&& thermal.triangles().getFirst().argb() != wider.triangles().getFirst().argb(),
				"Temperature range changes only colours on the accepted thermal frame");
		require(FluidColorMapping.color(new FluidDisplaySettings.View(FluidDisplaySettings.Mode.PH, 273.15, 373.15),
				null, null, first, 0, 0, 0xff197ae8) == FluidColorMapping.NO_DATA,
				"Missing pH differs from neutral");
        require(FluidRenderBatch.sameFrames(List.of(initial), List.of(initial), List.of(), List.of()),
                "Static frames reuse the uploaded batch");
        require(!FluidRenderBatch.sameFrames(List.of(initial), List.of(ph), List.of(), List.of()),
                "Diagnostic color changes invalidate the uploaded batch");
        require(!FluidRenderBatch.sameFrames(List.of(initial), List.of(), List.of(), List.of()),
                "Region removal invalidates the uploaded batch");
		emission(initial, ph);
		System.out.println("Client surface validation passed");
	}

	private static void emission(ClientRegionSnapshotStore.MaterialFrame composition, ClientRegionSnapshotStore.MaterialFrame diagnostic) {
		var empty = granular(composition, false);
		var nonempty = granular(composition, true);
		FluidRenderCoordinatesValidation.run(composition, nonempty);
		var vertices = new RecordingVertices();
		java.util.function.Function<String, TextureAtlasSprite> unexpected = id -> {
			throw new AssertionError("Unexpected sprite lookup: " + id);
		};
		GranularSurfaceGeometry.buildGeometry(Vec3.ZERO, vertices, empty, true, unexpected);
		require(vertices.count == 0, "Empty granular frame does not request a sprite or emit vertices");
		GranularSurfaceGeometry.buildGeometry(Vec3.ZERO, vertices, nonempty, false, unexpected);
		require(vertices.count > 0 && vertices.textured == 0, "Granular diagnostic vertices omit texture fields");
		vertices = new RecordingVertices();
		MaterialSurfaceGeometry.buildGeometry(Vec3.ZERO, vertices, diagnostic, false, false, unexpected);
		require(vertices.count > 0 && vertices.textured == 0, "Material diagnostic vertices omit texture fields");
		try (var image = new NativeImage(1, 1, true)) {
			var contents = new SpriteContents(Identifier.withDefaultNamespace("block/test"), new FrameSize(1, 1), image);
			var sprite = new TestSprite(contents);
			java.util.function.Function<String, TextureAtlasSprite> lookup = id -> sprite;
			vertices = new RecordingVertices();
			MaterialSurfaceGeometry.buildGeometry(Vec3.ZERO, vertices, composition, false, true, lookup);
			require(vertices.count == composition.triangles().size() * 8 && vertices.textured == vertices.count * 3,
                    "Both material sides emit complete block vertices for back-face culling");
			vertices = new RecordingVertices();
			MaterialSurfaceGeometry.buildGeometry(Vec3.ZERO, vertices, diagnostic, true, true, lookup);
			GranularSurfaceGeometry.buildGeometry(Vec3.ZERO, vertices, nonempty, true, lookup);
			require(vertices.count > 0 && vertices.textured == vertices.count * 3, "Shadow emits complete block vertices");
		}
	}

	private static ClientRegionSnapshotStore.GranularFrame granular(ClientRegionSnapshotStore.MaterialFrame material, boolean filled) {
		byte[] bulk = new byte[8], porosity = new byte[8];
		short[] top = new short[8]; Arrays.fill(top, (short) -1);
		if (filled) { bulk[0] = (byte) 128; porosity[0] = (byte) 80; top[0] = 0; }
		var payload = new FluidGranularSnapshotPayload(FluidProtocol.Layout.GRANULAR, material.payload(), "a".repeat(64),
				List.of(new GranularDisplaySnapshot.MaterialEntry("mmm:salt", 0xffffffff)), bulk, porosity, top,
				filled ? List.of(new GranularDisplaySnapshot.ParcelSample(32768, 32768, 32768, 10, 0)) : List.of(), null);
		return new ClientRegionSnapshotStore.GranularFrame(payload, material, bulk, porosity, top);
	}

	private static final class TestSprite extends TextureAtlasSprite {
		TestSprite(SpriteContents contents) { super(Identifier.withDefaultNamespace("textures/atlas/blocks.png"), contents, 16, 16, 0, 0, 0); }
	}

	private static final class RecordingVertices implements VertexConsumer {
		int count, textured;
		public VertexConsumer addVertex(float x, float y, float z) { count++; return this; }
		public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
		public VertexConsumer setColor(int color) { return this; }
		public VertexConsumer setUv(float u, float v) { textured++; return this; }
		public VertexConsumer setUv1(int u, int v) { textured++; return this; }
		public VertexConsumer setUv2(int u, int v) { textured++; return this; }
		public VertexConsumer setNormal(float x, float y, float z) { textured++; return this; }
		public VertexConsumer setLineWidth(float width) { return this; }
	}

	private static void interactiveResponses() {
		var session = new UUID(3, 4); var region = new UUID(5, 6); var dimension = "minecraft:overworld";
		var initial = new mmm.network.DemoWorkbenchPayload(session, 0, 7, dimension,
				mmm.network.DemoPreset.WATER, region, true, null);
		var demo = new DemoWorkbenchScreen.Request(initial);
		var failed = new mmm.network.DemoWorkbenchPayload(session, 7, 8, dimension,
				mmm.network.DemoPreset.WATER, region, true, "retry");
		require(!demo.awaiting(failed, dimension), "An idle demo accepted a stale response");
		demo.begin(mmm.network.DemoAction.RESET);
		require(demo.awaiting(failed, dimension) && !demo.awaiting(failed, "minecraft:the_nether")
				&& !demo.awaiting(new mmm.network.DemoWorkbenchPayload(new UUID(7,8), 7, 8, dimension,
				mmm.network.DemoPreset.WATER, region, true, null), dimension), "Demo response identity mismatch");
		var retry = new DemoWorkbenchScreen.Request(failed);
		require(!retry.awaiting(failed, dimension), "Failed demo response left the panel pending");
		retry.begin(mmm.network.DemoAction.RESET);
		require(retry.awaiting(new mmm.network.DemoWorkbenchPayload(session, 8, 9, dimension,
				mmm.network.DemoPreset.WATER, region, true, null), dimension), "Demo could not retry after failure");
		var capacity = mmm.controller.ControllerSummary.Capacity.available(0, 16);
		var summary = new mmm.controller.ControllerSummary(7, region, dimension, "a".repeat(64), 1, 1,
				new mmm.controller.ControllerSummary.Footprint(1, 1, 1, 2, 2, 2, 8, 1), true,
				mmm.controller.ControllerSummary.OperatorIntent.PAUSED, mmm.controller.ControllerSummary.MotionState.ACTIVE,
				mmm.controller.ControllerSummary.ThermalState.ACTIVE, new mmm.controller.ControllerSummary.Availability(null, null),
				new mmm.controller.ControllerSummary.Usage(capacity, capacity, capacity, capacity, capacity, capacity),
				new mmm.controller.ControllerSummary.Inventory(true, 0, 0, 0, 0, 0, 0, 0, 0, 0, true, 0,
						mmm.controller.ControllerSummary.TemperatureRange.unavailable()),
				new mmm.controller.ControllerSummary.Gates(true, false, false, false, true),
				new mmm.controller.ControllerSummary.Diagnostics(0, 0, 0, null));
		var controller = new ControllerConsoleScreen.Request(summary);
		controller.begin();
		require(controller.awaiting(region, 7) && !controller.awaiting(region, 8), "Controller accepted a mismatched reply");
		require(!new ControllerConsoleScreen.Request(summary).awaiting(region, 7), "Controller remained pending after a failed summary reply");
	}

	private static FluidMaterialSnapshotPayload material(long sequence, byte volume) {
		byte[] volumes = new byte[8], markers = new byte[8], fractions = new byte[8];
		Arrays.fill(volumes, volume); Arrays.fill(markers, (byte) 1); Arrays.fill(fractions, (byte) 255);
		return new FluidMaterialSnapshotPayload(FluidProtocol.Layout.MATERIAL, FluidMaterialSnapshotPayload.Kind.SNAPSHOT,
				Identifier.fromNamespaceAndPath("minecraft", "overworld"), new UUID(1, 1), 1, sequence,
				BlockPos.ZERO, 0.5F, 2, 2, 2,
				List.of(new FluidMaterialSnapshotPayload.MaterialEntry("mmm:water", 1000, "mmm:aqueous", 0xff197ae8)),
				List.of("mmm:aqueous"), new mmm.fluid.FluidSurfaceInput(2, 2, 2, 0.5F, List.of(new mmm.fluid.FluidSurfaceInput.Piece(0, 0, 0, 0, List.of(new mmm.fluid.FluidSurfaceInput.Plane(0, 1, 0, Byte.toUnsignedInt(volume) / 510.0))))), new byte[][] {fractions},
				new byte[][] {fractions}, false, List.of());
	}

	private static ClientRegionSnapshotStore.ChemicalFrame chemical(FluidMaterialSnapshotPayload material, int color) {
		short[] species = new short[32]; Arrays.fill(species, (short) -1);
		byte[] weights = new byte[32];
		for (int cell = 0; cell < 8; cell++) { species[cell * 4] = 0; weights[cell * 4] = (byte) 255; }
		var payload = new FluidChemicalSnapshotPayload(FluidProtocol.Layout.CHEMICAL, material, "a".repeat(64),
				List.of(new FluidChemicalSnapshotPayload.SpeciesEntry("mmm:h2o", color)), species, weights,
				new byte[8], ph());
		return new ClientRegionSnapshotStore.ChemicalFrame(payload, payload.topSpecies(), payload.topWeights(),
				payload.residualWeights(), payload.ph());
	}

	private static FluidDisplaySettings.View composition() {
		return new FluidDisplaySettings.View(FluidDisplaySettings.Mode.COMPOSITION, 273.15, 373.15);
	}
	private static byte[] ph() {
		byte[] ph = new byte[8]; Arrays.fill(ph, (byte) 70); return ph;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}

