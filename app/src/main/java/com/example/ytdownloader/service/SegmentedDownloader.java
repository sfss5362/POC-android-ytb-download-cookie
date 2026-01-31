package com.example.ytdownloader.service;

import com.example.ytdownloader.manager.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Multi-segment parallel downloader with resume support.
 * Uses HTTP Range requests to download file in parallel segments,
 * each writing to its own offset in a RandomAccessFile.
 */
public class SegmentedDownloader {
    private static final String TAG = "SegmentedDownloader";
    private static final long SMALL_FILE = 5 * 1024 * 1024;       // 5 MB
    private static final long MEDIUM_FILE = 50 * 1024 * 1024;     // 50 MB
    private static final int BUFFER_SIZE = 32768;                  // 32 KB
    private static final int PROGRESS_INTERVAL_MS = 300;

    private final OkHttpClient httpClient;
    private final File outputFile;
    private final ProgressCallback callback;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Call> activeCalls = new ArrayList<>();
    private ExecutorService executor;

    public interface ProgressCallback {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    static class SegmentState {
        final int index;
        final long startByte;
        final long endByte;
        final AtomicLong downloadedBytes;

        SegmentState(int index, long startByte, long endByte, long downloadedBytes) {
            this.index = index;
            this.startByte = startByte;
            this.endByte = endByte;
            this.downloadedBytes = new AtomicLong(downloadedBytes);
        }

        long remaining() {
            return (endByte - startByte + 1) - downloadedBytes.get();
        }

        boolean isComplete() {
            return remaining() <= 0;
        }
    }

    public SegmentedDownloader(OkHttpClient httpClient, File outputFile, ProgressCallback callback) {
        this.httpClient = httpClient;
        this.outputFile = outputFile;
        this.callback = callback;
    }

    /**
     * Main download method. Blocks until complete, cancelled, or error.
     * @param url the download URL
     * @param baseOffset offset added to downloaded bytes for progress reporting
     *                   (used when downloading video+audio sequentially)
     */
    public void download(String url, long baseOffset) throws Exception {
        if (cancelled.get()) return;

        // Probe Range support and content length
        long totalBytes = probeContentLength(url);
        boolean rangeSupported = probeRangeSupport(url);

        if (totalBytes <= 0 || !rangeSupported) {
            AppLogger.i(TAG, "Falling back to single-connection download (totalBytes=" + totalBytes
                    + ", rangeSupported=" + rangeSupported + ")");
            downloadSingle(url, baseOffset);
            return;
        }

        // Try to load existing metadata for resume
        List<SegmentState> segments = null;
        DownloadMetadata metadata = loadMetadata();
        if (metadata != null && metadata.totalBytes == totalBytes && metadata.url.equals(url)) {
            segments = metadata.segments;
            AppLogger.i(TAG, "Resuming from .seg file with " + segments.size() + " segments");
        }

        // Create segments if not resuming
        if (segments == null) {
            int segmentCount = computeSegmentCount(totalBytes);
            segments = createSegments(totalBytes, segmentCount);
            AppLogger.i(TAG, "New download: " + totalBytes + " bytes, " + segments.size() + " segments");
        }

        // Pre-allocate file
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            raf.setLength(totalBytes);
        }

        // Submit segment downloads
        int activeSegments = 0;
        for (SegmentState seg : segments) {
            if (!seg.isComplete()) activeSegments++;
        }

        executor = Executors.newFixedThreadPool(Math.max(1, activeSegments));
        List<Future<?>> futures = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();

        for (SegmentState seg : segments) {
            if (seg.isComplete()) {
                AppLogger.i(TAG, "Segment " + seg.index + " already complete, skipping");
                continue;
            }
            final List<SegmentState> finalSegments = segments;
            futures.add(executor.submit(() -> {
                try {
                    downloadSegment(seg, url, totalBytes, finalSegments);
                } catch (Exception e) {
                    if (!cancelled.get()) {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    }
                }
            }));
        }

