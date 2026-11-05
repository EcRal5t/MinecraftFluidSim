package mmm.chemistry;

import java.util.List;
import java.util.Map;

/** Built-in species, aqueous reactions and gas-liquid transfer definitions. */
public final class BuiltinChemicalDefinitions {
	public static final String AQUEOUS = "mmm:aqueous";
	public static final String N2 = "mmm:n2", O2 = "mmm:o2";
	public static final String H2O = "mmm:h2o", ETHANOL = "mmm:ethanol";
	/** Pure ethanol reference properties at 293.15 K; its inventory uses the aqueous carrier. */
	public static final double ETHANOL_REFERENCE_DENSITY_KG_M3 = 789.0;
	public static final double ETHANOL_HEAT_CAPACITY_J_KG_K = 2430.0;
	public static final double ETHANOL_CONDUCTIVITY_W_M_K = 0.171;
	public static final double ETHANOL_VISCOSITY_PA_S = 0.0012;
	public static final String H_PLUS = "mmm:h_plus";
	public static final String OH_MINUS = "mmm:oh_minus";
	public static final String NA_PLUS = "mmm:na_plus";
	public static final String K_PLUS = "mmm:k_plus";
	public static final String CL_MINUS = "mmm:cl_minus";
	public static final String NO3_MINUS = "mmm:no3_minus";
	public static final String HCL = "mmm:hcl";
	public static final String NAOH = "mmm:naoh";
	public static final String NANO3 = "mmm:nano3";
	public static final String KCL = "mmm:kcl";
	public static final String NACL = "mmm:nacl";
	public static final String KNO3 = "mmm:kno3";
	public static final String CO2 = "mmm:co2";
	public static final String HCO3_MINUS = "mmm:hco3_minus";
	public static final String HG = "mmm:hg";

	private static final List<ChemicalSpecies> SPECIES = List.of(
			species(N2, Map.of("N", 2), 0, 28, 0xffb9d3ed),
			species(O2, Map.of("O", 2), 0, 32, 0xffb9e2ed),
			species(H2O, Map.of("H", 2, "O", 1), 0, 18, 0xff4e9cff),
			species(ETHANOL, Map.of("C", 2, "H", 6, "O", 1), 0, 46, 0xffdedbdb),
			species(H_PLUS, Map.of("H", 1), 1, 1, 0xffff5b52),
			species(OH_MINUS, Map.of("H", 1, "O", 1), -1, 17, 0xff596dff),
			species(NA_PLUS, Map.of("Na", 1), 1, 23, 0xffd4d8e0),
			species(K_PLUS, Map.of("K", 1), 1, 39, 0xffcfc0ff),
			species(CL_MINUS, Map.of("Cl", 1), -1, 35, 0xffb8e88a),
			species(NO3_MINUS, Map.of("N", 1, "O", 3), -1, 62, 0xffc8e7ff),
			species(HCL, Map.of("H", 1, "Cl", 1), 0, 36, 0xffff7a5e),
			species(NAOH, Map.of("Na", 1, "O", 1, "H", 1), 0, 40, 0xff808cff),
			species(NANO3, Map.of("Na", 1, "N", 1, "O", 3), 0, 85, 0xffdceaff),
			species(KCL, Map.of("K", 1, "Cl", 1), 0, 74, 0xffd7e3c8),
			species(NACL, Map.of("Na", 1, "Cl", 1), 0, 58, 0xffe4edf0),
			species(KNO3, Map.of("K", 1, "N", 1, "O", 3), 0, 101, 0xffe2ddff),
			species(CO2, Map.of("C", 1, "O", 2), 0, 44, 0xffc8c8c8),
			species(HCO3_MINUS, Map.of("H", 1, "C", 1, "O", 3), -1, 61, 0xffd6e6ed),
			species(HG, Map.of("Hg", 1), 0, 200, 0xffaeb6c2)
	);

	private static final List<ChemicalPhaseDefinition> PHASES = List.of(
			new ChemicalPhaseDefinition(AQUEOUS, "mmm:water",
					List.of(H2O, H_PLUS, OH_MINUS, NA_PLUS, K_PLUS, CL_MINUS, NO3_MINUS,
							HCL, NAOH, NANO3, KCL, NACL, KNO3, CO2, HCO3_MINUS, ETHANOL),
					Map.of(H2O, ExactRational.parse("500000/9"))),
			new ChemicalPhaseDefinition("mmm:oil_chemistry", "mmm:oil",
					List.of(), Map.of()),
			new ChemicalPhaseDefinition("mmm:mercury_chemistry", "mmm:mercury",
					List.of(HG), Map.of(HG, ExactRational.of(68_000)))
	);

