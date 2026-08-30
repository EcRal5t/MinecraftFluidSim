package cc.ecisr.mmm.application;

import cc.ecisr.mmm.application.command.MotionStepCommand;
import cc.ecisr.mmm.application.command.ThermalStepCommand;
import cc.ecisr.mmm.application.result.MotionStepResult;
import cc.ecisr.mmm.application.result.ThermalStepResult;

/** Application-internal boundary used by tick scheduling to execute one candidate step. */
public interface RegionStepExecutor {
	MotionStepResult stepMotion(MotionStepCommand command);
	ThermalStepResult stepThermal(ThermalStepCommand command);
}
