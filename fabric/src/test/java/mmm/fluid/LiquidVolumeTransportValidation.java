package mmm.fluid;

import mmm.inventory.LiquidCellInventory;


import mmm.units.SignedDyadic;

/** Exact inventory, default-path handoff continuity and independent transport convergence. */
public final class LiquidVolumeTransportValidation {
	private final int n;
	private final double h;
	private final double[] volume;
	private final boolean[] solid;
	private final SignedDyadic[] exactVolume;
	private final ConservativeLiquidFlux transport;

	private LiquidVolumeTransportValidation(int n) {
		this.n = n; h = 6.0 / n;
		volume = new double[n * n]; solid = new boolean[n * n]; exactVolume = new SignedDyadic[n * n];
		transport = new ConservativeLiquidFlux(n, n, 1);
		int f = 0;
		for (int y = 0; y < n; y++) for (int x = 1; x < n; x++) {
			transport.velocity[f++] = (stream(x * h, (y + 1) * h) - stream(x * h, y * h)) / h;
		}
		for (int y = 1; y < n; y++) for (int x = 0; x < n; x++) {
			transport.velocity[f++] = (stream(x * h, y * h) - stream((x + 1) * h, y * h)) / h;
		}
		for (int y = 0; y < n; y++) for (int x = 0; x < n / 2; x++) volume[y * n + x] = 1;
		for (int c = 0; c < volume.length; c++) exactVolume[c] = SignedDyadic.of(volume[c]);
	}

	private void step(double dt) {
		transport.plan(volume, solid, dt / h, exactVolume);
		System.arraycopy(transport.resultingVolumes(), 0, exactVolume, 0, volume.length);
		for (int c = 0; c < volume.length; c++) volume[c] = exactVolume[c].toDouble();
	}
	private static double stream(double x, double y) {
		double r2 = (x - 3) * (x - 3) + (y - 3) * (y - 3);
		return r2 >= 6.25 ? 0 : 6.25 * Math.pow(1 - r2 / 6.25, 3);
	}

