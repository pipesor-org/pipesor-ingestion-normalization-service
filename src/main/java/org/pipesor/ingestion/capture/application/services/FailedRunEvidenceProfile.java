package org.pipesor.ingestion.capture.application.services;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.AggregateLog;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.Job;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.Run;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.Step;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.StepLogs;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.SourceLocator;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation.Evidence;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation.Execution;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation.Failure;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation.Stage;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation.StageName;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.INVALID_INPUT;

/**
 * General GitHub Actions preparation profile (protected evidence 2.2). For a failed run it selects
 * the first failed job and step, reads that step's log or, when GitHub only provides the job's
 * aggregate log, the lines inside the failed step's time window, and freezes one contiguous
 * excerpt of at most {@value #EXCERPT_LINES} lines ending at the most specific error line. The
 * excerpt stays private until mandatory protection; its locator binds member, lines and digest.
 */
public final class FailedRunEvidenceProfile {
    public static final String VERSION = "failed-run-evidence-v1";
    static final int EXCERPT_LINES = 40;
    private static final Set<String> FAILED = Set.of("failure", "timed_out");
    private static final Set<String> CONCLUSIONS = Set.of("success", "failure", "timed_out", "cancelled", "skipped", "neutral");
    private static final Pattern GENERIC_EXIT = Pattern.compile("##\\[error\\]\\s*Process completed with exit code \\d+\\.?");
    private static final List<Pattern> ERRORS = List.of(
            Pattern.compile("##\\[error\\].*"),
            Pattern.compile("\\[ERROR\\].*"),
            Pattern.compile("(?i)^(npm ERR!|npm error|yarn error|error TS\\d+|error\\[E\\d+\\]|ERROR:|Error:|E\\s{2,}\\S|FAILED|FAILURE:|BUILD FAILED|fatal:|panic:|Traceback).*"),
            Pattern.compile("(?i).*\\b(failed to solve|exception in thread|tests? failed|failures: [1-9]|errors: [1-9]|could not resolve|permission denied|timed out)\\b.*"),
            // Exception summaries such as "RuntimeError: …" or "java.lang.IllegalStateException: …".
            Pattern.compile("^\\s*(Caused by: )?[A-Za-z_][\\w.$]*(Error|Exception)(: .*)?$"));
    /** Lines kept after the anchor so trailing summaries are not cut off (the generic exit line ends it). */
    static final int TRAILING_LINES = 3;
    private final int maxInputBytes;
    private final int maxExcerptBytes;

    public record Classified(WorkflowObservation observation, String fragmentLocator) {
        @Override public String toString() { return "ClassifiedObservation[private]"; }
    }

    public FailedRunEvidenceProfile(int maxInputBytes, int maxExcerptBytes) {
        if (maxInputBytes < 1 || maxExcerptBytes < 1) throw invalid();
        this.maxInputBytes = maxInputBytes;
        this.maxExcerptBytes = maxExcerptBytes;
    }

    public Classified classify(StepLogs source) {
        if (source == null || source.run() == null || source.run().request() == null) throw invalid();
        Run run = source.run();
        if (!"completed".equals(run.status()) || !CONCLUSIONS.contains(run.conclusion()) || run.updatedAt() == null
                || run.startedAt() == null || run.jobs().isEmpty()) throw invalid();
        long total = 0;
        for (var log : source.logs()) total += log.bytes().length;
        for (var aggregate : source.aggregates()) total += aggregate.bytes().length;
        if (total > maxInputBytes) throw invalid();
        var stages = stages(run);
        Failure failure = null;
        Evidence evidence = null;
        String locator = null;
        if (FAILED.contains(run.conclusion())) {
            Job job = run.jobs().stream().filter(candidate -> FAILED.contains(candidate.conclusion()))
                    .min(Comparator.comparing((Job candidate) -> candidate.completedAt() == null ? Instant.MAX : candidate.completedAt())
                            .thenComparing(Job::id))
                    .orElseThrow(FailedRunEvidenceProfile::invalid);
            Step step = job.steps().stream().filter(candidate -> FAILED.contains(candidate.conclusion()))
                    .min(Comparator.comparingInt(Step::number)).orElse(null);
            var text = text(source, job, step);
            var excerpt = excerpt(text, step);
            StageName stage = stage(step, excerpt.text());
            failure = new Failure(stage, summary(stage), excerpt.detectedAt());
            locator = SourceLocator.of(job.id(), text.member(), excerpt.first(), excerpt.last(), sha256(excerpt.raw()));
            // The digest covers the original bytes; the excerpt handed to protection drops the BOM and terminal
            // escape sequences (GitHub logs often carry ANSI colors), which protection would refuse as control characters.
            String visible = visible(excerpt.raw());
            evidence = new Evidence(stage, job.id(), visible, null, locator);
        }
        var request = run.request();
        var observation = new WorkflowObservation(request.scope().context(), request.scope().pipeline(),
                new Execution(request.runId(), request.attempt(), run.branch(), run.status(), run.conclusion(), run.startedAt(), null),
                run.commitSha(), null, null, request.correlationId(), run.updatedAt(), run.updatedAt(),
                stages, failure, evidence);
        return new Classified(observation, locator);
    }

