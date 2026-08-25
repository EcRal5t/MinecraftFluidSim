package com.example.simulation.persistence;

import java.util.Arrays;
import java.util.Objects;

/** Length-delimited optional payload. Unknown types are retained without interpretation. */
public record RegionExtension(String type, byte[] payload) {
	public RegionExtension {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(payload, "payload");
		if (type.isBlank() || type.length() > 128) throw new IllegalArgumentException("Invalid extension type");
		if (payload.length > 16 * 1_048_576) throw new IllegalArgumentException("Region extension is too large");
		payload = payload.clone();
	}

	@Override
	public byte[] payload() {
		return payload.clone();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof RegionExtension extension
				&& type.equals(extension.type) && Arrays.equals(payload, extension.payload);
	}

	@Override
	public int hashCode() {
		return 31 * type.hashCode() + Arrays.hashCode(payload);
	}
}
