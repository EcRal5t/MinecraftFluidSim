package com.example.client;

import com.example.network.FluidSnapshotPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;

import java.util.concurrent.ThreadLocalRandom;

/** Client-only particle sampling of the latest server volume snapshot. */
public final class ClientFluidDebugView {
	private static final DustParticleOptions WATER_POINT = new DustParticleOptions(0x2588E8, 0.35F);
	private static final int POINTS_PER_UPDATE = 180;
	private static volatile Snapshot snapshot;
	private static int tick;

	private ClientFluidDebugView() { }

	public static void accept(FluidSnapshotPayload payload) {
		if (payload.isClear()) {
			snapshot = null;
			return;
		}
		int activeCount = 0;
		for (byte value : payload.volume()) if (Byte.toUnsignedInt(value) >= 16) activeCount++;
		int[] active = new int[activeCount];
		int cursor = 0;
		for (int i = 0; i < payload.volume().length; i++) {
			if (Byte.toUnsignedInt(payload.volume()[i]) >= 16) active[cursor++] = i;
		}
		snapshot = new Snapshot(payload, active);
	}

	public static void tick(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null || ++tick % 2 != 0) return;
		Snapshot current = snapshot;
		if (current == null || current.activeCells.length == 0) return;
		FluidSnapshotPayload payload = current.payload;
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int count = Math.min(POINTS_PER_UPDATE, current.activeCells.length);
		for (int i = 0; i < count; i++) {
			int cell = current.activeCells[random.nextInt(current.activeCells.length)];
			int x = cell % payload.sizeX();
			int yz = cell / payload.sizeX();
			int y = yz % payload.sizeY();
			int z = yz / payload.sizeY();
			float fill = Byte.toUnsignedInt(payload.volume()[cell]) / 255.0F;
			double cellSize = payload.cellSize();
			double px = payload.origin().getX() + (x + random.nextDouble()) * cellSize;
			double py = payload.origin().getY() + (y + random.nextDouble() * fill) * cellSize;
			double pz = payload.origin().getZ() + (z + random.nextDouble()) * cellSize;
			minecraft.level.addParticle(WATER_POINT, px, py, pz, 0.0, 0.0, 0.0);
		}
	}

	private record Snapshot(FluidSnapshotPayload payload, int[] activeCells) { }
}
