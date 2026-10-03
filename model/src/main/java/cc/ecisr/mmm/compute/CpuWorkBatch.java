package cc.ecisr.mmm.compute;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.IntConsumer;

/** Bounded independent numerical work; every task finishes before a result or rejection returns. */
public final class CpuWorkBatch {
	private static final ForkJoinPool WORKERS = new ForkJoinPool(
			Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
	private CpuWorkBatch() { }

	public static void run(int count, IntConsumer work) {
		run(count, 1_024, work);
	}

	public static void run(int count, int minimumBatchSize, IntConsumer work) {
		if (count < 0 || minimumBatchSize <= 0 || work == null) throw new IllegalArgumentException("Invalid CPU work range");
		int batches = Math.min(WORKERS.getParallelism(), count / minimumBatchSize);
		if (batches <= 1) {
			for (int index = 0; index < count; index++) work.accept(index);
			return;
		}
		ForkJoinTask<?>[] tasks = new ForkJoinTask<?>[batches];
		Throwable[] failures = new Throwable[batches];
		try {
			for (int batch = 0; batch < batches; batch++) {
				int slot = batch, first = (int) ((long) count * batch / batches), end = (int) ((long) count * (batch + 1) / batches);
				tasks[batch] = WORKERS.submit(() -> {
					try {
						for (int index = first; index < end; index++) work.accept(index);
					} catch (RuntimeException | Error failure) { failures[slot] = failure; }
				});
			}
		} finally {
			for (ForkJoinTask<?> task : tasks) if (task != null) task.join();
		}
		for (Throwable failure : failures) {
			if (failure instanceof RuntimeException rejected) throw rejected;
			if (failure instanceof Error rejected) throw rejected;
		}
	}
}
