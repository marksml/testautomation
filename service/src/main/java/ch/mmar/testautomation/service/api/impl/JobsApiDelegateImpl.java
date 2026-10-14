package ch.mmar.testautomation.service.api.impl;

import ch.mmar.testautomation.service.api.JobsApiDelegate;
import ch.mmar.testautomation.service.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.NativeWebRequest;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class JobsApiDelegateImpl implements JobsApiDelegate {
    // Implement the methods defined in JobsApiDelegate here
    private final ObjectProvider<NativeWebRequest> requestProvider;

    public JobsApiDelegateImpl(ObjectProvider<NativeWebRequest> requestProvider) {
        this.requestProvider = requestProvider;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        log.info("getRequest called");
        return Optional.ofNullable(requestProvider.getIfAvailable());
    }

    @Override
    public ResponseEntity<ClaimedJobRun> claimJobRun(JobClaimRequest jobClaimRequest, Integer wait) {
        log.info("claimJobRun requested by agent {} (wait={})", jobClaimRequest.getAgentId(), wait);
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @Override
    public ResponseEntity<JobRun> getJobRun(UUID jobRunId) {
        log.info("getJobRun requested for jobRunId={}", jobRunId);
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @Override
    public ResponseEntity<JobRunPage> listJobRuns(String issueKey, JobRunStatus status, Integer limit, Integer offset) {
        log.info("listJobRuns requested with issueKey={}, status={}, limit={}, offset={}", issueKey, status, limit, offset);
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @Override
    public ResponseEntity<JobRun> registerJobRun(JobRunRegistration jobRunRegistration, String idempotencyKey) {
        log.info("registerJobRun requested with idempotencyKey={}", idempotencyKey);
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @Override
    public ResponseEntity<JobRunLease> renewJobRunLease(UUID jobRunId) {
        log.info("renewJobRunLease requested for jobRunId={}", jobRunId);
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @Override
    public ResponseEntity<JobRunResultAck> submitJobRunResult(UUID jobRunId, JobRunResult jobRunResult) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }
}
