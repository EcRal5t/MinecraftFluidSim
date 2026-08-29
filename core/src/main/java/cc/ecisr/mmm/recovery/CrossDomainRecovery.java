package cc.ecisr.mmm.recovery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JDK-only recovery algebra for transactions whose sole physical commit point is a region HEAD
 * and whose remaining members live in independently durable external domains.
 */
public final class CrossDomainRecovery {
	public enum JournalPhase { PREPARED, REGION_COMMITTED }
	public enum ExternalTruth { BEFORE, AFTER, THIRD, UNAVAILABLE }
	public enum Outcome { DISCARD_UNCOMMITTED, CONVERGE_AFTER, WAIT_UNAVAILABLE, LOCKED }
	public enum Reason {
		NONE,
		HEAD_MISMATCH,
		TARGET_CHECKSUM_MISSING,
		TARGET_CHECKSUM_MISMATCH,
		TARGET_WITHOUT_COMMITTED_JOURNAL,
		BASE_CANDIDATE_DIRTY,
		BASE_EXTERNAL_CHANGED,
		TARGET_EXTERNAL_THIRD,
		EXTERNAL_UNAVAILABLE
	}

	public record Boundary(
			long headGeneration,
			long headManifestChecksum,
			long baseGeneration,
			long targetGeneration,
			boolean targetChecksumPresent,
			long targetManifestChecksum,
			JournalPhase phase,
			boolean baseCandidateDirty
	) {
		public Boundary {
			if (headGeneration < 0L || baseGeneration < 0L || baseGeneration == Long.MAX_VALUE
					|| targetGeneration != baseGeneration + 1L || phase == null) {
				throw new IllegalArgumentException("Cross-domain recovery boundary is invalid");
			}
		}
	}

	public record Decision(
			Outcome outcome,
			Reason reason,
			List<Integer> beforeMembers,
			List<Integer> afterMembers
	) {
		public Decision {
			if (outcome == null || reason == null || beforeMembers == null || afterMembers == null) {
				throw new IllegalArgumentException("Cross-domain recovery decision is incomplete");
			}
			boolean reasonValid = switch (outcome) {
				case LOCKED -> reason != Reason.NONE && reason != Reason.EXTERNAL_UNAVAILABLE;
				case WAIT_UNAVAILABLE -> reason == Reason.EXTERNAL_UNAVAILABLE;
				case DISCARD_UNCOMMITTED, CONVERGE_AFTER -> reason == Reason.NONE;
			};
			if (!reasonValid) throw new IllegalArgumentException("Cross-domain recovery reason is invalid");
			beforeMembers = List.copyOf(beforeMembers);
			afterMembers = List.copyOf(afterMembers);
			if (outcome != Outcome.CONVERGE_AFTER) {
				if (!beforeMembers.isEmpty() || !afterMembers.isEmpty()) {
					throw new IllegalArgumentException("Only convergence decisions may expose external members");
				}
			} else {
				validateMemberIndices(beforeMembers, afterMembers);
			}
		}

		private static void validateMemberIndices(List<Integer> before, List<Integer> after) {
			if (before.isEmpty() && after.isEmpty()) {
				throw new IllegalArgumentException("Convergence decision has no external members");
			}
			Set<Integer> seen = new HashSet<>();
			for (List<Integer> indices : List.of(before, after)) {
				int previous = -1;
				for (int index : indices) {
					if (index < 0 || index <= previous || !seen.add(index)) {
						throw new IllegalArgumentException("Cross-domain member indices are not canonical");
					}
					previous = index;
				}
			}
			for (int index = 0; index < seen.size(); index++) {
				if (!seen.contains(index)) {
					throw new IllegalArgumentException("Cross-domain member indices are not contiguous");
				}
			}
		}
	}

	private CrossDomainRecovery() { }

	public static Decision decide(Boundary boundary, List<ExternalTruth> externalTruths) {
		if (boundary == null || externalTruths == null || externalTruths.isEmpty()
				|| externalTruths.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Cross-domain recovery input is incomplete");
		}
		List<ExternalTruth> truths = List.copyOf(externalTruths);
		boolean base = boundary.headGeneration() == boundary.baseGeneration();
		boolean target = boundary.headGeneration() == boundary.targetGeneration();
		if (!base && !target) return locked(Reason.HEAD_MISMATCH);
		if (target && boundary.phase() != JournalPhase.REGION_COMMITTED) {
			return locked(Reason.TARGET_WITHOUT_COMMITTED_JOURNAL);
		}
		if (target && !boundary.targetChecksumPresent()) return locked(Reason.TARGET_CHECKSUM_MISSING);
		if (target && boundary.headManifestChecksum() != boundary.targetManifestChecksum()) {
			return locked(Reason.TARGET_CHECKSUM_MISMATCH);
		}
		if (base && boundary.baseCandidateDirty()) return locked(Reason.BASE_CANDIDATE_DIRTY);

		if (base) {
			if (truths.stream().anyMatch(value -> value == ExternalTruth.AFTER
					|| value == ExternalTruth.THIRD)) return locked(Reason.BASE_EXTERNAL_CHANGED);
			if (truths.contains(ExternalTruth.UNAVAILABLE)) return waiting();
			return new Decision(Outcome.DISCARD_UNCOMMITTED, Reason.NONE, List.of(), List.of());
		}

		if (truths.contains(ExternalTruth.THIRD)) return locked(Reason.TARGET_EXTERNAL_THIRD);
		if (truths.contains(ExternalTruth.UNAVAILABLE)) return waiting();
		ArrayList<Integer> before = new ArrayList<>();
		ArrayList<Integer> after = new ArrayList<>();
		for (int index = 0; index < truths.size(); index++) {
			if (truths.get(index) == ExternalTruth.BEFORE) before.add(index);
			else after.add(index);
		}
		return new Decision(Outcome.CONVERGE_AFTER, Reason.NONE, before, after);
	}

	private static Decision waiting() {
		return new Decision(Outcome.WAIT_UNAVAILABLE, Reason.EXTERNAL_UNAVAILABLE, List.of(), List.of());
	}

	private static Decision locked(Reason reason) {
		return new Decision(Outcome.LOCKED, reason, List.of(), List.of());
	}
}
