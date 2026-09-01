package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.model.region.RegionSectionState;

import cc.ecisr.mmm.fluid.air.GasAuditLedger;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasLimits;
import cc.ecisr.mmm.units.SignedDyadic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Required bounded gas payload for complete thermal region generations. */
final class GasRegionStateCodec {
	static final String MANIFEST_EXTENSION = "mmm:gas_manifest";
	static final String SECTION_EXTENSION = "mmm:gas_section";
	static final int CAP_MATERIALS = 1;
	static final int CAP_CHEMISTRY = 1 << 1;
	static final int CAP_GAS = 1 << 2;
	private static final int KNOWN_CAPABILITIES = CAP_MATERIALS | CAP_CHEMISTRY | CAP_GAS;
	private static final int MANIFEST_MAGIC = 0x47534d34;
	private static final int SECTION_MAGIC = 0x47535334;
	private static final int PAYLOAD_VERSION = 3;
	private static final int MAX_DYADIC_BYTES = 16 * 1_024;

	record NodeData(int topologyKey, GasInventory inventory) { }
	record SectionLedger(RegionSectionState.SectionKey key, int sealedCellCount) { }
	record ManifestData(
			int capabilities, String registryFingerprint, String chemicalRegistryFingerprint,
			List<NodeData> nodes, GasAuditLedger externalSource, GasAuditLedger escapedSink,
			SignedDyadic massRoundoff, SignedDyadic residualRoundoff, List<SectionLedger> sectionLedgers
	) {
		ManifestData { nodes = List.copyOf(nodes); sectionLedgers = List.copyOf(sectionLedgers); }
	}
	record SectionData(int[] cellNodeOrdinal) {
		SectionData { cellNodeOrdinal = cellNodeOrdinal.clone(); }
		@Override public int[] cellNodeOrdinal() { return cellNodeOrdinal.clone(); }
	}

	byte[] encodeManifest(ManifestData data) throws IOException {
		validateManifest(data);
		return encode(out -> {
			out.writeInt(MANIFEST_MAGIC); out.writeInt(PAYLOAD_VERSION); out.writeInt(data.capabilities());
			out.writeUTF(cc.ecisr.mmm.fluid.air.GasRegistry.UNIT_SYSTEM_ID);
			out.writeLong(Double.doubleToRawLongBits(cc.ecisr.mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA));
			out.writeLong(Double.doubleToRawLongBits(cc.ecisr.mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K));
			out.writeUTF(data.registryFingerprint()); out.writeUTF(data.chemicalRegistryFingerprint());
			out.writeInt(data.nodes().size());
			for (NodeData node : data.nodes()) { out.writeInt(node.topologyKey()); writeInventory(out, node.inventory()); }
			writeAudit(out, data.externalSource()); writeAudit(out, data.escapedSink());
			writeDyadic(out, data.massRoundoff()); writeDyadic(out, data.residualRoundoff());
			out.writeInt(data.sectionLedgers().size());
			for (SectionLedger ledger : data.sectionLedgers()) {
				out.writeInt(ledger.key().x()); out.writeInt(ledger.key().y()); out.writeInt(ledger.key().z());
				out.writeInt(ledger.sealedCellCount());
			}
		});
	}

	ManifestData decodeManifest(byte[] payload) throws IOException {
		try (DataInputStream input = input(payload)) {
			int magic = input.readInt(), version = input.readInt();
			if (magic != MANIFEST_MAGIC || version != PAYLOAD_VERSION) {
				throw new IOException("Unknown gas manifest payload");
			}
			int capabilities = input.readInt();
			if (!cc.ecisr.mmm.fluid.air.GasRegistry.UNIT_SYSTEM_ID.equals(input.readUTF())
					|| input.readLong() != Double.doubleToRawLongBits(cc.ecisr.mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA)
					|| input.readLong() != Double.doubleToRawLongBits(cc.ecisr.mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K)) {
				throw new IOException("Unsupported gas standard state");
			}
			String registry = input.readUTF(), chemistry = input.readUTF();
			int nodeCount = count(input.readInt(), GasLimits.MAX_NODES_PER_REGION, "nodes");
			ArrayList<NodeData> nodes = new ArrayList<>(nodeCount);
			for (int node = 0; node < nodeCount; node++) nodes.add(new NodeData(input.readInt(), readInventory(input)));
			GasAuditLedger source = readAudit(input), sink = readAudit(input);
			SignedDyadic massRoundoff = readDyadic(input), residualRoundoff = readDyadic(input);
			int sectionCount = count(input.readInt(), RegionStateCodec.MAX_SECTIONS, "section ledgers");
			if (sectionCount == 0) throw new IOException("Gas manifest has no section ledgers");
			ArrayList<SectionLedger> ledgers = new ArrayList<>(sectionCount);
			for (int section = 0; section < sectionCount; section++) ledgers.add(new SectionLedger(
					new RegionSectionState.SectionKey(input.readInt(), input.readInt(), input.readInt()),
					count(input.readInt(), Integer.MAX_VALUE, "sealed cells")));
			requireEnd(input);
			ManifestData result = new ManifestData(capabilities, registry, chemistry, nodes, source, sink,
					massRoundoff, residualRoundoff, ledgers);
			validateManifest(result);
			return result;
		} catch (EOFException truncated) {
			throw new IOException("Truncated gas manifest payload", truncated);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid gas manifest payload: " + invalid.getMessage(), invalid);
		}
	}

