package mmm.fluid;

import mmm.thermal.ThermalMaterialDefinition;
import mmm.units.SignedDyadic;

import java.util.List;

/** One retained selector for M9 definition, state-function and later transport/phase invariants. */

public abstract class ThermalValidationSupport {
	static final String CHEMICAL_FINGERPRINT = "1".repeat(64);
	private static final List<ThermalMaterialDefinition> BUILTIN_GAS_MATERIALS=loadBuiltinGasMaterials();
	public static final ThermalMaterialDefinition NITROGEN=fixtureMaterial(builtinGasMaterials().getFirst(),"nitrogen");

	private static ThermalMaterialDefinition fixtureMaterial(ThermalMaterialDefinition source,String carrier){
		return new ThermalMaterialDefinition("zztest:thermal/"+carrier,ThermalMaterialDefinition.CarrierKind.GAS,"mmm:"+carrier,
				source.referenceTemperatureK(),source.referenceSpecificEnergyJPerKg(),source.conductivityWPerMeterK(),source.caloricSegments());
	}
	public static List<ThermalMaterialDefinition> builtinGasMaterials(){return BUILTIN_GAS_MATERIALS;}
	private static List<ThermalMaterialDefinition> loadBuiltinGasMaterials(){
		var result=new java.util.ArrayList<ThermalMaterialDefinition>();
		for(String name:List.of("nitrogen","oxygen","co2","water_vapor","ethanol_vapor")){
			try(var reader=new java.io.InputStreamReader(java.util.Objects.requireNonNull(ThermalValidationSupport.class.getResourceAsStream(
					"/data/mmm/mmm/thermal_materials/"+name+".json")),java.nio.charset.StandardCharsets.UTF_8)){
				result.add(mmm.thermal.ThermalDefinitionCodec.decodeMaterial("mmm:"+name,reader));
			}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
		}
		return List.copyOf(result);
	}
	public static List<ThermalMaterialDefinition> thermalMaterials(List<ThermalMaterialDefinition> materials) {
		var complete=new java.util.ArrayList<>(materials);
		var template=materials.stream().filter(m->m.carrierKind()==ThermalMaterialDefinition.CarrierKind.GAS
				&&m.carrierId().equals("mmm:nitrogen")).findFirst().orElse(null);
		for(var material:builtinGasMaterials())if(!material.carrierId().equals("mmm:water_vapor")&&complete.stream().noneMatch(
				m->m.carrierKind()==ThermalMaterialDefinition.CarrierKind.GAS&&m.carrierId().equals(material.carrierId()))){
			String carrier=material.carrierId().substring(4);
			complete.add(fixtureMaterial(template==null?material:template,carrier));
		}
		return List.copyOf(complete);
	}
	public static List<mmm.thermal.ThermalSpeciesOffset> thermalOffsets(List<ThermalMaterialDefinition> materials,List<mmm.thermal.ThermalSpeciesOffset> offsets){
		var result=new java.util.ArrayList<>(offsets);
		for(var material:materials)if(material.carrierKind()==ThermalMaterialDefinition.CarrierKind.GAS){
			String species=switch(material.carrierId()){case "mmm:nitrogen"->"mmm:n2";case "mmm:oxygen"->"mmm:o2";case "mmm:co2"->"mmm:co2";case "mmm:water_vapor"->"mmm:h2o";case "mmm:ethanol_vapor"->"mmm:ethanol";default->null;};
			if(species!=null&&result.stream().noneMatch(o->o.hostThermalMaterialId().equals(material.id())&&o.speciesId().equals(species)))
				result.add(new mmm.thermal.ThermalSpeciesOffset("zztest:offset/"+material.id().replace(':','/')+"/"+species.substring(4),species,material.id(),mmm.chemistry.ExactRational.ZERO));
		}
		return List.copyOf(result);
	}
	public static mmm.thermal.ThermalRegistry thermalRegistry(List<ThermalMaterialDefinition> materials){return thermalRegistry(materials,List.of(),List.of(),"","");}
	public static mmm.thermal.ThermalRegistry thermalRegistry(List<ThermalMaterialDefinition> materials,List<mmm.thermal.ThermalSpeciesOffset> offsets,List<mmm.thermal.ThermalPhaseTransition> transitions,String chemistry,String granular){
		return thermalRegistry(materials,offsets,transitions,List.of(),chemistry,granular,"");
	}
	public static mmm.thermal.ThermalRegistry thermalRegistry(List<ThermalMaterialDefinition> materials,List<mmm.thermal.ThermalSpeciesOffset> offsets,List<mmm.thermal.ThermalPhaseTransition> transitions,List<mmm.thermal.ThermalBoundaryDefinition> boundaries,String chemistry,String granular){
		return thermalRegistry(materials,offsets,transitions,boundaries,chemistry,granular,"");
	}
	public static mmm.thermal.ThermalRegistry thermalRegistry(List<ThermalMaterialDefinition> materials,List<mmm.thermal.ThermalSpeciesOffset> offsets,List<mmm.thermal.ThermalPhaseTransition> transitions,List<mmm.thermal.ThermalBoundaryDefinition> boundaries,String chemistry,String granular,String gas){
		var complete=thermalMaterials(materials);return new mmm.thermal.ThermalRegistry(complete,thermalOffsets(complete,offsets),transitions,boundaries,chemistry,granular,gas);
	}
	public static mmm.thermal.ThermalRegistry thermalRegistry(List<ThermalMaterialDefinition> materials,List<mmm.thermal.ThermalSpeciesOffset> offsets,List<mmm.thermal.ThermalPhaseTransition> transitions,List<mmm.thermal.ThermalBoundaryDefinition> boundaries,List<mmm.thermal.FiniteThermalDeviceDefinition> devices,String chemistry,String granular,String gas){
		var complete=thermalMaterials(materials);return new mmm.thermal.ThermalRegistry(complete,thermalOffsets(complete,offsets),transitions,boundaries,devices,chemistry,granular,gas);
	}

