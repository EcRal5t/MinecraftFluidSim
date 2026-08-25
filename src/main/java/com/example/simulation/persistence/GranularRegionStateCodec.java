package com.example.simulation.persistence;

import com.example.granular.GranularLimits;
import com.example.granular.GranularAuditLedger;
import com.example.granular.GranularStateSnapshot;
import com.example.granular.SolidVolume;
import com.example.chemistry.ChemicalLimits;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Canonical bounded granular payload nested inside a jointly committed region generation. */
final class GranularRegionStateCodec {
	static final String MANIFEST_EXTENSION = "realwater:granular_manifest_v1";
	static final String SECTION_EXTENSION = "realwater:granular_section_v1";
	static final int CAP_MATERIALS = 1;
	static final int CAP_GRANULAR = 1 << 1;
	static final int CAP_CHEMISTRY = 1 << 2;
	static final int CAP_PRECIPITATION = 1 << 3;
	static final int KNOWN_CAPABILITIES = CAP_MATERIALS | CAP_GRANULAR | CAP_CHEMISTRY | CAP_PRECIPITATION;
	private static final int MANIFEST_MAGIC = 0x47524d35;
	private static final int SECTION_MAGIC = 0x47525335;
	private static final int PAYLOAD_VERSION = 2;

	record MaterialLedger(
			int activeCount, long activeSvq, long activeMassBits,
			int depositGroupCount, long depositSvq, long depositMassBits
	) { }

	record SectionLedger(
			RegionSectionState.SectionKey key, int activeCount, int depositGroupCount, int depositCellCount,
			long activeSvq, long activeMassBits, long depositSvq, long depositMassBits
	) { }

	record ManifestData(
			int capabilities, String registryFingerprint, long svqPerBv, int porosityScale,
			int activeBudget, int depositGroupBudget, List<String> materialPalette,
			String chemicalRegistryFingerprint, List<GranularStateSnapshot.StableComposition> solidCompositions,
			Map<String, Long> solidSpeciesTotals, GranularAuditLedger externalSource, GranularAuditLedger escapedSink,
			int activeCount, int depositGroupCount, int depositCellCount,
			long activeSvq, long activeMassBits, long depositSvq, long depositMassBits,
			List<MaterialLedger> materialLedgers, List<SectionLedger> sectionLedgers
	) { }

	record ActiveEntry(int ordinal, GranularStateSnapshot.ActiveParcel parcel) { }
	record SectionData(List<ActiveEntry> active, List<GranularStateSnapshot.DepositGroup> deposits) { }

	byte[] encodeManifest(ManifestData data) {
		return encode(out -> {
			out.writeInt(MANIFEST_MAGIC); out.writeInt(PAYLOAD_VERSION); out.writeInt(data.capabilities());
			out.writeUTF(data.registryFingerprint()); out.writeLong(data.svqPerBv()); out.writeInt(data.porosityScale());
			out.writeInt(data.activeBudget()); out.writeInt(data.depositGroupBudget());
			out.writeInt(data.materialPalette().size());
			for (String id : data.materialPalette()) out.writeUTF(id);
			out.writeUTF(data.chemicalRegistryFingerprint());
			out.writeInt(data.solidCompositions().size());
			for (GranularStateSnapshot.StableComposition composition : data.solidCompositions()) {
				out.writeLong(composition.massResidualBits()); out.writeInt(composition.components().size());
				for (GranularStateSnapshot.CompositionComponent component : composition.components()) {
					out.writeUTF(component.speciesId()); out.writeLong(component.amountQuanta());
				}
			}
			out.writeInt(data.solidSpeciesTotals().size());
			for (Map.Entry<String, Long> entry : new TreeMap<>(data.solidSpeciesTotals()).entrySet()) {
				out.writeUTF(entry.getKey()); out.writeLong(entry.getValue());
			}
			writeAudit(out, data.externalSource()); writeAudit(out, data.escapedSink());
			out.writeInt(data.activeCount()); out.writeInt(data.depositGroupCount()); out.writeInt(data.depositCellCount());
			out.writeLong(data.activeSvq()); out.writeLong(data.activeMassBits());
			out.writeLong(data.depositSvq()); out.writeLong(data.depositMassBits());
			out.writeInt(data.materialLedgers().size());
			for (MaterialLedger ledger : data.materialLedgers()) writeMaterialLedger(out, ledger);
			out.writeInt(data.sectionLedgers().size());
			for (SectionLedger ledger : data.sectionLedgers()) {
				out.writeInt(ledger.key().x()); out.writeInt(ledger.key().y()); out.writeInt(ledger.key().z());
				out.writeInt(ledger.activeCount()); out.writeInt(ledger.depositGroupCount()); out.writeInt(ledger.depositCellCount());
				out.writeLong(ledger.activeSvq()); out.writeLong(ledger.activeMassBits());
				out.writeLong(ledger.depositSvq()); out.writeLong(ledger.depositMassBits());
			}
		});
	}

