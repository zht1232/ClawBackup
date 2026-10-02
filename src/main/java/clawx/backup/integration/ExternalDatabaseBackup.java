package clawx.backup.integration;

import clawx.backup.config.BackupConfig;
import clawx.backup.config.ExternalDatabaseJob;
import clawx.backup.util.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Runs explicitly configured native database dump and restore tools without a shell. */
public final class ExternalDatabaseBackup {

    private static final String DUMP_DIRECTORY = ".clawbackup-external-databases";

    private ExternalDatabaseBackup() {
    }

    /** Export each configured database to a temporary server-root path for inclusion in the ZIP. */
    public static List<Path> backup(BackupConfig config, Path serverRoot, Runnable checkpoint,
                                   BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        List<ExternalDatabaseJob> jobs = config.getExternalDatabaseJobs();
        if (!config.isExternalDatabaseBackupEnabled() || jobs.isEmpty()) return new ArrayList<>();

        Path root = serverRoot.toAbsolutePath().normalize();
        Path dumpDirectory = root.resolve(DUMP_DIRECTORY).normalize();
        if (!dumpDirectory.startsWith(root)) throw new IOException("外部数据库备份目录无效");
        String runId = UUID.randomUUID().toString();
        Path runDirectory = dumpDirectory.resolve(runId).normalize();
        if (!runDirectory.startsWith(dumpDirectory) || runDirectory.equals(dumpDirectory)) {
            throw new IOException("外部数据库临时目录无效");
        }
        Files.createDirectories(runDirectory);

        List<Path> dumps = new ArrayList<>();
        Set<String> names = new HashSet<>();
        try {
            for (ExternalDatabaseJob job : jobs) {
                if (checkpoint != null) checkpoint.run();
                String safeName = safeName(job.getName());
                if (!names.add(safeName.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IOException("外部数据库任务名称冲突: " + job.getName());
                }
                Path output = runDirectory.resolve(safeName + ".dump").normalize();
                if (!output.startsWith(runDirectory)) throw new IOException("外部数据库输出路径无效");
                Files.deleteIfExists(output);

                Path diagnostics = diagnosticsPath(serverRoot, safeName);
                Files.createDirectories(diagnostics.getParent());
                Files.deleteIfExists(diagnostics);

                List<String> command = expand(job.getBackupCommand(), root, output);
                boolean commandWritesFile = containsPlaceholder(job.getBackupCommand(), "{output}");
                int exitCode = run(job, command, root, diagnostics,
                        commandWritesFile ? null : output, null, cancelled);
                if (exitCode != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
                    throw new IOException("外部数据库备份失败: " + job.getName() + " (退出码 " + exitCode
                            + "; 诊断文件: " + diagnostics + ")");
                }
                Files.deleteIfExists(diagnostics);
                dumps.add(output);
                Message.log("§e[备份] §a✔ 外部数据库已导出 §7" + job.getName());
            }
            return dumps;
        } catch (IOException | InterruptedException | RuntimeException e) {
            deleteRunDirectory(dumpDirectory, runDirectory);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw e;
        }
    }

    /** Restore configured jobs while their dependent plugins have been disabled during rollback. */
    public static void restore(BackupConfig config, Path serverRoot, Path archive) {
        if (!config.isExternalDatabaseBackupEnabled()) return;
        Path root = serverRoot.toAbsolutePath().normalize();
        Path dumpDirectory = root.resolve(DUMP_DIRECTORY).normalize();
        Set<Path> archiveDumps;
        try {
            archiveDumps = listArchiveDumps(archive, root, dumpDirectory);
        } catch (IOException e) {
            Message.log("§c[回档] §4无法扫描备份包中的外部数据库快照: " + e.getMessage());
            return;
        }
        Set<String> names = new HashSet<>();

        for (ExternalDatabaseJob job : config.getExternalDatabaseJobs()) {
            String safeName = safeName(job.getName());
            if (!names.add(safeName.toLowerCase(java.util.Locale.ROOT))) {
                Message.log("§c[回档] §4外部数据库任务名称冲突，跳过 §7" + job.getName());
                continue;
            }
            Path input = null;
            for (Path candidate : archiveDumps) {
                if (candidate.getFileName().toString().equalsIgnoreCase(safeName + ".dump")) {
                    input = candidate;
                    break;
                }
            }
            if (input == null || !Files.isRegularFile(input)) continue;
            if (job.getRestoreCommand().isEmpty()) {
                Message.log("§e[回档] §6外部数据库没有配置恢复命令，快照保留在 §7" + input);
                continue;
            }

            Path diagnostics = diagnosticsPath(serverRoot, safeName);
            try {
                Files.createDirectories(diagnostics.getParent());
                Files.deleteIfExists(diagnostics);
                boolean commandReadsFile = containsPlaceholder(job.getRestoreCommand(), "{input}");
                List<String> command = expand(job.getRestoreCommand(), root, input);
                int exitCode = run(job, command, root, diagnostics,
                        null, commandReadsFile ? null : input, null);
                if (exitCode == 0) {
                    Files.deleteIfExists(input);
                    deleteEmptyParentDirectories(dumpDirectory, input.getParent());
                    Files.deleteIfExists(diagnostics);
                    Message.log("§e[回档] §a✔ 外部数据库已恢复 §7" + job.getName());
                } else {
                    Message.log("§c[回档] §4外部数据库恢复失败 §7" + job.getName()
                            + " (退出码 " + exitCode + "; 诊断文件: " + diagnostics + ")");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Message.log("§c[回档] §4外部数据库恢复被中断 §7" + job.getName());
                return;
            } catch (Exception e) {
                Message.log("§c[回档] §4外部数据库恢复失败 §7" + job.getName()
                        + " (" + e.getMessage() + ")");
            }
        }
    }

    /** Remove only the exact dump files staged by this backup, never unrelated files. */
    public static void cleanup(Path serverRoot, List<Path> dumpFiles) {
        if (dumpFiles == null || dumpFiles.isEmpty()) return;
        Path root = serverRoot.toAbsolutePath().normalize();
        Path dumpDirectory = root.resolve(DUMP_DIRECTORY).normalize();
        if (!dumpDirectory.startsWith(root)) return;
        Set<Path> runDirectories = new HashSet<>();
        for (Path dump : dumpFiles) {
            Path normalized = dump.toAbsolutePath().normalize();
            if (!normalized.startsWith(dumpDirectory) || normalized.equals(dumpDirectory)) continue;
            try { Files.deleteIfExists(normalized); } catch (Exception ignored) {}
            Path runDirectory = normalized.getParent();
            if (runDirectory != null && runDirectory.startsWith(dumpDirectory)) runDirectories.add(runDirectory);
        }
        for (Path runDirectory : runDirectories) deleteEmptyParentDirectories(dumpDirectory, runDirectory);
        try { Files.deleteIfExists(dumpDirectory); } catch (Exception ignored) {}
    }

    private static Set<Path> listArchiveDumps(Path archive, Path serverRoot, Path dumpDirectory)
            throws IOException {
        Set<Path> result = new HashSet<>();
        String prefix = DUMP_DIRECTORY + "/";
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(prefix) || !name.endsWith(".dump")) continue;
                Path relative = Paths.get(name).normalize();
                if (relative.isAbsolute() || relative.getNameCount() < 3
                        || "..".equals(relative.getName(0).toString())) continue;
                Path target = serverRoot.resolve(relative).normalize();
                if (target.startsWith(dumpDirectory) && !target.equals(dumpDirectory)) result.add(target);
            }
        }
        return result;
    }

    private static void deleteRunDirectory(Path dumpDirectory, Path runDirectory) {
        Path root = dumpDirectory.toAbsolutePath().normalize();
        Path target = runDirectory.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) return;
        try {
            Files.walkFileTree(target, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    if (exc != null) throw exc;
                    Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {}
        try { Files.deleteIfExists(root); } catch (Exception ignored) {}
    }

    private static void deleteEmptyParentDirectories(Path dumpRoot, Path start) {
        Path root = dumpRoot.toAbsolutePath().normalize();
        Path current = start == null ? null : start.toAbsolutePath().normalize();
        while (current != null && !current.equals(root) && current.startsWith(root)) {
            try {
                Files.delete(current);
            } catch (IOException e) {
                break;
            }
            current = current.getParent();
        }
    }

    private static int run(ExternalDatabaseJob job, List<String> command, Path workingDirectory,
                           Path diagnostics, Path stdoutFile, Path stdinFile, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (command.isEmpty() || command.get(0).trim().isEmpty()) {
            throw new IOException("外部数据库命令为空: " + job.getName());
        }

        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        builder.environment().putAll(job.getEnvironment());
        builder.redirectError(diagnostics.toFile());
        if (stdoutFile != null) builder.redirectOutput(stdoutFile.toFile());
        else builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        if (stdinFile != null) builder.redirectInput(stdinFile.toFile());

        Process process = builder.start();
        if (stdinFile == null) {
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(job.getTimeoutSeconds());
        try {
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                    throw new java.util.concurrent.CancellationException("备份已取消");
                }
                if (System.nanoTime() >= deadline) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(2, TimeUnit.SECONDS);
                    }
                    throw new IOException("外部数据库命令超时: " + job.getName()
                            + " (" + job.getTimeoutSeconds() + " 秒; 诊断文件: " + diagnostics + ")");
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
        return process.exitValue();
    }

    private static List<String> expand(List<String> command, Path serverRoot, Path file) {
        List<String> result = new ArrayList<>(command.size());
        for (String argument : command) {
            result.add(argument.replace("{server-root}", serverRoot.toString())
                    .replace("{output}", file.toString())
                    .replace("{input}", file.toString()));
        }
        return result;
    }

    private static boolean containsPlaceholder(List<String> command, String placeholder) {
        for (String argument : command) {
            if (argument.contains(placeholder)) return true;
        }
        return false;
    }

    private static String safeName(String name) {
        String safe = name == null ? "" : name.trim().replaceAll("[^\\p{L}\\p{N}_.-]+", "_");
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) safe = "database";
        if (safe.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) safe = "db_" + safe;
        return safe;
    }

    private static Path diagnosticsPath(Path serverRoot, String safeName) {
        return serverRoot.toAbsolutePath().normalize()
                .resolve(".clawbackup-external-db-logs/external-db-" + safeName + ".log");
    }
}
