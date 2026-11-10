package mmm.client;

import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;

/** Collects GPU draw durations without waiting for the device. */
final class FluidRenderTiming implements AutoCloseable {
	private static final int SLOTS = 32;
	private final String name;
	private final byte[] state = new byte[SLOTS];
	private GpuQueryPool queries;
	private int next;
	private long totalNanos, maxNanos, samples;

	FluidRenderTiming(String name) {
		this.name = name;
	}

	int begin(RenderPass pass) {
		if (queries == null) queries = RenderSystem.getDevice().createTimestampQueryPool(SLOTS * 2);
		for (int i = 0; i < SLOTS; i++) {
			int slot = (next + i) % SLOTS;
			if (state[slot] == 0) {
				state[slot] = 1;
				next = (slot + 1) % SLOTS;
				pass.writeTimestamp(queries, slot * 2);
				return slot;
			}
		}
		return -1;
	}

	void end(RenderPass pass, int slot) {
		if (slot < 0) return;
		pass.writeTimestamp(queries, slot * 2 + 1);
		state[slot] = 2;
	}

	void poll() {
		if (queries == null) return;
		double period = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
		for (int slot = 0; slot < SLOTS; slot++) {
			if (state[slot] != 2) continue;
			var start = queries.getValue(slot * 2);
			var end = queries.getValue(slot * 2 + 1);
			if (start.isEmpty() || end.isEmpty()) continue;
			long nanos = (long) ((end.getAsLong() - start.getAsLong()) * period);
			totalNanos += nanos;
			maxNanos = Math.max(maxNanos, nanos);
			samples++;
			state[slot] = 0;
		}
	}

	Summary drain() {
		poll();
		Summary result = new Summary(name, samples, samples == 0 ? 0 : totalNanos / samples, maxNanos);
		totalNanos = maxNanos = samples = 0;
		return result;
	}

	@Override
	public void close() {
		if (queries != null) queries.close();
	}

	record Summary(String name, long samples, long averageNanos, long maxNanos) { }
}
