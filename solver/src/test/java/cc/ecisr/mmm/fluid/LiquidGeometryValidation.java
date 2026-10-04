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
		resolvedDomainUsesCollisionOpenSpace();
		liquidSourcesUseExactOpenGeometry();
		inventoryCentroidUsesResolvedCollisionSupport();
		geometricSourceBatchPreservesActualCapacity();
		geometricExtractionUsesOccupiedSupportAndVelocity();
		System.out.println("Liquid geometry volume, centroid and local subdivision validation passed");
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
		if (!domain.tryExtractExactVolume(0, 1, 1, 0.03125F, 0).transferred()) throw new AssertionError("Remaining local support could not be extracted");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		if (domain.particles.positionX(0) != 1.875F * domain.config.cellSize() || domain.particles.velocityX(0) != -1)
			throw new AssertionError("Spatial extraction retained the old group centroid or velocity");
		if (!domain.tryExtractExactVolume(1, 1, 1, 0.0625F, 0).transferred() || !domain.liquidGeometryState.cells().isEmpty())
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
		copied.liquidGeometryState = shear.geometry; FluidDomainStateMapper.capture(copied);
		var motion = new LiquidGeometryMotionPlan(grouped, mesh, regions.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray(), field,
				LiquidGeometryRefinement.children(root), 0.1, 4096, 128);
		if (motion.geometry.markerCount() != 1 || motion.inventoryMotion.size() != 1) throw new AssertionError("Geometric motion duplicated inventory ownership");
		var position = motion.inventoryMotion.getFirst();
		moments(new LiquidPolyhedron.Moments(1, position.positionMetres().x(), position.positionMetres().y(), position.positionMetres().z()), 1, 0.375 * width + 0.0125, 0.3125 * width, 0.3125 * width);
		if (!position.velocityMetresPerSecond().equals(new LiquidPolyhedron.Point(0.125, 0, 0))) throw new AssertionError("Inventory group lost its integrated momentum");
		copied.liquidGeometryState = motion.geometry; copied.particles.setPositionX(0, (float) position.positionMetres().x());
		copied.particles.setPositionY(0, (float) position.positionMetres().y()); copied.particles.setPositionZ(0, (float) position.positionMetres().z());
		copied.particles.setVelocityX(0, (float) position.velocityMetresPerSecond().x());
		FluidDomainStateMapper.capture(copied);
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
