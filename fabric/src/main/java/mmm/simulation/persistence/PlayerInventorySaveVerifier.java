package mmm.simulation.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Verifies the restart-visible player inventory after vanilla's best-effort save returns. */
public final class PlayerInventorySaveVerifier {
	private static final long MAXIMUM_NBT_BYTES = 64L * 1024 * 1024;
	private PlayerInventorySaveVerifier() { }

	/** Expected data is encoded by the same player serializer used by Minecraft's save path. */
	public static void forceAndVerify(Path playerFile, CompoundTag expected) throws IOException {
		for (String key : new String[]{"UUID", "Inventory", "SelectedItemSlot"}) {
			if (!expected.contains(key)) throw new IllegalArgumentException("Missing expected player field " + key);
		}
		DurableFileOperations.forceFile(playerFile);
		DurableFileOperations.forceParent(playerFile);
		CompoundTag saved = NbtIo.readCompressed(playerFile, NbtAccounter.create(MAXIMUM_NBT_BYTES));
		// Equipment owns the off-hand in 26.2. Missing equipment is legal only when the live save omits it too.
		for (String key : new String[]{"UUID", "Inventory", "SelectedItemSlot", "equipment"}) {
			if (!Objects.equals(expected.get(key), saved.get(key))) {
				throw new IOException("Player save did not persist the current " + key + ": " + playerFile);
			}
		}
	}
}
