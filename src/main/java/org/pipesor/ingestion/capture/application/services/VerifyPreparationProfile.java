package org.pipesor.ingestion.capture.application.services;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.*;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation.*;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.INVALID_INPUT;

/** Closed, approved Verify pilot mapping. Its output remains private until mandatory protection. */
public final class VerifyPreparationProfile {
    private static final String MAVEN = "Compile, test with isolated PostgreSQL and package";
    private static final String IMAGE = "Build local non-root image";
    private static final Set<String> FAILED = Set.of("failure", "timed_out");
    private static final Pattern GOAL = Pattern.compile("\\[INFO] --- ([a-z-]+):([^ :]+):([a-zA-Z]+) \\([^)]*\\) @ [^\\r\\n]+ ---");
    private static final Pattern FAILURE = Pattern.compile("\\[ERROR] Failed to execute goal (?:[a-zA-Z0-9_.-]+:)?([a-z-]+):([^ :]+):([a-zA-Z]+)(?: .*)?");
    private final int maxInputBytes;

    public VerifyPreparationProfile(int maxInputBytes) {
        if (maxInputBytes < 1) { throw invalid(); }
        this.maxInputBytes = maxInputBytes;
    }

    public Classified classify(StepLogs source) {
        if (source == null || source.run() == null) { throw invalid(); }
        Run run = source.run();
        var request = run.request();
        if (request == null || !"1446318020".equals(request.scope().repositoryId())
                || !Set.of("412504984", "414901007").contains(request.scope().workflowId()) || !"completed".equals(run.status())
                || !("success".equals(run.conclusion()) || failed(run.conclusion()))
                || run.updatedAt() == null || run.startedAt() == null) { throw invalid(); }
        var jobs = run.jobs().stream().filter(job -> "verify".equals(job.name())).toList();
        if (jobs.size() != 1) { throw invalid(); }
        Job job = jobs.getFirst();
        if (!"completed".equals(job.status()) || !sameOutcome(run.conclusion(), job.conclusion())) { throw invalid(); }
        if (!source.aggregates().isEmpty() && failed(job.conclusion())) {
            if (run.jobs().stream().anyMatch(other -> other != job && !("skipped".equals(other.conclusion()) || "success".equals(other.conclusion())))) { throw invalid(); }
            return aggregate(source, job);
        }
        if (run.jobs().size() != 1) { throw invalid(); }
        if (!"412504984".equals(request.scope().workflowId())) { throw invalid(); }
        var numbers = new HashSet<Integer>();
        for (var step : job.steps()) { if (!numbers.add(step.number())) { throw invalid(); } }
        Step maven = step(job, 4, MAVEN), image = step(job, 5, IMAGE);
        boolean failed = failed(run.conclusion());
        var failures = job.steps().stream().filter(step -> failed(step.conclusion())).toList();
        if (failed ? failures.size() != 1 : !failures.isEmpty()) { throw invalid(); }
        Step failedStep = failed ? failures.getFirst() : null;
        if (failedStep != null && failedStep != maven && failedStep != image) { throw invalid(); }
        if (!"completed".equals(maven.status()) || (!"success".equals(maven.conclusion()) && failedStep != maven)
                || (failedStep == maven ? !"skipped".equals(image.conclusion())
                    : !"completed".equals(image.status()) || (!"success".equals(image.conclusion()) && failedStep != image))) { throw invalid(); }
        long total = 0;
        var logKeys = new HashSet<String>();
        for (var log : source.logs()) {
            if (log == null || !job.id().equals(log.jobId()) || !numbers.contains(log.stepNumber())
                    || !logKeys.add(log.jobId() + ":" + log.stepNumber())) { throw invalid(); }
            total += log.bytes().length;
            if (total > maxInputBytes) { throw invalid(); }
        }
        var segments = mavenSegments(lines(log(source, job, maven)), maven);
        var stages = new EnumMap<StageName, Bounds>(StageName.class);
        for (var segment : segments) { add(stages, segment.stage(), segment.start(), segment.end()); }
        Failure failure = null;
        Evidence evidence = null;
        String locator = null;
        if (failedStep == maven) {
            Segment segment = segments.getLast();
            var terminal = segment.lines().stream().filter(line -> FAILURE.matcher(line.text()).matches()).toList();
            long allFailures = segments.stream().flatMap(s -> s.lines().stream()).filter(line -> FAILURE.matcher(line.text()).matches()).count();
            if (terminal.size() != 1 || allFailures != 1) { throw invalid(); }
            var match = FAILURE.matcher(terminal.getFirst().text());
            if (!match.matches() || !plugin(match.group(1)).equals(segment.plugin()) || !match.group(3).equals(segment.goal())) { throw invalid(); }
            failure = new Failure(segment.stage(), "Maven " + segment.goal() + " failed", terminal.getFirst().time());
            evidence = new Evidence(segment.stage(), job.id(), join(segment.lines()), (long) segment.index());
            locator = locator(job, maven, segment.index());
        } else {
            if (segments.stream().flatMap(s -> s.lines().stream()).anyMatch(line -> FAILURE.matcher(line.text()).matches())) { throw invalid(); }
            if (!stages.containsKey(StageName.BUILD) || !stages.containsKey(StageName.TESTING)) { throw invalid(); }
            var imageLines = lines(log(source, job, image));
            times(image);
            add(stages, StageName.CONTAINER, image.startedAt(), image.completedAt());
            if (failedStep == image) {
                failure = new Failure(StageName.CONTAINER, "Container image step failed", image.completedAt());
                evidence = new Evidence(StageName.CONTAINER, job.id(), join(imageLines), 0L);
                locator = locator(job, image, 0);
            }
        }
        var observation = new WorkflowObservation(request.scope().context(), request.scope().pipeline(),
                new Execution(request.runId(), request.attempt(), run.branch(), run.status(), run.conclusion(), run.startedAt(), null),
                run.commitSha(), null, null, request.correlationId(), run.updatedAt(), run.updatedAt(),
                stages.entrySet().stream().map(e -> new Stage(e.getKey(), "completed", e.getValue().start(), e.getValue().end())).toList(),
                failure, evidence);
        return new Classified(observation, locator);
    }

