package mmm.simulation.persistence;


import mmm.fluid.air.GasAuditLedger;
import mmm.fluid.air.GasComponentAmount;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasLimits;
import mmm.units.SignedDyadic;

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
	private static final int SPATIAL_MANIFEST_MAGIC = 0x4753504d; // GSPM
	private static final int SPATIAL_SECTION_MAGIC = 0x47535053; // GSPS
	private static final int MAX_DYADIC_BYTES = 16 * 1_024;

	record SpatialEntry(int cell, GasInventory inventory, double energy) { }
	static boolean isSpatial(byte[] payload) { return payload != null && payload.length >= 4 && java.nio.ByteBuffer.wrap(payload).getInt() == SPATIAL_MANIFEST_MAGIC; }
	record SpatialManifest(int capabilities, String registryFingerprint, String chemicalRegistryFingerprint,
			GasAuditLedger inventory, GasAuditLedger externalSource, GasAuditLedger escapedSink,
			GasAuditLedger phaseSource, GasAuditLedger phaseSink, SignedDyadic energy) { }

	byte[] encodeSpatialManifest(SpatialManifest data) throws IOException {
		return encode(out -> {
			out.writeInt(SPATIAL_MANIFEST_MAGIC);out.writeInt(data.capabilities());
			out.writeUTF(mmm.fluid.air.GasRegistry.UNIT_SYSTEM_ID);out.writeDouble(mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA);out.writeDouble(mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K);
			out.writeUTF(data.registryFingerprint());out.writeUTF(data.chemicalRegistryFingerprint());
			writeAudit(out,data.inventory());writeAudit(out,data.externalSource());writeAudit(out,data.escapedSink());
			writeAudit(out,data.phaseSource());writeAudit(out,data.phaseSink());writeDyadic(out,data.energy());
		});
	}

	SpatialManifest decodeSpatialManifest(byte[] payload) throws IOException {
		try(var in=input(payload)) {
			if(in.readInt()!=SPATIAL_MANIFEST_MAGIC)throw new IOException("Unknown spatial gas manifest");
			int capabilities=in.readInt();
			if(!mmm.fluid.air.GasRegistry.UNIT_SYSTEM_ID.equals(in.readUTF())||in.readDouble()!=mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA||in.readDouble()!=mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K)
				throw new IOException("Spatial gas standard state differs");
			String registry=in.readUTF(),chemical=in.readUTF();
			if((capabilities&~KNOWN_CAPABILITIES)!=0||(capabilities&CAP_GAS)==0||!registry.matches("[0-9a-f]{64}")
					||!(chemical.isEmpty()||chemical.matches("[0-9a-f]{64}"))||((capabilities&CAP_CHEMISTRY)!=0)!=!chemical.isEmpty())
				throw new IOException("Spatial gas manifest identity differs");
			var result=new SpatialManifest(capabilities,registry,chemical,readAudit(in),readAudit(in),readAudit(in),readAudit(in),readAudit(in),readDyadic(in));
			requireEnd(in);return result;
		} catch(EOFException|IllegalArgumentException failure){throw new IOException("Invalid spatial gas manifest",failure);}
	}

	byte[] encodeSpatialSection(List<SpatialEntry> entries) throws IOException {
		return encode(out -> {
			out.writeInt(SPATIAL_SECTION_MAGIC);out.writeInt(entries.size());int previous=-1;
			for(var entry:entries){
				if(entry.cell()<=previous||entry.inventory().isEmpty()||!Double.isFinite(entry.energy()))
					throw new IOException("Invalid spatial gas section row");
				previous=entry.cell();out.writeInt(entry.cell());writeInventory(out,entry.inventory());out.writeDouble(entry.energy());
			}
		});
	}

	List<SpatialEntry> decodeSpatialSection(byte[] payload,int cells) throws IOException {
		try(var in=input(payload)) {
			if(in.readInt()!=SPATIAL_SECTION_MAGIC)throw new IOException("Unknown spatial gas section");
			int count=count(in.readInt(),cells,"spatial cells"),previous=-1;var entries=new ArrayList<SpatialEntry>(count);
			for(int i=0;i<count;i++){
				int cell=in.readInt();var inventory=readInventory(in);double energy=in.readDouble();
				if(cell<=previous||cell>=cells||inventory.isEmpty()||!Double.isFinite(energy))throw new IOException("Invalid spatial gas row");
				previous=cell;entries.add(new SpatialEntry(cell,inventory,energy));
			}
			requireEnd(in);return List.copyOf(entries);
		} catch(EOFException|IllegalArgumentException failure){throw new IOException("Invalid spatial gas section",failure);}
	}

	private static void writeInventory(DataOutputStream out, GasInventory inventory) throws IOException {
		out.writeInt(inventory.components().size());
		for (GasComponentAmount amount : inventory.components()) {
			out.writeUTF(amount.componentId()); out.writeLong(amount.standardVolumeNanos());
			out.writeLong(amount.massUnits()); out.writeLong(amount.massResidualUnits());
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
			long mass = input.readLong(), residual = input.readLong();
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
			out.writeLong(entry.massUnits()); out.writeLong(entry.massResidualUnits());
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
			long mass = input.readLong(), residual = input.readLong();
			int speciesCount = count(input.readInt(), GasLimits.MAX_SPECIES_PER_COMPONENT, "audit species");
			ArrayList<GasComponentAmount.SpeciesAmount> species = new ArrayList<>(speciesCount);
			for (int entry = 0; entry < speciesCount; entry++) {
				species.add(new GasComponentAmount.SpeciesAmount(input.readUTF(), input.readLong()));
			}
			entries.add(new GasAuditLedger.Entry(id, nanos, mass, species, residual));
		}
		return new GasAuditLedger(entries);
	}

	static void writeDyadic(DataOutputStream out, SignedDyadic value) throws IOException {
		byte[] significand = value.significand().toByteArray();
		out.writeInt(value.exponent()); out.writeInt(significand.length); out.write(significand);
	}

	static SignedDyadic readDyadic(DataInputStream input) throws IOException {
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
		try (DataOutputStream output = new DataOutputStream(new java.io.FilterOutputStream(bytes) {
			@Override public void write(int value) throws IOException {
				requireSpace(1); bytes.write(value);
			}
			@Override public void write(byte[] value, int offset, int length) throws IOException {
				java.util.Objects.checkFromIndexSize(offset, length, value.length);
				requireSpace(length); bytes.write(value, offset, length);
			}
			private void requireSpace(int length) throws IOException {
				if (length > GasLimits.MAX_SERIALIZED_EXTENSION_BYTES - bytes.size())
					throw new IOException("Gas extension is over budget");
			}
		})) { writer.write(output); }
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
