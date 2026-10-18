package mmm.fluid;

import mmm.units.SignedDyadic;

/** Sparse chamber volume donors for the shared liquid/gas component pore allocation. */
record GasVolumeProposal(int[] nodeIds, SignedDyadic[] volumes, SignedDyadic[] minimumVolumes,
                         int[][] receivers, double[][] preferences) {
	GasVolumeProposal {
		int count = nodeIds.length;
		if (volumes.length != count || minimumVolumes.length != count || receivers.length != count || preferences.length != count)
			throw new IllegalArgumentException("Gas volume proposal columns differ");
		for (int i = 0; i < count; i++) {
			if (nodeIds[i] <= 0 || volumes[i].signum() <= 0 || minimumVolumes[i].signum() <= 0
					|| minimumVolumes[i].compareTo(volumes[i]) > 0 || receivers[i].length == 0
					|| receivers[i].length != preferences[i].length)
				throw new IllegalArgumentException("Invalid finite gas volume proposal");
		}
	}
}
