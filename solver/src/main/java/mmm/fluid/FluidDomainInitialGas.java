package mmm.fluid;

import mmm.fluid.air.SpatialGasState;
import mmm.thermal.ThermalOwnerSnapshot;
import mmm.units.SignedDyadic;

/** Replaces the private default gas of a new domain with transferred environment inventory. */
public final class FluidDomainInitialGas {
    private FluidDomainInitialGas() { }
    public static FluidDomainState replace(FluidDomainState source, SpatialGasState gas, SignedDyadic roundoff) {
        var old=source.spatialGas(); var thermal=source.thermalOwner();
        if(old==null || thermal==null || gas==null || !source.grid().equals(gas.grid()))
            throw new IllegalArgumentException("Initial gas handoff requires matching thermal grids");
        if(!old.escapedSink().isEmpty() || !old.phaseSource().isEmpty() || !old.phaseSink().isEmpty())
            throw new IllegalArgumentException("Initial gas has already exchanged material");
        var oldEnergy=SignedDyadic.sum(old.internalEnergy()); var newEnergy=SignedDyadic.sum(gas.internalEnergy());
        var audit=thermal.audit().withExternalSourceEnergy(oldEnergy.negate())
                .withExternalSourceEnergy(newEnergy.subtract(roundoff)).withRoundoff(roundoff);
        var nextThermal=new ThermalOwnerSnapshot(thermal.registryFingerprint(),thermal.insertionTemperatureK(),
                thermal.copyActiveParcelEnergy(),thermal.copyDepositEnergy(),new double[0],thermal.coherentGroups(),audit);
        return new FluidDomainState(source.grid(),source.config(),source.definitions(),source.sealedDomainBoundary(),
                source.liquid(),source.phaseOccupancy(),source.momentum(),source.fixedGeometry(),source.entityBodyState(),
                source.materialPalette(),source.chemicalRegistry(),source.chemicalSource(),source.chemicalSink(),
                source.copyChemicalDirtyCells(),source.chemicalReactionCursor(),source.chemicalMassTransferPending(),
                source.granularRegistry(),source.granular(),source.granularDynamics(),source.precipitationEnabled(),
                source.gasRegistry(),FluidDomainState.GasContinuation.spatial(gas),source.thermalRegistry(),nextThermal,
                source.maximumThermalTransportSubsteps(),source.pendingPhaseMotion(),source.copyThermalBoundaryTemperaturesK(),
                source.exportCondensedPhasePressure(),source.materialDefinitions(),source.activityState());
    }
}
