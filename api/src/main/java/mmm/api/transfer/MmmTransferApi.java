package mmm.api.transfer;

import java.util.UUID;

/** Main-server-thread transfers through durable actors and the shared container journal. */
public interface MmmTransferApi {
	/** Register once per server session for new requests; existing WALs retain their concrete durable address. */
	void registerActorProvider(String providerId, ActorProvider provider);

	/** Persist the ref-to-instance binding and its initial generation before planning transfers. */
	TransferResult registerActor(ActorRef actor);

	/** Preview actual inventory. A plan is session-local and expires at its stated server tick. */
	TransferResult plan(TransferRequest request);

	/** Revalidate actor identity, generation, inventory and region before executing the plan. */
	TransferResult commit(UUID planId);

	/** Read a durable operation receipt; a receipt may predate the current actor generation. */
	TransferResult receipt(UUID operationId);

	@FunctionalInterface
	interface ActorProvider {
		/** Return null when unavailable. Resolution must not load chunks or mutate inventories. */
		BlockInventoryAddress resolve(String objectId);
	}
}
