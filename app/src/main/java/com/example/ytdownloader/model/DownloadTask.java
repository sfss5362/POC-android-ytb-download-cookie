package com.example.ytdownloader.model;

public class DownloadTask {
    public enum Status {
        PENDING,
        DOWNLOADING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum DownloadType {
        VIDEO,
        AUDIO,
        THUMBNAIL
    }

    private String id;
    private String videoId;
    private String title;
    private String thumbnailUrl;
    private DownloadType downloadType;
    private Status status;
    private int progress;
    private String formatSpec;
    private String processId;
    private String outputPath;
    private String errorMessage;
    private long totalBytes;
    private long downloadedBytes;
    private String downloadUrl;

    public DownloadTask(String id, String videoId, String title, String thumbnailUrl, DownloadType downloadType) {
        this.id = id;
        this.videoId = videoId;
        this.title = title;
        this.thumbnailUrl = thumbnailUrl;
        this.downloadType = downloadType;
        this.status = Status.PENDING;
        this.progress = 0;
    }

    public String getId() { return id; }
    public String getVideoId() { return videoId; }
    public String getTitle() { return title; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public DownloadType getDownloadType() { return downloadType; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }

    public String getFormatSpec() { return formatSpec; }
    public void setFormatSpec(String formatSpec) { this.formatSpec = formatSpec; }

    public String getProcessId() { return processId; }
    public void setProcessId(String processId) { this.processId = processId; }

    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

    public long getDownloadedBytes() { return downloadedBytes; }
    public void setDownloadedBytes(long downloadedBytes) { this.downloadedBytes = downloadedBytes; }

    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }

    public String getStatusText() {
        switch (status) {
            case PENDING: return "Waiting...";
            case DOWNLOADING: return "Downloading... " + progress + "%";
            case COMPLETED: return "Completed";
            case FAILED: return "Failed: " + (errorMessage != null ? errorMessage : "Unknown error");
            case CANCELLED: return "Cancelled";
            default: return "";
        }
    }
}
