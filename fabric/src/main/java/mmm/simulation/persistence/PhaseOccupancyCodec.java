package mmm.simulation.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Section-local geometric liquid volumes; physical quantities remain in the cell inventory. */
final class PhaseOccupancyCodec {
	static final String SECTION_EXTENSION = "mmm:phase_occupancy";
	private static final int MAGIC = 0x4d504f43;

	byte[] encode(double[] volumes) throws IOException {
		validate(volumes);
		var bytes = new ByteArrayOutputStream(Math.addExact(8, Math.multiplyExact(8, volumes.length)));
		try (var output = new DataOutputStream(bytes)) {
			output.writeInt(MAGIC);
			output.writeInt(volumes.length);
			for (double volume : volumes) output.writeDouble(volume);
		}
		return bytes.toByteArray();
	}

	double[] decode(byte[] payload, int cells) throws IOException {
		int count = Math.multiplyExact(8, cells);
		if (payload == null || payload.length != 8L + 8L * count)
			throw new IOException("Phase occupancy payload differs from section dimensions");
		try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
			if (input.readInt() != MAGIC || input.readInt() != count)
				throw new IOException("Unknown phase occupancy encoding or dimensions");
			double[] volumes = new double[count];
			for (int piece = 0; piece < count; piece++) volumes[piece] = input.readDouble();
			validate(volumes);
			return volumes;
		}
	}

	private static void validate(double[] volumes) throws IOException {
		if (volumes == null || volumes.length == 0 || volumes.length % 8 != 0)
			throw new IOException("Phase occupancy requires eight micro volumes per cell");
		for (double volume : volumes)
			if (!(volume >= 0 && volume <= .125) || !Double.isFinite(volume))
				throw new IOException("Invalid geometric liquid volume");
	}
}
