package com.example.simulation.persistence;

import com.example.thermal.SignedDyadic;
import com.example.thermal.CoherentPhaseStore;
import com.example.thermal.ThermalAuditLedger;
import com.example.thermal.ThermalLimits;
import com.example.thermal.ThermalRegistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Required typed thermal payload nested inside a complete region-state v6 generation. */
final class ThermalRegionStateCodec {
	static final String MANIFEST_EXTENSION = "realwater:thermal_manifest_v1";
	static final String SECTION_EXTENSION = "realwater:thermal_section_v1";
	static final int CAP_MATERIALS = 1;
	static final int CAP_CHEMISTRY = 1 << 1;
	static final int CAP_GRANULAR = 1 << 2;
	static final int CAP_GAS = 1 << 3;
	static final int CAP_THERMAL = 1 << 4;
	private static final int KNOWN_CAPABILITIES = CAP_MATERIALS | CAP_CHEMISTRY | CAP_GRANULAR | CAP_GAS | CAP_THERMAL;
	private static final int MANIFEST_MAGIC = 0x5257544d; // RWTM
	private static final int SECTION_MAGIC = 0x52575453; // RWTS
	private static final int PAYLOAD_VERSION = 1;
	private static final int MAX_DYADIC_BYTES = ThermalLimits.MAX_DYADIC_SIGNIFICAND_BITS / 8 + 2;

