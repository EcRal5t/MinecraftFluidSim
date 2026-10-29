package mmm.fluid;

import mmm.thermal.ThermalCarrierContacts;
import mmm.thermal.ThermalCarrierTransport;
import java.util.Arrays;
import java.util.List;

/** Finite-volume conduction between persistent thermal owners in connected cut-cell supports. */
final class CarrierThermalConduction {
	private CarrierThermalConduction() { }
	/** Physical cell faces; contact ordinals are linear cell indices. */
	static List<mmm.thermal.FiniteThermalExchange.Contact> finitePorts(
			HostFluidState domain, List<FluidDomainContracts.ThermalPortRequest> requests) {
		domain.domainFlowFields.preparePhysicalState();
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		var access = domain.thermalState.exchangeCarrierAccess(domain.domainCapabilities.thermalMarkerContext());
		var byCell = new java.util.HashMap<Integer, java.util.List<Integer>>();
		for (int owner = 0; owner < access.carrierCount(); owner++) if (access.mass(owner) > 0)
			byCell.computeIfAbsent(access.cell(owner), ignored -> new java.util.ArrayList<>()).add(owner);
		var conductance = new java.util.TreeMap<Integer, java.util.Map<Integer, Double>>();
		var seen = new java.util.HashSet<Long>();
		double h = domain.config.cellSize();
		var phase = domain.gasState.frame.phase();
		for (var request : requests) {
			int cell = domain.domainFlowFields.cellIndexChecked(request.cellX(), request.cellY(), request.cellZ());
            if(!domain.groupAvailable(cell))continue;
			if (!seen.add(6L * cell + request.direction())) throw new IllegalArgumentException("Repeated thermal port face");
			var owners = byCell.get(cell);
			if (owners == null) continue;
			int axis = request.direction() / 2, side = request.direction() & 1;
			for (int micro = 0; micro < 8; micro++) {
				if ((micro >> axis & 1) != side || (domain.geometry.cutCells.mask(cell) & 1 << micro) != 0) continue;
				double[] coverage = new double[3];
				for (int owner : owners) {
					int support = access.supportMask(owner);
					if (support == 0) support = ~domain.geometry.cutCells.mask(cell) & 255;
					if ((support & 1 << micro) != 0) coverage[family(access.material(owner))] += access.volume(owner) / Integer.bitCount(support);
				}
				double tangent = .5 * (micro >> ((axis + 1) % 3) & 1);
				double other = .5 * (micro >> ((axis + 2) % 3) & 1);
				double dry = GasFaceIntersection.area(phase, cell, -1, axis, side, 0, tangent, other, .5);
				double porosity = phase.componentPoreScale(cell, micro, phase.poreVolume(cell).toDouble()
						/ (Integer.bitCount(~domain.geometry.cutCells.mask(cell) & 255) * .125));
				double[] area = {(.25 - dry) * porosity, dry * porosity, .25 * Math.min(1, coverage[2] / .125)};
				for (int owner : owners) {
					int support = access.supportMask(owner);
					if (support == 0) support = ~domain.geometry.cutCells.mask(cell) & 255;
					if ((support & 1 << micro) == 0) continue;
					int family = family(access.material(owner));
					double k = access.material(owner).conductivityWPerMeterK();
					if (k == 0 || coverage[family] == 0) continue;
					double share = access.volume(owner) / Integer.bitCount(support) / coverage[family];
					double g = h * h * area[family] * share
							/ (request.wallThicknessMetres() / request.wallConductivityWPerMetreK() + request.filmThicknessMetres() / k);
					if (g > 0) conductance.computeIfAbsent(cell, ignored -> new java.util.TreeMap<>()).merge(owner, g, Double::sum);
				}
			}
		}
		var result = new java.util.ArrayList<mmm.thermal.FiniteThermalExchange.Contact>();
		for (var cell : conductance.entrySet()) {
			double total = cell.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
			var carriers = new java.util.ArrayList<mmm.thermal.FiniteThermalExchange.Carrier>();
			for (var entry : cell.getValue().entrySet()) {
				int owner = entry.getKey();
				carriers.add(new mmm.thermal.FiniteThermalExchange.Carrier(owner, access.material(owner), access.mass(owner),
						access.baseEnergy(owner), access.internalEnergy(owner), entry.getValue() / total));
			}
			result.add(new mmm.thermal.FiniteThermalExchange.Contact(cell.getKey(), total, carriers));
		}
		return List.copyOf(result);
	}
	private static int family(mmm.thermal.ThermalMaterialDefinition material) {
		return switch (material.carrierKind()) { case LIQUID -> 0; case GAS -> 1; default -> 2; };
	}
	static List<ThermalCarrierTransport.Contact> contacts(HostFluidState domain, ThermalCarrierTransport.Batch owners) {
		if (domain.thermalState == null || owners.conductionBelowResolution(domain.thermalBoundaryTemperatureK)) return List.of();
		int count=owners.carrierCount(),liquids=domain.liquidCells.physicalSize();
		var head=new int[domain.cellCount];var next=new int[count];var support=new int[count];Arrays.fill(head,-1);
		var conductivity=new double[count];var fraction=new double[count];var coverage=new double[8*domain.cellCount];
		for(int owner=0;owner<count;owner++) {
			int cell=owners.cell(owner);support[owner]=owners.supportMask(owner)!=0?owners.supportMask(owner):owner<liquids?domain.liquidCells.physicalStockAt(owner).key().supportMask():~domain.geometry.cutCells.mask(cell)&255;
			conductivity[owner]=owners.material(owner).conductivityWPerMeterK();
			fraction[owner]=owners.volume(owner)/(.125*Integer.bitCount(support[owner]));
			next[owner]=head[cell];head[cell]=owner;
			for(int bit=0;bit<8;bit++)if((support[owner]&1<<bit)!=0)coverage[8*cell+bit]+=fraction[owner];
		}
		var contacts=new ThermalCarrierContacts(Math.max(16,count*4));double h=domain.config.cellSize();
		for(int cell=0;cell<domain.cellCount;cell++) {
			int x=cell%domain.sizeX,y=cell/domain.sizeX%domain.sizeY,z=cell/(domain.sizeX*domain.sizeY);
			for(int p=head[cell];p>=0;p=next[p]) {
				if(conductivity[p]==0)continue;
				for(int q=next[p];q>=0;q=next[q]) {
					int overlap=Integer.bitCount(support[p]&support[q]);
					double area=.125*overlap*Math.pow(Math.min(fraction[p],fraction[q]),2.0/3);
					double g=2*harmonic(conductivity[p],conductivity[q])*h*area;
					if(g>0)add(domain,owners,contacts,p,q,g,Double.NaN);
				}
				for(int axis=0;axis<3;axis++) {
					int coordinate=axis==0?x:axis==1?y:z,size=axis==0?domain.sizeX:axis==1?domain.sizeY:domain.sizeZ;
					if(coordinate+1<size) {
						int neighbor=cell+(axis==0?1:axis==1?domain.sizeX:domain.sizeX*domain.sizeY);
						for(int q=head[neighbor];q>=0;q=next[q]) {
							double area=0;
							for(int bit=0;bit<8;bit++)if((bit>>axis&1)!=0&&(support[p]&1<<bit)!=0&&(support[q]&1<<(bit^(1<<axis)))!=0) {
								double total=Math.max(coverage[8*cell+bit],coverage[8*neighbor+(bit^(1<<axis))]);
								if(total>0)area+=.25*fraction[p]*fraction[q]/total;
							}
							double g=harmonic(conductivity[p],conductivity[q])*h*area;
							if(g>0)add(domain,owners,contacts,p,q,g,Double.NaN);
						}
					}
					if(domain.thermalBoundaryTemperatureK!=null)for(int side=0;side<2;side++) {
						int direction=2*axis+side;double temperature=domain.thermalBoundaryTemperatureK[6*cell+direction];if(Double.isNaN(temperature))continue;
						double blocked=1-domain.domainCapabilities.thermalOpenFaceFraction(x,y,z,direction);if(blocked<=0)continue;
						int pieces=0;for(int bit=0;bit<8;bit++)if((bit>>axis&1)==side&&(support[p]&1<<bit)!=0)pieces++;
						double g=2*conductivity[p]*h*.25*pieces*fraction[p]*blocked;
						if(g>0)add(domain,owners,contacts,p,-1,g,temperature);
					}
				}
			}
		}
		var active=new ThermalCarrierContacts(contacts.size());
		for(int i=0;i<contacts.size();i++) if(domain.activityGroups.thermalActive(owners.cell(contacts.firstOwner(i))))
			active.add(contacts.firstOwner(i),contacts.secondOwner(i),contacts.conductance(i),contacts.reservoirTemperatureK(i));
		return active;
	}
	private static void add(HostFluidState domain,ThermalCarrierTransport.Batch owners,ThermalCarrierContacts contacts,
			int first,int second,double conductance,double reservoir) {
		int cell=owners.cell(first);
		if(second>=0) domain.activityGroups.coupleThermalCells(cell,owners.cell(second));
		double other=second<0?reservoir:owners.temperatureK(second);
		if(Math.abs(owners.temperatureK(first)-other)>mmm.thermal.ThermalTransport.CONDUCTION_RESOLUTION_K)
			domain.activityGroups.keepThermalAwake(cell);
		contacts.add(first,second,conductance,reservoir);
	}
	private static double harmonic(double first,double second) {
		if(first==0||second==0)return 0;double low=Math.min(first,second),high=Math.max(first,second);return low*(2/(1+low/high));
	}
}
