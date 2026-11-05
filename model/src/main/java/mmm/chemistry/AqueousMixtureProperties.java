package mmm.chemistry;

import java.util.Map;

/** Water, ethanol and dilute-salt mixture properties at ambient pressure. */
public final class AqueousMixtureProperties {
    // Dilute density measurements: https://pmc.ncbi.nlm.nih.gov/articles/PMC12334640/
    public static final double WATER_EFFECTIVE_VOLUME_M3_PER_MOL = 18e-6;
    public static final double SALT_EFFECTIVE_VOLUME_M3_PER_MOL = 16.4e-6;
    public static final double ETHANOL_EFFECTIVE_VOLUME_M3_PER_MOL = 58.3e-6;
    public static final double MAX_SALT_MOLALITY = .03;
    public static final double MIN_VOLUME_TEMPERATURE_K = 293.15;
    public static final double MAX_VOLUME_TEMPERATURE_K = 313.15;
    public static final double REFERENCE_TEMPERATURE_K = 298.15;
    // Jones-Dole coefficients at 25 C, concentration in mol/L:
    // https://www.sciencedirect.com/science/article/pii/0021979779901024
    public static final double VISCOSITY_A = .0062;
    public static final double VISCOSITY_B = .0802;

    private AqueousMixtureProperties() { }

    /** Equal ion shares are an effective-volume convention for neutral paired transfers. */
    public static double effectiveVolumePerMole(String species) {
        return switch (species) {
            case BuiltinChemicalDefinitions.H2O -> WATER_EFFECTIVE_VOLUME_M3_PER_MOL;
            case BuiltinChemicalDefinitions.NA_PLUS, BuiltinChemicalDefinitions.CL_MINUS -> SALT_EFFECTIVE_VOLUME_M3_PER_MOL / 2;
            case BuiltinChemicalDefinitions.NACL -> SALT_EFFECTIVE_VOLUME_M3_PER_MOL;
            case BuiltinChemicalDefinitions.ETHANOL -> ETHANOL_EFFECTIVE_VOLUME_M3_PER_MOL;
            default -> Double.NaN;
        };
    }

    /** Caloric and transport properties share the same conserved ethanol mass fraction. */
    public static mmm.thermal.ThermalMaterialDefinition thermalMaterial(
            mmm.thermal.ThermalMaterialDefinition material, double massKg, Map<String, Long> speciesQuanta) {
        if (material.carrierKind() != mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID
                || !material.carrierId().equals("mmm:water") || speciesQuanta == null) return material;
        long ethanol = speciesQuanta.getOrDefault(BuiltinChemicalDefinitions.ETHANOL, 0L);
        if (ethanol <= 0) return material;
        double fraction = Math.min(1.0, ethanol * 1e-6 * .046 / massKg);
        var segments = material.caloricSegments().stream().map(segment ->
                new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(segment.minimumTemperatureK(), segment.maximumTemperatureK(),
                        segment.specificHeatJPerKgK() * (1 - fraction) + BuiltinChemicalDefinitions.ETHANOL_HEAT_CAPACITY_J_KG_K * fraction)).toList();
        return new mmm.thermal.ThermalMaterialDefinition(material.id(), material.carrierKind(), material.carrierId(),
                material.referenceTemperatureK(), material.referenceSpecificEnergyJPerKg(),
                material.conductivityWPerMeterK() * (1 - fraction) + BuiltinChemicalDefinitions.ETHANOL_CONDUCTIVITY_W_M_K * fraction, segments);
    }

    /**
     * Evaluates actual mass/volume, mass-weighted water–ethanol properties and the dilute NaCl correction.
     * The supplied pure-carrier heat capacity and transport properties describe the water reference.
     * Species amounts are in moles. Unknown species or unpaired ions are reported outside the property range.
     */
    public static State evaluate(Map<String, Double> amounts, double massKg, double volumeM3,
                                 double temperatureK, double pressurePa, double heatCapacityJPerK,
                                 double pureViscosityPaS, double pureConductivityWPerMK) {
        if (amounts == null || !(massKg > 0 && volumeM3 > 0 && temperatureK > 0 && pressurePa > 0 && heatCapacityJPerK > 0
                && pureViscosityPaS > 0 && pureConductivityWPerMK >= 0)
                || !Double.isFinite(massKg + volumeM3 + temperatureK + pressurePa + heatCapacityJPerK + pureViscosityPaS + pureConductivityWPerMK))
            throw new IllegalArgumentException("Invalid aqueous state");
        double water = 0, ethanol = 0, sodium = 0, chloride = 0, undissociated = 0;
        boolean known = true;
        for (var entry : amounts.entrySet()) {
            double n = entry.getValue();
            if (!(Double.isFinite(n) && n >= 0)) throw new IllegalArgumentException("Invalid species amount");
            if (n == 0) continue;
            switch (entry.getKey()) {
                case BuiltinChemicalDefinitions.H2O -> water += n;
                case BuiltinChemicalDefinitions.ETHANOL -> ethanol += n;
                case BuiltinChemicalDefinitions.NA_PLUS -> sodium += n;
                case BuiltinChemicalDefinitions.CL_MINUS -> chloride += n;
                case BuiltinChemicalDefinitions.NACL -> undissociated += n;
                default -> known = false;
            }
        }
        double salt = Math.min(sodium, chloride) + undissociated;
        boolean paired = sodium == chloride;
        double molality = water > 0 ? salt / (water * .018) : Double.POSITIVE_INFINITY;
        double concentrationMolPerLiter = salt / volumeM3 / 1000;
        boolean supportedComposition = known && paired && (water > 0 || ethanol > 0 && salt == 0)
                && (salt == 0 || molality <= MAX_SALT_MOLALITY) && pressurePa >= 90_000 && pressurePa <= 110_000;
        boolean volumeSupported = supportedComposition && temperatureK >= MIN_VOLUME_TEMPERATURE_K
                && temperatureK <= MAX_VOLUME_TEMPERATURE_K;
        boolean viscositySupported = supportedComposition && (salt == 0 || Math.abs(temperatureK - REFERENCE_TEMPERATURE_K) <= .01);
        double ethanolMass = Math.min(massKg, ethanol * .046);
        double fraction = ethanolMass / massKg;
        double viscosity = (pureViscosityPaS * (1 - fraction) + BuiltinChemicalDefinitions.ETHANOL_VISCOSITY_PA_S * fraction)
                * (1 + VISCOSITY_A * Math.sqrt(concentrationMolPerLiter) + VISCOSITY_B * concentrationMolPerLiter);
        double capacity = heatCapacityJPerK * (1 - fraction) + ethanolMass * BuiltinChemicalDefinitions.ETHANOL_HEAT_CAPACITY_J_KG_K;
        double conductivity = pureConductivityWPerMK * (1 - fraction) + BuiltinChemicalDefinitions.ETHANOL_CONDUCTIVITY_W_M_K * fraction;
        return new State(massKg / volumeM3, capacity, viscosity, conductivity,
                molality, volumeSupported, viscositySupported);
    }

    /** The support flags describe the declared composition and temperature range. */
    public record State(double densityKgPerM3, double heatCapacityJPerK, double viscosityPaS,
                        double conductivityWPerMK, double saltMolality,
                        boolean effectiveVolumesSupported, boolean viscositySupported) { }
}