	public static void main(String[] args) {
		String entry = args.length == 0 ? "--fast" : args[0];
		if (args.length > 1) throw new IllegalArgumentException("Expected one liquid transport selector");
		switch (entry) {
			case "swirl" -> { convergence(); return; }
			case "chemical-tails", "components" -> disconnectedComponentsAndTraceChemistry();
			case "external" -> externalQuantityTransactions();
			case "moving" -> movingMaterialContinuesAfterRecovery();
			case "courant" -> {connectedDonorCourantMatchesFaceFlux();saturatedInteriorCannotCreateVacuum();}
			case "--fast", "mass", "quantization", "representation", "geometry-remap", "handoff", "handoff-short", "continuity" -> {
				zeroFluxSharesInventory(); disconnectedComponentsAndTraceChemistry(); externalQuantityTransactions();
				sharedCapacityDisplacement(); componentCapacityMovesItsOwnStock(); connectedDonorCourantMatchesFaceFlux(); saturatedInteriorCannotCreateVacuum(); finiteMovingPoreCapacity(); finiteFaceFluxNeedsNoSamplingFloor(); movingMaterialContinuesAfterRecovery();
			}
			default -> throw new IllegalArgumentException("Unknown liquid transport selector " + entry);
		}
		System.out.println("Cell liquid transport passed: " + entry);
	}
	private static void connectedDonorCourantMatchesFaceFlux(){
		var d=domain(2,2,2);var masks=new byte[8];masks[0]=(byte)126;d.geometry.cutCells.restoreMicroMasks(masks);
		var key=new mmm.inventory.LiquidCellInventory.Key(0,0,128);
		d.liquidCells.planAdditions(java.util.List.of(new mmm.inventory.LiquidCellInventory.Stock(key,SignedDyadic.of(.0625),
				d.unitScale.massUnitsForNormalized(62.5),null,0,false))).commit();d.markCellInventoryChanged();d.ensureGrid();
		d.mac.velocityX[d.xFaceIndex(1,0,0)]=.5F;d.mac.velocityY[d.yFaceIndex(0,1,0)]=.5F;d.mac.velocityZ[d.zFaceIndex(0,0,1)]=.5F;d.cellMacStage.publishMomentum();
		var flow=new CellLiquidVolumeTransport(d);
		require(Math.abs(flow.courantRate(0)-6)<1e-12&&Math.abs(flow.projectedCourantRate()-6)<1e-12,
				"Connected donor time scale did not use all actual outlet fluxes and its own pore volume");
		var flux=flow.prepareVolumeFlux(.1F);double outgoing=0;
		for(var port:flux.ports())if(port.donorCell()==0&&port.donorSupport()==128)outgoing+=port.volume().toDouble();
		require(Math.abs(outgoing/.0625-flow.projectedCourantRate()*.1F)<1e-12&&outgoing<.0625,
				"Courant estimate differs from actual shared coefficient face transport");
		d.mac.velocityX[d.xFaceIndex(1,0,0)]=-.5F;d.mac.velocityY[d.yFaceIndex(0,1,0)]=-.5F;d.mac.velocityZ[d.zFaceIndex(0,0,1)]=-.5F;d.cellMacStage.publishMomentum();
		var reversed=flow.prepareVolumeFlux(.1F);
		require(reversed.ports().isEmpty()&&reversed.resultingVolumes()[0].equals(SignedDyadic.of(.0625)),
				"A velocity reversal drained liquid from the actual dry donor");
	}
	private static void saturatedInteriorCannotCreateVacuum(){
		var d=domain(4,1,1);d.sealDomainBoundary();d.setVolumes(new float[]{1,1,1,1});d.ensureGrid();
		d.mac.velocityX[d.xFaceIndex(1,0,0)]=.1F;d.cellMacStage.publishMomentum();
		var before=d.liquidCells.snapshot();var plan=new CellLiquidVolumeTransport(d).prepare(.1F);
		for(int cell=0;cell<4;cell++)require(plan.inventory().future().exactCellVolume(cell).equals(SignedDyadic.of(1)),
				"A finite mixture-divergence residual created vacuum inside saturated liquid");
		require(plan.inventory().future().massUnits()==d.liquidCells.massUnits()&&plan.inventory().future().exactVolume().equals(d.liquidCells.exactVolume())
				&&before.rows().equals(d.liquidCells.snapshot().rows()),"Saturated-cell lower capacity repair created stock or modified source");
	}
	/** The projected pressure flux linearizes pore motion; accepted integer endpoints
	 * can require a finite correction even with a stationary predicted liquid face. */
	private static void finiteMovingPoreCapacity() {
		var d=domain(3,3,3);var granular=mmm.granular.BuiltinGranularDefinitions.createRegistry();
		d.bindGranular(granular,new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER)),BuiltinFluidMaterials.createRegistry());
		int material=granular.materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND);
		long svq=granular.material(material).maximumActiveParcelSvq();
		long mass=mmm.units.Mass.fromKilograms(granular.material(material).referenceDensity()*Math.scalb((double)svq,-27));
		d.granularState.parcels.add(new mmm.granular.GranularParcelStore.Parcel(.999F,.75F,.75F,0,0,0,svq,mass,material,0,0,(byte)0));
		d.granularCoupling.rebuild(d.granularState.parcels,d.granularState.deposits);
		var stocks=new java.util.ArrayList<LiquidCellInventory.Stock>();
		for(int cell=0;cell<d.cellCount;cell++) if(cell/3%3<2) {
			var volume=SignedDyadic.of(1).subtract(SignedDyadic.of(d.granularCoupling.solidSvq(cell),-24));
			var key=new LiquidCellInventory.Key(cell,0,255);
			stocks.add(new LiquidCellInventory.Stock(key,volume,d.unitScale.massUnitsForNormalized(volume.toDouble()*1000),null,0,false));
		}
		d.liquidCells.planAdditions(stocks).commit();d.markCellInventoryChanged();d.ensureGrid();
		d.granularNextCoupling.rebuild(d.granularState.parcels,d.granularState.deposits,
				(index,parcel)->new mmm.granular.GranularCouplingFields.Position(parcel.x()+.0005F,parcel.y(),parcel.z(),parcel.ceilMask()));
		var before=d.liquidCells.snapshot();var prepared=new CellLiquidVolumeTransport(d).prepare(.05F);
		require(prepared.volumeFlux().capacityCorrectionVolume()>0,"Finite pore motion did not exercise shared capacity projection");
		require(prepared.inventory().future().massUnits()==d.liquidCells.massUnits()
				&&prepared.inventory().future().exactVolume().equals(d.liquidCells.exactVolume())
				&&before.rows().equals(d.liquidCells.snapshot().rows()),"Finite capacity projection changed source or exact inventory");
		for(int cell=0;cell<d.cellCount;cell++) {
			var capacity=SignedDyadic.of(1).subtract(SignedDyadic.of(d.granularNextCoupling.solidSvq(cell),-24));
			require(prepared.inventory().future().exactCellVolume(cell).compareTo(capacity)<=0,"Finite capacity projection left an overfull endpoint");
		}
	}

	private static FluidDomain domain(int x, int y, int z) {
		return new FluidDomain(x, y, z, new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1e-5F, .4F, .002F, 0), 8*x*y*z);
	}
	static void zeroFluxPreservesCarrierColumns() { zeroFluxSharesInventory(); }
	private static void zeroFluxSharesInventory() {
		var d = domain(4, 3, 2); d.setVolume(1, 1, 1, .75F);
		var before = d.liquidCells.snapshot(); var prepared = new CellLiquidVolumeTransport(d).prepare(.05F);
		require(prepared.faceFluxes().isEmpty() && prepared.inventory().future().snapshot().rows().equals(before.rows()), "Zero MAC flux altered cell quantities");
		require(d.liquidCells.snapshot().rows().equals(before.rows()), "Preparation changed committed inventory");
		prepared.inventory().commit();
		require(d.liquidCells.snapshot().rows().get(d.cellIndex(1,1,1)) == before.rows().get(d.cellIndex(1,1,1)), "Unchanged row was rebuilt instead of shared");
	}
	private static void disconnectedComponentsAndTraceChemistry() {
		var chemistry = mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY;
		var d = domain(3, 2, 2); var masks=new byte[d.cellCount]; java.util.Arrays.fill(masks,(byte)126); d.geometry.cutCells.restoreMicroMasks(masks);
		d.materialRegistry = new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER));
		d.chemicalParcels = new mmm.chemistry.ChemicalParcelStore(chemistry, d.unitScale, 96, d.cellCount);
		d.liquidCells = new LiquidCellInventory(d.cellCount, chemistry);
		var mixture = mmm.chemistry.ChemicalMixtureRecord.fromStableAmounts(chemistry, java.util.Map.of(mmm.chemistry.BuiltinChemicalDefinitions.H2O, 1L), 0);
		var a = new LiquidCellInventory.Key(0,0,1); var b = new LiquidCellInventory.Key(0,0,128);
		d.liquidCells.planEdits(java.util.List.of(
				new LiquidCellInventory.Edit(a,new LiquidCellInventory.Stock(a,SignedDyadic.of(.0625),17,mixture,0,true)),
				new LiquidCellInventory.Edit(b,new LiquidCellInventory.Stock(b,SignedDyadic.of(.0625),19,null,0,false)))).commit();
		d.markCellInventoryChanged();
		var before = d.liquidCells.snapshot();
		for (int step = 0; step < 3; step++) {
			var prepared = new CellLiquidVolumeTransport(d).prepare(.05F); prepared.inventory().commit(); d.markCellInventoryChanged();
			var restored = LiquidCellInventory.fromValidatedSnapshot(d.liquidCells.snapshot(), chemistry); d.liquidCells = restored;
			require(restored.snapshot().rows().equals(before.rows()), "Closed disconnected cut supports exchanged trace chemistry");
		}
		require(d.liquidCells.massUnits() == 36 && d.liquidCells.stock(a).mixture().amount(chemistry.speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.H2O)) == 1,
				"Low-quantum chemical stock did not survive repeated transport and recovery");
	}
	private static void externalQuantityTransactions() {
		var d = domain(4,3,2); d.setVolume(1,1,1,.5F);
		var startVolume = d.liquidCells.exactVolume(); long mass = d.liquidCells.massUnits();
		var removed = d.tryExtractExactMixture(1,1,1,.125F,0);
		require(removed.transferred() && d.liquidCells.exactVolume().equals(startVolume.subtract(SignedDyadic.of(.125))), "Exact mixture extraction stalled");
		var inserted = d.tryInsertExactMixture(1,1,1,removed.portions(),0,true);
		require(inserted.transferred() && d.liquidCells.exactVolume().equals(startVolume) && d.liquidCells.massUnits() == mass, "External mixture cycle changed integer mass or volume");
		var prior = d.liquidCells.snapshot();
		var full = d.tryInsertExactMaterial(1,1,1,1,mass,0,0);
		require(!full.transferred() && d.liquidCells.snapshot().rows().equals(prior.rows()), "Failed capacity preparation published a partial insertion");
		d.setVolume(1,1,1,.25F); require(d.liquidCells.exactVolume().equals(SignedDyadic.of(.25)), "setVolume bypassed common cell inventory");
		d.transferPlanner.clearLiquid(); require(d.liquidCells.size() == 0 && d.liquidCells.massUnits() == 0, "Liquid clear left persistent quantities");
	}
	private static void movingMaterialContinuesAfterRecovery() {
		var d=domain(5,3,2);d.setVolume(1,1,1,.5F);d.ensureGrid();
		java.util.Arrays.fill(d.mac.velocityX,.2F);d.cellMacStage.publishMomentum();
		var initial=d.liquidCells.exactVolume();long mass=d.liquidCells.massUnits();double start=volumeCenterX(d);
		for(int step=0;step<3;step++) {
			d.ensureGrid();d.cellMacStage.transport(.05F);
			require(d.liquidCells.exactVolume().equals(initial)&&d.liquidCells.massUnits()==mass,"Continuous cell/MAC transport changed material inventory");
			d=FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
			for(int cell=0;cell<d.cellCount;cell++)require(d.liquidCells.exactCellVolume(cell).compareTo(SignedDyadic.of(d.effectiveOpenCellVolume(cell)))<=0,"Restored cell transport exceeded capacity");
		}
		require(volumeCenterX(d)>start+.01,"Recovered cell material stalled under persistent MAC momentum");
	}
	private static double volumeCenterX(FluidDomain d) {
		double total=0,moment=0;for(var stock:d.liquidCells.stocks()){double amount=stock.volumeFraction();total+=amount;moment+=amount*(stock.key().cell()%d.sizeX+.5);}return moment/total;
	}
	private static void sharedCapacityDisplacement() {
		var flux = new ConservativeLiquidFlux(3,1,1); var geometry = new LiquidFluxGeometry(new FlipCutCellGeometry(3,1,1)); flux.geometry(geometry);
		var volumes = new SignedDyadic[]{SignedDyadic.of(1),SignedDyadic.of(1),SignedDyadic.ZERO};
		var capacities = new SignedDyadic[]{SignedDyadic.of(.5),SignedDyadic.of(1),SignedDyadic.of(1)};
		flux.planDisplacement(volumes, capacities, new SignedDyadic[]{SignedDyadic.of(.5),SignedDyadic.ZERO,SignedDyadic.ZERO},
				new boolean[3], new FlipCutCellGeometry(3,1,1), 131072);
		var total = SignedDyadic.ZERO; for (int i=0;i<3;i++) { var amount=flux.resultingVolumes()[i]; require(amount.signum()>=0 && amount.compareTo(capacities[i])<=0,"Shared displacement exceeded capacity"); total=total.add(amount); }
		require(total.equals(SignedDyadic.of(2)), "Residual displacement routing changed volume");
	}
	private static void componentCapacityMovesItsOwnStock() {
		var d=domain(3,3,3);byte[] masks=new byte[d.cellCount];java.util.Arrays.fill(masks,(byte)255);
		masks[13]=126;masks[12]=(byte)253;d.geometry.cutCells.restoreMicroMasks(masks);
		for(int cell=0;cell<d.cellCount;cell++)d.geometry.solid[cell]=Byte.toUnsignedInt(masks[cell])==255;
		var key=new LiquidCellInventory.Key(13,0,1);var volume=SignedDyadic.of(.125);
		d.liquidCells.planAdditions(java.util.List.of(new LiquidCellInventory.Stock(key,volume,d.unitScale.massUnitsForNormalized(125),null,0,false))).commit();d.markCellInventoryChanged();d.ensureGrid();
		var source=d.liquidCells.snapshot();var previous=new long[d.cellCount];var next=previous.clone();next[13]=mmm.granular.SolidVolume.SVQ_PER_CELL/16;
		var prepared=GranularLiquidDisplacement.prepareInsertion(d,previous,next);
		require(prepared!=null,"Component overfill was hidden by whole-cell spare capacity");
		var future=prepared.inventory.future();
		require(future.stock(key).volume().equals(SignedDyadic.of(3,-5))
				&&future.stock(new LiquidCellInventory.Key(12,0,2)).volume().equals(SignedDyadic.of(1,-5))
				&&future.stock(new LiquidCellInventory.Key(13,0,128))==null,"Shared component projection crossed a closed support or missed its open port");
		require(future.exactVolume().equals(volume)&&future.massUnits()==d.liquidCells.massUnits()
				&&source.rows().equals(d.liquidCells.snapshot().rows()),"Component displacement changed source or exact inventory");
	}
	private static void finiteFaceFluxNeedsNoSamplingFloor() {
		var d=domain(2,2,2);d.setVolume(0,0,0,1);d.ensurePressureGeometry();
		var flux=new ConservativeLiquidFlux(2,2,2);flux.geometry(new LiquidFluxGeometry(d));
		var exact=new SignedDyadic[d.cellCount];var volumes=new double[d.cellCount];
		for(int cell=0;cell<d.cellCount;cell++)volumes[cell]=(exact[cell]=d.liquidCells.exactCellVolume(cell)).toDouble();
		flux.velocity[0]=Math.scalb(1.0,-40);flux.plan(volumes,d.geometry.solid,1,exact);
		require(flux.resultingVolumes()[1].signum()>0&&flux.resultingVolumes()[0].add(flux.resultingVolumes()[1]).equals(SignedDyadic.of(1)),"Resolvable face sweep was rejected at an obsolete sample floor");
	}

	private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
	private static void convergence() {
		for (double duration : new double[] {1, 4}) {
			double previousError = Double.POSITIVE_INFINITY, previousReferenceError = 0;
			for (int n : duration == 1 ? new int[] {12, 24, 48} : new int[] {24, 48, 96}) {
				var experiment = new LiquidVolumeTransportValidation(n); int steps = (int) (duration * n * 10);
				long start = System.nanoTime();
				for (int step = 0; step < steps; step++) experiment.step(duration / steps);
				double error = 0, referenceError = 0, peak = 0;
				SignedDyadic inventory = SignedDyadic.ZERO;
				for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
					double reference = referenceFraction(x, y, experiment.h, duration, 128);
					referenceError += Math.abs(reference - referenceFraction(x, y, experiment.h, duration, 64));
					double v = experiment.volume[y * n + x];
					var exact = experiment.exactVolume[y * n + x];
					if (exact.compareTo(SignedDyadic.ZERO) < 0 || exact.compareTo(SignedDyadic.of(1)) > 0)
						throw new AssertionError("Liquid flux exceeded exact cell capacity: " + exact);
					inventory = inventory.add(exact); error += Math.abs(v - reference); peak = Math.max(peak, v);
				}
				if (!inventory.equals(SignedDyadic.of(n * n / 2))) throw new AssertionError("Analytic swirl changed exact liquid inventory");
				double normalizedError = error / (n * n * 0.5), normalizedReferenceError = referenceError / (n * n * 0.5);
				if (!(normalizedError + normalizedReferenceError < previousError - previousReferenceError)
						|| duration == 1 && !(normalizedError < 0.6 * previousError))
					throw new AssertionError("Liquid volume failed spatial convergence beyond reference discretization: time=" + duration
							+ " N=" + n + " L1/V=" + normalizedError + " referenceDelta=" + normalizedReferenceError);
				previousError = normalizedError; previousReferenceError = normalizedReferenceError;
				System.out.printf("geometric flux t=%g N=%d L1/V=%g referenceDelta=%g peak=%g elapsed=%g ms%n",
						duration, n, normalizedError, normalizedReferenceError, peak, (System.nanoTime() - start) / 1e6);
			}
		}
	}

	private static double referenceFraction(int x, int y, double h, double time, int samples) {
		int inside = 0;
		for (int sy = 0; sy < samples; sy++) for (int sx = 0; sx < samples; sx++) {
			double dx = (x + (sx + 0.5) / samples) * h - 3, dy = (y + (sy + 0.5) / samples) * h - 3;
			double r2 = dx * dx + dy * dy, angle = r2 < 6.25 ? 6 * time * Math.pow(1 - r2 / 6.25, 2) : 0;
			if (dx * Math.cos(angle) + dy * Math.sin(angle) < 0) inside++;
		}
		return (double) inside / (samples * samples);
	}

}
