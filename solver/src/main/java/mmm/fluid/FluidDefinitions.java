package mmm.fluid;

import java.util.Objects;
import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.air.GasRegistry;
import mmm.granular.GranularRegistry;
import mmm.thermal.ThermalRegistry;

/** Immutable world material and physical definitions, independent of active inventory features. */
public record FluidDefinitions(MaterialRegistry material, ChemicalRegistry chemistry, GasRegistry gas,
                               GranularRegistry granular, ThermalRegistry thermal) {
    public FluidDefinitions {
        Objects.requireNonNull(material, "material definitions");
        Objects.requireNonNull(chemistry, "chemical definitions");
        Objects.requireNonNull(gas, "gas definitions");
        Objects.requireNonNull(granular, "granular definitions");
        Objects.requireNonNull(thermal, "thermal definitions");
        if (!thermal.chemicalRegistryFingerprint().equals(chemistry.fingerprint())
                || !thermal.granularRegistryFingerprint().equals(granular.fingerprint())
                || !thermal.gasRegistryFingerprint().equals(gas.fingerprint())
                || granular.hasReactiveRules() && !granular.chemicalRegistryFingerprint().equals(chemistry.fingerprint()))
            throw new IllegalArgumentException("World physical definition fingerprints differ");
        thermal = thermal.withChemistry(chemistry);
    }
    public MaterialRegistry material(MaterialRegistry value) {
        if(value==null || !value.fingerprint().equals(material.fingerprint()))throw new IllegalArgumentException("Material definitions differ from world");
        return material;
    }
    public ChemicalRegistry chemistry(ChemicalRegistry value) {
        if(value==null || !value.fingerprint().equals(chemistry.fingerprint()))throw new IllegalArgumentException("Chemical definitions differ from world");
        return chemistry;
    }
    public GasRegistry gas(GasRegistry value) {
        if(value==null || !value.fingerprint().equals(gas.fingerprint()))throw new IllegalArgumentException("Gas definitions differ from world");
        return gas;
    }
    public GranularRegistry granular(GranularRegistry value) {
        if(value==null || !value.fingerprint().equals(granular.fingerprint()))throw new IllegalArgumentException("Granular definitions differ from world");
        return granular;
    }
    public ThermalRegistry thermal(ThermalRegistry value) {
        if(value==null || !value.fingerprint().equals(thermal.fingerprint()))throw new IllegalArgumentException("Thermal definitions differ from world");
        return thermal;
    }
}
