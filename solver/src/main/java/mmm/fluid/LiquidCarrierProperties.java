package mmm.fluid;

import mmm.granular.GranularCarrier;
import mmm.thermal.ThermalCarrierContext;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.LiquidThermalExpansion;
import java.util.Arrays;

/**
 * Per-cell liquid properties prepared per mechanical substep and invalidated by grid or inventory edits.
 *
 * <p>Every consumer of liquid density, temperature and dynamic viscosity — active granular drag,
 * Darcy pore damping and the bed-shear closure — reads this one workspace, so a cell is observed
 * once per prepared batch instead of once per parcel support point or resistance face.
 *
 * <p>The arrays are fixed-capacity scratch. Nothing here is authoritative and nothing here is
 * persisted: the workspace is rebuilt from the marker grid, the world material definitions and the
	 * authoritative thermal carriers, and a restored or candidate domain rebuilds it from its own state.
 */
final class LiquidCarrierProperties {
	/** Cell key of the derived arrays; rebuilt whenever any component advances. */
	record Revision(long carrierMutation, long chemistryMutation, ThermalDomainState.Revision thermal) { }

	private final int cellCount;
	private final boolean[] liquid;
	private final int[] materialHandle;
	private final double[] density;
	private final double[] temperatureK;
	private final double[] viscosityPascalSeconds;
	private final double[] maximumReynolds;
	private final double[] relativeBuoyancy;
	private final double[] observationMass;
	private boolean buoyancyPrepared;
	private Revision built;
	private boolean recording;
	private int stepExtrapolatedCells;
	private String stepSample;
	private FluidDomain.CarrierViscosityStats publishedStats = new FluidDomain.CarrierViscosityStats(0, "");
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
		this.viscosityPascalSeconds = new double[cellCount];
		this.maximumReynolds = new double[cellCount];
		this.relativeBuoyancy = new double[cellCount];
		this.observationMass = new double[cellCount];
	}

	boolean hasLiquid(int cell) { return liquid[cell]; }
	int materialHandle(int cell) { return materialHandle[cell]; }
	double density(int cell) { return density[cell]; }
	double viscosityPascalSeconds(int cell) { return viscosityPascalSeconds[cell]; }
	double maximumReynolds(int cell) { return maximumReynolds[cell]; }
	double relativeBuoyancy(int cell) { return relativeBuoyancy[cell]; }

	/** All phases use the prepared cell temperature and retain their own expansion coefficient. */
	void prepareBuoyancy(ThermalCarrierContext markers,
			FluidMaterialRegistry materials, MaterialRegistry definitions, ThermalDomainState thermal) {
		if (buoyancyPrepared) return;
		Arrays.fill(relativeBuoyancy, 0.0);
		Arrays.fill(observationMass, 0.0);
		// The standalone liquid has constant density and no expansion law.
		if (thermal == null || materials == null) { buoyancyPrepared = true; return; }
		for (int marker = 0; marker < markers.markerCount(); marker++) {
			int cell=markers.cell(marker);double mass=markers.mass(marker);
			if (!(mass > 0)) continue;
			String id=materials.material(markers.fluidMaterialHandle(marker)).id();
			var expansion=definitions==null?LiquidThermalExpansion.require(id):definitions.entry(id).thermalExpansion();
			if(expansion==null)throw new IllegalStateException("Liquid "+id+" has no committed thermal expansion definition");
			relativeBuoyancy[cell]+=mass*expansion.relativeBuoyancy(temperatureK[cell]);
			observationMass[cell]+=mass;
		}
		for (int cell = 0; cell < cellCount; cell++) {
			if (observationMass[cell] > 0) relativeBuoyancy[cell] /= observationMass[cell];
		}
		buoyancyPrepared = true;
	}

	/** Cells whose temperature left the material's declared viscosity domain in the last build. */
	int extrapolatedCells() { return extrapolatedCells; }

	/** Deterministic first out-of-domain sample of the last build, or null when there was none. */
	String extrapolatedSample() {
		return sampleMaterialId == null ? null : sampleMaterialId + " at " + sampleTemperatureK
				+ " K outside [" + sampleMinimumTemperatureK + ", " + sampleMaximumTemperatureK + "] K";
	}

	void invalidate() { built = null; buoyancyPrepared = false; }

	void beginStep() {
		invalidate();
		recording = true;
		stepExtrapolatedCells = 0;
		stepSample = null;
	}

	FluidDomain.CarrierViscosityStats finishStep() {
		publishedStats = new FluidDomain.CarrierViscosityStats(stepExtrapolatedCells, stepSample);
		recording = false;
		return publishedStats;
	}

	/** Reading diagnostics never rebuilds physical properties or changes the last successful result. */
	FluidDomain.CarrierViscosityStats stats() {
		return publishedStats;
	}

	/**
	 * Rebuilds the workspace when the given revision differs from the last build.
	 *
	 * <p>{@code thermal} may be null for layouts without thermal capability; those cells then take the
	 * isothermal computation convention from {@link MaterialRegistry#STANDARD_TEMPERATURE_K}.
	 */
	void refresh(FluidDomain domain, ThermalCarrierContext markers, FluidMaterialRegistry materials,
			MaterialRegistry definitions, GranularDomainState granular, ThermalDomainState thermal, long carrierMutation) {
		long chemistryMutation = domain.chemicalParcels == null ? 0 : domain.chemicalParcels.mutationRevision();
		if (built != null && built.carrierMutation() == carrierMutation
				&& built.chemistryMutation() == chemistryMutation
				&& (thermal == null ? built.thermal() == null : thermal.matchesThermalRevision(built.thermal()))) return;
		CellLiquidGrid grid = domain.liquidGrid;
		if (thermal != null) thermal.preparePhaseScan(markers);
		buoyancyPrepared = false;
		extrapolatedCells = 0;
		sampleMaterialId = null;
		for (int cell = 0; cell < cellCount; cell++) {
			density[cell] = grid.cellDensity[cell];
			int handle = domain.liquidCarrierMaterialByCell == null ? -1
					: domain.liquidCarrierMaterialByCell[cell];
			boolean wet = grid.cellStockMass[cell] > 0 && grid.stockCount[cell] > 0;
			if (!wet) {
				liquid[cell] = false;
				materialHandle[cell] = -1;
				if (grid.stockCount[cell] == 0) density[cell] = AIR_DENSITY;
				temperatureK[cell] = Double.NaN;
				viscosityPascalSeconds[cell] = AIR_VISCOSITY;
				maximumReynolds[cell] = AIR_MAXIMUM_REYNOLDS;
				continue;
			}
			String materialId = materials == null ? null : materials.material(handle).id();
			double cellTemperature = thermal == null ? MaterialRegistry.STANDARD_TEMPERATURE_K
					: thermal.phaseScanTemperatureInCell(cell);
			// Positive liquid mass carries heat independently of optional viscosity data.
			if (!Double.isFinite(cellTemperature)) {
				throw new IllegalStateException("Liquid carrier cell " + cell
						+ " has no derived temperature for " + (materialId == null ? "the standalone liquid" : materialId));
			}
			double mu = definitions == null || materialId == null ? 0 : definitions.dynamicViscosityExtendingAt(materialId, cellTemperature);
			liquid[cell] = true;
			materialHandle[cell] = materials == null ? -1 : handle;
			temperatureK[cell] = cellTemperature;
			viscosityPascalSeconds[cell] = mu;
			GranularCarrier carrier = granular == null || materialId == null ? null
					: granular.registry.carrier(granular.registry.carrierHandle(materialId));
			maximumReynolds[cell] = carrier == null ? AIR_MAXIMUM_REYNOLDS : carrier.maximumReynolds();
			if (definitions != null && materialId != null && !definitions.viscosityDomainContains(materialId, cellTemperature)) {
				extrapolatedCells++;
				if (sampleMaterialId == null) {
					sampleMaterialId = materialId;
					sampleTemperatureK = cellTemperature;
					sampleMinimumTemperatureK = definitions.entry(materialId).minimumViscosityTemperatureK();
					sampleMaximumTemperatureK = definitions.entry(materialId).maximumViscosityTemperatureK();
				}
			}
		}
		built = new Revision(carrierMutation, chemistryMutation, thermal == null ? null : thermal.thermalRevision());
		if (recording) {
			stepExtrapolatedCells = Math.addExact(stepExtrapolatedCells, extrapolatedCells);
			if (stepSample == null) stepSample = extrapolatedSample();
		}
	}

	static final double AIR_DENSITY = 1.225;
	static final double AIR_VISCOSITY = 1.81e-5;
	static final double AIR_MAXIMUM_REYNOLDS = 100_000;
}
