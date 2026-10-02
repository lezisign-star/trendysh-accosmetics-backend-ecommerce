package com.nbh.backend.imagecleanup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A batch only leaves IN_PROGRESS when its own background job finishes. If the app restarts mid-run (a deploy)
 * that job just dies, and since only one batch may run at a time a single orphan would block every future run.
 * Anything still IN_PROGRESS at boot is therefore definitely stale and is marked INTERRUPTED.
 */
@Slf4j
@Component
public class StuckImageCleanupReconciler implements ApplicationRunner {

    private final ImageCleanupBatchRepository batches;

    public StuckImageCleanupReconciler(ImageCleanupBatchRepository batches) {
        this.batches = batches;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var stuck = batches.findByStatus(ImageCleanupBatch.Status.IN_PROGRESS);
        for (ImageCleanupBatch b : stuck) {
            b.setStatus(ImageCleanupBatch.Status.INTERRUPTED);
            b.setLastError("The app restarted while this batch was running.");
            batches.save(b);
        }
        if (!stuck.isEmpty()) {
            log.warn("Marked {} image-cleanup batch(es) INTERRUPTED on boot", stuck.size());
        }
    }
}
