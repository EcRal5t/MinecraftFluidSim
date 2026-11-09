package mmm.api.transfer;

import mmm.api.StableId;

/** Address of one durable, already loaded Minecraft block inventory slot. */
public record BlockInventoryAddress(String dimensionId, int blockX, int blockY, int blockZ, int slot) {
	public BlockInventoryAddress {
		StableId.require(dimensionId, 128, "dimension id");
		if (slot < 0 || slot >= 256) throw new IllegalArgumentException("Invalid block inventory slot");
	}
	public ActorRef actorRef() { return new ActorRef("mmm:block_inventory",dimensionId+"|"+blockX+"|"+blockY+"|"+blockZ+"|"+slot); }
	/** A new physical container at a previously registered slot uses a new stable registration id. */
	public ActorRef actorRef(String registrationId) {
		if(registrationId==null || !registrationId.matches("[A-Za-z0-9_-]{1,64}"))
			throw new IllegalArgumentException("Invalid block inventory registration id");
		return new ActorRef("mmm:block_inventory",actorRef().objectId()+"|"+registrationId);
	}
	public static BlockInventoryAddress fromObjectId(String objectId) {
		String[] fields=objectId.split("\\|",-1);
		if(fields.length!=5 && fields.length!=6) throw new IllegalArgumentException("Invalid persistent block inventory address");
		if(fields.length==6 && !fields[5].matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid block inventory registration id");
		return new BlockInventoryAddress(fields[0],Integer.parseInt(fields[1]),Integer.parseInt(fields[2]),Integer.parseInt(fields[3]),Integer.parseInt(fields[4]));
	}
}
