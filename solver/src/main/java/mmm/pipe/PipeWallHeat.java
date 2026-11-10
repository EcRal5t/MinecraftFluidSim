package mmm.pipe;

import java.util.*;
import java.util.function.DoubleUnaryOperator;
import mmm.fluid.FluidDefinitions;
import mmm.inventory.LiquidCellInventory;
import mmm.thermal.*;
import mmm.units.Mass;
import mmm.units.SignedDyadic;

/** Finite cavity/wall heat exchange. The caller publishes the opposite world-wall delta. */
public final class PipeWallHeat {
    private PipeWallHeat() { }
    public record Wall(int segment,String materialId,long massUnits,double energy,
                       double areaM2,double halfThicknessM) {
        public Wall {
            if(segment<0 || materialId==null || massUnits<=0 || !Double.isFinite(energy)
                    || !(areaM2>0) || !(halfThicknessM>0))throw new IllegalArgumentException("Invalid pipe thermal wall");
        }
    }
    public record Result(PipeNetworkState state,Map<Integer,Double> wallEnergyDeltas,SignedDyadic roundoff) { }
    public record HeatResult(PipeNetworkState state,SignedDyadic roundoff) { }
    private record Carrier(double energy,double volume,double conductivity,DoubleUnaryOperator temperature) { }

