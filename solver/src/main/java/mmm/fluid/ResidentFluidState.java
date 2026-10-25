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
    Observation observation();
    InventorySummary inventorySummary();
    Display display();
    ThermalAuditLedger thermalAudit();
    FluidDomain.BodyFluidSample sampleBody(double minX,double minY,double minZ,double maxX,double maxY,double maxZ);
    FluidCellInventoryView observeCellInventory(int x,int y,int z);
    java.util.List<FluidDomain.WaterContact> waterContacts(java.util.List<FluidDomain.WaterContactFace> faces);

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
