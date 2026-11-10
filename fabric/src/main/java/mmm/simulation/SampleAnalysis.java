package mmm.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mmm.chemistry.ChemicalLimits;
import mmm.fluid.air.GasRegistry;
import mmm.granular.SolidVolume;
import mmm.item.*;
import mmm.network.SampleAnalysisPayload;
import mmm.network.SampleAnalysisRequest;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalMaterialDefinition.CarrierKind;
import mmm.thermal.ThermalStateFunction;
import mmm.transfer.*;
import mmm.units.Mass;
import mmm.worldprofile.WorldCapability;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.HitResult;

/** Server-side read of a held container, independent of region and transfer operations. */
public final class SampleAnalysis {
    private SampleAnalysis() { }
    public static void register() {
        UseItemCallback.EVENT.register((player,level,hand) -> {
            if (!level.isClientSide() && player.isShiftKeyDown() && supported(player.getItemInHand(hand))
                    && player.pick(5.0,1.0F,false).getType()==HitResult.Type.MISS) return InteractionResult.SUCCESS_SERVER;
            return InteractionResult.PASS;
        });
        PayloadTypeRegistry.clientboundPlay().register(SampleAnalysisPayload.TYPE, SampleAnalysisPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SampleAnalysisRequest.TYPE, SampleAnalysisRequest.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SampleAnalysisRequest.TYPE, (request,context) ->
            context.server().execute(() -> {
                var player=context.player();
                try {
                    var result=response(player,request);
                    if (result!=null && ServerPlayNetworking.canSend(player,SampleAnalysisPayload.TYPE))
                        ServerPlayNetworking.send(player,result);
                } catch (IllegalArgumentException | IllegalStateException failure) {
                    player.sendSystemMessage(Component.literal("样品分析失败："+failure.getMessage()));
                }
            }));
    }
    static SampleAnalysisPayload response(ServerPlayer player,SampleAnalysisRequest request) {
        if (!player.level().dimension().identifier().toString().equals(request.dimension())
                || !player.isShiftKeyDown() || !supported(player.getItemInHand(request.hand()))
                || player.pick(5.0, 1.0F, false).getType()!=HitResult.Type.MISS) return null;
        var world=MmmRuntimeRegistry.require(player.level().getServer()).worldRegistries();
        var result=analyze(player.getItemInHand(request.hand()),world);
        return result.withSession(request.session(), request.dimension());
    }
    public static boolean supported(ItemStack stack) {
        return stack.getItem() instanceof MaterialBucketItem || stack.getItem() instanceof GranularContainerItem
                || stack.getItem() instanceof GasContainerItem || stack.getItem() instanceof PartialWaterBucketItem
                || stack.is(Items.WATER_BUCKET);
    }
    static SampleAnalysisPayload analyze(ItemStack stack, CommittedWorldRegistries world) {
        var materials = new ArrayList<SampleAnalysisPayload.Material>();
        SampleAnalysisPayload.Phase phase;
        var species = new TreeMap<String, Long>();
        var portions = new ArrayList<ThermalStateFunction.InventoryPortion>();
        var chemistry = world.hasCapability(WorldCapability.CHEMISTRY) ? world.chemicalRegistry() : null;
        double volume = 0, mass = 0, energy = Double.NaN, temperature = Double.NaN;
        boolean completeSpecies = true;
        if (stack.getItem() instanceof GasContainerItem) {
            phase = SampleAnalysisPayload.Phase.GAS;
            var contents = GasContainerItem.contents(stack);
            if (contents == null) return SampleAnalysisPayload.empty(phase);
            requireProfile(stack, world);
            if (!contents.gasRegistryFingerprint().equals(world.gasRegistry().fingerprint())
                    || !contents.chemicalRegistryFingerprint().equals(chemistry == null ? "" : chemistry.fingerprint()))
                throw new IllegalArgumentException("气体定义与当前世界不同");
            world.gasRegistry().requireCompatible(contents.inventory());
            volume = contents.inventory().standardVolumeNanos() * 1e-9;
            mass = Mass.kilograms(contents.inventory().totalMassUnits());
            energy = contents.internalEnergy();
            for (var c : contents.inventory().components()) {
                materials.add(new SampleAnalysisPayload.Material(c.componentId(), c.standardVolumeNanos() * 1e-9, Mass.kilograms(c.massUnits())));
                if (c.massResidualUnits() != 0 || c.species().isEmpty()) completeSpecies = false;
                for (var s : c.species()) species.merge(s.speciesId(), s.amountQuanta(), Math::addExact);
            }
            temperature = new GasThermalModel(world.thermalRegistry(), world.gasRegistry()).state(contents.inventory(), energy).temperatureK();
        } else if (stack.getItem() instanceof GranularContainerItem) {
            phase = SampleAnalysisPayload.Phase.SOLID;
            var contents = ContainerItemStacks.granularContents(stack, world.granularRegistry(), chemistry);
            if (contents == null) return SampleAnalysisPayload.empty(phase);
            requireProfile(stack, world);
            volume = (double) contents.totalSolidVolumeSvq() / SolidVolume.SVQ_PER_CUBIC_METER;
            mass = contents.totalMass();
            var heat = ContainerItemStacks.thermalContents(stack);
            for (var g : contents.groups()) {
                materials.add(new SampleAnalysisPayload.Material(g.materialId(), (double) g.solidVolumeSvq() / SolidVolume.SVQ_PER_CUBIC_METER, g.mass()));
                var amounts = new TreeMap<String, Long>();
                for (var s : g.composition()) { amounts.put(s.speciesId(), s.amountQuanta()); species.merge(s.speciesId(), s.amountQuanta(), Math::addExact); }
                if (g.massResidualUnits() != 0 || amounts.isEmpty()) completeSpecies = false;
                if (heat != null) portions.add(new ThermalStateFunction.InventoryPortion(world.thermalRegistry().materialHandle(CarrierKind.GRANULAR, g.materialId()), g.mass(), amounts));
            }
            if (heat != null) { heat.validateRegistryFingerprint(world.thermalRegistry().fingerprint()); energy = heat.internalEnergy(); temperature = new ThermalStateFunction(world.thermalRegistry()).stateForInventory(portions, energy).temperatureK(); }
        } else {
            phase = SampleAnalysisPayload.Phase.LIQUID;
            var heat = ContainerItemStacks.thermalContents(stack);
            if (stack.get(MmmComponents.CHEMICAL_CONTENTS) != null) {
                requireProfile(stack, world);
                var contents = ContainerItemStacks.chemicalContents(stack, world.chemicalRegistry());
                if (contents == null) throw new IllegalArgumentException("液体库存无效");
                volume = contents.totalVolumeNanos() * 1e-9; mass = Mass.kilograms(contents.totalMassUnits());
                for (var g : contents.groups()) {
                    materials.add(new SampleAnalysisPayload.Material(g.materialId(), g.volumeNanos() * 1e-9, g.massKilograms()));
                    if (g.massResidualUnits() != 0 || g.species().isEmpty()) completeSpecies = false;
                    for (var s : g.species()) species.merge(s.speciesId(), s.amountQuanta(), Math::addExact);
                }
                if (heat != null) { energy = heat.internalEnergy(); temperature = LiquidContainerThermodynamics.temperatureK(world.thermalRegistry(), heat, contents); }
            } else {
                FluidMixture mixture = ContainerItemStacks.fluidMixture(stack);
                if (mixture == null && (stack.is(Items.WATER_BUCKET) || stack.getItem() instanceof PartialWaterBucketItem)) {
                    long nanos = stack.is(Items.WATER_BUCKET) ? ContainerFluidLedger.NANOS_PER_BLOCK : PartialWaterBucketItem.volume(stack);
                    if (nanos <= 0) throw new IllegalArgumentException("水桶库存无效");
                    mixture = new FluidMixture(List.of(new FluidMixture.Component("mmm:water", nanos,
                            Mass.fromKilograms(nanos * 1e-9 * world.resolveMaterial("mmm:water").referenceDensity()))));
                } else if (mixture != null) requireProfile(stack, world);
                if (mixture == null) return SampleAnalysisPayload.empty(phase);
                volume = mixture.totalVolumeNanos() * 1e-9; mass = Mass.kilograms(mixture.totalMassUnits());
                for (var c : mixture.components()) materials.add(new SampleAnalysisPayload.Material(c.materialId(), c.volumeNanos() * 1e-9, c.mass()));
                completeSpecies = false;
                if (heat != null) {
                    energy = heat.internalEnergy();
                    if (stack.is(Items.WATER_BUCKET) || stack.getItem() instanceof PartialWaterBucketItem) {
                        heat.validateRegistryFingerprint(world.thermalRegistry().fingerprint());
                        var c = mixture.components().getFirst();
                        Map<String, Long> amounts = chemistry == null ? Map.of() : new mmm.chemistry.RegionChemistry(chemistry, 1)
                                .defaultRecord(c.materialId(), volume, 1.0F, c.massUnits()).stableAmounts(chemistry);
                        var portion = new ThermalStateFunction.InventoryPortion(world.thermalRegistry().materialHandle(CarrierKind.LIQUID, c.materialId()), c.mass(), amounts);
                        temperature = new ThermalStateFunction(world.thermalRegistry()).stateForInventory(List.of(portion), energy).temperatureK();
                    } else temperature = LiquidContainerThermodynamics.temperatureK(world.thermalRegistry(), chemistry, heat, mixture);
                }
            }
        }
        var composition = new ArrayList<SampleAnalysisPayload.Species>();
        double totalMoles = species.values().stream().mapToDouble(v -> (double) v / ChemicalLimits.QUANTA_PER_MOL).sum();
        for (var entry : species.entrySet()) {
            double moles = (double) entry.getValue() / ChemicalLimits.QUANTA_PER_MOL;
            double massFraction = chemistry != null && mass > 0
                    ? moles * chemistry.species(chemistry.speciesHandle(entry.getKey())).molarMassKgPerMol().toDouble() / mass
                    : Double.NaN;
            composition.add(new SampleAnalysisPayload.Species(entry.getKey(), entry.getValue(),
                    Double.isFinite(massFraction) ? Math.clamp(massFraction, 0, 1) : Double.NaN,
                    completeSpecies && totalMoles > 0 ? moles / totalMoles : Double.NaN));
        }
        // A container may have several carrier groups for the same material.
        var totals = new TreeMap<String, double[]>();
        for (var material : materials) {
            var total = totals.computeIfAbsent(material.id(), id -> new double[2]);
            total[0] += material.volumeM3(); total[1] += material.massKg();
        }
        var carrierRows = totals.entrySet().stream().map(e -> new SampleAnalysisPayload.Material(
                e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        return new SampleAnalysisPayload(phase, volume, mass, temperature, energy,
                completeSpecies && !species.isEmpty(), carrierRows, composition);
    }
    private static void requireProfile(ItemStack stack, CommittedWorldRegistries world) {
        if (!world.acceptsActualContainerProfile(stack)) throw new IllegalArgumentException("容器所属世界定义不同");
    }
}
