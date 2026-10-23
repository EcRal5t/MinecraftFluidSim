package mmm.simulation.persistence;

import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.recovery.CrossDomainRecovery;
import net.minecraft.nbt.*;

/** One region HEAD and a finite batch of vanilla soil moisture slots. */
public final class FarmlandJournalStore {
	public static final long NANOS_PER_LEVEL = 1_000_000;
	public record Change(long position, int before, int after, long actualNanos,
			List<ChemicalTransferLedger.Group> water, boolean thermal, double internalEnergy) {
		public Change {
			water = List.copyOf(water);
			if (before < 0 || after > 7 || after <= before || actualNanos != (after-before)*NANOS_PER_LEVEL
					|| water.isEmpty() || !Double.isFinite(internalEnergy)
					|| water.stream().anyMatch(group -> !group.materialId().equals("mmm:water"))
					|| water.stream().mapToLong(ChemicalTransferLedger.Group::massUnits).sum() <= 0)
				throw new IllegalArgumentException("Invalid farmland water transfer");
		}
	}
	public record Journal(UUID operationId, UUID regionId, String dimensionId, String profile,
			long baseGeneration, long targetGeneration, long targetChecksum,
			CrossDomainRecovery.JournalPhase phase, List<Change> changes) {
		public Journal {
			changes = List.copyOf(changes);
			if (operationId == null || regionId == null || dimensionId == null || dimensionId.isEmpty()
					|| profile == null || !profile.matches("[0-9a-f]{64}") || baseGeneration < 0
					|| targetGeneration != Math.addExact(baseGeneration,1) || phase == null
					|| changes.isEmpty() || changes.size() > 16
					|| changes.stream().map(Change::position).distinct().count() != changes.size()
					|| phase == CrossDomainRecovery.JournalPhase.REGION_COMMITTED && targetChecksum < 0)
				throw new IllegalArgumentException("Invalid farmland journal");
		}
		public Journal committed(long checksum) { return new Journal(operationId,regionId,dimensionId,profile,
				baseGeneration,targetGeneration,checksum,CrossDomainRecovery.JournalPhase.REGION_COMMITTED,changes); }
		public boolean protects(String dimension,long position) {
			return dimensionId.equals(dimension) && changes.stream().anyMatch(change->change.position()==position);
		}
	}
	private final Path directory;
	public FarmlandJournalStore(Path root) { directory=root.resolve("mmm/farmland-transfer"); }
	public List<Journal> discover() throws IOException {
		if (!Files.exists(directory)) return List.of();
		var result=new ArrayList<Journal>();
		try(var files=Files.list(directory)) {
			for(var file:files.sorted().toList()) {
				String name=file.getFileName().toString();
				if(name.endsWith(".nbt.tmp") && Files.isRegularFile(file)) continue;
				if(!name.endsWith(".nbt") || !Files.isRegularFile(file)) throw new IOException("Unknown farmland WAL evidence: "+file);
				var journal=read(file);
				if(!name.equals(journal.operationId()+".nbt")) throw new IOException("Farmland WAL filename identity differs");
				result.add(journal);
			}
		}
		return List.copyOf(result);
	}
	public void write(Journal journal) throws IOException {
		boolean created=!Files.exists(directory); Files.createDirectories(directory);
		if(created) DurableFileOperations.forceParent(directory);
		Path target=directory.resolve(journal.operationId()+".nbt"), temporary=directory.resolve(journal.operationId()+".nbt.tmp");
		Journal previous=Files.exists(target)?read(target):null;
		if(previous!=null && !previous.equals(journal) && !(previous.phase()==CrossDomainRecovery.JournalPhase.PREPARED
				&& journal.phase()==CrossDomainRecovery.JournalPhase.REGION_COMMITTED
				&& previous.committed(journal.targetChecksum()).equals(journal)))
			throw new IOException("Farmland WAL replacement changed its operation or members");
		NbtIo.writeCompressed(encode(journal),temporary);
		try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) { channel.force(true); }
		if(!read(temporary).equals(journal)) throw new IOException("Farmland WAL temporary readback differs");
		if(previous!=null && !read(target).equals(previous)) throw new IOException("Farmland WAL changed before publication");
		DurableFileOperations.atomicMoveReplacing(temporary,target); DurableFileOperations.forceDirectory(directory);
		if(!read(target).equals(journal)) throw new IOException("Farmland WAL published readback differs");
	}
	public void delete(UUID id) throws IOException {
		Files.deleteIfExists(directory.resolve(id+".nbt")); DurableFileOperations.forceDirectory(directory);
	}
	private CompoundTag encode(Journal journal) {
		var tag=new CompoundTag();
		tag.putString("Operation",journal.operationId().toString()); tag.putString("Region",journal.regionId().toString());
		tag.putString("Dimension",journal.dimensionId()); tag.putString("Profile",journal.profile());
		tag.putLong("Base",journal.baseGeneration()); tag.putLong("Target",journal.targetGeneration());
		tag.putLong("Checksum",journal.targetChecksum()); tag.putString("Phase",journal.phase().name());
		var members=new ListTag();
		for(var change:journal.changes()) {
			var member=new CompoundTag(); member.putLong("Position",change.position());
			member.putInt("Before",change.before()); member.putInt("After",change.after()); member.putLong("Nanos",change.actualNanos());
			member.putBoolean("Thermal",change.thermal()); member.putLong("EnergyBits",Double.doubleToRawLongBits(change.internalEnergy()));
			var groups=new ListTag();
			for(var group:change.water()) {
				var value=new CompoundTag(); value.putString("Material",group.materialId());
				value.putDouble("Volume",group.normalizedVolume()); value.putLong("Mass",group.massUnits()); value.putLong("Residual",group.massResidualUnits());
				var species=new CompoundTag(); group.speciesQuanta().forEach(species::putLong); value.put("Species",species); groups.add(value);
			}
			member.put("Water",groups); members.add(member);
		}
		tag.put("Changes",members); return tag;
	}
	private Journal read(Path path) throws IOException {
		try {
			var tag=NbtIo.readCompressed(path,NbtAccounter.create(1<<20)); var changes=new ArrayList<Change>();
			for(var entry:tag.getListOrEmpty("Changes")) {
				var member=(CompoundTag)entry; var groups=new ArrayList<ChemicalTransferLedger.Group>();
				for(var item:member.getListOrEmpty("Water")) {
					var group=(CompoundTag)item; var species=new TreeMap<String,Long>(); var data=group.getCompoundOrEmpty("Species");
					for(String id:data.keySet()) species.put(id,data.getLongOr(id,0));
					groups.add(new ChemicalTransferLedger.Group(group.getStringOr("Material",""),group.getDoubleOr("Volume",-1),
							group.getLongOr("Mass",-1),group.getLongOr("Residual",0),species));
				}
				changes.add(new Change(member.getLongOr("Position",0),member.getIntOr("Before",-1),member.getIntOr("After",-1),
						member.getLongOr("Nanos",0),groups,member.getBooleanOr("Thermal",false),Double.longBitsToDouble(member.getLongOr("EnergyBits",0))));
			}
			return new Journal(UUID.fromString(tag.getStringOr("Operation","")),UUID.fromString(tag.getStringOr("Region","")),
					tag.getStringOr("Dimension",""),tag.getStringOr("Profile",""),tag.getLongOr("Base",-1),tag.getLongOr("Target",-1),
					tag.getLongOr("Checksum",-1),CrossDomainRecovery.JournalPhase.valueOf(tag.getStringOr("Phase","")),changes);
		} catch(RuntimeException invalid) { throw new IOException("Invalid farmland WAL: "+path,invalid); }
	}
}
