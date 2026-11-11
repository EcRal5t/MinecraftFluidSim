package mmm.simulation;

import mmm.chemistry.*;
import mmm.fluid.*;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.granular.*;
import mmm.thermal.*;
import mmm.transfer.*;
import mmm.units.Mass;
import mmm.worldprofile.WorldCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.util.*;

/** Feed batches, mass totals, port cells and progress output shared by the process flow families. */
final class ProcessFlowSupport {
    private ProcessFlowSupport() {}
    static long processMass(FluidDomainState state) {
        long total=0;
        for(var stock:state.liquid().physicalStocks())total=Math.addExact(total,stock.massUnits());
        for(var gas:state.spatialGas().cells())total=Math.addExact(total,gas.totalMassUnits());
        if(state.granular()!=null)total=Math.addExact(total,state.granular().totalMassUnits());
        if(state.thermalOwner()!=null)for(var group:state.thermalOwner().coherentGroups())total=Math.addExact(total,group.massUnits());
        return total;
    }

    static long chemicalAmount(ChemicalContents contents,String species) {
        return contents.groups().stream().flatMap(group->group.species().stream()).filter(entry->entry.speciesId().equals(species)).mapToLong(ChemicalContents.SpeciesAmount::amountQuanta).sum();
    }

    static ItemStack saltedWater(CommittedWorldRegistries world,double temperature) {
        var chemistry=world.chemicalRegistry();var amounts=Map.of(BuiltinChemicalDefinitions.H2O,waterQuanta(world),BuiltinChemicalDefinitions.NACL,ChemicalLimits.QUANTA_PER_MOL);
        var composition=ChemicalMixtureRecord.fromStableAmounts(chemistry,amounts,0);
        long mass=Math.addExact(Mass.fromKilograms(world.resolveMaterial("mmm:water").referenceDensity()),chemistry.speciesMassUnits(chemistry.speciesHandle(BuiltinChemicalDefinitions.NACL),ChemicalLimits.QUANTA_PER_MOL));
        var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,mass-composition.speciesMassUnits(chemistry),amounts)));
        var result=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,chemistry,1_000_000_000L));
        world.bindActualContainerProfile(result);world.attachLiquidThermalContents(result,temperature);return result;
    }

    static ItemStack pureWater(CommittedWorldRegistries world,double temperature) {
        var chemistry=world.chemicalRegistry();var amounts=Map.of(BuiltinChemicalDefinitions.H2O,waterQuanta(world));
        var composition=ChemicalMixtureRecord.fromStableAmounts(chemistry,amounts,0);long mass=Mass.fromKilograms(world.resolveMaterial("mmm:water").referenceDensity());
        var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,mass-composition.speciesMassUnits(chemistry),amounts)));
        var result=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,chemistry,1_000_000_000L));
        world.bindActualContainerProfile(result);world.attachLiquidThermalContents(result,temperature);return result;
    }

    static ItemStack coolableSaltWater(CommittedWorldRegistries world,double feedTemperature,double coldTemperature,long volumeNanos) {
        var solubility=world.granularRegistry().reactions().stream().filter(reaction->reaction.solidMaterialId().equals(BuiltinGranularDefinitions.PRECIPITATE))
                .map(SolidLiquidReaction::solubility).filter(Objects::nonNull).findFirst().orElseThrow();
        double feedLimit=solubility.ratio()+solubility.slopePerK()*(feedTemperature-solubility.referenceTemperatureK());
        double coldLimit=solubility.ratio()+solubility.slopePerK()*(coldTemperature-solubility.referenceTemperatureK());
        if(!(feedLimit>coldLimit))throw new IllegalStateException("Salt solubility does not increase between the cold wall and feed temperatures");
        double volume=volumeNanos/(double)ContainerFluidLedger.NANOS_PER_BLOCK;
        long water=Math.round(waterQuanta(world)*volume),salt=Math.round(water*(feedLimit-(feedLimit-coldLimit)*0.02));
        var amounts=Map.of(BuiltinChemicalDefinitions.H2O,water,BuiltinChemicalDefinitions.NACL,salt);
        var registry=world.chemicalRegistry();var mixture=ChemicalMixtureRecord.fromStableAmounts(registry,amounts,0);
        long waterMass=Mass.fromKilograms(world.resolveMaterial("mmm:water").referenceDensity()*volume);
        long mass=Math.addExact(waterMass,registry.speciesMassUnits(registry.speciesHandle(BuiltinChemicalDefinitions.NACL),salt));
        var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,mass-mixture.speciesMassUnits(registry),amounts)));
        var result=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,registry,volumeNanos));
        world.bindActualContainerProfile(result);world.attachLiquidThermalContents(result,feedTemperature);return result;
    }

    static long waterQuanta(CommittedWorldRegistries world) {
        var registry=world.chemicalRegistry();var phase=registry.phase(registry.phaseHandle(BuiltinChemicalDefinitions.AQUEOUS));
        return Math.round(phase.defaultConcentrationMolPerCubicMeter().get(BuiltinChemicalDefinitions.H2O).toDouble()*ChemicalLimits.QUANTA_PER_MOL);
    }

    static long gasMass(ItemStack stack) { var gas=mmm.item.GasContainerItem.contents(stack);return gas==null?0:gas.inventory().totalMassUnits(); }

    static long chemicalMass(ItemStack stack,CommittedWorldRegistries world) {
        if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(stack))return 0;
        return mmm.item.ContainerItemStacks.chemicalContents(stack,world.chemicalRegistry()).totalMassUnits();
    }

    static final class TransferEnergyTotals {
        mmm.units.SignedDyadic source=mmm.units.SignedDyadic.ZERO,sink=mmm.units.SignedDyadic.ZERO;
        void add(mmm.thermal.ThermalAuditLedger before,mmm.thermal.ThermalAuditLedger after){
            source=source.add(after.externalSource().subtract(before.externalSource()));
            sink=sink.add(after.externalSink().subtract(before.externalSink()));
        }
    }

    static void requireEnergyTotal(double actual,double expected,String description){
        double scale=Math.max(Math.abs(actual),Math.abs(expected));
        if(!Double.isFinite(actual)||!Double.isFinite(expected)||Math.abs(actual-expected)>Math.max(1e-9,1e-10*scale))
            throw new AssertionError(description+": actual="+actual+" expected="+expected);
    }

    static long speciesAtVolume(List<ChemicalContents> batches,long volume,String species,ChemicalRegistry registry) {
        long remaining=volume,amount=0;
        for(var batch:batches) {
            long taken=Math.min(remaining,batch.totalVolumeNanos());if(taken==0)break;
            amount=Math.addExact(amount,chemicalAmount(batch.split(taken,registry).inserted(),species));remaining-=taken;
        }
        if(remaining!=0)throw new IllegalStateException("Product batches do not cover the common sample volume");
        return amount;
    }

    static int[] industrialPortCell(FluidRegion region,IndustrialLayout.Port port) {
        var interior=port.position().relative(port.outward().getOpposite());
        return new int[]{2*(interior.getX()-region.origin().getX())+Math.max(0,port.outward().getStepX()),
                2*(interior.getY()-region.origin().getY())+Math.max(0,port.outward().getStepY()),
                2*(interior.getZ()-region.origin().getZ())+Math.max(0,port.outward().getStepZ())};
    }

    static void progress(String scenario,int step,int total) {
        if(step%100==0)System.out.println("process_progress scenario="+scenario+" step="+step+"/"+total);
    }

    static ItemStack saltSeed(CommittedWorldRegistries world){
        return saltSeed(world,1000);
    }

    static ItemStack saltSeed(CommittedWorldRegistries world,long batches){
        var reaction=world.granularRegistry().reactions().stream()
                .filter(value->value.solidMaterialId().equals(BuiltinGranularDefinitions.PRECIPITATE)).findFirst().orElseThrow();
        long extent=Math.multiplyExact(reaction.solidExtentPerBatch(),batches);
        long svq=Math.multiplyExact(reaction.solidVolumeSvqPerBatch(),batches);
        var amounts=new TreeMap<String,Long>();
        reaction.solidComposition().forEach((species,coefficient)->amounts.put(species,Math.multiplyExact(extent,coefficient)));
        var composition=SolidCompositionRecord.fromStableAmounts(world.chemicalRegistry(),amounts,0);
        long mass=composition.speciesMassUnits(world.chemicalRegistry());
        var ledger=new GranularTransferLedger(List.of(new GranularTransferLedger.Group(BuiltinGranularDefinitions.PRECIPITATE,svq,mass,composition)));
        var item=mmm.item.ContainerItemStacks.toStack(GranularContents.fromLedger(ledger,world.granularRegistry(),world.chemicalRegistry()));
        world.bindActualContainerProfile(item);
        var thermal=world.thermalRegistry();int material=thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR,BuiltinGranularDefinitions.PRECIPITATE);
        double energy=new ThermalStateFunction(thermal).internalEnergyAtTemperature(material,Mass.kilograms(mass),amounts,293.15);
        mmm.item.ContainerItemStacks.attachThermal(item,thermal.fingerprint(),energy);
        return item;
    }

    static void available(FluidRegion region){boolean[] available=new boolean[region.grid().cellCount()];Arrays.fill(available,true);region.prepareGroupAvailability(available);}
}
