package com.eyemonitor.util;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 备忘录本地图片存储：相册 Uri → 应用私有目录拷贝（长边 ≤1920，质量 85）。
 * <p>
 * 纯本地用途：不传服务器、不入 media_cache（与任务/图库共享通道完全隔离）。
 */
public final class MemoImageStore {

    private static final String TAG = "MemoImageStore";
    private static final String DIR = "memos";
    private static final int MAX_EDGE = 1920;
    private static final int QUALITY = 85;

    private MemoImageStore() {}

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 拷贝一张相册图片到私有目录，返回绝对路径；失败返回 null */
    public static String saveImage(Context ctx, Uri uri) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            try (InputStream is = cr.openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, opt);
            }
            int sample = 1;
            while (opt.outWidth / sample > MAX_EDGE || opt.outHeight / sample > MAX_EDGE) {
                sample *= 2;
            }
            opt.inJustDecodeBounds = false;
            opt.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream is = cr.openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(is, null, opt);
            }
            if (bmp == null) return null;
            File out = new File(dir(ctx), System.currentTimeMillis() + "_" + (int) (Math.random() * 100000) + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, fos);
            }
            bmp.recycle();
            Log.i(TAG, "已存图: " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "存图失败: " + uri, e);
            return null;
        }
    }

    /** 删除一张本地图片（删除备忘录/移除图片时调用） */
    public static void deleteImage(String path) {
        if (path == null) return;
        try {
            File f = new File(path);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
    }

    /** 删除一组逗号分隔路径 */
    public static void deleteImages(String csv) {
        if (csv == null || csv.isEmpty()) return;
        for (String p : csv.split(",")) {
            if (!p.trim().isEmpty()) deleteImage(p.trim());
        }
    }
}
