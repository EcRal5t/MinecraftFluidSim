package mmm.gpu;

import java.util.List;
import java.util.Map;
import mmm.fluid.*;
import mmm.fluid.air.GasInventory;
import mmm.thermal.*;
import mmm.units.Mass;

/** Production world contact across a resolved half-block pipe bore. */
public final class GpuPipeThermalValidation {
    private GpuPipeThermalValidation() { }
    public static void main(String[] args) {
        int device=Integer.parseInt(args[0]);
        double metal=run(device,"mmm:ordinary_metal_wall"),insulated=run(device,"mmm:ordinary_insulation_wall");
        System.out.printf("pipe wall transfer: metal_J=%.9g insulated_J=%.9g%n",metal,insulated);
        if(!(metal>1e-6) || insulated!=0.0)throw new AssertionError("Pipe wall material did not control heat transfer");
    }
    private static double run(int device,String deviceId) {
        var defs=TestFluidDefinitions.load();var thermal=defs.thermal();
        var fluids=new FluidMaterialRegistry(List.of(defs.material().entry("mmm:water").material()));
        var domain=new HostFluidState(3,3,3,new FluidSolverConfig(.5f,0,.5f,200,1e-5f,.002f),defs);
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(defs.material());domain.sealDomainBoundary();
        for(int z=0;z<3;z++)for(int y=0;y<3;y++)for(int x=0;x<3;x++)domain.setSolid(x,y,z,true);
        // One cell at the corner of the 0.5 m square bore: two axial microboxes remain open.
        domain.setSolidMicroMask(1,1,1,0x3f);domain.setVolume(1,1,1,.25f);
        domain.bindGasRegistry(defs.gas());domain.bindThermal(thermal,fluids,350);
        var definition=thermal.finiteDevice(deviceId);int material=thermal.materialHandle(definition.thermalMaterialId());
        var wallMaterial=thermal.material(material);long mass=Mass.fromKilograms(definition.bodyMassKilograms()*.75);
        double energy=new ThermalStateFunction(thermal).internalEnergyAtTemperature(material,Mass.kilograms(mass),Map.of(),293.15);
        var wall=new FiniteThermalDeviceState("test:pipe",deviceId,deviceId,
                new FiniteThermalBodyState(0,thermal.fingerprint(),definition.thermalMaterialId(),mass,energy));
        var devices=new FiniteThermalDeviceInventory(thermal.fingerprint(),List.of(wall));
        // Two liquid microboxes contact the -Y pipe wall, total area 0.125 m².
        var contact=new FluidDomainContracts.ThermalPortRequest(1,1,1,2,.25,wallMaterial.conductivityWPerMeterK(),.125,0xc0,true);
        var port=new FluidStateAccess.DevicePort(wall.providerId(),wall.objectId(),contact);
        var key=new EnvironmentThermalState.Key(1,1,1,EnvironmentThermalState.Kind.SOLID,0);
        var node=new EnvironmentThermalState.Node(key,definition.thermalMaterialId(),mass,.75,GasInventory.EMPTY,energy,List.of());
        var world=EnvironmentThermalState.initialized("test:pipe_thermal",thermal.fingerprint(),List.of(node),List.of());
        var binding=new EnvironmentBinding(world.dimensionId(),List.of(new EnvironmentBinding.DeviceReference(wall.providerId(),wall.objectId(),key)));
        try(var backend=new VulkanFluidSolverBackend(device)) {
            backend.configureEnvironment(world,defs);
            try(var imported=backend.prepareState(null,domain.captureAuthoritativeState(),devices,List.of(port),binding)) {
                FluidStateAccess current=imported.commit();
                try {
                    for(int step=0;step<10;step++)try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(step,current,false,0));
                            var prepared=candidate.claimPreparedDeviceState()) {
                        var next=prepared.commit();current.close();current=next;
                    }
                    var after=backend.captureEnvironment(world.dimensionId());
                    if(!after.exactEnergy().equals(after.audit().expectedEnergy()))throw new AssertionError("Pipe wall energy audit differs");
                    return after.nodes().getFirst().internalEnergyJ()-energy;
                } finally { current.close(); }
            }
        }
    }
}
