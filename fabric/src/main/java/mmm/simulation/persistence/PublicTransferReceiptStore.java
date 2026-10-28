package mmm.simulation.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import mmm.api.transfer.*;
import mmm.transfer.ContainerTransferActor;
import mmm.transfer.ContainerTransferJournal;
import mmm.transfer.ContainerTransferManifests;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Durable actor bindings and replay receipts; physical recovery remains in the container WAL. */
public final class PublicTransferReceiptStore {
	public record Binding(ActorRef actor,DispenserAddress address,UUID instanceId) { }
	public enum State { PENDING, COMMITTED, NOT_COMMITTED }
	public record Entry(TransferRequest request,Binding binding,ContainerTransferJournal settlement,State state) {
		public TransferReceipt receipt() {
			if(state==State.PENDING) return null;
			boolean committed=state==State.COMMITTED;
			return new TransferReceipt(request.operationId(),binding.actor(),binding.instanceId(),
					request.expectedActorGeneration()+(committed?1:0),request.regionId(),
					committed?settlement.targetGeneration():settlement.baseGeneration(),committed,
					committed?ContainerTransferManifests.from(settlement):null);
		}
	}
	private final Path directory;
	private final Map<ActorRef,Binding> bindings=new LinkedHashMap<>();
	private final Map<UUID,Entry> entries=new LinkedHashMap<>();
	private IOException failure;
	public PublicTransferReceiptStore(Path root) {
		directory=root.resolve("mmm").resolve("public-transfer");
		try {
			if(!Files.exists(directory)) return;
			try(var paths=Files.list(directory)) {
				for(var path:paths.sorted().toList()) {
					String name=path.getFileName().toString();
					if (name.endsWith(".tmp")) continue;
					var data=read(path);
					if(name.endsWith(".actor.nbt")) {
						var binding=decodeBinding(data);
						if(!path.equals(bindingPath(binding.actor())) || bindings.putIfAbsent(binding.actor(),binding)!=null)
							throw new IOException("Public transfer actor binding identity differs from file");
					} else if(name.endsWith(".operation.nbt") || name.endsWith(".receipt.nbt")) {
						var entry=decodeEntry(data);
						boolean receipt=name.endsWith(".receipt.nbt");
						if(!path.equals(receipt?receiptPath(entry.binding().actor()):operationPath(entry.request().operationId()))
								|| receipt && entry.state()==State.PENDING || !receipt && entry.state()!=State.PENDING)
							throw new IOException("Public transfer operation identity differs from file");
						var old=entries.get(entry.request().operationId());
						if(old==null || receipt) entries.put(entry.request().operationId(),entry);
					} else throw new IOException("Unrecognized public transfer evidence: "+path);
				}
			}
		} catch(IOException | RuntimeException invalid) {
			failure=new IOException("Public transfer receipt store is unavailable; evidence preserved",invalid);
		}
	}
	public void requireReady() throws IOException { if(failure!=null) throw failure; }
	public Binding binding(ActorRef actor) throws IOException { requireReady(); return bindings.get(actor); }
	public Entry entry(UUID operation) throws IOException { requireReady(); return entries.get(operation); }
	public List<Entry> entries() throws IOException { requireReady(); return List.copyOf(entries.values()); }
	public void bind(Binding binding) throws IOException {
		requireReady();
		var old=bindings.get(binding.actor());
		if(old!=null) {
			if(!old.equals(binding)) throw new IOException("Persistent actor ref cannot be rebound");
			return;
		}
		if(bindings.size()>=1024) throw new IOException("Public transfer actor registration budget is full");
		if(bindings.values().stream().anyMatch(value -> value.instanceId().equals(binding.instanceId())))
			throw new IOException("Persistent inventory is already bound to another actor ref");
		if(entries.values().stream().anyMatch(value -> value.state()==State.PENDING && value.binding().address().equals(binding.address())))
			throw new IOException("Dispenser slot has a pending public operation");
		publish(bindingPath(binding.actor()),encodeBinding(binding)); bindings.put(binding.actor(),binding);
	}
	public void prepare(TransferRequest request,Binding binding,ContainerTransferJournal preview) throws IOException {
		requireReady();
		if(entries.containsKey(request.operationId())) throw new IOException("Operation identity is already durable");
		if(entries.values().stream().anyMatch(value -> value.state()==State.PENDING && value.binding().actor().equals(binding.actor())))
			throw new IOException("Actor already has a pending public operation");
		var entry=new Entry(request,binding,preview,State.PENDING);
		entries.put(request.operationId(),entry);
		publish(operationPath(request.operationId()),encodeEntry(entry));
	}
	public void validate(ContainerTransferJournal journal) throws IOException {
		if(!(journal.actor() instanceof ContainerTransferActor.Dispenser dispenser) || dispenser.inventoryId()==null) return;
		requireReady();
		var entry=entries.get(journal.transactionId());
		if(entry==null) return; // A registered container may also be used by the built-in dispenser adapter.
		var expected=entry.settlement();
		if(!expected.actor().equals(journal.actor()) || !expected.regionId().equals(journal.regionId())
				|| !expected.dimensionId().equals(journal.dimensionId()) || expected.operation()!=journal.operation()
				|| expected.baseGeneration()!=journal.baseGeneration() || expected.targetGeneration()!=journal.targetGeneration()
				|| expected.resourceKind()!=journal.resourceKind() || !expected.beforeItem().equals(journal.beforeItem())
				|| !expected.sourceItem().equals(journal.sourceItem()))
			throw new IOException("Public operation WAL differs from its durable actor/request binding");
		if(journal.phase()==ContainerTransferJournal.Phase.REGION_COMMITTED
				&& (!expected.afterItem().equals(journal.afterItem()) || expected.volumeNanos()!=journal.volumeNanos()
				|| expected.solidVolumeSvq()!=journal.solidVolumeSvq() || !java.util.Objects.equals(expected.thermalTransfer(),journal.thermalTransfer())))
			throw new IOException("Public operation actual settlement differs from the committed plan");
	}
	public void complete(ContainerTransferJournal journal,boolean committed) throws IOException {
		validate(journal);
		var entry=entries.get(journal.transactionId());
		if(entry==null) return;
		State state=committed?State.COMMITTED:State.NOT_COMMITTED;
		if(entry.state()!=State.PENDING) {
			if(entry.state()!=state) throw new IOException("Public operation receipt outcome changed");
			deletePending(journal.transactionId());
			return;
		}
		var after=new Entry(entry.request(),entry.binding(),committed?journal:entry.settlement(),state);
		publishReceipt(after);
	}
	public void abortWithoutJournal(UUID operation) throws IOException {
		var entry=entry(operation);
		if(entry==null || entry.state()!=State.PENDING) return;
		var after=new Entry(entry.request(),entry.binding(),entry.settlement(),State.NOT_COMMITTED);
		publishReceipt(after);
	}
	private void publishReceipt(Entry after) throws IOException {
		publish(receiptPath(after.binding().actor()),encodeEntry(after));
		entries.values().removeIf(value -> value.state()!=State.PENDING && value.binding().actor().equals(after.binding().actor()));
		entries.put(after.request().operationId(),after);
		deletePending(after.request().operationId());
	}
	private void deletePending(UUID operation) throws IOException {
		Files.deleteIfExists(operationPath(operation));DurableFileOperations.forceDirectory(directory);
	}
	private Path receiptPath(ActorRef actor) { return bindingPath(actor).resolveSibling(bindingPath(actor).getFileName().toString().replace(".actor.nbt",".receipt.nbt")); }
	private Path bindingPath(ActorRef actor) {
		try {
			byte[] bytes=MessageDigest.getInstance("SHA-256").digest((actor.providerId()+"\n"+actor.objectId()).getBytes(StandardCharsets.UTF_8));
			return directory.resolve(java.util.HexFormat.of().formatHex(bytes)+".actor.nbt");
		} catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}
	private Path operationPath(UUID operation) { return directory.resolve(operation+".operation.nbt"); }
	private CompoundTag read(Path path) throws IOException {
		if(!Files.isRegularFile(path)) throw new IOException("Public transfer evidence is not a file: "+path);
		return NbtIo.readCompressed(path,NbtAccounter.create(8L<<20));
	}
	private void publish(Path target,CompoundTag data) throws IOException {
		boolean created=!Files.exists(directory); Files.createDirectories(directory);
		if(created) DurableFileOperations.forceParent(directory);
		var temporary=target.resolveSibling(target.getFileName()+".tmp");
		NbtIo.writeCompressed(data,temporary); DurableFileOperations.forceFile(temporary);
		if(!read(temporary).equals(data)) throw new IOException("Public transfer publication readback differs");
		DurableFileOperations.atomicMoveReplacing(temporary,target); DurableFileOperations.forceDirectory(directory);
		if(!read(target).equals(data)) throw new IOException("Public transfer durable publication readback differs");
	}
	private static CompoundTag encodeBinding(Binding binding) {
		var data=new CompoundTag(); var address=binding.address();
		data.putString("Provider",binding.actor().providerId()); data.putString("Object",binding.actor().objectId());
		data.putString("Instance",binding.instanceId().toString()); data.putString("Dimension",address.dimensionId());
		data.putInt("X",address.blockX());data.putInt("Y",address.blockY());data.putInt("Z",address.blockZ());data.putInt("Slot",address.slot());
		return data;
	}
	private static Binding decodeBinding(CompoundTag data) {
		return new Binding(new ActorRef(data.getStringOr("Provider",""),data.getStringOr("Object","")),
				new DispenserAddress(data.getStringOr("Dimension",""),data.getIntOr("X",0),data.getIntOr("Y",0),data.getIntOr("Z",0),data.getIntOr("Slot",-1)),
				UUID.fromString(data.getStringOr("Instance","")));
	}
	private static CompoundTag encodeEntry(Entry entry) throws IOException {
		var data=new CompoundTag(); var request=entry.request();
		data.put("Binding",encodeBinding(entry.binding())); data.put("Settlement",ContainerJournalCodec.encode(entry.settlement()));
		data.putString("State",entry.state().name());data.putString("Operation",request.operationId().toString());
		data.putLong("ExpectedActorGeneration",request.expectedActorGeneration());data.putString("Region",request.regionId().toString());
		data.putString("Dimension",request.dimensionId());data.putDouble("X",request.worldXMetres());data.putDouble("Y",request.worldYMetres());data.putDouble("Z",request.worldZMetres());
		data.putString("Direction",request.direction().name());
		data.putBoolean("Granular",request.quantity() instanceof TransferQuantity.Granular);
		data.putLong("RequestedAmount",request.quantity() instanceof TransferQuantity.Granular granular?granular.solidVolumeSvq():((TransferQuantity.Liquid)request.quantity()).volumeNanos());
		return data;
	}
	private static Entry decodeEntry(CompoundTag data) throws IOException {
		var binding=decodeBinding(data.getCompoundOrEmpty("Binding"));long amount=data.getLongOr("RequestedAmount",0);
		TransferQuantity quantity=data.getBooleanOr("Granular",false)?new TransferQuantity.Granular(amount):new TransferQuantity.Liquid(amount,java.util.OptionalDouble.empty());
		var request=new TransferRequest(UUID.fromString(data.getStringOr("Operation","")),binding.actor(),data.getLongOr("ExpectedActorGeneration",-1),
				UUID.fromString(data.getStringOr("Region","")),data.getStringOr("Dimension",""),data.getDoubleOr("X",Double.NaN),data.getDoubleOr("Y",Double.NaN),data.getDoubleOr("Z",Double.NaN),
				TransferRequest.Direction.valueOf(data.getStringOr("Direction","")),quantity);
		return new Entry(request,binding,ContainerJournalCodec.decode(data.getCompoundOrEmpty("Settlement")),State.valueOf(data.getStringOr("State","")));
	}
}
