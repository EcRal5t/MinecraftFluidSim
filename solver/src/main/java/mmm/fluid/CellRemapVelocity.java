package mmm.fluid;

import java.util.List;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;

/** Cell-scale linear velocity transport; unresolved directions carry their mean velocity. */
final class CellRemapVelocity {
	private CellRemapVelocity() { }

	static double[][] gradient(FluidDomain domain, List<Integer> particles, double mass, double[] moment, double[] momentum) {
		var covariance = new DMatrixRMaj(3, 3);
		var cross = new double[3][3];
		double h = domain.config.cellSize();
		int cell = domain.particleCellIndex(particles.getFirst());
		int[] coordinate = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
		for (int p : particles) {
			double weight = domain.particles.mass(p) / mass;
			double[] x = {domain.particles.positionX(p), domain.particles.positionY(p), domain.particles.positionZ(p)};
			double[] v = {domain.particles.velocityX(p), domain.particles.velocityY(p), domain.particles.velocityZ(p)};
			for (int a = 0; a < 3; a++) { x[a] = x[a] / h - coordinate[a] - .5 - moment[a] / mass; v[a] -= momentum[a] / mass; }
			for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
				covariance.add(a, b, weight * x[a] * x[b]); cross[a][b] += weight * v[a] * x[b];
			}
		}
		var eigen = DecompositionFactory_DDRM.eig(3, true, true);
		if (!eigen.decompose(covariance)) throw new IllegalStateException("Liquid velocity covariance decomposition failed");
		var gradient = new double[3][3];
		// A direction narrower than one percent of a cell is below the transport resolution.
		for (int k = 0; k < 3; k++) {
			double variance = eigen.getEigenvalue(k).getReal();
			if (variance < 1e-4) continue;
			var direction = eigen.getEigenVector(k);
			for (int a = 0; a < 3; a++) {
				double slope = 0;
				for (int b = 0; b < 3; b++) slope += cross[a][b] * direction.get(b);
				for (int b = 0; b < 3; b++) gradient[a][b] += slope / variance * direction.get(b);
			}
		}
		return gradient;
	}

	/** Returns reconstructed velocities; their weighted mean is the transported momentum. */
	static double[][] sample(double[] masses, double[][] positions, double mass, double[] momentum,
	                         double[][] gradient, double kineticTwice) {
		var center = new double[3];
		for (int p = 0; p < masses.length; p++) for (int a = 0; a < 3; a++) center[a] += masses[p] / mass * positions[p][a];
		var result = new double[masses.length][3];
		double variation = 0, translation = 0;
		for (int a = 0; a < 3; a++) translation += momentum[a] * (momentum[a] / mass);
		for (int p = 0; p < masses.length; p++) for (int a = 0; a < 3; a++) {
			for (int b = 0; b < 3; b++) result[p][a] += gradient[a][b] / mass * (positions[p][b] - center[b]);
			variation += masses[p] * result[p][a] * result[p][a];
		}
		double scale = variation > 0 ? Math.min(1, Math.sqrt(Math.max(0, kineticTwice - translation) / variation)) : 1;
		for (int p = 0; p < masses.length; p++) for (int a = 0; a < 3; a++) result[p][a] = momentum[a] / mass + scale * result[p][a];
		return result;
	}
}
