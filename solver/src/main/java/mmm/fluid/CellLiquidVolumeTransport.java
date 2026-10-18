package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Shared upwind face transport directly between persistent liquid cell inventories. */
final class CellLiquidVolumeTransport {
	record Prepared(LiquidCellInventory.Plan inventory, List<LiquidCellInventory.FaceFlux> faceFluxes,
	                ConservativeLiquidFlux volumeFlux) { }
	private final FluidDomain domain;
	private final ConservativeLiquidFlux transport;
	private final LiquidFluxGeometry geometry;
	private final double[] volume,outgoing,componentPore;
	private final SignedDyadic[] exactVolume;
	private final int[] macFace;

	CellLiquidVolumeTransport(FluidDomain domain) {
		this.domain = domain;
		transport = new ConservativeLiquidFlux(domain.sizeX, domain.sizeY, domain.sizeZ);
		geometry = new LiquidFluxGeometry(domain); transport.geometry(geometry);
		volume = new double[domain.cellCount]; exactVolume = new SignedDyadic[domain.cellCount];
		outgoing = new double[8*domain.cellCount];componentPore=outgoing.clone();macFace = new int[transport.flux.length];
		for (int face = 0; face < macFace.length; face++) {
			int cell = transport.right[face], x = cell % domain.sizeX, y = cell / domain.sizeX % domain.sizeY, z = cell / (domain.sizeX * domain.sizeY);
			macFace[face] = switch (transport.axis[face]) {
				case 0 -> domain.xFaceIndex(x, y, z);
				case 1 -> domain.yFaceIndex(x, y, z);
				default -> domain.zFaceIndex(x, y, z);
			};
		}
	}
	record DiagnosticView(ConservativeLiquidFlux transport, SignedDyadic[] exactVolume) { }
	DiagnosticView diagnosticView() { return new DiagnosticView(transport, exactVolume); }

	double courantRate(double verticalPrediction) {
		return courantRate(verticalPrediction,false);
	}
	double projectedCourantRate() {
		domain.ensurePressureGeometry();geometry.prepareTransport();
		return courantRate(0,true);
	}
	private void prepareComponentQuantities(){
		Arrays.fill(outgoing,0);Arrays.fill(componentPore,0);
		for(int cell=0;cell<domain.cellCount;cell++){
			int mask=domain.geometry.cutCells.mask(cell),open=Integer.bitCount(~mask&255);
			if(open==0)continue;
			for(int support:LiquidCellInventory.connectedSupports(mask))componentPore[component(cell,support)]=geometry.componentPoreVolume(cell,support);
		}
	}
	/** Both complementary phases use the same total pore-donor Courant bound. */
	private double courantRate(double verticalPrediction,boolean frozen) {
		prepareComponentQuantities();var pairs=new int[4];
		for (int face = 0; face < transport.flux.length; face++) {
			double velocity = faceVelocity(face) + (transport.axis[face] == 1 ? verticalPrediction : 0);
			if(velocity==0||faceMass(face)<=0)continue;
			int a=transport.left[face],b=transport.right[face],axis=transport.axis[face];
			if(domain.geometry.solid[a]||domain.geometry.solid[b])continue;
			int maskA=domain.geometry.cutCells.mask(a),maskB=domain.geometry.cutCells.mask(b),count=0;
			for(int micro=0;micro<8;micro++)if((micro>>>axis&1)!=0){
				int supportA=CellRemapMicroTopology.support(maskA,micro),supportB=CellRemapMicroTopology.support(maskB,micro^(1<<axis));
				if(supportA==0||supportB==0)continue;int pair=supportA<<8|supportB;boolean present=false;
				for(int j=0;j<count;j++)if(pairs[j]==pair){present=true;break;}
				if(!present)pairs[count++]=pair;
			}
			for(int j=0;j<count;j++){
				int supportA=pairs[j]>>>8,supportB=pairs[j]&255;
				int source=velocity>0?component(a,supportA):component(b,supportB);
				double coefficient=frozen?geometry.totalPortCoefficient(a,b,axis,supportA,supportB):geometry.aperture(a,b,axis,supportA,supportB);
				outgoing[source]+=Math.abs(velocity)/domain.config.cellSize()*coefficient;
			}
		}
		double maximum=0;
		for(int source=0;source<outgoing.length;source++)if(outgoing[source]>0){
			if(componentPore[source]<=0)throw new IllegalStateException("Mixture flux drains a closed pore at cell "+source/8+" micro "+source%8);
			maximum=Math.max(maximum,outgoing[source]/componentPore[source]);
		}
		return maximum;
	}
	private static int component(int cell,int support){return 8*cell+Integer.numberOfTrailingZeros(support);}
	private double faceMass(int face){return switch(transport.axis[face]){
		case 0->domain.cellMacStage.massX[macFace[face]];case 1->domain.cellMacStage.massY[macFace[face]];default->domain.cellMacStage.massZ[macFace[face]];};}
	private float faceVelocity(int face) {
		return switch (transport.axis[face]) {
			case 0 -> domain.mac.velocityX[macFace[face]];
			case 1 -> domain.mac.velocityY[macFace[face]];
			default -> domain.mac.velocityZ[macFace[face]];
		};
	}

