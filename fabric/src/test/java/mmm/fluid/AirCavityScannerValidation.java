package mmm.fluid;

import java.util.List;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

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
		validateGasRestoreLineage();
		validateExactGasTopologyWeights();
		validateScanBudget();
		System.out.println("M5 cavity scanner gate passed: ambient/sealed classification, available volume, "
				+ "geometric edges and budget stop");
	}

	private static void validateMicroGasGeometry() {
		validateRuntimeGeometryBinding();
		GasPhaseInterfaceValidation.validate();
		validateMicroGasOverlap();
		validateComponentLiquidGeometry();
		validateGasPieceCapacity();
		validateMicroGasPoreCapacity();
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
		grid.cutCells.replaceMask(0,8);
		var partialPhase=new FlipPressurePhaseGeometry(2,2,2);
		partialPhase.rebuild(grid.solid,grid.liquidVolume,grid.cutCells,null,new boolean[8]);
		boolean[] equalCells=new boolean[8];for(int cell=0;cell<8;cell++) equalCells[cell]=partialPhase.sameCellGeometry(cell,phase);
		var partialFresh=GasComplementGeometry.capture(2,2,2,.5,grid.solid,grid.cutCells,partialPhase);
		var partialReuse=GasComplementGeometry.capture(2,2,2,.5,grid.solid,grid.cutCells,partialPhase,geometry,equalCells);
		var originalPort=geometry.ports().stream().filter(port->port.first()==1 && port.axis()==0).findFirst().orElseThrow();
		var restrictedPort=partialReuse.ports().stream().filter(port->port.first()==1 && port.axis()==0).findFirst().orElseThrow();
		check(originalPort.fullCutConnection() && !restrictedPort.fullCutConnection() && restrictedPort!=originalPort
				&& restrictedPort.area()==originalPort.area() && partialReuse.piece(8)==geometry.piece(8)
				&& partialReuse.pieces().equals(partialFresh.pieces()) && partialReuse.ports().equals(partialFresh.ports())
				&& partialReuse.exteriorPorts().equals(partialFresh.exteriorPorts()),
				"A changed peer mask reused a stale full-cut flag or changed the fresh microgeometry");
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
		check(splitOpen.chambers().size()==2 && splitOpen.nodeOfPiece(0)>0 && splitOpen.nodeOfPiece(7)>0
				&& splitOpen.nodeOfPiece(0)!=splitOpen.nodeOfPiece(7) && splitOpen.connections().size()==1
				&& splitOpen.connections().getFirst().firstNode()==0
				&& splitOpen.connections().getFirst().secondNode()==splitOpen.nodeOfPiece(0),
				"A partial exterior aperture bypassed finite flow or vented its isolated opposite microbox");
		check(splitClosed.chambers().size()==2 && splitClosed.nodeOfPiece(0)!=splitClosed.nodeOfPiece(7),
				"Two disjoint same-cell gas chambers acquired one owner");
		for(int axis=0;axis<3;axis++) {
			int peer=1<<axis,tangentAxis=(axis+1)%3;byte[] masks=new byte[8];java.util.Arrays.fill(masks,(byte)255);masks[0]=masks[peer]=0;
			float[] pv=new float[8];for(int i=0;i<2;i++) {for(int a=0;a<3;a++)pv[4*i+a]=(a==axis?i:0)+.5F;pv[4*i+tangentAxis]=.25F;pv[4*i+3]=.5F;}
			var domain=new FluidDomain(2,2,2,new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.5F,1e-4F,0),16);
			domain.restoreParticleState(new FlipParticleState(2,2,2,1,2,1,pv,new float[6],masks));domain.ensureInventoryGrid();
			var partial=GasComplementGeometry.capture(2,2,2,1,domain.geometry.solid,domain.geometry.cutCells,domain.pressurePhaseGeometry);
			var crossing=partial.ports().stream().filter(port->port.first()/8!=port.second()/8).toList();
			check(crossing.size()==2 && crossing.stream().allMatch(port->!port.fullCutConnection() && port.area()==.25)
					&& partial.ports().stream().filter(port->port.first()/8==port.second()/8).allMatch(GasComplementGeometry.Port::fullCutConnection),
					"Complete microfaces manufactured a complete cell face or split a convex within-cell gas volume");
			var gas=domain.bootstrapGasState("");
			check(gas.nodes().size()==2 && gas.externalSource().standardVolumeNanos()==1_000_000_000L
					&& domain.gasState.network.edgeCount()==3 && domain.gasState.network.auditClosed(),
					"Partial cell/exterior faces lost finite ambient stock or their conservative orifice edges");
			for(int cell:new int[]{0,peer}) {
				int owner=PressureBoundaryView.wholeCellGasNode(domain,cell);var pressure=new PressureBoundaryView(domain);
				check(owner>0 && pressure.gasNodeHasContinuumState(owner) && pressure.gasPressureOfNode(owner)==0
						&& pressure.gasNodeVolumeCompliance(owner)>0,"Partially open gas did not retain a finite pressure owner");
			}
		}
		var a=mmm.units.SignedDyadic.of((double).1234567F);
		var b=mmm.units.SignedDyadic.of((double).7654321F);var threshold=mmm.units.SignedDyadic.of(.1);
		var lower=new GasFaceIntersection.Plane(a,b,threshold);
		var tangent=new GasFaceIntersection.Plane(a.negate(),b.negate(),threshold.negate());
		check(GasFaceIntersection.area(0,0,1,List.of(lower,tangent))==0,"Tangential half-spaces manufactured a gas port");
		double upper=Math.nextUp(.1);
		var narrow=new GasFaceIntersection.Plane(a.negate(),b.negate(),mmm.units.SignedDyadic.of(-upper));
		double strip=GasFaceIntersection.area(0,0,1,List.of(lower,narrow));
		var numerator=new java.math.BigDecimal(upper).pow(2).subtract(new java.math.BigDecimal(.1).pow(2));
		var denominator=new java.math.BigDecimal((double).1234567F).multiply(new java.math.BigDecimal((double).7654321F)).multiply(java.math.BigDecimal.valueOf(2));
		double expected=numerator.divide(denominator,java.math.MathContext.DECIMAL128).doubleValue();
		check(strip>0 && Double.doubleToLongBits(strip)==Double.doubleToLongBits(expected),"A true narrow gas port lost its exact physical area");
		var tinyWet=new GasFaceIntersection.Plane(SignedDyadic.of(1),SignedDyadic.ZERO,SignedDyadic.of(Float.MIN_VALUE));
		check(GasFaceIntersection.area(0,0,.5,List.of(tinyWet))==.25
				&& !GasFaceIntersection.coversRectangle(0,0,.5,List.of(tinyWet))
				&& GasFaceIntersection.coversRectangle(0,0,.5,List.of(new GasFaceIntersection.Plane(SignedDyadic.of(1),SignedDyadic.ZERO,SignedDyadic.ZERO))),
				"Rounded full area hid a true wet strip or rejected an exactly complete dry face");
	}

	private static void validateMicroGasPoreCapacity() {
		Grid grid=new Grid(2,2,2,1);java.util.Arrays.fill(grid.solid,true);
		for(int cell=0;cell<8;cell++)grid.cutCells.replaceMask(cell,255);
		grid.solid[0]=false;grid.cutCells.replaceMask(0,126);
		var pore=new mmm.granular.GranularCouplingFields(2,2,2,1);
		var phase=new FlipPressurePhaseGeometry(2,2,2);long[] fixed=new long[8];
		for(long volume:new long[]{1L<<22,(1L<<22)-1}) {
			fixed[0]=volume;pore.setFixedCellSvq(fixed);
			phase.rebuild(grid.solid,grid.liquidVolume,grid.cutCells,pore,new boolean[8]);
			var gas=GasComplementGeometry.capture(2,2,2,1,grid.solid,grid.cutCells,phase);
			check(gas.poreVolume(0).equals(mmm.units.SignedDyadic.of((1L<<22)-volume,-24)),"Gas geometry changed exact pore capacity");
			if(volume==1L<<22) check(gas.pieces().isEmpty() && gas.ports().isEmpty() && GasChamberTopology.capture(gas,true).chambers().isEmpty(),
					"A fully occupied pore component acquired a gas chamber");
			else check(gas.pieces().size()==1 && gas.piece(0)==null && gas.piece(7)!=null,
					"A zero-capacity disconnected component acquired gas support");
		}
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
				check(capacity.availableVolumeNormalized().equals(mmm.units.SignedDyadic.of(expected)),"Gas capacity changed the authoritative component remainder");
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
		byte[] masks=new byte[8];java.util.Arrays.fill(masks,(byte)255);masks[0]=126;
		var d=new FluidDomain(2,2,2,new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.5F,1e-4F,0),16);
		float[] pv={.125F,.2F,.3F,.03F,.8F,.875F,.7F,.06F};
		d.restoreParticleState(new FlipParticleState(2,2,2,1,2,.09F,pv,new float[6],masks));d.ensureInventoryGrid();
		var gas=GasComplementGeometry.capture(2,2,2,1,d.geometry.solid,d.geometry.cutCells,d.pressurePhaseGeometry);
		var capacities=GasComponentCapacity.capture(d,gas,null);
		check(capacities.stream().anyMatch(c->!c.geometricDifferenceNormalized().isZero()),"PLIC capacity certificate lacked an inexact-root control");
		check(gas.piece(0).offsetBracket()!=null,"Gas piece lost its captured inverse certificate");
		d.particles.setVolume(0,.04F);
		try {GasComponentCapacity.capture(d,gas,null);throw new AssertionError("Stale gas geometry accepted a different raw liquid stock");}
		catch(GasTopologyBlockedException expected) { }
	}

	private static void validateRuntimeGeometryBinding() {
		byte[] masks=new byte[8];java.util.Arrays.fill(masks,(byte)255);masks[0]=masks[1]=0;
		float[] markers={.25F,.5F,.5F,.5F,1.5F,.5F,.5F,.25F};
		var domain=gasRestoreTarget(new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.5F,1e-4F,0),
				new FlipParticleState(2,2,2,1,2,.75F,markers,new float[6],masks));domain.ensureInventoryGrid();
		long[] occupied=new long[8];long inventoryRevision=domain.markerGrid.inventoryRevision;
		long phaseRevision=domain.pressurePhaseGeometry.revision(),carrierRevision=domain.carrierMutationRevision();
		var current=GasRuntimeGeometry.capture(domain,null,occupied,true);
		check(!current.cellDependsOnNeighborFractions(0) && current.cellDependsOnNeighborFractions(1),
				"PLIC dependency classification lost the own-moment and centered-gradient cases");
		var volume=SignedDyadic.of(.125);
		var edit=new PhaseLiquidGeometryEdit(0,0,List.of(new LiquidComponentMoments(0,255,1,volume,
				volume.multiply(SignedDyadic.of(.25)),volume.multiply(SignedDyadic.of(.5)),volume.multiply(SignedDyadic.of(.5)))));
		var future=GasRuntimeGeometry.capture(domain,edit,occupied,true);
		check(future.matchesSourceCarrierGeometry(domain,occupied) && !future.matchesCarrierGeometry(domain,occupied)
				&& future.liquidStock().get(0).equals(edit) && future.liquidStock().get(1).equals(current.liquidStock().get(1)),
				"Future gas frame lost its complete before/after liquid tuples");
		check(current.planeNormal(1,0,0)>0 && future.planeNormal(1,0,0)<0,
				"Future liquid edit failed to rebuild a centered neighbor's PLIC gradient");
		checkLocalBoilPlanes(domain,current,edit,future);
		var rebuiltCapacity=GasChamberCapacity.capture(domain,future.geometry(),future.topology(),occupied,future.liquidStock(),current);
		check(rebuiltCapacity.components().get(1)!=current.capacity().components().get(1)
				&& rebuiltCapacity.components().equals(future.capacity().components())
				&& rebuiltCapacity.chambers().equals(future.capacity().chambers()),
				"An unchanged liquid tuple reused a changed neighbor PLIC capacity certificate");
		check(domain.markerGrid.inventoryRevision==inventoryRevision && domain.pressurePhaseGeometry.revision()==phaseRevision
				&& domain.carrierMutationRevision()==carrierRevision && domain.particles.volume(0)==.5F
				&& domain.gasState.frame==null,"Private gas frame capture changed live owners or geometry caches");
		domain.gasState.frame=current;
		var repeated=GasRuntimeGeometry.capture(domain,null,occupied,true);
		var editedFromCaptured=GasRuntimeGeometry.capture(domain,edit,occupied,true);
		var phaseSource=GasRuntimeGeometry.preparePhaseSource(domain);
		check(phaseSource!=null,"Matching raw phase source was not prepared");
		var preparedTrial=GasRuntimeGeometry.capturePhaseEdit(domain,edit,occupied,true,phaseSource);
		var preparedReset=GasRuntimeGeometry.capturePhaseEdit(domain,current.liquidStock().get(0),occupied,true,phaseSource);
		var preparedAgain=GasRuntimeGeometry.capturePhaseEdit(domain,edit,occupied,true,phaseSource);
		checkRuntimePlanesEqual(preparedTrial,editedFromCaptured,"Prepared phase source changed candidate planes");
		checkRuntimePlanesEqual(preparedReset,current,"Previous phase trial leaked its target workspace into the next trial");
		checkRuntimePlanesEqual(preparedAgain,preparedTrial,"Repeated phase trial mutated an already prepared frame");
		check(java.util.Arrays.equals(preparedTrial.pieceNodes(),editedFromCaptured.pieceNodes())
				&& preparedTrial.capacity().components().equals(editedFromCaptured.capacity().components())
				&& preparedTrial.capacity().chambers().equals(editedFromCaptured.capacity().chambers())
				&& preparedTrial.matchesSourceCarrierGeometry(domain,occupied),"Prepared phase source changed capacities or source binding");
		domain.gasState.frame=null;
		try { GasRuntimeGeometry.capturePhaseEdit(domain,edit,occupied,true,phaseSource); throw new AssertionError("Changed phase source owner was reused"); }
		catch(GasTopologyBlockedException expected) { }
		check(repeated.offsetBracket(0,0)==current.offsetBracket(0,0)
				&& repeated.offsetBracket(1,0)==current.offsetBracket(1,0),"Unchanged PLIC failed to reuse captured inverse-volume certificates");
		check(repeated.geometry().pieces().getFirst()==current.geometry().pieces().getFirst()
				&& repeated.geometry().ports().getFirst()==current.geometry().ports().getFirst()
				&& repeated.geometry().exteriorPorts().getFirst()==current.geometry().exteriorPorts().getFirst(),
				"Equal runtime microgeometry failed to reuse immutable pieces and face records");
		check(current.geometry().piece(1)!=null && current.geometry().piece(8)!=null
				&& repeated.geometry().ports().stream().noneMatch(port->port.first()==1 && port.axis()==0),
				"Reused gas pieces manufactured a port on their zero-area shared face");
		checkRuntimePlanesEqual(repeated,current,"Unchanged captured PLIC differs from a complete rebuild");
		checkRuntimePlanesEqual(editedFromCaptured,future,"Captured PLIC reused a changed gradient neighbor");
		var peerEdit=new PhaseLiquidGeometryEdit(1,0,List.of(new LiquidComponentMoments(1,255,1,volume,
				volume.multiply(SignedDyadic.of(1.5)),volume.multiply(SignedDyadic.of(.5)),volume.multiply(SignedDyadic.of(.5)))));
		var peerFresh=GasRuntimeGeometry.capture(domain,peerEdit,occupied,true);
		domain.gasState.frame=current;
		var peerCaptured=GasRuntimeGeometry.capture(domain,peerEdit,occupied,true);
		domain.gasState.frame=null;
		check(peerCaptured.offsetBracket(0,0)==current.offsetBracket(0,0)
				&& peerCaptured.geometry().piece(0)==current.geometry().piece(0),
				"A changed neighbor fraction rebuilt an unchanged single-component own-moment PLIC");
		checkRuntimePlanesEqual(peerCaptured,peerFresh,"Own-moment PLIC reuse differs from independent full capture");
		domain.particles.setVolume(0,.125F);
		check(future.matchesCarrierGeometry(domain,occupied) && !future.matchesSourceCarrierGeometry(domain,occupied),
				"Published phase stock did not match its complete target frame");
		domain.particles.setVolume(0,.5F);domain.particles.setPositionX(0,.75F);
		var moved=GasRuntimeGeometry.capture(domain,null,occupied,true);
		check(moved.liquidStock().get(0).volume().equals(current.liquidStock().get(0).volume())
				&& moved.liquidStock().get(0).markerCount()==current.liquidStock().get(0).markerCount()
				&& domain.markerGrid.inventoryRevision==inventoryRevision && domain.carrierMutationRevision()==carrierRevision,
				"Position-only stale-frame fixture changed volume, count or epochs");
		check(!current.matchesCarrierGeometry(domain,occupied) && !current.matchesSourceCarrierGeometry(domain,occupied)
				&& moved.matchesCarrierGeometry(domain,occupied) && moved.planeNormal(0,0,0)<0,
				"Gas frame reused old M1 after a raw marker move with unchanged volume and count");
		domain.gasState.frame=current;
		check(GasRuntimeGeometry.preparePhaseSource(domain)==null,"Phase preparation reused raw M1 with unchanged epochs");
		try { GasRuntimeGeometry.capturePhaseEdit(domain,edit,occupied,true,phaseSource); throw new AssertionError("Raw position change reused a prepared phase source"); }
		catch(GasTopologyBlockedException expected) { }
		var movedFromCaptured=GasRuntimeGeometry.capture(domain,null,occupied,true);
		domain.gasState.frame=null;
		check(movedFromCaptured.offsetBracket(1,0)==current.offsetBracket(1,0),
				"Neighbor M1 changed a PLIC whose actual fraction-gradient inputs stayed equal");
		checkRuntimePlanesEqual(movedFromCaptured,moved,"Position-edited captured PLIC differs from a complete rebuild");
		domain.granularCoupling=new mmm.granular.GranularCouplingFields(2,2,2,1);
		occupied[0]=1L<<22;domain.granularCoupling.setFixedCellSvq(occupied);
		domain.particles.setVolume(0,.375F);
		var equalFractionFresh=GasRuntimeGeometry.capture(domain,null,occupied,true);
		domain.gasState.frame=current;
		var equalFractionCaptured=GasRuntimeGeometry.capture(domain,null,occupied,true);
		domain.gasState.frame=null;
		check(equalFractionCaptured.offsetBracket(1,0)==current.offsetBracket(1,0),
				"Neighbor SVQ and volume changes invalidated an unchanged actual fraction-gradient input");
		checkRuntimePlanesEqual(equalFractionCaptured,equalFractionFresh,"Pore-edited captured PLIC differs from a complete rebuild");
		var clear=GasRuntimeGeometry.allDry(domain,true);
		check(clear.matchesSourceCarrierGeometry(domain,occupied) && !clear.matchesSourceCarrierGeometry(domain,new long[8])
				&& !clear.matchesCarrierGeometry(domain,new long[8]) && clear.capacity().chambers().getFirst().cubicMeters()==2,
				"Complete clear frame failed to bind source occupancy or remove target solid capacity");
		check(clear.liquidStock().get(0).markerCount()==0 && clear.liquidStock().get(1).volume().equals(SignedDyadic.ZERO)
				&& domain.particles.count()==2 && domain.granularCoupling.solidSvq(0)==1L<<22,
				"Clear preview omitted zero-stock components or changed source inventory");
		domain.particles.setCount(0);domain.granularCoupling.setFixedCellSvq(new long[8]);
		check(clear.matchesCarrierGeometry(domain,new long[8]) && !clear.matchesSourceCarrierGeometry(domain,new long[8]),
				"Published clear stock failed its target/source geometry distinction");
		for (int mask : new int[]{254,126}) {
			var masked = mask == 254 ? pieceCapacityDomain(mask,.125F,.25F,.25F,.0625F)
					: pieceCapacityDomain(mask,.125F,.25F,.25F,.0625F,.875F,.75F,.75F,.03125F);
			masked.geometry.cutCells.replaceMask(1,0); masked.geometry.solid[1]=false;
			var own = GasRuntimeGeometry.capture(masked,null,new long[8],true);
			check(!own.cellDependsOnNeighborFractions(0),"Masked own-moment planes retained a false neighbor dependency");
			var neighborVolume=SignedDyadic.of(.125);
			var neighborEdit=new PhaseLiquidGeometryEdit(1,0,List.of(new LiquidComponentMoments(1,255,1,neighborVolume,
					neighborVolume.multiply(SignedDyadic.of(1.25)),neighborVolume.multiply(SignedDyadic.of(.5)),neighborVolume.multiply(SignedDyadic.of(.5)))));
			var neighborFresh=GasRuntimeGeometry.capture(masked,neighborEdit,new long[8],true);
			masked.gasState.frame=own;
			var neighborChanged=GasRuntimeGeometry.capture(masked,neighborEdit,new long[8],true);
			masked.gasState.frame=null;
			checkRuntimePlanesEqual(neighborChanged,neighborFresh,"Masked own-moment PLIC reuse differs from independent capture");
			if(mask==254)check(neighborChanged.offsetBracket(0,0)==own.offsetBracket(0,0),
					"An unchanged masked single-component own-moment PLIC lost its captured certificate");
			checkLocalBoilPlanes(masked,own,neighborEdit,neighborChanged);
			for (int micro=0;micro<8;micro++) check(java.util.Objects.equals(own.geometry().piece(micro),neighborChanged.geometry().piece(micro)),
					"Neighbor fraction changed a masked component's independent own-moment certificate");
			masked.particles.setPositionX(0,.25F);
			var centered=GasRuntimeGeometry.capture(masked,null,new long[8],true);
			check(centered.cellDependsOnNeighborFractions(0),"A centered masked component lost its actual fraction-gradient dependency");
			var centeredFresh=GasRuntimeGeometry.capture(masked,neighborEdit,new long[8],true);
			masked.gasState.frame=centered;
			var centeredCaptured=GasRuntimeGeometry.capture(masked,neighborEdit,new long[8],true);
			masked.gasState.frame=null;
			check(centeredCaptured.offsetBracket(0,0)!=centered.offsetBracket(0,0),
					"A centered own moment reused a PLIC after its actual neighbor-gradient input changed");
			checkRuntimePlanesEqual(centeredCaptured,centeredFresh,"Centered PLIC rebuild differs from independent capture");
			checkLocalBoilPlanes(masked,centered,neighborEdit,centeredCaptured);
		}
	}

	private static void checkLocalBoilPlanes(FluidDomain domain,GasRuntimeGeometry source,PhaseLiquidGeometryEdit edit,GasRuntimeGeometry future) {
		var previous=domain.gasState.frame;
		try {
			var field=GasLiquidPhaseChangeStage.class.getDeclaredField("support");field.setAccessible(true);
			var support=(PhaseChangeStageSupport)field.get(domain.gasLiquidPhaseChangeStage);support.prepareGranularOccupancy();
			var groups=new java.util.TreeMap<Integer,java.util.List<Integer>>();
			for(var piece:source.geometry().pieces())groups.computeIfAbsent(source.nodeOfPiece(piece.id()),ignored->new java.util.ArrayList<>()).add(piece.id());
			for(var group:groups.entrySet()) {
				domain.gasState.frame=source;
				var contact=new GasPhaseInterfaceResolver.Candidate(group.getKey(),~edit.collisionMask()&255,group.getValue(),null);
				var prepared=GasRuntimeGeometry.prepareBoilContactPlanes(support,edit.cell(),contact);
				check(prepared!=null,"Matching local phase inputs failed to prepare contact-only PLIC");
				var local=prepared.rebuild(edit);boolean alive=false;
				for(int piece:group.getValue()) {
					int cell=piece/8;var before=source.geometry().piece(piece);var after=future.geometry().piece(piece);
					if(after!=null && (before.dryPlane()==null || after.dryPlane()==null || before.dryPlane().equals(after.dryPlane())
							||GasGeometryOverlap.normalizedVolume(piece&7,List.of(before.dryPlane(),after.dryPlane())).signum()>0))alive=true;
					for(int micro=0;micro<8;micro++) {
						check(Double.doubleToRawLongBits(local.liquidFractionExact(cell,micro))==Double.doubleToRawLongBits(future.liquidFractionExact(cell,micro))
								&&Double.doubleToRawLongBits(local.planeOffset(cell,micro))==Double.doubleToRawLongBits(future.planeOffset(cell,micro))
								&&java.util.Objects.equals(local.offsetBracket(cell,micro),future.offsetBracket(cell,micro)),"Local/full contact PLIC certificate differs");
						for(int axis=0;axis<3;axis++)check(Float.floatToRawIntBits(local.planeNormal(cell,micro,axis))
								==Float.floatToRawIntBits(future.planeNormal(cell,micro,axis)),"Local/full contact normal differs");
					}
				}
				var resolver=new GasPhaseInterfaceResolver(support);
				boolean witness=group.getValue().stream().anyMatch(piece->{
					int cell=piece/8,sx=source.geometry().sizeX(),sy=source.geometry().sizeY();
					int x=cell%sx,y=cell/sx%sy,z=cell/(sx*sy),ex=edit.cell()%sx,ey=edit.cell()/sx%sy,ez=edit.cell()/(sx*sy);
					boolean adjacent=x==ex&&y==ey&&Math.abs(z-ez)==1 || x==ex&&z==ez&&Math.abs(y-ey)==1 || y==ey&&z==ez&&Math.abs(x-ex)==1;
					return cell!=edit.cell()&&(!adjacent||!source.cellDependsOnNeighborFractions(cell));
				});
				check(prepared.unchangedContact==witness,"A gradient-dependent or edited contact acquired an unchanged-piece witness");
				var bracket=local.offsetBracket(group.getValue().getFirst()/8,group.getValue().getFirst()&7);
				check(resolver.mayKeepBoilContact(prepared,edit,contact)==alive,"Local contact survival differs from full same-micro intersections");
				if(witness)check(bracket==local.offsetBracket(group.getValue().getFirst()/8,group.getValue().getFirst()&7),
						"An unchanged positive contact unnecessarily rebuilt the local PLIC scratch");
				domain.gasState.frame=null;
				check(resolver.mayKeepBoilContact(prepared,edit,contact),"A changed preparing frame acquired a zero-contact rejection");
				domain.gasState.frame=source;
				float x=domain.particles.positionX(0);domain.particles.setPositionX(0,Math.nextUp(x));
				check(GasRuntimeGeometry.prepareBoilContactPlanes(support,edit.cell(),contact)==null,"Changed local moments retained a contact PLIC source");
				domain.particles.setPositionX(0,x);
				int mask=domain.geometry.cutCells.mask(edit.cell());domain.geometry.cutCells.replaceMask(edit.cell(),mask^1);
				check(GasRuntimeGeometry.prepareBoilContactPlanes(support,edit.cell(),contact)==null,"Changed local masks retained a contact PLIC source");
				domain.geometry.cutCells.replaceMask(edit.cell(),mask);
			}
		} catch(ReflectiveOperationException failure) { throw new AssertionError("Local contact fixture binding failed",failure); }
		finally { domain.gasState.frame=previous; }
	}

	private static void checkRuntimePlanesEqual(GasRuntimeGeometry actual, GasRuntimeGeometry expected, String message) {
		check(actual.liquidStock().equals(expected.liquidStock()) && actual.geometry().pieces().equals(expected.geometry().pieces())
				&& actual.geometry().ports().equals(expected.geometry().ports()) && actual.geometry().exteriorPorts().equals(expected.geometry().exteriorPorts())
				&& actual.capacity().components().equals(expected.capacity().components()) && actual.capacity().chambers().equals(expected.capacity().chambers()),message);
		for(int cell=0;cell<actual.liquidStock().size();cell++) for(int micro=0;micro<8;micro++) {
			check(Double.doubleToRawLongBits(actual.liquidFractionExact(cell,micro))==Double.doubleToRawLongBits(expected.liquidFractionExact(cell,micro))
					&& Double.doubleToRawLongBits(actual.planeOffset(cell,micro))==Double.doubleToRawLongBits(expected.planeOffset(cell,micro))
					&& java.util.Objects.equals(actual.offsetBracket(cell,micro),expected.offsetBracket(cell,micro)),message);
			for(int axis=0;axis<3;axis++) check(Float.floatToRawIntBits(actual.planeNormal(cell,micro,axis))
					==Float.floatToRawIntBits(expected.planeNormal(cell,micro,axis)),message);
		}
	}

	private static void validateGasPieceCapacity() {
		var fixed=pieceCapacityDomain(0,.25F,.5F,.5F,.25F);
		fixed.granularCoupling=new mmm.granular.GranularCouplingFields(2,2,2,1);
		long[] occupied=new long[8];occupied[0]=1L<<22;fixed.granularCoupling.setFixedCellSvq(occupied);
		var geometry=pieceCapacityGeometry(fixed);var component=GasComponentCapacity.capture(fixed,geometry,null).getFirst();
		check(geometry.piece(1).offsetBracket().targetVolume().equals(ExactRational.ONE.divide(3)),
				"Pore-capacity fixture must use exact wet volume one third");
		for(var piece:component.pieces()) if((piece.id()&1)==1)
			check(piece.lower().equals(ExactRational.of(3).divide(32)) && piece.upper().equals(piece.lower())
					&& piece.normalizedVolume().equals(piece.lower()),"Inverse compensation moved a fully dry zero-width leaf");
		var topology=GasChamberTopology.capture(geometry,true);var liveOccupancy=fixed.granularCoupling;
		fixed.granularCoupling=null;
		try { GasChamberCapacity.capture(fixed,geometry,topology,null);throw new AssertionError("Live occupancy silently replaced planned solid stock"); }
		catch(GasTopologyBlockedException expected) { }
		var planned=GasChamberCapacity.capture(fixed,geometry,topology,null,occupied);
		occupied[0]=0;
		check(planned.chambers().getFirst().cubicMeters()==.5,"Planned coherent occupancy was omitted or retained a mutable alias");
		fixed.granularCoupling=liveOccupancy;
		checkPieceCapacityBounds(fixed,geometry,true);

		var general=pieceCapacityDomain(0,.2F,.3F,.4F,.07123456F,.7F,.6F,.8F,.0312F);
		var generalGeometry=pieceCapacityGeometry(general);
		checkPieceCapacityBounds(general,generalGeometry,false);
		check(GasComponentCapacity.capture(general,generalGeometry,null).getFirst().pieces().stream()
				.anyMatch(p->p.roundingErrorBound()>0),"Gas capacity rounding fixture lacks an inexact physical leaf");
		var source=GasRuntimeGeometry.capture(general,null,new long[8],true);
		var reusable=GasChamberCapacity.capture(general,source.geometry(),source.topology(),new long[8],source.liquidStock(),source);
		check(reusable.components().getFirst()==source.capacity().components().getFirst()
				&& reusable.components().equals(source.capacity().components())
				&& reusable.chambers().equals(source.capacity().chambers()),
				"Unchanged local raw stock and geometry failed to reuse its certified capacity");
		byte[] mergeMasks=new byte[8];java.util.Arrays.fill(mergeMasks,(byte)255);mergeMasks[0]=(byte)254;mergeMasks[1]=0;
		var merger=gasRestoreTarget(new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.5F,1e-4F,0),
				new FlipParticleState(2,2,2,1,0,0,new float[0],new float[0],mergeMasks));
		var divided=GasRuntimeGeometry.capture(merger,null,new long[8],true);
		merger.geometry.cutCells.replaceMask(0,0);
		var joined=GasRuntimeGeometry.capture(merger,null,new long[8],true);
		var joinedReuse=GasChamberCapacity.capture(merger,joined.geometry(),joined.topology(),new long[8],joined.liquidStock(),divided);
		check(divided.nodeCount()==2 && joined.nodeCount()==1
				&& joinedReuse.components().get(1)==divided.capacity().components().get(1)
				&& joinedReuse.components().get(0)!=divided.capacity().components().get(0)
				&& joinedReuse.pieces().equals(joined.capacity().pieces()) && joinedReuse.chambers().equals(joined.capacity().chambers()),
				"Merged nodes failed to reassign reused local capacity exactly as a fresh capture");
		merger.geometry.cutCells.replaceMask(0,254);
		var dividedAgain=GasRuntimeGeometry.capture(merger,null,new long[8],true);
		var dividedReuse=GasChamberCapacity.capture(merger,dividedAgain.geometry(),dividedAgain.topology(),new long[8],dividedAgain.liquidStock(),joined);
		check(dividedAgain.nodeCount()==2 && dividedReuse.components().get(1)==joined.capacity().components().get(1)
				&& dividedReuse.pieces().equals(dividedAgain.capacity().pieces())
				&& dividedReuse.chambers().equals(dividedAgain.capacity().chambers()),
				"Split nodes failed to reassign reused local capacity exactly as a fresh capture");

		var split=pieceCapacityDomain(126,.25F,.25F,.25F,.0625F,.75F,.75F,.75F,.03125F);
		var splitGeometry=pieceCapacityGeometry(split);var closed=GasChamberTopology.capture(splitGeometry,true);
		var closedCapacity=GasChamberCapacity.capture(split,splitGeometry,closed,null);
		check(closedCapacity.chambers().size()==2 && closed.nodeOfPiece(0)!=closed.nodeOfPiece(7)
				&& closedCapacity.chambers().get(0).cubicMeters()==.0625 && closedCapacity.chambers().get(1).cubicMeters()==.09375,
				"Two same-cell gas owners lost their distinct raw capacities");
		var componentFrame=GasRuntimeGeometry.capture(split,null,new long[8],true);
		split.gasState.frame=componentFrame;
		var componentReuse=GasRuntimeGeometry.capture(split,null,new long[8],true);
		split.gasState.frame=null;
		check(componentReuse.offsetBracket(0,0)==componentFrame.offsetBracket(0,0)
				&& componentReuse.offsetBracket(0,7)==componentFrame.offsetBracket(0,7),
				"Disconnected liquid components failed to retain their independent captured PLIC certificates");
		checkRuntimePlanesEqual(componentReuse,componentFrame,"Captured disconnected-component PLIC differs from a complete rebuild");
		for(float h:new float[]{.1F,.5F}) {
			var scaled=pieceCapacityScaledDomain(h,126,.25F,.25F,.25F,.0625F,.75F,.75F,.75F,.03125F);
			var scaledGeometry=pieceCapacityGeometry(scaled);
			var scaledCapacity=GasChamberCapacity.capture(scaled,scaledGeometry,GasChamberTopology.capture(scaledGeometry,true),null);
			double metric=h, cubic=metric*metric*metric;
			check(scaledCapacity.chambers().get(0).cubicMeters()==.0625*cubic
					&& scaledCapacity.chambers().get(1).cubicMeters()==.09375*cubic,"Gas chamber raw capacity lost its cubic SI metric");
			checkPieceCapacityBounds(scaled,scaledGeometry,true);
		}
		var open=GasChamberTopology.capture(splitGeometry,false);var openCapacity=GasChamberCapacity.capture(split,splitGeometry,open,null);
		check(open.nodeOfPiece(0)==1 && open.nodeOfPiece(7)==2 && openCapacity.ambientCubicMeters()==0
				&& openCapacity.chambers().get(0).cubicMeters()==.0625 && openCapacity.chambers().get(1).cubicMeters()==.09375,
				"A partial exterior aperture lost its finite capacity or consumed the isolated same-cell gas capacity");
		try { GasChamberCapacity.capture(split,pieceCapacityGeometry(split),closed,null);throw new AssertionError("Gas capacity accepted a topology from another geometry capture"); }
		catch(IllegalArgumentException expected) { }

		float a=Math.nextDown(.5F),b=Math.nextDown(Math.scalb(1F,-25)),c=(float)(Math.scalb(1.0,-49)-Math.scalb(1.0,-56));
		var missing=pieceCapacityDomain(0,.25F,.5F,.5F,a,.25F,.5F,.5F,b,.25F,.5F,.5F,c);
		var missingGeometry=pieceCapacityGeometry(missing);var bracket=missingGeometry.pieces().getFirst().offsetBracket();
		var raw=SignedDyadic.of(1).subtract(SignedDyadic.of(a).add(SignedDyadic.of(b)).add(SignedDyadic.of(c)));
		check(raw.equals(SignedDyadic.of(.5).add(SignedDyadic.of(1,-56))) && missingGeometry.pieces().size()==4
				&& bracket.offset()==.5 && bracket.lower()==Math.nextDown(.5),"Missing-leaf fixture lost its normal three-float exact stock");
		check(capacityRational(raw).compareTo(ExactRational.ONE.subtract(bracket.lowerVolume()))<=0,
				"Missing-leaf fixture must fit the whole-component inverse certificate");
		try { GasComponentCapacity.capture(missing,missingGeometry,null);throw new AssertionError("Raw gas capacity was assigned to absent selected pieces"); }
		catch(GasTopologyBlockedException expected) {
			check(expected.getMessage().contains("cannot fit its selected positive pieces"),"Missing gas leaf was rejected for another property");
		}
	}

	private static FluidDomain pieceCapacityDomain(int mask,float... positions) {
		return pieceCapacityScaledDomain(1,mask,positions);
	}
	private static FluidDomain pieceCapacityScaledDomain(float h,int mask,float... positions) {
		positions=positions.clone();
		int count=positions.length/4;float volume=0;for(int i=0;i<count;i++)volume+=positions[4*i+3];
		for(int i=0;i<count;i++)for(int axis=0;axis<3;axis++)positions[4*i+axis]*=h;
		byte[] masks=new byte[8];java.util.Arrays.fill(masks,(byte)255);masks[0]=(byte)mask;
		var domain=new FluidDomain(2,2,2,new FluidSolverConfig(h,1000,0,.01F,200,1e-5F,.5F,1e-4F,0));
		domain.restoreParticleState(new FlipParticleState(2,2,2,h,count,volume,positions,new float[3*count],masks));domain.ensureInventoryGrid();return domain;
	}
	private static GasComplementGeometry pieceCapacityGeometry(FluidDomain domain) {
		domain.pressurePhaseGeometry.rebuild(domain.geometry.solid,domain.markerGrid.exactCellMarkerVolume,domain.geometry.cutCells,
				domain.granularCoupling,new boolean[8],domain.markerGrid,domain.particles,domain.config.cellSize(),null);
		return GasComplementGeometry.capture(2,2,2,domain.config.cellSize(),domain.geometry.solid,domain.geometry.cutCells,domain.pressurePhaseGeometry);
	}
	private static ExactRational capacityRational(SignedDyadic value) {
		return value.exponent()>=0 ? new ExactRational(value.significand().shiftLeft(value.exponent()),java.math.BigInteger.ONE)
				:new ExactRational(value.significand(),java.math.BigInteger.ONE.shiftLeft(-value.exponent()));
	}
	private static void checkPieceCapacityBounds(FluidDomain domain,GasComplementGeometry geometry,boolean sealed) {
		var topology=GasChamberTopology.capture(geometry,sealed);var frame=GasChamberCapacity.capture(domain,geometry,topology,null);
		ExactRational[] intended=new ExactRational[topology.chambers().size()+1];java.util.Arrays.fill(intended,ExactRational.ZERO);
		var scale=capacityRational(SignedDyadic.of(geometry.cellVolumeCubicMeters()));
		for(var component:GasComponentCapacity.capture(domain,geometry,null)) {
			var sum=ExactRational.ZERO;var stored=SignedDyadic.ZERO;GasPieceCapacity.Piece pivot=null;
			for(var piece:component.pieces()) {
				var geometric=geometry.piece(piece.id());
				if(geometric.dryPlane()!=null) {
					var plane=geometric.dryPlane();var bracket=geometric.offsetBracket();
					var porosity=capacityRational(component.poreVolumeNormalized()).multiply(8).divide(Integer.bitCount(component.supportMask()));
					var lowerPlane=new GasGeometryOverlap.HalfSpace(plane.nx(),plane.ny(),plane.nz(),SignedDyadic.of(bracket.upper()));
					var upperPlane=new GasGeometryOverlap.HalfSpace(plane.nx(),plane.ny(),plane.nz(),SignedDyadic.of(bracket.lower()));
					check(piece.lower().equals(MicroBoxPlaneVolume.dryVolume(geometric.micro(),lowerPlane).multiply(porosity))
							&& piece.upper().equals(MicroBoxPlaneVolume.dryVolume(geometric.micro(),upperPlane).multiply(porosity)),
							"Gas piece reused a different PLIC endpoint's exact capacity");
				}
				check(piece.normalizedVolume().signum()>0 && piece.normalizedVolume().compareTo(piece.lower())>=0
						&& piece.normalizedVolume().compareTo(piece.upper())<=0,"Raw leaf capacity escaped its inverse certificate");
				sum=sum.add(piece.normalizedVolume());stored=stored.add(SignedDyadic.of(piece.cubicMeters()));
				var target=piece.normalizedVolume().multiply(scale);var error=capacityRational(SignedDyadic.of(piece.cubicMeters())).subtract(target);
				check((error.signum()<0?error.multiply(-1):error).compareTo(capacityRational(SignedDyadic.of(piece.roundingErrorBound())))<=0,
						"Leaf SI rounding error exceeds its outward bound");
				int node=topology.nodeOfPiece(piece.id());intended[node]=intended[node].add(target);
				if(pivot==null || piece.normalizedVolume().compareTo(pivot.normalizedVolume())>0)pivot=piece;
			}
			check(sum.equals(capacityRational(component.availableVolumeNormalized())),"Leaf capacities do not close exact raw component stock");
			if(pivot!=null) {var error=capacityRational(stored).subtract(sum.multiply(scale));
				check((error.signum()<0?error.multiply(-1):error).compareTo(capacityRational(SignedDyadic.of(Math.ulp(pivot.cubicMeters())).multiply(SignedDyadic.of(1,-1))))<=0,
						"Raw component SI closure exceeds the pivot half ULP");}
		}
		for(var chamber:frame.chambers()) checkCapacityNodeBound(chamber.cubicMeters(),chamber.roundingErrorBound(),intended[chamber.node()]);
		checkCapacityNodeBound(frame.ambientCubicMeters(),frame.ambientRoundingErrorBound(),intended[0]);
	}
	private static void checkCapacityNodeBound(double volume,double bound,ExactRational intended) {
		var error=capacityRational(SignedDyadic.of(volume)).subtract(intended);
		check((error.signum()<0?error.multiply(-1):error).compareTo(capacityRational(SignedDyadic.of(bound)))<=0,
				"Gas node capacity rounding error exceeds its SI bound");
	}

	private static void validateMicroGasOverlap() {
		validateQuadraticMicroMoments();
		var zero=mmm.units.SignedDyadic.ZERO;
		var one=mmm.units.SignedDyadic.of(1);
		var quarter=mmm.units.SignedDyadic.of(.25);
		var exact=mmm.chemistry.ExactRational.ONE;
		var tinyTarget=mmm.chemistry.ExactRational.ofFloat(Float.MIN_VALUE).divide(3);
		var inverse=PlicPlaneOffset.solve(1,Float.MIN_VALUE,1,0,tinyTarget);
		var tinyNormal=mmm.units.SignedDyadic.of(Float.MIN_VALUE);
		for(double offset:new double[]{inverse.lower(),inverse.upper()}) {
			var wet=new GasGeometryOverlap.HalfSpace(tinyNormal.negate(),one.negate(),zero,mmm.units.SignedDyadic.of(-offset));
			var measured=GasGeometryOverlap.normalizedVolume(0,List.of(wet));
			check(measured.equals(offset==inverse.lower()?inverse.lowerVolume():inverse.upperVolume()),"PLIC inverse certificate differs from independent polyhedron volume");
			check(measured.equals(GasGeometryOverlap.normalizedVolume(0,List.of(wet,wet))),
					"Coincident thin gas planes differ from the certified single-plane volume");
			check(GasGeometryOverlap.hasPositiveOverlap(0,wet,wet)==(measured.signum()>0),
					"Coincident extreme-tail planes changed overlap positivity");
		}
		check(inverse.lowerVolume().compareTo(tinyTarget)<=0 && inverse.upperVolume().compareTo(tinyTarget)>=0
				&& Math.nextUp(inverse.lower())==inverse.upper(),"Tiny PLIC volume lacks adjacent enclosing offsets");
		check(inverse.offset()>0 && inverse.offset()<1e-44,"PLIC inversion manufactured a finite layer from an extreme tail");
		for(float[] normal:new float[][]{{1,0,0},{-1,0,0},{0,-1,0},{0,0,1},{0,1,-.25F},{.1F,-.3F,1},{Float.MIN_VALUE,0,0},
				{Float.MIN_VALUE,1,0},{Float.MAX_VALUE,-Float.MIN_VALUE,1}})
			for(int support=1;support<=255;support++)for(int fraction:new int[]{0,1,3}) {
				var target=ExactRational.of(fraction*Integer.bitCount(support)).divide(24);
				var result=PlicPlaneOffset.solve(support,normal[0],normal[1],normal[2],target);
				var coefficients=new SignedDyadic[]{SignedDyadic.of(normal[0]),SignedDyadic.of(normal[1]),SignedDyadic.of(normal[2])};
				var cdf=new MicroBoxPlaneVolume.SelectedVolume(support,coefficients);
				var measured=new ExactRational[3]; int index=0;
				for(double offset:new double[]{result.lower(),result.upper(),.375}) {
					var plane=new GasGeometryOverlap.HalfSpace(coefficients[0],coefficients[1],coefficients[2],SignedDyadic.of(offset));
					var sum=ExactRational.ZERO;
					for(int micro=0;micro<8;micro++)if((support & 1<<micro)!=0)sum=sum.add(MicroBoxPlaneVolume.wetVolume(micro,plane));
					check(cdf.volume(offset).equals(sum),"Prepared PLIC CDF differs from independent exact leaf integration");
					measured[index++]=sum;
				}
				check(result.lowerVolume().equals(measured[0]) && result.upperVolume().equals(measured[1])
						&& measured[0].compareTo(target)<=0 && measured[1].compareTo(target)>=0,
						"Prepared PLIC CDF changed the exact enclosing endpoint volumes");
				check(result.lower()==result.upper()?measured[0].equals(target):Math.nextUp(result.lower())==result.upper(),
						"Prepared PLIC CDF changed the binary64 adjacent-offset certificate");
				double selected=target.multiply(2).compareTo(measured[0].add(measured[1]))<=0?result.lower():result.upper();
				check(Double.doubleToRawLongBits(selected)==Double.doubleToRawLongBits(result.offset()),
						"Prepared PLIC CDF changed the selected offset's raw bits");
			}
		var tetra=new GasGeometryOverlap.HalfSpace(one.negate(),one.negate(),one.negate(),mmm.units.SignedDyadic.of(-.5));
		check(GasGeometryOverlap.normalizedVolume(0,List.of(tetra)).equals(exact.divide(48)),
				"Gas overlap did not retain the exact tetrahedron volume");
		for(int slot=0;slot<8;slot++) check(MicroBoxPlaneVolume.dryVolume(slot,tetra).equals(GasGeometryOverlap.normalizedVolume(slot,List.of(tetra))),
				"Microbox CDF disagrees with exact tetrahedron clipping");
		for(int slot=1;slot<8;slot++) check(GasGeometryOverlap.normalizedVolume(slot,List.of(tetra)).isZero(),
				"Gas tetrahedron tangency manufactured positive volume");
		for(int axis=0;axis<3;axis++) {
			var normal=new mmm.units.SignedDyadic[]{zero,zero,zero};normal[axis]=one;
			var lower=new GasGeometryOverlap.HalfSpace(normal[0],normal[1],normal[2],quarter);
			var tangent=new GasGeometryOverlap.HalfSpace(normal[0].negate(),normal[1].negate(),normal[2].negate(),quarter.negate());
			var thickness=mmm.units.SignedDyadic.of(1,-100);
			var upper=new GasGeometryOverlap.HalfSpace(normal[0].negate(),normal[1].negate(),normal[2].negate(),quarter.add(thickness).negate());
			for(int slot=0;slot<8;slot++) {
				check(GasGeometryOverlap.normalizedVolume(slot,List.of()).equals(exact.divide(8)),"Empty gas overlap constraints changed an open microbox");
				check(GasGeometryOverlap.normalizedVolume(slot,List.of(lower,tangent)).isZero(),"Coincident gas planes manufactured overlap");
				var expected=(slot>>axis&1)==0?new mmm.chemistry.ExactRational(java.math.BigInteger.ONE,java.math.BigInteger.ONE.shiftLeft(102))
						:mmm.chemistry.ExactRational.ZERO;
				check(GasGeometryOverlap.normalizedVolume(slot,List.of(lower,upper)).equals(expected),"Sub-ULP positive gas overlap was lost");
				check(GasGeometryOverlap.normalizedVolume(slot,List.of(upper,lower)).equals(expected),"Gas overlap depends on clipping order");
				check(GasGeometryOverlap.hasPositiveOverlap(slot,lower,upper)==(expected.signum()>0)
						&& !GasGeometryOverlap.hasPositiveOverlap(slot,lower,tangent),"Exact overlap positivity lost a thin layer or admitted tangency");
				check(GasGeometryOverlap.normalizedVolume(slot,List.of(lower,lower)).equals(GasGeometryOverlap.normalizedVolume(slot,List.of(lower))),
						"Repeated gas plane double-counted its overlap");
			}
		}
		var constraints=new java.util.ArrayList<GasGeometryOverlap.HalfSpace>();
		for(int axis=0;axis<3;axis++) for(int sign:new int[]{-1,1}) {
			var normal=new SignedDyadic[]{zero,zero,zero};normal[axis]=SignedDyadic.of(sign);
			constraints.add(new GasGeometryOverlap.HalfSpace(normal[0],normal[1],normal[2],SignedDyadic.of(sign*.25)));
		}
		constraints.add(tetra);
		constraints.add(new GasGeometryOverlap.HalfSpace(one,one,one,SignedDyadic.of(.3)));
		constraints.add(new GasGeometryOverlap.HalfSpace(one.negate(),one.negate(),one.negate(),SignedDyadic.of(-Math.nextUp(.3))));
		constraints.add(new GasGeometryOverlap.HalfSpace(one,one.negate(),SignedDyadic.of(.5),SignedDyadic.of(.1)));
		constraints.add(new GasGeometryOverlap.HalfSpace(one.negate(),one,SignedDyadic.of(-.5),SignedDyadic.of(-.1)));
		constraints.add(new GasGeometryOverlap.HalfSpace(zero,zero,zero,zero));
		constraints.add(new GasGeometryOverlap.HalfSpace(zero,zero,zero,one));
		for(int slot=0;slot<8;slot++) for(var before:constraints) {
			var beforeVolume=GasGeometryOverlap.normalizedVolume(slot,List.of(before));
			check(GasGeometryOverlap.hasPositiveOverlap(slot,before,null)
					==(beforeVolume.signum()>0),"Single-plane positivity differs from exact volume");
			for(var after:constraints) {
				var afterVolume=GasGeometryOverlap.normalizedVolume(slot,List.of(after));
				var exactOverlap=GasGeometryOverlap.normalizedVolume(slot,List.of(before,after));
				check(GasGeometryOverlap.hasPositiveOverlap(slot,before,after)
						==(exactOverlap.signum()>0),"Two-plane positivity differs from exact volume");
				var firstPiece=new GasComplementGeometry.Piece(slot,0,slot,beforeVolume.toDouble(),beforeVolume,before,null);
				var secondPiece=new GasComplementGeometry.Piece(slot,0,slot,afterVolume.toDouble(),afterVolume,after,null);
				check(GasTopologyCoordinator.overlapVolume(firstPiece,secondPiece).equals(exactOverlap),
						"Certified gas containment differs from the independent exact intersection volume");
			}
		}
	}

	private static void validateQuadraticMicroMoments() {
		var total = new ExactRational[10]; java.util.Arrays.fill(total, ExactRational.ZERO);
		for (int micro = 0; micro < 8; micro++) {
			var q = GasGeometryOverlap.normalizedSecondMoments(micro, List.of());
			checkQuadraticPrefix(micro, List.of(), q);
			for (int a = 0, index = 4; a < 3; a++) for (int b = a; b < 3; b++) {
				var expected = a == b ? ExactRational.ONE.divide(96)
						: ExactRational.of((micro >> a & 1) == (micro >> b & 1) ? 1 : -1).divide(128);
				check(q[index++].equals(expected), "Full microbox quadratic integral differs from its analytic box moment");
			}
			for (int i = 0; i < 10; i++) total[i] = total[i].add(q[i]);
			for (int rotation = 0; rotation < 3; rotation++) {
				var normal = new SignedDyadic[3]; var lengths = new ExactRational[3]; var threshold = SignedDyadic.of(-1, -2);
				for (int a = 0; a < 3; a++) {
					long scale = 1L << (a + rotation) % 3; int sign = (micro >> a & 1) == 0 ? -1 : 1;
					normal[a] = SignedDyadic.of(sign * scale); lengths[a] = ExactRational.ONE.divide(4 * scale);
					if (sign > 0) threshold = threshold.add(SignedDyadic.of(scale));
				}
				var planes = List.of(new GasGeometryOverlap.HalfSpace(normal[0], normal[1], normal[2], threshold));
				var tetra = GasGeometryOverlap.normalizedSecondMoments(micro, planes); checkQuadraticPrefix(micro, planes, tetra);
				check(tetra[0].equals(ExactRational.ONE.divide(3072)), "Corner tetrahedron volume differs from abc/6");
				for (int a = 0, index = 4; a < 3; a++) for (int b = a; b < 3; b++) {
					int sign = (micro >> a & 1) == (micro >> b & 1) ? 1 : -1;
					var mean = a == b ? ExactRational.ONE.divide(4).subtract(lengths[a].divide(4)).add(lengths[a].multiply(lengths[a]).divide(10))
							: ExactRational.ONE.divide(4).subtract(lengths[a].add(lengths[b]).divide(8)).add(lengths[a].multiply(lengths[b]).divide(20));
					check(tetra[index++].equals(tetra[0].multiply(mean).multiply(sign)), "Reflected tetrahedron quadratic moment differs from analytic monomial integration");
				}
			}
		}
		check(total[0].equals(ExactRational.ONE), "Eight quadratic micro integrals lost whole-cell volume");
		for (int a = 0, index = 4; a < 3; a++) for (int b = a; b < 3; b++)
			check(total[index++].equals(a == b ? ExactRational.ONE.divide(12) : ExactRational.ZERO), "Whole-cell quadratic moment differs from the uniform cube");
		for (int axis = 0; axis < 3; axis++) {
			var normal = new SignedDyadic[]{SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO}; normal[axis] = SignedDyadic.of(1);
			var width = SignedDyadic.of(1, -100); var lower = new GasGeometryOverlap.HalfSpace(normal[0], normal[1], normal[2], SignedDyadic.of(1, -2));
			var upper = new GasGeometryOverlap.HalfSpace(normal[0].negate(), normal[1].negate(), normal[2].negate(), SignedDyadic.of(1, -2).add(width).negate());
			var planes = List.of(lower, upper); var q = GasGeometryOverlap.normalizedSecondMoments(0, planes); checkQuadraticPrefix(0, planes, q);
			check(java.util.Arrays.equals(q, GasGeometryOverlap.normalizedSecondMoments(0, List.of(upper, lower))), "Quadratic overlap depends on clipping order");
			var inverse = new ExactRational(q[0].denominator(), q[0].numerator());
			for (int a = 0, index = 4; a < 3; a++) for (int b = a; b < 3; b++) {
				var covariance = q[index++].multiply(inverse).subtract(q[a + 1].multiply(inverse).multiply(q[b + 1].multiply(inverse)));
				var expected = a != b ? ExactRational.ZERO : a == axis ? capacityRational(width.multiply(width)).divide(12) : ExactRational.ONE.divide(48);
				check(covariance.equals(expected), "Sub-ULP thin slab lost its exact positive covariance");
			}
			var tangent = new GasGeometryOverlap.HalfSpace(normal[0].negate(), normal[1].negate(), normal[2].negate(), SignedDyadic.of(-1, -2));
			for (var value : GasGeometryOverlap.normalizedSecondMoments(0, List.of(lower, tangent)))
				check(value.isZero(), "Tangential overlap acquired a quadratic measure");
			check(GasGeometryOverlap.positiveQuadrature(0, List.of(lower, tangent)).isEmpty(), "Tangential overlap acquired positive integration nodes");
		}
	}
	private static void checkQuadraticPrefix(int micro, List<GasGeometryOverlap.HalfSpace> planes, ExactRational[] quadratic) {
		check(quadratic[0].equals(GasGeometryOverlap.normalizedVolume(micro, planes)), "Quadratic integration changed its volume");
		var first = GasGeometryOverlap.normalizedMoments(micro, planes);
		for (int i = 0; i < 4; i++) check(quadratic[i].equals(first[i]), "Quadratic integration changed a retained first moment");
		var rule = GasGeometryOverlap.positiveQuadrature(micro, planes);
		var volume = ExactRational.ZERO; var observed = new double[10];
		for (var point : rule) {
			check(point.volume().signum() > 0, "Wet quadrature has a nonpositive weight");
			volume = volume.add(point.volume()); double weight = point.volume().toDouble(); var x = point.position();
			observed[0] += weight;
			for (int a = 0; a < 3; a++) {
				double low = (micro >>> a & 1) * .5 - .5;
				check(Double.isFinite(x[a]) && x[a] >= low && x[a] <= low + .5, "Wet quadrature leaves its microbox");
				observed[a + 1] += weight * x[a];
			}
			for (int a = 0, index = 4; a < 3; a++) for (int b = a; b < 3; b++) observed[index++] += weight * x[a] * x[b];
		}
		check(volume.equals(quadratic[0]), "Positive wet quadrature lost exact clipped volume");
		for (int i = 0; i < observed.length; i++) {
			double rounding = 16 * Math.max(1, rule.size()) * Math.ulp(quadratic[0].toDouble());
			check(Math.abs(observed[i] - quadratic[i].toDouble()) <= rounding, "Positive wet quadrature lost a quadratic moment");
		}
	}

	private static void validateExactGasTopologyWeights() {
		validateSingleGasSuccessorWeight();
		validateAmbientSelfRemap();
		validateAmbientGasSuccessorWeight();
		for (boolean reflected : new boolean[]{false, true}) {
			float h = .0008F;
			byte[] masks = new byte[45]; java.util.Arrays.fill(masks, (byte) 255);
			for (int x = 1; x <= 3; x++) masks[(1 * 3 + 1) * 5 + x] = 0;
			var domain = new FluidDomain(5, 3, 3, new FluidSolverConfig(h, 1000, 0, .01F, 200, 1e-5F, .5F, 1e-4F, 0));
			domain.restoreParticleState(new FlipParticleState(5, 3, 3, h, 0, 0, new float[0], new float[0], masks));
			domain.sealDomainBoundary();
			var parent = domain.bootstrapGasState("");
			var parentFrame = domain.gasState.frame;
			check(parent.nodes().size() == 1 && parent.nodes().getFirst().inventory().standardVolumeNanos() == 2,
					"Public gas split fixture must begin with one two-nano parent");
			int x = reflected ? 3 : 1, corner = reflected ? 6 : 7;
			check(domain.setSolidMicroMask(x, 1, 1, 255 ^ 1 << corner) == 0,
					"Empty-cell collision edit displaced liquid");
			var captured = domain.captureAuthoritativeState();
			var split = captured.gas();
			int tiny = domain.airNodeAt(x, 1, 1), large = domain.airNodeAt(2, 1, 1);
			check(split.nodes().size() == 2 && tiny > 0 && large > 0 && tiny != large && domain.gasBoundary.edgeCount() == 1,
					"Partial cut-face must connect two separately owned finite gas volumes");
			check(domain.gasBoundary.availableVolume(tiny) > 0
					&& domain.gasBoundary.availableVolume(large) == 16 * domain.gasBoundary.availableVolume(tiny),
					"Gas split must use the actual one-to-sixteen geometric volume ratio");
			var weights = gasOverlapWeights(domain, parentFrame, domain.gasState.frame);
			check(weights.get(1L << 32 | large).equals(weights.get(1L << 32 | tiny).multiply(16)),
					"Multiple gas successors lost their actual geometric weight ratio");
			check(domain.gasBoundary.inventory(tiny).isEmpty()
					&& domain.gasBoundary.inventory(large).equals(parent.nodes().getFirst().inventory()),
					"Quantized geometry weights diverted a nano into the tiny gas volume");
			check(split.externalSource().equals(parent.externalSource()) && split.escapedSink().equals(parent.escapedSink())
					&& split.massRoundoff().equals(parent.massRoundoff()) && split.residualRoundoff().equals(parent.residualRoundoff())
					&& domain.gasBoundary.auditClosed(), "Closed gas split changed its external ledgers");
			var restored = FluidDomainStateMapper.restore(captured);
			check(restored.gasBoundary.nodeCount() == 2
					&& restored.gasBoundary.inventory(restored.airNodeAt(x, 1, 1)).isEmpty()
					&& restored.gasBoundary.inventory(restored.airNodeAt(2, 1, 1)).equals(parent.nodes().getFirst().inventory()),
					"Complete gas handoff lost the tiny vacuum volume or its parent's inventory");
		}
		var one = mmm.units.SignedDyadic.of(1);
		var tinyWeight = ExactRational.ONE.divide(1L << 60);
		double[] direct = GasTopologyVolumePartition.partitionRational(1, List.of(ExactRational.ONE, tinyWeight));
		double[] reflected = GasTopologyVolumePartition.partitionRational(1, List.of(tinyWeight, ExactRational.ONE));
		check(direct[1] == Math.scalb(1.0, -60) && reflected[0] == direct[1] && reflected[1] == direct[0],
				"Gas volume remainder placement erased a positive tiny share or changed under permutation");
		var error = mmm.units.SignedDyadic.of(direct[0]).add(mmm.units.SignedDyadic.of(direct[1])).subtract(one);
		if (error.signum() < 0) error = error.negate();
		var halfUlp = mmm.units.SignedDyadic.of(Math.ulp(direct[0])).multiply(mmm.units.SignedDyadic.of(1, -1));
		check(error.compareTo(halfUlp) <= 0, "Gas volume partition exceeded its exact half-ULP closure bound");
	}

	private static void validateSingleGasSuccessorWeight() {
		var domain = pieceCapacityDomain(0, .19F, .24F, .31F, .1875F);
		domain.sealDomainBoundary(); var before = domain.bootstrapGasState(""); var old = domain.gasState.frame;
		check(old.nodeCount() == 1 && old.capacity().pieces().size() > 1,
				"Single-successor fixture requires a cavity spanning multiple dry microboxes");
		check(GasPhaseGeometryEnvelope.capture(old,domain.gasState.network,null,0,1)!=null,
				"One fixed convex component lost its unique valid phase successor certificate");
		domain.particles.setPositionX(0, .27F); domain.particles.setPositionY(0, .22F); domain.particles.setPositionZ(0, .29F);
		domain.gridDirty = true; domain.ensureInventoryGrid();
		var next = GasRuntimeGeometry.capture(domain, null, new long[8], true);
		var weights = gasOverlapWeights(domain, old, next);
		check(weights.size() == 1 && ExactRational.ONE.equals(weights.get(1L << 32 | 1)),
				"A unique finite gas successor needs the complete inventory proportion");
		double previousVolume = domain.gasState.network.availableVolume(1);
		check(Double.doubleToRawLongBits(GasTopologyVolumePartition.partitionRational(previousVolume, List.copyOf(weights.values()))[0])
				== Double.doubleToRawLongBits(previousVolume), "A unique gas successor changed its inherited control volume");
		var ambientVolume=next.liquidStock().get(0).components().getFirst().volume();
		var ambientEdit=new PhaseLiquidGeometryEdit(0,0,List.of(new LiquidComponentMoments(0,255,1,ambientVolume,
				ambientVolume.multiply(SignedDyadic.of(.75)),ambientVolume.multiply(SignedDyadic.of(.5)),ambientVolume.multiply(SignedDyadic.of(.5)))));
		var ambient = GasRuntimeGeometry.capture(domain, ambientEdit, new long[8], false);
		check(ambient.nodeCount()==0 && ExactRational.ONE.equals(gasOverlapWeights(domain, old, ambient).get(1L << 32)),
				"A unique ambient destination lost the complete finite inventory proportion");
		domain.gasTopologyCoordinator.refresh(); var after = domain.exportGasState("");
		check(after.nodes().size() == 1 && after.nodes().getFirst().inventory().equals(before.nodes().getFirst().inventory())
				&& after.externalSource().equals(before.externalSource()) && after.escapedSink().equals(before.escapedSink())
				&& domain.gasBoundary.auditClosed(), "Single-successor remapping changed finite stock or its external ledgers");
		domain.setGasNodeStandardVolume(1, 0); old = domain.gasState.frame;
		domain.particles.setPositionZ(0, Math.nextUp(domain.particles.positionZ(0)));
		domain.gridDirty = true; domain.ensureInventoryGrid(); next = GasRuntimeGeometry.capture(domain, null, new long[8], true);
		check(ExactRational.ONE.equals(gasOverlapWeights(domain, old, next).get(1L << 32 | 1)),
				"Vacuum geometry lost its unique successor's control-volume proportion");
		domain.gasTopologyCoordinator.refresh();
		check(domain.gasBoundary.inventory(1).isEmpty(), "A surviving vacuum acquired gas inventory during geometry remapping");
		var single = pieceCapacityDomain(0,.25F,.25F,.25F,Math.nextDown(1F));
		single.sealDomainBoundary(); single.bootstrapGasState("");
		var singleSource = single.gasState.frame;
		check(singleSource.nodeCount()==1 && singleSource.geometry().pieces().size()==1,
				"Single-piece phase fixture acquired another old dry microbox");
		var envelope=GasPhaseGeometryEnvelope.capture(singleSource,single.gasState.network,null,0,1);
		check(envelope!=null,"An edited single gas piece lost its unique valid phase successor certificate");
		var volume=SignedDyadic.of(.75); var moment=volume.multiply(SignedDyadic.of(.25));
		var edit=new PhaseLiquidGeometryEdit(0,0,List.of(new LiquidComponentMoments(0,255,1,volume,moment,moment,moment)));
		var expanded=GasRuntimeGeometry.capture(single,edit,new long[8],true);
		check(ExactRational.ONE.equals(gasOverlapWeights(single,singleSource,expanded).get(1L<<32|1))
				&& envelope.volumeUpper(edit)>=expanded.capacity().chambers().getFirst().cubicMeters(),
				"Single-piece source split its inventory proportion or escaped its future capacity upper bound");
		var oppositeMoment=volume.multiply(SignedDyadic.of(.75));
		var opposite=new PhaseLiquidGeometryEdit(0,0,List.of(new LiquidComponentMoments(0,255,1,volume,oppositeMoment,oppositeMoment,oppositeMoment)));
		var noOverlap=GasRuntimeGeometry.capture(single,opposite,new long[8],true);
		checkLocalBoilPlanes(single,singleSource,opposite,noOverlap);
		check(gasOverlapWeights(single,singleSource,noOverlap).isEmpty(),"A single old gas piece gained a successor without positive overlap");
		try { single.gasTopologyCoordinator.planAfterLiquidEdits(List.of(opposite)); throw new AssertionError("Zero-overlap finite single-piece stock gained a remap"); }
		catch(GasTopologyBlockedException expected) {
			check(expected.getMessage().contains("no positive successor"),"Zero-overlap single-piece stock failed for another property");
		}
		var nonconvex=pieceCapacityDomain(8,.25F,.25F,.25F,.125F);
		nonconvex.sealDomainBoundary(); nonconvex.bootstrapGasState("");
		var nonconvexSource=nonconvex.gasState.frame;
		check(nonconvexSource.nodeCount()==1 && nonconvexSource.geometry().pieces().size()>1
				&& GasPhaseGeometryEnvelope.capture(nonconvexSource,nonconvex.gasState.network,null,0,1)==null,
				"A nonconvex fixed component acquired an unsupported unique phase successor certificate");
		byte[] masks=new byte[8]; java.util.Arrays.fill(masks,(byte)255); masks[0]=masks[1]=masks[2]=masks[3]=0;
		float[] markers={.25F,.25F,.25F,.03125F};
		var connected=gasRestoreTarget(new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.5F,1e-4F,0),
				new FlipParticleState(2,2,2,1,1,.03125F,markers,new float[3],masks));
		connected.sealDomainBoundary(); connected.bootstrapGasState(""); var captured=connected.gasState.frame;
		check(captured.nodeCount()==1 && GasPhaseGeometryEnvelope.capture(captured,connected.gasState.network,null,0,1)==null,
				"Light wet stock acquired an unproved unique cross-cell gas successor");
		var remaining=SignedDyadic.of(.015625); var flippedMoment=remaining.multiply(SignedDyadic.of(.75));
		var flipped=new PhaseLiquidGeometryEdit(0,0,List.of(new LiquidComponentMoments(0,255,1,remaining,flippedMoment,flippedMoment,flippedMoment)));
		var changed=GasRuntimeGeometry.capture(connected,flipped,new long[8],true);
		connected.gasState.frame=null; var fresh=GasRuntimeGeometry.capture(connected,flipped,new long[8],true); connected.gasState.frame=captured;
		checkRuntimePlanesEqual(changed,fresh,"Split gas future differs from independent full capture");
		check(changed.nodeCount()==2 && changed.topology().connections().size()==1,
				"Partial cross-cell gas faces failed to split the old lumped stock into finite-flow owners");
		for(int micro=0;micro<8;micro++) check(GasGeometryOverlap.normalizedVolume(micro,
				List.of(captured.geometry().piece(micro).dryPlane(),changed.geometry().piece(micro).dryPlane())).signum()>0,
				"Gas successor counterexample lost its positive old/new microbox overlaps");
		for (int scenario = 0; scenario < 3; scenario++) validateAdvectedSingleSuccessor(scenario);
	}
	private static void validateAdvectedSingleSuccessor(int scenario) {
		boolean tiny = scenario == 1; float h = .5F, dt = tiny ? .125F : 1;
		float u = tiny ? .12345F : .25F, liquid = tiny ? Math.nextDown(1F) : scenario==0 ? .5F : .75F;
		var d = new FluidDomain(5,5,5,new FluidSolverConfig(h,1000,0,dt,64,1e-6F,.45F,.002F,0),2048);
		int source = d.cellIndex(2,2,2), receiver = source + 1;
		for (int z=0;z<5;z++) for (int y=0;y<5;y++) for (int x=0;x<5;x++)
			d.setVolume(x,y,z,x==2&&y==2&&z==2 ? liquid : x==4 ? .25F : 1);
		for (int p=0;p<d.particles.count();p++) {
			int cell=d.particleCellIndex(p);
			if (cell==source) {
				d.particles.setPositionX(p,tiny ? Math.nextDown(d.particles.positionX(p))
						: (2+(d.particles.positionX(p)<2.5F*h ? .25F : .75F)*liquid)*h);
				d.particles.setPositionY(p,(d.particles.positionY(p)<(2+liquid*.5F)*h ? 2.25F : 2.75F)*h);
			}
			d.particles.setVelocityX(p,u+(scenario==2&&cell==receiver ? .125F*(d.particles.positionX(p)-3*h) : 0));
		}
		d.rebuildGridFromParticles(); d.sealDomainBoundary();
		var gas=new mmm.fluid.air.GasRegistry(List.of(new mmm.fluid.air.GasComponentDefinition("test:advected",1.2,0,List.of(),List.of())),
				List.of(new mmm.fluid.air.GasRegistry.AmbientFraction("test:advected",ExactRational.ONE)));
		var liquids=new FluidMaterialRegistry(List.of(new FluidMaterial("mmm:water",1000,"mmm:water",0,Math.PI/2)));
		var thermal=new mmm.thermal.ThermalRegistry(List.of(
				new mmm.thermal.ThermalMaterialDefinition("test:water",mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water",300,0,.5,
						List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(150,1500,4000))),
				new mmm.thermal.ThermalMaterialDefinition("test:gas",mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,"test:advected",300,0,.025,
						List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(150,1500,718)))));
		d.bindGasRegistry(gas); d.bootstrapGasState(""); d.bindThermal(thermal,liquids,300);
		d.ensureGrid(); d.gridToParticleStage.prepareAdvectionCells();
		var old=d.gasState.frame; int previous=old.commonCellNode(source);
		if (scenario==0) check(old.geometry().pieces().stream().filter(piece->piece.cell()==source)
				.allMatch(piece->piece.normalizedVolume().equals(ExactRational.ONE.divide(8))),"Dry translation fixture lost its complete old microboxes");
		var stock=d.gasState.network.inventory(previous); double energy=d.thermalState.gasNodeEnergy(previous,d.gasState.network);
		var before=d.exportGasState(""); var basis=CellAffineVelocity.capture(d);
		double[][] velocity=new double[3][d.particles.count()], coefficients=new double[3][4*basis.blockCount()];
		for (int p=0;p<d.particles.count();p++) velocity[0][p]=d.particles.velocityX(p);
		basis.fit(velocity,coefficients);
		var flux=new ConservativeLiquidFlux(5,5,5,true); var flow=new LiquidAffineFaceFlow(d,flux,new CellAffineVelocityField(basis,coefficients));
		var geometry=new LiquidFluxGeometry(d); flux.geometry(geometry); flux.faceSweep(flow); flow.prepareVelocities(flux,geometry);
		SignedDyadic[] exact=d.markerGrid.exactCellVolumeInventory.clone(); double[] volumes=new double[d.cellCount];
		for (int cell=0;cell<volumes.length;cell++) volumes[cell]=exact[cell].toDouble();
		flux.plan(volumes,d.geometry.solid,dt/h,exact);
		var candidate=new CellCarrierRemapPlan(d,flux,dt,new BallisticLiquidRemap(d,dt)); var next=candidate.gasTopology.frame();
		int destination=next.commonCellNode(receiver);
		check(next.commonCellNode(source)<0 && GasAdvectionSuccessor.certify(d,old,next,flux,null,dt).get(previous)==destination,
				"Actual constant velocity and final micro ports lost a complete dry single-exit migration");
		check(gasOverlapWeights(d,old,next).keySet().stream().noneMatch(pair->pair>>>32==previous),
				"Advected gas fixture retained a same-piece successor");
		check(candidate.gasTopology.gasPlan().inventory(destination).equals(stock),"Advected gas changed its complete nanos/kg/species stock");
		double expected=new mmm.thermal.GasThermalModel(thermal,gas).compressAdiabatically(stock,energy,
				d.gasState.network.availableVolume(previous),candidate.gasTopology.gasPlan().availableVolume(destination)).finalInternalEnergy();
		check(candidate.gasTopology.thermalPlan().nodeEnergy(destination)==expected,
				"Advected gas lost its inherited old control volume or the existing volume-work law");
		if (scenario==2) check(candidate.gasTopology.gasPlan().availableVolume(destination)>d.gasState.network.availableVolume(previous)
				&& expected<energy,"Advected gas rejected a compressible successor's expansion work");
		if (tiny) check(stock.standardVolumeNanos()==mmm.fluid.air.AirNetwork.nanos(d.gasState.network.availableVolume(previous)),
				"Microgas advection bypassed the original finite nanos admission");
		var actualPorts=new CellRemapPorts(d,flux,new BallisticLiquidRemap(d,dt),false,null);
		check(GasAdvectionSuccessor.certify(d,old,next,flux,actualPorts,dt).get(previous)==destination,
				"Gas migration failed to bind actual complete-support liquid port totals");
		var inlet=actualPorts.outgoing(source-1,255).getFirst(); var original=inlet.volume;
		inlet.volume=original.subtract(SignedDyadic.of(ConservativeMarkerSampling.VOLUME_FLUX_RESOLUTION));
		check(!GasAdvectionSuccessor.certify(d,old,next,flux,actualPorts,dt).containsKey(previous),
				"Gas migration ignored a final indivisible-chemistry port reduction");
		inlet.volume=original;
		check(GasAdvectionSuccessor.certify(d,old,next,flux,null,Math.nextDown(dt)).isEmpty(),"Gas advection reused another time step's face plan");
		d.coordinator.publishCellRemap(CarrierMutationPlan.wholesaleCount(d.carrierMutationRevision(),d.particles.count()),candidate);
		var after=d.exportGasState("");
		check(after.externalSource().equals(before.externalSource()) && after.escapedSink().equals(before.escapedSink())
				&& d.gasBoundary.auditClosed() && d.thermalAudit().expectedInventory().equals(d.exactInternalEnergy()),
				"Advected finite gas changed external ledgers or failed its thermal/carrier audit");
		check(GasAdvectionSuccessor.certify(d,old,next,flux,null,dt).isEmpty(),"Gas advection reused a published carrier source");
	}

	private static void validateAmbientSelfRemap() {
		var domain = pieceCapacityDomain(0, .8F, .2F, .3F, .1875F);
		var before = domain.bootstrapGasState(""); var old = domain.gasState.frame;
		check(old.nodeCount() == 0 && old.capacity().pieces().size() > 1
				&& GasFaceIntersection.fullDryFace(domain.pressurePhaseGeometry,0,0,0),
				"Ambient self-remap fixture requires exterior-connected slanted dry microboxes");
		domain.particles.setPositionX(0, .9F); domain.particles.setPositionY(0, .22F); domain.particles.setPositionZ(0, .29F);
		domain.gridDirty = true; domain.ensureInventoryGrid();
		var next = GasRuntimeGeometry.capture(domain, null, new long[8], false);
		check(next.nodeCount() == 0 && GasFaceIntersection.fullDryFace(domain.pressurePhaseGeometry,0,0,0)
				&& gasOverlapWeights(domain, old, next).isEmpty(),
				"Persistent ambient space acquired unused exact remap weights");
		domain.gasTopologyCoordinator.refresh(); var after = domain.exportGasState("");
		check(after.nodes().isEmpty() && after.externalSource().equals(before.externalSource())
				&& after.escapedSink().equals(before.escapedSink()) && after.massRoundoff().equals(before.massRoundoff())
				&& after.residualRoundoff().equals(before.residualRoundoff()) && domain.gasBoundary.auditClosed(),
				"Ambient geometry movement changed finite stock or external gas ledgers");
	}

	private static void validateAmbientGasSuccessorWeight() {
		var domain = pieceCapacityScaledDomain(.5F, 0); domain.bootstrapGasState(""); var old = domain.gasState.frame;
		check(old.nodeCount() == 0, "Ambient trapping fixture must begin with an exterior-connected cell");
		domain.setSolidMicroMask(0, 0, 0, 126); domain.gasTopologyCoordinator.refresh(); var next = domain.gasState.frame;
		var weights = gasOverlapWeights(domain, old, next);
		check(!weights.containsKey(0L), "Ambient capture also accumulated an unused ambient self-weight");
		check(ExactRational.ONE.divide(64).equals(weights.get(1L)) && ExactRational.ONE.divide(64).equals(weights.get(2L)),
				"Ambient capture lost the actual SI volume of a partial-exterior or isolated microbox");
		check(domain.gasBoundary.inventory(1).standardVolumeNanos() == 15_625_000
				&& domain.gasBoundary.inventory(2).standardVolumeNanos() == 15_625_000
				&& domain.gasState.network.externalSource().standardVolumeNanos() == 31_250_000
				&& domain.gasBoundary.auditClosed(), "Ambient capture did not use its actual absolute trapped volume");
	}

	@SuppressWarnings("unchecked")
	private static java.util.Map<Long, ExactRational> gasOverlapWeights(FluidDomain domain, GasRuntimeGeometry old, GasRuntimeGeometry next) {
		try {
			var method = GasTopologyCoordinator.class.getDeclaredMethod("overlaps", GasRuntimeGeometry.class, GasRuntimeGeometry.class);
			method.setAccessible(true);
			var leaves = (java.util.Map<Long, List<ExactRational>>) method.invoke(domain.gasTopologyCoordinator, old, next);
			var result = new java.util.TreeMap<Long, ExactRational>();
			for (var entry : leaves.entrySet()) {
				ExactRational sum = ExactRational.ZERO;
				for (var leaf : entry.getValue()) sum = sum.add(leaf);
				result.put(entry.getKey(), sum);
			}
			return result;
		} catch (ReflectiveOperationException failure) { throw new AssertionError("Gas topology weight inspection failed", failure); }
	}

	private static void validateGasRestoreLineage() {
		var config = new FluidSolverConfig(1, 1000, 0, .01F, 200, 1e-5F, .5F, 1e-4F, 0);
		byte[] masks = new byte[8]; masks[1] = (byte) 255;
		float[] positions = new float[36];
		positions[0] = positions[1] = positions[2] = .25F; positions[3] = .125F;
		for (int slot = 0; slot < 8; slot++) {
			int base = 4 * (slot + 1);
			positions[base] = .25F + .5F * (slot & 1);
			positions[base + 1] = 1.25F + .5F * (slot >> 1 & 1);
			positions[base + 2] = .25F + .5F * (slot >> 2 & 1);
			positions[base + 3] = .125F;
		}
		var particles = new FlipParticleState(2, 2, 2, 1, 9, 1.125F, positions, new float[27], masks);
		var source = gasRestoreTarget(config, particles);
		var committed = source.bootstrapGasState("");
		check(committed.pieceNodeOrdinal()[7] == 1
				&& committed.pieceNodeOrdinal()[8] == -1 && committed.pieceNodeOrdinal()[16] == -1,
				"Gas restore fixture must distinguish partial gas, solid and exact full-liquid cells");
		var valid = gasRestoreTarget(config, particles);
		valid.restoreGasState(committed, mmm.fluid.air.BuiltinGasDefinitions.createRegistry());
		var restored = valid.exportGasState("");
		check(java.util.Arrays.equals(restored.pieceNodeOrdinal(), committed.pieceNodeOrdinal())
				&& restored.nodes().equals(committed.nodes())
				&& restored.externalSource().equals(committed.externalSource())
				&& restored.escapedSink().equals(committed.escapedSink())
				&& restored.massRoundoff().equals(committed.massRoundoff())
				&& restored.residualRoundoff().equals(committed.residualRoundoff()),
				"Valid gas restoration changed complete topology or inventory");
		for (int piece : new int[]{7, 8, 16}) {
			int[] membership = committed.pieceNodeOrdinal(); membership[piece] = 0;
			var altered = new mmm.fluid.air.GasStateSnapshot(2, 2, 2, committed.registryFingerprint(), "",
					committed.nodes(), membership, committed.externalSource(), committed.escapedSink(),
					committed.massRoundoff(), committed.residualRoundoff());
			var rejected = gasRestoreTarget(config, particles);
			var network = rejected.gasState.network;
			long gasRevision = network.revision(), carrierRevision = rejected.carrierMutationRevision();
			var externalSource = network.externalSource(); var escapedSink = network.escapedSink();
			var massRoundoff = network.massRoundoff(); var residualRoundoff = network.residualRoundoff();
			try {
				rejected.restoreGasState(altered, mmm.fluid.air.BuiltinGasDefinitions.createRegistry());
				throw new AssertionError("Gas restoration accepted altered membership at piece " + piece);
			} catch (IllegalArgumentException expected) { }
			check(rejected.gasState.network == network && network.revision() == gasRevision && network.nodeCount() == 0
					&& network.externalSource().equals(externalSource) && network.escapedSink().equals(escapedSink)
					&& network.massRoundoff().equals(massRoundoff) && network.residualRoundoff().equals(residualRoundoff)
					&& rejected.gasState.frame == null && !rejected.gasState.pressureActive
					&& rejected.carrierMutationRevision() == carrierRevision
					&& java.util.Arrays.equals(rejected.exportParticleState().copyPositionVolume(), positions)
					&& java.util.Arrays.equals(rejected.exportParticleState().copyCollisionMicroMasks(), masks),
					"Rejected gas lineage restoration changed authoritative owners");
		}
	}

	private static FluidDomain gasRestoreTarget(FluidSolverConfig config, FlipParticleState particles) {
		var domain = new FluidDomain(2, 2, 2, config);
		domain.sealDomainBoundary(); domain.restoreParticleState(particles);
		return domain;
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
				var gas=d.bootstrapGasState("");long expected=mmm.fluid.air.AirNetwork.nanos((1-sum)*.125);
				check(gas.externalSource().standardVolumeNanos()==expected,"Ambient bootstrap changed the rounded source amount");
			} else {
				try {d.bootstrapGasState("");throw new AssertionError("Sub-resolution ambient space invented a minimum gas portion");}
				catch(GasTopologyBlockedException expected) { }
				check(d.gasState.network==network && network.revision()==gasRevision && network.nodeCount()==0
						&& network.externalSource().entries().isEmpty() && network.escapedSink().entries().isEmpty()
						&& d.gasState.frame==null && d.carrierMutationRevision()==carrierRevision
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
