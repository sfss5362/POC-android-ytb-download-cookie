package com.example.ytdownloader.adapter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.ytdownloader.R;
import com.example.ytdownloader.model.DownloadTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DownloadListAdapter extends RecyclerView.Adapter<DownloadListAdapter.ViewHolder> {

    public interface OnTaskDeleteListener {
        void onTaskDeleted(String taskId, boolean deleteFile);
    }

    private final Context context;
    private final List<DownloadTask> tasks = new ArrayList<>();
    private Runnable onTaskRemovedListener;
    private OnTaskDeleteListener onTaskDeleteListener;

    public DownloadListAdapter(Context context) {
        this.context = context;
    }

    public void setOnTaskRemovedListener(Runnable listener) {
        this.onTaskRemovedListener = listener;
    }

    public void setOnTaskDeleteListener(OnTaskDeleteListener listener) {
        this.onTaskDeleteListener = listener;
    }

    public void addTask(DownloadTask task) {
        tasks.add(0, task);
        notifyItemInserted(0);
    }

    public void updateTask(DownloadTask task) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getId().equals(task.getId())) {
                tasks.set(i, task);
                notifyItemChanged(i);
                return;
            }
        }
    }

    public void setTasks(List<DownloadTask> newTasks) {
        tasks.clear();
        tasks.addAll(newTasks);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_download, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DownloadTask task = tasks.get(position);

        holder.tvName.setText(task.getTitle());
        holder.tvStatus.setText(task.getStatusText());

        // Load thumbnail
        if (task.getThumbnailUrl() != null) {
            Glide.with(context)
                    .load(task.getThumbnailUrl())
                    .centerCrop()
                    .into(holder.ivThumb);
        }

        // Update progress bar, folder button, delete button
        switch (task.getStatus()) {
            case COMPLETED:
                holder.progressBar.setVisibility(View.GONE);
                holder.btnAction.setVisibility(View.VISIBLE);
                holder.btnAction.setImageResource(android.R.drawable.ic_menu_share);
                holder.btnAction.setOnClickListener(v -> shareFile(task));
                holder.btnFolder.setVisibility(View.VISIBLE);
                holder.btnFolder.setOnClickListener(v -> {
                    if (task.getOutputPath() != null) {
                        File f = new File(task.getOutputPath());
                        Toast.makeText(context, f.getParent(), Toast.LENGTH_LONG).show();
                    }
                });
                holder.btnDelete.setVisibility(View.VISIBLE);
                holder.btnDelete.setOnClickListener(v -> showDeleteDialog(task, holder.getAdapterPosition()));
                break;
            case FAILED:
            case CANCELLED:
                holder.progressBar.setVisibility(View.GONE);
                holder.btnAction.setVisibility(View.GONE);
                holder.btnFolder.setVisibility(View.GONE);
                holder.btnDelete.setVisibility(View.VISIBLE);
                holder.btnDelete.setOnClickListener(v -> deleteTask(task, holder.getAdapterPosition(), false));
                break;
            case MERGING:
                holder.progressBar.setVisibility(View.VISIBLE);
                holder.progressBar.setIndeterminate(true);
                holder.btnAction.setVisibility(View.VISIBLE);
                holder.btnAction.setImageResource(android.R.drawable.ic_media_pause);
                holder.btnFolder.setVisibility(View.GONE);
                holder.btnDelete.setVisibility(View.GONE);
                break;
            default:
                holder.progressBar.setVisibility(View.VISIBLE);
                holder.progressBar.setIndeterminate(false);
                holder.progressBar.setProgress(task.getProgress());
                holder.btnAction.setVisibility(View.VISIBLE);
                holder.btnAction.setImageResource(android.R.drawable.ic_media_pause);
                holder.btnFolder.setVisibility(View.GONE);
                holder.btnDelete.setVisibility(View.GONE);
                break;
        }

        // Open file on click when completed
        holder.itemView.setOnClickListener(v -> {
            if (task.getStatus() == DownloadTask.Status.COMPLETED && task.getOutputPath() != null) {
                openFile(task);
            }
        });
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    private void showDeleteDialog(DownloadTask task, int position) {
        boolean fileExists = task.getOutputPath() != null && new File(task.getOutputPath()).exists();

        if (!fileExists) {
            deleteTask(task, position, false);
            return;
        }

        new AlertDialog.Builder(context)
                .setTitle("Delete record")
                .setMessage("Also delete the downloaded file?")
                .setPositiveButton("Delete file too", (dialog, which) -> deleteTask(task, position, true))
                .setNegativeButton("Record only", (dialog, which) -> deleteTask(task, position, false))
                .setNeutralButton("Cancel", null)
                .show();
    }

    private void deleteTask(DownloadTask task, int position, boolean deleteFile) {
        if (position < 0 || position >= tasks.size()) return;

        if (deleteFile && task.getOutputPath() != null) {
            File file = new File(task.getOutputPath());
            if (file.exists()) {
                boolean deleted = file.delete();
                Toast.makeText(context, deleted ? "File deleted" : "Failed to delete file", Toast.LENGTH_SHORT).show();
            }
        }

        String taskId = task.getId();
        tasks.remove(position);
        notifyItemRemoved(position);

        if (onTaskDeleteListener != null) {
            onTaskDeleteListener.onTaskDeleted(taskId, deleteFile);
        }
        if (onTaskRemovedListener != null) {
            onTaskRemovedListener.run();
        }
    }

    private void openFile(DownloadTask task) {
        File file = new File(task.getOutputPath());
        if (!file.exists()) return;

        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    private void shareFile(DownloadTask task) {
        File file = new File(task.getOutputPath());
        if (!file.exists()) return;

        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, "Share"));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView tvName;
        TextView tvStatus;
        ProgressBar progressBar;
        ImageButton btnAction;
        ImageButton btnFolder;
        ImageButton btnDelete;

        ViewHolder(View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.ivThumb);
            tvName = itemView.findViewById(R.id.tvName);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            progressBar = itemView.findViewById(R.id.progressBar);
            btnAction = itemView.findViewById(R.id.btnAction);
            btnFolder = itemView.findViewById(R.id.btnFolder);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}
