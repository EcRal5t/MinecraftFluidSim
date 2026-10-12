package mmm.compute;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

/** Bounded independent numerical work; every task finishes before a result or rejection returns. */
public final class CpuWorkBatch {
	private static final ForkJoinPool WORKERS = new ForkJoinPool(
			Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)));
	private CpuWorkBatch() { }
	@FunctionalInterface public interface RangeWork { void run(int first, int end); }

	public static void run(int count, RangeWork work) {
		run(count, 1_024, work);
	}

	public static void run(int count, int minimumBatchSize, RangeWork work) {
		if (count < 0 || minimumBatchSize <= 0 || work == null) throw new IllegalArgumentException("Invalid CPU work range");
		int batches = Math.min(WORKERS.getParallelism(), count / minimumBatchSize);
		if (batches <= 1) {
			if (count > 0) work.run(0, count);
			return;
		}
		ForkJoinTask<?>[] tasks = new ForkJoinTask<?>[batches];
		Throwable[] failures = new Throwable[batches];
		try {
			for (int batch = 0; batch < tasks.length; batch++) {
				int slot = batch, first = (int) ((long) count * batch / batches), end = (int) ((long) count * (batch + 1) / batches);
				tasks[batch] = WORKERS.submit(() -> range(first, end, work, failures, slot));
			}
		} finally {
			for (ForkJoinTask<?> task : tasks) if (task != null) task.join();
		}
		for (Throwable failure : failures) {
			if (failure instanceof RuntimeException rejected) throw rejected;
			if (failure instanceof Error rejected) throw rejected;
		}
	}

	private static void range(int first, int end, RangeWork work, Throwable[] failures, int slot) {
		try {
			work.run(first, end);
		} catch (RuntimeException | Error failure) { failures[slot] = failure; }
	}
}
