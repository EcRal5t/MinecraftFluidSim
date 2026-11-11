package mmm.client;

import net.minecraft.network.chat.Component;

/** Short display names; unknown data-pack identifiers remain visible. */
final class CompositionNames {
    private CompositionNames() { }
    static IndustrialUi.Icon phaseIcon(mmm.controller.ControllerSummary.Phase phase, boolean ion) {
        if (ion) return null;
        return switch (phase) {
            case LIQUID -> IndustrialUi.Icon.LIQUID;
            case GAS -> IndustrialUi.Icon.GAS;
            case GRANULAR, COHERENT -> IndustrialUi.Icon.SOLID;
        };
    }
    static Component label(String id) {
        String name = switch (id) {
            case "mmm:h2o", "mmm:water", "mmm:water_vapor", "mmm:steam", "mmm:ice" -> "H2O";
            case "mmm:ethanol", "mmm:ethanol_vapor" -> "C2H5OH";
            case "mmm:hcl" -> "HCl";
            case "mmm:naoh" -> "NaOH";
            case "mmm:nacl" -> "NaCl";
            case "mmm:kcl" -> "KCl";
            case "mmm:kno3" -> "KNO3";
            case "mmm:nano3" -> "NaNO3";
            case "mmm:hg", "mmm:mercury" -> "Hg";
            case "mmm:co2" -> "CO2";
            case "mmm:o2", "mmm:oxygen" -> "O2";
            case "mmm:n2", "mmm:nitrogen" -> "N2";
            case "mmm:h_plus" -> "H+";
            case "mmm:oh_minus" -> "OH−";
            case "mmm:na_plus" -> "Na+";
            case "mmm:k_plus" -> "K+";
            case "mmm:no3_minus" -> "NO3−";
            case "mmm:cl_minus" -> "Cl−";
            case "mmm:hco3_minus" -> "HCO3−";
            case "mmm:co3_2minus" -> "CO3²−";
            default -> id;
        };
        String canonical = switch (id) {
            case "mmm:water", "mmm:water_vapor", "mmm:steam", "mmm:ice" -> "mmm:h2o";
            case "mmm:mercury" -> "mmm:hg";
            case "mmm:oxygen" -> "mmm:o2";
            case "mmm:ethanol_vapor" -> "mmm:ethanol";
            case "mmm:nitrogen" -> "mmm:n2";
            default -> id;
        };
        return Component.translatableWithFallback("substance." + canonical.replace(':', '.'), name);
    }
}
