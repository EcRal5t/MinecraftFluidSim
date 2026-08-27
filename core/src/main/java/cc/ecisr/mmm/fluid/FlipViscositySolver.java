package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import java.util.List;

/** Conservative explicit Newtonian momentum diffusion on staggered face control volumes. */
final class FlipViscositySolver {
	record Field(
			int sizeX, int sizeY, int sizeZ, float[] velocity,
			double[] massKilograms, double[] dynamicViscosity, float[] openAreaFraction
	) {
		Field {
			int count = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
			if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || velocity == null || massKilograms == null
					|| dynamicViscosity == null || openAreaFraction == null || velocity.length != count
					|| massKilograms.length != count || dynamicViscosity.length != count
					|| openAreaFraction.length != count) {
				throw new IllegalArgumentException("Viscosity face field shape is invalid");
			}
		}
	}

	record Plan(List<Field> fields, int substeps, int coupledPairs, double maximumRatePerSecond) {
		Plan {
			fields = List.copyOf(fields);
			if (substeps <= 0 || coupledPairs < 0
					|| !(Double.isFinite(maximumRatePerSecond) && maximumRatePerSecond >= 0.0)) {
				throw new IllegalArgumentException("Viscosity plan statistics are invalid");
			}
		}

		void apply(double timeStepSeconds, double cellSizeMeters) {
			Field[] working = new Field[fields.size()];
			for (int index = 0; index < fields.size(); index++) {
				Field source = fields.get(index);
				working[index] = new Field(source.sizeX(), source.sizeY(), source.sizeZ(), source.velocity().clone(),
						source.massKilograms(), source.dynamicViscosity(), source.openAreaFraction());
			}
			double substep = timeStepSeconds / substeps;
			for (Field field : working) {
				double[] delta = new double[field.velocity().length];
				for (int step = 0; step < substeps; step++) {
					Arrays.fill(delta, 0.0);
					forEachPair(field, cellSizeMeters, (first, second, conductance) -> {
						double impulse = conductance * (field.velocity()[second] - field.velocity()[first]) * substep;
						delta[first] += impulse / field.massKilograms()[first];
						delta[second] -= impulse / field.massKilograms()[second];
					});
					for (int face = 0; face < field.velocity().length; face++) {
						if (field.massKilograms()[face] <= 0.0) continue;
						double updated = field.velocity()[face] + delta[face];
						if (!Double.isFinite(updated) || Math.abs(updated) > Float.MAX_VALUE) {
							throw new BlockedException("Viscous face velocity is not representable");
						}
						field.velocity()[face] = (float) updated;
					}
				}
			}
			for (int index = 0; index < fields.size(); index++) {
				System.arraycopy(working[index].velocity(), 0, fields.get(index).velocity(), 0,
						working[index].velocity().length);
			}
		}
	}

	private FlipViscositySolver() { }

	static Plan plan(
			double timeStepSeconds, double cellSizeMeters, int maximumSubsteps, Field... fields
	) {
		if (!(Double.isFinite(timeStepSeconds) && timeStepSeconds > 0.0
				&& Double.isFinite(cellSizeMeters) && cellSizeMeters > 0.0) || maximumSubsteps <= 0
				|| fields == null || fields.length == 0) {
			throw new IllegalArgumentException("Viscosity plan input is invalid");
		}
		double maximumRate = 0.0;
		int pairs = 0;
		for (Field field : fields) {
			double[] rowRate = new double[field.velocity().length];
			for (int face = 0; face < field.velocity().length; face++) validateFace(field, face);
			int[] pairCount = {0};
			forEachPair(field, cellSizeMeters, (first, second, conductance) -> {
				rowRate[first] += conductance / field.massKilograms()[first];
				rowRate[second] += conductance / field.massKilograms()[second];
				pairCount[0]++;
			});
			pairs = Math.addExact(pairs, pairCount[0]);
			for (double rate : rowRate) {
				if (!Double.isFinite(rate)) throw new BlockedException("Viscous operator rate is not finite");
				maximumRate = Math.max(maximumRate, rate);
			}
		}
		double requiredValue = Math.ceil(timeStepSeconds * maximumRate);
		if (!Double.isFinite(requiredValue) || requiredValue > maximumSubsteps) {
			throw new BlockedException("Viscous stability requires " + requiredValue
					+ " substeps, over budget " + maximumSubsteps);
		}
		return new Plan(Arrays.asList(fields), Math.max(1, (int) requiredValue), pairs, maximumRate);
	}

	private static void validateFace(Field field, int face) {
		double mass = field.massKilograms()[face];
		double viscosity = field.dynamicViscosity()[face];
		float aperture = field.openAreaFraction()[face];
		if (!(Double.isFinite(mass) && mass >= 0.0) || !(Float.isFinite(aperture) && aperture >= 0.0F && aperture <= 1.0F)
				|| mass > 0.0 && (!(Double.isFinite(viscosity) && viscosity > 0.0)
				|| !Float.isFinite(field.velocity()[face]))) {
			throw new BlockedException("Viscosity face state is outside its valid domain");
		}
	}

	private static void forEachPair(Field field, double cellSizeMeters, PairConsumer consumer) {
		for (int z = 0; z < field.sizeZ(); z++) for (int y = 0; y < field.sizeY(); y++) {
			for (int x = 0; x < field.sizeX(); x++) {
				int first = index(x, y, z, field.sizeX(), field.sizeY());
				if (x + 1 < field.sizeX()) pair(field, first,
						index(x + 1, y, z, field.sizeX(), field.sizeY()), cellSizeMeters, consumer);
				if (y + 1 < field.sizeY()) pair(field, first,
						index(x, y + 1, z, field.sizeX(), field.sizeY()), cellSizeMeters, consumer);
				if (z + 1 < field.sizeZ()) pair(field, first,
						index(x, y, z + 1, field.sizeX(), field.sizeY()), cellSizeMeters, consumer);
			}
		}
	}

	private static void pair(Field field, int first, int second, double cellSizeMeters, PairConsumer consumer) {
		if (field.massKilograms()[first] <= 0.0 || field.massKilograms()[second] <= 0.0) return;
		double aperture = Math.min(field.openAreaFraction()[first], field.openAreaFraction()[second]);
		if (aperture <= 0.0) return;
		double firstMu = field.dynamicViscosity()[first], secondMu = field.dynamicViscosity()[second];
		double harmonicMu = 2.0 * firstMu * secondMu / (firstMu + secondMu);
		double conductance = harmonicMu * aperture * cellSizeMeters;
		if (!(Double.isFinite(conductance) && conductance > 0.0)) {
			throw new BlockedException("Viscous pair conductance is outside its valid domain");
		}
		consumer.accept(first, second, conductance);
	}

	private static int index(int x, int y, int z, int sizeX, int sizeY) {
		return (z * sizeY + y) * sizeX + x;
	}

	@FunctionalInterface private interface PairConsumer {
		void accept(int first, int second, double conductanceKilogramsPerSecond);
	}

	static final class BlockedException extends IllegalStateException {
		BlockedException(String message) { super(message); }
	}
}
