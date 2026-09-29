package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularCarrier;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;
import cc.ecisr.mmm.thermal.ThermalDomainState;

/**
 * Per-cell liquid carrier properties derived once per carrier/thermal revision.
 *
 * <p>Every consumer of liquid density, temperature and dynamic viscosity — active granular drag,
 * Darcy pore damping and the bed-shear closure — reads this one workspace, so a cell is observed
 * once per revision instead of once per parcel support point or resistance face.
 *
 * <p>The arrays are fixed-capacity scratch. Nothing here is authoritative and nothing here is
 * persisted: the workspace is rebuilt from the marker grid, the world material definitions and the
 * authoritative thermal carriers, and a restored or candidate domain rebuilds it from its own state.
 */
final class LiquidCarrierProperties {
	/** Cell key of the derived arrays; rebuilt whenever any component advances. */
	record Revision(long carrierMutation, ThermalDomainState.Revision thermal) { }

	private final int cellCount;
	private final boolean[] liquid;
	private final int[] materialHandle;
	private final double[] density;
	private final double[] temperatureK;
	private final double[] viscosityPaPerS;
	private final double[] maximumReynolds;
	private Revision built;
	private int extrapolatedCells;
	private String sampleMaterialId;
	private double sampleTemperatureK;
	private double sampleMinimumTemperatureK;
	private double sampleMaximumTemperatureK;

	LiquidCarrierProperties(int cellCount) {
		this.cellCount = cellCount;
		this.liquid = new boolean[cellCount];
		this.materialHandle = new int[cellCount];
		this.density = new double[cellCount];
		this.temperatureK = new double[cellCount];
		this.viscosityPaPerS = new double[cellCount];
		this.maximumReynolds = new double[cellCount];
	}

	boolean hasLiquid(int cell) { return liquid[cell]; }
	int materialHandle(int cell) { return materialHandle[cell]; }
	double density(int cell) { return density[cell]; }
	double viscosityPaPerS(int cell) { return viscosityPaPerS[cell]; }
	double maximumReynolds(int cell) { return maximumReynolds[cell]; }

	/** Cells whose temperature left the material's declared viscosity domain in the last build. */
	int extrapolatedCells() { return extrapolatedCells; }

	/** Deterministic first out-of-domain sample of the last build, or null when there was none. */
	String extrapolatedSample() {
		return sampleMaterialId == null ? null : sampleMaterialId + " at " + sampleTemperatureK
				+ " K outside [" + sampleMinimumTemperatureK + ", " + sampleMaximumTemperatureK + "] K";
	}

	/** Last build's extrapolation summary, as published through the successful step's stats. */
	FluidDomain.CarrierViscosityStats stats() {
		return new FluidDomain.CarrierViscosityStats(extrapolatedCells, extrapolatedSample());
	}

	/**
	 * Rebuilds the workspace when the given revision differs from the last build.
	 *
	 * <p>{@code thermal} may be null for layouts without thermal capability; those cells then take the
	 * isothermal computation convention from {@link MaterialRegistry#STANDARD_TEMPERATURE_K}.
	 */
	void refresh(FluidDomainStorage domain, ThermalCarrierContext markers, FluidMaterialRegistry materials,
			MaterialRegistry definitions, GranularDomainState granular, ThermalDomainState thermal, Revision revision) {
		if (revision.equals(built)) return;
		MarkerGridIndex grid = domain.markerGrid;
		if (thermal != null) thermal.preparePhaseScan(markers);
		extrapolatedCells = 0;
		sampleMaterialId = null;
		for (int cell = 0; cell < cellCount; cell++) {
			int handle = domain.granularCarrierMaterialByCell == null ? -1
					: domain.granularCarrierMaterialByCell[cell];
			boolean wet = handle >= 0 && grid.markerCount[cell] > 0;
			if (!wet) {
				liquid[cell] = false;
				materialHandle[cell] = -1;
				density[cell] = AIR_DENSITY;
				temperatureK[cell] = Double.NaN;
				viscosityPaPerS[cell] = AIR_VISCOSITY;
				maximumReynolds[cell] = AIR_MAXIMUM_REYNOLDS;
				continue;
			}
			String materialId = materials.material(handle).id();
			double cellTemperature = thermal == null ? MaterialRegistry.STANDARD_TEMPERATURE_K
					: thermal.phaseScanTemperatureInCell(cell);
			// A wet cell without a derived temperature is a broken state, not a missing convention.
			if (!Double.isFinite(cellTemperature)) {
				throw new IllegalStateException("Liquid carrier cell " + cell
						+ " has no derived temperature for material " + materialId);
			}
			double mu = definitions.dynamicViscosityExtendingAt(materialId, cellTemperature);
			liquid[cell] = true;
			materialHandle[cell] = handle;
			density[cell] = grid.cellDensity[cell];
			temperatureK[cell] = cellTemperature;
			viscosityPaPerS[cell] = mu;
			GranularCarrier carrier = granular == null ? null
					: granular.registry.carrier(granular.registry.carrierHandle(materialId));
			maximumReynolds[cell] = carrier == null ? AIR_MAXIMUM_REYNOLDS : carrier.maximumReynolds();
			if (!definitions.viscosityDomainContains(materialId, cellTemperature)) {
				extrapolatedCells++;
				if (sampleMaterialId == null) {
					sampleMaterialId = materialId;
					sampleTemperatureK = cellTemperature;
					sampleMinimumTemperatureK = definitions.entry(materialId).minimumViscosityTemperatureK();
					sampleMaximumTemperatureK = definitions.entry(materialId).maximumViscosityTemperatureK();
				}
			}
		}
		built = revision;
	}

	static final double AIR_DENSITY = 1.225;
	static final double AIR_VISCOSITY = 1.81e-5;
	static final double AIR_MAXIMUM_REYNOLDS = 100_000;
}
