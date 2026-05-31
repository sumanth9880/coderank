package com.coderank.coderank.service;

import com.coderank.coderank.entity.Language;
import com.coderank.coderank.entity.SubmissionStatus;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Service
public class CodeExecutorService {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutorService.class);

    // Resource caps — the cage.
    private static final long MEMORY_BYTES = 256L * 1024 * 1024; // 256 MB
    private static final long CPU_PERIOD   = 100_000L;           // 100ms scheduler window
    private static final long CPU_QUOTA    = 50_000L;            // use at most 50ms of it => 0.5 CPU
    private static final long PIDS_LIMIT   = 64L;                // max processes (anti fork-bomb)
    private static final int  TIMEOUT_SEC  = 10;                 // wall-clock limit

    private final DockerClient docker;

    public CodeExecutorService(DockerClient docker) {
        this.docker = docker;
    }

    public ExecutionResult execute(Language language, String sourceCode, String stdin) {
        long start = System.nanoTime();
        Path workDir = null;
        String containerId = null;

        try {
            // 1. Write the user's code to a temp file on the host.
            workDir = Files.createTempDirectory("coderank-");
            Path sourcePath = workDir.resolve(language.getSourceFile()); // e.g. main.py
            Files.writeString(sourcePath, sourceCode);

            // 2. Create the container — caged, but not started yet.
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withMemory(MEMORY_BYTES)
                    .withMemorySwap(MEMORY_BYTES)   // swap == memory => no swap allowed
                    .withCpuPeriod(CPU_PERIOD)
                    .withCpuQuota(CPU_QUOTA)
                    .withPidsLimit(PIDS_LIMIT)
                    .withNetworkMode("none");       // NO network at all

            String[] cmd = language.getRunCommand().split("\\s+"); // "python3 main.py" -> [python3, main.py]

            CreateContainerResponse container = docker.createContainerCmd(language.getDockerImage())
                    .withCmd(cmd)
                    .withWorkingDir("/app")
                    .withHostConfig(hostConfig)
                    .exec();
            containerId = container.getId();

            // 3. Copy the source file into the container at /app.
            docker.copyArchiveToContainerCmd(containerId)
                    .withHostResource(sourcePath.toString())
                    .withRemotePath("/app")
                    .exec();

            // 4. Start it and wait — but no longer than TIMEOUT_SEC.
            docker.startContainerCmd(containerId).exec();

            WaitContainerResultCallback waitCb = docker.waitContainerCmd(containerId)
                    .exec(new WaitContainerResultCallback());
            boolean finished = waitCb.awaitCompletion(TIMEOUT_SEC, TimeUnit.SECONDS);

            // 5. Collect stdout/stderr from the container logs.
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            docker.logContainerCmd(containerId)
                    .withStdOut(true).withStdErr(true).withFollowStream(false)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override public void onNext(Frame frame) {
                            String text = new String(frame.getPayload());
                            if (frame.getStreamType() == StreamType.STDERR) stderr.append(text);
                            else stdout.append(text);
                        }
                    }).awaitCompletion();

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // 6. Did it time out, or finish?
            if (!finished) {
                docker.killContainerCmd(containerId).exec();
                return new ExecutionResult(SubmissionStatus.TIMEOUT,
                        stdout.toString(), stderr.toString(), null, elapsedMs, null);
            }

            int exitCode = docker.inspectContainerCmd(containerId)
                    .exec().getState().getExitCodeLong().intValue();
            SubmissionStatus status = (exitCode == 0)
                    ? SubmissionStatus.SUCCESS : SubmissionStatus.ERROR;

            return new ExecutionResult(status,
                    stdout.toString(), stderr.toString(), exitCode, elapsedMs, null);

        } catch (Exception e) {
            log.error("Execution failed", e);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new ExecutionResult(SubmissionStatus.ERROR,
                    "", "Internal execution error: " + e.getMessage(), null, elapsedMs, null);
        } finally {
            // 7. Always clean up — destroy container + temp files.
            if (containerId != null) {
                try { docker.removeContainerCmd(containerId).withForce(true).exec(); }
                catch (Exception ignore) {}
            }
            if (workDir != null) {
                try {
                    Files.deleteIfExists(workDir.resolve(language.getSourceFile()));
                    Files.deleteIfExists(workDir);
                } catch (IOException ignore) {}
            }
        }
    }
}