	ManifestData decodeManifest(byte[] bytes) throws IOException {
		try (DataInputStream input = input(bytes)) {
			if (input.readInt() != MANIFEST_MAGIC) {
				throw new IOException("Unknown granular manifest payload");
			}
			if (input.readInt() != PAYLOAD_VERSION) throw new IOException("Unknown granular manifest payload");
			int capabilities = input.readInt();
			if ((capabilities & ~KNOWN_CAPABILITIES) != 0
					|| (capabilities & (CAP_MATERIALS | CAP_GRANULAR)) != (CAP_MATERIALS | CAP_GRANULAR)
					|| (capabilities & CAP_PRECIPITATION) != 0 && (capabilities & CAP_CHEMISTRY) == 0) {
				throw new IOException("Invalid granular capability set");
			}
			String fingerprint = input.readUTF();
			if (!fingerprint.matches("[0-9a-f]{64}")) throw new IOException("Invalid granular registry fingerprint");
			long svqScale = input.readLong(); int porosityScale = input.readInt();
			if (svqScale != SolidVolume.SVQ_PER_BV || porosityScale != GranularLimits.POROSITY_SCALE) {
				throw new IOException("Unsupported granular unit scale");
			}
			int activeBudget = bounded(input.readInt(), 1, GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, "active budget");
			int depositBudget = bounded(input.readInt(), 1, GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION, "deposit budget");
			int materialCount = bounded(input.readInt(), 1, GranularLimits.MAX_MATERIALS, "material palette");
			List<String> palette = new ArrayList<>(materialCount);
			for (int index = 0; index < materialCount; index++) palette.add(input.readUTF());
			String chemicalFingerprint = input.readUTF();
			if (!chemicalFingerprint.isEmpty() && !chemicalFingerprint.matches("[0-9a-f]{64}")) {
				throw new IOException("Invalid solid composition chemical registry fingerprint");
			}
			int compositionCount = bounded(input.readInt(), 0,
					GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION + GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION,
					"solid composition table");
			List<GranularStateSnapshot.StableComposition> compositions = new ArrayList<>(compositionCount);
			for (int index = 0; index < compositionCount; index++) {
				long residual = input.readLong();
				int entries = bounded(input.readInt(), 1, GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES,
						"solid composition entries");
				List<GranularStateSnapshot.CompositionComponent> components = new ArrayList<>(entries);
				for (int entry = 0; entry < entries; entry++) components.add(
						new GranularStateSnapshot.CompositionComponent(input.readUTF(), positive(input.readLong(),
								"solid composition amount")));
				compositions.add(new GranularStateSnapshot.StableComposition(List.copyOf(components), residual));
			}
			int speciesLedgerCount = bounded(input.readInt(), 0, ChemicalLimits.MAX_SPECIES, "solid species ledger");
			TreeMap<String, Long> speciesTotals = new TreeMap<>();
			for (int index = 0; index < speciesLedgerCount; index++) {
				String id = input.readUTF(); long amount = positive(input.readLong(), "solid species total");
				if (speciesTotals.put(id, amount) != null) throw new IOException("Duplicate solid species ledger id " + id);
			}
			GranularAuditLedger externalSource = readAudit(input);
			GranularAuditLedger escapedSink = readAudit(input);
			int activeCount = bounded(input.readInt(), 0, activeBudget, "active count");
			int depositCount = bounded(input.readInt(), 0, depositBudget, "deposit group count");
			int depositCells = bounded(input.readInt(), 0, depositCount, "deposit cell count");
			long activeSvq = positiveOrZero(input.readLong(), "active SVQ"); long activeMass = input.readLong();
			long depositSvq = positiveOrZero(input.readLong(), "deposit SVQ"); long depositMass = input.readLong();
			requireMass(activeMass, activeCount, "active mass"); requireMass(depositMass, depositCount, "deposit mass");
			int ledgerCount = bounded(input.readInt(), materialCount, materialCount, "material ledgers");
			List<MaterialLedger> ledgers = new ArrayList<>(ledgerCount);
			for (int index = 0; index < ledgerCount; index++) ledgers.add(readMaterialLedger(input));
			int sectionCount = bounded(input.readInt(), 1, RegionStateCodec.MAX_SECTIONS, "granular section ledgers");
			List<SectionLedger> sections = new ArrayList<>(sectionCount);
			for (int index = 0; index < sectionCount; index++) {
				RegionSectionState.SectionKey key = new RegionSectionState.SectionKey(input.readInt(), input.readInt(), input.readInt());
				int sectionActive = bounded(input.readInt(), 0, activeBudget, "section active count");
				int sectionDeposit = bounded(input.readInt(), 0, depositBudget, "section deposit count");
				int sectionCells = bounded(input.readInt(), 0, sectionDeposit, "section deposit cells");
				long sectionActiveSvq = positiveOrZero(input.readLong(), "section active SVQ"); long sectionActiveMass = input.readLong();
				long sectionDepositSvq = positiveOrZero(input.readLong(), "section deposit SVQ"); long sectionDepositMass = input.readLong();
				requireMass(sectionActiveMass, sectionActive, "section active mass");
				requireMass(sectionDepositMass, sectionDeposit, "section deposit mass");
				sections.add(new SectionLedger(key, sectionActive, sectionDeposit, sectionCells,
						sectionActiveSvq, sectionActiveMass, sectionDepositSvq, sectionDepositMass));
			}
			requireEnd(input);
			return new ManifestData(capabilities, fingerprint, svqScale, porosityScale, activeBudget, depositBudget,
					List.copyOf(palette), chemicalFingerprint, List.copyOf(compositions), Map.copyOf(speciesTotals),
					externalSource, escapedSink,
					activeCount, depositCount, depositCells, activeSvq, activeMass,
					depositSvq, depositMass, List.copyOf(ledgers), List.copyOf(sections));
		} catch (EOFException truncated) { throw new IOException("Truncated granular manifest payload", truncated); }
		catch (IllegalArgumentException invalid) { throw new IOException("Invalid granular manifest payload: " + invalid.getMessage(), invalid); }
	}

