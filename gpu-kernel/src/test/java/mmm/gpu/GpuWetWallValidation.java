package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import mmm.fluid.MacMomentumState;
import mmm.inventory.DeviceLiquidInventory;
import mmm.units.DeviceDyadicArray;
import mmm.units.Mass;

/** Small resident geometry and viscosity scenario for wet fixed walls. */
public final class GpuWetWallValidation {
    private GpuWetWallValidation() { }
    private record Result(double bottomArea, double topArea, double sideArea, double bottomRatio,
                          double conductance, double heat, double initialK, double finalK, double viscosity) { }

    public static void main(String[] args) {
        int device = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        try (var context = new VulkanDeviceContext(device, true, true);
             var geometryKernel = new VulkanLiquidGeometry(context);
             var viscosityKernel = new VulkanLiquidViscosity(context)) {
            Result full = run(context, geometryKernel, viscosityKernel, 0, .5, 1, 1);
            Result half = run(context, geometryKernel, viscosityKernel, -1, .5, 1, 1);
            Result film = run(context, geometryKernel, viscosityKernel, -3, .5, 1, 1);
            Result thin = run(context, geometryKernel, viscosityKernel, -8, .5, 1, 1);
            Result compact = run(context, geometryKernel, viscosityKernel, -15, .5, 1, 1);
            Result still = run(context, geometryKernel, viscosityKernel, -1, .5, 1, 0);
            Result viscous = run(context, geometryKernel, viscosityKernel, -1, .5, 2, 1);
            Result larger = run(context, geometryKernel, viscosityKernel, -1, 1, 1, 1);
            Result saline = run(context, geometryKernel, viscosityKernel, -1, .5, 1, 1, true);
            near(full.bottomArea, 1, 1e-5, "full bottom area");
            near(full.topArea, 1, 1e-5, "full top area");
            near(half.bottomArea, 1, 1e-5, "partial bottom area");
            near(half.topArea, 0, 1e-5, "dry upper wall");
            near(half.sideArea, .5, 1e-4, "partial side area");
            near(film.bottomArea, 1, 1e-5, "film bottom area");
            near(film.topArea, 0, 1e-5, "film dry upper wall");
            near(film.sideArea, .125, 1e-4, "film side area");
            near(thin.bottomArea, 1, 1e-5, "thin film bottom area");
            near(thin.topArea, 0, 1e-5, "thin film dry upper wall");
            near(thin.heat, thin.initialK - thin.finalK, 1e-8, "thin film energy settlement");
            near(compact.bottomArea, 1, 1e-5, "compact film bottom area");
            near(compact.topArea, 0, 1e-5, "compact liquid dry upper wall");
            near(compact.heat, compact.initialK - compact.finalK, 1e-8, "compact liquid energy settlement");
            if (!(film.bottomRatio > half.bottomRatio && half.bottomRatio > full.bottomRatio))
                throw new AssertionError("Thin-film wall conductance must reflect its reduced depth");
            near(still.heat, 0, 1e-12, "stationary heat");
            near(viscous.conductance, 2 * half.conductance, 1e-9, "viscosity response");
            double saltMoles=Math.round(1000*.5*.5*.5*.5*10000)*1e-6;
            double molarity=saltMoles/(.5*.5*.5*.5*1000);
            var correction=saltCorrection();
            near(saline.viscosity, 1 + correction.a()*Math.sqrt(molarity)
                    + correction.b()*molarity, 1e-10, "dilute salt viscosity");
            near(larger.conductance, 2 * half.conductance, 1e-9, "geometric length response");
            if (!(half.heat > 0 && half.finalK < half.initialK))
                throw new AssertionError("Wall shear must dissipate moving liquid energy");
            near(half.heat, half.initialK - half.finalK, 1e-8, "viscous energy settlement");
            System.out.println("Wet wall geometry, stationary heat and viscous energy validated on " + context.deviceName());
        }
    }

