package com.example.chemistry;

import java.util.List;
import java.util.Map;

/** Frozen built-in M7 strong-electrolyte definition set. */
public final class BuiltinChemicalDefinitions {
	public static final String AQUEOUS = "realwater:aqueous";
	public static final String H2O = "realwater:h2o";
	public static final String H_PLUS = "realwater:h_plus";
	public static final String OH_MINUS = "realwater:oh_minus";
	public static final String NA_PLUS = "realwater:na_plus";
	public static final String K_PLUS = "realwater:k_plus";
	public static final String CL_MINUS = "realwater:cl_minus";
	public static final String NO3_MINUS = "realwater:no3_minus";
	public static final String HCL = "realwater:hcl";
	public static final String NAOH = "realwater:naoh";
	public static final String NANO3 = "realwater:nano3";
	public static final String KCL = "realwater:kcl";
	public static final String NACL = "realwater:nacl";
	public static final String KNO3 = "realwater:kno3";
	public static final String OIL_CARRIER = "realwater:oil_carrier";
	public static final String MERCURY_CARRIER = "realwater:mercury_carrier";

	private static final List<ChemicalSpecies> SPECIES = List.of(
			species(H2O, Map.of("H", 2, "O", 1), 0, 144, 0xff4e9cff),
			species(H_PLUS, Map.of("H", 1), 1, 8, 0xffff5b52),
			species(OH_MINUS, Map.of("H", 1, "O", 1), -1, 136, 0xff596dff),
			species(NA_PLUS, Map.of("Na", 1), 1, 184, 0xffd4d8e0),
			species(K_PLUS, Map.of("K", 1), 1, 312, 0xffcfc0ff),
			species(CL_MINUS, Map.of("Cl", 1), -1, 280, 0xffb8e88a),
			species(NO3_MINUS, Map.of("N", 1, "O", 3), -1, 496, 0xffc8e7ff),
			species(HCL, Map.of("H", 1, "Cl", 1), 0, 288, 0xffff7a5e),
			species(NAOH, Map.of("Na", 1, "O", 1, "H", 1), 0, 320, 0xff808cff),
			species(NANO3, Map.of("Na", 1, "N", 1, "O", 3), 0, 680, 0xffdceaff),
			species(KCL, Map.of("K", 1, "Cl", 1), 0, 592, 0xffd7e3c8),
			species(NACL, Map.of("Na", 1, "Cl", 1), 0, 464, 0xffe4edf0),
			species(KNO3, Map.of("K", 1, "N", 1, "O", 3), 0, 808, 0xffe2ddff),
			species(OIL_CARRIER, Map.of("Oil", 1), 0, 6_400, 0xffc89a32),
			species(MERCURY_CARRIER, Map.of("Hg", 1), 0, 108_800, 0xffaeb6c2)
	);

	private static final List<ChemicalPhaseDefinition> PHASES = List.of(
			new ChemicalPhaseDefinition(AQUEOUS, "realwater:water",
					List.of(H2O, H_PLUS, OH_MINUS, NA_PLUS, K_PLUS, CL_MINUS, NO3_MINUS,
							HCL, NAOH, NANO3, KCL, NACL, KNO3),
					Map.of(H2O, ExactRational.parse("500/9")), true),
			new ChemicalPhaseDefinition("realwater:oil_chemistry", "realwater:oil",
					List.of(OIL_CARRIER), Map.of(OIL_CARRIER, ExactRational.ONE), false),
			new ChemicalPhaseDefinition("realwater:mercury_chemistry", "realwater:mercury",
					List.of(MERCURY_CARRIER), Map.of(MERCURY_CARRIER, ExactRational.ONE), false)
	);

	private static final List<ChemicalReaction> REACTIONS = List.of(
			dissociation("realwater:dissociate_hcl", HCL, Map.of(H_PLUS, 1, CL_MINUS, 1)),
			dissociation("realwater:dissociate_naoh", NAOH, Map.of(NA_PLUS, 1, OH_MINUS, 1)),
			dissociation("realwater:dissociate_nano3", NANO3, Map.of(NA_PLUS, 1, NO3_MINUS, 1)),
			dissociation("realwater:dissociate_kcl", KCL, Map.of(K_PLUS, 1, CL_MINUS, 1)),
			dissociation("realwater:dissociate_nacl", NACL, Map.of(NA_PLUS, 1, CL_MINUS, 1)),
			dissociation("realwater:dissociate_kno3", KNO3, Map.of(K_PLUS, 1, NO3_MINUS, 1)),
			new ChemicalReaction("realwater:neutralize", ChemicalReaction.Kind.INSTANTANEOUS, AQUEOUS,
					Map.of(H_PLUS, 1, OH_MINUS, 1), Map.of(H2O, 1), ExactRational.ZERO)
	);

	public static final ChemicalRegistry REGISTRY = new ChemicalRegistry(SPECIES, PHASES, REACTIONS);

	private BuiltinChemicalDefinitions() { }

	public static List<ChemicalSpecies> species() { return SPECIES; }
	public static List<ChemicalPhaseDefinition> phases() { return PHASES; }
	public static List<ChemicalReaction> reactions() { return REACTIONS; }

	private static ChemicalSpecies species(String id, Map<String, Integer> composition, int charge, long mass, int color) {
		return new ChemicalSpecies(id, composition, charge, ExactRational.of(mass), color);
	}

	private static ChemicalReaction dissociation(String id, String reactant, Map<String, Integer> products) {
		return new ChemicalReaction(id, ChemicalReaction.Kind.COMPLETE_DISSOCIATION, AQUEOUS,
				Map.of(reactant, 1), products, ExactRational.ZERO);
	}
}