	private static void writeAudit(DataOutputStream output, GranularAuditLedger ledger) throws IOException {
		output.writeInt(ledger.entries().size());
		for (GranularAuditLedger.Entry entry : ledger.entries()) {
			output.writeUTF(entry.materialId()); output.writeLong(entry.solidVolumeSvq()); output.writeLong(entry.massBits());
			output.writeLong(entry.massResidualBits()); output.writeInt(entry.composition().size());
			for (GranularAuditLedger.SpeciesAmount amount : entry.composition()) {
				output.writeUTF(amount.speciesId()); output.writeLong(amount.amountQuanta());
			}
		}
	}

	private static GranularAuditLedger readAudit(DataInputStream input) throws IOException {
		int count = bounded(input.readInt(), 0, GranularLimits.MAX_MATERIALS, "granular audit entries");
		ArrayList<GranularAuditLedger.Entry> entries = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			String materialId = input.readUTF(); long svq = positive(input.readLong(), "granular audit SVQ");
			long massBits = input.readLong();
			if (!(Double.isFinite(Double.longBitsToDouble(massBits)) && Double.longBitsToDouble(massBits) > 0.0)) {
				throw new IOException("Invalid granular audit mass");
			}
			long residualBits = input.readLong();
			int speciesCount = bounded(input.readInt(), 0, GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES,
					"granular audit composition entries");
			ArrayList<GranularAuditLedger.SpeciesAmount> composition = new ArrayList<>(speciesCount);
			for (int species = 0; species < speciesCount; species++) composition.add(
					new GranularAuditLedger.SpeciesAmount(input.readUTF(), positive(input.readLong(), "granular audit species amount")));
			entries.add(new GranularAuditLedger.Entry(materialId, svq, massBits, residualBits, composition));
		}
		return new GranularAuditLedger(entries);
	}

	byte[] encodeSection(SectionData data) {
		return encode(out -> {
			out.writeInt(SECTION_MAGIC); out.writeInt(PAYLOAD_VERSION);
			out.writeInt(data.active().size());
			for (ActiveEntry entry : data.active()) {
				GranularStateSnapshot.ActiveParcel parcel = entry.parcel();
				out.writeInt(entry.ordinal());
				out.writeInt(Float.floatToRawIntBits(parcel.x())); out.writeInt(Float.floatToRawIntBits(parcel.y()));
				out.writeInt(Float.floatToRawIntBits(parcel.z())); out.writeInt(Float.floatToRawIntBits(parcel.velocityX()));
				out.writeInt(Float.floatToRawIntBits(parcel.velocityY())); out.writeInt(Float.floatToRawIntBits(parcel.velocityZ()));
				out.writeLong(parcel.solidVolumeSvq()); out.writeLong(Double.doubleToRawLongBits(parcel.mass()));
				out.writeInt(parcel.materialPaletteIndex()); out.writeInt(parcel.solidCompositionIndex());
			}
			out.writeInt(data.deposits().size());
			for (GranularStateSnapshot.DepositGroup group : data.deposits()) {
				out.writeInt(group.cell()); out.writeInt(group.porosityQ()); out.writeInt(group.materialPaletteIndex());
				out.writeInt(group.solidCompositionIndex()); out.writeLong(group.solidVolumeSvq());
				out.writeLong(Double.doubleToRawLongBits(group.mass()));
			}
		});
	}

	SectionData decodeSection(byte[] bytes, int activeBudget, int depositBudget) throws IOException {
		try (DataInputStream input = input(bytes)) {
			if (input.readInt() != SECTION_MAGIC) {
				throw new IOException("Unknown granular section payload");
			}
			if (input.readInt() != PAYLOAD_VERSION) throw new IOException("Unknown granular section payload");
			int activeCount = bounded(input.readInt(), 0, activeBudget, "section active entries");
			List<ActiveEntry> active = new ArrayList<>(activeCount);
			for (int index = 0; index < activeCount; index++) {
				int ordinal = input.readInt();
				GranularStateSnapshot.ActiveParcel parcel = new GranularStateSnapshot.ActiveParcel(
						Float.intBitsToFloat(input.readInt()), Float.intBitsToFloat(input.readInt()), Float.intBitsToFloat(input.readInt()),
						Float.intBitsToFloat(input.readInt()), Float.intBitsToFloat(input.readInt()), Float.intBitsToFloat(input.readInt()),
						input.readLong(), Double.longBitsToDouble(input.readLong()), input.readInt(), input.readInt());
				active.add(new ActiveEntry(ordinal, parcel));
			}
			int depositCount = bounded(input.readInt(), 0, depositBudget, "section deposit entries");
			List<GranularStateSnapshot.DepositGroup> deposits = new ArrayList<>(depositCount);
			for (int index = 0; index < depositCount; index++) deposits.add(new GranularStateSnapshot.DepositGroup(
					input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readLong(),
					Double.longBitsToDouble(input.readLong())));
			requireEnd(input);
			return new SectionData(List.copyOf(active), List.copyOf(deposits));
		} catch (EOFException truncated) { throw new IOException("Truncated granular section payload", truncated); }
	}

	private static void writeMaterialLedger(DataOutputStream output, MaterialLedger ledger) throws IOException {
		output.writeInt(ledger.activeCount()); output.writeLong(ledger.activeSvq()); output.writeLong(ledger.activeMassBits());
		output.writeInt(ledger.depositGroupCount()); output.writeLong(ledger.depositSvq()); output.writeLong(ledger.depositMassBits());
	}

	private static MaterialLedger readMaterialLedger(DataInputStream input) throws IOException {
		int active = bounded(input.readInt(), 0, GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, "material active count");
		long activeSvq = positiveOrZero(input.readLong(), "material active SVQ"); long activeMass = input.readLong();
		int deposits = bounded(input.readInt(), 0, GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION, "material deposit count");
		long depositSvq = positiveOrZero(input.readLong(), "material deposit SVQ"); long depositMass = input.readLong();
		requireMass(activeMass, active, "material active mass"); requireMass(depositMass, deposits, "material deposit mass");
		return new MaterialLedger(active, activeSvq, activeMass, deposits, depositSvq, depositMass);
	}

	private static byte[] encode(Writer writer) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream output = new DataOutputStream(bytes)) { writer.write(output); }
			return bytes.toByteArray();
		} catch (IOException impossible) { throw new IllegalStateException(impossible); }
	}

	private static DataInputStream input(byte[] bytes) throws IOException {
		if (bytes == null || bytes.length == 0 || bytes.length > 16 * 1_048_576) {
			throw new IOException("Invalid granular extension size");
		}
		return new DataInputStream(new ByteArrayInputStream(bytes));
	}

	private static int bounded(int value, int minimum, int maximum, String label) throws IOException {
		if (value < minimum || value > maximum) throw new IOException("Invalid " + label + " " + value);
		return value;
	}

	private static long positiveOrZero(long value, String label) throws IOException {
		if (value < 0L) throw new IOException("Negative " + label);
		return value;
	}

	private static long positive(long value, String label) throws IOException {
		if (value <= 0L) throw new IOException("Non-positive " + label); return value;
	}

	private static void requireMass(long bits, int count, String label) throws IOException {
		double mass = Double.longBitsToDouble(bits);
		if (!Double.isFinite(mass) || mass < 0.0 || count == 0 && bits != Double.doubleToRawLongBits(0.0)
				|| count > 0 && !(mass > 0.0)) throw new IOException("Invalid " + label);
	}

	private static void requireEnd(DataInputStream input) throws IOException {
		if (input.read() != -1) throw new IOException("Trailing granular extension data");
	}

	@FunctionalInterface private interface Writer { void write(DataOutputStream output) throws IOException; }
}
