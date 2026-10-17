package mmm.fluid;

import java.util.ArrayList;

/** Shared liquid pressure and bounded cell-scale velocity reconstruction. */
public final class MarkerPressureValidation {
    private MarkerPressureValidation() { }
    public static void main(String[] args) {
        if (args.length > 1) throw new IllegalArgumentException("Expected one pressure selector");
        if (args.length == 1 && !java.util.Set.of("--thin-layer", "--kernel").contains(args[0]))
            throw new IllegalArgumentException("Unknown pressure selector: " + args[0]);
        thinLayersKeepBoundedVelocity();
        if (args.length == 0 || !args[0].equals("--thin-layer")) {
            closedLiquidRestsAfterRestore();
            CellRemapMomentsValidation.main(new String[]{"common-mesh"});
        }
        System.out.println("Shared pressure validation passed");
    }

    private static void thinLayersKeepBoundedVelocity() {
        for (int axis = 0; axis < 3; axis++) for (double half : new double[]{.2, .02, 2.38e-7, 0})
            for (boolean constant : new boolean[]{false, true}) {
                var d = new FluidDomain(2, 2, 2, new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.4F,.002F,0));
                float[] positions = new float[32], velocities = new float[24];
                for (int p = 0; p < 8; p++) {
                    for (int a = 0; a < 3; a++) positions[4*p+a] = (float)(.5 + (((p >>> a)&1)==0 ? -1 : 1)*(a==axis?half:.2));
                    positions[4*p+3] = .0625F;
                    velocities[3*p+axis] = constant ? .25F : ((p>>>axis)&1)==0 ? -1e-4F : 1e-4F;
                }
                d.restoreParticleState(new FlipParticleState(2,2,2,1,8,.5F,positions,velocities,new byte[8]));
                var ids = new ArrayList<Integer>(); double mass=0;
                var moment = new double[3]; var momentum = new double[3];
                for (int p=0;p<8;p++) {
                    ids.add(p); double m=d.particles.mass(p); mass+=m;
                    for (int a=0;a<3;a++) { moment[a]+=m*(positions[4*p+a]-.5); momentum[a]+=m*velocities[3*p+a]; }
                }
                var gradient=CellRemapVelocity.gradient(d,ids,mass,moment,momentum);
                for (var row:gradient) for (double value:row)
                    if (!Double.isFinite(value) || Math.abs(value)>(constant?1e-12:.01))
                        throw new AssertionError("Unresolved thin support amplified a velocity gradient: "+value);
            }
    }

    private static void closedLiquidRestsAfterRestore() {
        var d = new FluidDomain(3,3,3,new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.4F,.002F,0));
        for(int z=0;z<3;z++)for(int y=0;y<3;y++)for(int x=0;x<3;x++)d.setVolume(x,y,z,1);
        for(int step=0;step<3;step++) {
            d = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0,d.captureAuthoritativeState(),java.util.List.of(),false,0,true)).claimPreparedCpuDomain();
            if(d.totalVolume()!=27)throw new AssertionError("Pressure changed closed liquid inventory");
            for(int p=0;p<d.particleCount();p++)if(Math.abs(d.particles.velocityX(p))+Math.abs(d.particles.velocityY(p))+Math.abs(d.particles.velocityZ(p))>1e-6)
                throw new AssertionError("Resting liquid acquired motion");
            d=FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
        }
    }
}
