package mmm.fluid;

/** Shared liquid pressure and macroscopic thin-layer motion. */
public final class MarkerPressureValidation {
    private MarkerPressureValidation() { }
    public static void main(String[] args) {
        if (args.length > 1) throw new IllegalArgumentException("Expected one pressure selector");
        if (args.length == 1 && !java.util.Set.of("--thin-layer", "--kernel", "--cavities").contains(args[0]))
            throw new IllegalArgumentException("Unknown pressure selector: " + args[0]);
        if (args.length==1&&args[0].equals("--cavities")) {
            separateCavitiesKeepIndependentPressure(); System.out.println("Independent cavity successors passed"); return;
        }
        thinLayersKeepBoundedVelocity();
        if (args.length == 0 || !args[0].equals("--thin-layer")) {
            closedLiquidRestsAfterRestore();
            separateCavitiesKeepIndependentPressure();
            LiquidMacroscopicMotionValidation.main(new String[]{"common-mesh"});
        }
        System.out.println("Shared pressure validation passed");
    }

    private static void thinLayersKeepBoundedVelocity() {
        for (float amount : new float[]{.02F, .2F}) for (int axis = 0; axis < 3; axis++) {
            var d = new FluidDomain(3, 3, 3, new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.002F));
            d.sealDomainBoundary(); d.setVolume(1,1,1,amount); d.domainFlowFields.preparePhysicalState();
            java.util.Arrays.fill(axis==0?d.mac.velocityX:axis==1?d.mac.velocityY:d.mac.velocityZ,.25F);
            d.cellMacStage.publishMomentum(); d.bootstrapGasState(null);
            var volume=d.liquidCells.exactVolume(); long mass=d.totalMassUnits();
            double kinetic=LiquidMacroscopicMotionValidation.Motion.capture(d).kinetic();
            for (int step=0;step<3;step++) {
                d.stepMechanicsOnly();
                if (!d.liquidCells.exactVolume().equals(volume)||d.totalMassUnits()!=mass)
                    throw new AssertionError("Thin-layer transport changed inventory");
                // Central MAC transport permits local overshoot at unresolved wet/dry faces;
                // its observable bound is total kinetic energy and finite motion.
                if (!Double.isFinite(d.maximumParticleSpeed())
                        || LiquidMacroscopicMotionValidation.Motion.capture(d).kinetic()>1.05*kinetic)
                    throw new AssertionError("Unforced thin layer gained kinetic energy");
                d=FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
            }
        }
    }

    private static void closedLiquidRestsAfterRestore() {
        var d = new FluidDomain(3,3,3,new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.002F));
        for(int z=0;z<3;z++)for(int y=0;y<3;y++)for(int x=0;x<3;x++)d.setVolume(x,y,z,1);
        for(int step=0;step<3;step++) {
            d = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0,d.captureAuthoritativeState(),java.util.List.of(),false,0,true)).claimPreparedCpuDomain();
            if(d.totalVolume()!=27)throw new AssertionError("Pressure changed closed liquid inventory");
            if(d.maximumParticleSpeed()>1e-6)
                throw new AssertionError("Resting liquid acquired motion");
            d=FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
        }
    }

    private static void separateCavitiesKeepIndependentPressure() {
        var d = new FluidDomain(4,3,3,new FluidSolverConfig(.5F,1000,0,.01F,400,1e-5F,.002F));
        byte[] masks = new byte[36]; java.util.Arrays.fill(masks,(byte)255);
        masks[17]=masks[19]=0;
        float[] points = new float[64];
        for(int side=0;side<2;side++)for(int bit=0;bit<8;bit++) {
            int p=side*8+bit;
            points[4*p]=(side==0?.625F:1.625F)+.25F*(bit&1);
            points[4*p+1]=.625F+.25F*(bit>>>1&1); points[4*p+2]=.625F+.25F*(bit>>>2&1);
            points[4*p+3]=.09375F;
        }
        d.restoreParticleState(new FlipParticleState(4,3,3,.5F,16,1.5F,points,new float[48],
                mmm.units.Mass.createLiquid(.5F,1000,points),masks));
        d.sealDomainBoundary(); d.synchronizeGasTopologyForPersistence(); d.domainFlowFields.preparePhysicalState();
        int left=d.gasState.frame.nodeOfPiece(17*8+2),right=d.gasState.frame.nodeOfPiece(19*8+2);
        if(left<=0||right<=0||left==right)throw new AssertionError("Distinct face contacts lost cavity ownership");
        d.setGasNodeStandardVolume(left,1.01*d.gasBoundary.availableVolume(left));
        d.setGasNodeStandardVolume(right,.99*d.gasBoundary.availableVolume(right));
        long gas=d.gasBoundary.sealedInventoryNanos(),mass=d.totalMassUnits(); double volume=d.totalVolumeExact();
        for(int step=0;step<3;step++) {
            d=new CpuFluidSolverBackend().evaluate(new FluidStepRequest(step,d.captureAuthoritativeState(),java.util.List.of(),false,0)).claimPreparedCpuDomain();
            if(d.gasBoundary.sealedInventoryNanos()!=gas
                    ||!d.gasBoundary.auditClosed()||d.totalMassUnits()!=mass||d.totalVolumeExact()!=volume)
                throw new AssertionError("Independent spatial gas pressures lost their conservative successor");
            d=FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
        }
    }
}
