package mmm.block;

import java.util.UUID;
import mmm.api.sensor.ProcessSensorSnapshot;
import mmm.api.sensor.ProcessSensorSnapshot.Measure;
import mmm.api.sensor.ProcessSensorSnapshot.Status;

public final class ProcessSensorSyncValidation {
    public static void main(String[] args) {
        var region=UUID.randomUUID();
        var first=new ProcessSensorSnapshot(Measure.PRESSURE,Status.RUNNING,101325.0,region,2,3,1);
        var next=new ProcessSensorSnapshot(Measure.PRESSURE,Status.RUNNING,101325.0,region,2,4,1.05);
        check(ProcessSensorBlockEntity.shouldSync(null,first,0,0),"first update");
        check(!ProcessSensorBlockEntity.shouldSync(first,next,100,119),"stable values sent too often");
        check(ProcessSensorBlockEntity.shouldSync(first,next,100,120),"stable values were not refreshed");
        check(ProcessSensorBlockEntity.shouldSync(first,next,100,99),"world clock reset");
        var paused=new ProcessSensorSnapshot(Measure.PRESSURE,Status.PAUSED,101325.0,region,2,4,1.05);
        check(ProcessSensorBlockEntity.shouldSync(first,paused,100,101),"pause delayed");
        var changed=new ProcessSensorSnapshot(Measure.PRESSURE,Status.RUNNING,101326.0,region,2,4,1.05);
        check(ProcessSensorBlockEntity.shouldSync(first,changed,100,101),"value change delayed");
        var other=new ProcessSensorSnapshot(Measure.PRESSURE,Status.RUNNING,101325.0,region,3,4,1.05);
        check(ProcessSensorBlockEntity.shouldSync(first,other,100,101),"new region incarnation delayed");
        System.out.println("ProcessSensorSyncValidation passed");
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
