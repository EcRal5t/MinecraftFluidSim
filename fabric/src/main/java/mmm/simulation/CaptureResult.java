package mmm.simulation;

/** Outcome of capturing nearby vanilla water into a transient debug region. */
public record CaptureResult(int wetCells, int removedVanillaBlocks, String failure) { }
