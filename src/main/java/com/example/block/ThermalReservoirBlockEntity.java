package com.example.block;

import com.example.thermal.ThermalLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** World-owned exact setpoint for the adjustable fixed-temperature boundary block. */
public final class ThermalReservoirBlockEntity extends BlockEntity {
	public static final double DEFAULT_TEMPERATURE_K = 293.15;
	private static final String TEMPERATURE_BITS_KEY = "temperature_bits";
	private static final long INVALID_TEMPERATURE_BITS = Double.doubleToRawLongBits(Double.NaN);

	private long temperatureBits = Double.doubleToRawLongBits(DEFAULT_TEMPERATURE_K);

	public ThermalReservoirBlockEntity(BlockPos position, BlockState state) {
		super(RealWaterBlocks.THERMAL_RESERVOIR_ENTITY, position, state);
	}

	public double temperatureK() {
		return Double.longBitsToDouble(temperatureBits);
	}

	public boolean hasValidTemperature() {
		double temperatureK = temperatureK();
		return Double.isFinite(temperatureK) && temperatureK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
	}

	public void setTemperatureK(double temperatureK) {
		if (!Double.isFinite(temperatureK) || temperatureK < ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K) {
			throw new IllegalArgumentException("Thermal reservoir temperature must be finite and at least "
					+ ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K + " K");
		}
		temperatureBits = Double.doubleToRawLongBits(temperatureK);
		setChanged();
		if (level != null) {
			BlockState state = getBlockState();
			level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		temperatureBits = input.getLong(TEMPERATURE_BITS_KEY).orElse(INVALID_TEMPERATURE_BITS);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putLong(TEMPERATURE_BITS_KEY, temperatureBits);
	}
}