        // Progress aggregation loop
        try {
            while (!allComplete(segments) && !cancelled.get()) {
                synchronized (errors) {
                    if (!errors.isEmpty()) break;
                }
                long downloaded = sumDownloaded(segments);
                if (callback != null) {
                    callback.onProgress(baseOffset + downloaded, 0);
                }
                saveMetadata(url, totalBytes, segments);
                Thread.sleep(PROGRESS_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Wait for all futures
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }

        executor.shutdown();

        // Check for errors
        if (cancelled.get()) {
            saveMetadata(url, totalBytes, segments);
            throw new Exception("Download cancelled");
        }
        synchronized (errors) {
            if (!errors.isEmpty()) {
                saveMetadata(url, totalBytes, segments);
                throw errors.get(0);
            }
        }

        // Final progress update
        if (callback != null) {
            callback.onProgress(baseOffset + totalBytes, 0);
        }

        // Cleanup metadata on success
        deleteMetadata();
        AppLogger.i(TAG, "Download complete: " + outputFile.getName());
    }

    /**
     * Returns the total content length by sending a HEAD-like request.
     */
    public long probeContentLength(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .head()
                .build();
        Call call = httpClient.newCall(request);
        synchronized (activeCalls) {
            activeCalls.add(call);
        }
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) return -1;
            String cl = response.header("Content-Length");
            return cl != null ? Long.parseLong(cl) : -1;
        } finally {
            synchronized (activeCalls) {
                activeCalls.remove(call);
            }
        }
    }

