package com.example.ytdownloader.model;

import java.util.List;

public class VideoInfo {
    private String videoId;
    private String title;
    private String author;
    private String thumbnailUrl;
    private long durationSeconds;
    private List<FormatOption> videoFormats;
    private List<FormatOption> audioFormats;

    public static class FormatOption {
        private String itag;
        private String quality;
        private String mimeType;
        private long contentLength;
        private boolean hasAudio;
        private boolean hasVideo;

        public FormatOption(String itag, String quality, String mimeType, long contentLength, boolean hasAudio, boolean hasVideo) {
            this.itag = itag;
            this.quality = quality;
            this.mimeType = mimeType;
            this.contentLength = contentLength;
            this.hasAudio = hasAudio;
            this.hasVideo = hasVideo;
        }

        public String getItag() { return itag; }
        public String getQuality() { return quality; }
        public String getMimeType() { return mimeType; }
        public long getContentLength() { return contentLength; }
        public boolean hasAudio() { return hasAudio; }
        public boolean hasVideo() { return hasVideo; }

        @Override
        public String toString() {
            String size = contentLength > 0 ? " (" + formatSize(contentLength) + ")" : "";
            return quality + size;
        }

        private String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }

    public VideoInfo(String videoId, String title, String author, String thumbnailUrl, long durationSeconds) {
        this.videoId = videoId;
        this.title = title;
        this.author = author;
        this.thumbnailUrl = thumbnailUrl;
        this.durationSeconds = durationSeconds;
    }

    public String getVideoId() { return videoId; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public long getDurationSeconds() { return durationSeconds; }

    public List<FormatOption> getVideoFormats() { return videoFormats; }
    public void setVideoFormats(List<FormatOption> videoFormats) { this.videoFormats = videoFormats; }

    public List<FormatOption> getAudioFormats() { return audioFormats; }
    public void setAudioFormats(List<FormatOption> audioFormats) { this.audioFormats = audioFormats; }

    public String getFormattedDuration() {
        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        long seconds = durationSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }
}
