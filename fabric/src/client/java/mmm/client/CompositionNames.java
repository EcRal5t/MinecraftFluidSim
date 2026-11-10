package mmm.client;

import net.minecraft.network.chat.Component;

/** Short display names; unknown data-pack identifiers remain visible. */
final class CompositionNames {
    private CompositionNames() { }
    static Component label(String id) {
        String name = switch (id) {
            case "mmm:h2o" -> "H2O";
            case "mmm:hcl" -> "HCl";
            case "mmm:naoh" -> "NaOH";
            case "mmm:nacl" -> "NaCl";
            case "mmm:co2" -> "CO2";
            case "mmm:o2" -> "O2";
            case "mmm:n2" -> "N2";
            case "mmm:h_plus" -> "H+";
            case "mmm:oh_minus" -> "OH−";
            case "mmm:na_plus" -> "Na+";
            case "mmm:cl_minus" -> "Cl−";
            case "mmm:hco3_minus" -> "HCO3−";
            case "mmm:co3_2minus" -> "CO3²−";
            default -> id;
        };
        return Component.translatableWithFallback("substance." + id.replace(':', '.'), name);
    }
}
