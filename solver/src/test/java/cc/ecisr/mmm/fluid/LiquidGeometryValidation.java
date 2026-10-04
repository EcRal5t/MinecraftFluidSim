package cc.ecisr.mmm.fluid;

/** Geometry conservation is checked separately from any liquid force or pressure solve. */
final class LiquidGeometryValidation {
	static void validate() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		moments(cube.moments(), 1, 0.5, 0.5, 0.5);
		moments(cube.clip(1, 1, 1, 1).moments(), 1.0 / 6, 1.0 / 24, 1.0 / 24, 1.0 / 24);
		for (double[] plane : new double[][] {{1, 0, 0, 0.25}, {1, 1, 1, 1.2}, {-2, 3, 1, 0.7}, {1, 1, 0, 1}, {0, 1, 0, 0}}) {
			var region = LiquidConvexRegion.of(cube); var cutter = new LiquidGeometryPlane(new LiquidPolyhedron.Point(plane[0], plane[1], plane[2]), plane[3]);
			if (!region.measure().isSum(region.clip(cutter).measure(), region.clip(cutter.opposite()).measure()))
				throw new AssertionError("Clipping lost exact volume or first moments");
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
		multipleFragmentsAndGasSpaceShareCompleteFaces();
		phasePartitionPreservesSolidOpenGeometry();
		System.out.println("Liquid geometry volume, centroid and local subdivision validation passed");
	}
	private static void phasePartitionPreservesSolidOpenGeometry() {
		var open = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 2, 3, 3);
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, open);
		var liquid = LiquidPolyhedron.box(0.125, 0.125, 0.125, 0.375, 0.375, 0.375);
		var phases = LiquidPhasePartition.build(root, java.util.List.of(liquid), 64); var measure = LiquidConvexRegion.EMPTY.measure();
		for (var region : phases) measure = measure.add(LiquidConvexRegion.of(region.cell().geometry()).measure());
		if (!measure.same(LiquidConvexRegion.of(open).measure())) throw new AssertionError("Phase partition changed solid open volume or first moments");
		var mesh = new LiquidControlVolumeMesh(phases.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
				(c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		for (var face : mesh.faces) if (face.second() < 0) {
			boolean support = false;
			for (var plane : open.planes()) support |= Math.abs(plane.signedDistance(face.surface().centroid())) <= plane.evaluationError(face.surface().centroid());
			if (!support) throw new AssertionError("Solid phase partition exposed an internal face");
		}
		try {
			LiquidPhasePartition.build(root, java.util.List.of(LiquidPolyhedron.box(0.75, 0.75, 0.75, 1, 1, 1)), 64);
			throw new AssertionError("Phase partition accepted liquid inside solid geometry");
		} catch (IllegalArgumentException expected) {
			if (!expected.getMessage().contains("open phase geometry")) throw expected;
		}
	}

	private static void multipleFragmentsAndGasSpaceShareCompleteFaces() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube);
		var inner = LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75).clip(1, 2, 3, 3);
		var disconnected = java.util.List.of(LiquidPolyhedron.box(0.125, 0.25, 0.25, 0.375, 0.75, 0.75),
				LiquidPolyhedron.box(0.625, 0.25, 0.25, 0.875, 0.75, 0.75));
		var cases = java.util.List.of(java.util.List.of(inner), disconnected,
				java.util.List.of(cube.clip(0, 1, 0, 0.501).clip(0, -1, 0, -0.499)),
				java.util.List.of(cube.clip(1, 2, 3, 3.01).clip(-1, -2, -3, -2.99)));
		int[] expectedGasComponents = {1, 1, 2, 2};
		for (int scenario = 0; scenario < cases.size(); scenario++) {
			var regions = LiquidPhasePartition.build(root, cases.get(scenario), 256);
			var cells = regions.stream().map(LiquidPhasePartition.Region::cell).toList();
			var sum = LiquidPolyhedron.Moments.ZERO;
			for (var cell : cells) sum = sum.add(cell.moments());
			moments(sum, 1, 0.5, 0.5, 0.5); LiquidPhasePartition.requireDisjoint(cells);
			var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, surface) ->
					new LiquidControlVolumeMesh.Boundary(false, 100000 + 1000 * 9.81 * (0.5 - surface.centroid().y())));
			for (int f = 0; f < mesh.faces.size(); f++) if (mesh.faces.get(f).second() < 0) {
				boolean exterior = false;
				for (int a = 0; a < 3; a++) for (double coordinate : new double[] {0, 1}) {
					boolean same = true; for (var point : mesh.polygons.get(f)) same &= point.coordinate(a) == coordinate;
					exterior |= same;
				}
				if (!exterior) throw new AssertionError("Phase partition exposed an internal face as exterior: scenario=" + scenario
						+ " cell=" + mesh.faces.get(f).first() + " surface=" + mesh.faces.get(f).surface() + " vertices=" + mesh.polygons.get(f));
			}
			int[] parents = java.util.stream.IntStream.range(0, cells.size()).toArray();
			for (var face : mesh.faces) if (face.second() >= 0 && regions.get(face.first()).liquid() < 0 && regions.get(face.second()).liquid() < 0)
				parents[ancestor(parents, face.first())] = ancestor(parents, face.second());
			var components = new java.util.HashSet<Integer>();
			for (int c = 0; c < cells.size(); c++) if (regions.get(c).liquid() < 0) components.add(ancestor(parents, c));
			if (components.size() != expectedGasComponents[scenario]) throw new AssertionError("Thin liquid interface changed gas connectivity");
			var projected = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[3 * cells.size()],
					new LiquidPolyhedron.Point(0, -9.81, 0), 0.025, 1e-10, 2048);
			for (double velocity : projected.cellVelocity()) if (Math.abs(velocity) > 1e-8)
				throw new AssertionError("Subcell shared faces failed affine hydrostatic equilibrium: " + velocity);
		}
		var fragments = disconnected.stream().map(g -> new LiquidGeometryRefinement.Cell(0, 0, 0, 0, g)).toList();
		var first = LiquidGeometryStateMapper.capture(fragments, 1, new int[] {0, 1});
		var second = LiquidGeometryStateMapper.capture(fragments.reversed(), 1, new int[] {1, 0});
		for (int c = 0; c < 2; c++) if (LiquidGeometryState.compare(first.cells().get(c), second.cells().get(c)) != 0)
			throw new AssertionError("Same-leaf fragment order changed canonical geometry");
		if (!java.util.Arrays.equals(first.copyMarkerCells(), second.copyMarkerCells()) || LiquidGeometryStateMapper.restore(first).size() != 2)
			throw new AssertionError("Same-leaf fragments lost inventory ownership across handoff");
		var plane = new LiquidGeometryPlane(new LiquidPolyhedron.Point(1, 2, 3), 0.75);
		if (!plane.key().equals(plane.opposite().key()) || !plane.key().equals(new LiquidGeometryPlane(new LiquidPolyhedron.Point(2, 4, 6), 1.5).key()))
			throw new AssertionError("Shared plane identity depends on normal orientation or exact scale");
		try {
			LiquidPhasePartition.build(root, java.util.List.of(inner, inner), 256);
			throw new AssertionError("Overlapping liquid fragments were accepted");
		} catch (IllegalArgumentException expected) { }
		try {
			LiquidPhasePartition.build(root, java.util.List.of(inner), 2);
			throw new AssertionError("Phase partition ignored its resource budget");
		} catch (IllegalStateException expected) { }
	}
	private static int ancestor(int[] parents, int c) { while (parents[c] != c) c = parents[c]; return c; }

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
		var vertices = copied.liquidGeometryState.cells().getFirst().copyVertices(); var original = vertices[0]; vertices[0] = java.math.BigInteger.ZERO;
		if (!copied.liquidGeometryState.cells().getFirst().copyVertices()[0].equals(original))
			throw new AssertionError("Liquid geometry snapshot exposed mutable vertices");
		var incompatible = LiquidGeometryStateMapper.capture(cells, width / 2, markerCells);
		try {
			LiquidGeometryStateMapper.requireCompatible(incompatible, domain);
			throw new AssertionError("Liquid geometry accepted inconsistent physical units");
		} catch (IllegalArgumentException expected) { }
		// One inventory group retains disconnected geometry without requiring a new chemical quantum per fragment.
		domain.particles.setPositionX(0, 0.375F * (float) width);
		var split = java.util.List.of(new LiquidGeometryRefinement.Cell(2, 1, 1, 1, LiquidPolyhedron.box(0.4375, 0.25, 0.25, 0.5, 0.375, 0.375)),
				new LiquidGeometryRefinement.Cell(2, 1, 1, 1, LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.3125, 0.375, 0.375)));
		domain.liquidGeometryState = LiquidGeometryStateMapper.capture(split, width, new int[] {0}, new int[] {0, 0});
		copied = FluidDomainStateMapper.restore(FluidDomainStateMapper.capture(domain));
		var grouped = copied.liquidGeometryState;
		if (grouped.cells().size() != 2 || grouped.markerCount() != 1 || !java.util.Arrays.equals(grouped.copyFragmentOwners(), new int[] {0, 0}))
			throw new AssertionError("Convex subdivision changed the inventory group");
		var ownership = grouped.copyFragmentOwners(); ownership[1] = 1;
		if (grouped.copyFragmentOwners()[1] != 0) throw new AssertionError("Liquid geometry snapshot exposed mutable inventory ownership");
		var recaptured = LiquidGeometryStateMapper.capture(LiquidGeometryStateMapper.restore(grouped), width, grouped.copyMarkerCells(), grouped.copyFragmentOwners());
		for (int c = 0; c < grouped.cells().size(); c++) if (LiquidGeometryState.compare(grouped.cells().get(c), recaptured.cells().get(c)) != 0)
			throw new AssertionError("Grouped geometry changed across backend handoff");
		try {
			new LiquidGeometryState(width, grouped.cells(), grouped.copyMarkerCells(), ownership);
			throw new AssertionError("Geometry accepted an inventory group with no carrier");
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
