package cc.ecisr.mmm.simulation.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Disk readback regression for silent vanilla save failures, independent of a running server. */
public final class PlayerInventorySaveValidation {
	private PlayerInventorySaveValidation() { }

	public static void main(String[] args) throws Exception {
		Path directory = Files.createTempDirectory("mmm-player-save-validation");
		Path file = directory.resolve("player.dat");
		try {
			CompoundTag expected = new CompoundTag();
			expected.putIntArray("UUID", new int[]{1, 2, 3, 4});
			ListTag inventory = new ListTag();
			CompoundTag item = new CompoundTag();
			item.putByte("Slot", (byte) 2);
			item.putString("id", "minecraft:bucket");
			item.putInt("count", 1);
			inventory.add(item);
			expected.put("Inventory", inventory);
			expected.putInt("SelectedItemSlot", 2);
			CompoundTag equipment = new CompoundTag();
			equipment.put("offhand", item.copy());
			expected.put("equipment", equipment);
			expectFailure(file, expected);
			NbtIo.writeCompressed(expected, file);
			PlayerInventorySaveVerifier.forceAndVerify(file, expected);
			for (String key : new String[]{"UUID", "Inventory", "SelectedItemSlot", "equipment"}) {
				CompoundTag stale = expected.copy();
				stale.remove(key);
				NbtIo.writeCompressed(stale, file);
				expectFailure(file, expected);
			}
			CompoundTag stale = expected.copy();
			stale.putInt("SelectedItemSlot", 3);
			NbtIo.writeCompressed(stale, file);
			expectFailure(file, expected);
			stale = expected.copy();
			ListTag oldInventory = new ListTag();
			CompoundTag oldItem = item.copy();
			oldItem.putString("id", "minecraft:water_bucket");
			oldInventory.add(oldItem);
			stale.put("Inventory", oldInventory);
			NbtIo.writeCompressed(stale, file);
			expectFailure(file, expected);
			// A later successful save is sufficient; no repair of the existing file is attempted by the verifier.
			NbtIo.writeCompressed(expected, file);
			PlayerInventorySaveVerifier.forceAndVerify(file, expected);
			expected.remove("equipment");
			NbtIo.writeCompressed(expected, file);
			PlayerInventorySaveVerifier.forceAndVerify(file, expected);
			System.out.println("Player inventory durable readback validation passed");
		} finally {
			Files.deleteIfExists(file);
			Files.delete(directory);
		}
	}

	private static void expectFailure(Path file, CompoundTag expected) throws IOException {
		try {
			PlayerInventorySaveVerifier.forceAndVerify(file, expected);
			throw new AssertionError("missing or stale player save was accepted");
		} catch (IOException correct) {
			// The coordinator retains WAL and retries I/O; no live ItemStack can stand in for this readback.
		}
	}
}