	byte[] encodeSection(SectionData data) throws IOException {
		if (data == null) throw new IOException("Gas section data is missing");
		return encode(out -> {
			out.writeInt(SECTION_MAGIC); out.writeInt(PAYLOAD_VERSION);
			int[] membership = data.cellNodeOrdinal(); out.writeInt(membership.length);
			for (int value : membership) out.writeInt(value);
		});
	}

	SectionData decodeSection(byte[] payload, int expectedCells, int nodeCount) throws IOException {
		try (DataInputStream input = input(payload)) {
			if (input.readInt() != SECTION_MAGIC || input.readInt() != PAYLOAD_VERSION) {
				throw new IOException("Unknown gas section payload");
			}
			int length = count(input.readInt(), expectedCells, "section cells");
			if (length != expectedCells) throw new IOException("Gas section membership cardinality mismatch");
			int[] membership = new int[length];
			for (int cell = 0; cell < length; cell++) {
				membership[cell] = input.readInt();
				if (membership[cell] < 0 || membership[cell] > nodeCount) {
					throw new IOException("Gas section names an unknown node ordinal");
				}
			}
			requireEnd(input); return new SectionData(membership);
		} catch (EOFException truncated) { throw new IOException("Truncated gas section payload", truncated); }
	}

	private static void validateManifest(ManifestData data) {
		if (data == null || (data.capabilities() & ~KNOWN_CAPABILITIES) != 0
				|| (data.capabilities() & (CAP_MATERIALS | CAP_GAS)) != (CAP_MATERIALS | CAP_GAS)
				|| data.registryFingerprint() == null || !data.registryFingerprint().matches("[0-9a-f]{64}")
				|| data.chemicalRegistryFingerprint() == null
				|| !(data.chemicalRegistryFingerprint().isEmpty() || data.chemicalRegistryFingerprint().matches("[0-9a-f]{64}"))
				|| ((data.capabilities() & CAP_CHEMISTRY) != 0) != !data.chemicalRegistryFingerprint().isEmpty()
				|| data.nodes() == null || data.nodes().size() > GasLimits.MAX_NODES_PER_REGION
				|| data.externalSource() == null || data.escapedSink() == null
				|| data.massRoundoff() == null || data.residualRoundoff() == null
				|| data.sectionLedgers() == null || data.sectionLedgers().isEmpty()
				|| data.sectionLedgers().size() > RegionStateCodec.MAX_SECTIONS) {
			throw new IllegalArgumentException("Gas manifest identity/capability is invalid");
		}
		int previousKey = -1;
		for (NodeData node : data.nodes()) {
			if (node == null || node.topologyKey() < 0 || node.topologyKey() <= previousKey
					|| node.inventory() == null) {
				throw new IllegalArgumentException("Gas manifest nodes are not canonical");
			}
			previousKey = node.topologyKey();
		}
		RegionSectionState.SectionKey previousSection = null;
		for (SectionLedger ledger : data.sectionLedgers()) {
			if (ledger == null || ledger.sealedCellCount() < 0
					|| previousSection != null && previousSection.compareTo(ledger.key()) >= 0) {
				throw new IllegalArgumentException("Gas manifest section ledgers are not canonical");
			}
			previousSection = ledger.key();
		}
		boolean species = data.nodes().stream().flatMap(node -> node.inventory().components().stream())
				.anyMatch(component -> !component.species().isEmpty())
				|| data.externalSource().entries().stream().anyMatch(entry -> !entry.species().isEmpty())
				|| data.escapedSink().entries().stream().anyMatch(entry -> !entry.species().isEmpty());
		if (species && data.chemicalRegistryFingerprint().isEmpty()) {
			throw new IllegalArgumentException("Gas species lack chemical registry identity");
		}
	}

	private static void writeInventory(DataOutputStream out, GasInventory inventory) throws IOException {
		out.writeInt(inventory.components().size());
		for (GasComponentAmount amount : inventory.components()) {
			out.writeUTF(amount.componentId()); out.writeLong(amount.standardVolumeNanos());
			out.writeLong(Double.doubleToRawLongBits(amount.mass())); out.writeLong(Double.doubleToRawLongBits(amount.massResidual()));
			out.writeInt(amount.species().size());
			for (GasComponentAmount.SpeciesAmount species : amount.species()) {
				out.writeUTF(species.speciesId()); out.writeLong(species.amountQuanta());
			}
		}
	}

