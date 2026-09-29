package com.eyemonitor.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.MediaCacheEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.service.MonitorService;
import com.eyemonitor.util.MediaUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 共享图库（按天分组 Grid）。
 * <p>
 * 数据源：MediaCacheEntity（服务器真源缓存）；发过/收过的媒体自动归档；
 * 未下载的项显示「点击下载」占位，点击后 GET 下载到 getFilesDir()/media/{fileId} 再全屏查看；
 * 长按删除 → DELETE /api/media/{fileId}，服务端删行+删文件并广播 media_deleted 双向同步。
 */
public class GalleryActivity extends AppCompatActivity {

    private static final String TAG = "GalleryActivity";
    private static final int ROW_HEADER = 0;
    private static final int ROW_MEDIA = 1;
    private static final SimpleDateFormat DAY_FORMAT =
            new SimpleDateFormat("yyyy-M-d", Locale.getDefault());

    private RecyclerView rvGallery;
    private TextView tvEmpty;
    private GalleryAdapter adapter;

    /** 对方新增/删除媒体时实时刷新（本端删除由 DELETE 响应后重载） */
    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String json = intent.getStringExtra(MonitorService.EXTRA_EVENT_JSON);
            if (json == null) return;
            WsMessage message = WsMessage.fromJson(json);
            if (message != null && ("media".equals(message.getType())
                    || "media_deleted".equals(message.getType()))) {
                loadGallery();
            }
        }
    };

    static class GalleryRow {
        final int type;
        final String day;
        final MediaCacheEntity media;

        GalleryRow(int type, String day, MediaCacheEntity media) {
            this.type = type;
            this.day = day;
            this.media = media;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        rvGallery = findViewById(R.id.rv_gallery);
        tvEmpty = findViewById(R.id.tv_gallery_empty);
        findViewById(R.id.btn_gallery_back).setOnClickListener(v -> finish());

        GridLayoutManager lm = new GridLayoutManager(this, 3);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position >= adapter.rows.size()) return 1;
                return adapter.rows.get(position).type == ROW_HEADER ? 3 : 1;
            }
        });
        adapter = new GalleryAdapter();
        rvGallery.setLayoutManager(lm);
        rvGallery.setAdapter(adapter);

        IntentFilter filter = new IntentFilter(MonitorService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(eventReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(eventReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadGallery();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(eventReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    /** 从 Room 读取媒体缓存并按天分组（时间倒序） */
    private void loadGallery() {
        AppDatabase.dbExecutor.execute(() -> {
            List<MediaCacheEntity> list = AppDatabase.getInstance(this).cacheDao().getMedia();
            List<GalleryRow> newRows = new ArrayList<>();
            String lastDay = null;
            for (MediaCacheEntity m : list) {
                String day = DAY_FORMAT.format(new Date(m.ts));
                if (!day.equals(lastDay)) {
                    newRows.add(new GalleryRow(ROW_HEADER, day, null));
                    lastDay = day;
                }
                newRows.add(new GalleryRow(ROW_MEDIA, null, m));
            }
            runOnUiThread(() -> adapter.setRows(newRows));
        });
    }

    /** 生成缩略图：视频走 ThumbnailUtils 后台生成，图片走 Glide */
    private void loadThumb(ImageView iv, String path, String mime) {
        if (MediaUtils.isVideo(mime)) {
            final String local = path;
            AppDatabase.dbExecutor.execute(() -> {
                final Bitmap bmp = ThumbnailUtils.createVideoThumbnail(local,
                        MediaStore.Video.Thumbnails.MINI_KIND);
                runOnUiThread(() -> {
                    if (bmp != null) iv.setImageBitmap(bmp);
                    else iv.setImageResource(R.drawable.ic_image);
                });
            });
        } else {
            Glide.with(iv)
                    .load(new File(path))
                    .centerCrop()
                    .placeholder(R.drawable.ic_image)
                    .error(R.drawable.ic_image)
                    .into(iv);
        }
    }

    /** 长按确认删除：服务器删行+删文件并广播 media_deleted，本地同步清理缓存/聊天气泡/文件 */
    private void confirmDelete(MediaCacheEntity media) {
        if (media == null || media.fileId == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.media_delete_title)
                .setMessage(R.string.media_delete_confirm)
                .setPositiveButton(R.string.ok, (d, w) -> deleteMedia(media))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteMedia(MediaCacheEntity media) {
        final String fileId = media.fileId;
        AuthManager.i(this).delete(this, "/api/media/" + fileId, new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                AppDatabase db = AppDatabase.getInstance(GalleryActivity.this);
                AppDatabase.dbExecutor.execute(() -> {
                    db.cacheDao().deleteMedia(fileId);
                    db.chatDao().deleteMediaChat(fileId);
                    MediaUtils.deleteLocalFile(GalleryActivity.this, fileId);
                });
                // 服务器已向对方广播 media_deleted；本地行清理完从 Room 重载
                loadGallery();
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> Toast.makeText(GalleryActivity.this,
                        getString(R.string.media_delete_failed, msg != null ? msg : code + ""),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.ViewHolder> {

        final List<GalleryRow> rows = new ArrayList<>();

        void setRows(List<GalleryRow> newRows) {
            rows.clear();
            rows.addAll(newRows);
            tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == ROW_HEADER) {
                return new ViewHolder(inflater.inflate(
                        R.layout.item_media_day_header, parent, false), viewType);
            }
            return new ViewHolder(inflater.inflate(
                    R.layout.item_media_gallery, parent, false), viewType);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            holder.bind(rows.get(position), position);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final int viewType;
            TextView tvDay;
            ImageView ivThumb;
            LinearLayout llPlaceholder;
            TextView tvHint;
            FrameLayout flBadge;

            ViewHolder(View view, int viewType) {
                super(view);
                this.viewType = viewType;
                if (viewType == ROW_HEADER) {
                    tvDay = view.findViewById(R.id.tv_media_day_header);
                } else {
                    ivThumb = view.findViewById(R.id.iv_gallery_thumb);
                    llPlaceholder = view.findViewById(R.id.ll_gallery_placeholder);
                    tvHint = view.findViewById(R.id.tv_gallery_hint);
                    flBadge = view.findViewById(R.id.fl_gallery_badge);
                }
            }

            void bind(GalleryRow row, int position) {
                if (viewType == ROW_HEADER) {
                    tvDay.setText(row.day);
                    return;
                }
                final MediaCacheEntity media = row.media;
                final String mime = media.mime;
                final boolean video = MediaUtils.isVideo(mime);
                flBadge.setVisibility(video ? View.VISIBLE : View.GONE);

                final String localPath = media.localPath;
                final boolean downloaded = localPath != null && new File(localPath).exists();
                if (downloaded) {
                    ivThumb.setVisibility(View.VISIBLE);
                    llPlaceholder.setVisibility(View.GONE);
                    loadThumb(ivThumb, localPath, mime);
                } else {
                    ivThumb.setVisibility(View.GONE);
                    llPlaceholder.setVisibility(View.VISIBLE);
                    tvHint.setText(R.string.media_download_hint);
                }

                itemView.setOnClickListener(v -> {
                    if (downloaded) {
                        MediaUtils.launchViewer(GalleryActivity.this, media.fileId,
                                mime, media.duration, localPath);
                    } else {
                        tvHint.setText(R.string.media_downloading);
                        MediaUtils.openMedia(GalleryActivity.this, media.fileId,
                                mime, media.duration, null, new MediaUtils.MediaCb() {
                                    @Override
                                    public void onReady(String path) {
                                        media.localPath = path;
                                        runOnUiThread(() -> notifyItemChanged(position));
                                    }

                                    @Override
                                    public void onError(int code, String msg) {
                                        runOnUiThread(() -> {
                                            tvHint.setText(R.string.media_download_hint);
                                            Toast.makeText(GalleryActivity.this,
                                                    R.string.media_download_failed,
                                                    Toast.LENGTH_SHORT).show();
                                        });
                                    }
                                });
                    }
                });
                itemView.setOnLongClickListener(v -> {
                    confirmDelete(media);
                    return true;
                });
            }
        }
    }
}