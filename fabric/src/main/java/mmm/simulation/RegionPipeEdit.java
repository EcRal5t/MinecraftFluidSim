package mmm.simulation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import mmm.fluid.FluidStateAccess;
import mmm.fluid.HostFluidState;
import mmm.fluid.PreparedEnvironmentState;
import mmm.fluid.PreparedFluidState;

/** Private region imports adopted together after all device and world candidates succeed. */
final class RegionPipeEdit implements AutoCloseable {
    private final FluidRegion region;
    private final long generation;
    private final HostFluidState candidate;
    private final PreparedFluidState prepared;
    private FluidStateAccess committed;
    RegionPipeEdit(FluidRegion region,HostFluidState candidate) {
        this.region=region;this.generation=region.solverGeneration();this.candidate=Objects.requireNonNull(candidate);
        if(!region.solverLoaded()||!candidate.authoritativeLayout().equals(region.solverLayout())
                || !region.deviceResident()&&candidate==region.editableHost())throw new IllegalStateException("Invalid pipe region candidate");
        prepared=region.preparePipeImport(candidate);
    }
    boolean isCurrent(){return region.solverLoaded() && region.solverGeneration()==generation;}
    static void commit(List<RegionPipeEdit> edits,PreparedEnvironmentState environment) {
        var unique=new HashSet<FluidRegion>();var temperatures=new ArrayList<double[]>(edits.size());
        for(var edit:edits) {
            if(!unique.add(edit.region)||!edit.isCurrent())throw new IllegalStateException("Pipe region candidate is stale");
            Math.addExact(edit.generation,1);temperatures.add(edit.candidate.thermalBoundaryTemperatures());
        }
        for(var edit:edits)if(edit.prepared!=null)edit.committed=edit.prepared.commit();
        if(environment!=null) {
            if(!environment.isCurrent())throw new IllegalStateException("Pipe environment candidate is stale");
            environment.commit();
        }
        var previous=new ArrayList<FluidStateAccess>();
        for(int i=0;i<edits.size();i++) {
            var edit=edits.get(i);
            var old=edit.region.adoptPipeImport(edit.candidate,edit.committed,edit.prepared!=null,edit.generation+1,temperatures.get(i));
            edit.committed=null;if(old!=null)previous.add(old);
        }
        for(var state:previous)try {state.close();}
        catch(RuntimeException failure){mmm.MmmMod.LOGGER.warn("Failed to release previous pipe region state",failure);}
    }
    static void publishObservations(List<RegionPipeEdit> edits){for(var edit:edits)edit.region.publishCommittedObservation();}
    @Override public void close(){if(committed!=null){committed.close();committed=null;}if(prepared!=null)prepared.close();}
}
