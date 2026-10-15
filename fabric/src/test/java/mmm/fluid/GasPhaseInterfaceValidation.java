package mmm.fluid;

import java.util.Arrays;

/** Actual microgeometry controls for phase contacts and disconnected liquid components. */
public final class GasPhaseInterfaceValidation {
	private GasPhaseInterfaceValidation() { }

	public static void main(String[] args) { validate(); }

	public static void validate() {
		for(int root=0;root<=8;root++)for(int mask=0;mask<256;mask++) {
			int equilibrium=root,representable=mask;
			var selected=GasPhaseBatchSearch.equilibrium(8,batch->(representable & 1<<((int)batch-1))==0 ? null
					: new GasPhaseBatchSearch.Sample<Integer>(equilibrium-batch,batch<=equilibrium?(int)batch:null));
			int admissible=mask & ((1<<root)-1), expected=32-Integer.numberOfLeadingZeros(admissible);
			check(expected==0?selected==null:selected!=null && selected==expected,
					"Saturation-root search lost a representable integer extent: root="+root+" mask="+mask);
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
		validateFiniteGasBatches();
		System.out.println("Gas phase microcontact validation passed");
	}

	private static void validateFiniteGasBatches() {
		var water=new mmm.thermal.ThermalMaterialDefinition("test:finite-air/water",mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,
				BuiltinFluidMaterials.WATER.id(),373.15,0,.1,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(250,300,2000),
						new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(300,800,4180)));
		var vapor=new mmm.thermal.ThermalMaterialDefinition("test:finite-air/vapor",mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,
				mmm.fluid.air.BuiltinGasDefinitions.WATER_VAPOR,373.15,2256000,.1,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(250,800,1410)));
		var air=new mmm.thermal.ThermalMaterialDefinition("test:finite-air/air",mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,
				mmm.fluid.air.BuiltinGasDefinitions.AIR,273.15,0,.025,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(150,800,718)));
		var transition=new mmm.thermal.ThermalPhaseTransition("test:finite-air/boil",water.id(),vapor.id(),373.15,168,168*1700L,
				mmm.chemistry.ExactRational.of(21000).divide(1<<24),mmm.thermal.ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS,10000,3000000);
		var registry=new mmm.thermal.ThermalRegistry(java.util.List.of(water,vapor,air),java.util.List.of(),java.util.List.of(transition),"","");
		try {
			for(int sourceCase=0;sourceCase<5;sourceCase++) {
				boolean existingVapor=sourceCase==1, mergingPeers=sourceCase==4;
				boolean completeDepletion=sourceCase==2 || mergingPeers, partialNeighbor=sourceCase==3;
				var definitions=registry;
				if(completeDepletion) {
					var equalEnergyWater=new mmm.thermal.ThermalMaterialDefinition(water.id(),water.carrierKind(),water.carrierId(),300,0,.1,water.caloricSegments());
					var equalEnergyVapor=new mmm.thermal.ThermalMaterialDefinition(vapor.id(),vapor.carrierKind(),vapor.carrierId(),300,0,.1,
							java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(250,300,7000),
									new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(300,800,5000)));
					var hotAir=new mmm.thermal.ThermalMaterialDefinition(air.id(),air.carrierKind(),air.carrierId(),400,0,.025,air.caloricSegments());
					definitions=new mmm.thermal.ThermalRegistry(java.util.List.of(equalEnergyWater,equalEnergyVapor,hotAir),java.util.List.of(),java.util.List.of(transition),"","");
				}
				byte[] masks=new byte[8];Arrays.fill(masks,(byte)255);masks[0]=0;if(partialNeighbor)masks[1]=0;if(mergingPeers)for(int peer:new int[]{1,2,4})masks[peer]=0;
				var domain=new FluidDomain(2,2,2,new FluidSolverConfig(.5F,1000,0,.01F,200,1e-5F,.5F,1e-4F,0),32);
				float volume=completeDepletion?mmm.granular.SolidVolume.toCellVolume(8*168):.5F;
				float[] positions=partialNeighbor?new float[]{.25F,.125F,.25F,volume, .75F,.125F,.25F,.5F}
						:new float[]{.25F,.125F,.25F,volume};
				domain.restoreParticleState(new FlipParticleState(2,2,2,.5F,positions.length/4,volume+(partialNeighbor?.5F:0),positions,
						new float[positions.length/4*3],masks));domain.sealDomainBoundary();
				domain.bindGasRegistry(mmm.fluid.air.BuiltinGasDefinitions.createRegistry());domain.bootstrapGasState("");
				int node=domain.gasState.frame.commonCellNode(0);
				if(mergingPeers)check(domain.gasState.frame.nodeCount()==3,"Complete-depletion merge fixture lost its three separate gas stocks");
				var phase=new mmm.thermal.GasLiquidPhaseModel(definitions,domain.gasState.network.registry());
				if(existingVapor) domain.gasState.network.commitPhaseMutation(domain.gasState.network.planPhaseSource(node,phase.gasBatch(transition,1)));
				domain.bindThermal(definitions,new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER)),completeDepletion||existingVapor?400:300);
				if(completeDepletion||existingVapor)domain.setThermalTemperatureInCell(0,0,0,300);
				var stage=ThermochemistryStageAssembly.create(domain).gasPhase();
				var field=GasLiquidPhaseChangeStage.class.getDeclaredField("support");field.setAccessible(true);
				var support=(PhaseChangeStageSupport)field.get(stage);
				support.ensureInventoryGrid();support.refreshGasTopology();domain.thermalState.preparePhaseScan(domain.thermalMarkerContext());
				var contact=new GasPhaseInterfaceResolver(support).resolve(0,transition,phase,domain.gasState,300).getFirst();
				var thermalCell=domain.thermalState.preparePhaseCandidateCell(0,domain.thermalMarkerContext());
				if(partialNeighbor) {
					var frame=domain.gasState.frame;int peer=frame.commonCellNode(1);
					check(peer>0 && peer!=node && !frame.fullDryFace(1,0,0)
							&& frame.topology().connections().stream().anyMatch(edge->edge.area()>0
							&& (edge.firstNode()==node && edge.secondNode()==peer || edge.firstNode()==peer && edge.secondNode()==node)),
							"Partially dry fixed face lost its separate finite-rate gas connection");
				}
				var prepare=Arrays.stream(GasLiquidPhaseChangeStage.class.getDeclaredMethods()).filter(m->m.getName().equals("prepareBoilTrial")).findFirst().orElseThrow();prepare.setAccessible(true);
				var complete=Arrays.stream(GasLiquidPhaseChangeStage.class.getDeclaredMethods()).filter(m->m.getName().equals("completeBoil")).findFirst().orElseThrow();complete.setAccessible(true);
				var source=GasRuntimeGeometry.preparePhaseSource(domain,0);
				var liquidSource=new PhaseLiquidInventory(support,0);
				double sourceEnergy=domain.thermalState.gasNodeEnergy(node,domain.gasState.network);
				@SuppressWarnings("unchecked")
				GasPhaseBatchSearch.Sample<Object>[] candidates = new GasPhaseBatchSearch.Sample[9];
				int largest=0;
				for(int batch=1;batch<=8;batch++) {
					var trial=prepare.invoke(stage,0,node,0,transition,phase,thermalCell,new boolean[0],300.0,(long)batch,contact,liquidSource,sourceEnergy);
					var prepared=(GasPhaseBatchSearch.Sample<?>)complete.invoke(stage,0,node,transition,phase,contact,source,trial);
					var full=(GasPhaseBatchSearch.Sample<?>)complete.invoke(stage,0,node,transition,phase,contact,null,trial);
					check((prepared==null)==(full==null),"Prepared gas geometry changed a representable complete physical candidate");
					if(prepared!=null) {
						check((prepared.residual()>=0)==(full.residual()>=0),"Prepared gas geometry changed the actual successor saturation direction");
						candidates[batch]=new GasPhaseBatchSearch.Sample<>(prepared.residual(),prepared.plan());
						if(prepared.residual()>=0)largest=batch;
					}
				}
				check(largest==(completeDepletion?8:existingVapor?0:1),"Finite-air comparison lost its partial/complete physical candidate: source="+sourceCase+" largest="+largest);
				var maximum=GasPhaseBatchSearch.equilibrium(8,batch->candidates[(int)batch]);
				check(largest==0?maximum==null:maximum==candidates[largest].plan(),"Finite-air search changed the exhaustive physical maximum");
				if(largest>0) {
					var beforeVolume=domain.markerGrid.exactCellVolumeInventory[0];
					domain.stepGasPhaseChangeOnly(8);
					domain.ensureInventoryGrid();
					check(beforeVolume.subtract(domain.markerGrid.exactCellVolumeInventory[0]).equals(
							mmm.units.SignedDyadic.of(Math.multiplyExact((long)largest,transition.lowerVolumeSvqPerBatch()),-24)),
							"Actual phase search pruned a physically admissible largest batch: source="+sourceCase);
					check(domain.gasBoundary().auditClosed() && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
							"Actual finite-air batch publication broke its gas or thermal audit");
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
