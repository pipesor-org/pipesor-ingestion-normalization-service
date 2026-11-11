package org.pipesor.ingestion.capture.application.ports;

import java.time.Instant;
import java.util.List;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;

/** Private unprotected source data. Never publish or process with AI before normalization/protection. */
public interface GitHubMachineCapture {
    Run readRun(Request request);
    Log readJobLog(Request request, String jobId);
    StepLogs readStepLogs(Request request);
    record Request(CaptureScope scope,String runId,int attempt,String correlationId) {
        public Request { java.util.Objects.requireNonNull(scope);CaptureScope.external(runId);CaptureScope.safe(correlationId);if(attempt<1)throw new IllegalArgumentException("Invalid machine request"); }
        @Override public String toString(){return "MachineRequest[private]";}
    }
    record Step(int number,String name,String status,String conclusion,Instant startedAt,Instant completedAt) {
        @Override public String toString(){return "MachineStep[private]";}
    }
    record Job(String id,String name,String status,String conclusion,Instant startedAt,Instant completedAt,List<Step> steps) {
        public Job { steps=List.copyOf(steps); }
        @Override public String toString(){return "MachineJob[private]";}
    }
    record Run(Request request,String fullName,String branch,String commitSha,String status,String conclusion,
               Instant createdAt,Instant updatedAt,Instant startedAt,List<Job> jobs) {
        public Run { jobs=List.copyOf(jobs); }
        @Override public String toString(){return "MachineRun[private]";}
    }
    record StepLog(String jobId,int stepNumber,byte[] bytes) {
        public StepLog { bytes=bytes.clone(); }
        @Override public byte[] bytes(){return bytes.clone();}
        @Override public String toString(){return "MachineStepLog[private]";}
    }
    record StepLogs(Run run,List<StepLog> logs) {
        public StepLogs { logs=List.copyOf(logs); }
        @Override public String toString(){return "MachineStepLogs[private]";}
    }
    record Log(Run run,String jobId,byte[] bytes) {
        public Log { bytes=bytes.clone(); }
        @Override public byte[] bytes(){return bytes.clone();}
        @Override public String toString(){return "MachineLog[private]";}
    }
}
