package cc.ecisr.mmm.fluid;

/** Geometry conservation is checked separately from any liquid force or pressure solve. */
final class LiquidGeometryValidation {
	static void validate() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		moments(cube.moments(), 1, 0.5, 0.5, 0.5);
		moments(cube.clip(1, 1, 1, 1).moments(), 1.0 / 6, 1.0 / 24, 1.0 / 24, 1.0 / 24);
		for (double[] plane : new double[][] {{1, 0, 0, 0.25}, {1, 1, 1, 1.2}, {-2, 3, 1, 0.7}, {1, 1, 0, 1}, {0, 1, 0, 0}}) {
			var lower = cube.clip(plane[0], plane[1], plane[2], plane[3]).moments();
			var upper = cube.clip(-plane[0], -plane[1], -plane[2], -plane[3]).moments();
			moments(lower.add(upper), 1, 0.5, 0.5, 0.5);
		}
		for (var liquid : new LiquidPolyhedron[] {cube.clip(1, 1, 1, 1.2), LiquidPolyhedron.box(0.125, 0.25, 0.375, 0.375, 0.5, 0.625)}) {
			var original = liquid.moments();
			pressureResultants(liquid);
			for (double dt : new double[] {1e-4, 1e-6}) {
				var moved = liquid.translated(0, -dt, 0).moments();
				moments(moved, original.volume(), original.x(), original.y() - original.volume() * dt, original.z());
			}
			var cells = java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, liquid));
			for (int level = 0; level < 3; level++) {
				var next = new java.util.ArrayList<LiquidGeometryRefinement.Cell>();
				for (var cell : cells) next.addAll(LiquidGeometryRefinement.children(cell));
				cells = java.util.List.copyOf(next);
				var sum = LiquidPolyhedron.Moments.ZERO;
				for (var cell : cells) sum = sum.add(cell.moments());
				moments(sum, original.volume(), original.x(), original.y(), original.z());
				int[] owners = java.util.stream.IntStream.range(0, cells.size()).toArray();
				var restored = LiquidGeometryStateMapper.restore(LiquidGeometryStateMapper.capture(cells, 1, owners));
				var restoredSum = LiquidPolyhedron.Moments.ZERO;
				for (var cell : restored) restoredSum = restoredSum.add(cell.moments());
				moments(restoredSum, original.volume(), original.x(), original.y(), original.z());
			}
		}
		cutCellSweepsUseTheSameGeometry();
		translationAcrossRefinementPreservesMoments();
		geometrySurvivesCandidateHandoff();
		affineCharacteristicsPreserveResolvedGeometry();
		System.out.println("Liquid geometry volume, centroid and local subdivision validation passed");
	}

	private static void affineCharacteristicsPreserveResolvedGeometry() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var partition = new java.util.ArrayList<>(LiquidGeometryRefinement.children(root));
		var refined = partition.removeFirst(); partition.addAll(LiquidGeometryRefinement.children(refined));
		var remap = new LiquidGeometryRemap(partition);
		var source = LiquidPolyhedron.box(0.375, 0.25, 0.375, 0.625, 0.5, 0.625);
		var initial = source.moments(); var inventory = cc.ecisr.mmm.units.SignedDyadic.of(initial.volume());
		for (double dt : new double[] {0, 1e-6, 0.025, 0.05}) {
			double omega = 3;
			var rotation = new LiquidAffineMotion(new LiquidPolyhedron.Point(0.5, 0.5, 0.5), new LiquidPolyhedron.Point(0, 0, 0),
					new double[] {0, -omega, 0, omega, 0, 0, 0, 0, 0}, dt);
			var sum = LiquidPolyhedron.Moments.ZERO; var amount = cc.ecisr.mmm.units.SignedDyadic.ZERO;
			for (var fragment : remap.advect(source, inventory, rotation)) {
				sum = sum.add(fragment.geometry().moments()); amount = amount.add(fragment.inventory());
			}
			if (!amount.equals(inventory)) throw new AssertionError("Affine liquid motion changed exact inventory");
			moments(sum, initial.volume(), initial.volume() * (0.5 + 0.125 * Math.sin(omega * dt)),
					initial.volume() * (0.5 - 0.125 * Math.cos(omega * dt)), initial.z());
			var shear = new LiquidAffineMotion(new LiquidPolyhedron.Point(0.5, 0.5, 0.5), new LiquidPolyhedron.Point(0.5, 0, 0),
					new double[] {0, 1, 0, 0, 0, 0, 0, 0, 0}, dt);
			var sheared = shear.map(source).moments();
			moments(sheared, initial.volume(), initial.x() + dt * initial.y(), initial.y(), initial.z());
		}
	}

	private static void geometrySurvivesCandidateHandoff() {
		var domain = new FluidDomain(4, 4, 4, FluidSolverConfig.prototypeDefaults());
		domain.setVolume(1, 1, 1, 0.125F);
		var geometry = LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.375, 0.375, 0.375);
		var cells = java.util.List.of(new LiquidGeometryRefinement.Cell(3, 2, 2, 2, geometry));
		var markerCells = new int[domain.particles.count()];
		double width = 4 * (double) domain.config.cellSize();
		domain.liquidGeometryState = LiquidGeometryStateMapper.capture(cells, width, markerCells);
		var copied = FluidDomainStateMapper.restore(FluidDomainStateMapper.capture(domain));
		var restored = LiquidGeometryStateMapper.restore(copied.liquidGeometryState);
		if (copied.liquidGeometryState == null || copied.liquidGeometryState.rootWidthMetres() != width || restored.size() != 1)
			throw new AssertionError("Liquid geometry was lost across a backend candidate handoff");
		moments(restored.getFirst().moments(), geometry.moments().volume(), geometry.moments().x(), geometry.moments().y(), geometry.moments().z());
		var vertices = copied.liquidGeometryState.cells().getFirst().copyVertices(); vertices[0] = 0;
		if (copied.liquidGeometryState.cells().getFirst().copyVertices()[0] != 0.25)
			throw new AssertionError("Liquid geometry snapshot exposed mutable vertices");
		var incompatible = LiquidGeometryStateMapper.capture(cells, width / 2, markerCells);
		try {
			LiquidGeometryStateMapper.requireCompatible(incompatible, domain);
			throw new AssertionError("Liquid geometry accepted inconsistent physical units");
		} catch (IllegalArgumentException expected) { }
	}

	private static void translationAcrossRefinementPreservesMoments() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var partition = new java.util.ArrayList<>(LiquidGeometryRefinement.children(root));
		var refined = partition.removeFirst(); partition.addAll(LiquidGeometryRefinement.children(refined));
		var remap = new LiquidGeometryRemap(partition);
		var source = LiquidPolyhedron.box(0.375, 0.25, 0.125, 0.625, 0.5, 0.375);
		var initial = source.moments();
		var inventory = cc.ecisr.mmm.units.SignedDyadic.of(0.125);
		for (double displacement : new double[] {0, -0.125, -1e-4, -1e-6, 0.125}) {
			var total = LiquidPolyhedron.Moments.ZERO;
			var amount = cc.ecisr.mmm.units.SignedDyadic.ZERO;
			for (var fragment : remap.translate(source, inventory, displacement, 0, 0)) {
				total = total.add(fragment.geometry().moments()); amount = amount.add(fragment.inventory());
			}
			if (!amount.equals(inventory)) throw new AssertionError("Refined liquid remap changed exact inventory");
			moments(total, initial.volume(), initial.x() + displacement * initial.volume(), initial.y(), initial.z());
		}
	}

	private static void pressureResultants(LiquidPolyhedron liquid) {
		double[] constant = new double[3], hydrostatic = new double[3];
		for (var face : liquid.surfaces()) for (int axis = 0; axis < 3; axis++) {
			double area = face.areaVector().coordinate(axis);
			constant[axis] -= area;
			hydrostatic[axis] -= (1 - face.centroid().y()) * area;
		}
		for (int axis = 0; axis < 3; axis++) {
			double expected = axis == 1 ? liquid.moments().volume() : 0;
			if (Math.abs(constant[axis]) > 128 * Math.ulp(1.0) || Math.abs(hydrostatic[axis] - expected) > 128 * Math.ulp(1.0))
				throw new AssertionError("Liquid surface pressure integral violated the divergence theorem");
		}
	}

	private static void cutCellSweepsUseTheSameGeometry() {
		var cut = new FlipCutCellGeometry(2, 2, 2);
		var geometry = new LiquidFluxGeometry(cut);
		for (int mask : new int[] {0, 0x33, 0x55, 0x71}) {
			cut.replaceMask(0, mask);
			for (int axis = 0; axis < 3; axis++) for (boolean positive : new boolean[] {false, true}) {
				int neighbor = 1 << axis;
				for (double width : new double[] {0.125, 0.25, 0.5}) {
					double volume = geometry.swept(0, neighbor, axis, width, positive, 1, 2, -1, 0.75F);
					var moment = geometry.moments(0, 1, 2, -1, 0.75, axis, width, positive, neighbor);
					if (Math.abs(volume - moment.volume()) > 8 * Math.ulp((float) volume))
						throw new AssertionError("Liquid sweep volume and moment geometry disagree");
				}
			}
		}
	}
	private static void moments(LiquidPolyhedron.Moments actual, double volume, double x, double y, double z) {
		double[] result = {actual.volume(), actual.x(), actual.y(), actual.z()}, expected = {volume, x, y, z};
		for (int i = 0; i < result.length; i++) if (Math.abs(result[i] - expected[i]) > 256 * Math.ulp(expected[i] == 0 ? 1 : expected[i]))
			throw new AssertionError("Liquid geometry moment " + i + ": actual=" + result[i] + " expected=" + expected[i]);
	}
	public static void main(String[] args) { validate(); }
}
