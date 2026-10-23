package mmm.api.transfer;

import mmm.api.StableId;

/** Address of one durable, already loaded Minecraft dispenser slot. */
public record DispenserAddress(String dimensionId, int blockX, int blockY, int blockZ, int slot) {
	public DispenserAddress {
		StableId.require(dimensionId, 128, "dimension id");
		if (slot < 0 || slot >= 9) throw new IllegalArgumentException("Invalid dispenser slot");
	}
	public ActorRef actorRef() { return new ActorRef("mmm:dispenser",dimensionId+"|"+blockX+"|"+blockY+"|"+blockZ+"|"+slot); }
	/** A new physical container at a previously registered slot uses a new stable registration id. */
	public ActorRef actorRef(String registrationId) {
		if(registrationId==null || !registrationId.matches("[A-Za-z0-9_-]{1,64}"))
			throw new IllegalArgumentException("Invalid dispenser registration id");
		return new ActorRef("mmm:dispenser",actorRef().objectId()+"|"+registrationId);
	}
	public static DispenserAddress fromObjectId(String objectId) {
		String[] fields=objectId.split("\\|",-1);
		if(fields.length!=5 && fields.length!=6) throw new IllegalArgumentException("Invalid persistent dispenser address");
		if(fields.length==6 && !fields[5].matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid dispenser registration id");
		return new DispenserAddress(fields[0],Integer.parseInt(fields[1]),Integer.parseInt(fields[2]),Integer.parseInt(fields[3]),Integer.parseInt(fields[4]));
	}
}
