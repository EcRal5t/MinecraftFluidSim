package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Positive cell-local boxes bound to the exact global-position moments of their published markers. */
public record LiquidCellShape(int cell, List<Box> boxes, SignedDyadic firstMomentX,
                              SignedDyadic firstMomentY, SignedDyadic firstMomentZ) {
	public static final int MAX_BOXES_PER_CELL = 8;
	public LiquidCellShape {
		if (cell < 0 || boxes == null || boxes.isEmpty() || boxes.size() > MAX_BOXES_PER_CELL
				|| firstMomentX == null || firstMomentY == null || firstMomentZ == null
				|| firstMomentX.signum() < 0 || firstMomentY.signum() < 0 || firstMomentZ.signum() < 0)
			throw new IllegalArgumentException("Invalid liquid cell shape");
		boxes = List.copyOf(boxes);
		for (int i = 0; i < boxes.size(); i++) for (int j = 0; j < i; j++) {
			boolean overlap = true;
			for (int a = 0; a < 3; a++) overlap &= boxes.get(i).lower(a).compareTo(boxes.get(j).upper(a)) < 0
					&& boxes.get(j).lower(a).compareTo(boxes.get(i).upper(a)) < 0;
			if (overlap) throw new IllegalArgumentException("Liquid cell boxes overlap with positive volume");
		}
	}
	public record Box(SignedDyadic lowerX, SignedDyadic lowerY, SignedDyadic lowerZ,
	                  SignedDyadic upperX, SignedDyadic upperY, SignedDyadic upperZ) {
		public Box {
			var lower = new SignedDyadic[]{lowerX, lowerY, lowerZ}; var upper = new SignedDyadic[]{upperX, upperY, upperZ};
			for (int a = 0; a < 3; a++) if (lower[a] == null || upper[a] == null || lower[a].signum() < 0
					|| upper[a].compareTo(SignedDyadic.of(1)) > 0 || lower[a].compareTo(upper[a]) >= 0)
				throw new IllegalArgumentException("Liquid box bounds must define positive volume inside the unit cell");
		}
		public SignedDyadic lower(int axis) { return switch (axis) { case 0 -> lowerX; case 1 -> lowerY; case 2 -> lowerZ;
			default -> throw new IllegalArgumentException("Invalid liquid box axis"); }; }
		public SignedDyadic upper(int axis) { return switch (axis) { case 0 -> upperX; case 1 -> upperY; case 2 -> upperZ;
			default -> throw new IllegalArgumentException("Invalid liquid box axis"); }; }
		public SignedDyadic volume() { return upperX.subtract(lowerX).multiply(upperY.subtract(lowerY)).multiply(upperZ.subtract(lowerZ)); }
	}
	public SignedDyadic volume() {
		var total = SignedDyadic.ZERO; for (var box : boxes) total = total.add(box.volume()); return total;
	}
	public SignedDyadic firstMoment(int axis) { return switch (axis) { case 0 -> firstMomentX; case 1 -> firstMomentY; case 2 -> firstMomentZ;
		default -> throw new IllegalArgumentException("Invalid liquid shape moment axis"); }; }
	public long cpuBytes() {
		long bytes = Integer.BYTES + 3L * Long.BYTES;
		for (int a = 0; a < 3; a++) bytes += dyadicBytes(firstMoment(a));
		for (var box : boxes) for (int a = 0; a < 3; a++) bytes += dyadicBytes(box.lower(a)) + dyadicBytes(box.upper(a));
		return bytes;
	}
	private static long dyadicBytes(SignedDyadic value) { return 8L + (value.significand().bitLength() + 8L) / 8; }

	/** Validates a complete cell tuple, using either full-domain masks or a section's mask lookup. */
	public static void requireMatches(List<LiquidCellShape> shapes, int sizeX, int sizeY, int sizeZ, float h,
	                                  float[] positionVolume, IntUnaryOperator collisionMask) {
		if (shapes == null || positionVolume == null || positionVolume.length % 4 != 0 || collisionMask == null
				|| sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || !(Float.isFinite(h) && h > 0))
			throw new IllegalArgumentException("Liquid shape binding requires complete marker and mask inputs");
		if (shapes.isEmpty()) return;
		int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ), previous = -1;
		var selected = new HashMap<Integer, Integer>(); var volume = new SignedDyadic[shapes.size()]; var first = new SignedDyadic[shapes.size()][3];
		var rounding = new SignedDyadic[shapes.size()][3];
		for (int i = 0; i < shapes.size(); i++) {
			var shape = shapes.get(i);
			if (shape == null || shape.cell <= previous || shape.cell >= cells || collisionMask.applyAsInt(shape.cell) != 0)
				throw new IllegalArgumentException("Liquid shapes require unique ordered cells with fully open masks");
			previous = shape.cell; selected.put(shape.cell, i); volume[i] = SignedDyadic.ZERO;
			java.util.Arrays.fill(first[i], SignedDyadic.ZERO);
			java.util.Arrays.fill(rounding[i], SignedDyadic.ZERO);
		}
		var spacing = SignedDyadic.of(h);
		for (int p = 0; p < positionVolume.length; p += 4) {
			for (int a = 0; a < 4; a++) if (!Float.isFinite(positionVolume[p + a]) || positionVolume[p + a] < 0)
				throw new IllegalArgumentException("Invalid raw liquid shape marker tuple");
			if (!(positionVolume[p + 3] > 0)) throw new IllegalArgumentException("Liquid shape requires positive marker volume");
			int x = (int) Math.floor(positionVolume[p] / h), y = (int) Math.floor(positionVolume[p + 1] / h), z = (int) Math.floor(positionVolume[p + 2] / h);
			var index = selected.get(x + sizeX * (y + sizeY * z)); if (index == null) continue;
			var amount = SignedDyadic.of(positionVolume[p + 3]); volume[index] = volume[index].add(amount);
			int[] coordinate = {x, y, z}; boolean contained = false;
			for (var box : shapes.get(index).boxes) {
				boolean inside = true;
				for (int a = 0; a < 3; a++) {
					var actual = SignedDyadic.of(positionVolume[p + a]); var origin = SignedDyadic.of(coordinate[a]);
					inside &= actual.compareTo(spacing.multiply(origin.add(box.lower(a)))) >= 0
							&& actual.compareTo(spacing.multiply(origin.add(box.upper(a)))) <= 0;
				}
				contained |= inside;
			}
			if (!contained) throw new IllegalArgumentException("Liquid marker lies outside its saved positive shape");
			for (int a = 0; a < 3; a++) {
				first[index][a] = first[index][a].add(amount.multiply(SignedDyadic.of(positionVolume[p + a])));
				rounding[index][a] = rounding[index][a].add(amount.multiply(SignedDyadic.of(Math.ulp(positionVolume[p + a]))));
			}
		}
		for (int i = 0; i < shapes.size(); i++) {
			if (!volume[i].equals(shapes.get(i).volume())) throw new IllegalArgumentException("Liquid shape volume differs from its raw cell inventory");
			for (int a = 0; a < 3; a++) if (!first[i][a].equals(shapes.get(i).firstMoment(a)))
				throw new IllegalArgumentException("Liquid shape first moment differs from its published marker tuple");
			int cell = shapes.get(i).cell; int[] coordinate = {cell % sizeX, cell / sizeX % sizeY, cell / (sizeX * sizeY)};
			for (int a = 0; a < 3; a++) {
				var geometric = SignedDyadic.ZERO;
				for (var box : shapes.get(i).boxes) geometric = geometric.add(box.volume().multiply(spacing).multiply(
						SignedDyadic.of(coordinate[a]).add(box.lower(a).add(box.upper(a)).multiply(SignedDyadic.of(1, -1)))));
				var error = first[i][a].subtract(geometric);
				if ((error.signum() < 0 ? error.negate() : error).compareTo(rounding[i][a]) > 0)
					throw new IllegalArgumentException("Liquid shape geometric first moment exceeds coordinate storage error");
			}
		}
	}
}