    private static Result run(VulkanDeviceContext context, VulkanLiquidGeometry geometryKernel,
                              VulkanLiquidViscosity viscosityKernel, int exponent, double h, double mu, double speed) {
        return run(context,geometryKernel,viscosityKernel,exponent,h,mu,speed,false);
    }
    private static Result run(VulkanDeviceContext context, VulkanLiquidGeometry geometryKernel,
                              VulkanLiquidViscosity viscosityKernel, int exponent, double h, double mu, double speed, boolean saline) {
        int sx = 3, sy = 2, sz = 2, n = sx * sy * sz;
        double volume = Math.scalb(1.0, exponent), mass = 1000 * volume * h * h * h;
        int[] offsets = new int[n + 1], materials = new int[n], supports = new int[n], signs = new int[n],
                exponents = new int[n], limbs = new int[n], volumeOffsets = new int[n + 1];
        long[] masses = new long[n], energy = new long[n];
        for (int c = 0; c < n; c++) {
            offsets[c] = c; volumeOffsets[c] = c; supports[c] = 255; signs[c] = 1;
            exponents[c] = exponent; limbs[c] = 1; masses[c] = Mass.fromKilograms(mass);
            energy[c] = Double.doubleToRawLongBits(mass * 4200 * 293.15);
        }
        offsets[n] = n; volumeOffsets[n] = n;
        int[] mixture=new int[n], speciesOffsets=new int[n+1], speciesHandles=new int[saline?3*n:0];
        long[] speciesAmounts=new long[speciesHandles.length], residual=new long[n];
        if(saline) for(int c=0;c<n;c++) {
            mixture[c]=1;speciesOffsets[c]=3*c;
            speciesHandles[3*c]=0;speciesHandles[3*c+1]=1;speciesHandles[3*c+2]=2;
            speciesAmounts[3*c+1]=speciesAmounts[3*c+2]=Math.round(mass*10000);
            speciesAmounts[3*c]=(masses[c]-580*speciesAmounts[3*c+1])/180;
            residual[c]=masses[c]-180*speciesAmounts[3*c]-580*speciesAmounts[3*c+1];
        }
        if(saline)speciesOffsets[n]=3*n;
        var inventory = new DeviceLiquidInventory(offsets, materials, supports,
                new DeviceDyadicArray(volumeOffsets, signs, exponents, limbs), masses, energy,
                mixture, speciesOffsets, speciesHandles, speciesAmounts, residual);
        double[] xMomentum = new double[(sx + 1) * sy * sz];
        var grid=mmm.spatial.SpatialGrid.rectangular(sx,sy,sz);
        for (int z = 0; z < sz; z++) for (int y = 0; y < sy; y++) for (int x = 1; x < sx; x++)
            xMomentum[grid.faceAt(x,y,z,0)] = mass * speed;
        var momentum = new MacMomentumState(sx, sy, sz, xMomentum, new double[sx * (sy + 1) * sz], new double[sx * sy * (sz + 1)]);
        long alignment = Math.max(8, context.storageOffsetAlignment());
        var liquid = VulkanLiquidStateLayout.forState(inventory, momentum, alignment);
        var geometry = GpuGasFixture.geometry(liquid, alignment);
        long temperatureOffset = (geometry.endOffset() + alignment - 1) & -alignment;
        var temperature = new VulkanLiquidStateLayout.Column(temperatureOffset, n, 8);
        var viscous = new VulkanLiquidViscosity.Layout(liquid, geometry.cells(), geometry.faces(), geometry.walls(),
                Math.toIntExact(temperature.offset() / 4), -1,
                saline?new VulkanLiquidViscosity.Tables(new int[]{0,1},new double[]{273,1000,mu,mu},0,0,1,2,3,-1,0,
                        mmm.fluid.TestFluidDefinitions.load().chemistry().species(mmm.fluid.TestFluidDefinitions.load().chemistry().speciesHandle("mmm:h2o")).molarMassKgPerMol().toDouble(),0,saltCorrection())
                        :new VulkanLiquidViscosity.Tables(new int[]{0, 1}, new double[]{273, 1000, mu, mu}),
                null, true, temperature.offset() + temperature.bindingBytes(), alignment);
        var initial = ByteBuffer.allocate(Math.toIntExact(viscous.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
        liquid.importState(initial, inventory, momentum); viscous.initializeHeaderAndTables(initial);
        for (int c = 0; c < n; c++) initial.putDouble(Math.toIntExact(temperature.offset() + 8L * c), saline?298.15:293.15);
        try (var storage = new VulkanRegionStorage(context, viscous.endOffset(), 1024 * 1024,liquid.grid())) {
            storage.initialize(initial, 0);
            try (var capture = storage.capture(); var candidate = storage.prepare(capture)) {
                var feedback = candidate.beginBatch((cmd, input, output, errors, staging, stack) -> {
                    geometryKernel.rebindRanges(GpuGasFixture.geometryRanges(liquid, geometry, output, errors));
                    geometryKernel.record(cmd, sx, sy, sz);
                    viscosityKernel.rebindRanges(new FlipKernelBindings.BufferRange(input), new FlipKernelBindings.BufferRange(output), new FlipKernelBindings.BufferRange(errors));
                    viscosityKernel.record(cmd, viscous, h, .05);
                }, 0, 0);
                if (feedback.error() != 0) throw new IllegalStateException("Wet wall GPU error=" + feedback.error());
                int error = candidate.finishBatch((cmd, input, output, errors, staging, stack) -> { });
                if (error != 0) throw new IllegalStateException("Wet wall GPU error=" + error);
                ByteBuffer walls = read(candidate, geometry.walls());
                ByteBuffer planes = read(candidate, geometry.planes());
                ByteBuffer conductance = read(candidate, viscous.column(VulkanLiquidViscosity.Field.WALL_CONDUCTANCE));
                ByteBuffer viscosity = read(candidate, viscous.column(VulkanLiquidViscosity.Field.MU));
                ByteBuffer heat = read(candidate, viscous.column(VulkanLiquidViscosity.Field.SOURCE_CELL_HEAT));
                ByteBuffer before = read(candidate, viscous.column(VulkanLiquidViscosity.Field.SOURCE_FACE_K));
                ByteBuffer after = read(candidate, viscous.column(VulkanLiquidViscosity.Field.FINAL_FACE_K));
                int lower = 1, upper = 1 + sx;
                System.out.printf("fill=2^%d plane=(%.9g,%.9g,%.9g,%.9g) bottom=%.9g top=%.9g side=%.9g%n",
                        exponent, planes.getDouble(32*lower), planes.getDouble(32*lower+8), planes.getDouble(32*lower+16),
                        planes.getDouble(32*lower+24), walls.getDouble(8*(12*lower+4)),
                        walls.getDouble(8*(12*upper+6)), walls.getDouble(8*(12*lower+8)));
                double totalHeat = sum(heat), initialK = sum(before), finalK = sum(after);
                return new Result(walls.getDouble(8 * (12 * lower + 4)), walls.getDouble(8 * (12 * upper + 6)),
                        walls.getDouble(8 * (12 * lower + 8)), walls.getDouble(8 * (12 * lower + 5)),
                        conductance.getDouble(8 * (3 * lower)), totalHeat, initialK, finalK, viscosity.getDouble(8*lower));
            }
        }
    }
    private static ByteBuffer read(VulkanRegionStorage.Candidate candidate, VulkanLiquidStateLayout.Column column) {
        var out = ByteBuffer.allocate(Math.toIntExact(column.bytes())).order(ByteOrder.LITTLE_ENDIAN);
        candidate.read(column.offset(), out); return out;
    }
    private static mmm.chemistry.ChemicalPhaseDefinition.ViscosityCorrection saltCorrection(){
        return mmm.fluid.TestFluidDefinitions.load().chemistry().phases().stream().filter(phase->phase.materialId().equals("mmm:water"))
                .map(mmm.chemistry.ChemicalPhaseDefinition::viscosityCorrection).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
    }
    private static double sum(ByteBuffer buffer) { double result = 0; for (int i = 0; i < buffer.capacity(); i += 8) result += buffer.getDouble(i); return result; }
    private static void near(double actual, double expected, double tolerance, String label) {
        if (Math.abs(actual - expected) > tolerance * Math.max(1, Math.abs(expected)))
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
}
