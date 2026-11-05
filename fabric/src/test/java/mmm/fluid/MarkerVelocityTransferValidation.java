package mmm.fluid;

import java.util.Arrays;
import java.util.List;
import mmm.units.Mass;

/** Actual SI dual-face inertia, one-time initial velocity input and persistent MAC momentum. */
public final class MarkerVelocityTransferValidation {
	private MarkerVelocityTransferValidation() { }
	public static void main(String[] args) { validate(); System.out.println("Cell/MAC inertia validation passed"); }
	public static void validate() {
		initialInputKeepsActualMassAndMomentum();
		adjacentCellsSupplyActualFaceMass();
		closedFacesStopNormalMomentum();
        mixedHostEditsCarryMassAndEnergy();
        bucketDisplacesGasIntoOpenCells();
        isolatedGasCompressesWithoutTransport();
        thinFilmSealsItsWettedFace();
        spatialGasWetQueriesSurviveRestore();
        containerEditsFollowOpenFaces();
        ambientFaceMomentumSurvivesLocalEdit();
        ambientResetAndVentCarryKineticEnergy();
	}
	private static HostFluidState domain() {
		return new HostFluidState(4,4,4,new FluidSolverConfig(.5F,1000,0,.001F,400,1e-5F,.002F));
	}
	private static void initialInputKeepsActualMassAndMomentum() {
		for (double scale : new double[]{125, 1.25e8}) {
			var d=domain(); long[] masses={Mass.fromKilograms(scale),Mass.fromKilograms(3*scale)};
			float[] velocity={1,-2,3, 5,2,-1};
			d.restoreMaterialParticleState(new FlipMaterialParticleState(4,4,4,.5F,2,.25,
					Math.addExact(masses[0],masses[1]),List.of(new FluidMaterial("test:liquid",1000,"test:liquid",0,Math.PI/2)),
					new float[]{.625F,.625F,.625F,.125F, .625F,.625F,.625F,.125F},velocity,new byte[64],masses,new int[2]));
			d.domainFlowFields.preparePhysicalState();
			near(d.totalMassUnits(),Math.addExact(masses[0],masses[1]),0,"Integer input mass");
			double totalMass=Mass.kilograms(masses[0])+Mass.kilograms(masses[1]);
			double kineticInput=0;
			for(int axis=0;axis<3;axis++) {
				double p=Mass.kilograms(masses[0])*velocity[axis]+Mass.kilograms(masses[1])*velocity[3+axis];
				double[] inertia=axis==0?d.cellMacStage.massX:axis==1?d.cellMacStage.massY:d.cellMacStage.massZ;
				double[] momentum=axis==0?d.mac.momentumX:axis==1?d.mac.momentumY:d.mac.momentumZ;
				float[] speed=axis==0?d.mac.velocityX:axis==1?d.mac.velocityY:d.mac.velocityZ;
				near(Arrays.stream(inertia).sum(),totalMass,8*Math.ulp(totalMass),"SI dual-face mass");
				near(Arrays.stream(momentum).sum(),p,8*Math.ulp(Math.max(1,Math.abs(p))),"Input momentum");
				for(int face=0;face<inertia.length;face++) if(inertia[face]>0)
					near(speed[face],p/totalMass,4*Math.ulp((float)(p/totalMass)),"Mass-weighted initial velocity");
				kineticInput+=.5*Mass.kilograms(masses[0])*velocity[axis]*velocity[axis]
						+.5*Mass.kilograms(masses[1])*velocity[3+axis]*velocity[3+axis];
			}
			if(d.liquidKineticEnergyJoules()>kineticInput+16*Math.ulp(kineticInput))throw new AssertionError("Initial averaging amplified kinetic energy");
			var state=d.captureAuthoritativeState();var restored=HostFluidState.restoreAuthoritativeState(state);
			if(!state.liquid().stocks().equals(restored.exportLiquidCellState().stocks())
					||!Arrays.equals(state.momentum().copyX(),restored.exportMacMomentum().copyX())
					||!Arrays.equals(state.momentum().copyY(),restored.exportMacMomentum().copyY())
					||!Arrays.equals(state.momentum().copyZ(),restored.exportMacMomentum().copyZ()))
				throw new AssertionError("Typed handoff changed actual inventory or momentum");
		}
	}
	private static void adjacentCellsSupplyActualFaceMass() {
		var d=domain();d.bindMaterialRegistry(new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER)));
		long first=Mass.fromKilograms(25),second=Mass.fromKilograms(100);
		if(!d.domainInventory.tryInsertExactMaterial(1,1,1,.2F,first,0,0).transferred()
				||!d.domainInventory.tryInsertExactMaterial(2,1,1,.2F,second,0,0).transferred())throw new AssertionError("Variable-density input was refused");
		d.domainFlowFields.preparePhysicalState();
		near(d.cellMacStage.massX[d.domainFlowFields.xFaceIndex(2,1,1)],.5*(Mass.kilograms(first)+Mass.kilograms(second)),0,"Shared face actual inertia");
		near(d.cellMacStage.massX[d.domainFlowFields.xFaceIndex(1,1,1)],.5*Mass.kilograms(first),0,"Liquid/gas face actual inertia");
	}
	private static void closedFacesStopNormalMomentum() {
		var d=domain();d.setSolid(1,1,1,true);d.setVolume(2,1,1,.2F);d.domainFlowFields.preparePhysicalState();
		int face=d.domainFlowFields.xFaceIndex(2,1,1);
		double[][] momentum={d.mac.momentumX.clone(),d.mac.momentumY.clone(),d.mac.momentumZ.clone()};
		momentum[0][face]=-7*d.cellMacStage.massX[face];
		var candidate=d.cellMacStage.prepareMomentumInput(momentum,d.liquidCells,null);
		near(candidate.momentum().copyX()[face],0,0,"Closed face normal momentum");
		if(candidate.heatByCell().isEmpty())throw new AssertionError("Stopped momentum must transfer kinetic energy to the liquid");
	}
    private static HostFluidState mixedDomain(boolean sealed) {
        try {
            var regs=mmm.simulation.FullCapabilityTestFixture.load();var d=domain();
            if(sealed)d.sealDomainBoundary();
            mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(d,regs.liquids());
            d.setVolume(1,1,1,.5F);mmm.simulation.FullCapabilityTestFixture.bindFull(d,regs,293.15,8);
            d.gasNodeAt(1.25,.75,.75);d.domainFlowFields.preparePhysicalState();return d;
        } catch(Exception failure){throw new IllegalStateException("Cannot prepare mixed-host edit fixture",failure);}
    }
    private static void setFaceMotion(HostFluidState d) {
        d.domainFlowFields.ensureInventoryGrid();
        for(int axis=0;axis<3;axis++){
            double[] p=axis==0?d.mac.momentumX:axis==1?d.mac.momentumY:d.mac.momentumZ;
            double[] mass=axis==0?d.cellMacStage.massX:axis==1?d.cellMacStage.massY:d.cellMacStage.massZ;
            for(int face=0;face<p.length;face++)p[face]=d.cellMacStage.isWallFace(axis,face,null)?0:mass[face]*(axis==0?1.25:axis==1?-.4:.2);
        }
        d.mac.markMomentumChanged();d.velocityGridDirty=true;
    }
    private static double kinetic(HostFluidState d) {
        d.domainFlowFields.ensureInventoryGrid();double total=0;
        for(int axis=0;axis<3;axis++){
            double[] p=axis==0?d.mac.momentumX:axis==1?d.mac.momentumY:d.mac.momentumZ;
            double[] mass=axis==0?d.cellMacStage.massX:axis==1?d.cellMacStage.massY:d.cellMacStage.massZ;
            for(int i=0;i<p.length;i++)if(mass[i]>0)total+=.5*p[i]*p[i]/mass[i];else if(p[i]!=0)throw new AssertionError("Face momentum lost its mixture inertia");
        }
        return total;
    }
    private static long totalGasMass(HostFluidState d) {
        long mass=0;for(var gas:d.gasState.spatial.cells())mass=Math.addExact(mass,gas.totalMassUnits());return mass;
    }
    private static void mixedHostEditsCarryMassAndEnergy() {
        var d=mixedDomain(true);setFaceMotion(d);
        int node=d.gasNodeAt(1.25,.75,.75);long gasBefore=totalGasMass(d);
        double totalBefore=d.exactInternalEnergy().toDouble()+kinetic(d);
        var inserted=d.editGas(node,mmm.fluid.air.BuiltinGasDefinitions.OXYGEN,100_000_000,293.15,false);
        if(totalGasMass(d)!=gasBefore+inserted.moved().totalMassUnits())throw new AssertionError("Gas injection lost mass");
        near(d.exactInternalEnergy().toDouble()+kinetic(d),totalBefore+inserted.internalEnergy().toDouble(),1e-5,"Stationary gas injection U + kinetic");
        double sinkBefore=d.cellMacStage.externalKineticSinkJoules();totalBefore=d.exactInternalEnergy().toDouble()+kinetic(d);
        var removed=d.editGas(node,mmm.fluid.air.BuiltinGasDefinitions.OXYGEN,100_000_000,293.15,true);
        double carriedK=d.cellMacStage.externalKineticSinkJoules()-sinkBefore;
        if(!(carriedK>0)||totalGasMass(d)!=Math.subtractExact(Math.addExact(gasBefore,inserted.moved().totalMassUnits()),removed.moved().totalMassUnits()))throw new AssertionError("Gas extraction lost its mass or carried kinetic energy");
        near(d.exactInternalEnergy().toDouble()+kinetic(d)+removed.internalEnergy().toDouble()+carriedK,totalBefore,1e-5,"Gas extraction U + kinetic");
        long liquidBefore=d.totalMassUnits();sinkBefore=d.cellMacStage.externalKineticSinkJoules();
        var liquid=d.tryExtractExactVolume(1,1,1,.1F,0);
        if(!liquid.transferred()||d.totalMassUnits()+liquid.massUnits()!=liquidBefore||!(d.cellMacStage.externalKineticSinkJoules()>sinkBefore))
            throw new AssertionError("Liquid extraction lost its mass or carried kinetic energy");
        long beforeCollisionGas=totalGasMass(d);d.setSolid(1,1,1,true);
        if(totalGasMass(d)!=beforeCollisionGas)throw new AssertionError("Closed-cell gas did not move to an open neighbor");
        if(!d.gasBoundary().auditClosed()||!d.thermalAudit().acceptsInventory(d.exactInternalEnergy()))
            throw new AssertionError("Mixed host edit gas or thermal audit did not close");
    }
    private static void bucketDisplacesGasIntoOpenCells() {
        try {
            var regs=mmm.simulation.FullCapabilityTestFixture.load();var d=domain();d.sealDomainBoundary();
            mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(d,regs.liquids());
            mmm.simulation.FullCapabilityTestFixture.bindFull(d,regs,293.15,8);
            d.gasNodeAt(.75,.75,.75);long gasBefore=totalGasMass(d);
            var inserted=d.tryInsertExactVolume(1,1,1,8.0F,3);
            if(!inserted.transferred()||totalGasMass(d)!=gasBefore)throw new AssertionError("Bucket insertion failed to preserve closed-domain gas");
            var model=d.domainCapabilities.spatialGasModel();double maximum=0;
            for(int cell=0;cell<d.cellCount;cell++)if(!d.gasState.spatial.cells().get(cell).isEmpty())
                maximum=Math.max(maximum,model.state(d.gasState.spatial.cells().get(cell),d.gasState.spatial.energyAt(cell)).temperatureK());
            if(maximum>500)throw new AssertionError("Open neighboring gas overheated after bucket insertion: "+maximum);
            for(var stock:d.exportLiquidCellState().stocks())if(stock.massUnits()>0){
                var composition=stock.mixture()==null?java.util.Map.<String,Long>of():stock.mixture().stableAmounts(d.liquidCells.chemicalRegistry());
                double temperature=d.thermalState.stateFunction().stateForInternalEnergy(
                        d.thermalState.thermalMaterialHandleForFluid(stock.key().material()),stock.massKilograms(),composition,stock.internalEnergy()).temperatureK();
                if(temperature>350)throw new AssertionError("Bucket liquid overheated: "+temperature);
            }
            System.out.println("Closed bucket gas displacement max temperature: "+maximum+" K");
            if(!d.gasBoundary().auditClosed()||!d.thermalAudit().acceptsInventory(d.exactInternalEnergy()))
                throw new AssertionError("Bucket insertion failed gas or thermal audit");
        } catch(Exception failure){throw new IllegalStateException("Bucket displacement fixture failed",failure);}
    }
    private static void isolatedGasCompressesWithoutTransport() {
        try {
            var regs=mmm.simulation.FullCapabilityTestFixture.load();var d=domain();d.sealDomainBoundary();
            mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(d,regs.liquids());
            for(int z=0;z<4;z++)for(int y=0;y<4;y++)for(int x=0;x<4;x++)
                if(x!=1||y!=1||z!=1)d.setSolid(x,y,z,true);
            mmm.simulation.FullCapabilityTestFixture.bindFull(d,regs,293.15,8);
            d.gasNodeAt(.75,.75,.75);long gasBefore=totalGasMass(d);
            if(!d.tryInsertExactVolume(1,1,1,.5F,0).transferred()||totalGasMass(d)!=gasBefore)
                throw new AssertionError("Isolated gas was lost during liquid insertion");
            int cell=1+4*(1+4);double temperature=d.domainCapabilities.spatialGasModel().state(d.gasState.spatial.cells().get(cell),d.gasState.spatial.energyAt(cell)).temperatureK();
            if(!(temperature>293.15))throw new AssertionError("Isolated gas did not compress: "+temperature+" volume="+d.liquidCells.exactCellVolume(cell)+" gas="+d.gasState.spatial.cells().get(cell).standardVolumeNanos());
            if(!d.gasBoundary().auditClosed()||!d.thermalAudit().acceptsInventory(d.exactInternalEnergy()))
                throw new AssertionError("Isolated gas compression audit failed");
        } catch(Exception failure){throw new IllegalStateException("Isolated gas fixture failed",failure);}
    }
    private static void thinFilmSealsItsWettedFace() {
        var d=domain();d.setVolume(1,1,1,1f/256);d.pressureSystem.prepareGeometry();
        var phase=d.pressureSystem.phaseGeometry();int cell=1+4*(1+4);boolean closed=false,open=false;
        for(int axis=0;axis<3;axis++)for(int side=0;side<2;side++){
            double area=0;
            for(int a=0;a<2;a++)for(int b=0;b<2;b++)
                area+=GasFaceIntersection.area(phase,cell,-1,axis,side,0,.5*a,.5*b,.5);
            closed|=area==0;open|=area>.99;
        }
        if(!closed||!open)throw new AssertionError("Thin liquid film did not retain its wetted and dry faces");
    }
    private static void containerEditsFollowOpenFaces() {
        var d=new HostFluidState(5,5,5,new FluidSolverConfig(.5F,1000,0,.05F,48,1e-4F,.002F));
        for(int y=0;y<5;y++)for(int z=0;z<5;z++)d.setSolid(2,y,z,true);
        d.setVolume(3,2,2,.25F);
        if(d.tryExtractExactVolume(1,2,2,.125F,4).transferred())
            throw new AssertionError("Container extracted through a sealed wall");
        if(d.containerExtractableVolumeNear(1,2,2,0,0)!=0)
            throw new AssertionError("Local sample crossed its selected cell");
        d.setSolid(2,2,2,false);
        if(!d.tryExtractExactVolume(1,2,2,.125F,2).transferred())
            throw new AssertionError("Open passage did not allow container extraction");
        d.setVolume(3,2,2,.25F);
        d.setSolid(2,2,2,true);
        if(!d.tryInsertExactVolume(1,2,2,2F,4).transferred())
            throw new AssertionError("Open side refused normal container insertion");
        if(d.domainFlowFields.exactVolumeInCell(3,2,2)!=.25F)
            throw new AssertionError("Container insertion crossed a sealed wall");
        d.setSolid(2,2,2,false);
        var reachable=d.domainFlowFields.nearbyCells(1,2,2,2);
        if(reachable.stream().noneMatch(c->c.index()==d.domainFlowFields.cellIndex(3,2,2)&&c.distanceSquared()==4))
            throw new AssertionError("Open passage did not expose the shortest connected path");
        if(!d.tryExtractExactVolume(3,2,2,.125F,0).transferred())
            throw new AssertionError("Local sample refused liquid in its selected cell");
    }
    private static void spatialGasWetQueriesSurviveRestore() {
        var d=mixedDomain(true);
        var bounds=new mmm.world.MovingBodyFluidCoupling.Bounds(.55,.5,.55,.95,.8,.95);
        var face=new FluidDomainContracts.WaterContactFace(1,.5,.5,1,.5,1,1);
        var before=MovingBodyWetGeometry.capture(d,bounds).sample();
        var contacts=d.waterContacts(List.of(face));
        if(!(before.displacedVolumeCubicMetres()>0 && before.waterContactVolumeCubicMetres()>0
                && before.wetSurfaceAreaSquareMetres()>0) || contacts.isEmpty()
                || !(contacts.stream().mapToDouble(FluidDomainContracts.WaterContact::areaSquareMetres).sum()>0))
            throw new AssertionError("Spatial gas wet body or water contact is empty");
        var gasBounds=new mmm.world.MovingBodyFluidCoupling.Bounds(.9,.6,.6,1.1,.9,.9);
        var gasBefore=MovingBodyWetGeometry.capture(d,gasBounds).sample();
        int node=d.gasNodeAt(1.25,.75,.75);
        d.editGas(node,mmm.fluid.air.BuiltinGasDefinitions.OXYGEN,100_000_000,293.15,false);
        var after=MovingBodyWetGeometry.capture(d,gasBounds).sample();
        var currentWet=MovingBodyWetGeometry.capture(d,bounds).sample();
        var currentContacts=d.waterContacts(List.of(face));
        if(!(Math.abs(after.pressureForceXNewtons()-gasBefore.pressureForceXNewtons())>1e-8))throw new AssertionError("Local gas pressure did not act on body");
        var restored=HostFluidState.restoreAuthoritativeState(d.captureAuthoritativeState());
        var restoredWet=MovingBodyWetGeometry.capture(restored,bounds).sample();
        var restoredGas=MovingBodyWetGeometry.capture(restored,gasBounds).sample();
        near(restoredWet.displacedVolumeCubicMetres(),currentWet.displacedVolumeCubicMetres(),1e-8,"Restored wet body volume");
        near(restored.waterContacts(List.of(face)).stream().mapToDouble(FluidDomainContracts.WaterContact::areaSquareMetres).sum(),
                currentContacts.stream().mapToDouble(FluidDomainContracts.WaterContact::areaSquareMetres).sum(),1e-8,"Restored water contact area");
        near(restoredGas.pressureForceXNewtons(),after.pressureForceXNewtons(),1e-6,"Restored local gas pressure force");
    }
    private static void ambientFaceMomentumSurvivesLocalEdit() {
        var d=mixedDomain(false);d.domainFlowFields.ensureInventoryGrid();
        int face=d.domainFlowFields.xFaceIndex(0,1,1);double mass=d.cellMacStage.massX[face];
        if(!(mass>0)||d.cellMacStage.isWallFace(0,face,null))throw new AssertionError("Exterior gas face is not open");
        d.mac.momentumX[face]=mass*1.25;d.mac.markMomentumChanged();d.velocityGridDirty=true;
        double expected=d.mac.momentumX[face];int node=d.gasNodeAt(1.25,1.25,1.25);
        d.editGas(node,mmm.fluid.air.BuiltinGasDefinitions.OXYGEN,100_000_000,293.15,false);
        near(d.mac.momentumX[face],expected,1e-12,"Open exterior face momentum");
    }

    private static void ambientResetAndVentCarryKineticEnergy() {
        var d=mixedDomain(true);setFaceMotion(d);
        var beforeAudit=d.thermalAudit();double before=d.exactInternalEnergy().toDouble()+kinetic(d);
        double sinkBefore=d.cellMacStage.externalKineticSinkJoules();d.resetAmbientGas();
        var afterAudit=d.thermalAudit();double source=afterAudit.externalSource().subtract(beforeAudit.externalSource()).toDouble();
        double sink=afterAudit.externalSink().subtract(beforeAudit.externalSink()).toDouble();
        double kineticSink=d.cellMacStage.externalKineticSinkJoules()-sinkBefore;
        if(!(kineticSink>0))throw new AssertionError("Reset gas lost its carried kinetic energy");
        near(d.exactInternalEnergy().toDouble()+kinetic(d)+sink+kineticSink,before+source,1e-5,"Ambient reset U + kinetic");
        before=d.exactInternalEnergy().toDouble()+kinetic(d);sinkBefore=d.cellMacStage.externalKineticSinkJoules();
        var vent=d.commitOperatorVentSealedGas(d.planOperatorVentSealedGas());
        kineticSink=d.cellMacStage.externalKineticSinkJoules()-sinkBefore;
        if(!(kineticSink>0)||totalGasMass(d)!=0)throw new AssertionError("Vent lost gas kinetic energy or retained gas");
        near(d.exactInternalEnergy().toDouble()+kinetic(d)+vent.internalEnergy().toDouble()+kineticSink,before,1e-5,"Gas vent U + kinetic");
        if(!d.thermalAudit().acceptsInventory(d.exactInternalEnergy()))throw new AssertionError("Reset or vent thermal audit did not close");
    }

	private static void near(double actual,double expected,double tolerance,String label) {
		if(!(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance))throw new AssertionError(label+": "+actual+" != "+expected);
	}
}
