package mmm.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import mmm.chemistry.ChemicalLimits;
import mmm.fluid.air.GasRegistry;
import mmm.granular.SolidVolume;
import mmm.item.*;
import mmm.network.SampleAnalysisPayload;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalMaterialDefinition.CarrierKind;
import mmm.thermal.ThermalStateFunction;
import mmm.transfer.*;
import mmm.units.Mass;
import mmm.units.UnitFormat;
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
        PayloadTypeRegistry.clientboundPlay().register(SampleAnalysisPayload.TYPE, SampleAnalysisPayload.CODEC);
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!player.isShiftKeyDown() || !supported(player.getItemInHand(hand))
                    || player.pick(5.0, 1.0F, false).getType() != HitResult.Type.MISS) return InteractionResult.PASS;
            if (player instanceof ServerPlayer serverPlayer) {
                try {
                    var world = MmmRuntimeRegistry.require(serverPlayer.level().getServer()).worldRegistries();
                    if (ServerPlayNetworking.canSend(serverPlayer, SampleAnalysisPayload.TYPE))
                        ServerPlayNetworking.send(serverPlayer, analyze(player.getItemInHand(hand), world));
                    else serverPlayer.sendSystemMessage(Component.literal("样品分析界面不可用"));
                } catch (IllegalArgumentException | IllegalStateException failure) {
                    serverPlayer.sendSystemMessage(Component.literal("样品分析失败：" + failure.getMessage()));
                }
                return InteractionResult.SUCCESS_SERVER;
            }
            return InteractionResult.SUCCESS;
        });
    }
    private static boolean supported(ItemStack stack) {
        return stack.getItem() instanceof MaterialBucketItem || stack.getItem() instanceof GranularContainerItem
                || stack.getItem() instanceof GasContainerItem || stack.getItem() instanceof PartialWaterBucketItem
                || stack.is(Items.WATER_BUCKET);
    }
    static SampleAnalysisPayload analyze(ItemStack stack, CommittedWorldRegistries world) {
        var lines = new ArrayList<String>();
        var species = new TreeMap<String, Long>();
        var portions = new ArrayList<ThermalStateFunction.InventoryPortion>();
        var chemistry = world.hasCapability(WorldCapability.CHEMISTRY) ? world.chemicalRegistry() : null;
        double volume = 0, mass = 0, energy = Double.NaN, temperature = Double.NaN;
        boolean completeSpecies = true, gas = false;
        if (stack.getItem() instanceof GasContainerItem) {
            var contents = GasContainerItem.contents(stack);
            if (contents == null) return new SampleAnalysisPayload(List.of("空气体容器"));
            requireProfile(stack, world);
            if (!contents.gasRegistryFingerprint().equals(world.gasRegistry().fingerprint())
                    || !contents.chemicalRegistryFingerprint().equals(chemistry == null ? "" : chemistry.fingerprint()))
                throw new IllegalArgumentException("气体定义与当前世界不同");
            world.gasRegistry().requireCompatible(contents.inventory());
            volume = contents.inventory().standardVolumeNanos() * 1e-9;
            mass = Mass.kilograms(contents.inventory().totalMassUnits());
            gas = true; energy = contents.internalEnergy();
            for (var c : contents.inventory().components()) {
                lines.add("气体 " + c.componentId() + " · " + UnitFormat.volumeNanos(c.standardVolumeNanos()));
                if (c.massResidualUnits() != 0 || c.species().isEmpty()) completeSpecies = false;
                for (var s : c.species()) species.merge(s.speciesId(), s.amountQuanta(), Math::addExact);
            }
            temperature = new GasThermalModel(world.thermalRegistry(), world.gasRegistry()).state(contents.inventory(), energy).temperatureK();
        } else if (stack.getItem() instanceof GranularContainerItem) {
            var contents = ContainerItemStacks.granularContents(stack, world.granularRegistry(), chemistry);
            if (contents == null) return new SampleAnalysisPayload(List.of("空颗粒容器"));
            requireProfile(stack, world);
            volume = (double) contents.totalSolidVolumeSvq() / SolidVolume.SVQ_PER_CUBIC_METER;
            mass = contents.totalMass();
            var heat = ContainerItemStacks.thermalContents(stack);
            for (var g : contents.groups()) {
                lines.add("颗粒 " + g.materialId() + " · " + UnitFormat.solidVolume(g.solidVolumeSvq()) + " · " + number(g.mass()) + " kg");
                var amounts = new TreeMap<String, Long>();
                for (var s : g.composition()) { amounts.put(s.speciesId(), s.amountQuanta()); species.merge(s.speciesId(), s.amountQuanta(), Math::addExact); }
                if (g.massResidualUnits() != 0 || amounts.isEmpty()) completeSpecies = false;
                if (heat != null) portions.add(new ThermalStateFunction.InventoryPortion(world.thermalRegistry().materialHandle(CarrierKind.GRANULAR, g.materialId()), g.mass(), amounts));
            }
            if (heat != null) { heat.validateRegistryFingerprint(world.thermalRegistry().fingerprint()); energy = heat.internalEnergy(); temperature = new ThermalStateFunction(world.thermalRegistry()).stateForInventory(portions, energy).temperatureK(); }
        } else {
            var heat = ContainerItemStacks.thermalContents(stack);
            if (stack.get(MmmComponents.CHEMICAL_CONTENTS) != null) {
                requireProfile(stack, world);
                var contents = ContainerItemStacks.chemicalContents(stack, world.chemicalRegistry());
                if (contents == null) throw new IllegalArgumentException("液体库存无效");
                volume = contents.totalVolumeNanos() * 1e-9; mass = Mass.kilograms(contents.totalMassUnits());
                for (var g : contents.groups()) {
                    lines.add("液体 " + g.materialId() + " · " + UnitFormat.volumeNanos(g.volumeNanos()) + " · " + number(g.massKilograms()) + " kg");
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
                if (mixture == null) return new SampleAnalysisPayload(List.of("空液体容器"));
                volume = mixture.totalVolumeNanos() * 1e-9; mass = Mass.kilograms(mixture.totalMassUnits());
                for (var c : mixture.components()) lines.add("液体 " + c.materialId() + " · " + UnitFormat.volumeNanos(c.volumeNanos()) + " · " + number(c.mass()) + " kg");
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
        lines.add(0, "总质量：" + number(mass) + " kg");
        lines.add(0, (gas ? "标准体积（101325 Pa，293.15 K）：" : "总体积：") + UnitFormat.volume(volume));
        lines.add(2, Double.isFinite(temperature) ? "温度：" + number(temperature) + " K · 内能：" + UnitFormat.energy(energy) : "温度：无已保存热状态");
        if (species.isEmpty()) lines.add("物种：库存未记录物种组成");
        else {
            double totalMoles = species.values().stream().mapToDouble(v -> (double) v / ChemicalLimits.QUANTA_PER_MOL).sum();
            lines.add("物种组成：" + (completeSpecies ? "完整" : "含未记录物种的质量"));
            for (var entry : species.entrySet()) {
                double mol = (double) entry.getValue() / ChemicalLimits.QUANTA_PER_MOL;
                lines.add(entry.getKey() + " · " + UnitFormat.amount(entry.getValue()));
                if (chemistry != null) {
                    double speciesMass = mol * chemistry.species(chemistry.speciesHandle(entry.getKey())).molarMassKgPerMol().toDouble();
                    lines.add("质量分数：" + number(100 * speciesMass / mass) + "%" + (completeSpecies ? " · 摩尔分数：" + number(100 * mol / totalMoles) + "%" : ""));
                } else if (completeSpecies) lines.add("摩尔分数：" + number(100 * mol / totalMoles) + "%");
                if (!gas && volume > 0) lines.add((stack.getItem() instanceof GranularContainerItem ? "固体体积浓度：" : "样品总体积浓度：") + number(mol / volume) + " mol/m³");
                if (gas && volume > 0) lines.add("标准体积浓度：" + number(mol / volume) + " mol/m³");
            }
        }
        return new SampleAnalysisPayload(lines);
    }
    private static void requireProfile(ItemStack stack, CommittedWorldRegistries world) {
        if (!world.acceptsActualContainerProfile(stack)) throw new IllegalArgumentException("容器所属世界定义不同");
    }
    private static String number(double value) { return String.format(Locale.ROOT, "%.5g", value); }
}
