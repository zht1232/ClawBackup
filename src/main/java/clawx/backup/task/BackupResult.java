package clawx.backup.task;

import java.nio.file.Path;
import java.util.List;

/**
 * 备份结果记录
 */
public class BackupResult {
    private final boolean success;
    private final String message;
    private final Path backupFile;
    private long elapsedMs;
    private long compressedSize;
    private long totalFiles;
    private int skippedFiles;
    private List<String> skippedFileNames;

    public BackupResult(boolean success, String message, Path backupFile) {
        this.success = success;
        this.message = message;
        this.backupFile = backupFile;
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public Path getBackupFile() { return backupFile; }

    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }

    public long getCompressedSize() { return compressedSize; }
    public void setCompressedSize(long compressedSize) { this.compressedSize = compressedSize; }

    public long getTotalFiles() { return totalFiles; }
    public void setTotalFiles(long totalFiles) { this.totalFiles = totalFiles; }

    public int getSkippedFiles() { return skippedFiles; }
    public void setSkippedFiles(int skippedFiles) { this.skippedFiles = skippedFiles; }

    public List<String> getSkippedFileNames() { return skippedFileNames; }
    public void setSkippedFileNames(List<String> skippedFileNames) { this.skippedFileNames = skippedFileNames; }
}