	/** Prepares quantities and carried U; the coupled publisher owns commit. */
	Prepared prepare(float dt) {
		return prepareFlux(prepareVolumeFlux(dt),dt);
	}
	/** Pressure/volume feedback can finish before any integer material allocation is prepared. */
	ConservativeLiquidFlux prepareVolumeFlux(float dt) {
		if (!(Float.isFinite(dt) && dt > 0)) throw new IllegalArgumentException("Liquid transport duration must be positive");
		domain.ensurePressureGeometry();
		double courant=projectedCourantRate()*dt;
		if(courant>1+8*Math.ulp(1.0))throw new MechanicalTimeRefinement((int)Math.ceil(courant*domain.config.timeStep()/dt),"Connected liquid donor Courant "+courant);
		for (int cell = 0; cell < volume.length; cell++) volume[cell] = (exactVolume[cell] = domain.liquidCells.exactCellVolume(cell)).toDouble();
		for (int face = 0; face < transport.flux.length; face++) transport.velocity[face] = faceVelocity(face);
		transport.plan(volume, domain.geometry.solid, dt / domain.config.cellSize(), exactVolume);
		return transport;
	}
	Prepared prepareFlux(ConservativeLiquidFlux transport, float dt) { return prepareFlux(transport,dt,domain.liquidCells); }
	Prepared prepareFlux(ConservativeLiquidFlux transport, float dt, LiquidCellInventory source) {
		var remaining = new HashMap<LiquidCellInventory.Key, SignedDyadic>();
		for (var stock : source.stocks()) remaining.put(stock.key(), stock.volume());
		var fluxes = new ArrayList<LiquidCellInventory.FaceFlux>();
		for (var port : transport.ports()) if (port.volume().signum() > 0) {
			int donor = port.donorCell(), receiver = port.receiverCell();
			var stocks = source.stocksInCell(donor).stream().filter(stock -> stock.key().supportMask() == port.donorSupport()).toList();
			var capacities = new SignedDyadic[stocks.size()]; var preferences = new double[stocks.size()];
			for (int i = 0; i < stocks.size(); i++) {
				var stock = stocks.get(i); capacities[i] = remaining.get(stock.key());
				preferences[i] = stock.volumeFraction();
			}
			var amounts = LiquidCellInventory.allocateVolumes(port.volume(), capacities, preferences);
			for (int i = 0; i < stocks.size(); i++) if (amounts[i].signum() > 0) {
				var key = stocks.get(i).key(); remaining.put(key, capacities[i].subtract(amounts[i]));
				fluxes.add(new LiquidCellInventory.FaceFlux(port.face(), donor, receiver, port.donorSupport(), port.receiverSupport(), key.material(), amounts[i]));
			}
		}
		int[] masks = new int[domain.cellCount]; for (int cell = 0; cell < masks.length; cell++) masks[cell] = domain.geometry.cutCells.mask(cell);
		var inventory = source.planTransport(fluxes, domain.sizeX, domain.sizeY, masks,FluidThermalBinding.liquidEnergyPartitioner(domain));
		for (int cell : inventory.changedCells()) {
			if (!inventory.future().exactCellVolume(cell).equals(transport.resultingVolumes()[cell]))
				throw new IllegalStateException("Liquid material flux differs from shared cell volume at " + cell);
			for (var stock : inventory.future().stocksInCell(cell)) {
				var amount = SignedDyadic.ZERO;
				for (var other : inventory.future().stocksInCell(cell)) if (other.key().supportMask() == stock.key().supportMask()) amount = amount.add(other.volume());
				if (amount.compareTo(transport.componentCapacity(cell, stock.key().supportMask())) > 0)
					throw new IllegalStateException("Liquid material transport exceeds actual connected pore capacity");
			}
		}
		return new Prepared(inventory, List.copyOf(fluxes), transport);
	}
}
