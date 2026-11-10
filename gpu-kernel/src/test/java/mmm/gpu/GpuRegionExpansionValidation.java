package mmm.gpu;

import java.util.List;
import mmm.fluid.*;

/** Checks the production edge observation without exporting a resident fluid generation. */
public final class GpuRegionExpansionValidation {
    public static void main(String[] args) {
        var definitions=TestFluidDefinitions.load();
        try(var backend=new VulkanFluidSolverBackend(args.length==0?0:Integer.parseInt(args[0]))) {
            check(backend,definitions,new int[0],0);
            check(backend,definitions,new int[]{4+8*(4+8*4)},0);
            check(backend,definitions,new int[]{0,7+8*(7+8*7)},63);
            check(backend,definitions,new int[]{1+8*(4+8*4)},1);
            check(backend,definitions,new int[]{1+8*(4+8*4)},0,true);
            resizeCandidate(backend, definitions);
        }
        System.out.println("Resident region edge observation: empty, interior, all faces and negative X passed");
    }

    private static void resizeCandidate(VulkanFluidSolverBackend backend, FluidDefinitions definitions) {
        var sourceGrid = mmm.spatial.SpatialGrid.rectangular(8, 8, 8);
        var targetGrid = mmm.spatial.SpatialGrid.rectangular(16, 8, 8);
        var domain = new HostFluidState(sourceGrid, new FluidSolverConfig(.5f, 0, .05f, 200, 1e-5f, .002f), definitions);
        var materials = new FluidMaterialRegistry(List.of(definitions.material().entry("mmm:water").material()));
        domain.bindMaterialRegistry(materials);
        domain.bindMaterialDefinitions(definitions.material());
        domain.setVolume(2, 2, 2, .5f);
        domain.bindGasRegistry(definitions.gas());
        domain.bindThermal(definitions.thermal(), materials, 293.15);
        double[] mx = new double[sourceGrid.faceCount(0)], my = new double[sourceGrid.faceCount(1)], mz = new double[sourceGrid.faceCount(2)];
        int face = sourceGrid.faceAt(2, 2, 2, 0);
        mx[face - sourceGrid.faceOffset(0)] = 10.0;
        domain.restoreMacMomentum(new MacMomentumState(sourceGrid, mx, my, mz));
        var environment = mmm.thermal.EnvironmentThermalState.initialized("mmm:resize", definitions.thermal().fingerprint(), List.of(), List.of());
        backend.configureEnvironment(environment, definitions);
        try (var initial = backend.prepareState(null, domain.captureAuthoritativeState(), null, List.of());
             var old = initial.commit()) {
            var previous = old.exportState();
            var map = new SpatialGridRemap(sourceGrid, targetGrid, 8, 0, 0);
            var newGas = new java.util.ArrayList<mmm.fluid.air.GasInventory>(
                    java.util.Collections.nCopies(targetGrid.cellCount(), mmm.fluid.air.GasInventory.EMPTY));
            double[] energy = new double[targetGrid.cellCount()];
            var ambient = definitions.gas().createAmbient(125_000_000L);
            double ambientEnergy = new mmm.thermal.GasThermalModel(definitions.thermal(), definitions.gas())
                    .internalEnergyAtTemperature(ambient, 293.15);
            for (int cell = 0; cell < targetGrid.cellCount(); cell++) if (targetGrid.x(cell) < 8) {
                newGas.set(cell, ambient);
                energy[cell] = ambientEnergy;
            }
            var gas = map.gas(previous.spatialGas(), newGas, energy);
            var audit = previous.thermalOwner().audit().withExternalSourceEnergy(mmm.units.SignedDyadic.sum(energy));
            double[] boundary = previous.copyThermalBoundaryTemperaturesK() == null ? null : new double[targetGrid.cellCount() * 6];
            if (boundary != null) java.util.Arrays.fill(boundary, Double.NaN);
            var resized = FluidDomainResize.prepare(previous, targetGrid, 8, 0, 0,
                    FixedGeometrySnapshot.fromMasks(targetGrid, new byte[targetGrid.cellCount()]), gas, audit, boundary);
            try (var env = backend.prepareEnvironment(environment, definitions);
                 var discarded = backend.prepareState(old, resized, null, List.of())) {
                discarded.attachEnvironment(env);
            }
            if (backend.captureEnvironment("mmm:resize").generation() != environment.generation())
                throw new AssertionError("Discard changed environment");
            try (var env = backend.prepareEnvironment(environment, definitions);
                 var prepared = backend.prepareState(old, resized, null, List.of())) {
                prepared.attachEnvironment(env);
                try (var accepted = prepared.commit()) {
                    var saved = accepted.exportState();
                    var restored = HostFluidState.restoreAuthoritativeState(saved).captureAuthoritativeState();
                    int moved = targetGrid.cell(10, 2, 2);
                    if (restored.liquid().rows().get(moved).isEmpty()
                            || !restored.liquid().rows().get(moved).getFirst().volume().equals(previous.liquid().rows().get(sourceGrid.cell(2, 2, 2)).getFirst().volume()))
                        throw new AssertionError("Resized liquid inventory changed");
                    if (!restored.spatialGas().cells().get(moved).equals(previous.spatialGas().cells().get(sourceGrid.cell(2, 2, 2))))
                        throw new AssertionError("Resized gas inventory changed");
                    int movedFace = map.face(face) - targetGrid.faceOffset(0);
                    if (restored.momentum().copyX()[movedFace] != previous.momentum().copyX()[face - sourceGrid.faceOffset(0)])
                        throw new AssertionError("Resized face momentum changed");
                    if (backend.captureEnvironment("mmm:resize").generation() != environment.generation() + 1)
                        throw new AssertionError("Environment was not jointly adopted");
                }
            }
        }
    }
    private static void check(VulkanFluidSolverBackend backend,FluidDefinitions definitions,int[] wetCells,int expected) {
        check(backend,definitions,wetCells,expected,false);
    }
    private static void check(VulkanFluidSolverBackend backend,FluidDefinitions definitions,int[] wetCells,int expected,boolean closeWest) {
        var domain=new HostFluidState(8,8,8,new FluidSolverConfig(.5f,0,.05f,200,1e-5f,.002f),definitions);
        domain.bindMaterialRegistry(new FluidMaterialRegistry(List.of(definitions.material().entry("mmm:water").material())));
        domain.bindMaterialDefinitions(definitions.material());
        float[] volumes=new float[512]; for(int cell:wetCells)volumes[cell]=.5f;
        domain.setVolumes(volumes);
        var initial=domain.captureAuthoritativeState();
        if(closeWest){
            var grid=initial.grid();int[] exterior=new int[grid.cellCount()];
            exterior[grid.cell(0,4,4)]=15;
            var fixed=FixedGeometrySnapshot.fromMasks(grid,initial.copyCollisionMasks(),exterior,List.of());
            initial=FluidDomainResize.prepare(initial,grid,0,0,0,fixed,initial.spatialGas(),null,null);
        }
        try(var prepared=backend.prepareState(null,initial,null,List.of());
            var state=prepared.commit()) {
            int flags=((ResidentFluidState)state).expansionDirections();
            if(flags!=expected)throw new AssertionError("Expected edge flags "+expected+", found "+flags);
        }
    }
}
