package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import java.util.List;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

/** Standalone M5 cavity-scanner gate: ambient/sealed classification, exact available volume, fixed-aperture geometric edges and the scan budget. */
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
		check(!geometry.ports().isEmpty()&&!geometry.exteriorPorts().isEmpty(),
				"Full cube gas geometry omitted its internal or exterior openings");
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
			var domain=new FluidDomain(2,2,2,new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,1e-4F,0),16);
			domain.restoreParticleState(new FlipParticleState(2,2,2,1,2,1,pv,new float[6], mmm.units.Mass.createLiquid(1, 1000.0, pv),masks));domain.domainFlowFields.ensurePressureGeometry();
			var partial=domain.gasState.frame.geometry();
			var crossing=partial.ports().stream().filter(port->port.first()/8!=port.second()/8).toList();
			// Each stock reconstructs a horizontal half-filled cell. Side-by-side
			// cells share dry space; vertically stacked cells have a wet separating face.
			boolean liquidSeal=axis==1;
			check(crossing.isEmpty()==liquidSeal && crossing.stream().allMatch(port -> port.area()>0 && port.fullCutConnection())
					&& crossing.stream().mapToDouble(GasComplementGeometry.Port::area).sum()<=1,
					"Shared PLIC face lost its dry connection or its resolved liquid seal");
			var gas=domain.bootstrapGasState("");
			check(gas.nodes().isEmpty() && gas.externalSource().standardVolumeNanos()==0
					&& domain.gasState.network.edgeCount()==0 && domain.gasState.network.auditClosed(),
					"Open fixed faces lost ambient connectivity or created finite gas inventory");
			for(var piece:partial.pieces()) check(domain.gasState.frame.nodeOfPiece(piece.id())==0,
					"An exterior-connected positive dry support acquired a sealed owner");
			var sealed=new FluidDomain(2,2,2,domain.config(),16);
			sealed.restoreParticleState(new FlipParticleState(2,2,2,1,2,1,pv,new float[6],mmm.units.Mass.createLiquid(1,1000,pv),masks));
			sealed.sealDomainBoundary();var closed=sealed.bootstrapGasState("");
			int expectedChambers=liquidSeal?2:1;
			check(closed.nodes().size()==expectedChambers && sealed.gasState.network.edgeCount()==0 && sealed.gasState.network.auditClosed(),
					"Sealed supports lost their dry connection or crossed their separating liquid face");
			checkRelative(sealed.gasBoundary().nodeIds().stream().mapToDouble(sealed.gasBoundary()::availableVolume).sum(),1,
					"Gas connections changed actual cavity capacity");
			var restored=FluidDomain.restoreAuthoritativeState(sealed.captureAuthoritativeState());
			check(restored.gasState.network.nodeCount()==expectedChambers
					&&restored.gasBoundary().sealedInventory().equals(sealed.gasBoundary().sealedInventory())
					&&java.util.Arrays.equals(restored.exportGasState("").pieceNodeOrdinal(),closed.pieceNodeOrdinal())
					&&restored.gasState.network.auditClosed(),"Gas inventory, dry connection or liquid seal changed on restore");

		}
		validateGasFaceAreas();
	}

	private static void validateGasFaceAreas() {
		var a=mmm.units.SignedDyadic.of((double).1234567F);
		var b=mmm.units.SignedDyadic.of((double).7654321F);var threshold=mmm.units.SignedDyadic.of(.1);
		var lower=new GasFaceIntersection.Plane(a,b,threshold);
		var tangent=new GasFaceIntersection.Plane(a.negate(),b.negate(),threshold.negate());
		check(GasFaceIntersection.area(0,0,1,List.of(lower,tangent))==0,"Tangential half-spaces manufactured a gas port");
		double upper = .10001;
		var narrow=new GasFaceIntersection.Plane(a.negate(),b.negate(),mmm.units.SignedDyadic.of(-upper));
		double strip=GasFaceIntersection.area(0,0,1,List.of(lower,narrow));
		var numerator=new java.math.BigDecimal(upper).pow(2).subtract(new java.math.BigDecimal(.1).pow(2));
		var denominator=new java.math.BigDecimal((double).1234567F).multiply(new java.math.BigDecimal((double).7654321F)).multiply(java.math.BigDecimal.valueOf(2));
		double expected=numerator.divide(denominator,java.math.MathContext.DECIMAL128).doubleValue();
		check(strip > 0 && Math.abs(strip - expected) <= 2e-7 * expected,"A true narrow gas port lost its exact physical area");
		var tinyWet=new GasFaceIntersection.Plane(SignedDyadic.of(1),SignedDyadic.ZERO,SignedDyadic.of(Float.MIN_VALUE));
		check(GasFaceIntersection.area(0,0,.5,List.of(tinyWet))==.25
				&& !GasFaceIntersection.coversRectangle(0,0,.5,List.of(tinyWet))
				&& GasFaceIntersection.coversRectangle(0,0,.5,List.of(new GasFaceIntersection.Plane(SignedDyadic.of(1),SignedDyadic.ZERO,SignedDyadic.ZERO))),
				"Rounded full area hid a true wet strip or rejected an exactly complete dry face");
        double epsilon = 1e-4, cornerArea = epsilon * epsilon / (2 * a.toDouble() * b.toDouble());
        var upperCorner = new GasFaceIntersection.Plane(a.toDouble(), b.toDouble(), a.toDouble() + b.toDouble() - epsilon);
        var lowerCorner = new GasFaceIntersection.Plane(-a.toDouble(), -b.toDouble(), -epsilon);
        checkRelative(GasFaceIntersection.area(0, 0, 1, List.of(upperCorner)), cornerArea, "Resolved upper gas corner area");
        checkRelative(GasFaceIntersection.area(0, 0, 1, List.of(lowerCorner)), cornerArea, "Resolved lower gas corner area");
        check(GasFaceIntersection.area(0, 0, 1, List.of(new GasFaceIntersection.Plane(0, 0, 0))) == 1,
                "Coplanar constraint removed a complete gas face");
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
			var d=new FluidDomain(2,2,2,new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,1e-4F,0),16);
			d.restoreParticleState(new FlipParticleState(2,2,2,1,count,stock[0]+stock[1],pv,new float[3*count], mmm.units.Mass.createLiquid(1, 1000.0, pv),masks));d.domainFlowFields.ensurePressureGeometry();
			var frame=pieceCapacityFrame(d,true);var phase=frame.phase();var gas=frame.geometry();
			var capacities=gas.actualCapacity().components;
			check(capacities.size()==2,"Gas capacity omitted a fixed open component");
			for (int i=0;i<2;i++) {
				int bit=i==0?corner:opposite;
				check(Math.abs(d.exportPhaseOccupancyState().liquidVolume(bit)-stock[i])<2e-15,
						"Persistent phase occupation transferred liquid between disconnected components");
				check(Math.abs(.125*phase.liquidFractionExact(0,bit)-stock[i])<2e-15,
						"Prepared micro liquid geometry differs from its actual component inventory");
				double expected=.125-stock[i];var piece=gas.piece(bit);
				check(expected==0?piece==null:piece!=null&&Math.abs(piece.volume()-expected)<2e-15,"Gas geometry disagrees with component inventory");
				var capacity=capacities.stream().filter(c->c.supportMask()==1<<bit).findFirst().orElseThrow();
				check(capacity.availableVolumeNormalized().equals(mmm.units.SignedDyadic.of(expected)),"Gas capacity changed the authoritative component remainder");
			}
			var topology=GasChamberTopology.capture(gas,true);
			check(topology.chambers().size()==(stock[0]<.125F?1:0)+(stock[1]<.125F?1:0),"Component PLIC omitted or invented an independent gas chamber");
		}

		byte[] masks=new byte[8];java.util.Arrays.fill(masks,(byte)255);masks[0]=126;
		var d=new FluidDomain(2,2,2,new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,1e-4F,0),16);
		float[] pv={.125F,.2F,.3F,.03F,.8F,.875F,.7F,.06F};
		d.restoreParticleState(new FlipParticleState(2,2,2,1,2,.09F,pv,new float[6], mmm.units.Mass.createLiquid(1, 1000.0, pv),masks));d.domainFlowFields.ensurePressureGeometry();
		var gas=pieceCapacityFrame(d,true).geometry();
		var capacities=gas.actualCapacity().components;
		checkRelative(capacities.stream().mapToDouble(c->c.availableVolumeNormalized().toDouble()).sum()+d.totalVolumeExact(),.25,"Disconnected open capacity and liquid inventory");
		var staleStock=d.liquidCells.physicalStockAt(0);
		d.liquidCells.planEdits(List.of(new LiquidCellInventory.Edit(staleStock.key(),new LiquidCellInventory.Stock(staleStock.key(),SignedDyadic.of(.04),staleStock.massUnits(),staleStock.mixture(),staleStock.internalEnergy(),staleStock.indicator())))).commit();
		var changedCapacities=pieceCapacityFrame(d,true).geometry().actualCapacity().components;
		checkRelative(changedCapacities.stream().mapToDouble(c->c.availableVolumeNormalized().toDouble()).sum()+d.totalVolumeExact(),.25,
				"Gas capacity follows actual inventory after a liquid edit");
	}

    private static void validateRuntimeGeometryBinding() {
        validateGasBridge();
        byte[] masks = new byte[8]; java.util.Arrays.fill(masks, (byte)255); masks[0] = masks[1] = 0;
        float[] markers = {.25F, .5F, .5F, .5F, 1.5F, .5F, .5F, .25F};
        var domain = gasRestoreTarget(new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,1e-4F,0),
                new FlipParticleState(2,2,2,1,2,.75F,markers,new float[6],mmm.units.Mass.createLiquid(1,1000,markers),masks));
        long[] occupied = new long[8];
        var current = GasRuntimeGeometry.capture(domain,occupied,true);
        var stock = domain.liquidCells.physicalStockAt(0);
        var changed = new LiquidCellInventory.Stock(stock.key(),SignedDyadic.of(.125),stock.massUnits(),stock.mixture(),stock.internalEnergy(),stock.indicator());
        var plan = domain.liquidCells.planEdits(List.of(new LiquidCellInventory.Edit(stock.key(),changed)));
        var nextVolumes=domain.exportPhaseOccupancyState().copyLiquidMicroVolumes();
        for(int micro=0;micro<8;micro++)nextVolumes[micro]*=.25;
        var nextOccupancy=new mmm.inventory.PhaseOccupancyState(nextVolumes);
        var future = GasRuntimeGeometry.captureCellFuture(domain,plan.future(),occupied,true,null,nextOccupancy);
        check(future.matchesSourceCarrierGeometry(domain,occupied) && !future.matchesCarrierGeometry(domain,occupied)
                        && domain.liquidCells.stock(stock.key()).volume().equals(SignedDyadic.of(.5)),
                "Future gas preparation changed live inventory or lost its source binding");
        checkRelative(future.capacity().chambers().stream().mapToDouble(GasChamberCapacity.Chamber::cubicMeters).sum(),
                2-.125-.25,"Future phase occupation complements actual liquid stock");
        plan.commit();domain.restorePhaseOccupancyState(nextOccupancy);
        check(future.matchesCarrierGeometry(domain,occupied) && !current.matchesCarrierGeometry(domain,occupied)
                        && !future.matchesSourceCarrierGeometry(domain,occupied),
                "Published cell inventory did not invalidate the old geometry source");
        var energyOnly = new LiquidCellInventory.Stock(changed.key(),changed.volume(),changed.massUnits(),changed.mixture(),changed.internalEnergy()+1,changed.indicator());
        domain.liquidCells.planEdits(List.of(new LiquidCellInventory.Edit(changed.key(),energyOnly))).commit();
        check(future.matchesCarrierGeometry(domain,occupied),"Thermal-only inventory update rebuilt liquid geometry");
        byte[] closed=masks.clone();closed[1]=(byte)255;
        var peer=domain.liquidCells.stocksInCell(1).getFirst();
        var closePlan=domain.liquidCells.planEdits(List.of(new LiquidCellInventory.Edit(peer.key(),null)));
        var closedVolumes=nextOccupancy.copyLiquidMicroVolumes();java.util.Arrays.fill(closedVolumes,8,16,0);
        var closedFuture=GasRuntimeGeometry.captureCellFuture(domain,closePlan.future(),occupied,true,closed,
                new mmm.inventory.PhaseOccupancyState(closedVolumes));
        check(closedFuture.matchesSourceCarrierGeometry(domain,occupied)
                        && closedFuture.geometry().collisionMask(1)==255 && domain.geometry.cutCells.mask(1)==0,
                "Future collision preparation published masks or used the old capacity");
        domain.geometry.cutCells.replaceMask(0,1);
        check(!future.matchesCarrierGeometry(domain,occupied) && !closedFuture.matchesSourceCarrierGeometry(domain,occupied),
                "Changed collision mask retained a prepared gas source");
    }

    private static void validateGasBridge() {
        var grid=new Grid(5,3,3,1);java.util.Arrays.fill(grid.solid,true);
        for(int cell=0;cell<45;cell++)grid.cutCells.replaceMask(cell,255);
        for(int x=0;x<4;x++){grid.solid[20+x]=false;grid.cutCells.replaceMask(20+x,0);}
        var dry=new FlipPressurePhaseGeometry(5,3,3);
        dry.rebuild(grid.solid,grid.liquidVolume,grid.cutCells,null,new boolean[45]);
        var source=GasComplementGeometry.capture(5,3,3,1,grid.solid,grid.cutCells,dry);
        grid.liquidVolume[21]=1;
        var wet=new FlipPressurePhaseGeometry(5,3,3);
        wet.rebuild(grid.solid,grid.liquidVolume,grid.cutCells,null,new boolean[45]);
        var target=GasComplementGeometry.capture(5,3,3,1,grid.solid,grid.cutCells,wet);
        for(boolean sealed:new boolean[]{false,true}) {
            var split=GasChamberTopology.capture(target,sealed);
            check(split.chambers().size()==(sealed?2:1),"Closing gas bridge lost a chamber or ambient ownership");
            var restored=GasChamberTopology.capture(source,sealed);
            check(restored.chambers().size()==(sealed?1:0),"Reopened gas bridge failed to merge chambers");
        }
    }

	private static void validateGasPieceCapacity() {
        var fixed=pieceCapacityDomain(0,.25F,.5F,.5F,.25F);
        fixed.granularCoupling=new mmm.granular.GranularCouplingFields(2,2,2,1);
        long[] occupied=new long[8];occupied[0]=1L<<22;fixed.granularCoupling.setFixedCellSvq(occupied);
        var fixedFrame=pieceCapacityFrame(fixed,true);
        checkRelative(fixedFrame.poreVolume(0).toDouble(),.75,"Actual granular pore capacity");
        checkRelative(fixedFrame.capacity().chambers().getFirst().cubicMeters(),.5,
                "Gas capacity complements granular and liquid occupancy");
        checkPreparedCapacityClosure(fixedFrame);

        var general=pieceCapacityDomain(0,.2F,.3F,.4F,.07123456F,.7F,.6F,.8F,.0312F);
        checkPreparedCapacityClosure(pieceCapacityFrame(general,false));

        byte[] mergeMasks=new byte[8];java.util.Arrays.fill(mergeMasks,(byte)255);mergeMasks[0]=(byte)254;mergeMasks[1]=0;
        var merger=gasRestoreTarget(new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,1e-4F,0),
                new FlipParticleState(2,2,2,1,0,0,new float[0],new float[0],mmm.units.Mass.createLiquid(1,1000,new float[0]),mergeMasks));
        var divided=pieceCapacityFrame(merger,true);
        merger.geometry.cutCells.replaceMask(0,0);
        var joined=pieceCapacityFrame(merger,true);
        check(divided.nodeCount()==2&&joined.nodeCount()==1,"Opening a full fixed face failed to join gas chambers");
        checkRelative(joined.capacity().chambers().getFirst().cubicMeters(),2,"Joined actual gas capacity");
        merger.geometry.cutCells.replaceMask(0,254);
        var dividedAgain=pieceCapacityFrame(merger,true);
        check(dividedAgain.nodeCount()==2,"Restored fixed restriction failed to separate gas chambers");
        checkPreparedCapacityClosure(dividedAgain);

        var split=pieceCapacityDomain(126,.25F,.25F,.25F,.0625F,.75F,.75F,.75F,.03125F);
        var closed=pieceCapacityFrame(split,true);
        check(closed.nodeCount()==2&&closed.nodeOfPiece(0)!=closed.nodeOfPiece(7)
                        &&closed.capacity().chambers().get(0).cubicMeters()==.0625
                        &&closed.capacity().chambers().get(1).cubicMeters()==.09375,
                "Two same-cell gas owners lost their distinct actual capacities");
        for(float h:new float[]{.1F,.5F}) {
            var scaled=pieceCapacityScaledDomain(h,126,.25F,.25F,.25F,.0625F,.75F,.75F,.75F,.03125F);
            var frame=pieceCapacityFrame(scaled,true);double metric=h,cubic=metric*metric*metric;
            check(frame.capacity().chambers().get(0).cubicMeters()==.0625*cubic
                            &&frame.capacity().chambers().get(1).cubicMeters()==.09375*cubic,
                    "Gas chamber actual capacity lost its cubic SI metric");
            checkPreparedCapacityClosure(frame);
        }
        var open=pieceCapacityFrame(split,false);
        check(open.nodeCount()==2&&open.capacity().ambientCubicMeters()==0
                        &&open.capacity().chambers().get(0).cubicMeters()==.0625
                        &&open.capacity().chambers().get(1).cubicMeters()==.09375,
                "A partial exterior aperture vented a finite chamber or mixed disconnected capacity");

        float a=.5F,b=.25F,c=Math.scalb(1F,-40);
        var missing=pieceCapacityDomain(0,.25F,.5F,.5F,a,.25F,.5F,.5F,b,.25F,.5F,.5F,c);
        missing.sealDomainBoundary();var missingFrame=pieceCapacityFrame(missing,true);
        var raw=SignedDyadic.of(1).subtract(SignedDyadic.of(a).add(SignedDyadic.of(b)).add(SignedDyadic.of(c)));
        check(missingFrame.geometry().actualCapacity().components.getFirst().availableVolumeNormalized().equals(raw),
                "Sub-resolution material volume changed actual gas capacity");
        missing.bootstrapGasState("");
        var restoredMissing=FluidDomain.restoreAuthoritativeState(missing.captureAuthoritativeState());
        check(restoredMissing.gasBoundary().auditClosed(),"Sub-resolution material volume lost gas inventory on restore");
    }

	private static FluidDomain pieceCapacityDomain(int mask,float... positions) {
		return pieceCapacityScaledDomain(1,mask,positions);
	}
	private static FluidDomain pieceCapacityScaledDomain(float h,int mask,float... positions) {
		positions=positions.clone();
		int count=positions.length/4;float volume=0;for(int i=0;i<count;i++)volume+=positions[4*i+3];
		for(int i=0;i<count;i++)for(int axis=0;axis<3;axis++)positions[4*i+axis]*=h;
		byte[] masks=new byte[8];java.util.Arrays.fill(masks,(byte)255);masks[0]=(byte)mask;
		var domain=new FluidDomain(2,2,2,new FluidSolverConfig(h,1000,0,.01F,200,1e-5F,1e-4F,0));
		domain.restoreParticleState(new FlipParticleState(2,2,2,h,count,volume,positions,new float[3*count], mmm.units.Mass.createLiquid(h, 1000.0, positions),masks));return domain;
	}
	private static GasRuntimeGeometry pieceCapacityFrame(FluidDomain domain,boolean sealed) {
        // These fixtures edit stock, collision masks and solid occupancy before preparing one endpoint.
        domain.phaseOccupancy=null;domain.markCellInventoryChanged();
        long[] occupied=domain.granularCoupling==null?new long[domain.cellCount]:domain.granularCoupling.copySolidSvq();
        return GasRuntimeGeometry.capture(domain,occupied,sealed);
    }
    private static void checkPreparedCapacityClosure(GasRuntimeGeometry frame) {
        double expected=0;
        for(var component:frame.geometry().actualCapacity().components)
            expected+=component.availableVolumeNormalized().toDouble()*frame.geometry().cellVolumeCubicMeters();
        double actual=frame.capacity().ambientCubicMeters();
        for(var chamber:frame.capacity().chambers()) {
            check(chamber.cubicMeters()>0,"A prepared gas chamber has no actual capacity");
            actual+=chamber.cubicMeters();
        }
        checkRelative(actual,expected,"Prepared gas owners preserve actual open-component capacity");
    }

    private static void validateMicroGasOverlap() {
        for (int micro = 0; micro < 8; micro++) {
            checkRelative(GasGeometryOverlap.normalizedVolume(micro, List.of()), .125, "Dry microbox volume");
            double x = (micro & 1) * .5;
            var lower = new GasGeometryOverlap.HalfSpace(1, 0, 0, x + .125);
            var upper = new GasGeometryOverlap.HalfSpace(-1, 0, 0, -(x + .375));
            checkRelative(GasGeometryOverlap.normalizedVolume(micro, List.of(lower, upper)), .0625, "Resolved gas slab");
            var tangent = new GasGeometryOverlap.HalfSpace(-1, 0, 0, -(x + .125));
            check(!GasGeometryOverlap.hasPositiveOverlap(micro, lower, tangent), "Tangent gas supports acquired a positive connection");
        }
        for (int support : new int[]{1, 85, 126, 255}) for (float nx : new float[]{-.7F, .3F, 1}) {
            double target = Integer.bitCount(support) / 24.0;
            var result = PlicPlaneOffset.solve(support, nx, .4F, -.2F, target);
            var cdf = new MicroBoxPlaneVolume.SelectedVolume(support,
                    new double[]{nx, .4F, -.2F});
            check(Math.abs(cdf.measure(result.offset()) - target) <= 4e-5 * Integer.bitCount(support) / 8,
                    "PLIC reconstructed volume exceeds the local interface resolution");
        }
        for (int micro = 0; micro < 8; micro++) for (int direction = 0; direction < 24; direction++) {
            double nx = Math.sin(direction + .2), ny = Math.cos(direction + .4), nz = Math.sin(.7 * direction + .1);
            var plane = new GasGeometryOverlap.HalfSpace(nx, ny, nz, .5 * (nx + ny + nz));
            var reverse = new GasGeometryOverlap.HalfSpace(-nx, -ny, -nz, -.5 * (nx + ny + nz));
            checkRelative(MicroBoxPlaneVolume.dryVolume(micro, plane) + MicroBoxPlaneVolume.dryVolume(micro, reverse), .125,
                    "Floating geometry complementary volumes");
            var duplicate = new GasGeometryOverlap.HalfSpace(2 * nx, 2 * ny, 2 * nz, nx + ny + nz);
            checkRelative(GasGeometryOverlap.normalizedVolume(micro, List.of(plane, duplicate)), MicroBoxPlaneVolume.dryVolume(micro, plane),
                    "Equivalent half-space clipping volume");
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
			var domain = new FluidDomain(5, 3, 3, new FluidSolverConfig(h, 1000, 0, .01F, 200, 1e-5F, 1e-4F, 0));
			domain.restoreParticleState(new FlipParticleState(5, 3, 3, h, 0, 0, new float[0], new float[0], mmm.units.Mass.createLiquid(h, 1000.0, new float[0]), masks));
			domain.sealDomainBoundary();
			var parent = domain.bootstrapGasState("");
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
					&& Math.abs(domain.gasBoundary.availableVolume(large) / domain.gasBoundary.availableVolume(tiny) - 16) <= 1e-12,
					"Gas split must use the actual one-to-sixteen geometric volume ratio");
			check(domain.gasBoundary.inventory(tiny).isEmpty()
					&& domain.gasBoundary.inventory(large).equals(parent.nodes().getFirst().inventory()),
					"Quantized geometry weights diverted a nano into the tiny gas volume");
			check(split.externalSource().equals(parent.externalSource()) && split.escapedSink().equals(parent.escapedSink())
					&& domain.gasBoundary.auditClosed(), "Closed gas split changed its external ledgers");
			var restored = FluidDomainStateMapper.restore(captured);
			check(restored.gasBoundary.nodeCount() == 2
					&& restored.gasBoundary.inventory(restored.airNodeAt(x, 1, 1)).isEmpty()
					&& restored.gasBoundary.inventory(restored.airNodeAt(2, 1, 1)).equals(parent.nodes().getFirst().inventory()),
					"Complete gas handoff lost the tiny vacuum volume or its parent's inventory");
		}
		var one = mmm.units.SignedDyadic.of(1);
		double tinyWeight = Math.scalb(1.0, -60);
		double[] direct = GasTopologyVolumePartition.partition(1, List.of(1.0, tinyWeight));
		double[] reflected = GasTopologyVolumePartition.partition(1, List.of(tinyWeight, 1.0));
		check(direct[1] > 0 && direct[1] <= Math.scalb(1.0, -52) && reflected[0] == direct[1] && reflected[1] == direct[0],
				"Gas volume remainder placement erased a positive tiny share or changed under permutation");
		var error = mmm.units.SignedDyadic.of(direct[0]).add(mmm.units.SignedDyadic.of(direct[1])).subtract(one);
		if (error.signum() < 0) error = error.negate();
		var halfUlp = mmm.units.SignedDyadic.of(Math.ulp(direct[0])).multiply(mmm.units.SignedDyadic.of(1, -1));
		check(error.compareTo(halfUlp) <= 0, "Gas volume partition exceeded its exact half-ULP closure bound");
	}

	private static void validateSingleGasSuccessorWeight() {
		var config=new FluidSolverConfig(.5F,1000,0,.125F,128,1e-6F,.002F,0);
		var d=new FluidDomain(7,3,3,config,128);d.sealDomainBoundary();
		var gas=new mmm.fluid.air.GasRegistry(List.of(
				new mmm.fluid.air.GasComponentDefinition("test:left",1.2,0,List.of(),List.of()),
				new mmm.fluid.air.GasComponentDefinition("test:right",1.2,0,List.of(),List.of())),
				List.of(new mmm.fluid.air.GasRegistry.AmbientFraction("test:left",ExactRational.ONE)));
		d.bindGasRegistry(gas);
		var liquids=new FluidMaterialRegistry(List.of(new FluidMaterial("mmm:water",1000,"mmm:water",0,Math.PI/2)));
		d.materialRegistry=liquids;
		byte[] masks=new byte[d.cellCount];java.util.Arrays.fill(masks,(byte)255);
		for(int x=0;x<7;x++)masks[d.domainFlowFields.cellIndex(x,1,1)]=0;
		d.geometry.cutCells.restoreMicroMasks(masks);
		for(int cell=0;cell<d.cellCount;cell++)d.geometry.solid[cell]=Byte.toUnsignedInt(masks[cell])==255;
		d.markCellInventoryChanged();d.setVolume(3,1,1,1);
		var topology=d.bootstrapGasState("");
		int left=d.airNodeAt(0,1,1),right=d.airNodeAt(6,1,1);
		check(left>0&&right>0&&left!=right&&topology.nodes().size()==2,
				"A full-micro liquid plug failed to separate its two finite gas chambers");
		// Low initial pressure gives an inertia-dominated one-cell translation, with labelled gas on each side.
		var a=new mmm.fluid.air.GasInventory(List.of(new mmm.fluid.air.GasComponentAmount("test:left",1000,12000,List.of(),12000)));
		var b=new mmm.fluid.air.GasInventory(List.of(new mmm.fluid.air.GasComponentAmount("test:right",1000,12000,List.of(),12000)));
		var nodes=new java.util.ArrayList<mmm.fluid.air.GasStateSnapshot.Node>();
		for(int ordinal=1;ordinal<=topology.nodes().size();ordinal++)nodes.add(new mmm.fluid.air.GasStateSnapshot.Node(
				topology.nodes().get(ordinal-1).topologyKey(),ordinal==left?a:b));
		var labelled=new mmm.fluid.air.GasStateSnapshot(7,3,3,gas.fingerprint(),"",nodes,topology.pieceNodeOrdinal(),
				mmm.fluid.air.GasAuditLedger.of(a.merge(b)),mmm.fluid.air.GasAuditLedger.EMPTY);
		var liquidState=d.exportLiquidCellState();var momentum=d.exportMacMomentum();var phase=d.exportPhaseOccupancyState();
		var restored=new FluidDomain(7,3,3,config,128);restored.sealDomainBoundary();
		restored.geometry.cutCells.restoreMicroMasks(masks);
		for(int cell=0;cell<restored.cellCount;cell++)restored.geometry.solid[cell]=Byte.toUnsignedInt(masks[cell])==255;
		restored.materialRegistry=liquids;
		restored.restoreLiquidCellState(new LiquidCellInventory.Snapshot(liquidState.rows()),null);
		restored.restoreMacMomentum(momentum);
		restored.restorePhaseOccupancyState(new mmm.inventory.PhaseOccupancyState(phase.copyLiquidMicroVolumes()));
		restored.restoreGasState(labelled,gas);d=restored;
		var definitions=new java.util.ArrayList<mmm.thermal.ThermalMaterialDefinition>();
		definitions.add(new mmm.thermal.ThermalMaterialDefinition("test:plug_water",mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,
				"mmm:water",300,0,0,List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1,2000,4184))));
		for(String component:List.of("test:left","test:right"))definitions.add(new mmm.thermal.ThermalMaterialDefinition(component,
				mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,component,300,0,0,
				List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1,2000,718))));
		d.bindThermal(new mmm.thermal.ThermalRegistry(definitions),liquids,300);
		d.domainFlowFields.ensureGrid();
		for(int face=0;face<d.mac.velocityX.length;face++)if(d.cellMacStage.massX[face]>0)d.mac.velocityX[face]=.5F;
		d.cellMacStage.publishMomentum();
		var mass=d.liquidCells.massUnits();var volume=d.liquidCells.exactVolume();
		var expectedGas=mmm.fluid.air.GasAuditLedger.of(a.merge(b));
		for(int step=0;step<8;step++) {
			d=new CpuFluidSolverBackend().evaluate(new FluidStepRequest(step,d.captureAuthoritativeState(),List.of(),false,0,true)).claimPreparedCpuDomain();
			var snapshot=d.exportGasState("");
			check(d.gasBoundary().sealedInventory().equals(expectedGas)&&d.gasBoundary().auditClosed()
					&&snapshot.externalSource().equals(expectedGas)&&snapshot.escapedSink().isEmpty(),
					"Moving liquid plug changed finite component inventory or external gas ledgers");
			for(int node=1;node<=d.gasBoundary().nodeCount();node++) {
				var inventory=d.gasBoundary().inventory(node);
				check(!(inventory.component("test:left")!=null&&inventory.component("test:right")!=null),
						"Gas crossed a resolved moving liquid seal");
				check(d.gasBoundary().availableVolume(node)>0,"Moving gas chamber lost its actual positive capacity");
			}
			check(d.airNodeAt(0,1,1)>0&&d.airNodeAt(6,1,1)>0&&d.airNodeAt(0,1,1)!=d.airNodeAt(6,1,1),
					"The plug's two macroscopic gas chambers merged");
			check(d.liquidCells.massUnits()==mass&&d.liquidCells.exactVolume().equals(volume)
					&&d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),
					"Moving plug lost liquid inventory or its thermal audit");
			if(step==3) {
				var state=d.captureAuthoritativeState();
				d=FluidDomain.restoreAuthoritativeState(coldState(state));
				FluidDomainStateAssertions.assertSameState(state,d.captureAuthoritativeState(),"Moving plug cold restore");
			}
		}
		double moment=0;
		for(var stock:d.liquidCells.stocks())moment+=(stock.key().cell()%7+.5)*stock.massUnits();
		check(moment/mass>4.25,"The inertia-driven liquid plug did not advance through its original cell face");
	}

	/** Value-only state forces reconstruction from saved occupancy instead of a prepared runtime graph. */
	private static FluidDomainState coldState(FluidDomainState s) {
		return new FluidDomainState(s.sizeX(),s.sizeY(),s.sizeZ(),s.config(),s.maximumParticleCount(),s.sealedDomainBoundary(),
				new LiquidCellInventory.Snapshot(s.liquid().rows()),new mmm.inventory.PhaseOccupancyState(s.phaseOccupancy().copyLiquidMicroVolumes()),
				s.momentum(),s.copyCollisionMasks(),s.materialPalette(),s.chemicalRegistry(),s.chemicalSource(),s.chemicalSink(),
				s.copyChemicalDirtyCells(),s.chemicalReactionCursor(),s.granularRegistry(),s.granular(),s.granularDynamics(),s.precipitationEnabled(),
				s.gasRegistry(),s.gas(),s.thermalRegistry(),s.thermalOwner(),s.maximumThermalTransportSubsteps(),s.pendingPhaseVolume(),
				s.copyThermalBoundaryTemperaturesK(),s.condensedPhasePressure(),s.materialDefinitions());
	}

	private static void validateAmbientSelfRemap() {
		var domain = new FluidDomain(3,3,3,new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,1e-4F,0));
        domain.setVolume(1,1,1,.1875F);
        var before = domain.bootstrapGasState(""); var old = domain.gasState.frame;
		check(old.nodeCount() == 0 && old.capacity().ambientCubicMeters() > 0
				&& GasFaceIntersection.fullDryFace(domain.pressurePhaseGeometry,0,0,0),
				"Ambient self-remap fixture requires exterior-connected slanted dry microboxes");
		domain.setVolume(1,1,1,.25F);
		domain.gridDirty = true; domain.domainFlowFields.ensurePressureGeometry();
		var next = GasRuntimeGeometry.capture(domain,new long[domain.cellCount], false);
		check(next.nodeCount() == 0 && GasFaceIntersection.fullDryFace(domain.pressurePhaseGeometry,0,0,0)
				&& next.capacity().ambientCubicMeters()>0,
				"Persistent ambient space acquired unused exact remap weights");
		domain.gasTopologyCoordinator.refresh(); var after = domain.exportGasState("");
		check(after.nodes().isEmpty() && after.externalSource().equals(before.externalSource())
				&& after.escapedSink().equals(before.escapedSink()) && domain.gasBoundary.auditClosed(),
				"Ambient geometry movement changed finite stock or external gas ledgers");
	}

	private static void validateAmbientGasSuccessorWeight() {
		var domain = pieceCapacityScaledDomain(.5F, 0); domain.bootstrapGasState(""); var old = domain.gasState.frame;
		check(old.nodeCount() == 0, "Ambient trapping fixture must begin with an exterior-connected cell");
		domain.setSolidMicroMask(0, 0, 0, 126); domain.gasTopologyCoordinator.refresh(); var next = domain.gasState.frame;
		check(next.nodeCount()==2 && next.capacity().chambers().stream().allMatch(chamber->chamber.cubicMeters()>0),
				"Fixed cut apertures lost separate positive captured gas capacities");
		check(domain.gasBoundary.inventory(1).standardVolumeNanos() == 15_625_000
				&& domain.gasBoundary.inventory(2).standardVolumeNanos() == 15_625_000
				&& domain.gasState.network.externalSource().standardVolumeNanos() == 31_250_000
				&& domain.gasBoundary.auditClosed(), "Ambient capture did not use its actual absolute trapped volume");
	}


	private static void validateGasRestoreLineage() {
		var config = new FluidSolverConfig(1, 1000, 0, .01F, 200, 1e-5F, 1e-4F, 0);
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
		var particles = new FlipParticleState(2, 2, 2, 1, 9, 1.125F, positions, new float[27], mmm.units.Mass.createLiquid(1, 1000.0, positions), masks);
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
				&& restored.escapedSink().equals(committed.escapedSink()),
				"Valid gas restoration changed complete topology or inventory");
		for (int piece : new int[]{7, 8, 16}) {
			int[] membership = committed.pieceNodeOrdinal(); membership[piece] = 0;
			var altered = new mmm.fluid.air.GasStateSnapshot(2, 2, 2, committed.registryFingerprint(), "",
					committed.nodes(), membership, committed.externalSource(), committed.escapedSink());
			var rejected = gasRestoreTarget(config, particles);
			var before = rejected.captureAuthoritativeState();
			var network = rejected.gasState.network;
			long gasRevision=network.revision(),carrierRevision=rejected.carrierMutationRevision;
			var externalSource = network.externalSource(); var escapedSink = network.escapedSink();
			try {
				rejected.restoreGasState(altered, mmm.fluid.air.BuiltinGasDefinitions.createRegistry());
				throw new AssertionError("Gas restoration accepted altered membership at piece " + piece);
			} catch (IllegalArgumentException expected) { }
			check(rejected.gasState.network == network && network.revision() == gasRevision && network.nodeCount() == 0
					&& network.externalSource().equals(externalSource) && network.escapedSink().equals(escapedSink)
					&& rejected.gasState.frame == null && !rejected.gasState.pressureActive
					&& rejected.carrierMutationRevision == carrierRevision,
					"Rejected gas lineage restoration changed authoritative owners");
			FluidDomainStateAssertions.assertSameState(before,rejected.captureAuthoritativeState(),"Rejected gas lineage restoration");
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
				.scan(grid.solid, grid.stockCount, grid.liquidVolume, grid.cutCells, null, true);
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
			var d=new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,0,.05F,200,1e-5F,.002F,0));
			d.restoreParticleState(new FlipParticleState(3,3,3,.5F,8,(float)sum,pv,new float[24], mmm.units.Mass.createLiquid(.5F, 1000.0, pv),masks));d.sealDomainBoundary();
			var network=d.gasState.network;long gasRevision=network.revision(),carrierRevision=d.domainFlowFields.carrierMutationRevision();
            var gas = d.bootstrapGasState("");
            long expected = mmm.fluid.air.AirNetwork.nanos((1 - sum) * .125);
            check(gas.externalSource().standardVolumeNanos() == expected && d.gasBoundary().auditClosed(),
                    "Ambient capture changed its rounded inventory or introduced a minimum portion");
            var restored = FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
            check(restored.gasBoundary().auditClosed(), "Sub-quantum vacuum capacity failed to restore");
		}
	}

	private static void validateClosedCapacityComplementsCarrierInventory() {
		int n = 8;
		Grid grid = new Grid(n);
		int first = (4 * n + 4) * n + 4;
		int second = (4 * n + 4) * n + 5;
		grid.stockCount[first] = 1;
		grid.stockCount[second] = 1;
		grid.liquidVolume[first] = 1.5;
		grid.liquidVolume[second] = 0.5;
		AirCavityScanner.Result result = new AirCavityScanner(n, n, n, 0.5F, n * n * n)
				.scan(grid.solid, grid.stockCount, grid.liquidVolume, grid.cutCells, null, true);
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
		grid.stockCount[0] = 1;
		grid.stockCount[1] = 1;
		grid.liquidVolume[0] = 0.75;
		grid.liquidVolume[1] = 0.75;
		AirCavityScanner scanner = new AirCavityScanner(5, 2, 2, 1.0F, 20);
		AirCavityScanner.Result before = scanner.scan(
				grid.solid, grid.stockCount, grid.liquidVolume, grid.cutCells, null, true);
		grid.liquidVolume[0] = 1.25;
		grid.liquidVolume[1] = 0.25;
		AirCavityScanner.Result after = scanner.scan(
				grid.solid, grid.stockCount, grid.liquidVolume, grid.cutCells, null, true);
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
			grid.stockCount[cell] = grid.solid[cell] ? 0 : 1;
			grid.liquidVolume[cell] = grid.solid[cell] ? 0 : 1;
		}
		var scanner = new AirCavityScanner(4, 4, 4, 1, 64);
		var full = scanner.scanRetainingClosedLineages(grid.solid, grid.stockCount,
				grid.liquidVolume, grid.cutCells, null, previous, 0);
		check(full.sealedNodeCount() == 0, "full liquid domain invented a gas owner");
		for (int cell = 0; cell < 64; cell++) if (!grid.solid[cell]) grid.liquidVolume[cell] = 0.75;
		var opened = scanner.scanRetainingClosedLineages(grid.solid, grid.stockCount,
				grid.liquidVolume, grid.cutCells, null, previous, 0);
		check(opened.sealedNodeCount() == 2, "disconnected partial voids need separate vacuum owners");
		var initial = scanner.scan(grid.solid, grid.stockCount, grid.liquidVolume, grid.cutCells, null, true);
		check(java.util.Arrays.equals(opened.cellNodeId(), initial.cellNodeId())
				&& java.util.Arrays.equals(opened.sealedAvailableVolume(), initial.sealedAvailableVolume()),
				"initial closed partial topology failed to establish the same geometry owners");
		var orphaned = scanner.scanRetainingClosedLineages(grid.solid, grid.stockCount,
				grid.liquidVolume, grid.cutCells, null, previous, 2);
		check(java.util.Arrays.equals(opened.cellNodeId(), orphaned.cellNodeId()),
				"disappeared old owners prevented new residual topology");
		checkRelative(java.util.Arrays.stream(opened.sealedAvailableVolume()).sum(), 12,
				"partial vacuum capacity must equal extracted volume");
		var restored = scanner.scanRetainingClosedLineages(grid.solid, grid.stockCount,
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
				.scan(halfMetre.solid, halfMetre.stockCount, halfMetre.liquidVolume, halfMetre.cutCells);
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
			else if (y <= 1) { grid.stockCount[i] = 8; grid.liquidVolume[i] = 1.0F; }
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
				.scan(grid.solid, grid.stockCount, grid.liquidVolume, grid.cutCells);
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
			grid.stockCount[i] = 1;
			grid.liquidVolume[i] = 0.5F;
		}
		AirCavityScanner scanner = new AirCavityScanner(n, n, n, 1.0F, n * n * n);
		AirCavityScanner.Result result = scanner.scan(
				grid.solid, grid.stockCount, grid.liquidVolume, grid.cutCells);
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
				grid.solid, grid.stockCount, grid.liquidVolume, grid.cutCells, null,
				result.cellNodeId(), result.cellLineageNodeId(), result.sealedNodeCount(), false);
		AirCavityScanner.Result rescanned = scanner.scan(
				grid.solid, grid.stockCount, grid.liquidVolume, grid.cutCells);
		check(java.util.Arrays.equals(result.cellNodeId(), rescanned.cellNodeId())
				&& java.util.Arrays.equals(result.cellLineageNodeId(), rescanned.cellLineageNodeId())
				&& Double.doubleToRawLongBits(stableVolumes[0])
				== Double.doubleToRawLongBits(rescanned.sealedAvailableVolume()[0]),
				"stable-lineage available-volume refresh differed from a complete cavity rescan");

		Grid openGrid = tank(n, false, 1.0F);
		for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
			int i = (z * n + 3) * n + x;
			openGrid.stockCount[i] = 1;
			openGrid.liquidVolume[i] = 0.5F;
		}
		AirCavityScanner.Result openResult = scan(openGrid, n * n * n);
		check(openResult.sealedNodeCount() == 0, "residual next to ambient must not create sealed inventory");
	}

	private static void validateScanBudget() {
		int n = 8;
		Grid grid = tank(n, true, 1.0F);
		AirCavityScanner.Result result = new AirCavityScanner(n, n, n, 1.0F, 50)
				.scan(grid.solid, grid.stockCount, grid.liquidVolume, grid.cutCells);
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
				.scan(grid.solid, grid.stockCount, grid.liquidVolume, grid.cutCells);
	}

	private static Grid tank(int n, boolean roof, float cellSize) {
		Grid grid = new Grid(n, n, n, cellSize);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			int i = (z * n + y) * n + x;
			boolean shell = x == 0 || x == n - 1 || z == 0 || z == n - 1 || y == 0 || (roof && y == n - 1);
			if (shell) grid.solid[i] = true;
			else if (y <= 3) { grid.stockCount[i] = 8; grid.liquidVolume[i] = 1.0F; }
		}
		return grid;
	}

	private static final class Grid {
		final int sizeX;
		final int sizeY;
		final int sizeZ;
		final float cellSize;
		final boolean[] solid;
		final int[] stockCount;
		final double[] liquidVolume;
		final FlipCutCellGeometry cutCells;

		Grid(int sizeX, int sizeY, int sizeZ, float cellSize) {
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.sizeZ = sizeZ;
			this.cellSize = cellSize;
			this.solid = new boolean[sizeX * sizeY * sizeZ];
			this.stockCount = new int[sizeX * sizeY * sizeZ];
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
