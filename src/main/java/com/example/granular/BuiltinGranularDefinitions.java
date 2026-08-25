package com.example.granular;

import com.example.chemistry.BuiltinChemicalDefinitions;
import com.example.chemistry.ExactRational;

import java.util.List;
import java.util.Map;

/** Deterministic built-in definitions used by the datapack and validation fixtures. */
public final class BuiltinGranularDefinitions {
	public static final String FINE_SAND = "realwater:fine_sand";
	public static final String LIGHT_BEADS = "realwater:light_beads";
	public static final String PRECIPITATE = "realwater:precipitate";
	public static final String SODIUM_CHLORIDE_PRECIPITATION = "realwater:sodium_chloride";

	private BuiltinGranularDefinitions() { }

	public static GranularRegistry createRegistry() {
		return new GranularRegistry(List.of(
				material(FINE_SAND, 2_600.0, 0.0005, 380_000, 32.0, 0xffc2a065, false),
				material(LIGHT_BEADS, 500.0, 0.0001, 400_000, 20.0, 0xffe8e8d8, false),
				material(PRECIPITATE, 2_799.97507960832, 0.00001, 500_000, 25.0, 0xffd8d4c8, true)
		), List.of(
				new GranularCarrier("realwater:water", 1.002e-3, 100_000.0),
				new GranularCarrier("realwater:oil", 6.5e-2, 10_000.0),
				new GranularCarrier("realwater:mercury", 1.526e-3, 100_000.0)
		), List.of(new SolidLiquidReaction(SODIUM_CHLORIDE_PRECIPITATION,
				BuiltinChemicalDefinitions.AQUEOUS, Map.of(
						BuiltinChemicalDefinitions.NA_PLUS, 64,
						BuiltinChemicalDefinitions.CL_MINUS, 64), PRECIPITATE, Map.of(
						BuiltinChemicalDefinitions.NA_PLUS, 64,
						BuiltinChemicalDefinitions.CL_MINUS, 64), ExactRational.of(1_000_000L),
				281L, 400L, 4_096L, true)), BuiltinChemicalDefinitions.REGISTRY);
	}

	private static ParticulateMaterial material(
			String id, double density, double diameter, int porosityQ, double reposeDegrees, int argb, boolean reactive
	) {
		return new ParticulateMaterial(id, density, diameter, porosityQ, Math.toRadians(reposeDegrees),
				0.6, 0.45, 0.1, 0.05, SolidVolume.SVQ_PER_CELL / 4L,
				GranularLimits.MAX_PARCEL_VOLUME_SVQ, argb, id, reactive);
	}
}
