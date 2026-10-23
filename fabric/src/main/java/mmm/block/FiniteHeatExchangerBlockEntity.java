package mmm.block;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** World locator for the finite device inventory saved by its region. */
public final class FiniteHeatExchangerBlockEntity extends BlockEntity {
	private UUID regionId;
	private String objectId;

	public FiniteHeatExchangerBlockEntity(BlockPos position, BlockState state) {
		super(MmmBlocks.FINITE_HEAT_EXCHANGER_ENTITY, position, state);
	}
	public UUID regionId() { return regionId; }
	public String objectId() { return objectId; }

	public void bind(UUID regionId, String objectId) {
		this.regionId = Objects.requireNonNull(regionId, "Heat exchanger region is missing");
		this.objectId = Long.toString(Long.parseLong(objectId));
		if (!this.objectId.equals(Long.toString(getBlockPos().asLong())))
			throw new IllegalArgumentException("Heat exchanger locator differs from its world slot");
		setChanged();
	}

	@Override protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		String region = input.getString("region_id").orElse(null);
		String object = input.getString("object_id").orElse(null);
		regionId = region == null ? null : UUID.fromString(region);
		objectId = object == null ? null : Long.toString(Long.parseLong(object));
		if (objectId != null && !objectId.equals(Long.toString(getBlockPos().asLong())))
			throw new IllegalArgumentException("Heat exchanger locator differs from its world slot");
		if ((regionId == null) != (objectId == null)) throw new IllegalArgumentException("Incomplete heat exchanger region locator");
	}

	@Override protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (regionId != null) {
			output.putString("region_id", regionId.toString());
			output.putString("object_id", objectId);
		}
	}
}
