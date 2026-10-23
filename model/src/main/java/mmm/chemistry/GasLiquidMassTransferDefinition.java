package mmm.chemistry;

/** Dilute neutral-solute Henry equilibrium and effective two-film velocities, with zero partial molar volume. */
public record GasLiquidMassTransferDefinition(
		String id,
		String speciesId,
		String liquidPhaseId,
		String gasComponentId,
		double referenceTemperatureK,
		double henrySolubilityMolPerM3Pa,
		double temperatureSlopeK,
		double liquidFilmVelocityMPerS,
		double gasFilmVelocityMPerS
) {
	public GasLiquidMassTransferDefinition {
		ChemicalIds.requireId(id, "gas/liquid mass transfer id");
		ChemicalIds.requireId(speciesId, "transferred species id");
		ChemicalIds.requireId(liquidPhaseId, "receiving liquid phase id");
		ChemicalIds.requireId(gasComponentId, "gas component id");
		requirePositive(referenceTemperatureK, "Reference temperature");
		requirePositive(henrySolubilityMolPerM3Pa, "Henry solubility");
		requirePositive(liquidFilmVelocityMPerS, "Liquid film velocity");
		requirePositive(gasFilmVelocityMPerS, "Gas film velocity");
		if (!Double.isFinite(temperatureSlopeK)) throw new IllegalArgumentException("Henry temperature slope must be finite");
		if (temperatureSlopeK == 0) temperatureSlopeK = 0;
	}

	/** c_eq = H(T) p_species, with concentration in mol/m³ and absolute partial pressure in Pa. */
	public double henrySolubilityAt(double temperatureK) {
		requirePositive(temperatureK, "Interface temperature");
		double result = henrySolubilityMolPerM3Pa
				* Math.exp(temperatureSlopeK * (1 / temperatureK - 1 / referenceTemperatureK));
		requirePositive(result, "Temperature-dependent Henry solubility");
		return result;
	}

	/** Converts gas-film concentration resistance to the liquid concentration convention. */
	public double overallLiquidFilmVelocityAt(double temperatureK, double molarGasConstantJPerMolK) {
		requirePositive(molarGasConstantJPerMolK, "Molar gas constant");
		return 1 / (1 / liquidFilmVelocityMPerS
				+ henrySolubilityAt(temperatureK) * molarGasConstantJPerMolK * temperatureK / gasFilmVelocityMPerS);
	}

	private static void requirePositive(double value, String name) {
		if (!(Double.isFinite(value) && value > 0)) throw new IllegalArgumentException(name + " must be finite and positive");
	}
}
