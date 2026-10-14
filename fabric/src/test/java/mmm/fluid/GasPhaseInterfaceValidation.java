package mmm.fluid;

import java.util.Arrays;

/** Actual microgeometry controls for phase contacts and disconnected liquid components. */
public final class GasPhaseInterfaceValidation {
	private GasPhaseInterfaceValidation() { }

	public static void main(String[] args) { validate(); }

	public static void validate() {
		for (int set = 0; set < 256; set++) {
			int feasible = set;
			Integer maximum = GasPhaseBatchSearch.maximum(8, batch -> (feasible & 1 << ((int) batch - 1)) != 0 ? (int) batch : null,
					(low, high) -> (feasible & ((1 << (int) high) - (1 << ((int) low - 1)))) == 0);
			check(maximum == null ? set == 0 : maximum == 32 - Integer.numberOfLeadingZeros(set),
					"Certified interval exclusion changed a disconnected integer feasible set");
		}
		for (boolean bothWet : new boolean[]{false, true}) {
			FluidDomain domain = domain(126, false, bothWet
					? new float[]{.25F,.25F,.25F,.0625F, .75F,.75F,.75F,.03125F}
					: new float[]{.25F,.25F,.25F,.0625F});
			var before = domain.exportParticleState();
			var frame = GasRuntimeGeometry.capture(domain, null, new long[8], true);
			var contacts = GasPhaseInterfaceResolver.contacts(frame, 0);
			check(contacts.size() == (bothWet ? 2 : 1), "Disconnected dry microbox became a liquid contact");
			for (var contact : contacts) {
				check(contact.supportMask() == 1 || bothWet && contact.supportMask() == 128,
						"Contact crossed a fixed solid partition");
				int micro = contact.supportMask() == 1 ? 0 : 7;
				check(contact.node() == frame.nodeOfPiece(micro) && contact.pieces().equals(java.util.List.of(micro)),
						"Independent wet components shared a gas owner or contact piece");
			}
			check(Arrays.equals(before.copyPositionVolume(), domain.exportParticleState().copyPositionVolume())
					&& frame.matchesSourceCarrierGeometry(domain, new long[8]), "Contact observation changed its source stock");
		}
		// The interface lies exactly on the internal y=.5 microface; no individual piece is partially dry.
		var half = domain(0, false, new float[]{.5F,.25F,.5F,.5F});
		var halfFrame = GasRuntimeGeometry.capture(half, null, new long[8], true);
		var halfContacts = GasPhaseInterfaceResolver.contacts(halfFrame, 0);
		check(halfContacts.size() == 1 && halfContacts.getFirst().supportMask() == 255
				&& halfContacts.getFirst().pieces().size() == 4, "A microface-aligned PLIC interface lost its gas contact");
		for (int piece : halfContacts.getFirst().pieces()) check((piece & 2) != 0,
				"An aligned liquid interface selected the wet side as gas");

		var full = domain(0, true, new float[]{.5F,.5F,.5F,1});
		var fullFrame = GasRuntimeGeometry.capture(full, null, new long[8], true);
		var sharedContacts = GasPhaseInterfaceResolver.contacts(fullFrame, 0);
		check(sharedContacts.size() == 1 && sharedContacts.getFirst().pieces().size() == 4,
				"Full liquid lost its shared wet/dry face contact");
		for (int piece : sharedContacts.getFirst().pieces()) check(piece / 8 == 1 && (piece & 1) == 0,
				"Shared-face contact selected an unrelated gas microbox");
		full.bootstrapGasState("");
		var source = full.gasState.frame;
		var envelope = GasPhaseGeometryEnvelope.capture(source, full.gasState.network, null, 0, source.commonCellNode(1));
		check(envelope != null, "A fixed source gas chamber lost its single-successor capacity certificate");
		var volume = mmm.units.SignedDyadic.of(.75);
		var moment = volume.multiply(mmm.units.SignedDyadic.of(.5));
		var edit = new PhaseLiquidGeometryEdit(0, 0, java.util.List.of(new LiquidComponentMoments(0,255,1,volume,moment,moment,moment)));
		var future = GasRuntimeGeometry.capture(full, edit, new long[8], true);
		check(envelope.volumeUpper(edit) >= future.capacity().chambers().getFirst().cubicMeters()
				&& envelope.volumeUpper(edit) <= Math.nextUp(Math.nextUp(Math.nextUp(1.25))),
				"Certified future capacity omitted released volume or broadened its rounding budget");
		var merger = domain(0, true, new float[]{.5F,.5F,.5F,1});
		merger.geometry.cutCells.replaceMask(2,0); merger.geometry.solid[2]=false; merger.bootstrapGasState("");
		var mergingSource = merger.gasState.frame;
		check(mergingSource.nodeCount()==2 && GasPhaseGeometryEnvelope.capture(mergingSource,merger.gasState.network,null,
				0,mergingSource.commonCellNode(1))==null,"A possible merger reused an isolated gas inventory/energy certificate");
		var branched = domain(0, true, new float[]{.5F,.25F,.5F,.5F});
		for(int cell:new int[]{2,3}) { branched.geometry.cutCells.replaceMask(cell,0); branched.geometry.solid[cell]=false; }
		branched.bootstrapGasState("");
		var branchSource = branched.gasState.frame;
		var branchContact = GasPhaseInterfaceResolver.contacts(branchSource,0).getFirst();
		check(GasPhaseGeometryEnvelope.capture(branchSource,branched.gasState.network,null,0,branchContact.node())==null,
				"A changing multi-cell source claimed a complete-stock successor");
		var branchEnvelope = GasPhaseGeometryEnvelope.capture(branchSource,branched.gasState.network,null,0,branchContact.node(),branchContact.pieces());
		check(branchEnvelope!=null && branchEnvelope.splitSource()
				&& branchEnvelope.sourceShare().equals(mmm.chemistry.ExactRational.of(6).divide(7)),
				"Fixed contact backbone lost its exact source-stock share: " + branchContact + " share="
						+ (branchEnvelope==null?null:branchEnvelope.sourceShare()));
		check(GasPhaseGeometryEnvelope.capture(branchSource,branched.gasState.network,null,0,branchContact.node(),
				branchContact.pieces().stream().filter(piece->piece/8==0).toList())==null,
				"Changing contact pieces supplied an unproved persistent gas path");
		validateFiniteGasBatchBounds();
		System.out.println("Gas phase microcontact validation passed");
	}

