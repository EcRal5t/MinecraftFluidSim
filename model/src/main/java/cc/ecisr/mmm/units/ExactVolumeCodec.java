package cc.ecisr.mmm.units;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

/** Canonical bounded numerator/denominator encoding shared by inventory and transfer checkpoints. */
public final class ExactVolumeCodec {
	private ExactVolumeCodec() { }
	public static void write(DataOutputStream out, ExactVolume value) throws IOException {
		for (var component : new BigInteger[]{value.numerator(), value.denominator()}) {
			byte[] bytes = component.toByteArray(); out.writeInt(bytes.length); out.write(bytes);
		}
	}
	public static ExactVolume read(DataInputStream in) throws IOException {
		byte[][] encoded = new byte[2][]; var components = new BigInteger[2];
		for (int c = 0; c < 2; c++) {
			int length = in.readInt();
			if (length < 1 || length > ExactVolume.MAX_COMPONENT_BITS / 8 + 1 || length > in.available())
				throw new IOException("Exact volume component length is invalid");
			encoded[c] = in.readNBytes(length); components[c] = new BigInteger(encoded[c]);
		}
		try {
			var value = new ExactVolume(components[0], components[1]);
			if (!Arrays.equals(encoded[0], value.numerator().toByteArray()) || !Arrays.equals(encoded[1], value.denominator().toByteArray()))
				throw new IOException("Non-canonical exact volume");
			return value;
		} catch (IllegalArgumentException | ArithmeticException invalid) { throw new IOException("Invalid exact volume", invalid); }
	}
}