    private record Line(Instant time, String text, String raw) {
        @Override public String toString() { return "SourceLine[private]"; }
    }
    private record Text(String member, List<Line> lines, int from, int to) { }
    private record Excerpt(int first, int last, String raw, String text, Instant detectedAt) {
        @Override public String toString() { return "Excerpt[private]"; }
    }

    /** The failed step's own member when GitHub provides it, else the job aggregate member within the step's window. */
    private Text text(StepLogs source, Job job, Step step) {
        if (step != null) {
            var own = source.logs().stream().filter(log -> job.id().equals(log.jobId()) && log.stepNumber() == step.number()).toList();
            if (own.size() > 1) throw invalid();
            if (own.size() == 1) {
                var lines = lines(own.getFirst().bytes());
                return new Text(job.name() + "/" + step.number() + "_" + step.name() + ".txt", lines, 0, lines.size() - 1);
            }
        }
        var aggregates = source.aggregates().stream().filter(log -> job.id().equals(log.jobId())).toList();
        if (aggregates.size() != 1) throw invalid();
        AggregateLog aggregate = aggregates.getFirst();
        var lines = lines(aggregate.bytes());
        int from = 0, to = lines.size() - 1;
        if (step != null && step.startedAt() != null && step.completedAt() != null && !step.completedAt().isBefore(step.startedAt())) {
            Instant end = step.completedAt().plusSeconds(1);
            from = -1;
            for (int i = 0; i < lines.size(); i++) {
                var time = lines.get(i).time();
                if (time.isBefore(step.startedAt()) || !time.isBefore(end)) continue;
                if (from < 0) from = i;
                to = i;
            }
            if (from < 0) throw invalid();
        }
        return new Text(aggregate.member(), lines, from, to);
    }

    private Excerpt excerpt(Text text, Step step) {
        int anchor = -1, generic = -1;
        for (int i = text.from(); i <= text.to(); i++) {
            String value = text.lines().get(i).text();
            if (GENERIC_EXIT.matcher(value).matches()) { generic = i; continue; }
            if (ERRORS.stream().anyMatch(pattern -> pattern.matcher(value).matches())) anchor = i;
        }
        if (anchor < 0) anchor = generic >= 0 ? generic : text.to();
        int end = anchor;
        while (end < text.to() && end - anchor < TRAILING_LINES
                && !GENERIC_EXIT.matcher(text.lines().get(end + 1).text()).matches()
                && !text.lines().get(end + 1).text().isBlank()) {
            end++;
        }
        int start = Math.max(text.from(), end - (EXCERPT_LINES - 1));
        String raw = raw(text.lines(), start, end);
        while (raw.getBytes(StandardCharsets.UTF_8).length > maxExcerptBytes && start < anchor) {
            start++;
            raw = raw(text.lines(), start, end);
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > maxExcerptBytes) throw invalid();
        var selected = text.lines().subList(start, end + 1);
        String plain = String.join("\n", selected.stream().map(Line::text).toList());
        Instant detected = text.lines().get(anchor).time();
        if (step != null && step.completedAt() != null && detected.isAfter(step.completedAt().plusSeconds(1))) throw invalid();
        return new Excerpt(start + 1, end + 1, raw, plain, detected);
    }

