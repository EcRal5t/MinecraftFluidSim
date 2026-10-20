package mmm.fluid;

import mmm.thermal.ThermalCarrierContacts;
import mmm.thermal.ThermalCarrierTransport;
import java.util.Arrays;
import java.util.List;

/** Finite-volume conduction between persistent condensed owners in connected cut-cell supports. */
final class LiquidThermalConduction {
	private LiquidThermalConduction() { }
	static List<ThermalCarrierTransport.Contact> contacts(FluidDomain domain) {
		if (domain.thermalState == null) return List.of();
		var context=domain.domainCapabilities.thermalMarkerContext();var owners=domain.thermalState.carrierAccess(context);
		int count=owners.carrierCount(),liquids=context.markerCount();
		var head=new int[domain.cellCount];var next=new int[count];var support=new int[count];Arrays.fill(head,-1);
		var conductivity=new double[count];var fraction=new double[count];var coverage=new double[8*domain.cellCount];
		for(int owner=0;owner<count;owner++) {
			int cell=owners.cell(owner);support[owner]=owner<liquids?domain.liquidCells.physicalStockAt(owner).key().supportMask():~domain.geometry.cutCells.mask(cell)&255;
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
					if(g>0)contacts.add(p,q,g,Double.NaN);
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
							if(g>0)contacts.add(p,q,g,Double.NaN);
						}
					}
					if(domain.thermalBoundaryTemperatureK!=null)for(int side=0;side<2;side++) {
						int direction=2*axis+side;double temperature=domain.thermalBoundaryTemperatureK[6*cell+direction];if(Double.isNaN(temperature))continue;
						double blocked=1-domain.domainCapabilities.thermalOpenFaceFraction(x,y,z,direction);if(blocked<=0)continue;
						int pieces=0;for(int bit=0;bit<8;bit++)if((bit>>axis&1)==side&&(support[p]&1<<bit)!=0)pieces++;
						double g=2*conductivity[p]*h*.25*pieces*fraction[p]*blocked;
						if(g>0)contacts.add(p,-1,g,temperature);
					}
				}
			}
		}
		return contacts;
	}
	private static double harmonic(double first,double second) {
		if(first==0||second==0)return 0;double low=Math.min(first,second),high=Math.max(first,second);return low*(2/(1+low/high));
	}
}
