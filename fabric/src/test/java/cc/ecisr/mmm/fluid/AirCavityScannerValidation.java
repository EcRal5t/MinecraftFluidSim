package cc.ecisr.mmm.fluid;

import java.util.List;

/** Standalone M5 cavity-scanner gate: ambient/sealed classification, exact available volume, partial-face geometric edges and the scan budget. */
public final class AirCavityScannerValidation {
	private AirCavityScannerValidation() { }

	public static void main(String[] args) {
		validateMicroGasGeometry();
		validateOpenTankIsAmbient();
		validateControllerExtentIsSealed();
		validateClosedCapacityComplementsCarrierInventory();
		validateAmbientCaptureResolution();
		validateIsolatedCompartmentCapacityIsLocal();
		validateNewPartialVacuumOwners();
		validateSealedTankVolume();
		validateOpenUTubeIsAmbient();
		validateMultipleSealedCavities();
		validatePartialFaceGeometricEdge();
		validateInterfaceResidualAttribution();
		validateScanBudget();
		System.out.println("M5 cavity scanner gate passed: ambient/sealed classification, available volume, "
				+ "geometric edges and budget stop");
	}

	private static void validateMicroGasGeometry() {
		validateMicroGasOverlap();
		validateComponentLiquidGeometry();
		Grid grid=new Grid(2,2,2,.5F);var phase=new FlipPressurePhaseGeometry(2,2,2);
		phase.rebuild(grid.solid,grid.liquidVolume,grid.cutCells,null,new boolean[8]);
		var geometry=GasComplementGeometry.capture(2,2,2,.5,grid.solid,grid.cutCells,phase);
		check(geometry.pieces().size()==64 && geometry.ports().size()==144 && geometry.exteriorPorts().size()==96,
				"Full cube gas microgeometry omitted or duplicated a piece or port");
		checkRelative(geometry.pieces().stream().mapToDouble(GasComplementGeometry.Piece::volume).sum(),1,"Full cube gas geometry volume");
		checkRelative(geometry.exteriorPorts().stream().mapToDouble(GasComplementGeometry.ExteriorPort::area).sum(),6,"Full cube exterior area");
		var openTopology=GasChamberTopology.capture(geometry,false);var closedTopology=GasChamberTopology.capture(geometry,true);
		check(openTopology.chambers().isEmpty() && openTopology.nodeOfPiece(0)==0,"Actual exterior gas failed to reach ambient");
		check(closedTopology.chambers().size()==1 && closedTopology.chambers().getFirst().minimumPiece()==0,
				"Sealed gas chamber lost canonical micro-piece identity");
		checkRelative(closedTopology.chambers().getFirst().geometricVolume(),1,"Sealed microgas chamber volume");
		grid.solid[0]=true;grid.cutCells.replaceMask(0,255);phase.rebuild(grid.solid,grid.liquidVolume,grid.cutCells,null,new boolean[8]);
		check(geometry.pieces().size()==64 && geometry.piece(0).volume()==.015625 && geometry.collisionMask(0)==0,"Gas geometry retained mutable input aliases");
		grid.cutCells.replaceMask(1,126);
		try { GasComplementGeometry.capture(2,2,2,.5,grid.solid,grid.cutCells,phase);throw new AssertionError("Stale PLIC collision geometry was captured"); }
		catch(IllegalArgumentException expected) { }
		try { geometry.pieces().clear();throw new AssertionError("Gas pieces remained mutable"); }
		catch(UnsupportedOperationException expected) { }
		Grid split=new Grid(2,2,2,1);java.util.Arrays.fill(split.solid,true);
		for(int cell=0;cell<8;cell++)split.cutCells.replaceMask(cell,255);
		split.solid[0]=false;split.cutCells.replaceMask(0,126);var splitPhase=new FlipPressurePhaseGeometry(2,2,2);
		splitPhase.rebuild(split.solid,split.liquidVolume,split.cutCells,null,new boolean[8]);
		var separate=GasComplementGeometry.capture(2,2,2,1,split.solid,split.cutCells,splitPhase);
		check(separate.pieces().size()==2 && separate.ports().isEmpty() && separate.piece(0)!=null && separate.piece(7)!=null,
				"Two opposite open microboxes acquired a gas connection through solid");
		checkRelative(separate.pieces().stream().mapToDouble(GasComplementGeometry.Piece::volume).sum(),.25,"Disconnected gas volume");
		var splitOpen=GasChamberTopology.capture(separate,false);var splitClosed=GasChamberTopology.capture(separate,true);
		check(splitOpen.chambers().size()==1 && splitOpen.nodeOfPiece(0)==0 && splitOpen.nodeOfPiece(7)==1,
				"A cell-level ambient label vented its isolated opposite microbox");
		check(splitClosed.chambers().size()==2 && splitClosed.nodeOfPiece(0)!=splitClosed.nodeOfPiece(7),
				"Two disjoint same-cell gas chambers acquired one owner");
		var a=cc.ecisr.mmm.units.SignedDyadic.of((double).1234567F);
		var b=cc.ecisr.mmm.units.SignedDyadic.of((double).7654321F);var threshold=cc.ecisr.mmm.units.SignedDyadic.of(.1);
		var lower=new GasFaceIntersection.Plane(a,b,threshold);
		var tangent=new GasFaceIntersection.Plane(a.negate(),b.negate(),threshold.negate());
		check(GasFaceIntersection.area(0,0,1,List.of(lower,tangent))==0,"Tangential half-spaces manufactured a gas port");
		double upper=Math.nextUp(.1);
		var narrow=new GasFaceIntersection.Plane(a.negate(),b.negate(),cc.ecisr.mmm.units.SignedDyadic.of(-upper));
		double strip=GasFaceIntersection.area(0,0,1,List.of(lower,narrow));
		var numerator=new java.math.BigDecimal(upper).pow(2).subtract(new java.math.BigDecimal(.1).pow(2));
		var denominator=new java.math.BigDecimal((double).1234567F).multiply(new java.math.BigDecimal((double).7654321F)).multiply(java.math.BigDecimal.valueOf(2));
		double expected=numerator.divide(denominator,java.math.MathContext.DECIMAL128).doubleValue();
		check(strip>0 && Double.doubleToLongBits(strip)==Double.doubleToLongBits(expected),"A true narrow gas port lost its exact physical area");
	}

