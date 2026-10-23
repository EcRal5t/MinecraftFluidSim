package mmm.api.transfer;

/** Closed outcomes; diagnostic text is intended for people. */
public enum TransferStatus {
	OK, COMMITTED, NOT_COMMITTED, PENDING, INVALID_REQUEST, WRONG_THREAD, UNAVAILABLE,
	NO_REGION, STALE_PLAN, STALE_ACTOR, CONFLICT, NOT_TRANSFERRED, RECEIPT_UNAVAILABLE
}
