package com.example.fluid;

import java.util.Arrays;
import java.util.List;

/** M3 test-side P2G gate for simultaneous marker volume, mass and momentum transfer. */
final class M3MaterialTransferValidation {
	private static final int SIZE = 4;
	private static final double CELL_SIZE = 1.0;

	private M3MaterialTransferValidation() { }

	static Summary validate() {
		FluidMaterialRegistry registry = registry();
		validateRegistry(registry);

		Markers equalDensity = markers(registry, false);
		Transfer equalTransfer = transfer(equalDensity);
		validateTransferLedger(equalDensity, equalTransfer);
		check(Arrays.equals(equalTransfer.volumeWeightedVelocity(), equalTransfer.massWeightedVelocity()),
				"equal-density material labels changed P2G face velocity");

		Markers variableDensity = markers(registry, true);
		Transfer variableTransfer = transfer(variableDensity);
		validateTransferLedger(variableDensity, variableTransfer);
		check(!Arrays.equals(variableTransfer.volumeWeightedVelocity(), variableTransfer.massWeightedVelocity()),
				"variable-density P2G did not switch from volume weighting to momentum/mass weighting");
		return new Summary(variableDensity.totalVolume(), variableDensity.totalMass(),
				variableDensity.momentum(0), maximumDifference(
						variableTransfer.volumeWeightedVelocity(), variableTransfer.massWeightedVelocity()));
	}

	private static FluidMaterialRegistry registry() {
		return new FluidMaterialRegistry(List.of(
				new FluidMaterial("realwater:material_a", 1_000.0, "realwater:aqueous"),
				new FluidMaterial("realwater:material_b", 1_000.0, "realwater:immiscible_b"),
				new FluidMaterial("realwater:material_c", 800.0, "realwater:immiscible_c"),
				new FluidMaterial("realwater:material_d", 1_050.0, "realwater:aqueous")
		));
	}

	private static void validateRegistry(FluidMaterialRegistry registry) {
		check(registry.size() == 4, "material registry size changed");
		int a = registry.handle("realwater:material_a");
		int b = registry.handle("realwater:material_b");
		int c = registry.handle("realwater:material_c");
		int d = registry.handle("realwater:material_d");
		check(a == 0 && b == 1 && c == 2 && d == 3, "runtime handles are not declaration-order dense");
		check(registry.areMiscible(a, d), "same miscibility group was rejected");
		check(!registry.areMiscible(a, b) && !registry.areMiscible(a, c), "unlike miscibility groups were merged");
		expectIllegal(() -> new FluidMaterial("Bad Id", 1_000.0, "realwater:aqueous"));
		expectIllegal(() -> new FluidMaterial("realwater:bad", 0.0, "realwater:aqueous"));
		expectIllegal(() -> new FluidMaterialRegistry(List.of(
				registry.material(a), registry.material(a))));
		expectIllegal(() -> registry.material(-1));
		expectIllegal(() -> registry.handle("realwater:missing"));
	}

	private static Markers markers(FluidMaterialRegistry registry, boolean variableDensity) {
		double[] x = {1.25, 1.75, 2.25, 2.75};
		double[] y = {1.25, 2.25, 1.75, 2.75};
		double[] z = {1.75, 1.25, 2.75, 2.25};
		double[] volume = {0.125, 0.250, 0.125, 0.250};
		double[] velocityX = {0.5, -1.0, 1.5, -0.5};
		double[] velocityY = {-0.5, 0.25, 1.0, -1.5};
		double[] velocityZ = {1.0, -0.25, 0.5, -1.0};
		int[] material = {
				registry.handle("realwater:material_a"), registry.handle("realwater:material_b"),
				registry.handle(variableDensity ? "realwater:material_c" : "realwater:material_a"),
				registry.handle(variableDensity ? "realwater:material_c" : "realwater:material_b")
		};
		int[] phase = {11, 22, variableDensity ? 33 : 11, variableDensity ? 33 : 22};
		double[] mass = new double[volume.length];
		for (int marker = 0; marker < mass.length; marker++) {
			mass[marker] = volume[marker] * registry.material(material[marker]).referenceDensity();
		}
		return new Markers(x, y, z, volume, mass, velocityX, velocityY, velocityZ, material, phase);
	}