	private static GasInventory readInventory(DataInputStream input) throws IOException {
		int count = count(input.readInt(), GasLimits.MAX_COMPONENTS_PER_NODE, "node components");
		ArrayList<GasComponentAmount> amounts = new ArrayList<>(count);
		for (int component = 0; component < count; component++) {
			String id = input.readUTF(); long nanos = input.readLong();
			double mass = Double.longBitsToDouble(input.readLong()), residual = Double.longBitsToDouble(input.readLong());
			int speciesCount = count(input.readInt(), GasLimits.MAX_SPECIES_PER_COMPONENT, "component species");
			ArrayList<GasComponentAmount.SpeciesAmount> species = new ArrayList<>(speciesCount);
			for (int entry = 0; entry < speciesCount; entry++) {
				species.add(new GasComponentAmount.SpeciesAmount(input.readUTF(), input.readLong()));
			}
			amounts.add(new GasComponentAmount(id, nanos, mass, species, residual));
		}
		return new GasInventory(amounts);
	}

	private static void writeAudit(DataOutputStream out, GasAuditLedger ledger) throws IOException {
		out.writeInt(ledger.entries().size());
		for (GasAuditLedger.Entry entry : ledger.entries()) {
			out.writeUTF(entry.componentId()); out.writeLong(entry.standardVolumeNanos());
			writeDyadic(out, entry.mass()); writeDyadic(out, entry.massResidual());
			out.writeInt(entry.species().size());
			for (GasComponentAmount.SpeciesAmount species : entry.species()) {
				out.writeUTF(species.speciesId()); out.writeLong(species.amountQuanta());
			}
		}
	}

	private static GasAuditLedger readAudit(DataInputStream input) throws IOException {
		int count = count(input.readInt(), GasLimits.MAX_COMPONENTS, "audit components");
		ArrayList<GasAuditLedger.Entry> entries = new ArrayList<>(count);
		for (int component = 0; component < count; component++) {
			String id = input.readUTF(); long nanos = input.readLong();
			SignedDyadic mass = readDyadic(input), residual = readDyadic(input);
			int speciesCount = count(input.readInt(), GasLimits.MAX_SPECIES_PER_COMPONENT, "audit species");
			ArrayList<GasComponentAmount.SpeciesAmount> species = new ArrayList<>(speciesCount);
			for (int entry = 0; entry < speciesCount; entry++) {
				species.add(new GasComponentAmount.SpeciesAmount(input.readUTF(), input.readLong()));
			}
			entries.add(new GasAuditLedger.Entry(id, nanos, mass, species, residual));
		}
		return new GasAuditLedger(entries);
	}

	private static void writeDyadic(DataOutputStream out, SignedDyadic value) throws IOException {
		byte[] significand = value.significand().toByteArray();
		out.writeInt(value.exponent()); out.writeInt(significand.length); out.write(significand);
	}

	private static SignedDyadic readDyadic(DataInputStream input) throws IOException {
		int exponent = input.readInt(), length = input.readInt();
		if (length <= 0 || length > MAX_DYADIC_BYTES) throw new IOException("Gas dyadic significand length is invalid");
		byte[] bytes = input.readNBytes(length);
		if (bytes.length != length) throw new EOFException("Truncated gas dyadic");
		SignedDyadic result = new SignedDyadic(new BigInteger(bytes), exponent);
		if (result.exponent() != exponent || !java.util.Arrays.equals(result.significand().toByteArray(), bytes)) {
			throw new IOException("Gas dyadic encoding is non-canonical");
		}
		return result;
	}

	private static byte[] encode(Writer writer) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) { writer.write(output); }
		byte[] result = bytes.toByteArray();
		if (result.length > GasLimits.MAX_SERIALIZED_EXTENSION_BYTES) throw new IOException("Gas extension is over budget");
		return result;
	}

	private static DataInputStream input(byte[] payload) throws IOException {
		if (payload == null || payload.length == 0 || payload.length > GasLimits.MAX_SERIALIZED_EXTENSION_BYTES) {
			throw new IOException("Invalid gas extension size");
		}
		return new DataInputStream(new ByteArrayInputStream(payload));
	}

	private static int count(int value, int maximum, String label) throws IOException {
		if (value < 0 || value > maximum) throw new IOException("Invalid gas " + label + " count");
		return value;
	}

	private static void requireEnd(DataInputStream input) throws IOException {
		if (input.read() != -1) throw new IOException("Trailing gas extension data");
	}

	@FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
}
