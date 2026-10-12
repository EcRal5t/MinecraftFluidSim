package mmm.worldprofile;

/** Registry kinds a world profile can bind; the token is the canonical aggregate encoding key. */
public enum RegistryKind {
	CHEMICAL("chemical"),
	GAS("gas"),
	GRANULAR("granular"),
	MATERIAL("material"),
	THERMAL("thermal");

	private final String token;

	RegistryKind(String token) { this.token = token; }

	public String token() { return token; }
}
