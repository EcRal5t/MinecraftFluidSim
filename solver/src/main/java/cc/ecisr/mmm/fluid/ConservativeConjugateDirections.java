package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Preconditioned conjugate directions with explicit energy orthogonalization in binary64 arithmetic. */
final class ConservativeConjugateDirections {
	private record Direction(double[] value, double[] action, double energy) { }
	private final double[] scale;
	private final List<Direction> previous = new ArrayList<>();
	ConservativeConjugateDirections(double[] diagonal) {
		scale = new double[diagonal.length];
		for (int i = 0; i < scale.length; i++) {
			if (!(diagonal[i] > 0) || !Double.isFinite(diagonal[i])) throw new IllegalArgumentException("Conjugate directions require positive finite preconditioning");
			scale[i] = Math.sqrt(diagonal[i]);
		}
	}
	double[] direction(double[] residual) { return direction(residual, new double[residual.length]); }
	double[] direction(double[] residual, double[] error) {
		if (residual.length != scale.length || error.length != scale.length) throw new IllegalArgumentException("Incomplete conjugate residual");
		// A full basis exhausts this correction space. Rebuild it from the freshly evaluated residual.
		if (previous.size() == scale.length) previous.clear();
		var result = new double[scale.length];
		for (int i = 0; i < result.length; i++) {
			if (!(error[i] >= 0) || !Double.isFinite(error[i])) throw new IllegalArgumentException("Invalid conjugate residual certificate");
			// The unresolved part of the certified gradient drives the search. Its full value still determines the line minimizer.
			result[i] = Math.copySign(Math.max(0, Math.abs(residual[i]) - error[i]), residual[i]) / scale[i];
		}
		normalize(result);
		var unprojected = result.clone();
		// Two modified Gram-Schmidt passes restore conjugacy lost by the three-term recurrence.
		for (int pass = 0; pass < 2; pass++) for (var direction : previous) {
			var coefficient = dot(result, direction.action) / direction.energy;
			for (int i = 0; i < result.length; i++) result[i] = Math.fma(-coefficient, direction.value[i], result[i]);
		}
		// Retain every represented correction; the actual operator certifies stationarity after the update.
		boolean independent = false;
		for (double component : result) independent |= component != 0;
		if (!independent) { previous.clear(); result = unprojected; }
		normalize(result);
		for (int i = 0; i < result.length; i++) result[i] /= scale[i]; return result;
	}
	private static void normalize(double[] value) {
		double largest = 0; for (double component : value) largest = Math.max(largest, Math.abs(component));
		if (!(largest > 0) || !Double.isFinite(largest)) throw new IllegalStateException("Conjugate residual exceeds numerical range");
		for (int i = 0; i < value.length; i++) value[i] /= largest;
	}
	void remember(double[] value, double[] action) {
		if (value.length != scale.length || action.length != scale.length) throw new IllegalArgumentException("Incomplete conjugate action");
		var scaled = new double[scale.length]; var scaledAction = scaled.clone();
		for (int i = 0; i < scaled.length; i++) { scaled[i] = value[i] * scale[i]; scaledAction[i] = action[i] / scale[i]; }
		double energy = dot(scaled, scaledAction);
		if (!(energy > 0) || !Double.isFinite(energy)) throw new IllegalStateException("Conjugate direction has unresolved positive inertia");
		double norm = Math.sqrt(energy);
		for (int i = 0; i < scaled.length; i++) { scaled[i] /= norm; scaledAction[i] /= norm; }
		previous.add(new Direction(scaled, scaledAction, dot(scaled, scaledAction)));
	}
	/** Compensated summation retains small orthogonal components beside already resolved large modes. */
	static double dot(double[] first, double[] second) {
		double value = 0, correction = 0;
		for (int i = 0; i < first.length; i++) {
			double term = first[i] * second[i], next = value + term;
			correction += Math.fma(first[i], second[i], -term);
			correction += Math.abs(value) >= Math.abs(term) ? (value - next) + term : (term - next) + value; value = next;
		}
		return value + correction;
	}
}
