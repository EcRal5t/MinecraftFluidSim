package mmm.sensor;

import java.util.UUID;
import mmm.api.sensor.ProcessSensorSnapshot;
import mmm.api.sensor.ProcessSensorSnapshot.Measure;
import mmm.api.sensor.ProcessSensorSnapshot.Status;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;

/** Network-only block entity summary; the world save contains sensor configuration. */
public final class ProcessSensorSnapshotCodec {
    private ProcessSensorSnapshotCodec() { }
    public static void write(CompoundTag tag,ProcessSensorSnapshot value) {
        tag.putString("observation_measure",value.measure().name()); tag.putString("observation_status",value.status().name());
        tag.putBoolean("observation_has_value",value.value()!=null);
        if(value.value()!=null) tag.putDouble("observation_value",value.value());
        if(value.regionId()!=null) {
            tag.putString("observation_region",value.regionId().toString()); tag.putLong("observation_incarnation",value.regionIncarnation());
            tag.putLong("observation_generation",value.solverGeneration()); tag.putDouble("observation_seconds",value.simulationSeconds());
        }
    }
    public static ProcessSensorSnapshot read(ValueInput input,Measure fallback) {
        var status=input.getString("observation_status");
        if(status.isEmpty())return ProcessSensorSnapshot.waiting(fallback);
        var measure=Measure.valueOf(input.getStringOr("observation_measure",fallback.name()));
        Double value=input.getBooleanOr("observation_has_value",false)?input.getDoubleOr("observation_value",Double.NaN):null;
        var region=input.getString("observation_region");
        return new ProcessSensorSnapshot(measure,Status.valueOf(status.get()),value,
                region.map(UUID::fromString).orElse(null),input.getLongOr("observation_incarnation",0),
                input.getLongOr("observation_generation",0),input.getDoubleOr("observation_seconds",0));
    }
}
