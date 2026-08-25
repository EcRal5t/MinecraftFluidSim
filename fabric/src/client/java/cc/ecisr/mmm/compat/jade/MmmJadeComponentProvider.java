package cc.ecisr.mmm.compat.jade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Client-only formatting of the bounded server summary. */
final class MmmJadeComponentProvider implements IBlockComponentProvider {
	static final MmmJadeComponentProvider INSTANCE = new MmmJadeComponentProvider();

	private MmmJadeComponentProvider() { }

	@Override
	public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
		Tag encoded = accessor.getServerData().get(MmmJadeServerDataProvider.UID.toString());
		if (!(encoded instanceof CompoundTag tag)) return;
		Optional<JadeFluidSummary> decoded = JadeFluidSummary.decode(tag);
		if (decoded.isEmpty()) return;
		JadeFluidSummary summary = decoded.orElseThrow();
		tooltip.add(Component.translatable("jade.mmm.inventory", decimal(summary.volumeCubicMetres() * 1_000.0),
				decimal(summary.massKilograms())));
		tooltip.add(Component.translatable("jade.mmm.density", decimal(summary.densityKilogramsPerCubicMetre())));
		if (summary.temperatureKelvin() != null) {
			tooltip.add(Component.translatable("jade.mmm.temperature", decimal(summary.temperatureKelvin())));
		}
		if (!summary.materials().isEmpty()) {
			String values = summarize(summary.materials(), summary.materialCount(), JadeFluidSummary.Material::id);
			tooltip.add(Component.translatable("jade.mmm.materials", values));
		}
		if (!summary.species().isEmpty()) {
			String values = summarize(summary.species(), summary.speciesCount(),
					entry -> entry.id() + " " + entry.quanta() + " q");
			tooltip.add(Component.translatable("jade.mmm.species", values));
		}
	}

	@Override
	public Identifier getUid() {
		return MmmJadeServerDataProvider.UID;
	}

	private static <T> String summarize(List<T> values, int totalCount, Function<T, String> display) {
		String summary = values.stream().map(display).collect(Collectors.joining(", "));
		int hidden = totalCount - values.size();
		return hidden == 0 ? summary : summary + " (+" + hidden + ")";
	}

	private static String decimal(double value) {
		double magnitude = Math.abs(value);
		String format = magnitude >= 100.0 ? "%.0f" : magnitude >= 10.0 ? "%.1f" : "%.2f";
		return String.format(Locale.ROOT, format, value);
	}
}
