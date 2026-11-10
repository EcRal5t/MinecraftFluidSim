package mmm.pipe;

import java.util.Objects;
import mmm.units.SignedDyadic;

/** Dimension-wide conservation accounts shared by every pipe network.
 * Supplied and removed name the transfer direction; reference energy may be signed. External work and environment heat
 * are signed into the network. Roundoff is published energy minus its exact candidate. */
public record PipeAudit(SignedDyadic initialEnergy, SignedDyadic suppliedEnergy, SignedDyadic removedEnergy,
                        SignedDyadic externalWork, SignedDyadic environmentHeat, SignedDyadic roundoff) {
    public static final PipeAudit EMPTY = new PipeAudit(SignedDyadic.ZERO,SignedDyadic.ZERO,SignedDyadic.ZERO,
            SignedDyadic.ZERO,SignedDyadic.ZERO,SignedDyadic.ZERO);
    public PipeAudit {
        Objects.requireNonNull(initialEnergy); Objects.requireNonNull(suppliedEnergy); Objects.requireNonNull(removedEnergy);
        Objects.requireNonNull(externalWork); Objects.requireNonNull(environmentHeat); Objects.requireNonNull(roundoff);
    }
}
