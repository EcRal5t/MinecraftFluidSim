package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import java.util.Arrays;

/** Phase contacts follow positive gas capacity and disconnected fixed supports. */
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
			var first=GasPhaseInterfaceResolver.contacts(frame,0);
			var second=GasPhaseInterfaceResolver.contacts(frame,7);
			check(first.size()==1&&second.size()==(bothWet?1:0),"Separate dry compartment became a liquid contact");
			for(var contact:first)check(contact.supportMask()==1&&contact.node()>0&&!contact.components().isEmpty(),"First compartment lost its owned interface");
			for(var contact:second)check(contact.supportMask()==128&&contact.node()>0&&!contact.components().isEmpty(),"Second compartment lost its owned interface");
			if(bothWet)check(first.getFirst().node()!=second.getFirst().node(),"Fixed wall joined separate gas chambers");
			check(Arrays.equals(before.copyPositionVolume(), domain.exportParticleState().copyPositionVolume())
					&&frame.matchesSourceCarrierGeometry(domain,new long[8]),"Contact observation changed its source stock");
		}
		// The reconstructed half-cell interface must contact positive dry capacity.
		var half = domain(0, false, new float[]{.5F,.25F,.5F,.5F});
		var halfFrame = GasRuntimeGeometry.capture(half, new long[8], true);
		var halfContacts = GasPhaseInterfaceResolver.contacts(halfFrame, 0);
		check(!halfContacts.isEmpty(),"Half-filled liquid lost its positive gas interface");
		for(var contact:halfContacts) {
			check(contact.supportMask()==255&&contact.node()>0&&!contact.components().isEmpty(),"Half-filled interface left its open fixed support");
			for(var component:contact.components()) check(component.volume()>0
					&&halfFrame.topology().nodeOfComponent(component.cell(),component.supportMask())==contact.node(),"Liquid interface selected gas without positive owned capacity");
		}

		var full = domain(0, true, new float[]{.5F,.5F,.5F,1});
		var fullFrame = GasRuntimeGeometry.capture(full, new long[8], true);
		var sharedContacts = GasPhaseInterfaceResolver.contacts(fullFrame, 0);
		check(!sharedContacts.isEmpty(),"Full liquid lost its shared wet/dry face contact");
		for(var contact:sharedContacts) {
			check(contact.supportMask()==255&&contact.node()>0&&!contact.components().isEmpty(),"Shared interface left its connected support");
			for(var component:contact.components()) check(component.cell()==1&&component.volume()>0
					&&fullFrame.topology().nodeOfComponent(component.cell(),component.supportMask())==contact.node(),"Shared-face interface selected nonadjacent or empty gas capacity");
		}
		validateRestrictedPhaseTransport();
		validateFiniteGasBatches();
		validateCondensedCellBatches();
		System.out.println("Gas phase component contact validation passed");
	}

	/** Two half-filled supports keep separate gas inventories while liquid crosses a fixed orifice. */
	private static void validateRestrictedPhaseTransport(){
		var domain=new FluidDomain(2,2,2,new FluidSolverConfig(1,1000,0,.01F,120,1e-5F,.002F));
		// Empty supports above establish a resolved vertical free-surface gradient.
		// The dry aperture must survive the small accepted horizontal liquid flux.
		byte[] masks=new byte[8];Arrays.fill(masks,(byte)255);masks[0]=masks[1]=masks[2]=masks[3]=(byte)0xf0;
		float[] points={.5F,.25F,.25F,.25F,1.5F,.25F,.25F,.25F};
		domain.restoreParticleState(new FlipParticleState(2,2,2,1,2,.5F,points,new float[6],
				mmm.units.Mass.createLiquid(1,1000,points),masks));
		domain.sealDomainBoundary();domain.domainFlowFields.preparePhysicalState();
		int first=domain.gasState.frame.commonCellNode(0),second=domain.gasState.frame.commonCellNode(1);
		check(first>0&&second>0&&first!=second,
				"Restricted two-phase fixture lost its separate pressure nodes");
		requireSharedGasPort(domain,first,second);
		domain.setGasNodeStandardVolume(first,.5);domain.setGasNodeStandardVolume(second,.125);
		var source=domain.gasState.network.inventory(first);var target=domain.gasState.network.inventory(second);
		var total=domain.gasState.network.sealedInventory();
		long liquidMass=domain.totalMassUnits();var liquidVolume=domain.liquidCells.exactVolume();
		domain.domainFlowFields.preparePhysicalState();
		domain.mac.velocityX[domain.domainFlowFields.xFaceIndex(1,0,0)]=.01F;
		domain.cellMacStage.publishMomentum();domain.pressureSystem.prepareOperator();
		var flow=new ConservativeLiquidFlux(domain);flow.prepare(.01F,new long[8]);
		var liquid=flow.prepareInventory(domain.liquidCells);
		check(!liquid.transfers().isEmpty(),"Restricted phase fixture did not exercise actual liquid transport");
		var gas=domain.gasTopologyCoordinator.planAfterLiquidDisplacement(liquid.future(),new long[8],flow.gasAllocation());
		domain.gasTopologyCoordinator.requireBeforeCarrierCommit(gas);liquid.commit();domain.markCellInventoryChanged();
		domain.gasTopologyCoordinator.commitPrepared(gas,new long[8]);
		first=domain.gasState.frame.commonCellNode(0);second=domain.gasState.frame.commonCellNode(1);
		check(first!=second&&domain.gasState.network.inventory(first).equals(source)
				&&domain.gasState.network.inventory(second).equals(target),
				"A small carrier flux mixed complete reservoirs across the restricted gas port");
		check(domain.totalMassUnits()==liquidMass&&domain.liquidCells.exactVolume().equals(liquidVolume),
				"Restricted two-phase transport changed exact liquid mass or volume");
		requireSharedGasPort(domain,first,second);
		check(domain.gasState.network.gaugePressure(first)>domain.gasState.network.gaugePressure(second),
				"Restricted phase transport reversed its prescribed gas pressure load");
		domain.gasTopologyCoordinator.step(1e-6F);
		long moved=domain.gasState.network.standardVolumeNanos(second)-target.standardVolumeNanos();
		check(moved>0&&moved<source.standardVolumeNanos()/1000,
				"Restricted gas port did not retain a finite actual flow: moved="+moved);
		check(domain.gasState.network.sealedInventory().equals(total)&&domain.gasState.network.auditClosed(),
				"Restricted gas flow changed the complete gas inventory");
	}

	private static void requireSharedGasPort(FluidDomain domain,int first,int second){
		for(var edge:domain.gasState.network.edges())
			if((edge.firstNode()==first&&edge.secondNode()==second||edge.firstNode()==second&&edge.secondNode()==first)
					&&edge.areaSquareMeters()>0&&edge.areaSquareMeters()<1)return;
		throw new AssertionError("Restricted two-phase fixture has no actual shared dry port after reconstruction");
	}

	private static void validateFiniteGasBatches() {
		var water=new mmm.thermal.ThermalMaterialDefinition("test:finite-air/water",mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,
				BuiltinFluidMaterials.WATER.id(),373.15,0,.1,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(250,300,2000),
						new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(300,800,4180)));
		var vapor=new mmm.thermal.ThermalMaterialDefinition("test:finite-air/vapor",mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,
				mmm.fluid.air.BuiltinGasDefinitions.WATER_VAPOR,373.15,2256000,.1,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(250,800,1410)));
		var air=new mmm.thermal.ThermalMaterialDefinition("test:finite-air/air",mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,
				mmm.fluid.air.BuiltinGasDefinitions.NITROGEN,273.15,0,.025,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(150,800,718)));
		var transition=new mmm.thermal.ThermalPhaseTransition("test:finite-air/boil",water.id(),vapor.id(),373.15,168,168*1700L,
				mmm.chemistry.ExactRational.of(mmm.units.Mass.fromKilograms(1000.0 * 168 / mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER)).divide(mmm.units.Mass.UNITS_PER_KILOGRAM),mmm.thermal.ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS,10000,3000000);
		var registry=ThermalValidationSupport.thermalRegistry(ThermalValidationSupport.thermalMaterials(java.util.List.of(water,vapor,air)),java.util.List.of(),java.util.List.of(transition),"","");
		var dry=new FluidDomain(2,2,2,new FluidSolverConfig(.5F,1000,0,.01F,200,1e-5F,1e-4F));
		for(int cell=1;cell<8;cell++)dry.setSolid(cell%2,cell/2%2,cell/4,true);
		dry.sealDomainBoundary();dry.bindMaterialRegistry(new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER)));
		dry.bindGasRegistry(mmm.fluid.air.BuiltinGasDefinitions.createRegistry());dry.bootstrapGasState("");
		dry.commitOperatorVentSealedGas(dry.planOperatorVentSealedGas());
		dry.gasState.network.commitPhaseSource(1,new mmm.thermal.GasLiquidPhaseModel(registry,dry.gasState.network.registry()).gasBatch(transition,128));
		dry.bindThermal(registry,dry.materialRegistry,300);
		long mass=dry.gasState.network.inventory(1).totalMassUnits();
		var condensed=dry.stepGasPhaseChangeOnly(16);
		check(condensed.condensedCells()>0&&dry.totalVolumeExact()>0&&dry.totalMassUnits()+dry.gasState.network.inventory(1).totalMassUnits()==mass
				&&dry.exactInternalEnergy().equals(dry.thermalAudit().expectedInventory()),"Supersaturated dry chamber failed to nucleate conserving condensate");

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
					definitions=ThermalValidationSupport.thermalRegistry(ThermalValidationSupport.thermalMaterials(java.util.List.of(equalEnergyWater,equalEnergyVapor,hotAir)),java.util.List.of(),java.util.List.of(transition),"","");
				}
				byte[] masks=new byte[8];Arrays.fill(masks,(byte)255);masks[0]=0;if(partialNeighbor)masks[1]=0;if(mergingPeers)for(int peer:new int[]{1,2,4})masks[peer]=0;
				var domain=new FluidDomain(2,2,2,new FluidSolverConfig(.5F,1000,0,.01F,200,1e-5F,1e-4F));
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
                var nonWaterBefore=domain.gasState.network.sealedInventory().entries().stream()
                        .filter(entry->!entry.componentId().equals(mmm.fluid.air.BuiltinGasDefinitions.WATER_VAPOR)).toList();
                check(nonWaterBefore.stream().map(mmm.fluid.air.GasAuditLedger.Entry::componentId).collect(java.util.stream.Collectors.toSet())
                        .equals(java.util.Set.of(mmm.fluid.air.BuiltinGasDefinitions.NITROGEN,mmm.fluid.air.BuiltinGasDefinitions.OXYGEN,mmm.fluid.air.BuiltinGasDefinitions.CO2)),
                        "Default phase fixture lost the complete dry mixture");
                double volumeBefore = domain.totalVolume();
                for (int step = 0; step < 3; step++) {
                    domain.stepGasPhaseChangeOnly(8);
                    if(completeDepletion&&step==0){check(domain.totalVolumeExact()==0,"Complete vaporization retained liquid");checkpointVapor(domain,definitions);}
                    check(domain.gasState.network.sealedInventory().entries().stream()
                                    .filter(entry->!entry.componentId().equals(mmm.fluid.air.BuiltinGasDefinitions.WATER_VAPOR)).toList().equals(nonWaterBefore),
                            "Water phase exchange changed nitrogen, oxygen or carbon dioxide inventory");
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


	private static void checkpointVapor(FluidDomain domain,mmm.thermal.ThermalRegistry thermal){
		try{
			var savedGas=domain.gasBoundary().sealedInventory();
			check(savedGas.entries().stream().anyMatch(entry->entry.componentId().equals(mmm.fluid.air.BuiltinGasDefinitions.WATER_VAPOR)),
					"Vapor checkpoint must retain a nondefault wet composition");
			var origin=net.minecraft.core.BlockPos.ZERO;var controller=new net.minecraft.core.BlockPos(-1,0,0);
			var footprint=new mmm.simulation.controller.FluidRegionFootprint(controller,origin,origin,java.util.Set.of(origin.asLong()),java.util.Set.of(controller.asLong()),java.util.Set.of(0L));
			net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension=net.minecraft.resources.ResourceKey.create(
					net.minecraft.resources.ResourceKey.createRegistryKey(net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm","phase_checkpoint_dimensions")),
					net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm","phase_checkpoint"));
			var region=new mmm.simulation.FluidRegion(java.util.UUID.randomUUID(),dimension,origin,domain,footprint);
			var saved=mmm.simulation.RegionCheckpointFixtures.captureThermal(region,1);saved.validate();
			var restored=new mmm.simulation.persistence.RegionCheckpointRestorer(saved.checkpoint()).reconstructAuthoritativeState(null,null,thermal,domain.gasState.network.registry(),BuiltinFluidMaterials.createRegistry());
			var next=FluidDomain.restoreAuthoritativeState(restored);
			check(next.totalVolumeExact()==0&&next.hasPendingPhaseMotionWork()&&next.exactInternalEnergy().equals(domain.exactInternalEnergy())
					&&next.gasBoundary().sealedInventory().equals(domain.gasBoundary().sealedInventory()),"Actual fully vaporized checkpoint lost gas, energy or pending motion");
			next.stepMechanicsOnly();check(!next.hasPendingPhaseMotionWork()&&next.gasBoundary().sealedInventory().equals(savedGas),
					"Restored wet mixture changed its composition during mechanical advancement");
		}catch(java.io.IOException failed){throw new AssertionError("Actual vaporized state failed durable checkpoint",failed);}
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
					mmm.fluid.air.BuiltinGasDefinitions.NITROGEN,300,0,.025,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(150,800,718)));
			var offsets=new java.util.ArrayList<mmm.thermal.ThermalSpeciesOffset>();
			if(chemical)for(var species:mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY.species())for(var material:java.util.List.of(liquid,solid))
				offsets.add(new mmm.thermal.ThermalSpeciesOffset("test:batch/offset"+offsets.size(),species.id(),material.id(),mmm.chemistry.ExactRational.ZERO));
			var registry=ThermalValidationSupport.thermalRegistry(ThermalValidationSupport.thermalMaterials(java.util.List.of(liquid,solid,air)),offsets,java.util.List.of(transition),
					chemical?mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY.fingerprint():"","");
			var domain=new FluidDomain(2,2,2,new FluidSolverConfig(.5F,1000,0,.01F,200,1e-5F,1e-4F));
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
			var isolated=new FluidDomain(4,2,2,new FluidSolverConfig(.5F,1000,0,.01F,200,1e-5F,1e-4F));
			isolated.bindMaterialRegistry(fluids);for(int cell=0;cell<isolated.cellCount;cell++)if(cell!=0&&cell!=2)isolated.setSolid(cell%4,cell/4%2,cell/8,true);
			isolated.setVolume(0,0,0,1);isolated.setVolume(2,0,0,.5F);
			if(chemical)isolated.bindChemistry(mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY);
			isolated.bindThermal(registry,fluids,275);
			var blockedBefore=isolated.liquidCells.stocksInCell(0);var isolatedEnergy=condensedEnergy(isolated);
			long isolatedMass=isolated.totalMassUnits();
			var independent=isolated.stepInertPhaseChangeOnly(16);
			check(independent.operations()>0&&independent.frozenCells()==1&&isolated.liquidCells.stocksInCell(0).equals(blockedBefore)
					&&isolated.thermalState.coherentPhases().snapshot().stream().allMatch(group->group.cell()==2),
					"Blocked isolated freezing prevented an independent executable phase transfer");
			check(isolated.totalMassUnits()+isolated.thermalState.coherentPhases().totalMassUnits()==isolatedMass
					&&condensedEnergy(isolated).equals(isolatedEnergy)&&isolated.exactInternalEnergy().equals(isolated.thermalAudit().expectedInventory()),
					"Independent freezing changed total mass or latent energy");

		}
	}

	private static mmm.units.SignedDyadic condensedEnergy(FluidDomain domain) {
		var energy=domain.thermalState.coherentPhases().exactEnergy();
		for(var stock:domain.liquidCells.physicalStocks())energy=energy.add(mmm.units.SignedDyadic.of(stock.internalEnergy()));
		return energy;
	}

	private static FluidDomain domain(int mask, boolean peerOpen, float[] markers) {
		markers=markers.clone();
		boolean separate=mask==126;
		if(separate)for(int marker=0;marker<markers.length/4;marker++)
			if(markers[4*marker]>.5F)for(int axis=0;axis<3;axis++)markers[4*marker+axis]+=1;
		byte[] masks = new byte[8]; Arrays.fill(masks, (byte)255); masks[0] = (byte)(separate?254:mask);if(separate)masks[7]=(byte)127;
		if (peerOpen) masks[1] = 0;
		float total = 0;
		for (int i = 3; i < markers.length; i += 4) total += markers[i];
		FluidDomain domain = new FluidDomain(2, 2, 2,
				new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,1e-4F));
		domain.restoreParticleState(new FlipParticleState(2,2,2,1,markers.length/4,total,markers,
				new float[markers.length/4*3], mmm.units.Mass.createLiquid(1, 1000.0, markers),masks));
		domain.sealDomainBoundary();
		return domain;
	}

	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
