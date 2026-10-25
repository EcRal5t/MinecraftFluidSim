package mmm.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Commit-driven, read-only controller liquid-level projection. */
final class ControllerComparatorSignals {
	private record Address(ResourceKey<Level> dimension, BlockPos position) { }
	private final Map<Address, Integer> signals = new HashMap<>();
	private final BiConsumer<ResourceKey<Level>, BlockPos> changed;

	ControllerComparatorSignals(FluidRegionManager regions, BiConsumer<ResourceKey<Level>, BlockPos> changed) {
		this.changed = java.util.Objects.requireNonNull(changed);
		regions.observeCommittedChanges(this::observe);
	}

	int signal(ResourceKey<Level> dimension, BlockPos position) {
		return signals.getOrDefault(new Address(dimension, position), 0);
	}

	private void observe(FluidRegion region, boolean present) {
		if (!region.controllerManaged()) return;
		var address = new Address(region.dimension(), region.footprint().controllerPosition());
		int previous = signals.getOrDefault(address, 0);
		int next = present ? signalFor(region) : 0;
		if (present) signals.put(address, next);
		else signals.remove(address);
		if (previous != next) changed.accept(address.dimension(), address.position());
	}

	static int signalFor(FluidRegion region) {
		if (!region.controllerManaged() || !region.solverLoaded() || region.availabilityFailure() != null) return 0;
		return strength(region.liquidVolume(), region.fixedOpenVolume());
	}

	/** Vanilla container ratio: zero empty, 1..14 partial, 15 full. Both arguments use the same volume unit. */
	static int strength(double liquid, double fixedCapacity) {
		if (!(liquid > 0) || !(fixedCapacity > 0)) return 0;
		return 1 + (int) Math.floor(14 * Math.min(1, liquid / fixedCapacity));
	}
}