    private boolean probeRangeSupport(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-0")
                    .build();
            Call call = httpClient.newCall(request);
            synchronized (activeCalls) {
                activeCalls.add(call);
            }
            try (Response response = call.execute()) {
                return response.code() == 206;
            } finally {
                synchronized (activeCalls) {
                    activeCalls.remove(call);
                }
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "Range probe failed: " + e.getMessage());
            return false;
        }
    }

    private int computeSegmentCount(long totalBytes) {
        if (totalBytes < SMALL_FILE) return 1;
        if (totalBytes < MEDIUM_FILE) return 2;
        return 4;
    }

    private List<SegmentState> createSegments(long totalBytes, int count) {
        List<SegmentState> segments = new ArrayList<>();
        long segSize = totalBytes / count;
        for (int i = 0; i < count; i++) {
            long start = i * segSize;
            long end = (i == count - 1) ? (totalBytes - 1) : (start + segSize - 1);
            segments.add(new SegmentState(i, start, end, 0));
        }
        return segments;
    }

    private void downloadSegment(SegmentState seg, String url, long totalBytes,
                                 List<SegmentState> allSegments) throws Exception {
        long fromByte = seg.startByte + seg.downloadedBytes.get();
        long toByte = seg.endByte;

        AppLogger.i(TAG, "Segment " + seg.index + ": downloading bytes " + fromByte + "-" + toByte);

        Request request = new Request.Builder()
                .url(url)
                .header("Range", "bytes=" + fromByte + "-" + toByte)
                .build();

        Call call = httpClient.newCall(request);
        synchronized (activeCalls) {
            if (cancelled.get()) return;
            activeCalls.add(call);
        }

        try (Response response = call.execute()) {
            if (cancelled.get()) return;
            if (response.code() != 206 && response.code() != 200) {
                throw new Exception("Segment " + seg.index + ": HTTP " + response.code());
            }

            if (response.body() == null) {
                throw new Exception("Segment " + seg.index + ": empty response body");
            }

            try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw");
                 InputStream in = response.body().byteStream()) {
                raf.seek(fromByte);
                byte[] buf = new byte[BUFFER_SIZE];
                int len;
                while ((len = in.read(buf)) > 0) {
                    if (cancelled.get()) return;
                    raf.write(buf, 0, len);
                    seg.downloadedBytes.addAndGet(len);
                }
            }
        } finally {
            synchronized (activeCalls) {
                activeCalls.remove(call);
            }
        }

        AppLogger.i(TAG, "Segment " + seg.index + " complete");
    }

    /**
     * Fallback single-connection download when Range is not supported.
     */
    private void downloadSingle(String url, long baseOffset) throws Exception {
        Request request = new Request.Builder().url(url).build();
        Call call = httpClient.newCall(request);
        synchronized (activeCalls) {
            if (cancelled.get()) return;
            activeCalls.add(call);
        }

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("HTTP " + response.code() + " " + response.message());
            }
            if (response.body() == null) throw new Exception("Empty response body");

            long contentLength = response.body().contentLength();
            long downloaded = 0;
            long lastNotifyTime = 0;

            try (InputStream in = response.body().byteStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(outputFile)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int len;
                while ((len = in.read(buf)) > 0) {
                    if (cancelled.get()) return;
                    out.write(buf, 0, len);
                    downloaded += len;

                    long now = System.currentTimeMillis();
                    if (now - lastNotifyTime > PROGRESS_INTERVAL_MS) {
                        if (callback != null) {
                            callback.onProgress(baseOffset + downloaded, contentLength);
                        }
                        lastNotifyTime = now;
                    }
                }
            }

            if (callback != null) {
                callback.onProgress(baseOffset + (contentLength > 0 ? contentLength : downloaded), contentLength);
            }
        } finally {
            synchronized (activeCalls) {
                activeCalls.remove(call);
            }
        }

        deleteMetadata();
    }

    /**
     * Cancel download: cancel all HTTP calls, shutdown executor, save metadata.
     */
    public void cancel() {
        cancelled.set(true);
        synchronized (activeCalls) {
            for (Call call : activeCalls) {
                call.cancel();
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // --- Metadata persistence for resume ---

    private File getMetadataFile() {
        return new File(outputFile.getAbsolutePath() + ".seg");
    }

    private File getMetadataTempFile() {
        return new File(outputFile.getAbsolutePath() + ".seg.tmp");
    }

    private void saveMetadata(String url, long totalBytes, List<SegmentState> segments) {
        try {
            JSONObject root = new JSONObject();
            root.put("url", url);
            root.put("totalBytes", totalBytes);
            root.put("urlTimestamp", System.currentTimeMillis());
            root.put("segmentCount", segments.size());

            JSONArray segs = new JSONArray();
            for (SegmentState seg : segments) {
                JSONObject s = new JSONObject();
                s.put("index", seg.index);
                s.put("startByte", seg.startByte);
                s.put("endByte", seg.endByte);
                s.put("downloadedBytes", seg.downloadedBytes.get());
                segs.put(s);
            }
            root.put("segments", segs);

            File tmp = getMetadataTempFile();
            File target = getMetadataFile();
            java.io.FileWriter writer = new java.io.FileWriter(tmp);
            writer.write(root.toString(2));
            writer.close();

            // Atomic rename
            if (!tmp.renameTo(target)) {
                // Fallback: delete target then rename
                target.delete();
                tmp.renameTo(target);
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "Failed to save .seg metadata: " + e.getMessage());
        }
    }

    private DownloadMetadata loadMetadata() {
        File file = getMetadataFile();
        if (!file.exists()) return null;

        try {
            byte[] bytes = new byte[(int) file.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            fis.read(bytes);
            fis.close();

            JSONObject root = new JSONObject(new String(bytes));
            DownloadMetadata meta = new DownloadMetadata();
            meta.url = root.getString("url");
            meta.totalBytes = root.getLong("totalBytes");

            JSONArray segs = root.getJSONArray("segments");
            meta.segments = new ArrayList<>();
            for (int i = 0; i < segs.length(); i++) {
                JSONObject s = segs.getJSONObject(i);
                meta.segments.add(new SegmentState(
                        s.getInt("index"),
                        s.getLong("startByte"),
                        s.getLong("endByte"),
                        s.getLong("downloadedBytes")
                ));
            }
            return meta;
        } catch (Exception e) {
            AppLogger.w(TAG, "Failed to load .seg metadata: " + e.getMessage());
            return null;
        }
    }

    public void deleteMetadata() {
        File meta = getMetadataFile();
        if (meta.exists()) meta.delete();
        File tmp = getMetadataTempFile();
        if (tmp.exists()) tmp.delete();
    }

    private boolean allComplete(List<SegmentState> segments) {
        for (SegmentState seg : segments) {
            if (!seg.isComplete()) return false;
        }
        return true;
    }

    private long sumDownloaded(List<SegmentState> segments) {
        long total = 0;
        for (SegmentState seg : segments) {
            total += seg.downloadedBytes.get();
        }
        return total;
    }

    static class DownloadMetadata {
        String url;
        long totalBytes;
        List<SegmentState> segments;
    }
}
