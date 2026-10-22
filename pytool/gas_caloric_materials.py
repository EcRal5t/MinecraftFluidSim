"""Generate ideal-gas Cv tables from NIST Shomate integrals and JANAF water enthalpy.
Sources: https://webbook.nist.gov/cgi/cbook.cgi?ID=C7727379&Mask=421&Units=SI
https://webbook.nist.gov/cgi/cbook.cgi?ID=C7782447&Mask=1&Type=JANAFG
https://webbook.nist.gov/cgi/cbook.cgi?ID=C124389&Mask=1&Units=SI
https://janaf.nist.gov/tables/H-064.html
R is CODATA; model molar masses deliberately use integer atomic masses.
CO2 below 298 K holds its 298 K Cp; H2O uses JANAF enthalpy interpolation.
Transport coefficients hold the NIST Huber 300 K low-pressure reference constant.
"""
import json
from pathlib import Path
R = 8.31446261815324
ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'fabric/src/main/resources/data/mmm/mmm'
CUTS = [100., 200., 300., 400., 500., 600., 700., 900., 1200., 1700.]
# A-E coefficients, split only at original NIST fit boundaries.
COEFFICIENTS = {
 'nitrogen': [(100.,500.,(28.98641,1.853978,-9.647459,16.63537,.000117)),
              (500.,2000.,(19.50583,19.88705,-8.598535,1.369784,.527601))],
 'oxygen': [(100.,700.,(31.32234,-20.23531,57.86644,-36.50624,-.007374)),
            (700.,2000.,(30.03235,8.772972,-3.988133,.788313,-.741599))],
 'co2': [(298.,1200.,(24.99735,55.18696,-33.69137,7.948387,-.136638)),
         (1200.,6000.,(58.16639,2.720074,-.492289,.038844,-6.447293))]
}
WATER_H = [(100.,-6.615),(200.,-3.282),(298.15,0.),(300.,.062),(400.,3.452),
           (500.,6.925),(600.,10.501),(700.,14.192),(800.,18.002),(900.,21.938),
           (1000.,26.),(1100.,30.191),(1200.,34.506),(1300.,38.942),(1400.,43.493),
           (1500.,48.151),(1600.,52.908),(1700.,57.758)]
MASS = {'nitrogen':.028,'oxygen':.032,'water_vapor':.018,'co2':.044}
CONDUCTIVITY = {'nitrogen':.0259,'oxygen':.0265,'water_vapor':.0186,'co2':.0168}
def primitive(t,c):
 a,b,cc,d,e=c;t/=1000
 return 1000*(a*t+b*t*t/2+cc*t**3/3+d*t**4/4-e/t)
def cp(t,c):
 a,b,cc,d,e=c;t/=1000
 return a+b*t+cc*t*t+d*t**3+e/(t*t)
def water_h(t):
 for (lo,hlo),(hi,hhi) in zip(WATER_H,WATER_H[1:]):
  if lo<=t<=hi:return 1000*(hlo+(hhi-hlo)*(t-lo)/(hi-lo))
 raise ValueError(t)
def enthalpy_change(name,low,high):
 if name=='water_vapor':return water_h(high)-water_h(low)
 total=0
 for lo,hi,c in COEFFICIENTS[name]:
  a,b=max(low,lo),min(high,hi)
  if b>a:total+=primitive(b,c)-primitive(a,c)
 if low<COEFFICIENTS[name][0][0]:
  edge=COEFFICIENTS[name][0][0];total+=cp(edge,COEFFICIENTS[name][0][2])*(min(high,edge)-low)
 return total
def sensible(segments,low,high):
 return sum(max(0,min(high,s['maximum_temperature_k'])-max(low,s['minimum_temperature_k']))*s['specific_heat_j_per_kg_k'] for s in segments)
def generate():
 tables={}
 for name,mass in MASS.items():
  segments=[dict(minimum_temperature_k=a,maximum_temperature_k=b,
    specific_heat_j_per_kg_k=(enthalpy_change(name,a,b)/(b-a)-R)/mass) for a,b in zip(CUTS,CUTS[1:])]
  tables[name]=dict(carrier_kind='GAS',carrier_id='mmm:'+name,reference_temperature_k=293.15,
   reference_specific_energy_j_per_kg=0.,conductivity_w_per_m_k=CONDUCTIVITY[name],caloric_segments=segments)
 # Derive the quantized vapor batch from the same model molar mass as its ideal-gas density.
 phase_path=DATA/'phase_transitions/water_vapor.json'
 phase=json.loads(phase_path.read_text())
 batch_num,batch_den=map(int,phase['mass_kg_per_batch'].split('/'));mass=batch_num/batch_den
 curve=phase['equilibrium_curve'];pressure=101325.
 boiling=float(curve[0]['temperature_k'])
 for lower,upper in zip(curve,curve[1:]):
  low,high=float(lower['pressure_pa']),float(upper['pressure_pa'])
  if pressure>=low:
   fraction=min(1,(pressure-low)/(high-low))
   boiling=float(lower['temperature_k'])+fraction*(float(upper['temperature_k'])-float(lower['temperature_k']))
 phase['upper_volume_svq_per_batch']=str(round(mass*(R/MASS['water_vapor'])*boiling/pressure*2**27))
 phase_path.write_bytes((json.dumps(phase,separators=(',',':'))+'\n').encode())
 volume=(int(phase['upper_volume_svq_per_batch'])-int(phase['lower_volume_svq_per_batch']))/2**27
 water=json.loads((DATA/'thermal_materials/water.json').read_text())
 liquid_segments=[{key:float(value) for key,value in segment.items()} for segment in water['caloric_segments']]
 liquid_u=float(water['reference_specific_energy_j_per_kg'])+sensible(liquid_segments,float(water['reference_temperature_k']),boiling)
 tables['water_vapor']['reference_specific_energy_j_per_kg']=2256700+liquid_u-pressure*volume/mass-sensible(tables['water_vapor']['caloric_segments'],293.15,boiling)
 for name,table in tables.items():
  # Resource numeric strings follow the existing exact-decimal codec convention.
  def strings(value):
   if isinstance(value,dict):return {k:strings(v) for k,v in value.items()}
   if isinstance(value,list):return [strings(v) for v in value]
   return str(value) if isinstance(value,(int,float)) else value
  (DATA/'thermal_materials'/f'{name}.json').write_bytes((json.dumps(strings(table),separators=(',',':'))+'\n').encode())
  print(name,'M=',MASS[name],'Cv300 segment=',table['caloric_segments'][2]['specific_heat_j_per_kg_k'],'Uref=',table['reference_specific_energy_j_per_kg'])
if __name__=='__main__':generate()
