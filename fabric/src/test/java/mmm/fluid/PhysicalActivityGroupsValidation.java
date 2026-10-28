package mmm.fluid;

import java.util.List;

/** Independent compartments keep sleeping inventories while a local input advances its neighbour. */
public final class PhysicalActivityGroupsValidation {
	private PhysicalActivityGroupsValidation() { }

	public static void main(String[] args) {
		validateSlowGasPort();
        validateUnavailableGroup();
		FluidDomain domain = compartments();
		for (int step = 0; step < 210; step++) domain = advance(domain, step);
		var asleep = domain.activityStats();
		require(asleep.groupCount() == 2 && asleep.sleepingGroups() == 2,
				"Separated stationary compartments did not sleep: " + asleep);
		var right = rightStocks(domain);
		var momentum = domain.mac.captureMomentum();
		for (int query = 0; query < 5; query++) {
			domain.activityStats(); domain.totalVolumeExact(); domain.maximumPressure();
		}
		require(domain.activityStats().equals(asleep), "Read-only observations woke a compartment");
		domain.setVolume(1, 1, 1, .75F);
		domain = advance(domain, 211);
		require(domain.activityStats().sleepingGroups() == 1,
				"Local water input woke an unrelated compartment: " + domain.activityStats());
		domain.domainFlowFields.preparePhysicalState();
		for (int face = 0; face < domain.mac.velocityX.length; face++)
			if (face % (domain.sizeX + 1) > 0 && face % (domain.sizeX + 1) < 4)
				domain.mac.velocityX[face] = .1F;
		domain.cellMacStage.publishMomentum();
		double beforeX = leftCentre(domain);
		long mass = domain.totalMassUnits();
		for (int step = 0; step < 8; step++) domain = advance(domain, 212 + step);
		require(leftCentre(domain) > beforeX, "Awake compartment did not advance its water");
		require(domain.totalMassUnits() == mass, "Independent advance changed total mass");
		require(rightStocks(domain).equals(right), "Sleeping liquid inventory changed");
		assertRightMomentum(momentum, domain.mac.captureMomentum());
		require(domain.activityStats().sleepingGroups() == 1, "Neighbour lost its independent sleep");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.activityStats().equals(domain.activityStats()), "Candidate handoff lost activity state");
		domain.replaceActivityConnections(List.of(new int[]{cell(1, 1, 1), cell(6, 1, 1)}));
		domain = advance(domain, 230);
		require(domain.activityStats().groupCount() == 1, "Shared finite device did not join its compartments");
		domain.replaceActivityConnections(List.of());
		domain.setSolid(4, 1, 1, false);
		domain = advance(domain, 231);
		require(domain.activityStats().groupCount() == 1, "An opened wall retained disconnected activity groups");
		System.out.println("Independent physical activity, local wake, shared device and geometry merge passed");
	}
    private static void validateUnavailableGroup() {
        var domain=compartments(.5F);
        var registry=mmm.granular.BuiltinGranularDefinitions.createRegistry();
        domain.bindGranular(registry,new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER)),BuiltinFluidMaterials.createRegistry());
        int sand=registry.materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND);
        long svq=registry.material(sand).maximumActiveParcelSvq();
        long mass=mmm.units.Mass.fromKilograms(registry.material(sand).referenceDensity()*svq/mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER);
        domain.granularState.parcels.add(new mmm.granular.GranularParcelStore.Parcel(3.25F,1.25F,.75F,.03F,.02F,0,svq,mass,sand,0,9,(byte)0));
        domain.granularCoupling.rebuild(domain.granularState.parcels,domain.granularState.deposits);
        domain.domainPhaseInventory.markGranularExternalChange(cell(6,2,1));
        domain.domainFlowFields.preparePhysicalState();
        for(int face=0;face<domain.mac.velocityX.length;face++)if(face%10>0&&face%10<4)domain.mac.velocityX[face]=.1F;
        domain.cellMacStage.publishMomentum();

        boolean[] available=new boolean[domain.cellCount];java.util.Arrays.fill(available,true);available[cell(6,3,1)]=false;
        domain.prepareGroupAvailability(available);
        require(domain.groupAvailable(cell(1,1,1))&&!domain.groupAvailable(cell(6,1,1)),"Missing world input did not pause exactly its connected group");
        var loadedBody = new mmm.world.MovingBodyFluidCoupling.Bounds(.55, .55, .55, .65, .65, .65);
        var unloadedBody = new mmm.world.MovingBodyFluidCoupling.Bounds(3.05, .55, .55, 3.15, .65, .65);
        require(domain.activityGroups.bodyAvailable(loadedBody) && !domain.activityGroups.bodyAvailable(unloadedBody),
                "Entity occupancy did not follow the loaded physical group");
        require(domain.activityStats().blockedGroups()==1&&domain.activityStats().blockedCells()>0,"Unavailable groups were reported as sleep");
        var right=rightStocks(domain);var parcel=domain.granularState.parcels.parcel(0);var momentum=domain.mac.captureMomentum();
        int node=domain.gasState.frame.nodeOfPiece(8*cell(6,3,1));var gas=domain.gasState.network.inventory(node);
        double before=leftCentre(domain);
        for(int step=0;step<3;step++)domain=advance(domain,300+step);
        require(leftCentre(domain)>before,"Available group failed to advance while its neighbour lacked world input");
        require(rightStocks(domain).equals(right)&&domain.granularState.parcels.parcel(0).equals(parcel),"Unavailable liquid or granular state changed");
        require(domain.gasState.network.inventory(node).equals(gas),"Unavailable gas inventory changed");

        assertRightMomentum(momentum,domain.mac.captureMomentum());
        require(!domain.groupAvailable(cell(6,1,1)),"Candidate handoff lost unavailable groups");
        var rejected=domain.tryInsertExactVolume(6,1,1,.1F,8);
        require(!rejected.transferred(),"An unavailable edit target searched into an available group");
        java.util.Arrays.fill(available,true);domain.prepareGroupAvailability(available);
        require(domain.groupAvailable(cell(6,1,1))&&domain.activityStats().blockedGroups()==0,"Reloaded world input retained a paused group");
        require(domain.activityGroups.bodyAvailable(unloadedBody), "Entity remained blocked after world input returned");
    }

	private static void validateSlowGasPort() {
		var domain=compartments(); var network=domain.gasState.network;
		int left=domain.gasState.frame.nodeOfPiece(8*cell(1,2,1));
		int right=domain.gasState.frame.nodeOfPiece(8*cell(6,2,1));
		require(left>0 && right>0 && left!=right,"Slow-port fixture did not preserve separate finite chambers");
		network.setStandardVolume(left,network.standardVolume(left)+.001);
		network.addOrMergeEdge(left,0,1e-30,.65);
		var before=network.inventory(left); var other=network.inventory(right);
		var source=network.externalSource(); var sink=network.escapedSink();
		for(int step=0;step<210;step++) {
			domain.activityGroups.beginStep();
			require(domain.activityGroups.mechanicsActive(cell(1,2,1)) && domain.activityGroups.thermalActive(cell(1,2,1)),
					"Finite gas pressure drive slept before its representable equilibrium transfer ended");
			network.stepEdges(.025,domain.activityGroups::gasNodeIsActive);
			domain.activityGroups.finishStep(true,true);
		}
		require(domain.activityStats().sleepingGroups()==1,"A slow ambient gas port woke an unrelated compartment");
		require(network.inventory(left).equals(before) && network.inventory(right).equals(other)
				&& network.externalSource().equals(source) && network.escapedSink().equals(sink),
				"Subquantum port fixture changed inventory or material audits");
		network.removeEdge(left,0);
		for(int step=0;step<210;step++) { domain.activityGroups.beginStep(); domain.activityGroups.finishStep(true,true); }
		require(domain.activityStats().sleepingGroups()==2,"Closing the last pressure port did not permit finite gas sleep");
	}

	private static FluidDomain compartments() {
		return compartments(1);
	}
	private static FluidDomain compartments(float cellSize) {
		var domain = new FluidDomain(9, 4, 4, new FluidSolverConfig(cellSize, 1000, 0, .025F, 120, 1e-5F, .002F));
		domain.sealDomainBoundary();
		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) domain.setSolid(4, y, z, true);
		domain.setVolume(1, 1, 1, .5F); domain.setVolume(6, 1, 1, .5F);
		domain.bootstrapGasState(null);
		return domain;
	}
	private static FluidDomain advance(FluidDomain domain, long generation) {
		return new CpuFluidSolverBackend().evaluate(new FluidStepRequest(generation,
				domain.captureAuthoritativeState(), List.of(), false, 0)).claimPreparedCpuDomain();
	}
	private static List<mmm.inventory.LiquidCellInventory.Stock> rightStocks(FluidDomain domain) {
		return domain.liquidCells.stocks().stream().filter(stock -> stock.key().cell() % 9 > 4).toList();
	}
	private static double leftCentre(FluidDomain domain) {
		double weighted = 0, volume = 0;
		for (var stock : domain.liquidCells.stocks()) if (stock.key().cell() % 9 < 4) {
			weighted += stock.volumeFraction() * (stock.key().cell() % 9 + .5); volume += stock.volumeFraction();
		}
		return weighted / volume;
	}
	private static void assertRightMomentum(MacMomentumState before, MacMomentumState after) {
		for (int axis = 0; axis < 3; axis++) {
			double[] first = axis == 0 ? before.copyX() : axis == 1 ? before.copyY() : before.copyZ();
			double[] second = axis == 0 ? after.copyX() : axis == 1 ? after.copyY() : after.copyZ();
			for (int face = 0; face < first.length; face++) if (face % (axis == 0 ? 10 : 9) > 4)
				require(Double.doubleToRawLongBits(first[face]) == Double.doubleToRawLongBits(second[face]),
						"Sleeping MAC momentum changed");
		}
	}
	private static int cell(int x, int y, int z) { return (z * 4 + y) * 9 + x; }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
