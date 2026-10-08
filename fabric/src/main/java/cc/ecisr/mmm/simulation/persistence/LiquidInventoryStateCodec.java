package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.fluid.LiquidInventoryState;
import cc.ecisr.mmm.model.region.RegionManifest;
import cc.ecisr.mmm.model.region.RegionSectionState;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.ExactVolume;
import cc.ecisr.mmm.units.ExactVolumeCodec;
import java.io.*;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Bounded canonical encoding of the extensive liquid columns in original carrier order. */
final class LiquidInventoryStateCodec {
	static final String MANIFEST_EXTENSION = "mmm:liquid_inventory";
	private static final int MAGIC = 0x4c495156, MAX_BYTES = 16 * 1_048_576;

	byte[] encode(LiquidInventoryState state) throws IOException {
		long size = 8;
		for (int p = 0; p < state.count(); p++) size += 16L + state.volume(p).numerator().toByteArray().length
				+ state.volume(p).denominator().toByteArray().length + state.mass(p).significand().toByteArray().length;
		if (size > MAX_BYTES) throw new IOException("Liquid inventory exceeds checkpoint payload budget");
		var buffer = new ByteArrayOutputStream((int) size);
		try (var out = new DataOutputStream(buffer)) {
			out.writeInt(MAGIC); out.writeInt(state.count());
			for (int p = 0; p < state.count(); p++) { ExactVolumeCodec.write(out, state.volume(p)); write(out, state.mass(p)); }
		}
		return buffer.toByteArray();
	}
	LiquidInventoryState decode(byte[] payload, int expectedCount) throws IOException {
		if (payload == null || payload.length > MAX_BYTES) throw new IOException("Liquid inventory is missing or over budget");
		try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
			if (in.readInt() != MAGIC) throw new IOException("Unknown liquid inventory encoding");
			int count = in.readInt();
			if (count < 0 || count != expectedCount || count > in.available() / 19) throw new IOException("Liquid inventory count differs from carrier payload");
			var volume = new ExactVolume[count]; var mass = new SignedDyadic[count];
			for (int p = 0; p < count; p++) { volume[p] = ExactVolumeCodec.read(in); mass[p] = read(in); }
			if (in.available() != 0) throw new IOException("Trailing liquid inventory bytes");
			return new LiquidInventoryState(volume, mass);
		} catch (IllegalArgumentException | ArithmeticException invalid) {
			throw new IOException("Invalid liquid inventory: " + invalid.getMessage(), invalid);
		}
	}
	static LiquidInventoryState readManifest(RegionManifest manifest, List<RegionSectionState> sections) throws IOException {
		for (var section : sections) for (var extension : section.extensions()) if (MANIFEST_EXTENSION.equals(extension.type()))
			throw new IOException("Extensive liquid inventory is manifest-only");
		LiquidInventoryState state = null;
		for (var extension : manifest.extensions()) if (MANIFEST_EXTENSION.equals(extension.type())) {
			if (state != null) throw new IOException("Duplicate extensive liquid inventory");
			state = new LiquidInventoryStateCodec().decode(extension.payload(), manifest.totalMarkerCount());
		}
		if (state == null) throw new IOException("Extensive liquid inventory is missing");
		return state;
	}
	private static void write(DataOutputStream out, SignedDyadic value) throws IOException {
		byte[] bytes = value.significand().toByteArray(); out.writeInt(value.exponent()); out.writeInt(bytes.length); out.write(bytes);
	}
	private static SignedDyadic read(DataInputStream in) throws IOException {
		int exponent = in.readInt(), length = in.readInt();
		if (length < 1 || length > SignedDyadic.MAX_SIGNIFICAND_BITS / 8 + 1 || length > in.available())
			throw new IOException("Liquid inventory significand length is invalid");
		byte[] bytes = in.readNBytes(length); var value = new SignedDyadic(new BigInteger(bytes), exponent);
		if (value.exponent() != exponent || !Arrays.equals(bytes, value.significand().toByteArray()))
			throw new IOException("Non-canonical liquid inventory quantity");
		return value;
	}
}