	private static void validateFiniteGasBatchBounds() {
		var water=new mmm.thermal.ThermalMaterialDefinition("test:bounds/water",mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,
				BuiltinFluidMaterials.WATER.id(),373.15,0,.1,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(250,300,2000),
						new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(300,800,4180)));
		var vapor=new mmm.thermal.ThermalMaterialDefinition("test:bounds/vapor",mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,
				mmm.fluid.air.BuiltinGasDefinitions.WATER_VAPOR,373.15,2256000,.1,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(250,800,1410)));
		var air=new mmm.thermal.ThermalMaterialDefinition("test:bounds/air",mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,
				mmm.fluid.air.BuiltinGasDefinitions.AIR,273.15,0,.025,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(150,800,718)));
		var transition=new mmm.thermal.ThermalPhaseTransition("test:bounds/boil",water.id(),vapor.id(),373.15,168,168*1700L,
				mmm.chemistry.ExactRational.of(21000).divide(1<<24),mmm.thermal.ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS,10000,3000000);
		var registry=new mmm.thermal.ThermalRegistry(java.util.List.of(water,vapor,air),java.util.List.of(),java.util.List.of(transition),"","");
		for(long reference:new long[]{-1_000_000_000_000L,3_000_000_000_000L}) {
			var offset=new mmm.thermal.ThermalSpeciesOffset("test:bounds/h2o", "mmm:h2o",vapor.id(),mmm.chemistry.ExactRational.of(reference).divide(7));
			var partitionVapor=new mmm.thermal.ThermalMaterialDefinition(vapor.id(),vapor.carrierKind(),vapor.carrierId(),vapor.referenceTemperatureK(),
					vapor.referenceSpecificEnergyEuPerMu(),vapor.conductivityEuPerSecondMeterK(),java.util.List.of(
						new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(250,301,1111.17),
						new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(301,500,1410.13001),
						new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(500,800,2000.25)));
			var partitionAir=new mmm.thermal.ThermalMaterialDefinition(air.id(),air.carrierKind(),air.carrierId(),air.referenceTemperatureK(),
					air.referenceSpecificEnergyEuPerMu(),air.conductivityEuPerSecondMeterK(),java.util.List.of(
						new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(150,273.15,718.1234567),
						new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(273.15,350,702.25),
						new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(350,800,789.000001)));
			var definitions=new mmm.thermal.ThermalRegistry(java.util.List.of(water,partitionVapor,partitionAir),java.util.List.of(offset),java.util.List.of(transition),"0".repeat(64),"");
			var model=new mmm.thermal.GasThermalModel(definitions,mmm.fluid.air.BuiltinGasDefinitions.createRegistry());
			var stock=new mmm.fluid.air.GasInventory(java.util.List.of(
					new mmm.fluid.air.GasComponentAmount(air.carrierId(),9_007_199_254_740_991L,72,java.util.List.of(),72),
					new mmm.fluid.air.GasComponentAmount(vapor.carrierId(),91,.003,java.util.List.of(new mmm.fluid.air.GasComponentAmount.SpeciesAmount("mmm:h2o",37)),.003)));
			var merged=new GasPhaseMergeBounds(definitions,model,java.util.List.of());
			for(double temperature:new double[]{293.15,Math.nextDown(301),301,Math.nextUp(301),333.333333,400,Math.nextUp(600)})
				for(long[] weights:new long[][]{{1,1},{999,1},{1,999},{0,1,3,7},{4095,1,1,1}}) {
				var children=stock.partition(weights);double energy=model.internalEnergyAtTemperature(stock,temperature);
				var energies=model.partitionEnergy(stock,energy,children);long sum=Arrays.stream(weights).sum();
				for(int child=0;child<weights.length;child++)if(weights[child]>0) {
					var share=mmm.chemistry.ExactRational.of(weights[child]).divide(sum);
					double lower=merged.partitionTemperatureLower(stock,energy,share,weights.length);
					check(Double.isFinite(lower) && lower<=model.state(children.get(child),energies[child]).temperatureK(),
							"Split gas temperature lower bound exceeded an actual common-temperature child");
					long nanos=share.numerator().multiply(java.math.BigInteger.valueOf(stock.component(vapor.carrierId()).standardVolumeNanos()))
							.divide(share.denominator()).longValueExact();
					var amount=children.get(child).component(vapor.carrierId());
					check(nanos<=(amount==null?0:amount.standardVolumeNanos()),"Split stock bound exceeded a largest-remainder vapor share");
				}
			}
		}
		try {
			for(int sourceCase=0;sourceCase<3;sourceCase++) {
				boolean existingVapor=sourceCase==1, completeDepletion=sourceCase==2;
				var definitions=registry;
				if(completeDepletion) {
					var equalEnergyWater=new mmm.thermal.ThermalMaterialDefinition(water.id(),water.carrierKind(),water.carrierId(),300,0,.1,water.caloricSegments());
					var equalEnergyVapor=new mmm.thermal.ThermalMaterialDefinition(vapor.id(),vapor.carrierKind(),vapor.carrierId(),300,0,.1,
							java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(250,300,7000),
									new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(300,800,5000)));
					var hotAir=new mmm.thermal.ThermalMaterialDefinition(air.id(),air.carrierKind(),air.carrierId(),400,0,.025,air.caloricSegments());
					definitions=new mmm.thermal.ThermalRegistry(java.util.List.of(equalEnergyWater,equalEnergyVapor,hotAir),java.util.List.of(),java.util.List.of(transition),"","");
				}
				byte[] masks=new byte[8];Arrays.fill(masks,(byte)255);masks[0]=0;
				var domain=new FluidDomain(2,2,2,new FluidSolverConfig(.5F,1000,0,.01F,200,1e-5F,.5F,1e-4F,0),32);
				float volume=completeDepletion?mmm.granular.SolidVolume.toCellVolume(8*168):.5F;
				domain.restoreParticleState(new FlipParticleState(2,2,2,.5F,1,volume,new float[]{.25F,.125F,.25F,volume},new float[3],masks));domain.sealDomainBoundary();
				domain.bindGasRegistry(mmm.fluid.air.BuiltinGasDefinitions.createRegistry());domain.bootstrapGasState("");
				int node=domain.gasState.frame.commonCellNode(0);
				var phase=new mmm.thermal.GasLiquidPhaseModel(definitions,domain.gasState.network.registry());
				if(existingVapor) domain.gasState.network.commitPhaseMutation(domain.gasState.network.planPhaseSource(node,phase.gasBatch(transition,1)));
				domain.bindThermal(definitions,new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER)),completeDepletion||existingVapor?400:300);
				if(completeDepletion||existingVapor)domain.setThermalTemperatureInCell(0,0,0,300);
				var stage=ThermochemistryStageAssembly.create(domain).gasPhase();
				var field=GasLiquidPhaseChangeStage.class.getDeclaredField("support");field.setAccessible(true);
				var support=(PhaseChangeStageSupport)field.get(stage);
				support.ensureGrid();support.refreshGasTopology();domain.thermalState.preparePhaseScan(domain.thermalMarkerContext());
				var contact=new GasPhaseInterfaceResolver(support).resolve(0,transition,phase,domain.gasState,300).getFirst();
				var thermalCell=domain.thermalState.preparePhaseCandidateCell(0,domain.thermalMarkerContext());
				var envelope=GasPhaseGeometryEnvelope.forBoiling(support,0,node);
				var bounds=GasBoilingBatchBounds.forBoiling(support,0,node,0,transition,thermalCell,300,new boolean[0],255,phase,envelope);
				check(bounds!=null,"Finite air lost its complete-source interval bound");
				var prepare=Arrays.stream(GasLiquidPhaseChangeStage.class.getDeclaredMethods()).filter(m->m.getName().equals("prepareBoilInventory")).findFirst().orElseThrow();prepare.setAccessible(true);
				var complete=Arrays.stream(GasLiquidPhaseChangeStage.class.getDeclaredMethods()).filter(m->m.getName().equals("completeBoil")).findFirst().orElseThrow();complete.setAccessible(true);
				var constructor=complete.getParameterTypes()[6].getDeclaredConstructor(GasLiquidPhaseChangeStage.class);constructor.setAccessible(true);
				var source=constructor.newInstance(stage);var planes=GasRuntimeGeometry.prepareBoilContactPlanes(support,0,contact);
				Object[] candidates=new Object[9];int largest=0;
				for(int batch=1;batch<=8;batch++) {
					var inventory=prepare.invoke(stage,0,node,0,transition,phase,thermalCell,new boolean[0],300.0,(long)batch,contact);
					candidates[batch]=complete.invoke(stage,0,node,transition,phase,contact,planes,source,inventory);
					if(candidates[batch]!=null)largest=batch;
				}
				check(largest==(completeDepletion?8:existingVapor?0:1),"Finite-air comparison lost its partial/complete physical candidate: source="+sourceCase+" largest="+largest);
				if(completeDepletion)check(!bounds.excluded(1,8) && !bounds.excluded(8,8),"Complete depletion used a fictitious remaining liquid temperature");
				int excluded=0;
				for(int low=1;low<=8;low++)for(int high=low;high<=8;high++)if(bounds.excluded(low,high)) {
					excluded++;for(int batch=low;batch<=high;batch++)check(candidates[batch]==null,"Finite-air bounds excluded a complete physical batch");
				}
				var maximum=GasPhaseBatchSearch.maximum(8,batch->candidates[(int)batch],bounds::excluded);
				check((completeDepletion?excluded==0:excluded>0) && (largest==0?maximum==null:maximum==candidates[largest]),"Finite-air interval search changed the exhaustive maximum");
				if(completeDepletion) {
					var model=new mmm.thermal.GasThermalModel(definitions,domain.gasState.network.registry());
					var stock=domain.gasState.network.inventory(node);double energy=domain.thermalState.gasNodeEnergy(node,domain.gasState.network);
					var cold=new GasPhaseMergeBounds.Stock(stock,model.internalEnergyAtTemperature(stock,200),mmm.units.SignedDyadic.of(.125));
					var born=new mmm.thermal.ThermalCaloricBounds.PortionRange(definitions.materialHandle(vapor.id()),
							transition.massMuPerBatch().toDouble(),transition.massMuPerBatch().multiply(8).toDouble(),java.util.Map.of());
					var caloric=new mmm.thermal.ThermalCaloricBounds(definitions);double error=caloric.forwardRoundingBound(born,300);
					double rise=caloric.temperatureWithEnergyGainLower(java.util.List.of(born),300,
							mmm.units.SignedDyadic.of(born.massHigh()).multiply(mmm.units.SignedDyadic.of(5000)).toDouble());
					check(rise==Math.nextDown(Math.nextDown(301.0)),"Energy-gain lower bound lost its actual caloric segment or inverter allowance");
					for(boolean optional:new boolean[]{false,true}) {
						var merged=new GasPhaseMergeBounds(definitions,model,optional?java.util.List.of(cold):java.util.List.of());
						double lower=merged.temperatureLower(stock,energy,born,300,error);
						check(lower>(optional?200:300),"Mandatory heat surplus did not strengthen the minimum-temperature bound");
						for(int batch=1;batch<=8;batch++)for(int subset=0;subset<=(optional?1:0);subset++) {
							var birth=phase.gasBatch(transition,batch);var inventory=stock.merge(birth);
							var total=mmm.units.SignedDyadic.of(energy).add(mmm.units.SignedDyadic.of(model.internalEnergyAtTemperature(birth,300)));
							if(subset==1){inventory=inventory.merge(cold.inventory());total=total.add(mmm.units.SignedDyadic.of(cold.energy()));}
							check(lower<=model.state(inventory,total.toDouble()).temperatureK(),"Mandatory heat surplus exceeded an actual optional-subset mixture temperature");
						}
					}
				}
			}
		} catch(ReflectiveOperationException failure) { throw new AssertionError("Finite-air production-candidate comparison failed",failure); }
	}

	private static FluidDomain domain(int mask, boolean peerOpen, float[] markers) {
		byte[] masks = new byte[8]; Arrays.fill(masks, (byte)255); masks[0] = (byte) mask;
		if (peerOpen) masks[1] = 0;
		float total = 0;
		for (int i = 3; i < markers.length; i += 4) total += markers[i];
		FluidDomain domain = new FluidDomain(2, 2, 2,
				new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.5F,1e-4F,0), 32);
		domain.restoreParticleState(new FlipParticleState(2,2,2,1,markers.length/4,total,markers,
				new float[markers.length/4*3],masks));
		domain.sealDomainBoundary();
		return domain;
	}

	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