	private static Transfer transfer(Markers markers) {
		int totalFaces = (SIZE + 1) * SIZE * SIZE * 3;
		double[] faceVolume = new double[totalFaces];
		double[] faceMass = new double[totalFaces];
		double[] volumeMomentum = new double[totalFaces];
		double[] massMomentum = new double[totalFaces];
		int offset = 0;
		for (int axis = 0; axis < 3; axis++) {
			int nx = axis == 0 ? SIZE + 1 : SIZE;
			int ny = axis == 1 ? SIZE + 1 : SIZE;
			int nz = axis == 2 ? SIZE + 1 : SIZE;
			for (int marker = 0; marker < markers.size(); marker++) {
				double gx = markers.x()[marker] / CELL_SIZE - (axis == 0 ? 0.0 : 0.5);
				double gy = markers.y()[marker] / CELL_SIZE - (axis == 1 ? 0.0 : 0.5);
				double gz = markers.z()[marker] / CELL_SIZE - (axis == 2 ? 0.0 : 0.5);
				int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
				double fx = gx - x0, fy = gy - y0, fz = gz - z0;
				double velocity = markers.velocity(axis)[marker];
				for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
					int x = x0 + ox, y = y0 + oy, z = z0 + oz;
					if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) {
						throw new IllegalArgumentException("Transfer marker touched the grid boundary");
					}
					double weight = trilinearWeight(ox, oy, oz, fx, fy, fz);
					int face = offset + x + nx * (y + ny * z);
					faceVolume[face] += weight * markers.volume()[marker];
					faceMass[face] += weight * markers.mass()[marker];
					volumeMomentum[face] += weight * markers.volume()[marker] * velocity;
					massMomentum[face] += weight * markers.mass()[marker] * velocity;
				}
			}
			offset += nx * ny * nz;
		}
		double[] volumeVelocity = new double[totalFaces];
		double[] massVelocity = new double[totalFaces];
		for (int face = 0; face < totalFaces; face++) {
			if (faceVolume[face] > 0.0) volumeVelocity[face] = volumeMomentum[face] / faceVolume[face];
			if (faceMass[face] > 0.0) massVelocity[face] = massMomentum[face] / faceMass[face];
		}
		return new Transfer(faceVolume, faceMass, volumeMomentum, massMomentum, volumeVelocity, massVelocity);
	}

	private static void validateTransferLedger(Markers markers, Transfer transfer) {
		int axisFaces = (SIZE + 1) * SIZE * SIZE;
		for (int axis = 0; axis < 3; axis++) {
			int from = axis * axisFaces, to = from + axisFaces;
			checkClose(sum(transfer.faceVolume(), from, to), markers.totalVolume(), "P2G volume ledger");
			checkClose(sum(transfer.faceMass(), from, to), markers.totalMass(), "P2G mass ledger");
			checkClose(sum(transfer.massMomentum(), from, to), markers.momentum(axis), "P2G momentum ledger");
		}
	}

	private static double trilinearWeight(int ox, int oy, int oz, double fx, double fy, double fz) {
		return (ox == 0 ? 1.0 - fx : fx) * (oy == 0 ? 1.0 - fy : fy) * (oz == 0 ? 1.0 - fz : fz);
	}

	private static double sum(double[] values, int from, int to) {
		double result = 0.0;
		for (int i = from; i < to; i++) result += values[i];
		return result;
	}

	private static double maximumDifference(double[] left, double[] right) {
		double result = 0.0;
		for (int i = 0; i < left.length; i++) result = Math.max(result, Math.abs(left[i] - right[i]));
		return result;
	}

	private static void checkClose(double actual, double expected, String message) {
		double tolerance = 1.0E-12 * Math.max(1.0, Math.abs(expected));
		check(Math.abs(actual - expected) <= tolerance, message + ": expected=" + expected + ", actual=" + actual);
	}

	private static void expectIllegal(Runnable action) {
		try {
			action.run();
			throw new AssertionError("Expected invalid material state to be rejected");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	record Summary(double volume, double mass, double momentumX, double maximumWeightingDifference) { }

	private record Transfer(double[] faceVolume, double[] faceMass,
	                        double[] volumeMomentum, double[] massMomentum,
	                        double[] volumeWeightedVelocity, double[] massWeightedVelocity) { }

	private record Markers(double[] x, double[] y, double[] z, double[] volume, double[] mass,
	                       double[] velocityX, double[] velocityY, double[] velocityZ,
	                       int[] materialHandle, int[] phaseLabel) {
		int size() { return x.length; }
		double[] velocity(int axis) { return axis == 0 ? velocityX : axis == 1 ? velocityY : velocityZ; }
		double totalVolume() { return sum(volume, 0, volume.length); }
		double totalMass() { return sum(mass, 0, mass.length); }
		double momentum(int axis) {
			double result = 0.0;
			double[] velocity = velocity(axis);
			for (int marker = 0; marker < size(); marker++) result += mass[marker] * velocity[marker];
			return result;
		}
	}
}
