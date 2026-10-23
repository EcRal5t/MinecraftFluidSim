package mmm.transfer;

import java.util.UUID;

/** Persistent inventory incarnation and monotonic mutation generation. */
public record TransferActorState(UUID instanceId,long generation) {
	public TransferActorState {
		if(instanceId==null || generation<0 || generation==Long.MAX_VALUE)
			throw new IllegalArgumentException("Invalid persistent transfer actor state");
	}
	public String encode() { return instanceId+":"+generation; }
	public static TransferActorState decode(String encoded) {
		if(encoded==null) throw new IllegalArgumentException("Missing persistent transfer actor state");
		int split=encoded.lastIndexOf(':');
		return new TransferActorState(UUID.fromString(encoded.substring(0,split)),Long.parseLong(encoded.substring(split+1)));
	}
}
