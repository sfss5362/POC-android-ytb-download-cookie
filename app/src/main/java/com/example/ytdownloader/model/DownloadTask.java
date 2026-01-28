package com.example.ytdownloader.model;

public class DownloadTask {
    public enum Status {
        PENDING,
        DOWNLOADING_VIDEO,
        DOWNLOADING_AUDIO,
        MERGING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum DownloadType {
        VIDEO_ONLY,
        AUDIO_ONLY,
        BEST_QUALITY_MERGE
    }

    private String id;
    private String videoId;
    private String title;
    private String thumbnailUrl;
    private DownloadType downloadType;
    private Status status;
    private int progress;
    private String videoItag;
    private String audioItag;
    private String outputPath;
    private String errorMessage;
    private long totalBytes;
    private long downloadedBytes;

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

    public String getVideoItag() { return videoItag; }
    public void setVideoItag(String videoItag) { this.videoItag = videoItag; }

    public String getAudioItag() { return audioItag; }
    public void setAudioItag(String audioItag) { this.audioItag = audioItag; }

    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

    public long getDownloadedBytes() { return downloadedBytes; }
    public void setDownloadedBytes(long downloadedBytes) { this.downloadedBytes = downloadedBytes; }

    public String getStatusText() {
        switch (status) {
            case PENDING: return "Waiting...";
            case DOWNLOADING_VIDEO: return "Downloading video... " + progress + "%";
            case DOWNLOADING_AUDIO: return "Downloading audio... " + progress + "%";
            case MERGING: return "Merging...";
            case COMPLETED: return "Completed";
            case FAILED: return "Failed: " + (errorMessage != null ? errorMessage : "Unknown error");
            case CANCELLED: return "Cancelled";
            default: return "";
        }
    }
}
