package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import java.util.Arrays;

/** Actual microgeometry controls for phase contacts and disconnected liquid components. */
public final class GasPhaseInterfaceValidation {
	private GasPhaseInterfaceValidation() { }

	public static void main(String[] args) { validate(); }

	public static void validate() {
		for (boolean bothWet : new boolean[]{false, true}) {
			FluidDomain domain = domain(126, false, bothWet
					? new float[]{.25F,.25F,.25F,.0625F, .75F,.75F,.75F,.03125F}
					: new float[]{.25F,.25F,.25F,.0625F});
			var before = domain.exportParticleState();
			var frame = GasRuntimeGeometry.capture(domain, new long[8], true);
			var contacts = GasPhaseInterfaceResolver.contacts(frame, 0);
			check(contacts.size() == (bothWet ? 2 : 1), "Disconnected dry microbox became a liquid contact");
			for (var contact : contacts) {
				check(contact.supportMask() == 1 || bothWet && contact.supportMask() == 128,
						"Contact crossed a fixed solid partition");
				check(contact.node()>0&&!contact.pieces().isEmpty(),"Wet fixed component lost its finite gas interface");
				for(int piece:contact.pieces()) check(piece/8==0&&(contact.supportMask()&1<<(piece&7))!=0
						&&frame.nodeOfPiece(piece)==contact.node()&&frame.geometry().piece(piece).volume()>0,
						"Gas interface crossed a fixed partition or lost positive capacity");
			}
			if(bothWet) check(contacts.get(0).node()!=contacts.get(1).node(),"Fixed solid partition joined independent gas chambers");
			check(Arrays.equals(before.copyPositionVolume(), domain.exportParticleState().copyPositionVolume())
					&& frame.matchesSourceCarrierGeometry(domain, new long[8]), "Contact observation changed its source stock");
		}
		// The reconstructed half-cell interface must contact positive dry capacity.
		var half = domain(0, false, new float[]{.5F,.25F,.5F,.5F});
		var halfFrame = GasRuntimeGeometry.capture(half, new long[8], true);
		var halfContacts = GasPhaseInterfaceResolver.contacts(halfFrame, 0);
		check(!halfContacts.isEmpty(),"Half-filled liquid lost its positive gas interface");
		for(var contact:halfContacts) {
			check(contact.supportMask()==255&&contact.node()>0&&!contact.pieces().isEmpty(),"Half-filled interface left its open fixed support");
			for(int piece:contact.pieces()) check(halfFrame.geometry().piece(piece).volume()>0
					&&halfFrame.nodeOfPiece(piece)==contact.node(),"Liquid interface selected gas without positive owned capacity");
		}

		var full = domain(0, true, new float[]{.5F,.5F,.5F,1});
		var fullFrame = GasRuntimeGeometry.capture(full, new long[8], true);
		var sharedContacts = GasPhaseInterfaceResolver.contacts(fullFrame, 0);
		check(!sharedContacts.isEmpty(),"Full liquid lost its shared wet/dry face contact");
		for(var contact:sharedContacts) {
			check(contact.supportMask()==255&&contact.node()>0&&!contact.pieces().isEmpty(),"Shared interface left its connected support");
			for(int piece:contact.pieces()) check(piece/8==1&&fullFrame.geometry().piece(piece).volume()>0
					&&fullFrame.nodeOfPiece(piece)==contact.node(),"Shared-face interface selected nonadjacent or empty gas capacity");
		}
		validateFiniteGasBatches();
		validateCondensedCellBatches();
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
				mmm.chemistry.ExactRational.of(mmm.units.Mass.fromKilograms(1000.0 * 168 / mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER)).divide(mmm.units.Mass.UNITS_PER_KILOGRAM),mmm.thermal.ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS,10000,3000000);
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
						new float[positions.length/4*3], mmm.units.Mass.createLiquid(.5F, 1000.0, positions),masks));domain.sealDomainBoundary();
				domain.bindGasRegistry(mmm.fluid.air.BuiltinGasDefinitions.createRegistry());domain.bootstrapGasState("");
				int node=domain.gasState.frame.commonCellNode(0);

				var phase=new mmm.thermal.GasLiquidPhaseModel(definitions,domain.gasState.network.registry());
				if(existingVapor) domain.gasState.network.commitPhaseMutation(domain.gasState.network.planPhaseSource(node,phase.gasBatch(transition,1)));
				domain.bindThermal(definitions,new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER)),completeDepletion||existingVapor?400:300);
				if(completeDepletion||existingVapor)domain.setThermalTemperatureInCell(0,0,0,300);
                double volumeBefore = domain.totalVolume();
                for (int step = 0; step < 3; step++) {
                    domain.stepGasPhaseChangeOnly(8);
                    check(domain.gasBoundary().auditClosed()
                                    && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
                            "Finite phase publication must conserve gas and internal energy");
                    domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
                }
                if (!existingVapor) check(domain.totalVolume() < volumeBefore,
                        "Heated finite liquid must vaporize and continue after restoration: source=" + sourceCase);



			}
		} finally { }
	}


	static void validateCondensedCellBatches() {
		for(boolean chemical:new boolean[]{false,true}) {
			var liquid=new mmm.thermal.ThermalMaterialDefinition("test:batch/liquid",mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,
					BuiltinFluidMaterials.WATER.id(),300,100000,0,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(250,400,1000)));
			var solid=new mmm.thermal.ThermalMaterialDefinition("test:batch/solid",mmm.thermal.ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID,
					"test:batch/solid",300,0,0,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(250,400,1000)));
			var transition=new mmm.thermal.ThermalPhaseTransition("test:batch/freeze",solid.id(),liquid.id(),300,1L<<21,1L<<20,
					mmm.chemistry.ExactRational.parse("7.8125"),mmm.thermal.ThermalPhaseTransition.InterfaceRequirement.NONE,0,200000);
			var air=new mmm.thermal.ThermalMaterialDefinition("test:batch/air",mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,
					mmm.fluid.air.BuiltinGasDefinitions.AIR,300,0,.025,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(150,800,718)));
			var offsets=new java.util.ArrayList<mmm.thermal.ThermalSpeciesOffset>();
			if(chemical)for(var species:mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY.species())for(var material:java.util.List.of(liquid,solid))
				offsets.add(new mmm.thermal.ThermalSpeciesOffset("test:batch/offset"+offsets.size(),species.id(),material.id(),mmm.chemistry.ExactRational.ZERO));
			var registry=new mmm.thermal.ThermalRegistry(java.util.List.of(liquid,solid,air),offsets,java.util.List.of(transition),
					chemical?mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY.fingerprint():"","");
			var domain=new FluidDomain(2,2,2,new FluidSolverConfig(.5F,1000,0,.01F,200,1e-5F,.5F,1e-4F,0),32);
			var fluids=new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER));domain.bindMaterialRegistry(fluids);domain.setVolume(0,0,0,.5F);
			if(chemical)domain.bindChemistry(mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY);
			domain.bindThermal(registry,fluids,275);
			long mass=domain.liquidCells.physicalStockAt(0).massUnits();
			var energy=condensedEnergy(domain);var species=domain.liquidCells.physicalStockAt(0).mixture();
			var frozen=domain.stepInertPhaseChangeOnly(16);
			check(frozen.operations()==2&&domain.coherentPhaseGroupCount()==1,"Cell freeze did not derive the macro equilibrium extent");
			long total=domain.liquidCells.physicalStocks().stream().mapToLong(LiquidCellInventory.Stock::massUnits).sum()+domain.thermalState.coherentPhases().totalMassUnits();
			check(total==mass&&domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())&&condensedEnergy(domain).equals(energy),"Cell freeze lost integer mass or latent energy: mass="+total+"/"+mass+", U="+domain.exactInternalEnergy()+", before="+energy+", audit="+domain.thermalAudit());
			domain=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			domain.setThermalTemperatureInCell(0,0,0,325);
			energy=condensedEnergy(domain);var melted=domain.stepInertPhaseChangeOnly(16);
			check(melted.operations()==2&&domain.coherentPhaseGroupCount()==0&&domain.liquidCells.physicalSize()==1,
					"Cell melt did not publish one continuing liquid inventory");
			check(domain.liquidCells.physicalStockAt(0).massUnits()==mass&&domain.liquidCells.exactCellVolume(0).equals(mmm.units.SignedDyadic.of(.5))
					&&condensedEnergy(domain).equals(energy)&&domain.thermalAudit().expectedInventory().equals(domain.exactInternalEnergy()),"Melt lost inventory or internal energy");
			if(chemical)check(domain.liquidCells.physicalStockAt(0).mixture().equals(species),"Freeze/melt changed chemical inventory or residual");
			check(domain.stepInertPhaseChangeOnly(16).operations()==0,"Equilibrated cell repeated a phase transfer");
		}
	}

	private static mmm.units.SignedDyadic condensedEnergy(FluidDomain domain) {
		var energy=domain.thermalState.coherentPhases().exactEnergy();
		for(var stock:domain.liquidCells.physicalStocks())energy=energy.add(mmm.units.SignedDyadic.of(stock.internalEnergy()));
		return energy;
	}

	private static FluidDomain domain(int mask, boolean peerOpen, float[] markers) {
		byte[] masks = new byte[8]; Arrays.fill(masks, (byte)255); masks[0] = (byte) mask;
		if (peerOpen) masks[1] = 0;
		float total = 0;
		for (int i = 3; i < markers.length; i += 4) total += markers[i];
		FluidDomain domain = new FluidDomain(2, 2, 2,
				new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.5F,1e-4F,0), 32);
		domain.restoreParticleState(new FlipParticleState(2,2,2,1,markers.length/4,total,markers,
				new float[markers.length/4*3], mmm.units.Mass.createLiquid(1, 1000.0, markers),masks));
		domain.sealDomainBoundary();
		return domain;
	}

	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
