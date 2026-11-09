package mmm.chemistry;

import java.util.Map;

/** Water, ethanol and dilute-salt mixture properties at ambient pressure. */
public final class AqueousMixtureProperties {
    private AqueousMixtureProperties() { }

    /** Caloric and transport properties share the same conserved ethanol mass fraction. */
    public static mmm.thermal.ThermalMaterialDefinition thermalMaterial(
            mmm.thermal.ThermalMaterialDefinition material, double massKg, Map<String, Long> speciesQuanta, ChemicalRegistry chemistry) {
        if (material.carrierKind() != mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID
                || !material.carrierId().equals("mmm:water") || speciesQuanta == null) return material;
        long ethanol = speciesQuanta.getOrDefault(BuiltinChemicalDefinitions.ETHANOL, 0L);
        if (ethanol <= 0) return material;
        if (chemistry == null) throw new IllegalArgumentException("Chemical registry is required for liquid mixture properties");
        var definition=chemistry.species(chemistry.speciesHandle(BuiltinChemicalDefinitions.ETHANOL));
        var properties=definition.liquidProperties();
        if (properties == null) throw new IllegalArgumentException("Liquid properties are missing for " + definition.id());
        double fraction = Math.min(1.0, ethanol * 1e-6 * definition.molarMassKgPerMol().toDouble() / massKg);
        var segments = material.caloricSegments().stream().map(segment ->
                new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(segment.minimumTemperatureK(), segment.maximumTemperatureK(),
                        segment.specificHeatJPerKgK() * (1 - fraction) + properties.heatCapacityJPerKgK() * fraction)).toList();
        return new mmm.thermal.ThermalMaterialDefinition(material.id(), material.carrierKind(), material.carrierId(),
                material.referenceTemperatureK(), material.referenceSpecificEnergyJPerKg(),
                material.conductivityWPerMeterK() * (1 - fraction) + properties.conductivityWPerMK() * fraction, segments);
    }

}
