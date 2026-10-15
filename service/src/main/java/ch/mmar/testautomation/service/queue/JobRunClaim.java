package ch.mmar.testautomation.service.queue;

import ch.mmar.testautomation.service.model.JobRunSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One claimed run, read straight from the {@code jobs} row the claim statement returned.
 *
 * <p>Everything an agent needs to execute the run is in here: a DB-less agent must not have to look
 * anything up elsewhere (docs/service-api-sequences.md §2.1). The heartbeat interval is service
 * configuration rather than row data, so it is added when this is mapped onto the wire model.
 */
public record JobRunClaim(
        UUID jobRunId,
        String issueKey,
        String testName,
        JobRunSource source,
        Map<String, String> parameters,
        List<String> requiresCapabilities,
        int attempt,
        Instant createdAt,
        Instant leaseExpiresAt) {
}
