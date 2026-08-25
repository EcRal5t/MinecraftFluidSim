package cc.ecisr.mmm.thermal;

import java.util.Comparator;

/** Provider-stable identity and authoritative body truth for one finite-capacity thermal device. */
public record FiniteThermalDeviceState(
		String providerId,
		String objectId,
		String deviceTypeId,
		FiniteThermalBodyState body
) implements Comparable<FiniteThermalDeviceState> {
	private static final Comparator<FiniteThermalDeviceState> ORDER = Comparator
			.comparing(FiniteThermalDeviceState::providerId)
			.thenComparing(FiniteThermalDeviceState::objectId);

	public FiniteThermalDeviceState {
		ThermalIds.requireId(providerId, "finite thermal device provider id");
		ThermalIds.requireId(deviceTypeId, "finite thermal device type id");
		if (objectId == null || objectId.isEmpty() || objectId.length() > ThermalLimits.MAX_DEVICE_OBJECT_ID_LENGTH) {
			throw new IllegalArgumentException("Finite thermal device object id is missing or over budget");
		}
		for (int index = 0; index < objectId.length(); index++) {
			char value = objectId.charAt(index);
			if (value < 0x21 || value > 0x7e) {
				throw new IllegalArgumentException("Finite thermal device object id must be printable ASCII");
			}
		}
		if (body == null) throw new IllegalArgumentException("Finite thermal device body is missing");
	}

	@Override public int compareTo(FiniteThermalDeviceState other) { return ORDER.compare(this, other); }

	public boolean sameObject(FiniteThermalDeviceState other) {
		return other != null && providerId.equals(other.providerId) && objectId.equals(other.objectId);
	}
}