	/** Explicit raw owners for phase storage, integer partitions and carrier-count budgets. */
	static void restorePhaseTestCarriers(HostFluidState domain, FluidMaterialRegistry materials,
	                                     float[] volumes, int[] materialHandles) {
		restorePhaseTestCarriers(domain, materials, 1, 1, 1, volumes, materialHandles);
	}
	static void restorePhaseTestCarriers(HostFluidState domain, FluidMaterialRegistry materials, int x, int y, int z,
	                                     float[] volumes, int[] materialHandles) {
		float[] positions = new float[4 * volumes.length]; long[] masses = new long[volumes.length];
		for (int marker = 0; marker < volumes.length; marker++) {
			float[] position = domain.domainFlowFields.markerSlotPosition(x, y, z, marker);
			System.arraycopy(position, 0, positions, 4 * marker, 3); positions[4 * marker + 3] = volumes[marker];
			masses[marker] = domain.unitScale.massUnitsForNormalized(volumes[marker] * materials.material(materialHandles[marker]).referenceDensity());
		}
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.config().cellSize(), volumes.length, SignedDyadic.sum(volumes, volumes.length).toDouble(), mmm.units.Mass.sum(masses), materials.materials(), positions, new float[3 * volumes.length],
				domain.geometry.cutCells.copyMicroMasks(), masses, materialHandles));
	}

	static ThermalMaterialDefinition material(
			String id, String carrier, double referenceSpecificEnergy,
			List<ThermalMaterialDefinition.CaloricSegment> segments
	) {
		return new ThermalMaterialDefinition(id, ThermalMaterialDefinition.CarrierKind.LIQUID, carrier,
				300.0, referenceSpecificEnergy, 0.5, segments);
	}

	static boolean close(double first, double second) {
		return Math.abs(first - second) <= Math.max(1.0E-10,
				Math.max(Math.ulp(first), Math.ulp(second)) * 8.0);
	}

	static double relativeError(double actual, double expected) {
		return Math.abs(actual - expected) / Math.abs(expected);
	}

	static void expectFailure(Runnable action, String message) {
		try { action.run(); }
		catch (RuntimeException expected) { return; }
		throw new AssertionError(message);
	}

	static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
