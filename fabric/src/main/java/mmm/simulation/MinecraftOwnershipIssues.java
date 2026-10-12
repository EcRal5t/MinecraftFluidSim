package mmm.simulation;

import java.util.List;

/** Mutable runtime view of boot and recovery ownership failures. */
final class MinecraftOwnershipIssues {
	private List<String> issues;

	MinecraftOwnershipIssues(List<String> issues) {
		this.issues = List.copyOf(issues);
	}

	List<String> current() { return issues; }
	void replace(List<String> replacement) { issues = List.copyOf(replacement); }
}