    public static Result exchange(PipeNetworkState source,List<Wall> walls,double seconds,FluidDefinitions definitions) {
        if(!(Double.isFinite(seconds)&&seconds>0))throw new IllegalArgumentException("Invalid heat interval");
        var segments=new ArrayList<>(source.segments());
        var rows=new ArrayList<>(source.liquids().rows());var deltas=new TreeMap<Integer,Double>();
        var thermal=definitions.thermal();var caloric=new ThermalStateFunction(thermal);
        var gasModel=new GasThermalModel(thermal,definitions.gas());
        for(var wall:walls) {
            if(deltas.containsKey(wall.segment()))throw new IllegalArgumentException("Duplicate pipe wall");
            var segment=segments.get(wall.segment());var row=new ArrayList<>(rows.get(wall.segment()));
            double oldElastic=PipeHydraulics.mixture(segment,row,gasModel).liquidElasticEnergyJ();
            var deposits=new ArrayList<>(segment.deposits());
            int wallHandle=thermal.materialHandle(wall.materialId());
            var wallMaterial=thermal.material(wallHandle);double wallMass=Mass.kilograms(wall.massUnits());
            DoubleUnaryOperator wallTemperature=u->caloric.stateForInternalEnergy(wallHandle,wallMass,Map.of(),u).temperatureK();
            var carriers=new ArrayList<Carrier>();double liquidVolume=0,solidVolume=0;
            for(var stock:row) {
                String id=source.materialIds().get(stock.key().material());
                int handle=thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,id);
                Map<String,Long> species=stock.mixture()==null?Map.of():stock.mixture().stableAmounts(definitions.chemistry());
                var material=caloric.materialForComposition(handle,stock.massKilograms(),species);
                double volume=stock.volume().toDouble();liquidVolume+=volume;
                carriers.add(new Carrier(stock.internalEnergy(),volume,material.conductivityWPerMeterK(),
                        u->caloric.stateForInternalEnergy(handle,stock.massKilograms(),species,u).temperatureK()));
            }
            for(var deposit:deposits) {
                var group=deposit.inventory();int handle=thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR,group.materialId());
                Map<String,Long> species=group.composition()==null?Map.of():group.composition().stableAmounts(definitions.chemistry());
                double mass=Mass.kilograms(group.massUnits());
                double volume=group.solidVolumeSvq()/(double)mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER;solidVolume+=volume;
                var material=caloric.materialForComposition(handle,mass,species);
                carriers.add(new Carrier(deposit.internalEnergy(),volume,material.conductivityWPerMeterK(),
                        u->caloric.stateForInternalEnergy(handle,mass,species,u).temperatureK()));
            }
            double gasEnergy=segment.gasInternalEnergy();
            if(!segment.gas().isEmpty()) {
                double conductivity=0,total=segment.gas().totalMass();
                for(var component:segment.gas().components()) {
                    int handle=thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS,component.componentId());
                    conductivity+=thermal.material(handle).conductivityWPerMeterK()*Mass.kilograms(component.massUnits())/total;
                }
                carriers.add(new Carrier(gasEnergy,Math.max(0,segment.capacityM3()-liquidVolume-solidVolume),conductivity,
                        u->gasModel.state(segment.gas(),u).temperatureK()));
            }
            double occupied=carriers.stream().mapToDouble(Carrier::volume).sum(),wallEnergy=wall.energy();
            double[] energy=new double[carriers.size()];
            for(int i=0;i<carriers.size();i++) {
                var carrier=carriers.get(i);energy[i]=carrier.energy();
                if(carrier.volume()==0 || carrier.conductivity()==0 || wallMaterial.conductivityWPerMeterK()==0)continue;
                double area=wall.areaM2()*carrier.volume()/Math.max(segment.capacityM3(),occupied);
                double conductance=area/(wall.halfThicknessM()/wallMaterial.conductivityWPerMeterK()+.125/carrier.conductivity());
                double delta=transfer(carrier.energy(),wallEnergy,carrier.temperature(),wallTemperature,conductance*seconds);
                energy[i]+=delta;double nextWall=wallEnergy-delta;
                wallEnergy=nextWall;
            }
            for(int i=0;i<row.size();i++) {
                var stock=row.get(i);row.set(i,new LiquidCellInventory.Stock(stock.key(),stock.volume(),stock.massUnits(),stock.mixture(),energy[i]));
            }
            for(int i=0;i<deposits.size();i++)deposits.set(i,new mmm.granular.GranularTransferLedger.Portion(deposits.get(i).inventory(),energy[row.size()+i]));
            if(!segment.gas().isEmpty())gasEnergy=energy[energy.length-1];
            gasEnergy=settleElastic(segment,row,deposits,gasEnergy,oldElastic,gasModel);
            segments.set(wall.segment(),new PipeSegmentState(segment.id(),segment.elevationM(),segment.capacityM3(),segment.gas(),gasEnergy,deposits));
            rows.set(wall.segment(),row);deltas.put(wall.segment(),wallEnergy-wall.energy());
        }
        var result=new PipeNetworkState(segments,source.connections(),source.materialIds(),new LiquidCellInventory.Snapshot(rows));
        var roundoff=PipeNetworkSolver.energy(result,definitions,0).subtract(PipeNetworkSolver.energy(source,definitions,0));
        for(double delta:deltas.values())roundoff=roundoff.add(SignedDyadic.of(delta));
        return new Result(result,Map.copyOf(deltas),roundoff);
    }

    /** Adds work to a single segment while retaining its coupled liquid elastic energy. */
    public static HeatResult addSegmentHeat(PipeNetworkState source,int index,SignedDyadic heat,FluidDefinitions definitions) {
        Objects.requireNonNull(heat);var segments=new ArrayList<>(source.segments());
        var rows=new ArrayList<>(source.liquids().rows());var row=new ArrayList<>(rows.get(index));var segment=segments.get(index);
        var model=new GasThermalModel(definitions.thermal(),definitions.gas());
        double oldElastic=PipeHydraulics.mixture(segment,row,model).liquidElasticEnergyJ(),gasEnergy=segment.gasInternalEnergy();
        if(!row.isEmpty()) {
            var stock=row.getFirst();double energy=SignedDyadic.of(stock.internalEnergy()).add(heat).toDouble();
            var registry=definitions.thermal();int material=registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,source.materialIds().get(stock.key().material()));
            var species=stock.mixture()==null?Map.<String,Long>of():stock.mixture().stableAmounts(definitions.chemistry());
            new ThermalStateFunction(registry).stateForInternalEnergy(material,stock.massKilograms(),species,energy);
            row.set(0,new LiquidCellInventory.Stock(stock.key(),stock.volume(),stock.massUnits(),stock.mixture(),energy));
        } else if(!segment.gas().isEmpty()) {
            gasEnergy=SignedDyadic.of(gasEnergy).add(heat).toDouble();model.requireInventoryEnergy(segment.gas(),gasEnergy);
        } else if(heat.signum()!=0)throw new IllegalStateException("Pipe heat has no fluid carrier");
        gasEnergy=settleElastic(segment,row,segment.deposits(),gasEnergy,oldElastic,model);
        segments.set(index,new PipeSegmentState(segment.id(),segment.elevationM(),segment.capacityM3(),segment.gas(),gasEnergy,segment.deposits()));
        rows.set(index,row);var next=new PipeNetworkState(segments,source.connections(),source.materialIds(),new LiquidCellInventory.Snapshot(rows));
        var residual=PipeNetworkSolver.energy(next,definitions,0).subtract(PipeNetworkSolver.energy(source,definitions,0)).subtract(heat);
        return new HeatResult(next,residual);
    }
    private static double settleElastic(PipeSegmentState segment,List<LiquidCellInventory.Stock> row,
            List<mmm.granular.GranularTransferLedger.Portion> deposits,double gasEnergy,double oldElastic,GasThermalModel model) {
        if(segment.gas().isEmpty() || row.isEmpty())return gasEnergy;
        double target=gasEnergy+oldElastic;
        for(int iteration=0;iteration<32;iteration++) {
            var trial=new PipeSegmentState(segment.id(),segment.elevationM(),segment.capacityM3(),segment.gas(),gasEnergy,deposits);
            double next=gasEnergy+(target-(gasEnergy+PipeHydraulics.mixture(trial,row,model).liquidElasticEnergyJ()));
            if(next==gasEnergy)return gasEnergy;
            model.requireInventoryEnergy(segment.gas(),next);gasEnergy=next;
        }
        throw new IllegalStateException("Pipe elastic energy did not converge");
    }

    /** Backward Euler with a monotone bracket; finite owners cannot cross equilibrium. */
    static double transfer(double fluid,double wall,DoubleUnaryOperator fluidT,DoubleUnaryOperator wallT,double gdt) {
        double difference=wallT.applyAsDouble(wall)-fluidT.applyAsDouble(fluid);
        if(difference==0 || gdt==0)return 0;
        double sign=Math.signum(difference),low=0,high=Math.abs(difference)*gdt;
        for(int i=0;i<56;i++) {
            double amount=.5*(low+high),q=sign*amount;
            double residual;
            try { residual=sign*(wallT.applyAsDouble(wall-q)-fluidT.applyAsDouble(fluid+q))*gdt-amount; }
            catch(ThermalStateFunction.EnergyOutsideValidityException invalid) { high=amount;continue; }
            if(residual>0)low=amount;else high=amount;
        }
        return sign*.5*(low+high);
    }
}
