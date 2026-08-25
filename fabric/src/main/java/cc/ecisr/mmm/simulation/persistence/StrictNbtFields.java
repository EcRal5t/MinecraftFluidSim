package cc.ecisr.mmm.simulation.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.util.Map;

/** Exact compound shape validation for authoritative snapshots with no migration semantics. */
public final class StrictNbtFields {
	private StrictNbtFields() { }

	public static void requireExact(CompoundTag tag, Map<String, Byte> fields, String context) throws IOException {
		if (tag == null || fields == null || context == null || context.isBlank()) {
			throw new IllegalArgumentException("Strict NBT field validation is incomplete");
		}
		if (!tag.keySet().equals(fields.keySet())) {
			throw new IOException(context + " field set is not exact");
		}
		for (Map.Entry<String, Byte> field : fields.entrySet()) {
			Tag value = tag.get(field.getKey());
			if (value == null || value.getId() != field.getValue()) {
				throw new IOException("Missing or invalid " + context + " field " + field.getKey());
			}
		}
	}
}
