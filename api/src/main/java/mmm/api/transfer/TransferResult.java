package mmm.api.transfer;

/** One plan, one durable receipt, or a status without transferred inventory. */
public record TransferResult(
		TransferStatus status, TransferPlan plan, TransferReceipt receipt,
		long actorGeneration, String detail
) {
	public TransferResult {
		if (status == null || actorGeneration < -1 || detail == null || detail.isBlank()
				|| plan != null && receipt != null
				|| plan != null && status != TransferStatus.OK
				|| status == TransferStatus.COMMITTED && (receipt == null || !receipt.committed())
				|| receipt != null && receipt.committed() != (status == TransferStatus.COMMITTED))
			throw new IllegalArgumentException("Invalid transfer result");
	}
}
