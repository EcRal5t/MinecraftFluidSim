package cc.ecisr.mmm.chemistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Canonical bounded binary payload for cumulative external-source and escaped-sink audit truth. */
public final class ChemicalAuditLedgerCodec {
	private static final int MAGIC = 0x52574341; // RWCA
	private static final int VERSION = 1;
	public static final int MAX_BYTES = 16 * 1_048_576;

	private ChemicalAuditLedgerCodec() { }

	public static byte[] encode(ChemicalTransferLedger externalSource, ChemicalTransferLedger escapedSink) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream output = new DataOutputStream(bytes)) {
				output.writeInt(MAGIC);
				output.writeInt(VERSION);
				writeLedger(output, externalSource);
				writeLedger(output, escapedSink);
			}
			byte[] encoded = bytes.toByteArray();
			if (encoded.length > MAX_BYTES) throw new IllegalArgumentException("Chemical audit ledgers exceed 16 MiB");
			return encoded;
		} catch (IOException impossible) {
			throw new IllegalStateException("In-memory chemical audit encoding failed", impossible);
		}
	}

	public static AuditLedgers decode(byte[] encoded) throws IOException {
		if (encoded == null || encoded.length == 0 || encoded.length > MAX_BYTES) {
			throw new IOException("Invalid chemical audit payload size");
		}
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
			if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unknown chemical audit payload");
			ChemicalTransferLedger source = readLedger(input);
			ChemicalTransferLedger sink = readLedger(input);
			if (input.read() != -1) throw new IOException("Trailing chemical audit payload bytes");
			AuditLedgers result = new AuditLedgers(source, sink);
			if (!Arrays.equals(encoded, encode(source, sink))) throw new IOException("Chemical audit payload is not canonical");
			return result;
		} catch (EOFException truncated) {
			throw new IOException("Truncated chemical audit payload", truncated);
		} catch (IllegalArgumentException | ArithmeticException invalid) {
			throw new IOException("Invalid chemical audit payload: " + invalid.getMessage(), invalid);
		}
	}

	private static void writeLedger(DataOutputStream output, ChemicalTransferLedger ledger) throws IOException {
		if (ledger == null) throw new IllegalArgumentException("Chemical audit ledger is missing");
		output.writeInt(ledger.groups().size());
		for (ChemicalTransferLedger.Group group : ledger.groups()) {
			output.writeUTF(group.materialId());
			output.writeBoolean(group.indicator());
			output.writeLong(Double.doubleToRawLongBits(group.volume()));
			output.writeLong(Double.doubleToRawLongBits(group.mass()));
			output.writeLong(group.massResidualBits());
			output.writeInt(group.speciesQuanta().size());
			for (Map.Entry<String, Long> species : new TreeMap<>(group.speciesQuanta()).entrySet()) {
				output.writeUTF(species.getKey());
				output.writeLong(species.getValue());
			}
		}
	}

	private static ChemicalTransferLedger readLedger(DataInputStream input) throws IOException {
		int groups = input.readInt();
		if (groups < 0 || groups > ChemicalLimits.MAX_TRANSFER_GROUPS) throw new IOException("Invalid chemical audit group count");
		List<ChemicalTransferLedger.Group> result = new ArrayList<>(groups);
		for (int group = 0; group < groups; group++) {
			String material = input.readUTF();
			boolean indicator = input.readBoolean();
			double volume = Double.longBitsToDouble(input.readLong());
			double mass = Double.longBitsToDouble(input.readLong());
			long residualBits = input.readLong();
			int entries = input.readInt();
			if (entries <= 0 || entries > ChemicalLimits.MAX_TRANSFER_SPECIES_PER_GROUP) {
				throw new IOException("Invalid chemical audit species count");
			}
			TreeMap<String, Long> species = new TreeMap<>();
			for (int entry = 0; entry < entries; entry++) {
				if (species.put(input.readUTF(), input.readLong()) != null) throw new IOException("Duplicate chemical audit species");
			}
			result.add(new ChemicalTransferLedger.Group(material, indicator, volume, mass, residualBits, species));
		}
		return result.isEmpty() ? ChemicalTransferLedger.EMPTY : new ChemicalTransferLedger(result);
	}

	public record AuditLedgers(ChemicalTransferLedger externalSource, ChemicalTransferLedger escapedSink) {
		public AuditLedgers {
			if (externalSource == null || escapedSink == null) throw new IllegalArgumentException("Chemical audit ledgers are missing");
		}
	}
}
