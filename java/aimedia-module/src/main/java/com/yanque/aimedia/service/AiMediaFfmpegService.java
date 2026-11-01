package com.yanque.aimedia.service;

import cn.hutool.core.util.StrUtil;
import com.yanque.aimedia.config.AiMediaProcessingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FFmpeg 基础能力层，负责提取音频和精确裁切视频，不处理任务状态。
 */
@Service
public class AiMediaFfmpegService {
    private static final Logger log = LoggerFactory.getLogger(AiMediaFfmpegService.class);
    private static final int MAX_ERROR_LENGTH = 900;

    private final AiMediaProcessingProperties properties;

    public AiMediaFfmpegService(AiMediaProcessingProperties properties) {
        this.properties = properties;
    }

    public ExtractedAudio extractMp3(String videoInput, Long taskId) {
        Path audioFile = null;
        Path logFile = null;
        try {
            // 每次任务使用随机临时文件名，避免并发任务互相覆盖。
            Path tempDirectory = resolveTempDirectory();
            audioFile = Files.createTempFile(tempDirectory, "media-audio-" + taskId + "-", ".mp3");
            logFile = Files.createTempFile(tempDirectory, "media-ffmpeg-" + taskId + "-", ".log");

            // ProcessBuilder 按参数传递命令，视频 URL 不会经过 Shell 解释。
            List<String> command = List.of(
                    properties.getFfmpegPath(),
                    "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-i", videoInput,
                    "-vn", "-ac", "1", "-ar", "16000",
                    "-codec:a", "libmp3lame", "-b:a", "64k",
                    audioFile.toString()
            );

            // FFmpeg 日志重定向到文件，避免错误输出填满进程管道后造成死锁。
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();

            // 长视频转换时间较长，但仍需要超时保护，防止异常进程永久占用线程。
            boolean completed = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg 执行超时");
            }
            if (process.exitValue() != 0) {
                // 只读取有限长度的错误信息，最终会写入步骤表 error_message。
                throw new IllegalStateException("FFmpeg 执行失败：" + readError(logFile));
            }

            // 退出码为 0 后继续校验文件，防止得到空音频。
            long fileSize = Files.size(audioFile);
            if (fileSize <= 0) {
                throw new IllegalStateException("FFmpeg 未生成有效音频文件");
            }
            return new ExtractedAudio(audioFile, fileSize);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            deleteQuietly(audioFile);
            throw new IllegalStateException("FFmpeg 执行被中断", exception);
        } catch (IOException exception) {
            deleteQuietly(audioFile);
            throw new IllegalStateException("无法执行 FFmpeg：" + exception.getMessage(), exception);
        } finally {
            deleteQuietly(logFile);
        }
    }

    /**
     * 按毫秒时间范围生成 MP4 素材。
     *
     * 输出文件只存在于操作系统临时目录，调用方必须在上传结束后立即删除。
     */
    public RenderedVideo renderVideoClip(String videoInput,
                                         Long taskId,
                                         Integer segmentNo,
                                         long startMs,
                                         long endMs) {
        if (startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("视频素材裁切时间范围无效");
        }

        Path videoFile = null;
        Path logFile = null;
        try {
            Path tempDirectory = resolveTempDirectory();
            videoFile = Files.createTempFile(
                    tempDirectory, "media-clip-" + taskId + "-" + segmentNo + "-", ".mp4");
            logFile = Files.createTempFile(
                    tempDirectory, "media-clip-ffmpeg-" + taskId + "-" + segmentNo + "-", ".log");

            String startSeconds = formatSeconds(startMs);
            String durationSeconds = formatSeconds(endMs - startMs);
            List<String> command = List.of(
                    properties.getFfmpegPath(),
                    "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-ss", startSeconds,
                    "-i", videoInput,
                    "-t", durationSeconds,
                    "-map", "0:v:0", "-map", "0:a:0?",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
                    "-c:a", "aac", "-b:a", "128k",
                    "-movflags", "+faststart", "-avoid_negative_ts", "make_zero",
                    videoFile.toString()
            );

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            boolean completed = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg 视频裁切超时");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("FFmpeg 视频裁切失败：" + readError(logFile));
            }

            long fileSize = Files.size(videoFile);
            if (fileSize <= 0) {
                throw new IllegalStateException("FFmpeg 未生成有效视频片段");
            }
            return new RenderedVideo(videoFile, fileSize);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            deleteQuietly(videoFile);
            throw new IllegalStateException("FFmpeg 视频裁切被中断", exception);
        } catch (IOException exception) {
            deleteQuietly(videoFile);
            throw new IllegalStateException("无法执行 FFmpeg 视频裁切：" + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            // FFmpeg 非零退出或输出文件无效时，调用方拿不到文件路径，因此在这里完成清理。
            deleteQuietly(videoFile);
            throw exception;
        } finally {
            deleteQuietly(logFile);
        }
    }

    private String formatSeconds(long milliseconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", milliseconds / 1000D);
    }

    private Path resolveTempDirectory() throws IOException {
        // 未配置专用目录时使用 JVM 所在操作系统的临时目录。
        if (StrUtil.isBlank(properties.getTempDir())) {
            return Paths.get(System.getProperty("java.io.tmpdir"));
        }
        Path directory = Paths.get(properties.getTempDir()).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        return directory;
    }

    private String readError(Path logFile) {
        try {
            String message = Files.readString(logFile, StandardCharsets.UTF_8).trim();
            if (message.isEmpty()) {
                return "未返回错误信息";
            }
            return message.length() <= MAX_ERROR_LENGTH
                    ? message : message.substring(message.length() - MAX_ERROR_LENGTH);
        } catch (IOException exception) {
            return "无法读取 FFmpeg 错误日志";
        }
    }

    public void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            // 清理失败不覆盖真正的处理异常，但会记录并在 JVM 退出时再次删除。
            file.toFile().deleteOnExit();
            log.warn("删除 FFmpeg 临时文件失败，已登记退出时清理: file={}", file, exception);
        }
    }

    public record ExtractedAudio(Path file, long fileSize) {
    }

    public record RenderedVideo(Path file, long fileSize) {
    }
}
