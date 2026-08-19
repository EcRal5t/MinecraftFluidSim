package com.example.simulation.persistence;

import com.example.fluid.FluidSolverConfig;
import com.example.simulation.FluidRegion;
import com.example.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32C;

/** Canonical CRC encoding independent of NBT field ordering. */
final class RegionStateIntegrity {
	private RegionStateIntegrity() { }

	static long sectionChecksum(RegionSectionState section) {
		return checksum(out -> {
			writeIdentity(out, section.regionId().getMostSignificantBits(), section.regionId().getLeastSignificantBits(),
					section.dimensionId(), section.checkpointGeneration());
			out.writeInt(section.sectionX()); out.writeInt(section.sectionY()); out.writeInt(section.sectionZ());
			out.writeInt(section.localStartX()); out.writeInt(section.localStartY()); out.writeInt(section.localStartZ());
			out.writeInt(section.sizeX()); out.writeInt(section.sizeY()); out.writeInt(section.sizeZ());
			writeInts(out, section.markerOrdinals());
			writeFloats(out, section.positionVolume());
			writeFloats(out, section.velocities());
			writeBytes(out, section.solidMask());
			writeExtensions(out, section.extensions());
		});
	}

	static long manifestChecksum(RegionManifest manifest) {
		return checksum(out -> {
			writeIdentity(out, manifest.regionId().getMostSignificantBits(), manifest.regionId().getLeastSignificantBits(),
					manifest.dimensionId(), manifest.checkpointGeneration());
			out.writeLong(manifest.networkGeneration());
			writeBlockPos(out, manifest.origin());
			out.writeInt(manifest.sizeX()); out.writeInt(manifest.sizeY()); out.writeInt(manifest.sizeZ());
			writeConfig(out, manifest.solverConfig());
			out.writeLong(manifest.reservedMarkerCapacity());
			writeRuntime(out, manifest.runtime());
			writeFootprint(out, manifest.footprint());
			out.writeInt(manifest.totalMarkerCount());
			out.writeLong(manifest.totalVolumeBits());
			List<RegionManifest.SectionDescriptor> descriptors = new ArrayList<>(manifest.sections());
			descriptors.sort(Comparator.naturalOrder());
			out.writeInt(descriptors.size());
			for (RegionManifest.SectionDescriptor descriptor : descriptors) {
				out.writeInt(descriptor.sectionX()); out.writeInt(descriptor.sectionY()); out.writeInt(descriptor.sectionZ());
				out.writeInt(descriptor.markerCount()); out.writeLong(descriptor.volumeBits()); out.writeLong(descriptor.checksum());
			}
			writeExtensions(out, manifest.extensions());
		});
	}

	private static long checksum(Writer writer) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream out = new DataOutputStream(bytes)) { writer.write(out); }
			CRC32C crc = new CRC32C();
			crc.update(bytes.toByteArray());
			return crc.getValue();
		} catch (IOException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static void writeIdentity(DataOutputStream out, long most, long least, String dimension, long generation) throws IOException {
		out.writeInt(1); out.writeInt(1);
		out.writeLong(most); out.writeLong(least); out.writeUTF(dimension); out.writeLong(generation);
	}

	private static void writeConfig(DataOutputStream out, FluidSolverConfig config) throws IOException {
		out.writeInt(Float.floatToRawIntBits(config.cellSize()));
		out.writeInt(Float.floatToRawIntBits(config.density()));
		out.writeInt(Float.floatToRawIntBits(config.gravity()));
		out.writeInt(Float.floatToRawIntBits(config.timeStep()));
		out.writeInt(config.maxPressureIterations());
		out.writeInt(Float.floatToRawIntBits(config.pressureTolerance()));
		out.writeInt(Float.floatToRawIntBits(config.maxCourantNumber()));
		out.writeInt(Float.floatToRawIntBits(config.fluidEpsilon()));
		out.writeInt(Float.floatToRawIntBits(config.interfaceCompression()));
	}

	private static void writeRuntime(DataOutputStream out, FluidRegion.PersistedRuntime runtime) throws IOException {
		out.writeUTF(runtime.resumeState().name());
		out.writeBoolean(runtime.entityPhysicsEnabled()); out.writeBoolean(runtime.debugOverlays());
		out.writeInt(runtime.stepIntervalTicks()); out.writeLong(runtime.tick());
	}

	private static void writeFootprint(DataOutputStream out, FluidRegionFootprint footprint) throws IOException {
		writeBlockPos(out, footprint.controllerPosition());
		writeBlockPos(out, footprint.minimumInterior());
		writeBlockPos(out, footprint.maximumInterior());
		writeLongSet(out, footprint.interiorBlocks());
		writeLongSet(out, footprint.boundaryBlocks());
		writeLongSet(out, footprint.requiredChunks());
	}

	private static void writeBlockPos(DataOutputStream out, BlockPos pos) throws IOException {
		out.writeInt(pos.getX()); out.writeInt(pos.getY()); out.writeInt(pos.getZ());
	}

	private static void writeLongSet(DataOutputStream out, Set<Long> values) throws IOException {
		long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
		out.writeInt(sorted.length);
		for (long value : sorted) out.writeLong(value);
	}

	private static void writeExtensions(DataOutputStream out, List<RegionExtension> extensions) throws IOException {
		out.writeInt(extensions.size());
		for (RegionExtension extension : extensions) {
			out.writeUTF(extension.type());
			writeBytes(out, extension.payload());
		}
	}

	private static void writeInts(DataOutputStream out, int[] values) throws IOException {
		out.writeInt(values.length);
		for (int value : values) out.writeInt(value);
	}

	private static void writeFloats(DataOutputStream out, float[] values) throws IOException {
		out.writeInt(values.length);
		for (float value : values) out.writeInt(Float.floatToRawIntBits(value));
	}

	private static void writeBytes(DataOutputStream out, byte[] values) throws IOException {
		out.writeInt(values.length); out.write(values);
	}

	@FunctionalInterface
	private interface Writer { void write(DataOutputStream out) throws IOException; }
}
