package mmm.client;

import mmm.MmmMod;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/** Owns the staged GPU buffer and the translucent render-pass submission. */
final class FluidRenderBackend implements AutoCloseable {
	private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	private final StagedVertexBuffer stagedBuffer = new StagedVertexBuffer(
			() -> "Matter, Motion & Mechanics debug surface",
			RenderType.BIG_BUFFER_SIZE
	);

	void render(Consumer<VertexConsumer> geometry) {
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
		geometry.accept(vertices);
		stagedBuffer.upload();
		StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(draw);
		if (info != null) execute(Minecraft.getInstance(), info, pipeline);
		stagedBuffer.endFrame();
	}

	private static void execute(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
				RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX
		);
		RenderTarget target = client.gameRenderer.mainRenderTarget();
		GpuTextureView color = target.getColorTextureView();
		if (color == null) return;
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> MmmMod.MOD_ID + " volume surface",
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
		stagedBuffer.close();
	}
}
