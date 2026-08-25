package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.block.ThermalReservoirBlockEntity;
import cc.ecisr.mmm.thermal.ThermalBoundaryDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves world-authoritative block/tag definitions into a bounded per-region face field. */
final class ThermalBoundaryResolver {
	private final Map<Identifier, ThermalBoundaryDefinition> blocks;
	private final List<TagTarget> tags;

	ThermalBoundaryResolver(ThermalRegistry registry) {
		if (registry == null) throw new IllegalArgumentException("Thermal boundary registry is missing");
		LinkedHashMap<Identifier, ThermalBoundaryDefinition> direct = new LinkedHashMap<>();
		ArrayList<TagTarget> tagTargets = new ArrayList<>();
		for (ThermalBoundaryDefinition definition : registry.boundaries()) {
			Identifier target = Identifier.parse(definition.targetId());
			if (definition.targetKind() == ThermalBoundaryDefinition.TargetKind.BLOCK) {
				if (!BuiltInRegistries.BLOCK.containsKey(target)) {
					throw new IllegalArgumentException("Unknown thermal boundary block " + target);
				}
				direct.put(target, definition);
			} else {
				TagKey<Block> tag = TagKey.create(Registries.BLOCK, target);
				if (BuiltInRegistries.BLOCK.get(tag).isEmpty()) {
					throw new IllegalArgumentException("Unknown thermal boundary block tag " + target);
				}
				tagTargets.add(new TagTarget(tag, definition));
			}
		}
		blocks = Map.copyOf(direct);
		tags = List.copyOf(tagTargets);
		// A direct target and any number of tags may overlap after tag reload; such a mapping is not deterministic.
		BuiltInRegistries.BLOCK.stream().forEach(block -> matchingDefinition(block.defaultBlockState()));
	}

	boolean refresh(ServerLevel level, FluidRegion region, int cellsPerBlock) {
		if (level == null || region == null || cellsPerBlock <= 0
				|| !region.solverLoaded() || !region.domain().hasThermalState()) return false;
		int sizeX = region.domain().sizeX(), sizeY = region.domain().sizeY(), sizeZ = region.domain().sizeZ();
		double[] temperatures = new double[Math.multiplyExact(Math.multiplyExact(
				Math.multiplyExact(sizeX, sizeY), sizeZ), 6)];
		Arrays.fill(temperatures, Double.NaN);
		HashMap<BlockPos, Double> cache = new HashMap<>();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int cell = (z * sizeY + y) * sizeX + x;
			for (int direction = 0; direction < 6; direction++) {
				BlockPos target = targetBlock(region.origin(), x, y, z, direction, cellsPerBlock);
				double temperature = cache.computeIfAbsent(target, pos -> surfaceTemperature(level, pos));
				if (!Double.isNaN(temperature)) temperatures[cell * 6 + direction] = temperature;
			}
		}
		return region.domain().replaceThermalBoundaryTemperatures(temperatures);
	}

	private double surfaceTemperature(ServerLevel level, BlockPos position) {
		BlockState state = level.getBlockState(position);
		if (state.is(MmmBlocks.THERMAL_RESERVOIR)) {
			if (!(level.getBlockEntity(position) instanceof ThermalReservoirBlockEntity reservoir)) {
				throw new IllegalArgumentException("Thermal reservoir at " + position.toShortString()
						+ " has no authoritative block entity");
			}
			if (!reservoir.hasValidTemperature()) {
				throw new IllegalArgumentException("Thermal reservoir at " + position.toShortString()
						+ " has an invalid persisted setpoint");
			}
			return reservoir.temperatureK();
		}
		ThermalBoundaryDefinition definition = matchingDefinition(state);
		return definition == null || definition.kind() == ThermalBoundaryDefinition.Kind.ADIABATIC
				? Double.NaN : definition.surfaceTemperatureK();
	}

	private ThermalBoundaryDefinition matchingDefinition(BlockState state) {
		Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		ThermalBoundaryDefinition result = blocks.get(blockId);
		for (TagTarget target : tags) if (state.typeHolder().is(target.tag())) {
			if (result != null) throw new IllegalArgumentException("Ambiguous thermal boundary mapping for " + blockId
						+ ": " + result.id() + " and " + target.definition().id());
			result = target.definition();
		}
		return result;
	}

	private static BlockPos targetBlock(
			BlockPos origin, int x, int y, int z, int direction, int cellsPerBlock
	) {
		int blockX = Math.floorDiv(x + (direction == 0 ? -1 : direction == 1 ? 1 : 0), cellsPerBlock);
		int blockY = Math.floorDiv(y + (direction == 2 ? -1 : direction == 3 ? 1 : 0), cellsPerBlock);
		int blockZ = Math.floorDiv(z + (direction == 4 ? -1 : direction == 5 ? 1 : 0), cellsPerBlock);
		return origin.offset(blockX, blockY, blockZ);
	}

	private record TagTarget(TagKey<Block> tag, ThermalBoundaryDefinition definition) { }
}
