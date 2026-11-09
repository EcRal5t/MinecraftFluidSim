package mmm.fluid;

import mmm.chemistry.ChemicalDisplaySnapshot;
import mmm.granular.GranularDisplaySnapshot;
import mmm.thermal.ThermalAuditLedger;
import mmm.thermal.ThermalDisplaySnapshot;

/** Committed device inventory with bounded host observations and explicit checkpoint export. */
public interface ResidentFluidState extends FluidStateAccess {
    @Override ResidentFluidState retain();
    FixedGeometrySnapshot fixedGeometry();
    EntityBodyState entityBodies();
    PhysicalActivityGraph activityGraph();
    boolean hasPendingPhaseMotion(boolean[] activeCells);
    Observation observation();
    InventorySummary inventorySummary();
    Display display();
    ThermalAuditLedger thermalAudit();
    FluidDomainContracts.BodyFluidSample sampleBody(double minX,double minY,double minZ,double maxX,double maxY,double maxZ);
    FluidCellInventoryView observeCellInventory(int x,int y,int z);
    CellCarrierInventoryView observeCellCarriers(int x,int y,int z);
    double observeLiquidFlow(int[] faces);
    double observeGasFlow(int[] faces);
    boolean hasGranularOccupancy(int x,int y,int z);
    java.util.List<FluidDomainContracts.WaterContact> waterContacts(java.util.List<FluidDomainContracts.WaterContactFace> faces);

    record Observation(double liquidVolume, int liquidRows, float maximumSpeed,
                       boolean gasPressureFlow, boolean granularQuiet, boolean pendingPhaseMotion,
                       boolean thermalAuditClosed) { }
    record InventorySummary(double liquidVolumeCubicMetres,long liquidMassUnits,long chemicalTotalQuanta,
                            long granularSolidVolumeSvq,long granularMassUnits,int granularParcelCount,int granularDepositCount,
                            long coherentSolidVolumeSvq,long coherentMassUnits,int coherentGroupCount,
                            long gasStandardVolumeNanos,long gasMassUnits,int gasNodeCount,int finiteDeviceCount,
                            boolean thermalBound,double minimumTemperatureK,double maximumTemperatureK,
                            double totalInternalEnergyJoules,double deviceInternalEnergyJoules) { }
    record Display(FluidMaterialRenderSnapshot material, ChemicalDisplaySnapshot chemical,
                   GranularDisplaySnapshot granular, ThermalDisplaySnapshot thermal) { }
}