	byte[] encodeManifest(ManifestData data) throws IOException {
		validateManifest(data);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(MANIFEST_MAGIC); out.writeInt(PAYLOAD_VERSION); out.writeInt(data.capabilities());
			out.writeUTF(data.registryFingerprint()); out.writeUTF(ThermalRegistry.UNIT_SYSTEM_ID);
			out.writeInt(data.liquidMarkerCount()); out.writeInt(data.activeParcelCount());
			out.writeInt(data.depositGroupCount()); out.writeInt(data.coherentGroupCount()); out.writeInt(data.gasNodeCount());
			writeDyadic(out, data.liquidEnergy()); writeDyadic(out, data.activeEnergy());
			writeDyadic(out, data.depositEnergy()); writeDyadic(out, data.coherentEnergy());
			writeDyadic(out, data.gasEnergy()); writeDyadic(out, data.totalEnergy());
			writeAudit(out, data.audit());
			out.writeInt(data.sectionLedgers().size());
			for (SectionLedger ledger : data.sectionLedgers()) {
				out.writeInt(ledger.key().x()); out.writeInt(ledger.key().y()); out.writeInt(ledger.key().z());
				out.writeInt(ledger.liquidMarkerCount()); out.writeInt(ledger.activeParcelCount());
				out.writeInt(ledger.depositGroupCount()); out.writeInt(ledger.coherentGroupCount());
				writeDyadic(out, ledger.energy());
			}
			out.writeInt(data.gasNodeEnergyBits().length);
			for (long energy : data.gasNodeEnergyBits()) out.writeLong(energy);
		}
		return bounded(bytes.toByteArray());
	}

	ManifestData decodeManifest(byte[] payload) throws IOException {
		bounded(payload);
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
			if (input.readInt() != MANIFEST_MAGIC || input.readInt() != PAYLOAD_VERSION) {
				throw new IOException("Unknown thermal manifest payload");
			}
			int capabilities = input.readInt(); String fingerprint = input.readUTF(); String unit = input.readUTF();
			if (!ThermalRegistry.UNIT_SYSTEM_ID.equals(unit)) throw new IOException("Thermal region unit system mismatch");
			int liquid = count(input.readInt(), Integer.MAX_VALUE, "liquid markers");
			int active = count(input.readInt(), Integer.MAX_VALUE, "active parcels");
			int deposits = count(input.readInt(), Integer.MAX_VALUE, "deposit groups");
			int coherent = count(input.readInt(), ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION, "coherent groups");
			int gas = count(input.readInt(), ThermalLimits.MAX_GAS_NODES_PER_REGION, "gas nodes");
			SignedDyadic liquidEnergy = readDyadic(input), activeEnergy = readDyadic(input);
			SignedDyadic depositEnergy = readDyadic(input), coherentEnergy = readDyadic(input);
			SignedDyadic gasEnergy = readDyadic(input), totalEnergy = readDyadic(input);
			ThermalAuditLedger audit = readAudit(input);
			int sections = count(input.readInt(), RegionStateCodec.MAX_SECTIONS, "section ledgers");
			if (sections == 0) throw new IOException("Thermal manifest has no section ledgers");
			ArrayList<SectionLedger> ledgers = new ArrayList<>(sections);
			for (int index = 0; index < sections; index++) ledgers.add(new SectionLedger(
					new RegionSectionState.SectionKey(input.readInt(), input.readInt(), input.readInt()),
					count(input.readInt(), Integer.MAX_VALUE, "section liquid markers"),
					count(input.readInt(), Integer.MAX_VALUE, "section active parcels"),
					count(input.readInt(), Integer.MAX_VALUE, "section deposit groups"),
					count(input.readInt(), ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION, "section coherent groups"),
					readDyadic(input)));
			int gasEnergyCount = count(input.readInt(), ThermalLimits.MAX_GAS_NODES_PER_REGION, "gas energies");
			long[] gasEnergyBits = new long[gasEnergyCount];
			for (int index = 0; index < gasEnergyCount; index++) gasEnergyBits[index] = input.readLong();
			requireEnd(input);
			ManifestData result = new ManifestData(capabilities, fingerprint, liquid, active, deposits, coherent, gas,
					liquidEnergy, activeEnergy, depositEnergy, coherentEnergy, gasEnergy, totalEnergy,
					audit, ledgers, gasEnergyBits);
			validateManifest(result);
			return result;
		} catch (EOFException truncated) {
			throw new IOException("Truncated thermal manifest payload", truncated);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid thermal manifest payload: " + invalid.getMessage(), invalid);
		}
	}

	byte[] encodeSection(SectionData data) throws IOException {
		validateSection(data);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(SECTION_MAGIC); out.writeInt(PAYLOAD_VERSION);
			writeDoubles(out, data.liquidMarkerEnergyBits()); writeDoubles(out, data.activeParcelEnergyBits());
			writeDoubles(out, data.depositGroupEnergyBits());
			out.writeInt(data.coherentGroups().size());
			for (CoherentEntry group : data.coherentGroups()) {
				out.writeInt(group.cell()); out.writeUTF(group.phaseId()); out.writeLong(group.occupiedVolumeSvq());
				out.writeLong(group.massBits()); writeComposition(out, group.composition());
				out.writeLong(group.internalEnergyBits());
			}
			writeDyadic(out, data.energy());
		}
		return bounded(bytes.toByteArray());
	}

	SectionData decodeSection(byte[] payload, int maximumLiquid, int maximumActive, int maximumDeposits) throws IOException {
		bounded(payload);
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
			if (input.readInt() != SECTION_MAGIC || input.readInt() != PAYLOAD_VERSION) {
				throw new IOException("Unknown thermal section payload");
			}
			long[] liquid = readDoubles(input, maximumLiquid, "liquid marker energies");
			long[] active = readDoubles(input, maximumActive, "active parcel energies");
			long[] deposits = readDoubles(input, maximumDeposits, "deposit group energies");
			int coherentCount = count(input.readInt(), ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION, "coherent groups");
			ArrayList<CoherentEntry> coherent = new ArrayList<>(coherentCount);
			for (int index = 0; index < coherentCount; index++) coherent.add(new CoherentEntry(
					input.readInt(), input.readUTF(), input.readLong(), input.readLong(),
					readComposition(input), input.readLong()));
			SignedDyadic energy = readDyadic(input); requireEnd(input);
			SectionData result = new SectionData(liquid, active, deposits, coherent, energy);
			validateSection(result);
			return result;
		} catch (EOFException truncated) {
			throw new IOException("Truncated thermal section payload", truncated);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid thermal section payload: " + invalid.getMessage(), invalid);
		}
	}

	private static void validateManifest(ManifestData data) {
		if (data == null || (data.capabilities() & ~KNOWN_CAPABILITIES) != 0
				|| (data.capabilities() & (CAP_MATERIALS | CAP_THERMAL)) != (CAP_MATERIALS | CAP_THERMAL)
				|| data.registryFingerprint() == null || !data.registryFingerprint().matches("[0-9a-f]{64}")
				|| data.sectionLedgers() == null || data.sectionLedgers().isEmpty()
				|| data.sectionLedgers().size() > RegionStateCodec.MAX_SECTIONS
				|| data.gasNodeEnergyBits() == null || data.gasNodeEnergyBits().length != data.gasNodeCount()) {
			throw new IllegalArgumentException("Thermal manifest identity/counts are invalid");
		}
		count(data.liquidMarkerCount(), Integer.MAX_VALUE, "liquid markers");
		count(data.activeParcelCount(), Integer.MAX_VALUE, "active parcels");
		count(data.depositGroupCount(), Integer.MAX_VALUE, "deposit groups");
		count(data.coherentGroupCount(), ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION, "coherent groups");
		count(data.gasNodeCount(), ThermalLimits.MAX_GAS_NODES_PER_REGION, "gas nodes");
		if (data.gasNodeCount() > 0 && (data.capabilities() & CAP_GAS) == 0
				|| data.activeParcelCount() + data.depositGroupCount() > 0 && (data.capabilities() & CAP_GRANULAR) == 0) {
			throw new IllegalArgumentException("Thermal manifest inventories lack their prerequisite capability");
		}
		List<SectionLedger> sorted = new ArrayList<>(data.sectionLedgers()); sorted.sort(Comparator.comparing(SectionLedger::key));
		if (!sorted.equals(data.sectionLedgers())) throw new IllegalArgumentException("Thermal section ledgers are not canonical");
		int liquid = 0, active = 0, deposits = 0, coherent = 0; SignedDyadic sectionEnergy = SignedDyadic.ZERO;
		RegionSectionState.SectionKey previous = null;
		for (SectionLedger ledger : data.sectionLedgers()) {
			if (previous != null && previous.equals(ledger.key())) throw new IllegalArgumentException("Duplicate thermal section ledger");
			liquid = Math.addExact(liquid, ledger.liquidMarkerCount()); active = Math.addExact(active, ledger.activeParcelCount());
			deposits = Math.addExact(deposits, ledger.depositGroupCount()); coherent = Math.addExact(coherent, ledger.coherentGroupCount());
			sectionEnergy = sectionEnergy.add(ledger.energy()); previous = ledger.key();
		}
		SignedDyadic components = data.liquidEnergy().add(data.activeEnergy()).add(data.depositEnergy())
				.add(data.coherentEnergy()).add(data.gasEnergy());
		if (liquid != data.liquidMarkerCount() || active != data.activeParcelCount()
				|| deposits != data.depositGroupCount() || coherent != data.coherentGroupCount()
				|| !components.equals(data.totalEnergy()) || !sectionEnergy.add(data.gasEnergy()).equals(data.totalEnergy())
				|| !data.audit().expectedInventory().equals(data.totalEnergy())) {
			throw new IllegalArgumentException("Thermal manifest energy/count ledger does not close");
		}
		for (long bits : data.gasNodeEnergyBits()) requireFinite(bits, "gas node energy");
		if (!SignedDyadic.sum(toDoubles(data.gasNodeEnergyBits())).equals(data.gasEnergy())) {
			throw new IllegalArgumentException("Thermal gas energy ledger does not close");
		}
	}

	private static void validateSection(SectionData data) {
		if (data == null || data.liquidMarkerEnergyBits() == null || data.activeParcelEnergyBits() == null
				|| data.depositGroupEnergyBits() == null || data.coherentGroups() == null || data.energy() == null) {
			throw new IllegalArgumentException("Thermal section data is missing");
		}
		SignedDyadic energy = SignedDyadic.ZERO;
		for (long[] values : List.of(data.liquidMarkerEnergyBits(), data.activeParcelEnergyBits(), data.depositGroupEnergyBits())) {
			for (long bits : values) { requireFinite(bits, "thermal inventory energy"); energy = energy.add(SignedDyadic.of(Double.longBitsToDouble(bits))); }
		}
		CoherentEntry previous = null;
		for (CoherentEntry group : data.coherentGroups()) {
			if (previous != null && CoherentEntry.ORDER.compare(previous, group) >= 0) {
				throw new IllegalArgumentException("Thermal coherent groups are not canonical");
			}
			energy = energy.add(SignedDyadic.of(group.internalEnergy())); previous = group;
		}
		if (!energy.equals(data.energy())) throw new IllegalArgumentException("Thermal section energy ledger does not close");
	}

	private static void writeAudit(DataOutputStream out, ThermalAuditLedger audit) throws IOException {
		writeDyadic(out, audit.initialInventory()); writeDyadic(out, audit.externalSource());
		writeDyadic(out, audit.externalSink()); writeDyadic(out, audit.mechanicalWork()); writeDyadic(out, audit.roundoffResidual());
	}
	private static void writeComposition(
			DataOutputStream out, CoherentPhaseStore.Composition composition
	) throws IOException {
		out.writeBoolean(composition != null);
		if (composition == null) return;
		out.writeLong(composition.massResidualBits());
		out.writeInt(composition.components().size());
		for (CoherentPhaseStore.Component component : composition.components()) {
			out.writeUTF(component.speciesId()); out.writeLong(component.amountQuanta());
		}
	}
	private static CoherentPhaseStore.Composition readComposition(DataInputStream input) throws IOException {
		if (!input.readBoolean()) return null;
		long residualBits = input.readLong();
		int count = count(input.readInt(), com.example.chemistry.ChemicalLimits.MAX_SPECIES_PER_PARCEL,
				"coherent composition components");
		if (count == 0) throw new IOException("Empty coherent composition");
		ArrayList<CoherentPhaseStore.Component> components = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			components.add(new CoherentPhaseStore.Component(input.readUTF(), input.readLong()));
		}
		return new CoherentPhaseStore.Composition(components, residualBits);
	}
	private static ThermalAuditLedger readAudit(DataInputStream input) throws IOException {
		return new ThermalAuditLedger(readDyadic(input), readDyadic(input), readDyadic(input), readDyadic(input), readDyadic(input));
	}
	private static void writeDyadic(DataOutputStream out, SignedDyadic value) throws IOException {
		byte[] significand = value.significand().toByteArray();
		out.writeInt(value.exponent()); out.writeInt(significand.length); out.write(significand);
	}
	private static SignedDyadic readDyadic(DataInputStream input) throws IOException {
		int exponent = input.readInt(), length = input.readInt();
		if (length <= 0 || length > MAX_DYADIC_BYTES) throw new IOException("Thermal dyadic significand length is invalid");
		byte[] bytes = input.readNBytes(length);
		if (bytes.length != length) throw new EOFException("Truncated thermal dyadic");
		SignedDyadic result = new SignedDyadic(new BigInteger(bytes), exponent);
		if (!java.util.Arrays.equals(result.significand().toByteArray(), bytes) || result.exponent() != exponent) {
			throw new IOException("Thermal dyadic encoding is non-canonical");
		}
		return result;
	}
	private static void writeDoubles(DataOutputStream out, long[] bits) throws IOException {
		out.writeInt(bits.length); for (long value : bits) out.writeLong(value);
	}
	private static long[] readDoubles(DataInputStream input, int maximum, String label) throws IOException {
		int count = count(input.readInt(), maximum, label); long[] result = new long[count];
		for (int index = 0; index < count; index++) result[index] = input.readLong();
		return result;
	}
	private static int count(int value, int maximum, String label) {
		if (value < 0 || value > maximum) throw new IllegalArgumentException("Invalid thermal " + label + " count");
		return value;
	}
	private static void requireFinite(long bits, String label) {
		if (!Double.isFinite(Double.longBitsToDouble(bits))) throw new IllegalArgumentException(label + " is not finite");
	}
	private static double[] toDoubles(long[] bits) {
		double[] result = new double[bits.length];
		for (int index = 0; index < bits.length; index++) result[index] = Double.longBitsToDouble(bits[index]);
		return result;
	}
	private static byte[] bounded(byte[] payload) throws IOException {
		if (payload == null || payload.length > ThermalLimits.MAX_SERIALIZED_EXTENSION_BYTES) {
			throw new IOException("Thermal extension is over budget");
		}
		return payload;
	}
	private static void requireEnd(DataInputStream input) throws IOException {
		if (input.read() != -1) throw new IOException("Trailing thermal extension bytes");
	}

	record ManifestData(
			int capabilities, String registryFingerprint,
			int liquidMarkerCount, int activeParcelCount, int depositGroupCount, int coherentGroupCount, int gasNodeCount,
			SignedDyadic liquidEnergy, SignedDyadic activeEnergy, SignedDyadic depositEnergy,
			SignedDyadic coherentEnergy, SignedDyadic gasEnergy, SignedDyadic totalEnergy,
			ThermalAuditLedger audit, List<SectionLedger> sectionLedgers, long[] gasNodeEnergyBits
	) {
		ManifestData { sectionLedgers = List.copyOf(sectionLedgers); gasNodeEnergyBits = gasNodeEnergyBits.clone(); }
		@Override public long[] gasNodeEnergyBits() { return gasNodeEnergyBits.clone(); }
	}

	record SectionLedger(
			RegionSectionState.SectionKey key, int liquidMarkerCount, int activeParcelCount,
			int depositGroupCount, int coherentGroupCount, SignedDyadic energy
	) { }

	record SectionData(
			long[] liquidMarkerEnergyBits, long[] activeParcelEnergyBits, long[] depositGroupEnergyBits,
			List<CoherentEntry> coherentGroups, SignedDyadic energy
	) {
		SectionData {
			liquidMarkerEnergyBits = liquidMarkerEnergyBits.clone(); activeParcelEnergyBits = activeParcelEnergyBits.clone();
			depositGroupEnergyBits = depositGroupEnergyBits.clone(); coherentGroups = List.copyOf(coherentGroups);
		}
		@Override public long[] liquidMarkerEnergyBits() { return liquidMarkerEnergyBits.clone(); }
		@Override public long[] activeParcelEnergyBits() { return activeParcelEnergyBits.clone(); }
		@Override public long[] depositGroupEnergyBits() { return depositGroupEnergyBits.clone(); }
	}

	record CoherentEntry(
			int cell, String phaseId, long occupiedVolumeSvq, long massBits,
			CoherentPhaseStore.Composition composition, long internalEnergyBits
	) {
		static final Comparator<CoherentEntry> ORDER = Comparator.comparingInt(CoherentEntry::cell)
				.thenComparing(CoherentEntry::phaseId);
		CoherentEntry {
			ThermalIdsProxy.require(phaseId);
			if (cell < 0 || occupiedVolumeSvq <= 0L
					|| !(Double.isFinite(Double.longBitsToDouble(massBits)) && Double.longBitsToDouble(massBits) > 0.0)
					|| !Double.isFinite(Double.longBitsToDouble(internalEnergyBits))) {
				throw new IllegalArgumentException("Invalid coherent thermal entry");
			}
		}
		double mass() { return Double.longBitsToDouble(massBits); }
		double internalEnergy() { return Double.longBitsToDouble(internalEnergyBits); }
	}

	/** Avoids exporting the package-private thermal ID validator through the persistence API. */
	private static final class ThermalIdsProxy {
		private static void require(String id) {
			if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || id.length() > ThermalLimits.MAX_THERMAL_ID_LENGTH) {
				throw new IllegalArgumentException("Invalid coherent thermal phase id");
			}
		}
	}
}