	private static void validateComponentLiquidGeometry() {
		for (int corner = 0; corner < 8; corner++) for (float[] stock : List.of(
				new float[]{.0625F,.03125F},new float[]{.03125F,.0625F},new float[]{.0625F,.0625F},new float[]{0,.125F})) {
			int opposite=corner^7, count=(stock[0]>0?1:0)+(stock[1]>0?1:0), marker=0;
			float[] pv=new float[4*count];
			for (int i=0;i<2;i++) if(stock[i]>0) {
				int bit=i==0?corner:opposite, base=4*marker++;
				pv[base]=.25F+.5F*(bit&1);pv[base+1]=.25F+.5F*(bit>>1&1);pv[base+2]=.25F+.5F*(bit>>2&1);pv[base+3]=stock[i];
			}
			byte[] masks=new byte[8];java.util.Arrays.fill(masks,(byte)255);masks[0]=(byte)(255^(1<<corner)^(1<<opposite));
			var d=new FluidDomain(2,2,2,new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.5F,1e-4F,0),16);
			d.restoreParticleState(new FlipParticleState(2,2,2,1,count,stock[0]+stock[1],pv,new float[3*count],masks));d.ensureInventoryGrid();
			var phase=d.pressurePhaseGeometry;
			var gas=GasComplementGeometry.capture(2,2,2,1,d.geometry.solid,d.geometry.cutCells,phase);
			var capacities=GasComponentCapacity.capture(d,gas,null);
			check(capacities.size()==2,"Gas capacity omitted a fixed open component");
			for (int i=0;i<2;i++) {
				int bit=i==0?corner:opposite;double[] moments=new double[4];double wet=phase.liquidFractionExact(0,bit);
				if(wet>=1)moments[0]=1;
				else if(wet>0) {
					double nx=phase.planeNormal(0,bit,0),ny=phase.planeNormal(0,bit,1),nz=phase.planeNormal(0,bit,2);
					LiquidFluxGeometry.unitCubeMoments(.5*nx,.5*ny,.5*nz,phase.planeOffset(0,bit)-.5*(nx*(bit&1)+ny*(bit>>1&1)+nz*(bit>>2&1)),moments);
				}
				check(Math.abs(.125*moments[0]-stock[i])<2e-15,"PLIC transferred liquid between disconnected components");
				double expected=.125-stock[i];var piece=gas.piece(bit);
				check(expected==0?piece==null:piece!=null&&Math.abs(piece.volume()-expected)<2e-15,"Gas geometry disagrees with component inventory");
				var capacity=capacities.stream().filter(c->c.supportMask()==1<<bit).findFirst().orElseThrow();
				check(capacity.availableVolumeNormalized().equals(cc.ecisr.mmm.units.SignedDyadic.of(expected)),"Gas capacity changed the authoritative component remainder");
			}
			var topology=GasChamberTopology.capture(gas,true);
			check(topology.chambers().size()==(stock[0]<.125F?1:0)+(stock[1]<.125F?1:0),"Component PLIC omitted or invented an independent gas chamber");
		}
		for(int axis=0;axis<3;axis++) {
			int corner=1<<axis,opposite=corner^7,peer=axis==0?1:axis==1?2:4;
			byte[] masks=new byte[8];java.util.Arrays.fill(masks,(byte)255);masks[0]=(byte)(255^(1<<corner)^(1<<opposite));masks[peer]=(byte)254;
			float[] pv=new float[8];
			for(int i=0;i<2;i++) {int bit=i==0?corner:opposite,b=4*i;pv[b]=.25F+.5F*(bit&1);pv[b+1]=.25F+.5F*(bit>>1&1);pv[b+2]=.25F+.5F*(bit>>2&1);pv[b+3]=i==0?.0625F:.03125F;}
			var d=new FluidDomain(2,2,2,new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.5F,1e-4F,0),16);
			d.restoreParticleState(new FlipParticleState(2,2,2,1,2,.09375F,pv,new float[6],masks));d.ensureInventoryGrid();
			var phase=d.pressurePhaseGeometry;double[] swept=new double[4];
			new LiquidFluxGeometry(d).sweptMoments(0,peer,axis,.5,true,phase.planeNormal(0,0),phase.planeNormal(0,1),phase.planeNormal(0,2),phase.planeOffset(0),swept,1<<corner,1);
			check(Math.abs(swept[0]-.0625)<2e-15,"Shared-face swept volume used another component's PLIC");
		}
	}

	private static void validateMicroGasOverlap() {
		var zero=cc.ecisr.mmm.units.SignedDyadic.ZERO;
		var one=cc.ecisr.mmm.units.SignedDyadic.of(1);
		var quarter=cc.ecisr.mmm.units.SignedDyadic.of(.25);
		var exact=cc.ecisr.mmm.chemistry.ExactRational.ONE;
		var tinyTarget=cc.ecisr.mmm.chemistry.ExactRational.ofFloat(Float.MIN_VALUE).divide(3);
		var inverse=PlicPlaneOffset.solve(1,Float.MIN_VALUE,1,0,tinyTarget);
		var tinyNormal=cc.ecisr.mmm.units.SignedDyadic.of(Float.MIN_VALUE);
		for(double offset:new double[]{inverse.lower(),inverse.upper()}) {
			var wet=new GasGeometryOverlap.HalfSpace(tinyNormal.negate(),one.negate(),zero,cc.ecisr.mmm.units.SignedDyadic.of(-offset));
			var measured=GasGeometryOverlap.normalizedVolume(0,List.of(wet));
			check(measured.equals(offset==inverse.lower()?inverse.lowerVolume():inverse.upperVolume()),"PLIC inverse certificate differs from independent polyhedron volume");
		}
		check(inverse.lowerVolume().compareTo(tinyTarget)<=0 && inverse.upperVolume().compareTo(tinyTarget)>=0
				&& Math.nextUp(inverse.lower())==inverse.upper(),"Tiny PLIC volume lacks adjacent enclosing offsets");
		check(inverse.offset()>0 && inverse.offset()<1e-44,"PLIC inversion manufactured a finite layer from an extreme tail");
		var tetra=new GasGeometryOverlap.HalfSpace(one.negate(),one.negate(),one.negate(),cc.ecisr.mmm.units.SignedDyadic.of(-.5));
		check(GasGeometryOverlap.normalizedVolume(0,List.of(tetra)).equals(exact.divide(48)),
				"Gas overlap did not retain the exact tetrahedron volume");
		for(int slot=0;slot<8;slot++) check(MicroBoxPlaneVolume.dryVolume(slot,tetra).equals(GasGeometryOverlap.normalizedVolume(slot,List.of(tetra))),
				"Microbox CDF disagrees with exact tetrahedron clipping");
		for(int slot=1;slot<8;slot++) check(GasGeometryOverlap.normalizedVolume(slot,List.of(tetra)).isZero(),
				"Gas tetrahedron tangency manufactured positive volume");
		for(int axis=0;axis<3;axis++) {
			var normal=new cc.ecisr.mmm.units.SignedDyadic[]{zero,zero,zero};normal[axis]=one;
			var lower=new GasGeometryOverlap.HalfSpace(normal[0],normal[1],normal[2],quarter);
			var tangent=new GasGeometryOverlap.HalfSpace(normal[0].negate(),normal[1].negate(),normal[2].negate(),quarter.negate());
			var thickness=cc.ecisr.mmm.units.SignedDyadic.of(1,-100);
			var upper=new GasGeometryOverlap.HalfSpace(normal[0].negate(),normal[1].negate(),normal[2].negate(),quarter.add(thickness).negate());
			for(int slot=0;slot<8;slot++) {
				check(GasGeometryOverlap.normalizedVolume(slot,List.of()).equals(exact.divide(8)),"Empty gas overlap constraints changed an open microbox");
				check(GasGeometryOverlap.normalizedVolume(slot,List.of(lower,tangent)).isZero(),"Coincident gas planes manufactured overlap");
				var expected=(slot>>axis&1)==0?new cc.ecisr.mmm.chemistry.ExactRational(java.math.BigInteger.ONE,java.math.BigInteger.ONE.shiftLeft(102))
						:cc.ecisr.mmm.chemistry.ExactRational.ZERO;
				check(GasGeometryOverlap.normalizedVolume(slot,List.of(lower,upper)).equals(expected),"Sub-ULP positive gas overlap was lost");
				check(GasGeometryOverlap.normalizedVolume(slot,List.of(upper,lower)).equals(expected),"Gas overlap depends on clipping order");
				check(GasGeometryOverlap.normalizedVolume(slot,List.of(lower,lower)).equals(GasGeometryOverlap.normalizedVolume(slot,List.of(lower))),
						"Repeated gas plane double-counted its overlap");
			}
		}
	}

	private static void validateControllerExtentIsSealed() {
		int n = 8;
		Grid grid = new Grid(n);
		AirCavityScanner.Result result = new AirCavityScanner(n, n, n, 0.5F, n * n * n)
				.scan(grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells, null, true);
		check(result.sealedNodeCount() == 1,
				"a validated controller interior must keep boundary-layer headspace sealed");
		checkRelative(result.sealedAvailableVolume()[0], 64.0,
				"controller interior gas volume");
	}

	private static void validateAmbientCaptureResolution() {
		for (int exponent : new int[]{-12,-28}) {
			byte[] masks=new byte[27];java.util.Arrays.fill(masks,(byte)255);masks[13]=0;
			float[] volumes={.5F,.25F,.125F,.0625F,.03125F,.015625F,.0078125F,(float)(.0078125-Math.scalb(1,exponent))};
			float[] pv=new float[32];double sum=0;
			for(int i=0;i<8;i++) {int b=4*i;pv[b]=.625F+.25F*(i&1);pv[b+1]=.625F+.25F*(i>>1&1);pv[b+2]=.625F+.25F*(i>>2&1);pv[b+3]=volumes[i];sum+=volumes[i];}
			var d=new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,0,.05F,200,1e-5F,.4F,.002F,0));
			d.restoreParticleState(new FlipParticleState(3,3,3,.5F,8,(float)sum,pv,new float[24],masks));d.sealDomainBoundary();
			var network=d.gasState.network;long gasRevision=network.revision(),carrierRevision=d.carrierMutationRevision();
			if(exponent==-12) {
				var gas=d.bootstrapGasState("");long expected=cc.ecisr.mmm.fluid.air.AirNetwork.nanos((1-sum)*.125);
				check(gas.externalSource().standardVolumeNanos()==expected,"Ambient bootstrap changed the rounded source amount");
			} else {
				try {d.bootstrapGasState("");throw new AssertionError("Sub-resolution ambient space invented a minimum gas portion");}
				catch(GasTopologyBlockedException expected) { }
				check(d.gasState.network==network && network.revision()==gasRevision && network.nodeCount()==0
						&& network.externalSource().entries().isEmpty() && network.escapedSink().entries().isEmpty()
						&& d.gasState.cellNodeId==null && d.carrierMutationRevision()==carrierRevision
						&& java.util.Arrays.equals(pv,d.exportParticleState().copyPositionVolume()),"Rejected ambient capture changed authoritative owners");
			}
		}
	}

	private static void validateClosedCapacityComplementsCarrierInventory() {
		int n = 8;
		Grid grid = new Grid(n);
		int first = (4 * n + 4) * n + 4;
		int second = (4 * n + 4) * n + 5;
		grid.markerCount[first] = 1;
		grid.markerCount[second] = 1;
		grid.liquidVolume[first] = 1.5;
		grid.liquidVolume[second] = 0.5;
		AirCavityScanner.Result result = new AirCavityScanner(n, n, n, 0.5F, n * n * n)
				.scan(grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells, null, true);
		check(result.sealedNodeCount() == 1,
				"closed controller capacity regression must retain one gas owner");
		checkRelative(result.sealedAvailableVolume()[0], (n * n * n - 2.0) * 0.125,
				"closed gas capacity must complement total authoritative liquid volume");
	}

	/**
	 * A closed shell is settled one cavity at a time. Liquid redistributed inside one watertight
	 * compartment must not move a compartment behind a solid wall, even when the markers cluster
	 * past the open volume of a single cell.
	 */
	private static void validateIsolatedCompartmentCapacityIsLocal() {
		Grid grid = new Grid(5, 2, 2, 1.0F);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) {
			int wall = (z * 2 + y) * 5 + 2;
			grid.solid[wall] = true;
			grid.cutCells.replaceMask(wall, 255);
		}
		grid.markerCount[0] = 1;
		grid.markerCount[1] = 1;
		grid.liquidVolume[0] = 0.75;
		grid.liquidVolume[1] = 0.75;
		AirCavityScanner scanner = new AirCavityScanner(5, 2, 2, 1.0F, 20);
		AirCavityScanner.Result before = scanner.scan(
				grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells, null, true);
		grid.liquidVolume[0] = 1.25;
		grid.liquidVolume[1] = 0.25;
		AirCavityScanner.Result after = scanner.scan(
				grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells, null, true);
		check(before.sealedNodeCount() == 2 && after.sealedNodeCount() == 2,
				"a solid wall must keep two sealed compartments, got " + before.sealedNodeCount()
						+ "/" + after.sealedNodeCount());
		check(before.geometricEdges().isEmpty() && after.geometricEdges().isEmpty(),
				"compartments must stay isolated");
		int left = after.cellNodeId()[5] - 1;
		int right = after.cellNodeId()[3] - 1;
		check(left >= 0 && right >= 0 && left != right, "compartments must own distinct cavities");
		checkRelative(after.sealedAvailableVolume()[left], 6.5, "clustered compartment capacity");
		checkRelative(after.sealedAvailableVolume()[right], 8.0, "isolated compartment capacity");
		checkRelative(before.sealedAvailableVolume()[left], after.sealedAvailableVolume()[left],
				"redistributing an unchanged liquid total must not move its own compartment");
		checkRelative(before.sealedAvailableVolume()[right], after.sealedAvailableVolume()[right],
				"liquid clustering must not reach a compartment behind a solid wall");
	}

	private static void validateNewPartialVacuumOwners() {
		Grid grid = new Grid(4);
		int[] previous = new int[64];
		java.util.Arrays.fill(previous, -1);
		for (int cell = 0; cell < 64; cell++) {
			grid.solid[cell] = cell % 4 == 1;
			grid.markerCount[cell] = grid.solid[cell] ? 0 : 1;
			grid.liquidVolume[cell] = grid.solid[cell] ? 0 : 1;
		}
		var scanner = new AirCavityScanner(4, 4, 4, 1, 64);
		var full = scanner.scanRetainingClosedLineages(grid.solid, grid.markerCount,
				grid.liquidVolume, grid.cutCells, null, previous, 0);
		check(full.sealedNodeCount() == 0, "full liquid domain invented a gas owner");
		for (int cell = 0; cell < 64; cell++) if (!grid.solid[cell]) grid.liquidVolume[cell] = 0.75;
		var opened = scanner.scanRetainingClosedLineages(grid.solid, grid.markerCount,
				grid.liquidVolume, grid.cutCells, null, previous, 0);
		check(opened.sealedNodeCount() == 2, "disconnected partial voids need separate vacuum owners");
		var initial = scanner.scan(grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells, null, true);
		check(java.util.Arrays.equals(opened.cellNodeId(), initial.cellNodeId())
				&& java.util.Arrays.equals(opened.sealedAvailableVolume(), initial.sealedAvailableVolume()),
				"initial closed partial topology failed to establish the same geometry owners");
		var orphaned = scanner.scanRetainingClosedLineages(grid.solid, grid.markerCount,
				grid.liquidVolume, grid.cutCells, null, previous, 2);
		check(java.util.Arrays.equals(opened.cellNodeId(), orphaned.cellNodeId()),
				"disappeared old owners prevented new residual topology");
		checkRelative(java.util.Arrays.stream(opened.sealedAvailableVolume()).sum(), 12,
				"partial vacuum capacity must equal extracted volume");
		var restored = scanner.scanRetainingClosedLineages(grid.solid, grid.markerCount,
				grid.liquidVolume, grid.cutCells, null, opened.cellLineageNodeId(), 2);
		check(java.util.Arrays.equals(opened.cellNodeId(), restored.cellNodeId())
				&& java.util.Arrays.equals(opened.sealedAvailableVolume(), restored.sealedAvailableVolume()),
				"partial vacuum topology changed on persistence rescan");
	}

	private static void validateOpenTankIsAmbient() {
		int n = 8;
		Grid grid = tank(n, false, 1.0F);
		AirCavityScanner.Result result = scan(grid, n * n * n);
		check(result.sealedNodeCount() == 0, "open tank must have no sealed cavities");
		check(result.stopReason() == AirCavityScanner.StopReason.COMPLETED, "open tank scan must complete");
		for (int z = 1; z < n - 1; z++) for (int y = 4; y < n; y++) for (int x = 1; x < n - 1; x++) {
			check(result.cellNodeId()[(z * n + y) * n + x] == 0,
					"open tank air cell must be ambient at " + x + "," + y + "," + z);
		}
		for (int z = 1; z < n - 1; z++) for (int y = 1; y <= 3; y++) for (int x = 1; x < n - 1; x++) {
			check(result.cellNodeId()[(z * n + y) * n + x] == -1,
					"water cell must not join a gas node at " + x + "," + y + "," + z);
		}
	}

	private static void validateSealedTankVolume() {
		int n = 8;
		Grid grid = tank(n, true, 1.0F);
		AirCavityScanner.Result result = scan(grid, n * n * n);
		check(result.sealedNodeCount() == 1, "sealed tank must expose exactly one cavity");
		checkRelative(result.sealedAvailableVolume()[0], 6 * 6 * 3, "sealed tank cavity volume");

		Grid halfMetre = tank(n, true, 0.5F);
		AirCavityScanner.Result scaled = new AirCavityScanner(n, n, n, 0.5F, n * n * n)
				.scan(halfMetre.solid, halfMetre.markerCount, halfMetre.liquidVolume, halfMetre.cutCells);
		checkRelative(scaled.sealedAvailableVolume()[0], 6 * 6 * 3 * 0.125, "half-metre cavity volume");
	}

	private static void validateOpenUTubeIsAmbient() {
		int n = 8;
		Grid grid = new Grid(n);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			int i = (z * n + y) * n + x;
			boolean shell = x == 0 || x == n - 1 || z == 0 || z == n - 1 || y == 0;
			boolean divider = (x == 3 || x == 4) && y <= 5;
			if (shell || divider) grid.solid[i] = true;
			else if (y <= 1) { grid.markerCount[i] = 8; grid.liquidVolume[i] = 1.0F; }
		}
		AirCavityScanner.Result result = scan(grid, n * n * n);
		check(result.sealedNodeCount() == 0, "open U-tube air must classify as ambient");
		for (int z = 1; z < n - 1; z++) for (int y = 2; y < n - 1; y++) for (int x = 1; x < n - 1; x++) {
			if (x == 3 || x == 4) continue;
			check(result.cellNodeId()[(z * n + y) * n + x] == 0,
					"U-tube air cell must be ambient at " + x + "," + y + "," + z);
		}
	}

	private static void validateMultipleSealedCavities() {
		int sizeX = 16, sizeY = 8, sizeZ = 8;
		Grid grid = new Grid(sizeX, sizeY, sizeZ, 1.0F);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int i = (z * sizeY + y) * sizeX + x;
			boolean outerShell = x == 0 || x == sizeX - 1 || z == 0 || z == sizeZ - 1 || y == 0 || y == sizeY - 1;
			boolean divider = x == 8;
			if (outerShell || divider) grid.solid[i] = true;
		}
		AirCavityScanner.Result result = new AirCavityScanner(sizeX, sizeY, sizeZ, 1.0F, sizeX * sizeY * sizeZ)
				.scan(grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells);
		check(result.sealedNodeCount() == 2, "two isolated boxes must produce two sealed nodes, got " + result.sealedNodeCount());
		checkRelative(result.sealedAvailableVolume()[0], 7 * 6 * 6, "first sealed cavity volume");
		checkRelative(result.sealedAvailableVolume()[1], 6 * 6 * 6, "second sealed cavity volume");
		check(result.cellNodeId()[(1 * sizeY + 1) * sizeX + 1] == 1, "first cavity id must go to the lower cell index");
		check(result.cellNodeId()[(1 * sizeY + 1) * sizeX + 9] == 2, "second cavity id must follow discovery order");
	}

	private static void validatePartialFaceGeometricEdge() {
		int n = 8;
		Grid grid = new Grid(n);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			int i = (z * n + y) * n + x;
			boolean shell = x == 0 || x == n - 1 || z == 0 || z == n - 1 || y == 0 || y == n - 1;
			boolean divider = x == 4 && y >= 1 && y < n - 1 && z >= 1 && z < n - 1;
			if (shell || divider) grid.solid[i] = true;
		}
		// one wall cell keeps a single open quadrant on its left face: a 1/4-open gap, not a connection
		grid.cutCells.replaceMask((1 * n + 1) * n + 4, 0xFE);
		grid.solid[(1 * n + 1) * n + 4] = false;
		AirCavityScanner.Result result = scan(grid, n * n * n);
		check(result.sealedNodeCount() == 3,
				"left chamber, sliver pocket and right chamber must stay separate, got " + result.sealedNodeCount());
		checkRelative(result.sealedAvailableVolume()[0], 3 * 6 * 6, "left chamber volume");
		checkRelative(result.sealedAvailableVolume()[1], 0.125, "sliver pocket volume");
		checkRelative(result.sealedAvailableVolume()[2], 2 * 6 * 6, "right chamber volume");
		List<AirCavityScanner.GeometricEdge> edges = result.geometricEdges();
		check(edges.size() == 1, "exactly one geometric edge must exist, got " + edges.size());
		check(edges.get(0).firstNode() == 1 && edges.get(0).secondNode() == 2,
				"the gap must connect the left chamber to the sliver pocket");
		checkRelative(edges.get(0).areaSquareMeters(), 0.25, "quarter-open face area");
	}

	private static void validateInterfaceResidualAttribution() {
		int n = 8;
		Grid grid = tank(n, true, 1.0F);
		for (int y = 2; y <= 3; y++) for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
			int i = (z * n + y) * n + x;
			grid.markerCount[i] = 1;
			grid.liquidVolume[i] = 0.5F;
		}
		AirCavityScanner scanner = new AirCavityScanner(n, n, n, 1.0F, n * n * n);
		AirCavityScanner.Result result = scanner.scan(
				grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells);
		check(result.sealedNodeCount() == 1, "partially filled sealed tank must stay one cavity");
		checkRelative(result.sealedAvailableVolume()[0], 6 * 6 * 3 + 2 * 6 * 6 * 0.5,
				"multi-cell interface residual must join the sealed cavity volume");
		for (int y = 2; y <= 3; y++) for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
			int cell = (z * n + y) * n + x;
			check(result.cellNodeId()[cell] == -1 && result.cellLineageNodeId()[cell] == 1,
					"connected partial interface gas must retain runtime cavity lineage without entering persisted membership");
		}
		for (int y = 2; y <= 3; y++) for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
			grid.liquidVolume[(z * n + y) * n + x] = 0.25F;
		}
		double[] stableVolumes = scanner.measureSealedAvailableVolumes(
				grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells, null,
				result.cellNodeId(), result.cellLineageNodeId(), result.sealedNodeCount(), false);
		AirCavityScanner.Result rescanned = scanner.scan(
				grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells);
		check(java.util.Arrays.equals(result.cellNodeId(), rescanned.cellNodeId())
				&& java.util.Arrays.equals(result.cellLineageNodeId(), rescanned.cellLineageNodeId())
				&& Double.doubleToRawLongBits(stableVolumes[0])
				== Double.doubleToRawLongBits(rescanned.sealedAvailableVolume()[0]),
				"stable-lineage available-volume refresh differed from a complete cavity rescan");

		Grid openGrid = tank(n, false, 1.0F);
		for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
			int i = (z * n + 3) * n + x;
			openGrid.markerCount[i] = 1;
			openGrid.liquidVolume[i] = 0.5F;
		}
		AirCavityScanner.Result openResult = scan(openGrid, n * n * n);
		check(openResult.sealedNodeCount() == 0, "residual next to ambient must not create sealed inventory");
	}

	private static void validateScanBudget() {
		int n = 8;
		Grid grid = tank(n, true, 1.0F);
		AirCavityScanner.Result result = new AirCavityScanner(n, n, n, 1.0F, 50)
				.scan(grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells);
		check(result.stopReason() == AirCavityScanner.StopReason.BUDGET_EXHAUSTED, "budget must stop the scan");
		check(result.sealedNodeCount() == 0, "budget exhaustion must classify air as ambient, never sealed");
		check(result.visitedAirCells() <= 50, "visited cells must respect the budget, got " + result.visitedAirCells());
		for (int z = 1; z < n - 1; z++) for (int y = 4; y < n - 1; y++) for (int x = 1; x < n - 1; x++) {
			check(result.cellNodeId()[(z * n + y) * n + x] == 0,
					"unvisited air must degrade to ambient at " + x + "," + y + "," + z);
		}
	}

	private static AirCavityScanner.Result scan(Grid grid, int budget) {
		int n = grid.sizeX;
		return new AirCavityScanner(n, grid.sizeY, grid.sizeZ, grid.cellSize, budget)
				.scan(grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells);
	}

	private static Grid tank(int n, boolean roof, float cellSize) {
		Grid grid = new Grid(n, n, n, cellSize);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			int i = (z * n + y) * n + x;
			boolean shell = x == 0 || x == n - 1 || z == 0 || z == n - 1 || y == 0 || (roof && y == n - 1);
			if (shell) grid.solid[i] = true;
			else if (y <= 3) { grid.markerCount[i] = 8; grid.liquidVolume[i] = 1.0F; }
		}
		return grid;
	}

	private static final class Grid {
		final int sizeX;
		final int sizeY;
		final int sizeZ;
		final float cellSize;
		final boolean[] solid;
		final int[] markerCount;
		final double[] liquidVolume;
		final FlipCutCellGeometry cutCells;

		Grid(int sizeX, int sizeY, int sizeZ, float cellSize) {
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.sizeZ = sizeZ;
			this.cellSize = cellSize;
			this.solid = new boolean[sizeX * sizeY * sizeZ];
			this.markerCount = new int[sizeX * sizeY * sizeZ];
			this.liquidVolume = new double[sizeX * sizeY * sizeZ];
			this.cutCells = new FlipCutCellGeometry(sizeX, sizeY, sizeZ);
		}

		Grid(int n) { this(n, n, n, 1.0F); }
	}

	private static void checkRelative(double actual, double expected, String what) {
		double error = Math.abs(actual - expected) / expected;
		if (error > 1.0E-9) throw new AssertionError(what + ": actual=" + actual + " expected=" + expected);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
