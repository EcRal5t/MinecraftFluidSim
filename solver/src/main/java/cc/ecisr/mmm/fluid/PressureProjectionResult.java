package cc.ecisr.mmm.fluid;

/**
 * Outcome of one PCG pressure solve: iterations actually taken, the final absolute RMS
 * residual and whether it met the tolerance. The stage turns a non-converged result into
 * the typed blocking exception; nothing here mutates solver state.
 */
record PressureProjectionResult(int iterations, double residualRms, boolean converged) { }
