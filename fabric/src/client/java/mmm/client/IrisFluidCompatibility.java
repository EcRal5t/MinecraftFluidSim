package mmm.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mmm.MmmMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/** Optional Iris 26.2 bridge; shader material numbers are read again after pack reloads. */
final class IrisFluidCompatibility {
	private static final Bridge BRIDGE = FabricLoader.getInstance().isModLoaded("iris") ? new Bridge() : null;

	private IrisFluidCompatibility() { }

	static void register(RenderPipeline surface, RenderPipeline diagnostic) {
		if (BRIDGE == null) return;
		// Iris copies both its main and shadow program maps from this built-in pipeline.
		BRIDGE.invoke(BRIDGE.copyPipeline, null, RenderPipelines.TRANSLUCENT_TERRAIN, surface);
		BRIDGE.invoke(BRIDGE.assignPipeline, BRIDGE.api, diagnostic, BRIDGE.basicProgram);
		MmmMod.LOGGER.info("MMM Iris 26.2 material, shadow and diagnostic pipelines registered");
	}

	static void beginMaterial(VertexConsumer vertices, String materialId, int x, int y, int z) {
		if (BRIDGE == null || !BRIDGE.blockBuffer.isInstance(vertices)) return;
		BlockState state = switch (materialId) {
			case "mmm:water" -> Blocks.WATER.defaultBlockState();
			case "mmm:mercury" -> Blocks.IRON_BLOCK.defaultBlockState();
			case "mmm:granular" -> Blocks.SAND.defaultBlockState();
			default -> Blocks.STAINED_GLASS.white().defaultBlockState();
		};
		Object settings = BRIDGE.settings;
		@SuppressWarnings("unchecked")
		Map<BlockState, Integer> ids = (Map<BlockState, Integer>) BRIDGE.invoke(BRIDGE.blockIds, settings);
		int material = ids == null ? 0 : ids.getOrDefault(state, 0);
		BRIDGE.invoke(BRIDGE.beginBlock, vertices, material, (byte) (materialId.equals("mmm:water") ? 1 : -1),
				(byte) 0, x, y, z);
	}

	private static final class Bridge {
		final Object api, basicProgram, settings;
		final Class<?> blockBuffer;
		final Method assignPipeline, copyPipeline, beginBlock, blockIds;

		Bridge() {
			try {
				Class<?> apiType = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				Class<?> programType = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
				api = apiType.getMethod("getInstance").invoke(null);
				basicProgram = programType.getField("BASIC").get(null);
				assignPipeline = apiType.getMethod("assignPipeline", RenderPipeline.class, programType);
				copyPipeline = Class.forName("net.irisshaders.iris.pipeline.IrisPipelines")
						.getMethod("copyPipeline", RenderPipeline.class, RenderPipeline.class);
				blockBuffer = Class.forName("net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder");
				beginBlock = blockBuffer.getMethod("beginBlock", int.class, byte.class, byte.class,
						int.class, int.class, int.class);
				Class<?> settingsType = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
				settings = settingsType.getField("INSTANCE").get(null);
				blockIds = settingsType.getMethod("getBlockStateIds");
			} catch (ReflectiveOperationException failure) {
				throw new IllegalStateException("Cannot bind MMM rendering to the Iris 26.2 interfaces", failure);
			}
		}

		Object invoke(Method method, Object receiver, Object... arguments) {
			try {
				return method.invoke(receiver, arguments);
			} catch (InvocationTargetException failure) {
				throw new IllegalStateException("Iris fluid rendering failed at " + method.getName(), failure.getCause());
			} catch (ReflectiveOperationException failure) {
				throw new IllegalStateException("Cannot call Iris fluid rendering interface " + method.getName(), failure);
			}
		}
	}
}
