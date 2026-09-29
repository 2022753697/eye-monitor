package com.eyemonitor.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.app.Dialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.FolderCacheEntity;
import com.eyemonitor.db.MediaCacheEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.service.MonitorService;
import com.eyemonitor.service.SyncManager;
import com.eyemonitor.util.MediaUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 共享图库（按天分组 Grid + 文件夹分类）。
 * <p>
 * 文件夹共享给配对双方：顶部「全部 / 各文件夹」切换过滤，➕新建文件夹，长按文件夹删除
 * （删除后内容归「全部」不丢数据）。右上角「上传」把选择器中的图片/视频传到当前文件夹。
 * 数据源：MediaCacheEntity / FolderCacheEntity（服务器真源缓存）。
 */
public class GalleryActivity extends AppCompatActivity {

    private static final String TAG = "GalleryActivity";
    private static final int ROW_HEADER = 0;
    private static final int ROW_MEDIA = 1;
    private static final int ROW_FOLDER = 2;
    /** 未分类模式的特殊标记 */
    private static final long MODE_UNFILED = -1;
    private static final int REQ_PICK_MEDIA = 1001;
    private static final int ROW_ADD_FOLDER = 3;
    private static final int ROW_FOLDER_HEADER = 4;
    private static final SimpleDateFormat DAY_FORMAT =
            new SimpleDateFormat("yyyy-M-d", Locale.getDefault());

    private RecyclerView rvGallery;
    private TextView tvEmpty;
    private View btnGallerySelect;
    private View fabAddFolder;
    private View batchBar;
    private TextView tvBatchCount;
    private GalleryAdapter adapter;

    /** 批量选择模式与选中集合 */
    private boolean batchMode;
    private final java.util.LinkedHashSet<String> batchSelected = new java.util.LinkedHashSet<>();

    /** 当前模式：null = 全部（文件夹卡片上下列表）；MODE_UNFILED = 未分类；>0 = 指定文件夹 */
    private Long currentFolderId = null;