    private static final Pattern TERMINAL_ESCAPES = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]");

    static String visible(String raw) {
        String text = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        return TERMINAL_ESCAPES.matcher(text).replaceAll("");
    }

    private static String raw(List<Line> lines, int from, int to) {
        var out = new StringBuilder();
        for (int i = from; i <= to; i++) out.append(lines.get(i).raw());
        return out.toString();
    }

    private static List<Stage> stages(Run run) {
        var bounds = new EnumMap<StageName, Instant[]>(StageName.class);
        for (var job : run.jobs()) {
            for (var step : job.steps()) {
                if (step.startedAt() == null || step.completedAt() == null || step.completedAt().isBefore(step.startedAt())
                        || "skipped".equals(step.conclusion())) continue;
                var stage = stage(step, "");
                var current = bounds.get(stage);
                if (current == null) bounds.put(stage, new Instant[]{step.startedAt(), step.completedAt()});
                else {
                    if (step.startedAt().isBefore(current[0])) current[0] = step.startedAt();
                    if (step.completedAt().isAfter(current[1])) current[1] = step.completedAt();
                }
            }
        }
        var stages = new ArrayList<Stage>();
        bounds.forEach((stage, range) -> stages.add(new Stage(stage, "completed", range[0], range[1])));
        return List.copyOf(stages);
    }

    /** Coarse stage from the step name and, for the failed step, its excerpt. Unknown steps are BUILD. */
    static StageName stage(Step step, String excerpt) {
        String name = step == null ? "" : step.name().toLowerCase(Locale.ROOT);
        String text = excerpt.toLowerCase(Locale.ROOT);
        if (step == null || name.equals("set up job") || name.equals("complete job") || name.startsWith("set up ")
                || name.startsWith("run actions/checkout") || name.startsWith("post ") || text.contains("unable to resolve action")
                || text.contains("could not resolve host") || text.contains("runner") && text.contains("lost communication")) {
            return StageName.INFRASTRUCTURE;
        }
        if (name.matches(".*\\b(docker|image|container|buildx)\\b.*") || text.contains("failed to solve")) return StageName.CONTAINER;
        if (name.matches(".*\\b(deploy|release|publish|helm|kubectl|terraform apply)\\b.*")) return StageName.DEPLOYMENT;
        if (name.matches(".*\\b(lint|commitlint|validate|sonar|quality|scan|codeql|coverage|format|checkstyle|audit)\\b.*")) return StageName.QUALITY_GATE;
        if (name.matches(".*\\b(test|tests|pytest|jest|junit|e2e)\\b.*") || text.contains("surefire") || text.contains("tests run:")
                && text.contains("failures: ") || text.matches("(?s).*\\bfailed\\b.*\\btests?\\b.*")) return StageName.TESTING;
        return StageName.BUILD;
    }

    private static String summary(StageName stage) {
        return switch (stage) {
            case BUILD -> "Build step reported a failure";
            case TESTING -> "Test step reported a failure";
            case QUALITY_GATE -> "Quality gate step reported a failure";
            case DEPLOYMENT -> "Deployment step reported a failure";
            case CONTAINER -> "Container step reported a failure";
            case INFRASTRUCTURE -> "Runner or job setup reported an error";
        };
    }

    private static List<Line> lines(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) { throw invalid(); }
        if (text.isBlank() || text.indexOf('\u0000') >= 0) throw invalid();
        var lines = new ArrayList<Line>();
        Instant previous = null;
        int offset = 0;
        while (offset < text.length()) {
            int newline = text.indexOf('\n', offset);
            int end = newline < 0 ? text.length() : newline + 1;
            String raw = text.substring(offset, end);
            String value = raw.stripTrailing();
            if (offset == 0 && value.startsWith("﻿")) value = value.substring(1);
            Instant time = null;
            int separator = value.indexOf(' ');
            if (separator > 0) {
                try { time = Instant.parse(value.substring(0, separator)); } catch (RuntimeException notTimestamped) { time = null; }
            }
            if (time == null) {
                if (previous == null) throw invalid();
                lines.add(new Line(previous, value, raw));
            } else {
                if (previous != null && time.isBefore(previous)) time = previous;
                lines.add(new Line(time, value.substring(separator + 1), raw));
                previous = time;
            }
            offset = end;
        }
        return List.copyOf(lines);
    }

    private static byte[] sha256(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static CaptureFailure invalid() { return new CaptureFailure(INVALID_INPUT); }
}
