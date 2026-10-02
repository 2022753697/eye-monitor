package com.eyemonitor.util;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.bumptech.glide.Glide;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.MediaCacheEntity;
import com.eyemonitor.ui.MediaViewActivity;
import com.eyemonitor.ui.VideoPlayerActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 媒体工具：选取校验（≤100MB / 视频 ≤5 分钟）、本地缓存路径、HTTP 下载、全屏打开。
 */
public final class MediaUtils {

    private static final String TAG = "MediaUtils";

    /** 服务端上限一致：100MB */
    public static final long MAX_MEDIA_BYTES = 100L * 1024 * 1024;
    /** 视频时长上限：5 分钟 */
    public static final long MAX_VIDEO_MS = 5 * 60 * 1000L;

    private MediaUtils() {}

    /** 打开全屏前确保文件已就绪的回调（localPath 为本地缓存完整路径） */
    public interface MediaCb {
        void onReady(String localPath);
        void onError(int code, String msg);
    }

    // --- 本地缓存路径 ---

    public static File mediaDir(Context context) {
        File dir = new File(context.getFilesDir(), "media");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File localMediaFile(Context context, String fileId) {
        return new File(mediaDir(context), fileId);
    }

    public static boolean isImage(String mime) {
        return mime != null && mime.startsWith("image/");
    }

    public static boolean isVideo(String mime) {
        return mime != null && mime.startsWith("video/");
    }

    /** 系统返回的 mime 为空时，按文件名后缀推断（相册 heic/heif 等场景） */
    public static String inferMime(String mime, String fileName) {
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"))) {
            return mime;
        }
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        switch (fileName.substring(dot + 1).toLowerCase()) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "heic": case "heif": return "image/heic";
            case "mp4": return "video/mp4";
            case "mov": return "video/quicktime";
            case "3gp": return "video/3gpp";
            case "m4a": return "audio/mp4";
            case "mp3": return "audio/mpeg";
            case "aac": return "audio/aac";
            default: return null;
        }
    }

    // --- 选取校验 ---

    /** 读取文件大小（OpenableColumns，失败返回 -1） */
    public static long querySize(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "querySize 失败", e);
        }
        return -1;
    }

    /** 读取文件名（失败回退 media_send_tmp） */
    public static String queryDisplayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && !c.isNull(idx)) return c.getString(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "queryDisplayName 失败", e);
        }
        return "media_send_tmp";
    }

    /** 读取视频时长（毫秒，失败返回 -1） */
    public static long queryDurationMs(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d == null) return -1;
            return Long.parseLong(d);
        } catch (Exception e) {
            Log.w(TAG, "读取视频时长失败", e);
            return -1;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
    }

    /** 把所选 Uri 内容拷贝到目标文件（缓存/上传用），返回是否成功 */
    public static boolean copyUriToFile(Context context, Uri uri, File target) {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) return false;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            return target.length() > 0;
        } catch (Exception e) {
            Log.e(TAG, "拷贝所选媒体失败", e);
            return false;
        }
    }

    // --- 下载 + 打开 ---

    /**
     * 确保媒体已缓存到本地：已下载直接回调；否则 GET /api/media/{fileId} 下载并更新 Room localPath。
     */
    public static void ensureDownloaded(Context context, String fileId, Runnable onStart, MediaCb cb) {        final File target = localMediaFile(context, fileId);
        if (target.exists() && target.length() > 0) {
            cb.onReady(target.getAbsolutePath());
            return;
        }
        if (onStart != null) onStart.run();
        AuthManager.i(context).downloadMedia(context, fileId, target, new AuthManager.DownloadCallback() {
            @Override
            public void onSuccess(File file) {
                final String path = file.getAbsolutePath();
                // 更新 Room 的 localPath（保留其余元数据）
                AppDatabase db = AppDatabase.getInstance(context);
                AppDatabase.dbExecutor.execute(() -> {
                    MediaCacheEntity e = db.cacheDao().getMedia(fileId);
                    if (e == null) {
                        e = new MediaCacheEntity();
                        e.fileId = fileId;
                        e.ts = System.currentTimeMillis();
                    }
                    e.localPath = path;
                    // 语音波形（新消息）：音频下载完成后分析包络缓存，失败静默回退 wifi 图标
                    if (e.mime != null && e.mime.startsWith("audio/")
                            && com.eyemonitor.util.WaveformMath.fromCsv(e.waveform) == null) {
                        try {
                            String wave = com.eyemonitor.util.WaveformAnalyzer.analyze(file);
                            if (wave != null) e.waveform = wave;
                        } catch (Exception ignored) {}
                    }
                    db.cacheDao().upsertMedia(e);
                });
                cb.onReady(path);
            }

            @Override
            public void onError(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 任务媒体下载：只落文件不写 media_cache（任务配图不进共享图库） */
    public static void ensureTaskMedia(Context context, String fileId, Runnable onStart, MediaCb cb) {
        final File target = localMediaFile(context, fileId);        if (target.exists() && target.length() > 0) {
            cb.onReady(target.getAbsolutePath());
            return;
        }
        if (onStart != null) onStart.run();
        AuthManager.i(context).downloadMedia(context, fileId, target, new AuthManager.DownloadCallback() {
            @Override
            public void onSuccess(File file) {
                cb.onReady(file.getAbsolutePath());
            }

            @Override
            public void onError(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 任务配图加载：mediaFileIds 逗号串取第一张；本地缺失时下载（不写缓存）；centerCrop 用于缩略图 */
    public static void loadTaskPhoto(Context context, final ImageView iv,
                                     final String mediaFileIds, final boolean centerCrop) {
        if (mediaFileIds == null || mediaFileIds.isEmpty()) {
            iv.setVisibility(View.GONE);
            return;
        }
        loadTaskPhotoInto(context, iv, mediaFileIds.split(",")[0].trim(), centerCrop);
    }

    /** 单张任务配图加载（本地缺失时下载，不写 media_cache） */
    public static void loadTaskPhotoInto(Context context, final ImageView iv,
                                         final String fileId, final boolean centerCrop) {
        if (fileId == null || fileId.isEmpty()) {
            iv.setVisibility(View.GONE);
            return;
        }
        iv.setVisibility(View.VISIBLE);
        final File f = localMediaFile(context, fileId);
        if (f != null && f.exists()) {
            if (centerCrop) Glide.with(iv).load(f).centerCrop().into(iv);
            else Glide.with(iv).load(f).fitCenter().into(iv);
            return;
        }
        ensureTaskMedia(context, fileId, null, new MediaCb() {
            @Override
            public void onReady(String localPath) {
                iv.post(() -> {
                    if (centerCrop) Glide.with(iv).load(new File(localPath)).centerCrop().into(iv);
                    else Glide.with(iv).load(new File(localPath)).fitCenter().into(iv);
                });
            }

            @Override
            public void onError(int code, String msg) {
                iv.setVisibility(View.GONE);
            }
        });
    }

    /** 多图条：把 mediaFileIds 全部渲染为 thumbDp 缩略图（横向排布，点击回调 fileId） */
    public static void loadTaskPhotos(Context context, LinearLayout container,
                                      String mediaFileIds, int thumbDp,
                                      java.util.function.Consumer<String> onClick) {
        container.removeAllViews();
        if (mediaFileIds == null || mediaFileIds.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int size = (int) (thumbDp * density);
        String[] ids = mediaFileIds.split(",");
        int added = 0;
        for (String s : ids) {
            final String fid = s.trim();
            if (fid.isEmpty()) continue;
            ImageView iv = new ImageView(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = (int) (4 * density);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_media_placeholder);
            iv.setOnClickListener(v -> {
                if (onClick != null) onClick.accept(fid);
            });
            loadTaskPhotoInto(context, iv, fid, true);
            container.addView(iv);
            added++;
        }
        if (added == 0) {
            container.setVisibility(View.GONE);
        } else {
            container.setVisibility(View.VISIBLE);
        }
    }

    /** 全屏放大预览任务配图（黑底，点击关闭；本地缺失自动下载） */
    public static void openPhotoPreview(final Context context, final String fileId) {
        final ImageView big = new ImageView(context);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        big.setBackgroundColor(0xFF000000);
        big.setClickable(true);
        final android.app.Dialog dlg = new android.app.Dialog(context,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.setContentView(big);
        big.setOnClickListener(v -> dlg.dismiss());
        File f = localMediaFile(context, fileId);
        if (f != null && f.exists()) {
            Glide.with(big).load(f).into(big);
        } else {
            ensureTaskMedia(context, fileId, null, new MediaCb() {
                @Override
                public void onReady(String localPath) {
                    big.post(() -> Glide.with(big).load(new File(localPath)).into(big));
                }

                @Override
                public void onError(int code, String msg) {
                }
            });
        }
        dlg.show();
    }


    /** 确保已下载后打开全屏查看（图片 / 视频播放页） */
    public static void openMedia(Context context, String fileId, String mime, long duration,
                                 Runnable onStart, MediaCb cb) {
        ensureDownloaded(context, fileId, onStart, new MediaCb() {
            @Override
            public void onReady(String localPath) {
                launchViewer(context, fileId, mime, duration, localPath);
                if (cb != null) cb.onReady(localPath);
            }

            @Override
            public void onError(int code, String msg) {
                if (cb != null) cb.onError(code, msg);
            }
        });
    }

    /** 按媒体类型启动全屏页（图片 MediaViewActivity / 视频 VideoPlayerActivity） */
    public static void launchViewer(Context context, String fileId, String mime, long duration,
                                    String localPath) {
        if (isVideo(mime)) {
            Intent i = new Intent(context, VideoPlayerActivity.class);
            i.putExtra(VideoPlayerActivity.EXTRA_PATH, localPath);
            context.startActivity(i);
        } else {
            Intent i = new Intent(context, MediaViewActivity.class);
            i.putExtra(MediaViewActivity.EXTRA_PATH, localPath);
            context.startActivity(i);
        }
        // 全屏页由下而上进入
        if (context instanceof android.app.Activity) {
            Transitions.up((android.app.Activity) context);
        }
    }

    /** 删除本地缓存文件（Room 行删除由调用方负责） */
    public static void deleteLocalFile(Context context, String fileId) {
        File f = localMediaFile(context, fileId);
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "删除本地媒体失败: " + f.getAbsolutePath());
        }
        File part = new File(f.getParentFile(), f.getName() + ".part");
        if (part.exists()) part.delete();
    }
}