    private Classified aggregate(StepLogs source, Job job) {
        var run = source.run();
        if (!failed(job.conclusion()) || source.aggregates().size() != 1) { throw invalid(); }
        var member = source.aggregates().getFirst();
        byte[] bytes = member.bytes();
        if (!job.id().equals(member.jobId()) || !member.member().matches("[0-9]{1,9}_verify\\.txt")
                || bytes.length > maxInputBytes || job.startedAt() == null || job.completedAt() == null) { throw invalid(); }
        var failures = job.steps().stream().filter(s -> failed(s.conclusion())).toList();
        if (failures.size() != 1) { throw invalid(); }
        var failedStep = failures.getFirst();
        boolean setup = "Set up job".equals(failedStep.name());
        if (!setup && !MAVEN.equals(failedStep.name())) { throw invalid(); }
        if (!"completed".equals(failedStep.status())) { throw invalid(); }
        times(failedStep);
        var sourceLines = lines(bytes);
        Line selected = null;
        int number = 0;
        StageName stage = StageName.INFRASTRUCTURE;
        for (int i = 0; i < sourceLines.size(); i++) {
            var line = sourceLines.get(i);
            var match = FAILURE.matcher(line.text());
            if (setup ? !line.text().matches("##\\[error\\]\\s*\\S.*") : !match.matches()) { continue; }
            if (selected != null || line.raw().length() > 4096 || line.time().isBefore(failedStep.startedAt())
                    || !line.time().isBefore(failedStep.completedAt().plusSeconds(1))) { throw invalid(); }
            if (!setup) { stage = stage(plugin(match.group(1)), match.group(3)); }
            selected = line;
            number = i + 1;
        }
        if (selected == null) { throw invalid(); }
        String digest;
        try {
            digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(selected.raw().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        String locator = "v2:job:" + job.id() + ":member:" + member.member() + ":lines:" + number + "-" + number + ":sha256:" + digest;
        var request = run.request();
        var observation = new WorkflowObservation(request.scope().context(), request.scope().pipeline(),
                new Execution(request.runId(), request.attempt(), run.branch(), run.status(), run.conclusion(), run.startedAt(), null),
                run.commitSha(), null, null, request.correlationId(), run.updatedAt(), run.updatedAt(),
                List.of(), new Failure(stage, setup ? "Job setup reported an error" : "Maven goal reported a failure", selected.time()),
                new Evidence(stage, job.id(), selected.raw(), null, locator));
        return new Classified(observation, locator);
    }

    private static boolean failed(String conclusion) { return conclusion != null && FAILED.contains(conclusion); }
    private static boolean sameOutcome(String run, String job) { return run.equals(job) || failed(run) && failed(job); }
    private static Step step(Job job, int number, String name) {
        var found = job.steps().stream().filter(s -> s.number() == number && name.equals(s.name())).toList();
        if (found.size() != 1) { throw invalid(); }
        return found.getFirst();
    }
    private static byte[] log(StepLogs source, Job job, Step step) {
        var found = source.logs().stream().filter(l -> job.id().equals(l.jobId()) && step.number() == l.stepNumber()).toList();
        if (found.size() != 1) { throw invalid(); }
        return found.getFirst().bytes();
    }
    private static List<Line> lines(byte[] bytes) {
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw invalid(); }
        if (text.isBlank() || text.indexOf('\u0000') >= 0 || text.indexOf('\u001b') >= 0) { throw invalid(); }
        var lines = new ArrayList<Line>();
        Instant previous = null;
        int offset = 0;
        while (offset < text.length()) {
            int newline = text.indexOf('\n', offset);
            int end = newline < 0 ? text.length() : newline + 1;
            String raw = text.substring(offset, end);
            String value = raw.stripTrailing();
            if (offset == 0 && value.startsWith("\uFEFF")) { value = value.substring(1); }
            int separator = value.indexOf(' ');
            if (separator < 1) { throw invalid(); }
            Instant time;
            try { time = Instant.parse(value.substring(0, separator)); }
            catch (RuntimeException invalid) { throw invalid(); }
            if (previous != null && time.isBefore(previous)) { throw invalid(); }
            lines.add(new Line(time, value.substring(separator + 1), raw));
            previous = time; offset = end;
        }
        return List.copyOf(lines);
    }
    private static List<Segment> mavenSegments(List<Line> lines, Step step) {
        times(step);
        var result = new ArrayList<Segment>();
        int begin = -1;
        String plugin = null, goal = null;
        StageName stage = null;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            var match = GOAL.matcher(line.text());
            if (!match.matches()) {
                if (line.text().startsWith("[INFO] ---") || begin < 0 && FAILURE.matcher(line.text()).matches()) { throw invalid(); }
                continue;
            }
            if (begin >= 0) { result.add(new Segment(result.size(), plugin, goal, stage, lines.get(begin).time(), line.time(), List.copyOf(lines.subList(begin, i)))); }
            plugin = plugin(match.group(1)); goal = match.group(3); stage = stage(plugin, goal); begin = i;
        }
        if (begin < 0) { throw invalid(); }
        Instant end = lines.getLast().time().isAfter(step.completedAt()) ? lines.getLast().time() : step.completedAt();
        result.add(new Segment(result.size(), plugin, goal, stage, lines.get(begin).time(), end, List.copyOf(lines.subList(begin, lines.size()))));
        if (result.getFirst().start().isBefore(step.startedAt())) { throw invalid(); }
        return List.copyOf(result);
    }
    private static String plugin(String value) {
        if (value.startsWith("maven-") && value.endsWith("-plugin")) { return value.substring(6, value.length() - 7); }
        if (value.equals("spring-boot-maven-plugin")) { return "spring-boot"; }
        return value;
    }
    private static StageName stage(String plugin, String goal) {
        return switch (plugin + ":" + goal) {
            case "clean:clean", "resources:resources", "resources:testResources", "compiler:compile", "compiler:testCompile", "jar:jar", "spring-boot:repackage" -> StageName.BUILD;
            case "surefire:test" -> StageName.TESTING;
            default -> throw invalid();
        };
    }
    private static void times(Step step) {
        if (step.startedAt() == null || step.completedAt() == null || step.completedAt().isBefore(step.startedAt())) { throw invalid(); }
    }
    private static void add(EnumMap<StageName, Bounds> stages, StageName stage, Instant start, Instant end) {
        var old = stages.get(stage);
        stages.put(stage, old == null ? new Bounds(start, end) : new Bounds(old.start().isBefore(start) ? old.start() : start, old.end().isAfter(end) ? old.end() : end));
    }
    private static String join(List<Line> lines) { return lines.stream().map(Line::raw).collect(java.util.stream.Collectors.joining()); }
    private static String locator(Job job, Step step, int index) { return "v1:job:" + job.id() + ":step:" + step.number() + ":index:" + index; }
    private static CaptureFailure invalid() { return new CaptureFailure(INVALID_INPUT); }
    private record Bounds(Instant start, Instant end) {}
    private record Line(Instant time, String text, String raw) { @Override public String toString() { return "SourceLine[private]"; } }
    private record Segment(int index, String plugin, String goal, StageName stage, Instant start, Instant end, List<Line> lines) {
        @Override public String toString() { return "SourceSegment[private]"; }
    }
    public record Classified(WorkflowObservation observation, String fragmentLocator) {
        @Override public String toString() { return "ClassifiedObservation[private]"; }
    }
}
