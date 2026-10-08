package cc.ecisr.mmm.fluid;

/** Geometry conservation is checked separately from any liquid force or pressure solve. */
final class LiquidGeometryValidation {
	private static void integerNormalizationPreservesExactRationals() {
		var random = new java.util.Random(0x73411);
		for (int bits : new int[]{64, 128, 256, 1024, 4096, 8192, 16384, 32768, 65536}) for (int sample = 0; sample < 8; sample++) {
			var factor = new java.math.BigInteger(257, random).add(java.math.BigInteger.ONE);
			var first = new java.math.BigInteger(bits, random).multiply(factor);
			var second = new java.math.BigInteger(bits, random).multiply(factor);
			var expected = first.gcd(second);
			if (!LiquidIntegerArithmetic.gcd(first.negate(), second).equals(expected)) throw new AssertionError("Batched reduction changed the integer GCD");
			var fraction = new LiquidExactPolygon.Fraction(first, second.negate());
			if (!fraction.numerator().equals(first.divide(expected).negate()) || !fraction.denominator().equals(second.divide(expected)))
				throw new AssertionError("Large exact rational normalization changed its canonical representation");
		}
		var power = java.math.BigInteger.ONE.shiftLeft(65536);
		var below = new LiquidExactPolygon.Fraction(power.subtract(java.math.BigInteger.ONE), power);
		var above = new LiquidExactPolygon.Fraction(power.add(java.math.BigInteger.ONE), power);
		var one = LiquidExactPolygon.Fraction.of(1);
		if (below.rounded() != above.rounded() || below.compareTo(one) >= 0 || above.compareTo(one) <= 0 || below.compareTo(above) >= 0
				|| below.negate().compareTo(above.negate()) <= 0 || below.compareTo(below) != 0 || below.negate().compareTo(LiquidExactPolygon.Fraction.ZERO) >= 0)
			throw new AssertionError("Exact fraction ordering lost a sub-observation difference or its sign");
		for (var pair : java.util.List.of(new java.math.BigInteger[]{power, power}, new java.math.BigInteger[]{power, java.math.BigInteger.ZERO},
				new java.math.BigInteger[]{java.math.BigInteger.ZERO, power}, new java.math.BigInteger[]{power.multiply(power), power},
				new java.math.BigInteger[]{power.add(java.math.BigInteger.ONE), power}, new java.math.BigInteger[]{power.subtract(java.math.BigInteger.ONE), power}))
			if (!LiquidIntegerArithmetic.gcd(pair[0], pair[1]).equals(pair[0].gcd(pair[1]))) throw new AssertionError("Exact reduction failed at a leading-word limit");
		var first = java.math.BigInteger.ONE; var second = first;
		while (second.bitLength() < 16384) { var next = first.add(second); first = second; second = next; }
		if (!LiquidIntegerArithmetic.gcd(first, second).equals(java.math.BigInteger.ONE)) throw new AssertionError("Consecutive Fibonacci integers lost their coprimality");
	}
	static void validate() {
		integerNormalizationPreservesExactRationals();
		LiquidVolumeInterfaceValidation.validate();
		canonicalGeometryProofSurvivesPacking();
		controlCellsPreserveChosenMechanicalResolution();
		mechanicalCellsCancelAuxiliaryCuts();
		separatePressureSupportsPreserveIndependentVolumes();
		mechanicalFacetsIgnoreAuxiliaryGasCuts();
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var rationalFans = new java.util.ArrayList<LiquidExactPolygon>(); int fan = 0;
		for (int x = 0; x < 4; x++) for (var face : LiquidExactPolygon.faces(LiquidPolyhedron.box(x / 4.0, 0, 0, (x + 1) / 4.0, 1, 1))) {
			var denominator = java.math.BigInteger.ONE.shiftLeft(1024).add(java.math.BigInteger.valueOf(2 * ++fan + 1));
			var fraction = new LiquidExactPolygon.Fraction(java.math.BigInteger.ONE, denominator);
			var center = face.centroidCoordinates(); var corner = face.vertices().getFirst().coordinates();
			for (int axis = 0; axis < 3; axis++) center[axis] = center[axis].add(corner[axis].subtract(center[axis]).multiply(fraction));
			var point = LiquidExactPolygon.vertex(center);
			for (int edge = 0; edge < face.vertices().size(); edge++) rationalFans.add(LiquidExactPolygon.triangle(point, face.vertices().get(edge), face.vertices().get((edge + 1) % face.vertices().size())));
		}
		if (!LiquidExactPolygon.measure(rationalFans).same(cube.measure()) || !LiquidExactPolygon.volume(rationalFans).equals(cube.measure().volume()))
			throw new AssertionError("Independent rational face fans changed exact volume or first moments");
		for (var face : LiquidExactPolygon.faces(cube)) {
			var vertices = new java.util.ArrayList<LiquidExactPolygon.Vertex>();
			for (int e = 0; e < face.vertices().size(); e++) {
				var a = face.vertices().get(e).coordinates(); var b = face.vertices().get((e + 1) % face.vertices().size()).coordinates();
				vertices.add(face.vertices().get(e));
				for (int divisor : new int[]{3, 7, 101}) {
					var point = a.clone();
					for (int axis = 0; axis < 3; axis++) point[axis] = a[axis].add(b[axis].subtract(a[axis]).multiply(LiquidExactPolygon.Fraction.of(1).subtract(LiquidExactPolygon.Fraction.of(1).divide(LiquidExactPolygon.Fraction.of(divisor)))));
					vertices.add(LiquidExactPolygon.vertex(point));
				}
			}
			var subdivided = LiquidExactPolygon.restored(vertices, face.coefficients());
			if (!java.util.Arrays.equals(face.areaVector(), subdivided.areaVector()) || !java.util.Arrays.equals(face.centroidCoordinates(), subdivided.centroidCoordinates())
					|| subdivided.vertices().size() != vertices.size()) throw new AssertionError("Collinear subdivision changed area, first moment or mesh incidence");
			var surface = subdivided.surface();
			for (var equivalent : java.util.List.of(subdivided.cycled(1), subdivided.clippedTo(java.util.List.of(face)), subdivided.reversed().reversed()))
				if (!java.util.Arrays.equals(equivalent.areaVector(), face.areaVector()) || !java.util.Arrays.equals(equivalent.centroidCoordinates(), face.centroidCoordinates())
						|| !equivalent.surface().equals(surface)) throw new AssertionError("Polygon ordering or retained clipping changed its exact integrals or observations");
			var reversed = subdivided.reversed(); var reverseArea = reversed.areaVector(); var forwardArea = subdivided.areaVector();
			for (int axis = 0; axis < 3; axis++) if (!reverseArea[axis].equals(forwardArea[axis].negate()))
				throw new AssertionError("Reversing a warmed polygon changed its oriented area");
			if (!java.util.Arrays.equals(reversed.centroidCoordinates(), face.centroidCoordinates()) || !reversed.plane().key().equals(face.plane().key())
					|| reversed.plane().positive() == face.plane().positive())
				throw new AssertionError("Reversing a warmed polygon changed its centroid or supporting plane");
		}
		moments(cube.moments(), 1, 0.5, 0.5, 0.5);
		moments(cube.clip(1, 1, 1, 1).moments(), 1.0 / 6, 1.0 / 24, 1.0 / 24, 1.0 / 24);
		for (double shift : new double[]{0, 0.125}) {
			var points = java.util.List.of(new LiquidPolyhedron.Point(shift + 0.125, 0.25, 0.375), new LiquidPolyhedron.Point(shift + 0.75, 0.375, 0.25),
					new LiquidPolyhedron.Point(shift + 0.375, 0.875, 0.25), new LiquidPolyhedron.Point(shift + 0.25, 0.5, 0.875));
			var vertices = points.stream().map(p -> LiquidExactPolygon.vertex(new LiquidExactPolygon.Fraction[]{LiquidExactPolygon.Fraction.of(p.x()), LiquidExactPolygon.Fraction.of(p.y()), LiquidExactPolygon.Fraction.of(p.z())})).toList();
			var surface = LiquidExactPolygon.tetrahedron(vertices); var measured = LiquidExactPolygon.measure(surface);
			if (!measured.same(LiquidExactPolygon.tetrahedronMeasure(vertices)) || !LiquidExactPolygon.volume(surface).equals(measured.volume()))
				throw new AssertionError("Planar surface integration differs from independent tetrahedron volume and centroid");
			var reversed = new java.util.ArrayList<>(vertices); java.util.Collections.swap(reversed, 1, 2);
			if (!LiquidExactPolygon.tetrahedronVolume(vertices).equals(LiquidExactPolygon.tetrahedronVolume(reversed))
					|| !LiquidExactPolygon.homogeneousTetrahedronDeterminant(vertices).equals(LiquidExactPolygon.homogeneousTetrahedronDeterminant(reversed).negate()))
				throw new AssertionError("Homogeneous tetrahedron determinant lost orientation or unsigned volume");
		}
		var rationalPoints = new java.util.ArrayList<LiquidExactPolygon.Vertex>();
		for (int point = 0; point < 4; point++) {
			var denominator = java.math.BigInteger.ONE.shiftLeft(80 + point).add(java.math.BigInteger.valueOf(2 * point + 1));
			var coordinates = new LiquidExactPolygon.Fraction[3];
			for (int axis = 0; axis < 3; axis++) coordinates[axis] = new LiquidExactPolygon.Fraction(
					point == axis + 1 ? denominator : java.math.BigInteger.valueOf(point + axis + 1), denominator);
			rationalPoints.add(LiquidExactPolygon.vertex(coordinates));
		}
		var rationalFaces = LiquidExactPolygon.tetrahedron(rationalPoints); var rationalMeasure = LiquidExactPolygon.measure(rationalFaces);
		if (!rationalMeasure.same(LiquidExactPolygon.tetrahedronMeasure(rationalPoints)) || !rationalMeasure.same(surfaceIntegralMeasure(rationalFaces)))
			throw new AssertionError("Common-denominator integration changed volume or first moments for independent rational vertices");
		for (double[] plane : new double[][] {{1, 0, 0, 0.25}, {1, 1, 1, 1.2}, {-2, 3, 1, 0.7}, {1, 1, 0, 1}, {0, 1, 0, 0}}) {
			var region = LiquidConvexRegion.of(cube); var cutter = new LiquidGeometryPlane(new LiquidPolyhedron.Point(plane[0], plane[1], plane[2]), plane[3]);
			if (!region.measure().isSum(region.clip(cutter).measure(), region.clip(cutter.opposite()).measure()))
				throw new AssertionError("Clipping lost exact volume or first moments");
			var lower = cube.clip(plane[0], plane[1], plane[2], plane[3]).moments();
			var upper = cube.clip(-plane[0], -plane[1], -plane[2], -plane[3]).moments();
			moments(lower.add(upper), 1, 0.5, 0.5, 0.5);
			for (var piece : java.util.List.of(region.clip(cutter), region.clip(cutter.opposite())))
				if (!piece.measure().same(surfaceIntegralMeasure(piece.faces()))) throw new AssertionError("Homogeneous spatial moments differ from independent surface divergence integrals");
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
				var exact = LiquidConvexRegion.EMPTY.measure();
				for (var cell : cells) {
					sum = sum.add(cell.moments()); exact = exact.add(cell.geometry().measure());
					for (var face : LiquidExactPolygon.faces(cell.geometry())) for (var vertex : face.vertices()) for (int axis = 0; axis < 3; axis++) {
						var coordinate = vertex.coordinates()[axis]; var lo = LiquidExactPolygon.Fraction.of(cell.minimum(axis));
						if (coordinate.subtract(lo).signum() < 0 || coordinate.subtract(lo.add(LiquidExactPolygon.Fraction.of(cell.width()))).signum() > 0)
							throw new AssertionError("Refined geometry leaves its dyadic cell");
					}
				}
				if (!exact.same(liquid.measure())) throw new AssertionError("Refinement lost exact volume or first moments");
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
		resolvedDomainUsesCollisionOpenSpace();
		resolvedGasSeparatesChambersInsideOneBaseCell();
		resolvedGasSurvivesCompleteCandidate();
		liquidSourcesUseExactOpenGeometry();
		inventoryCentroidUsesResolvedCollisionSupport();
		geometricSourceBatchPreservesActualCapacity();
		geometricExtractionUsesOccupiedSupportAndVelocity();
		exactCompactionPreservesGeometryAndPhysicalFields();
		materialInterfacesUseResolvedPhaseAndWallGeometry();
		disjointLiquidRetainsExistingGasRegions();
		System.out.println("Liquid geometry volume, centroid and local subdivision validation passed");
	}
	private static LiquidExactPolygon.Measure surfaceIntegralMeasure(java.util.List<LiquidExactPolygon> faces) {
		var volume = LiquidExactPolygon.Fraction.ZERO;
		var moment = new LiquidExactPolygon.Fraction[]{volume, volume, volume};
		for (var face : faces) {
			var area = face.areaVector(); var center = face.centroidCoordinates(); var contribution = LiquidExactPolygon.Fraction.ZERO;
			for (int axis = 0; axis < 3; axis++) contribution = contribution.add(area[axis].multiply(center[axis]));
			volume = volume.add(contribution);
			for (int axis = 0; axis < 3; axis++) moment[axis] = moment[axis].add(contribution.multiply(center[axis]));
		}
		for (int axis = 0; axis < 3; axis++) moment[axis] = moment[axis].divide(LiquidExactPolygon.Fraction.of(4));
		return new LiquidExactPolygon.Measure(volume.divide(LiquidExactPolygon.Fraction.of(3)), moment);
	}
	private static void mechanicalCellsCancelAuxiliaryCuts() {
		starKernelKeepsMeansAndAffineWork();
		physicalRefinementResolvesIndependentExtents();
		var cube = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75));
		var pieces = LiquidGeometryRefinement.children(cube);
		var unified = LiquidGeometryStateMapper.capture(pieces, 1, new int[]{0}, new int[pieces.size()], new double[3 * pieces.size()], new int[pieces.size()]);
		var independent = LiquidGeometryStateMapper.capture(pieces, 1, new int[]{0}, new int[pieces.size()]);
		for (var state : java.util.List.of(unified, independent)) {
			var cells = LiquidGeometryStateMapper.restore(state); var indices = java.util.stream.IntStream.range(0, cells.size()).boxed().toList();
			var controls = new LiquidMechanicalControlCells(state, cells, indices, java.util.Collections.nCopies(cells.size(), -1), java.util.Map.of(), 4096);
			if (controls.regions.size() != (state == unified ? 1 : 8)) throw new AssertionError("Auxiliary cuts and chosen mechanical resolution were conflated");
			var measure = LiquidConvexRegion.EMPTY.measure();
			for (var control : controls.regions) measure = measure.add(LiquidExactPolygon.measure(LiquidExactPolygon.faces(control.geometry())));
			if (!measure.same(cube.geometry().measure())) throw new AssertionError("Mechanical assembly changed exact volume or first moments");
			if (state == unified) {
				var refined = new LiquidMechanicalControlCells(state, cells, indices, java.util.Collections.nCopies(cells.size(), -1), java.util.Map.of(0, 1), 4096);
				if (refined.regions.size() != 8) throw new AssertionError("Explicit spatial refinement retained an unresolved control cell");
			}
		}
		var star = new java.util.ArrayList<LiquidGeometryRefinement.Cell>();
		star.add(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.375, 0.375, 0.375, 0.625, 0.625, 0.625)));
		for (int axis = 0; axis < 3; axis++) for (int side : new int[]{-1, 1}) {
			double[] lo = {0.375, 0.375, 0.375}, hi = {0.625, 0.625, 0.625};
			lo[axis] = side < 0 ? 0.125 : 0.625; hi[axis] = side < 0 ? 0.375 : 0.875;
			star.add(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2])));
		}
		var state = LiquidGeometryStateMapper.capture(star, 1, new int[]{0}, new int[star.size()], new double[3 * star.size()], new int[star.size()]);
		var actual = LiquidGeometryStateMapper.restore(state); var ids = java.util.stream.IntStream.range(0, actual.size()).boxed().toList();
		var controls = new LiquidMechanicalControlCells(state, actual, ids, java.util.Collections.nCopies(actual.size(), -1), java.util.Map.of(), 4096);
		if (controls.regions.size() != 1) throw new AssertionError("Star-shaped control cell retained auxiliary convex pressure unknowns");
		var mesh = new LiquidControlVolumeMesh(controls.regions, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var mean = new double[]{0.125, -0.25, 0.0625}; var rates = new LiquidExactPolygon.Fraction[mesh.faces.size()];
		for (int f = 0; f < rates.length; f++) {
			rates[f] = LiquidExactPolygon.Fraction.ZERO;
			for (int a = 0; a < 3; a++) rates[f] = rates[f].add(mesh.faces.get(f).areaVector().get(a).multiply(LiquidExactPolygon.Fraction.of(mean[a])));
		}
		var field = new LiquidConformingVelocity(mesh, rates, mean); var motion = new LiquidConformingTransport(mesh, field, rates, 1.0 / 128);
		var sum = LiquidConvexRegion.EMPTY.measure(); for (var image : motion.images) sum = sum.add(image.geometry().measure());
		var expected = LiquidExactPolygon.measure(LiquidExactPolygon.faces(controls.regions.getFirst().geometry().translated(mean[0] / 128, mean[1] / 128, mean[2] / 128)));
		if (!sum.same(expected)) throw new AssertionError("Non-convex pressure-cell fan lost exact rigid transport geometry");
	}
	private static void physicalRefinementResolvesIndependentExtents() {
		for (double height : new double[]{1, Math.scalb(1.0, -20), Math.scalb(1.0, -80)}) {
			var cell = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.125, 0, 0.25, 0.875, height, 0.75));
			var state = LiquidGeometryStateMapper.capture(java.util.List.of(cell), 1, new int[]{0}, new int[]{0});
			for (int depth : new int[]{1, 2}) {
				var refined = new LiquidMechanicalControlCells(state, java.util.List.of(cell), java.util.List.of(0), java.util.List.of(-1), java.util.Map.of(0, depth), 128);
				int count = depth == 1 ? 8 : 64;
				if (refined.regions.size() != count) throw new AssertionError("Physical refinement coupled the thin direction to collision-grid spacing");
				var measured = LiquidConvexRegion.EMPTY.measure(); var volume = cell.geometry().measure().volume().divide(LiquidExactPolygon.Fraction.of(count));
				for (int c = 0; c < count; c++) {
					var geometry = refined.regions.get(c).geometry(); measured = measured.add(geometry.measure());
					if (!geometry.measure().volume().equals(volume) || refined.fragments[c] != 0
							|| refined.support.get(c).stream().anyMatch(piece -> !LiquidPhasePartition.Address.of(piece).equals(LiquidPhasePartition.Address.of(cell))))
						throw new AssertionError("Physical refinement changed material ownership, positive volume or collision support");
				}
				if (!measured.same(cell.geometry().measure())) throw new AssertionError("Physical refinement changed exact occupancy volume or first moments");
				if (depth == 1) {
					var mesh = new LiquidControlVolumeMesh(refined.regions, 1, 1000, (c, face) -> new LiquidControlVolumeMesh.Boundary(false, 0));
					var rates = new LiquidExactPolygon.Fraction[mesh.faces.size()]; java.util.Arrays.fill(rates, LiquidExactPolygon.Fraction.ZERO);
					var field = new LiquidConformingVelocity(mesh, rates, new double[3 * count]);
					var coefficient = new double[rates.length]; java.util.Arrays.fill(coefficient, 1); field.requireSurfaceResolution(coefficient);
				}
			}
			try {
				new LiquidMechanicalControlCells(state, java.util.List.of(cell), java.util.List.of(0), java.util.List.of(-1), java.util.Map.of(0, 1), 7);
				throw new AssertionError("Physical refinement bypassed its positive-region budget");
			} catch (IllegalStateException expected) { if (!expected.getMessage().contains("positive-region budget")) throw expected; }
		}
	}
	private static void starKernelKeepsMeansAndAffineWork() {
		var cavity = java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 0.25, 1)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0.25, 0, 0.25, 1, 1)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.75, 0.25, 0, 1, 1, 1)));
		if (LiquidPolyhedron.restoredExact(LiquidExteriorGeometry.of(cavity)).kernelPoint() != null)
			throw new AssertionError("A non-star-shaped cavity acquired a fictitious positive fan kernel");
		nonStarControlsCancelAuxiliaryCuts(cavity);
		for (double height : new double[]{1, 1.0 / 1024}) {
			var pieces = java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 0.25, height)),
					new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0.25, 0, 0.25, 1, height)));
			var state = LiquidGeometryStateMapper.capture(pieces, 1, new int[]{0}, new int[2], new double[6], new int[2]);
			var controls = new LiquidMechanicalControlCells(state, pieces, java.util.List.of(0, 1), java.util.List.of(-1, -1), java.util.Map.of(), 4096);
			if (controls.regions.size() != 1) throw new AssertionError("A star-shaped control with an exterior centroid gained auxiliary pressure cells");
			var shape = controls.regions.getFirst().geometry(); var center = LiquidExactPolygon.vertex(shape.centroidCoordinates());
			var faces = LiquidExactPolygon.faces(shape); var kernel = shape.kernelPoint();
			if (faces.stream().noneMatch(face -> center.distance(face.coefficients()).signum() > 0)
					|| faces.stream().anyMatch(face -> kernel.distance(face.coefficients()).signum() >= 0))
				throw new AssertionError("The auxiliary fan point did not independently satisfy the actual surface half-spaces");
			var expected = LiquidVolumeInterface.measure(pieces.stream().map(LiquidGeometryRefinement.Cell::geometry).toList());
			if (!shape.measure().same(expected)) throw new AssertionError("Kernel selection changed volume or material first moments");
			var mesh = new LiquidControlVolumeMesh(controls.regions, 1, 1000, (c, face) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			var gradient = ControlVolumeGradient.build(mesh.cells, mesh.faces)[0];
			for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
				var identity = LiquidExactPolygon.Fraction.ZERO;
				for (int f = 0; f < gradient.indices.length; f++) identity = identity.add(gradient.exactBasis[f][a].multiply(gradient.exactDisplacement[f][b]));
				if (!identity.equals(LiquidExactPolygon.Fraction.of(a == b ? 1 : 0))) throw new AssertionError("Auxiliary support changed the affine gradient of cell means");
			}
			for (var weight : gradient.exactWeight) if (weight.signum() <= 0) throw new AssertionError("Auxiliary fan supplied a non-positive stabilization metric");
			var slope = new double[]{0.125, -0.25, 0.0625};
			var projected = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(slope, new LiquidPolyhedron.Point(0, 0, 0), 1.0 / 128, 1e-10, 512);
			for (int a = 0; a < 3; a++) if (Math.abs(projected.cellVelocity()[a] - slope[a]) > 1e-13)
				throw new AssertionError("Auxiliary support changed uniform physical momentum");
			var rates = new LiquidExactPolygon.Fraction[mesh.faces.size()];
			for (int f = 0; f < rates.length; f++) {
				rates[f] = LiquidExactPolygon.Fraction.ZERO;
				for (int a = 0; a < 3; a++) rates[f] = rates[f].add(mesh.faces.get(f).areaVector().get(a).multiply(LiquidExactPolygon.Fraction.of(slope[a])));
			}
			var field = new LiquidConformingVelocity(mesh, rates, slope); var moment = new LiquidExactPolygon.Fraction[3]; java.util.Arrays.fill(moment, LiquidExactPolygon.Fraction.ZERO);
			for (var tet : field.tetrahedra) {
				if (tet.volume().signum() <= 0) throw new AssertionError("Kernel fan has a non-positive integral weight");
				for (var point : tet.vertices()) for (int a = 0; a < 3; a++) moment[a] = moment[a].add(tet.volume().multiply(field.velocity.get(point).get(a)).divide(LiquidExactPolygon.Fraction.of(4)));
			}
			for (int a = 0; a < 3; a++) if (!moment[a].equals(expected.volume().multiply(LiquidExactPolygon.Fraction.of(slope[a]))))
				throw new AssertionError("Auxiliary fan changed the exact prescribed mean momentum");
			var motion = new LiquidConformingTransport(mesh, field, rates, 1.0 / 128); var measured = LiquidConvexRegion.EMPTY.measure();
			for (var image : motion.images) measured = measured.add(image.geometry().measure());
			if (!measured.same(shape.translated(slope[0] / 128, slope[1] / 128, slope[2] / 128).measure()))
				throw new AssertionError("Auxiliary kernel changed rigid transport volume or first moments");
		}
	}
	private static void nonStarControlsCancelAuxiliaryCuts(java.util.List<LiquidGeometryRefinement.Cell> cavity) {
		var expected = LiquidVolumeInterface.measure(cavity.stream().map(LiquidGeometryRefinement.Cell::geometry).toList());
		for (boolean fine : new boolean[]{false, true}) {
			var pieces = fine ? cavity.stream().flatMap(cell -> LiquidGeometryRefinement.children(cell).stream()).toList() : cavity;
			var state = LiquidGeometryStateMapper.capture(pieces, 1, new int[]{0}, new int[pieces.size()], new double[3 * pieces.size()], new int[pieces.size()]);
			var actual = LiquidGeometryStateMapper.restore(state); var ids = java.util.stream.IntStream.range(0, actual.size()).boxed().toList();
			var controls = new LiquidMechanicalControlCells(state, actual, ids, java.util.Collections.nCopies(actual.size(), -1), java.util.Map.of(), 4096);
			if (controls.regions.size() != 1) throw new AssertionError("Auxiliary positive subdivision changed the physical control count: " + controls.regions.size());
			var measure = LiquidConvexRegion.EMPTY.measure(); var retained = new java.util.HashSet<LiquidGeometryRefinement.Cell>();
			for (int c = 0; c < controls.regions.size(); c++) {
				var geometry = controls.regions.get(c).geometry();
				if (geometry.kernelPoint() != null) throw new AssertionError("Concave material lost its complete physical geometry");
				measure = measure.add(geometry.measure());
				for (var piece : controls.support.get(c)) if (!retained.add(piece)) throw new AssertionError("Agglomerated control duplicated positive material support");
			}
			if (!measure.same(expected) || retained.size() != actual.size() || !retained.containsAll(actual))
				throw new AssertionError("Agglomeration changed exact occupancy, volume or first moments");
			var mesh = new LiquidControlVolumeMesh(controls.regions, controls.support, 1, 1000, (c, face) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			if (mesh.faces.stream().anyMatch(face -> face.second() >= 0) || mesh.subdivision.size() < 2)
				throw new AssertionError("Auxiliary faces acquired independent mechanical rates");
			var mean = new double[3 * mesh.cells.size()]; var u = new double[]{0.125, -0.25, 0.0625};
			for (int c = 0; c < mesh.cells.size(); c++) System.arraycopy(u, 0, mean, 3 * c, 3);
			var rates = new LiquidExactPolygon.Fraction[mesh.faces.size()];
			for (int f = 0; f < rates.length; f++) {
				rates[f] = LiquidExactPolygon.Fraction.ZERO;
				for (int a = 0; a < 3; a++) rates[f] = rates[f].add(mesh.faces.get(f).areaVector().get(a).multiply(LiquidExactPolygon.Fraction.of(u[a])));
			}
			var gravity = new LiquidPolyhedron.Point(0, -9.81, 0); double dt = 1.0 / 128;
			var projected = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(mean, gravity, dt, 1e-10, 512);
			for (int a = 0; a < 3; a++) if (Math.abs(projected.cellVelocity()[a] - u[a] - dt * gravity.coordinate(a)) > 1e-13)
				throw new AssertionError("A concave control changed its complete gravitational inertia");
			var faceVelocity = new double[3 * mesh.faces.size()]; for (int f = 0; f < mesh.faces.size(); f++) System.arraycopy(u, 0, faceVelocity, 3 * f, 3);
			var viscous = new LiquidControlVolumeViscosity(mesh.cells, mesh.faces, new double[]{1}, FluidSolverConfig.WallCondition.FREE_SLIP)
					.advance(mean, faceVelocity, dt, 1e-10, 512);
			if (!java.util.Arrays.equals(viscous.cellVelocity(), mean) || !java.util.Arrays.equals(viscous.faceVelocity(), faceVelocity)
					|| viscous.dissipatedJoules() != 0 || viscous.temporalDissipationJoules() != 0)
				throw new AssertionError("A concave control acquired viscous stress under rigid translation");
			var field = new LiquidConformingVelocity(mesh, rates, mean); var motion = new LiquidConformingTransport(mesh, field, rates, 1.0 / 128);
			var imageMeasure = LiquidConvexRegion.EMPTY.measure(); for (var image : motion.images) imageMeasure = imageMeasure.add(image.geometry().measure());
			var target = LiquidConvexRegion.EMPTY.measure(); for (var piece : cavity) target = target.add(piece.geometry().translated(u[0] / 128, u[1] / 128, u[2] / 128).measure());
			if (!imageMeasure.same(target)) throw new AssertionError("Non-star agglomeration changed exact rigid transport");
		}
	}
	private static void separatePressureSupportsPreserveIndependentVolumes() {
		var first = LiquidPolyhedron.box(0, 0, 0, 0.25, 0.25, 0.25);
		for (int contact = 0; contact < 3; contact++) {
			var second = contact == 0 ? LiquidPolyhedron.box(0.75, 0, 0, 1, 0.25, 0.25)
					: contact == 1 ? LiquidPolyhedron.box(0.25, 0.25, 0, 0.5, 0.5, 0.25) : LiquidPolyhedron.box(0.25, 0.125, 0, 0.5, 0.375, 0.25);
			var pieces = java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, first), new LiquidGeometryRefinement.Cell(0, 0, 0, 0, second));
			var state = LiquidGeometryStateMapper.capture(pieces, 1, new int[]{0}, new int[2], new double[6], new int[2]);
			var controls = new LiquidMechanicalControlCells(state, pieces, java.util.List.of(0, 1), java.util.List.of(-1, -1), java.util.Map.of(), 64);
			if (controls.regions.size() != (contact == 2 ? 1 : 2)) throw new AssertionError("Pressure support crossed a zero-area contact or split a positive-area passage");
			if (contact != 0) continue;
			var mesh = new LiquidControlVolumeMesh(controls.regions, controls.support, 1, 1,
					(c, face) -> new LiquidControlVolumeMesh.Boundary(false, face.centroid().x() < 0.5 ? 1 : 2));
			var result = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[6], new LiquidPolyhedron.Point(0, 0, 0), 0.01, 1e-12, 128);
			for (var rate : result.volumeRate()) if (Math.abs(rate.rounded()) > 1e-12)
				throw new AssertionError("Disconnected pressure supports exchanged volume despite separate constant equilibrium pressures");
			for (var speed : result.cellVelocity()) if (Math.abs(speed) > 1e-12) throw new AssertionError("Independent static pressure generated liquid momentum");
		}
	}
	private static void mechanicalFacetsIgnoreAuxiliaryGasCuts() {
		var body = LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var regions = LiquidPhasePartition.build(root, java.util.List.of(body), 64);
		for (boolean finer : new boolean[]{false, true}) for (boolean subdivided : new boolean[]{false, true}) {
			var cells = new java.util.ArrayList<LiquidGeometryRefinement.Cell>(); cells.add(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, body));
			for (var region : regions) if (region.liquid() < 0) {
				var gas = finer ? LiquidGeometryRefinement.children(region.cell()) : java.util.List.of(region.cell());
				for (var part : gas) cells.add(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, part.geometry()));
			}
			LiquidPhasePartition.requireComplete(root, cells);
			var phase = subdivided ? new LiquidControlVolumeMesh(cells, cells.stream().map(java.util.List::of).toList(), 1, 1000, (c, surface) -> new LiquidControlVolumeMesh.Boundary(true, 0))
					: new LiquidControlVolumeMesh(cells, 1, 1000, (c, surface) -> new LiquidControlVolumeMesh.Boundary(true, 0));
			var liquid = new int[cells.size()]; java.util.Arrays.fill(liquid, -1); liquid[0] = 0;
			var rows = new int[cells.size()]; java.util.Arrays.fill(rows, -1); var contacts = new int[cells.size()]; contacts[0] = -1;
			var coefficient = new double[phase.faces.size()];
			for (int f = 0; f < coefficient.length; f++) {
				var face = phase.faces.get(f);
				if (liquid[face.first()] >= 0 || face.second() >= 0 && liquid[face.second()] >= 0) coefficient[f] = 0.125;
			}
			var physical = new LiquidMechanicalFaceInput(phase, liquid, contacts, rows, coefficient, (f, gas) -> 0);
			if (physical.mesh.faces.size() != 6 || physical.mesh.exactPolygons.stream().anyMatch(p -> p.vertices().size() != 4))
				throw new AssertionError("Auxiliary gas cuts changed physical pressure facets or interface velocity nodes");
			for (int f = 0; f < physical.mesh.faces.size(); f++) {
				if (physical.contactNodes[f] != 0 || physical.pressureNodes[f] != -1 || physical.surfaceCoefficient[f] != 0.125)
					throw new AssertionError("Facet union changed gas contact, pressure support or surface coefficient");
				var polygon = physical.mesh.exactPolygons.get(f); var original = LiquidExactPolygon.faces(body).stream().filter(p -> p.plane().key().equals(polygon.plane().key())).findFirst().orElseThrow();
				if (!original.vertexKey().equals(polygon.vertexKey())) throw new AssertionError("Physical facet union changed the occupied interface");
			}
			var mean = new double[]{0.125, -0.25, 0.5};
			var projected = new LiquidControlVolumeProjection(physical.mesh.cells, physical.mesh.faces).project(mean, new LiquidPolyhedron.Point(0, 0, 0), 0.01, 1e-10, 512);
			var field = new LiquidConformingVelocity(physical.mesh, projected.volumeRate(), projected.cellVelocity());
			for (var velocity : field.velocity.values()) for (int a = 0; a < 3; a++)
				if (!velocity.get(a).equals(LiquidExactPolygon.Fraction.of(mean[a]))) throw new AssertionError("Auxiliary gas geometry changed rigid liquid motion");
		}
	}
	private static void controlCellsPreserveChosenMechanicalResolution() {
		var a = new LiquidGeometryRefinement.Cell(1, 0, 0, 0, LiquidPolyhedron.box(0.25, 0.125, 0.125, 0.5, 0.375, 0.375));
		var b = new LiquidGeometryRefinement.Cell(1, 1, 0, 0, LiquidPolyhedron.box(0.5, 0.125, 0.125, 0.75, 0.375, 0.375));
		var first = LiquidGeometryStateMapper.capture(java.util.List.of(a, b), 1, new int[]{0}, new int[]{0, 0}, new double[6], new int[]{0, 0});
		var reversed = LiquidGeometryStateMapper.capture(java.util.List.of(b, a), 1, new int[]{1}, new int[]{1, 1}, new double[6], new int[]{1, 1});
		if (!java.util.Arrays.equals(first.copyControlCells(), reversed.copyControlCells())) throw new AssertionError("Mechanical resolution depends on fragment enumeration");
		int[] returned = first.copyControlCells(); returned[1] = 1;
		if (first.copyControlCells()[1] != 0) throw new AssertionError("Control-cell support exposed mutable state");
		var inserted = new LiquidGeometryInsertionPlan(first, 2, 2, 2, 0.5F, new byte[8],
				java.util.List.of(new LiquidGeometryInsertionPlan.Insertion(0, 1, 0, 0.125F)), 256).geometry;
		var owners = inserted.copyFragmentOwners(); var controls = inserted.copyControlCells(); int oldOwner = inserted.copyMarkerCells()[0];
		var oldControls = new java.util.HashSet<Integer>(); for (int f = 0; f < owners.length; f++) if (owners[f] == oldOwner) oldControls.add(controls[f]);
		if (oldControls.size() != 1) throw new AssertionError("Source remapping converted auxiliary cuts into independent control cells");
		try {
			LiquidGeometryStateMapper.capture(java.util.List.of(a, b), 1, new int[]{0}, new int[]{0, 0}, new double[]{1, 0, 0, -1, 0, 0}, new int[]{0, 0});
			throw new AssertionError("One control cell accepted inconsistent resolved velocities");
		} catch (IllegalArgumentException expected) { }
	}
	private static void canonicalGeometryProofSurvivesPacking() {
		var geometry = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(-2, 3, 1, 0.7);
		var faces = LiquidExactPolygon.faces(geometry).stream().map(face -> face.cycled(face.vertices().size() - 1)).toList().reversed();
		var cell = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.restoredExact(faces));
		LiquidGeometryState state; LiquidGeometryRefinement.Cell warm;
		try (var ignored = LiquidGeometryStateMapper.openReadScope()) {
			state = LiquidGeometryStateMapper.capture(java.util.List.of(cell), 1, new int[]{0});
			warm = LiquidGeometryStateMapper.restore(state).getFirst();
		}
		var cold = LiquidGeometryStateMapper.restore(state).getFirst();
		var warmFaces = LiquidExactPolygon.faces(warm.geometry()); var coldFaces = LiquidExactPolygon.faces(cold.geometry());
		if (!warm.geometry().measure().same(cold.geometry().measure()) || warmFaces.size() != coldFaces.size()) throw new AssertionError("Packed geometry changed exact moments across proof reuse");
		for (int f = 0; f < warmFaces.size(); f++) if (!warmFaces.get(f).vertices().equals(coldFaces.get(f).vertices()) || !warmFaces.get(f).coefficients().equals(coldFaces.get(f).coefficients()))
			throw new AssertionError("Packed geometry proof changed canonical face or vertex order");
		var points = java.util.List.of(new LiquidPolyhedron.Point(0, 0, 0), new LiquidPolyhedron.Point(1, 1, 0), new LiquidPolyhedron.Point(0, 1, 0), new LiquidPolyhedron.Point(0.75, 0, 0));
		var crossing = points.stream().map(p -> LiquidExactPolygon.vertex(new LiquidExactPolygon.Fraction[]{LiquidExactPolygon.Fraction.of(p.x()), LiquidExactPolygon.Fraction.of(p.y()), LiquidExactPolygon.Fraction.of(p.z())})).toList();
		try {
			LiquidExactPolygon.restored(crossing, java.util.List.of(java.math.BigInteger.ZERO, java.math.BigInteger.ZERO, java.math.BigInteger.ONE, java.math.BigInteger.ZERO));
			throw new AssertionError("Positive signed area admitted a self-intersecting face");
		} catch (IllegalArgumentException expected) { }
	}
	private static void disjointLiquidRetainsExistingGasRegions() {
		var leaf = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var liquids = java.util.List.of(LiquidPolyhedron.box(0, 0, 0.25, 1, 1, 0.5), LiquidPolyhedron.box(0.25, 0.25, 0.75, 0.75, 0.75, 1));
		var regions = LiquidPhasePartition.build(leaf, liquids, 128); var lower = LiquidConvexRegion.of(LiquidPolyhedron.box(0, 0, 0, 1, 1, 0.25));
		boolean retained = false;
		for (var region : regions) if (region.liquid() < 0) {
			var geometry = LiquidConvexRegion.of(region.cell().geometry());
			if (geometry.measure().same(lower.measure()) && lower.faces().stream().allMatch(face -> geometry.faces().stream().allMatch(p -> p.inside(face.coefficients())))) retained = true;
		}
		if (!retained) throw new AssertionError("Disjoint liquid fragmented an unchanged gas region");
		var shape = LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);
		var mesh = new LiquidControlVolumeMesh(java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, shape)), 1, 1000,
				(c, face) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var rates = new LiquidExactPolygon.Fraction[mesh.faces.size()]; java.util.Arrays.fill(rates, LiquidExactPolygon.Fraction.ZERO);
		var tets = new LiquidConformingVelocity(mesh, rates, new double[3]).tetrahedra.stream()
				.map(tet -> LiquidConvexRegion.ofTetrahedron(tet.vertices()).geometry()).toList();
		var actualPlanes = new java.util.HashSet<LiquidGeometryPlane.Key>();
		for (var geometry : java.util.List.of(leaf.geometry(), shape)) for (var plane : geometry.planes()) actualPlanes.add(plane.key());
		for (var region : LiquidPhasePartition.build(leaf, tets, 128)) if (region.liquid() < 0)
			for (var plane : region.cell().geometry().planes()) if (!actualPlanes.contains(plane.key()))
				throw new AssertionError("Internal liquid tetrahedra introduced a gas partition plane");
	}
	private static void materialInterfacesUseResolvedPhaseAndWallGeometry() {
		var registry = new FluidMaterialRegistry(java.util.List.of(
				FluidMaterial.withSurfaceTension("mmm:first", 1000, "mmm:first", 0.125, 60),
				FluidMaterial.withSurfaceTension("mmm:second", 900, "mmm:second", 0.25, 120)),
				java.util.List.of(new FluidMaterialRegistry.LiquidPairTension(0, 1, 0.375)));
		var domain = new FluidDomain(2, 2, 2, FluidSolverConfig.prototypeDefaults(), 64);
		domain.setVolume(0, 0, 0, 1); domain.setVolume(1, 0, 0, 1); domain.setMaterialInCell(1, 0, 0, registry, 1);
		var cells = java.util.List.of(new LiquidGeometryRefinement.Cell(1, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.5, 0.5, 0.5)),
				new LiquidGeometryRefinement.Cell(1, 1, 0, 0, LiquidPolyhedron.box(0.5, 0, 0, 1, 0.5, 0.5)));
		var markers = new int[domain.particles.count()]; for (int p = 8; p < markers.length; p++) markers[p] = 1;
		var state = LiquidGeometryStateMapper.capture(cells, 2 * (double) domain.config.cellSize(), markers);
		var partition = new LiquidGeometryDomainPartition(2, 2, 2, domain.config.cellSize(), domain.geometry.cutCells, cells, 128);
		var input = new LiquidGeometryPhaseInput(state, partition, true, 128); var properties = new LiquidGeometryCarrierProperties(domain, state, input);
		boolean pair = false, gas = false, wet = false, nonwet = false;
		for (int f = 0; f < input.mesh.faces.size(); f++) {
			var face = input.mesh.faces.get(f); double coefficient = properties.surfaceCoefficient[f];
			if (face.second() >= 0 && input.fragments[face.first()] >= 0 && input.fragments[face.second()] >= 0) {
				if (coefficient != 0.375) throw new AssertionError("Liquid pair energy lost its actual shared interface"); pair = true;
			} else if (face.second() == -2 && input.fragments[face.first()] >= 0) {
				wet |= coefficient < 0; nonwet |= coefficient > 0;
			} else if (coefficient > 0) {
				if (coefficient != 0.125 && coefficient != 0.25) throw new AssertionError("Liquid-gas energy lost its material phase"); gas = true;
			}
		}
		if (!(pair && gas && wet && nonwet)) throw new AssertionError("Resolved material properties omitted an interface or wall energy");
		try {
			var merged = LiquidGeometryStateMapper.capture(cells, state.rootWidthMetres(), new int[markers.length], new int[]{0, 0});
			new LiquidGeometryCarrierProperties(domain, merged, new LiquidGeometryPhaseInput(merged, partition, true, 128));
			throw new AssertionError("Immiscible inventory was accepted without its phase interface");
		} catch (IllegalArgumentException expected) { }
	}
	private static void resolvedGasSurvivesCompleteCandidate() {
		var config = FluidSolverConfig.prototypeDefaults(); float h = config.cellSize();
		byte[] collision = new byte[8]; java.util.Arrays.fill(collision, (byte) 0xff); collision[0] = 0;
		var sheet = new LiquidGeometryRefinement.Cell(1, 0, 0, 0,
				LiquidPolyhedron.box(0, 0.25 - 1.0 / 1024, 0, 0.5, 0.25 + 1.0 / 1024, 0.5));
		var geometry = LiquidGeometryStateMapper.capture(java.util.List.of(sheet), 2 * (double) h, new int[]{0});
		var liquid = new FlipParticleState(2, 2, 2, h, 1, 1.0F / 256,
				new float[]{0.5F * h, 0.5F * h, 0.5F * h, 1.0F / 256}, new float[3], collision, geometry);
		var domain = new FluidDomain(2, 2, 2, config); domain.restoreParticleState(liquid); domain.sealDomainBoundary();
		var gas = domain.bootstrapGasState("");
		if (gas.geometry() == null || gas.nodes().size() != 2 || !domain.gasState.network.auditClosed())
			throw new AssertionError("Resolved gas bootstrap did not initialize two actual finite chambers");
		var input = domain.captureAuthoritativeState(); var restored = FluidDomain.restoreAuthoritativeState(input);
		var after = restored.exportGasState("");
		if (after.geometry() != gas.geometry() || !after.nodes().equals(gas.nodes())
				|| !after.externalSource().equals(gas.externalSource()) || !after.escapedSink().equals(gas.escapedSink())
				|| !restored.gasState.network.auditClosed()) throw new AssertionError("Complete candidate lost resolved gas support or component inventory");
		for (int n = 1; n <= 2; n++) if (restored.gasNodeGaugePressurePa(n) != domain.gasNodeGaugePressurePa(n)
				|| restored.gasState.network.availableVolume(n) != domain.gasState.network.availableVolume(n))
			throw new AssertionError("Restored gas EOS used a coarse cell capacity");
		var rejected = new FluidDomain(2, 2, 2, config); rejected.restoreParticleState(liquid); rejected.sealDomainBoundary();
		var joined = new cc.ecisr.mmm.fluid.air.GasGeometryState(geometry.rootWidthMetres(), gas.geometry().cells(),
				new int[gas.geometry().cells().size()], 0, true);
		var invalid = cc.ecisr.mmm.fluid.air.GasStateSnapshot.captureResolved(2, 2, 2, joined,
				new cc.ecisr.mmm.fluid.air.AirNetwork(), "");
		try { rejected.restoreGasState(invalid, domain.gasState.network.registry()); throw new AssertionError("Gas restore accepted inconsistent chamber support"); }
		catch (IllegalArgumentException expected) {
			if (rejected.gasState.cellNodeId != null || rejected.gasState.geometry != null || rejected.gasState.network.nodeCount() != 0)
				throw new AssertionError("Rejected gas restore changed the live topology");
		}
	}
	private static void resolvedGasSeparatesChambersInsideOneBaseCell() {
		var collision = new FlipCutCellGeometry(2, 2, 2);
		for (int c = 1; c < 8; c++) collision.replaceMask(c, 0xff);
		var sheet = new LiquidGeometryRefinement.Cell(1, 0, 0, 0,
				LiquidPolyhedron.box(0, 0.25 - 1.0 / 1024, 0, 0.5, 0.25 + 1.0 / 1024, 0.5));
		var state = LiquidGeometryStateMapper.capture(java.util.List.of(sheet), 1, new int[]{0});
		var domain = new LiquidGeometryDomainPartition(2, 2, 2, 0.5F, collision, java.util.List.of(sheet), 128);
		for (boolean sealed : new boolean[]{false, true}) {
			var input = new LiquidGeometryPhaseInput(state, domain, sealed, 256); var gas = new ResolvedGasGeometry(input);
			var primitive = new LiquidExactPolygon.Fraction[gas.volumes.length]; java.util.Arrays.fill(primitive, LiquidExactPolygon.Fraction.ZERO);
			for (int c = 0; c < gas.phaseNodes.length; c++) if (gas.phaseNodes[c] >= 0) primitive[gas.phaseNodes[c]] = primitive[gas.phaseNodes[c]].add(input.mesh.cells.get(c).geometry().measure().volume());
			if (!java.util.Arrays.equals(gas.volumes, primitive) || !java.util.Arrays.equals(gas.volumes, ResolvedGasGeometry.volumes(gas.state)))
				throw new AssertionError("Gas exterior and primitive capacity integrals disagree");
			if (gas.state.nodeCount() != (sealed ? 2 : 0)) throw new AssertionError("Gas topology lost a chamber inside one base cell");
			var volume = LiquidExactPolygon.Fraction.ZERO; for (var v : gas.volumes) volume = volume.add(v);
			if (!volume.add(LiquidConvexRegion.of(sheet.geometry()).measure().volume()).equals(LiquidExactPolygon.Fraction.of(0.125)))
				throw new AssertionError("Resolved gas changed exact remaining space");
			int[] labels = gas.state.nodeOrdinals(); if (labels.length > 0) labels[0] = -1;
			for (int node : gas.state.nodeOrdinals()) if (node < 0) throw new AssertionError("Gas support exposed a mutable membership array");
			for (int c = 0; c < input.fragments.length; c++) if ((input.fragments[c] >= 0) != (gas.phaseNodes[c] == -1))
				throw new AssertionError("Pressure gas membership differs from its actual phase geometry");
			if (sealed && (!gas.volumes[1].equals(gas.volumes[2]) || gas.state.topologyKey(1) >= gas.state.topologyKey(2)))
				throw new AssertionError("Gas chamber keys or volumes are inconsistent");
			var refinedDomain = new LiquidGeometryDomainPartition(2, 2, 2, 0.5F, collision, LiquidGeometryRefinement.children(sheet), 256);
			var refined = new LiquidGeometryPhaseInput(state, refinedDomain, sealed, 512);
			var savedLabels = ResolvedGasGeometry.requireCompatible(gas.state, refined, 4096);
			var stored = new LiquidGeometryPhaseInput(state, refinedDomain, gas.state, 512);
			var directLabels = ResolvedGasGeometry.requireCompatible(gas.state, stored, 0);
			for (int c = 0; c < directLabels.length; c++) if (stored.fragments[c] < 0) {
				int expected = sealed ? stored.mesh.cells.get(c).geometry().centroid().y() < 0.25 ? 1 : 2 : 0;
				if (directLabels[c] != expected) throw new AssertionError("Stored gas refinement changed a chamber identity");
			}
			for (int c = 0; c < savedLabels.length; c++) if (refined.fragments[c] < 0) {
				int expected = sealed ? refined.mesh.cells.get(c).geometry().centroid().y() < 0.25 ? 1 : 2 : 0;
				if (savedLabels[c] != expected) throw new AssertionError("Refinement changed an actual gas chamber's inventory identity");
			}
			try {
				ResolvedGasGeometry.requireCompatible(gas.state, refined, 0);
				throw new AssertionError("Gas support matching ignored its exact-intersection work budget");
			} catch (IllegalStateException expected) { }
			if (sealed) {
				var joined = new cc.ecisr.mmm.fluid.air.GasGeometryState(1, gas.state.cells(), new int[gas.state.cells().size()], 0, true);
				try {
					ResolvedGasGeometry.requireCompatible(joined, refined, 4096);
					throw new AssertionError("Saved gas support joined two independent sealed chambers");
				} catch (IllegalArgumentException expected) { }
			}
		}
		var weights = ResolvedGasOverlap.weights(java.util.List.of(LiquidExactPolygon.Fraction.of(1).divide(LiquidExactPolygon.Fraction.of(3)),
				LiquidExactPolygon.Fraction.of(1).divide(LiquidExactPolygon.Fraction.of(7))));
		if (!weights.equals(java.util.List.of(java.math.BigInteger.valueOf(7), java.math.BigInteger.valueOf(3))))
			throw new AssertionError("Gas overlap allocation rounded rational geometry weights");
	}
	private static void exactCompactionPreservesGeometryAndPhysicalFields() {
		var a = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.125, 0.125, 0.125, 0.375, 0.375, 0.375));
		var b = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.375, 0.125, 0.125, 0.625, 0.375, 0.375));
		var c = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.625, 0.125, 0.125, 0.875, 0.375, 0.375));
		var input = LiquidGeometryStateMapper.capture(java.util.List.of(a, b, c), 1, new int[]{0}, new int[]{0, 0, 0}, new double[]{1, 0, 0, 1, 0, 0, 1, 0, 0}, new int[]{0, 0, 0});
		for (int budget : new int[]{0, 1, 64}) {
			var compact = LiquidGeometryCompaction.compact(input, budget); var measure = LiquidConvexRegion.EMPTY.measure();
			for (var cell : LiquidGeometryStateMapper.restore(compact)) measure = measure.add(LiquidConvexRegion.of(cell.geometry()).measure());
			if (!measure.same(LiquidConvexRegion.of(LiquidPolyhedron.box(0.125, 0.125, 0.125, 0.875, 0.375, 0.375)).measure()))
				throw new AssertionError("Exact compaction changed volume or spatial moments");
			for (var cell : compact.cells()) if (!java.util.Arrays.equals(cell.copyVelocity(), new double[]{1, 0, 0}))
				throw new AssertionError("Exact compaction changed the resolved field");
			if (budget == 64 && compact.cells().size() != 1) throw new AssertionError("Convex auxiliary cuts retained unnecessary geometric work");
			if (budget == 64) {
				var merged = LiquidGeometryStateMapper.restore(compact).getFirst().geometry();
				var box = LiquidPolyhedron.box(0.125, 0.125, 0.125, 0.875, 0.375, 0.375);
				for (double[] cut : new double[][]{{0, 0, -1, -0.25}, {1, 1, 1, 0.75}, {1, 2, 3, 1.5}, {-2, 1, 3, 0.25}}) {
					var clipped = merged.clip(cut[0], cut[1], cut[2], cut[3]);
					if (!clipped.measure().same(box.clip(cut[0], cut[1], cut[2], cut[3]).measure()))
						throw new AssertionError("Clipping a subdivided convex surface changed exact volume or moments");
					LiquidGeometryStateMapper.restore(LiquidGeometryStateMapper.capture(java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, clipped)), 1, new int[]{0}));
				}
			}
		}
		var concave = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.375, 0.125, 0.125, 0.625, 0.625, 0.375));
		var gap = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(Math.nextUp(0.375), 0.125, 0.125, 0.625, 0.375, 0.375));
		for (var neighbor : java.util.List.of(concave, gap)) {
			var shape = LiquidGeometryStateMapper.capture(java.util.List.of(a, neighbor), 1, new int[]{0}, new int[]{0, 0}, new double[6], new int[]{0, 0});
			if (LiquidGeometryCompaction.compact(shape, 64) != shape) throw new AssertionError("Compaction filled a concavity or a finite gas gap");
		}
		var different = LiquidGeometryStateMapper.capture(java.util.List.of(a, b), 1, new int[]{0}, new int[]{0, 0}, new double[]{1, 0, 0, -1, 0, 0});
		if (LiquidGeometryCompaction.compact(different, 64) != different) throw new AssertionError("Compaction averaged a resolved velocity difference");
		var separate = LiquidGeometryStateMapper.capture(java.util.List.of(a, b), 1, new int[]{0, 1});
		if (LiquidGeometryCompaction.compact(separate, 64) != separate) throw new AssertionError("Compaction combined independent material inventories");
		var independentControls = LiquidGeometryStateMapper.capture(java.util.List.of(a, b), 1, new int[]{0}, new int[]{0, 0});
		if (LiquidGeometryCompaction.compact(independentControls, 64) != independentControls) throw new AssertionError("Compaction combined independent mechanical control cells");
		var refined = LiquidGeometryStateMapper.capture(java.util.List.of(
				new LiquidGeometryRefinement.Cell(1, 0, 0, 0, LiquidPolyhedron.box(0.25, 0.125, 0.125, 0.5, 0.375, 0.375)),
				new LiquidGeometryRefinement.Cell(1, 1, 0, 0, LiquidPolyhedron.box(0.5, 0.125, 0.125, 0.75, 0.375, 0.375))),
				1, new int[]{0}, new int[]{0, 0});
		if (LiquidGeometryCompaction.compact(refined, 64) != refined) throw new AssertionError("Compaction removed the chosen spatial refinement");
	}
	private static void geometricExtractionUsesOccupiedSupportAndVelocity() {
		var domain = new FluidDomain(4, 4, 4, FluidSolverConfig.prototypeDefaults()); domain.setVolume(1, 1, 1, 0.125F);
		var left = new LiquidGeometryRefinement.Cell(2, 0, 1, 1, LiquidPolyhedron.box(0.125, 0.25, 0.25, 0.1875, 0.375, 0.375));
		var right = new LiquidGeometryRefinement.Cell(2, 1, 1, 1, LiquidPolyhedron.box(0.4375, 0.25, 0.25, 0.5, 0.375, 0.375));
		domain.liquidGeometryState = LiquidGeometryStateMapper.capture(java.util.List.of(left, right), 4 * (double) domain.config.cellSize(),
				new int[]{0}, new int[]{0, 0}, new double[]{1, 0, 0, -1, 0, 0});
		var before = domain.exportParticleState();
		if (domain.tryExtractExactVolume(0, 1, 1, 0.125F, 0).transferred()
				|| domain.liquidGeometryState != before.liquidGeometry() || !java.util.Arrays.equals(before.copyPositionVolume(), domain.exportParticleState().copyPositionVolume()))
			throw new AssertionError("Extraction used the whole inventory group instead of its actual local support");
		var transfer = domain.tryExtractExactVolume(0, 1, 1, 0.03125F, 0);
		if (!transfer.transferred() || transfer.volume() != 0.03125 || domain.totalVolumeExact() != 0.09375
				|| Math.abs(domain.particles.velocityX(0) + 1.0 / 3) > Math.ulp(domain.particles.velocityX(0)))
			throw new AssertionError("Partial extraction lost occupied geometry or retained momentum");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		if (domain.removeParticlesInCell(0, 1, 1, Float.POSITIVE_INFINITY) != 0.03125F)
			throw new AssertionError("Single-cell removal did not use the remaining actual local support");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		if (domain.particles.positionX(0) != 1.875F * domain.config.cellSize() || domain.particles.velocityX(0) != -1)
			throw new AssertionError("Spatial extraction retained the old group centroid or velocity");
		if (domain.removeParticlesInCell(1, 1, 1, Float.POSITIVE_INFINITY) != 0.0625F || !domain.liquidGeometryState.cells().isEmpty())
			throw new AssertionError("Complete extraction retained empty inventory geometry");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		var shared = new FluidDomain(4, 4, 4, FluidSolverConfig.prototypeDefaults()); shared.setVolume(1, 1, 1, 0.125F);
		double mass = shared.particles.mass(0) / 2; float h = shared.config.cellSize();
		shared.particles.setVolume(0, 0.0625F); shared.particles.setMass(0, mass);
		shared.carrierMutations().appendMarker(CarrierMutationPlan.append(shared.carrierMutationRevision(), 1), shared.cellIndex(1, 1, 1),
				1.25F * h, 1.25F * h, 1.25F * h, 0.0625F, 0, mass, null, false, false);
		shared.liquidGeometryState = LiquidGeometryStateMapper.capture(java.util.List.of(left, right), 4 * (double) h,
				new int[]{0, 0}, new int[]{0, 0}, new double[]{1, 0, 0, -1, 0, 0});
		if (!shared.tryExtractExactVolume(0, 1, 1, 0.0625F, 0).transferred() || shared.particles.count() != 1
				|| shared.particles.velocityX(0) != -1 || shared.particles.positionX(0) != 1.875F * h)
			throw new AssertionError("Shared inventory group lost spatial ownership during marker removal");
		LiquidGeometryStateMapper.requireCompatible(shared.liquidGeometryState, shared);
	}
	private static void inventoryCentroidUsesResolvedCollisionSupport() {
		var domain = new FluidDomain(4, 4, 4, FluidSolverConfig.prototypeDefaults()); domain.setVolume(1, 1, 1, 0.125F);
		var left = new LiquidGeometryRefinement.Cell(2, 0, 1, 1, LiquidPolyhedron.box(0.125, 0.25, 0.25, 0.1875, 0.375, 0.375));
		var right = new LiquidGeometryRefinement.Cell(2, 1, 1, 1, LiquidPolyhedron.box(0.4375, 0.25, 0.25, 0.5, 0.375, 0.375));
		domain.liquidGeometryState = LiquidGeometryStateMapper.capture(java.util.List.of(left, right), 4 * (double) domain.config.cellSize(), new int[] {0}, new int[] {0, 0});
		domain.geometry.cutCells.replaceMask(domain.cellIndex(1, 1, 1), 1);
		var exported = domain.exportParticleState(); var restored = FluidDomainStateMapper.restore(FluidDomainStateMapper.capture(domain));
		if (exported.liquidGeometry() != domain.liquidGeometryState || restored.liquidGeometryState == null)
			throw new AssertionError("Inventory handoff lost actual liquid support across an occupied centroid");
		var badPosition = exported.copyPositionVolume(); badPosition[0] += 0.125F;
		var inconsistent = new FlipParticleState(4, 4, 4, domain.config.cellSize(), 1, 0.125F, badPosition,
				exported.copyParticleVelocities(), exported.copyCollisionMicroMasks(), exported.liquidGeometry());
		var before = restored.exportParticleState();
		try {
			restored.restoreParticleState(inconsistent);
			throw new AssertionError("Restore accepted a geometric centroid inconsistent with the inventory carrier");
		} catch (IllegalArgumentException expected) { }
		if (!java.util.Arrays.equals(before.copyPositionVolume(), restored.exportParticleState().copyPositionVolume()))
			throw new AssertionError("Failed native geometry restore changed the submitted inventory");
		var wall = exported.copyCollisionMicroMasks(); wall[domain.cellIndex(0, 1, 1)] = (byte) 0xff;
		var intersecting = new FlipParticleState(4, 4, 4, domain.config.cellSize(), 1, 0.125F, exported.copyPositionVolume(),
				exported.copyParticleVelocities(), wall, exported.liquidGeometry());
		try {
			restored.restoreParticleState(intersecting);
			throw new AssertionError("Restore accepted actual liquid volume inside a solid collision cell");
		} catch (IllegalArgumentException | IllegalStateException expected) { }
		if (!java.util.Arrays.equals(before.copyCollisionMicroMasks(), restored.exportParticleState().copyCollisionMicroMasks()))
			throw new AssertionError("Rejected native collision support changed the committed masks");
	}
	private static void liquidSourcesUseExactOpenGeometry() {
		var open = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 2, 3, 3);
		var volume = LiquidConvexRegion.of(open).measure().volume();
		for (var quantity : java.util.List.of(LiquidExactPolygon.Fraction.of(1.0 / 64), volume.divide(LiquidExactPolygon.Fraction.of(3)), volume)) {
			var source = new LiquidGeometrySourcePlan(java.util.List.of(open), quantity, 256);
			var measured = LiquidConvexRegion.EMPTY.measure();
			var cells = source.geometry.stream().map(g -> new LiquidGeometryRefinement.Cell(0, 0, 0, 0, g)).toList(); LiquidPhasePartition.requireDisjoint(cells);
			for (var piece : source.geometry) {
				var contained = LiquidConvexRegion.of(piece);
				for (var plane : open.planes()) contained = contained.clip(plane);
				if (!contained.measure().same(LiquidConvexRegion.of(piece).measure())) throw new AssertionError("Geometric source left its actual open gas space");
				measured = measured.add(contained.measure());
			}
			if (!measured.volume().equals(quantity)) throw new AssertionError("Geometric source rounded away a positive inventory tail");
			var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, open);
			var phases = LiquidPhasePartition.build(root, source.geometry, 256); var after = LiquidConvexRegion.EMPTY.measure();
			for (var region : phases) after = after.add(LiquidConvexRegion.of(region.cell().geometry()).measure());
			if (!after.same(LiquidConvexRegion.of(open).measure())) throw new AssertionError("Source insertion changed total open space or its first moments");
		}
		try {
			new LiquidGeometrySourcePlan(java.util.List.of(open), volume.add(LiquidExactPolygon.Fraction.of(1e-30)), 256);
			throw new AssertionError("Geometric source accepted more than the exact gas capacity");
		} catch (IllegalStateException expected) { }
	}
	private static void geometricSourceBatchPreservesActualCapacity() {
		var domain = new FluidDomain(4, 4, 4, FluidSolverConfig.prototypeDefaults(), 4096);
		domain.setVolume(1, 1, 1, 0.125F);
		var left = new LiquidGeometryRefinement.Cell(2, 0, 1, 1, LiquidPolyhedron.box(0.125, 0.25, 0.25, 0.1875, 0.375, 0.375));
		var right = new LiquidGeometryRefinement.Cell(2, 1, 1, 1, LiquidPolyhedron.box(0.4375, 0.25, 0.25, 0.5, 0.375, 0.375));
		domain.liquidGeometryState = LiquidGeometryStateMapper.capture(java.util.List.of(left, right), 4 * (double) domain.config.cellSize(), new int[]{0}, new int[]{0, 0});
		domain.geometry.cutCells.replaceMask(domain.cellIndex(1, 1, 1), 1);
		var room = domain.availableCellVolumes();
		if (room[domain.cellIndex(0, 1, 1)].toDouble() != 0.9375 || room[domain.cellIndex(1, 1, 1)].toDouble() != 0.8125)
			throw new AssertionError("Native transfer capacity was derived from inventory centroid instead of occupied support");
		if (domain.addVolumeNearBudgeted(1, 1, 1, 0.8125F, 0).addedVolume() != 0.8125F)
			throw new AssertionError("Geometric source failed to use actual remaining open capacity");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		if (domain.availableCellVolumes()[domain.cellIndex(1, 1, 1)].signum() != 0 || domain.totalVolumeExact() != 0.9375)
			throw new AssertionError("Source batch changed inventory or exceeded exact cell capacity");
		var copied = FluidDomainStateMapper.restore(FluidDomainStateMapper.capture(domain));
		if (copied.liquidGeometryState.markerCount() != domain.particles.count()) throw new AssertionError("Source batch lost geometry during candidate handoff");
		var unchanged = domain.exportParticleState();
		if (domain.addVolumeNearBudgeted(1, 1, 1, Float.MIN_VALUE, 0).addedVolume() != 0
				|| !java.util.Arrays.equals(unchanged.copyPositionVolume(), domain.exportParticleState().copyPositionVolume()))
			throw new AssertionError("Rejected source changed its native inventory");
		var empty = new FluidDomain(4, 4, 4, FluidSolverConfig.prototypeDefaults(), 256);
		empty.liquidGeometryState = new LiquidGeometryState(4 * (double) empty.config.cellSize(), java.util.List.of(), new int[0]);
		if (empty.addVolumeNearBudgeted(1, 1, 1, Float.MIN_VALUE, 0).addedVolume() != Float.MIN_VALUE)
			throw new AssertionError("Geometric source discarded a representable positive volume tail");
		LiquidGeometryStateMapper.requireCompatible(empty.liquidGeometryState, empty);
		var single = new LiquidGeometryInsertionPlan(new LiquidGeometryState(0.5, java.util.List.of(), new int[0]), 1, 1, 1, 0.5F,
				new byte[1], java.util.List.of(new LiquidGeometryInsertionPlan.Insertion(0, 0, 0, 0.5F)), 64);
		var singleVolume = LiquidExactPolygon.Fraction.ZERO;
		for (var cell : LiquidGeometryStateMapper.restore(single.geometry)) singleVolume = singleVolume.add(cell.geometry().measure().volume());
		if (!singleVolume.equals(LiquidExactPolygon.Fraction.of(0.5))) throw new AssertionError("Single-cell geometric source failed its dyadic root");
		if (empty.removeParticlesInCell(1, 1, 1, Float.MIN_VALUE) != Float.MIN_VALUE || !empty.liquidGeometryState.cells().isEmpty())
			throw new AssertionError("Ordinary removal discarded a positive inventory tail or retained its geometry");
		LiquidGeometryStateMapper.requireCompatible(empty.liquidGeometryState, empty);
	}
	private static void resolvedDomainUsesCollisionOpenSpace() {
		var collision = new FlipCutCellGeometry(3, 2, 2);
		var liquid = new LiquidGeometryRefinement.Cell(4, 9, 5, 5, LiquidPolyhedron.box(0.5625, 0.3125, 0.3125, 0.625, 0.375, 0.375));
		var state = LiquidGeometryStateMapper.capture(java.util.List.of(liquid), 2, new int[] {0}, new int[] {0}, new double[] {0.125, 0, 0});
		for (int mask : new int[] {0, 0x33, 0xff}) {
			collision.replaceMask(0, mask);
			var partition = new LiquidGeometryDomainPartition(3, 2, 2, 0.5F, collision, java.util.List.of(liquid), 1024);
			for (boolean sealed : new boolean[] {false, true}) {
				var input = new LiquidGeometryPhaseInput(state, partition, sealed, 2048);
				var measured = LiquidConvexRegion.EMPTY.measure(); int fluids = 0, outlets = 0;
				for (int c = 0; c < input.mesh.cells.size(); c++) {
					measured = measured.add(LiquidConvexRegion.of(input.mesh.cells.get(c).geometry()).measure());
					if (input.fragments[c] >= 0) {
						fluids++; if (input.fragments[c] != 0 || input.cellVelocity[3 * c] != 0.125) throw new AssertionError("Collision subdivision lost incoming liquid field");
					}
				}
				if (fluids != 1 || measured.volume().rounded() != 1.5 - Integer.bitCount(mask) / 64.0)
					throw new AssertionError("Resolved domain changed collision open volume");
				for (var face : input.mesh.faces) if (face.second() == -1) {
					outlets++; var p = face.surface().centroid();
					if (p.x() != 0 && p.x() != 1.5 && p.y() != 0 && p.y() != 1 && p.z() != 0 && p.z() != 1)
						throw new AssertionError("Solid obstacle opened an ambient gas boundary");
				}
				if (sealed ? outlets != 0 : outlets == 0) throw new AssertionError("Resolved domain changed physical exterior conditions");
			}
		}
		collision.replaceMask(0, 1);
		var blocked = new LiquidGeometryRefinement.Cell(3, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.125, 0.125, 0.125));
		try {
			new LiquidGeometryDomainPartition(3, 2, 2, 0.5F, collision, java.util.List.of(blocked), 1024);
			throw new AssertionError("Resolved liquid entered a solid collision micro cell");
		} catch (IllegalArgumentException expected) { }
		try {
			new LiquidGeometryDomainPartition(3, 2, 2, 0.5F, collision, java.util.List.of(liquid), 1);
			throw new AssertionError("Resolved collision partition ignored its resource budget");
		} catch (IllegalStateException expected) { }
	}
	private static void phasePartitionPreservesSolidOpenGeometry() {
		var open = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 2, 3, 3);
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, open);
		var liquid = LiquidPolyhedron.box(0.125, 0.125, 0.125, 0.375, 0.375, 0.375);
		var phases = LiquidPhasePartition.build(root, java.util.List.of(liquid), 64); var measure = LiquidConvexRegion.EMPTY.measure();
		for (var region : phases) measure = measure.add(LiquidConvexRegion.of(region.cell().geometry()).measure());
		if (!measure.same(LiquidConvexRegion.of(open).measure())) throw new AssertionError("Phase partition changed solid open volume or first moments");
		var complete = phases.stream().map(LiquidPhasePartition.Region::cell).toList();
		LiquidPhasePartition.requireComplete(root, complete);
		try {
			LiquidPhasePartition.requireComplete(root, complete.subList(1, complete.size()));
			throw new AssertionError("Stored phase admission accepted an uncovered liquid volume");
		} catch (IllegalArgumentException expected) { }
		var cube = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		try {
			LiquidPhasePartition.requireComplete(cube, java.util.List.of(
					new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.125, 0, 0, 0.625, 1, 1)),
					new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.375, 0, 0, 0.875, 1, 1))));
			throw new AssertionError("Stored phase admission accepted overlap with the same total volume and centroid");
		} catch (IllegalArgumentException expected) { }
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
		try (var ignored = LiquidGeometryStateMapper.openReadScope()) {
			var input = new java.util.ArrayList<>(copied.liquidGeometryState.cells());
			LiquidGeometryStateMapper.restoreCells(input);
			input.add(input.getFirst());
			try {
				LiquidGeometryStateMapper.restoreCells(input);
				throw new AssertionError("A prior geometry proof admitted newly overlapping fragments");
			} catch (IllegalArgumentException expected) { }
			input.removeLast();
			moments(LiquidGeometryStateMapper.restoreCells(input).getFirst().moments(), geometry.moments().volume(), geometry.moments().x(), geometry.moments().y(), geometry.moments().z());
		}
		var incompatible = LiquidGeometryStateMapper.capture(cells, width / 2, markerCells);
		try {
			LiquidGeometryStateMapper.requireCompatible(incompatible, domain);
			throw new AssertionError("Liquid geometry accepted inconsistent physical units");
		} catch (IllegalArgumentException expected) { }
		var changedVolume = java.util.List.of(new LiquidGeometryRefinement.Cell(2, 1, 1, 1,
				LiquidPolyhedron.box(0.25, 0.25, 0.25, Math.nextUp(0.375), 0.375, 0.375)));
		try {
			LiquidGeometryStateMapper.requireCompatible(LiquidGeometryStateMapper.capture(changedVolume, width, markerCells), domain);
			throw new AssertionError("Geometric volume drift was accepted as observer rounding");
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
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var regions = LiquidPhasePartition.build(root, LiquidGeometryStateMapper.restore(grouped).stream().map(LiquidGeometryRefinement.Cell::geometry).toList(), 64);
		var support = new LiquidGeometryDomainPartition(copied.sizeX, copied.sizeY, copied.sizeZ, copied.config.cellSize(), copied.geometry.cutCells,
				LiquidGeometryStateMapper.restore(grouped), 4096);
		var nativeInput = new LiquidGeometryPhaseInput(grouped, support, false, 4096); var properties = new LiquidGeometryCarrierProperties(copied, grouped, nativeInput);
		for (int c = 0; c < nativeInput.fragments.length; c++) if (nativeInput.fragments[c] >= 0
				&& (properties.density[c] != copied.config.density() || properties.dynamicViscosity[c] != 0 || properties.acceleration[3 * c + 1] != -copied.config.gravity()))
			throw new AssertionError("Resolved carrier properties differ from the shared inventory mass and physical scale");
		var mesh = new LiquidControlVolumeMesh(regions.stream().map(LiquidPhasePartition.Region::cell).toList(), width, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var rates = new LiquidExactPolygon.Fraction[mesh.faces.size()]; var primary = new double[3 * mesh.cells.size()];
		for (int c = 0; c < mesh.cells.size(); c++) primary[3 * c] = 0.125;
		for (int f = 0; f < rates.length; f++) rates[f] = mesh.faces.get(f).areaVector().getFirst().multiply(LiquidExactPolygon.Fraction.of(0.125));
		var field = LiquidControlVolumeVelocity.reconstruct(mesh, rates, primary);
		for (int f = 0; f < rates.length; f++) rates[f] = mesh.faces.get(f).areaVector().get(1).multiply(mesh.faces.get(f).centroid().getFirst()
				.subtract(LiquidExactPolygon.Fraction.of(0.375 * width))).multiply(LiquidExactPolygon.Fraction.of(0.125));
		for (int c = 0; c < mesh.cells.size(); c++) { primary[3 * c] = 0; primary[3 * c + 1] = 0.125 * (mesh.cells.get(c).centroid().x() - 0.375 * width); }
		var shear = new LiquidGeometryMotionPlan(grouped, mesh, regions.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray(),
				LiquidControlVolumeVelocity.reconstruct(mesh, rates, primary), LiquidGeometryRefinement.children(root), 0, 4096, 128);
		var resolved = shear.geometry.cells(); double separation = 0.125 * 0.09375 * width;
		if (resolved.size() != 2 || resolved.getFirst().copyVelocity()[1] != -separation || resolved.getLast().copyVelocity()[1] != separation)
			throw new AssertionError("Shared inventory erased distinct resolved control-volume velocities");
		var fieldCopy = resolved.getFirst().copyVelocity(); fieldCopy[1] = 1;
		if (resolved.getFirst().copyVelocity()[1] != -separation) throw new AssertionError("Geometric velocity snapshot exposed mutable state");
		shear.publish(copied); FluidDomainStateMapper.capture(copied);
		double massKilograms = copied.unitScale.kilograms(copied.particles.mass(0));
		double resolvedEnergy = 0.5 * massKilograms * separation * separation;
		if (Math.abs(copied.particleKineticEnergyJoules() - resolvedEnergy) > 4 * Math.ulp(resolvedEnergy)
				|| copied.particles.velocityY(0) != 0) throw new AssertionError("Geometric energy erased resolved velocity variance inside an inventory group");
		double potential = massKilograms * copied.config.gravity() * 0.3125 * width;
		if (Math.abs(copied.gravitationalPotentialEnergyJoules() - potential) > 4 * Math.ulp(potential))
			throw new AssertionError("Geometric potential energy differs from its actual spatial moment");
		var coarseState = copied.liquidGeometryState; var fineCells = new java.util.ArrayList<LiquidGeometryRefinement.Cell>(); var fineVelocity = new java.util.ArrayList<double[]>();
		var coarseCells = LiquidGeometryStateMapper.restore(coarseState);
		for (int f = 0; f < coarseCells.size(); f++) for (var child : LiquidGeometryRefinement.children(coarseCells.get(f)))
			for (var finer : LiquidGeometryRefinement.children(child)) { fineCells.add(finer); fineVelocity.add(coarseState.cells().get(f).copyVelocity()); }
		var speed = new double[3 * fineCells.size()]; for (int f = 0; f < fineCells.size(); f++) System.arraycopy(fineVelocity.get(f), 0, speed, 3 * f, 3);
		var coarseEnergy = LiquidGeometryMechanicalEnergy.measure(copied);
		copied.liquidGeometryState = LiquidGeometryStateMapper.capture(fineCells, width, new int[]{0}, new int[fineCells.size()], speed);
		LiquidGeometryStateMapper.requireCompatible(copied.liquidGeometryState, copied);
		if (!LiquidGeometryMechanicalEnergy.measure(copied).equals(coarseEnergy)) throw new AssertionError("Auxiliary fragment subdivision changed exact P0 energy or momentum");
		copied.liquidGeometryState = coarseState;
		var motion = new LiquidGeometryMotionPlan(copied.liquidGeometryState, mesh, regions.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray(), field,
				LiquidGeometryRefinement.children(root), 0.1, 4096, 128);
		if (motion.geometry.markerCount() != 1 || motion.inventoryMotion.size() != 1) throw new AssertionError("Geometric motion duplicated inventory ownership");
		var position = motion.inventoryMotion.getFirst();
		moments(new LiquidPolyhedron.Moments(1, position.positionMetres().x(), position.positionMetres().y(), position.positionMetres().z()), 1, 0.375 * width + 0.0125, 0.3125 * width, 0.3125 * width);
		if (!position.velocityMetresPerSecond().equals(new LiquidPolyhedron.Point(0.125, 0, 0))) throw new AssertionError("Inventory group lost its integrated momentum");
		var beforeMotion = copied.exportParticleState(); int obstacle = copied.cellIndex(2, 1, 1);
		copied.geometry.cutCells.replaceMask(obstacle, 1);
		try { motion.publish(copied); throw new AssertionError("Geometric motion entered newly occupied collision space"); }
		catch (IllegalArgumentException | IllegalStateException expected) { }
		if (copied.liquidGeometryState != beforeMotion.liquidGeometry() || !java.util.Arrays.equals(beforeMotion.copyPositionVolume(), copied.exportParticleState().copyPositionVolume())
				|| !java.util.Arrays.equals(beforeMotion.copyParticleVelocities(), copied.exportParticleState().copyParticleVelocities()))
			throw new AssertionError("Rejected geometric collision changed the input geometry or observations");
		copied.geometry.cutCells.replaceMask(obstacle, 0);
		motion.publish(copied); FluidDomainStateMapper.capture(copied);
		var submitted = copied.exportParticleState();
		try { motion.publish(copied); throw new AssertionError("Obsolete geometric motion changed the next input"); }
		catch (IllegalStateException expected) { }
		if (copied.liquidGeometryState != submitted.liquidGeometry() || !java.util.Arrays.equals(submitted.copyPositionVolume(), copied.exportParticleState().copyPositionVolume()))
			throw new AssertionError("Rejected geometric motion changed the submitted inventory");
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
