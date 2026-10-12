package mmm.fluid;

/** Rejects a gas-topology candidate whose finite inventory/capacity ownership cannot close. */
public final class GasTopologyBlockedException extends IllegalStateException {
	GasTopologyBlockedException(String message) { super(message); }
}