    /** 对方新增/删除媒体时实时刷新（本端删除由 DELETE 响应后重载） */
    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String json = intent.getStringExtra(MonitorService.EXTRA_EVENT_JSON);
            if (json == null) return;
            WsMessage message = WsMessage.fromJson(json);
            if (message != null && ("media".equals(message.getType())
                    || "media_deleted".equals(message.getType())
                    || "folder_sync".equals(message.getType()))) {
                loadGallery();
            }
        }
    };

    static class GalleryRow {
        final int type;
        final String day;
        final MediaCacheEntity media;
        /** ROW_FOLDER：非 null = 文件夹行；null = 未分类行 */
        final FolderCacheEntity folder;
        /** ROW_FOLDER：该组媒体（最多取 3 张做缩略预览） */
        final List<MediaCacheEntity> previews;

        GalleryRow(int type, String day, MediaCacheEntity media) {
            this.type = type;
            this.day = day;
            this.media = media;
            this.folder = null;
            this.previews = null;
        }

        GalleryRow(FolderCacheEntity folder, List<MediaCacheEntity> previews) {
            this.type = ROW_FOLDER;
            this.day = null;
            this.media = null;
            this.folder = folder;
            this.previews = previews == null ? new ArrayList<>() : previews;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        rvGallery = findViewById(R.id.rv_gallery);
        tvEmpty = findViewById(R.id.tv_gallery_empty);
        btnGallerySelect = findViewById(R.id.btn_gallery_select);
        batchBar = findViewById(R.id.batch_bar);
        tvBatchCount = findViewById(R.id.tv_batch_count);
        findViewById(R.id.btn_gallery_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_gallery_upload).setOnClickListener(v -> pickMedia());
        btnGallerySelect.setOnClickListener(v -> toggleBatchMode());
        findViewById(R.id.btn_batch_move).setOnClickListener(v -> showBatchMoveDialog());
        findViewById(R.id.btn_batch_delete).setOnClickListener(v -> confirmBatchDelete());
        findViewById(R.id.btn_batch_cancel).setOnClickListener(v -> toggleBatchMode());
        fabAddFolder = findViewById(R.id.fab_add_folder);
        fabAddFolder.setOnClickListener(v -> showCreateFolderDialog());

        GridLayoutManager lm = new GridLayoutManager(this, 3);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position >= adapter.rows.size()) return 1;
                int type = adapter.rows.get(position).type;
                // 文件夹卡与日期头占满整行（span 3）；媒体项为 1/3 列
                return (type == ROW_HEADER || type == ROW_FOLDER) ? 3 : 1;
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

    // --- 批量选择 ---

    private void toggleBatchMode() {
        batchMode = !batchMode;
        batchSelected.clear();
        batchBar.setVisibility(batchMode ? View.VISIBLE : View.GONE);
        btnGallerySelect.setVisibility(batchMode ? View.GONE : View.VISIBLE);
        fabAddFolder.setVisibility(batchMode ? View.GONE : View.VISIBLE);
        updateBatchCount();
        adapter.notifyDataSetChanged();
    }

    private void updateBatchCount() {
        tvBatchCount.setText(getString(R.string.batch_selected_count, batchSelected.size()));
    }

    private void toggleBatchSelect(MediaCacheEntity media) {
        if (!batchSelected.remove(media.fileId)) {
            batchSelected.add(media.fileId);
        }
        updateBatchCount();
        adapter.notifyDataSetChanged();
    }

    private void confirmBatchDelete() {
        if (batchSelected.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.batch_delete_confirm, batchSelected.size()))
                .setPositiveButton(R.string.ok, (d, w) -> batchDelete())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 批量删除：逐个 DELETE + 本地清理，全部结束后退出批量并刷新 */
    private void batchDelete() {
        final String[] ids = batchSelected.toArray(new String[0]);
        final int total = ids.length;
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger(0);
        final AppDatabase db = AppDatabase.getInstance(this);
        for (final String fileId : ids) {
            AuthManager.i(this).delete(this, "/api/media/" + fileId, new AuthManager.Callback() {
                @Override
                public void onSuccess(com.google.gson.JsonObject data) {
                    success.incrementAndGet();
                    AppDatabase.dbExecutor.execute(() -> {
                        db.cacheDao().deleteMedia(fileId);
                        db.chatDao().deleteMediaChat(fileId);
                        MediaUtils.deleteLocalFile(GalleryActivity.this, fileId);
                        if (done.incrementAndGet() == total) onBatchFinished();
                    });
                }

                @Override
                public void onError(int code, String msg) {
                    if (done.incrementAndGet() == total) onBatchFinished();
                }
            });
        }
    }

    /** 批量移动：先选目标文件夹，再逐个 PUT */
    private void showBatchMoveDialog() {
        if (batchSelected.isEmpty()) return;
        AppDatabase.dbExecutor.execute(() -> {
            final List<FolderCacheEntity> list = AppDatabase.getInstance(this).cacheDao().getFolders();
            runOnUiThread(() -> {
                final String[] names = new String[list.size() + 1];
                final long[] ids = new long[list.size() + 1];
                names[0] = getString(R.string.gallery_unfiled);
                ids[0] = 0;
                for (int i = 0; i < list.size(); i++) {
                    names[i + 1] = list.get(i).name;
                    ids[i + 1] = list.get(i).id;
                }
                new AlertDialog.Builder(GalleryActivity.this)
                        .setTitle(R.string.media_move_to_folder_title)
                        .setItems(names, (d, which) -> batchMoveTo(ids[which]))
                        .show();
            });
        });
    }

    private void batchMoveTo(final long targetFolderId) {
        final String[] ids = batchSelected.toArray(new String[0]);
        final int total = ids.length;
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        final AppDatabase db = AppDatabase.getInstance(this);
        for (final String fileId : ids) {
            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            body.addProperty("folderId", targetFolderId);
            AuthManager.i(this).putJson(this, "/api/media/" + fileId + "/folder",
                    body.toString(), new AuthManager.Callback() {
                        @Override
                        public void onSuccess(com.google.gson.JsonObject data) {
                            AppDatabase.dbExecutor.execute(() -> {
                                MediaCacheEntity m = db.cacheDao().getMedia(fileId);
                                if (m != null) {
                                    m.folderId = targetFolderId == 0 ? null : targetFolderId;
                                    db.cacheDao().upsertMedia(m);
                                }
                                if (done.incrementAndGet() == total) onBatchFinished();
                            });
                        }

                        @Override
                        public void onError(int code, String msg) {
                            if (done.incrementAndGet() == total) onBatchFinished();
                        }
                    });
        }
    }

    private void onBatchFinished() {
        runOnUiThread(() -> {
            batchMode = false;
            batchSelected.clear();
            batchBar.setVisibility(View.GONE);
            btnGallerySelect.setVisibility(View.VISIBLE);
            loadGallery();
        });
    }

    // --- 文件夹分类 ---

    private GradientDrawable tintBg(int radius, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void showCreateFolderDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_create_folder);
        Window window = dialog.getWindow();
        if (window != null) {
            // 透明窗口底 + 布局自带圆角白卡；宽度取屏幕 85%
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.85f);
            window.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        EditText input = dialog.findViewById(R.id.et_folder_name);
        dialog.findViewById(R.id.btn_folder_cancel).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.btn_folder_ok).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) return;
            dialog.dismiss();
            createFolder(name);
        });
        dialog.show();
    }

    private void createFolder(final String name) {
        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
        body.addProperty("name", name);
        AuthManager.i(this).postJson(this, "/api/folders", body.toString(), new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                final long id = data.has("id") ? data.get("id").getAsLong() : 0L;
                runOnUiThread(() -> {
                    FolderCacheEntity f = new FolderCacheEntity();
                    f.id = id;
                    f.name = data.has("name") && !data.get("name").isJsonNull()
                            ? data.get("name").getAsString() : name;
                    f.ts = data.has("createdAt") ? data.get("createdAt").getAsLong()
                            : System.currentTimeMillis();
                    final long fid = id;
                    AppDatabase.dbExecutor.execute(() -> {
                        AppDatabase.getInstance(GalleryActivity.this).cacheDao().upsertFolder(f);
                        runOnUiThread(() -> {
                            currentFolderId = fid;
                            loadGallery();
                        });
                    });
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> Toast.makeText(GalleryActivity.this,
                        getString(R.string.gallery_folder_create_failed,
                                msg != null ? msg : code + ""), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void confirmDeleteFolder(final FolderCacheEntity folder) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.gallery_folder_delete_title)
                .setMessage(getString(R.string.gallery_folder_delete_message, folder.name))
                .setPositiveButton(R.string.ok, (d, w) -> deleteFolder(folder))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteFolder(final FolderCacheEntity folder) {
        AuthManager.i(this).delete(this, "/api/folders/" + folder.id, new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                final AppDatabase db = AppDatabase.getInstance(GalleryActivity.this);
                AppDatabase.dbExecutor.execute(() -> {
                    // 服务端已把该文件夹内容归未分类，本地同步：删文件夹行 + 媒体 folderId 置空
                    db.cacheDao().deleteFolder(folder.id);
                    for (MediaCacheEntity m : db.cacheDao().getMediaByFolder(folder.id)) {
                        m.folderId = null;
                        db.cacheDao().upsertMedia(m);
                    }
                    runOnUiThread(() -> {
                        if (folder.id == currentFolderId) currentFolderId = null;
                        loadGallery();
                    });
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> Toast.makeText(GalleryActivity.this,
                        getString(R.string.gallery_folder_delete_failed,
                                msg != null ? msg : code + ""), Toast.LENGTH_LONG).show());
            }
        });
    }

    // --- 媒体展示 ---

    /** 从 Room 读取：全部 = 文件夹分组列表；文件夹/未分类 = 按天分组媒体网格 */
    private void loadGallery() {
        AppDatabase.dbExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            List<GalleryRow> newRows = new ArrayList<>();
            if (currentFolderId == null) {
                // 全部：每个文件夹一张整行卡片（名称 + 最多5张预览 + 查看更多）+ 未分类卡片；新建入口 = 右下角 FAB
                for (FolderCacheEntity f : db.cacheDao().getFolders()) {
                    List<MediaCacheEntity> previews = db.cacheDao().getMediaByFolder(f.id);
                    List<MediaCacheEntity> head = previews.size() > 5
                            ? previews.subList(0, 5) : previews;
                    newRows.add(new GalleryRow(f, head));
                }
                List<MediaCacheEntity> unfiled = db.cacheDao().getMediaUnfiled();
                if (!unfiled.isEmpty()) {
                    List<MediaCacheEntity> head = unfiled.size() > 5
                            ? unfiled.subList(0, 5) : unfiled;
                    newRows.add(new GalleryRow(null, head));
                }
            } else {
                // 文件夹/未分类视图：顶部「← 全部 + 名称」头行 + 按天网格
                newRows.add(new GalleryRow(ROW_FOLDER_HEADER, resolveFolderName(db), null));
                List<MediaCacheEntity> list = currentFolderId == MODE_UNFILED
                        ? db.cacheDao().getMediaUnfiled()
                        : db.cacheDao().getMediaByFolder(currentFolderId);
                String lastDay = null;
                for (MediaCacheEntity m : list) {
                    String day = DAY_FORMAT.format(new Date(m.ts));
                    if (!day.equals(lastDay)) {
                        newRows.add(new GalleryRow(ROW_HEADER, day, null));
                        lastDay = day;
                    }
                    newRows.add(new GalleryRow(ROW_MEDIA, null, m));
                }
            }
            runOnUiThread(() -> {
                adapter.setRows(newRows);
                tvEmpty.setVisibility(newRows.isEmpty() ? View.VISIBLE : View.GONE);
                tvEmpty.setText(currentFolderId == null
                        ? getString(R.string.gallery_empty)
                        : getString(R.string.gallery_folder_empty));
            });
        });
    }

    /** 当前文件夹显示名（dbExecutor 内调用） */
    private String resolveFolderName(AppDatabase db) {
        if (currentFolderId == MODE_UNFILED) return getString(R.string.gallery_unfiled);
        for (FolderCacheEntity f : db.cacheDao().getFolders()) {
            if (f.id == currentFolderId) return f.name;
        }
        return "";
    }

    // --- 上传到当前文件夹 ---

    private void pickMedia() {
        PrefsManager prefs = new PrefsManager(this);
        if (!prefs.isPaired()) {
            Toast.makeText(this, R.string.media_not_paired, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        try {
            startActivityForResult(intent, REQ_PICK_MEDIA);
        } catch (Exception e) {
            Toast.makeText(this, R.string.media_pick_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_MEDIA && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) handleMediaPicked(uri);
        }
    }

    private void handleMediaPicked(final Uri uri) {
        long size = MediaUtils.querySize(this, uri);
        if (size > MediaUtils.MAX_MEDIA_BYTES) {
            Toast.makeText(this, R.string.media_file_too_large, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = MediaUtils.queryDisplayName(this, uri);
        String mime = MediaUtils.inferMime(getContentResolver().getType(uri), name);
        final boolean video = MediaUtils.isVideo(mime);
        final String finalName = name;
        AppDatabase.dbExecutor.execute(() -> {
            String safe = finalName.length() > 60 ? finalName.substring(finalName.length() - 60) : finalName;
            File tmp = new File(new File(getCacheDir(), "media_send"),
                    System.currentTimeMillis() + "_" + safe.replaceAll("[^a-zA-Z0-9._-]", "_"));
            File dir = tmp.getParentFile();
            if (dir != null) dir.mkdirs();
            if (!MediaUtils.copyUriToFile(GalleryActivity.this, uri, tmp)) {
                runOnUiThread(() -> Toast.makeText(GalleryActivity.this, R.string.media_pick_failed,
                        Toast.LENGTH_SHORT).show());
                return;
            }
            long duration = video ? MediaUtils.queryDurationMs(GalleryActivity.this, uri) : 0;
            final File file = tmp;
            final long finalDuration = duration;
            runOnUiThread(() -> {
                if (video && (finalDuration < 0 || finalDuration > MediaUtils.MAX_VIDEO_MS)) {
                    file.delete();
                    Toast.makeText(GalleryActivity.this, R.string.media_video_too_long,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                uploadMedia(file, finalName, mime, finalDuration, uri);
            });
        });
    }

    private void uploadMedia(final File file, final String name, final String mime,
                             final long duration, final Uri uri) {
        Toast.makeText(this, R.string.media_uploading, Toast.LENGTH_SHORT).show();
        final Long folderId = currentFolderId;
        final PrefsManager prefs = new PrefsManager(this);
        AuthManager.i(this).uploadMedia(this, file, prefs.getPairCode(), folderId,
                new AuthManager.Callback() {
                    @Override
                    public void onSuccess(com.google.gson.JsonObject data) {
                        final String fileId = data.has("fileId") ? data.get("fileId").getAsString() : null;
                        if (fileId == null || fileId.isEmpty()) {
                            file.delete();
                            runOnUiThread(() -> Toast.makeText(GalleryActivity.this,
                                    R.string.auth_error_response, Toast.LENGTH_SHORT).show());
                            return;
                        }
                        final long now = System.currentTimeMillis();
                        final MediaCacheEntity e = new MediaCacheEntity();
                        e.fileId = fileId;
                        e.serverFileName = data.has("fileName") && !data.get("fileName").isJsonNull()
                                ? data.get("fileName").getAsString() : name;
                        e.mime = data.has("mime") && !data.get("mime").isJsonNull()
                                ? data.get("mime").getAsString() : mime;
                        e.size = data.has("size") ? data.get("size").getAsLong() : file.length();
                        e.duration = duration;
                        e.folderId = folderId;
                        e.ts = now;
                        final AppDatabase db = AppDatabase.getInstance(GalleryActivity.this);
                        AppDatabase.dbExecutor.execute(() -> {
                            File dst = MediaUtils.localMediaFile(GalleryActivity.this, fileId);
                            boolean archived = (dst.exists() && dst.length() > 0)
                                    || MediaUtils.copyUriToFile(GalleryActivity.this, uri, dst);
                            if (!archived) archived = file.renameTo(dst);
                            if (archived) e.localPath = dst.getAbsolutePath();
                            file.delete();
                            db.cacheDao().upsertMedia(e);
                            runOnUiThread(() -> {
                                String from = prefs.getNickname() != null ? prefs.getNickname() : "";
                                MonitorService.sendMediaMeta(GalleryActivity.this, fileId,
                                        e.serverFileName, e.mime, e.size, duration, from);
                                loadGallery();
                            });
                        });
                    }

                    @Override
                    public void onError(int code, String msg) {
                        file.delete();
                        runOnUiThread(() -> Toast.makeText(GalleryActivity.this,
                                getString(R.string.media_upload_failed,
                                        msg != null ? msg : code + ""), Toast.LENGTH_LONG).show());
                    }
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

    /** 长按媒体：删除 / 移动到文件夹 */
    private void showMediaActions(final MediaCacheEntity media) {
        String[] actions = {getString(R.string.media_delete), getString(R.string.media_move_to_folder)};
        new AlertDialog.Builder(this)
                .setTitle(media.serverFileName != null ? media.serverFileName : media.fileId)
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        confirmDelete(media);
                    } else {
                        showMoveFolderDialog(media);
                    }
                })
                .show();
    }

    /** 选择目标文件夹（含「未分类」）移动媒体 */
    private void showMoveFolderDialog(final MediaCacheEntity media) {
        AppDatabase.dbExecutor.execute(() -> {
            final List<FolderCacheEntity> list = AppDatabase.getInstance(this).cacheDao().getFolders();
            runOnUiThread(() -> {
                final String[] names = new String[list.size() + 1];
                final long[] ids = new long[list.size() + 1];
                names[0] = getString(R.string.gallery_unfiled);
                ids[0] = 0; // 0 = 未分类
                for (int i = 0; i < list.size(); i++) {
                    names[i + 1] = list.get(i).name;
                    ids[i + 1] = list.get(i).id;
                }
                new AlertDialog.Builder(GalleryActivity.this)
                        .setTitle(R.string.media_move_to_folder_title)
                        .setItems(names, (d, which) -> moveMediaToFolder(media, ids[which]))
                        .show();
            });
        });
    }

    private void moveMediaToFolder(final MediaCacheEntity media, final long targetFolderId) {
        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
        body.addProperty("folderId", targetFolderId);
        AuthManager.i(this).putJson(this, "/api/media/" + media.fileId + "/folder",
                body.toString(), new AuthManager.Callback() {
                    @Override
                    public void onSuccess(com.google.gson.JsonObject data) {
                        AppDatabase db = AppDatabase.getInstance(GalleryActivity.this);
                        AppDatabase.dbExecutor.execute(() -> {
                            media.folderId = targetFolderId == 0 ? null : targetFolderId;
                            db.cacheDao().upsertMedia(media);
                            runOnUiThread(() -> loadGallery());
                        });
                    }

                    @Override
                    public void onError(int code, String msg) {
                        runOnUiThread(() -> Toast.makeText(GalleryActivity.this,
                                getString(R.string.media_move_failed,
                                        msg != null ? msg : code + ""), Toast.LENGTH_LONG).show());
                    }
                });
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
            if (viewType == ROW_FOLDER) {
                return new ViewHolder(inflater.inflate(
                        R.layout.item_media_folder, parent, false), viewType);
            }
            if (viewType == ROW_ADD_FOLDER) {
                return new ViewHolder(inflater.inflate(
                        R.layout.item_add_folder, parent, false), viewType);
            }
            if (viewType == ROW_FOLDER_HEADER) {
                return new ViewHolder(inflater.inflate(
                        R.layout.item_folder_header, parent, false), viewType);
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
            View vBatchOverlay;
            FrameLayout flBatchCheck;
            // ROW_FOLDER
            TextView tvFolderName;
            TextView tvFolderMore;
            LinearLayout llFolderPreview;
            // ROW_FOLDER_HEADER
            TextView tvFolderBack;
            TextView tvFolderHeaderName;
            // ROW_ADD_FOLDER
            TextView tvAddFolder;

            ViewHolder(View view, int viewType) {
                super(view);
                this.viewType = viewType;
                if (viewType == ROW_HEADER) {
                    tvDay = view.findViewById(R.id.tv_media_day_header);
                } else if (viewType == ROW_FOLDER) {
                    tvFolderName = view.findViewById(R.id.tv_folder_name);
                    tvFolderMore = view.findViewById(R.id.tv_folder_more);
                    llFolderPreview = view.findViewById(R.id.ll_folder_preview);
                } else if (viewType == ROW_ADD_FOLDER) {
                    tvAddFolder = view.findViewById(R.id.tv_add_folder);
                } else if (viewType == ROW_FOLDER_HEADER) {
                    tvFolderBack = view.findViewById(R.id.tv_folder_back);
                    tvFolderHeaderName = view.findViewById(R.id.tv_folder_header_name);
                } else {
                    ivThumb = view.findViewById(R.id.iv_gallery_thumb);
                    llPlaceholder = view.findViewById(R.id.ll_gallery_placeholder);
                    tvHint = view.findViewById(R.id.tv_gallery_hint);
                    flBadge = view.findViewById(R.id.fl_gallery_badge);
                    vBatchOverlay = view.findViewById(R.id.v_batch_overlay);
                    flBatchCheck = view.findViewById(R.id.fl_batch_check);
                }
            }

            void bind(GalleryRow row, int position) {
                if (viewType == ROW_HEADER) {
                    tvDay.setText(row.day);
                    return;
                }
                if (viewType == ROW_FOLDER) {
                    bindFolderRow(row);
                    return;
                }
                if (viewType == ROW_ADD_FOLDER) {
                    tvAddFolder.setOnClickListener(v -> showCreateFolderDialog());
                    return;
                }
                if (viewType == ROW_FOLDER_HEADER) {
                    tvFolderHeaderName.setText(row.day == null ? "" : row.day);
                    tvFolderBack.setOnClickListener(v -> {
                        currentFolderId = null;
                        loadGallery();
                    });
                    return;
                }
                final MediaCacheEntity media = row.media;
                final String mime = media.mime;
                final boolean video = MediaUtils.isVideo(mime);
                flBadge.setVisibility(video ? View.VISIBLE : View.GONE);

                // 批量选择模式：角标 + 遮罩 + 点选切换；非批量：正常浏览/长按菜单
                final boolean selected = batchSelected.contains(media.fileId);
                if (batchMode) {
                    flBatchCheck.setVisibility(View.VISIBLE);
                    flBatchCheck.getBackground().setTint(selected
                            ? getColor(R.color.primary) : getColor(R.color.surface));
                    vBatchOverlay.setVisibility(selected ? View.VISIBLE : View.GONE);
                    itemView.setOnClickListener(v -> toggleBatchSelect(media));
                    itemView.setOnLongClickListener(null);
                } else {
                    flBatchCheck.setVisibility(View.GONE);
                    vBatchOverlay.setVisibility(View.GONE);
                }

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

                if (!batchMode) {
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
                        showMediaActions(media);
                        return true;
                    });
                }
            }

            /** 文件夹卡片行：第一行名称（未分类行显示「未分类」），第二行缩略图堆叠 + 查看更多；点击进入该组 */
            void bindFolderRow(GalleryRow row) {
                final boolean isUnfiled = row.folder == null;
                tvFolderName.setText(isUnfiled ? getString(R.string.gallery_unfiled) : row.folder.name);
                llFolderPreview.removeAllViews();
                if (row.previews.isEmpty()) {
                    TextView empty = new TextView(GalleryActivity.this);
                    empty.setText(R.string.gallery_folder_no_content);
                    empty.setTextColor(getColor(R.color.text_secondary));
                    empty.setTextSize(12f);
                    llFolderPreview.addView(empty);
                } else {
                    for (MediaCacheEntity m : row.previews) {
                        ImageView iv = new ImageView(GalleryActivity.this);
                        int size = dp(56);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                        lp.setMargins(0, 0, dp(6), 0);
                        iv.setLayoutParams(lp);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        iv.setBackground(tintBg(dp(8), getColor(R.color.surface_alt)));
                        String local = m.localPath;
                        if (local != null && new File(local).exists()) {
                            loadThumb(iv, local, m.mime);
                        } else {
                            iv.setImageResource(R.drawable.ic_image);
                        }
                        llFolderPreview.addView(iv);
                    }
                }
                View.OnClickListener enter = v -> {
                    currentFolderId = isUnfiled ? MODE_UNFILED : row.folder.id;
                    loadGallery();
                };
                itemView.setOnClickListener(enter);
                tvFolderMore.setOnClickListener(enter);
            }
        }
    }
}