	private static final List<ChemicalReaction> REACTIONS = List.of(
			dissociation("mmm:dissociate_hcl", HCL, Map.of(H_PLUS, 1, CL_MINUS, 1)),
			dissociation("mmm:dissociate_naoh", NAOH, Map.of(NA_PLUS, 1, OH_MINUS, 1)),
			dissociation("mmm:dissociate_nano3", NANO3, Map.of(NA_PLUS, 1, NO3_MINUS, 1)),
			dissociation("mmm:dissociate_kcl", KCL, Map.of(K_PLUS, 1, CL_MINUS, 1)),
			dissociation("mmm:dissociate_nacl", NACL, Map.of(NA_PLUS, 1, CL_MINUS, 1)),
			dissociation("mmm:dissociate_kno3", KNO3, Map.of(K_PLUS, 1, NO3_MINUS, 1)),
			new ChemicalReaction("mmm:neutralize", ChemicalReaction.Kind.INSTANTANEOUS, AQUEOUS,
					Map.of(H_PLUS, 1, OH_MINUS, 1), Map.of(H2O, 1), ExactRational.ZERO),
			// Dilute 25 °C coefficients: Johnson, Limnol. Oceanogr. 27 (1982), doi:10.4319/lo.1982.27.5.0849.
			// Hydration is pseudo-first-order in water at 55,555.6 mol/m³; all rate inputs use mol/m³.
			new ChemicalReaction("mmm:co2_hydration", ChemicalReaction.Kind.FINITE_RATE, AQUEOUS,
					Map.of(CO2, 1, H2O, 1), Map.of(H_PLUS, 1, HCO3_MINUS, 1), ExactRational.ZERO,
					new ChemicalReaction.Kinetics(6.66e-7, 76.0, 61_400, 52_300, 298.15)),
			new ChemicalReaction("mmm:co2_hydroxide", ChemicalReaction.Kind.FINITE_RATE, AQUEOUS,
					Map.of(CO2, 1, OH_MINUS, 1), Map.of(HCO3_MINUS, 1), ExactRational.ZERO,
					new ChemicalReaction.Kinetics(7.1, 1.8e-4, 55_000, 103_200, 298.15))
	);

	// Henry Hcp and temperature slope: https://henrys-law.org/henry/casrn/124-38-9
	// The film velocities describe the chosen slow effective films of a dilute, gently moving water surface.
	private static final List<GasLiquidMassTransferDefinition> GAS_LIQUID_MASS_TRANSFERS = List.of(
			new GasLiquidMassTransferDefinition("mmm:co2_water", CO2, AQUEOUS, "mmm:co2",
					298.15, 3.4e-4, 2400, 1e-5, 1e-2,273.15,373.15));
	public static final ChemicalRegistry REGISTRY = new ChemicalRegistry(SPECIES, PHASES, REACTIONS, GAS_LIQUID_MASS_TRANSFERS);

	private BuiltinChemicalDefinitions() { }

	public static List<ChemicalSpecies> species() { return SPECIES; }
	public static List<ChemicalPhaseDefinition> phases() { return PHASES; }
	public static List<ChemicalReaction> reactions() { return REACTIONS; }
	public static List<GasLiquidMassTransferDefinition> gasLiquidMassTransfers() { return GAS_LIQUID_MASS_TRANSFERS; }

	private static ChemicalSpecies species(String id, Map<String, Integer> composition, int charge, long molarMassGramsPerMol, int color) {
		Double diffusion = switch(id) { case H2O -> 2.3e-9; case NA_PLUS -> 1.33e-9; case CL_MINUS -> 2.03e-9;
			case NACL -> 1.6e-9; case ETHANOL -> 1.1e-9; default -> null; };
		Double volume = switch(id) { case H2O -> 1.8e-5; case NA_PLUS, CL_MINUS -> 8.2e-6;
			case NACL -> 1.64e-5; case ETHANOL -> 5.83e-5; default -> null; };
		return new ChemicalSpecies(id, composition, charge, ExactRational.of(molarMassGramsPerMol).divide(1000), color, diffusion, volume);
	}

	private static ChemicalReaction dissociation(String id, String reactant, Map<String, Integer> products) {
		return new ChemicalReaction(id, ChemicalReaction.Kind.COMPLETE_DISSOCIATION, AQUEOUS,
				Map.of(reactant, 1), products, ExactRational.ZERO);
	}
}
