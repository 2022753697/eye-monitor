package com.eyemonitor.ui;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.eyemonitor.R;

import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信式媒体选择器：最近照片/视频 4 列网格 + 相册下拉，多选（≤9）顺序角标，底部「发送(n)」。
 * 数据源 MediaStore.Files（API 29+ 免存储权限；API 24-28 需 READ_EXTERNAL_STORAGE）。
 * 选中结果通过 {@link #EXTRA_SELECTED_URIS}（ArrayList&lt;Uri&gt;）返回给聊天页。
 */
public class MediaPickerActivity extends BaseActivity {

    private static final String TAG = "MediaPickerActivity";
    public static final int MAX_SELECT = 9;
    public static final String EXTRA_SELECTED_URIS = "selected_uris";
    private static final int REQ_READ_STORAGE = 7001;

    private LinearLayout albumPanel;
    private TextView tvAlbumName;
    private TextView tvSelected;
    private Button btnSend;
    private FrameLayout previewOverlay;
    private ViewPager2 previewPager;
    private TextView tvPreviewBadge;
    /** 预览页图片列表（当前相册的图片子集） */
    private final List<MediaItem> previewImages = new ArrayList<>();
    private int currentPreviewIndex;
    private PreviewAdapter previewAdapter;

    private final List<MediaItem> allMedia = new ArrayList<>();
    private final List<MediaItem> shownMedia = new ArrayList<>();
    private final List<Album> albums = new ArrayList<>();
    /** mediaId -> 选中顺序（1..n），插入序=选择序 */
    private final LinkedHashMap<Long, Integer> selected = new LinkedHashMap<>();
    @Nullable private Album currentAlbum; // null = 全部

    private GridAdapter gridAdapter;
    private AlbumAdapter albumAdapter;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_picker);

        tvAlbumName = findViewById(R.id.tv_album_name);
        tvSelected = findViewById(R.id.tv_selected);
        btnSend = findViewById(R.id.btn_send);
        albumPanel = findViewById(R.id.album_panel);
        previewOverlay = findViewById(R.id.preview_overlay);
        previewPager = findViewById(R.id.preview_pager);
        tvPreviewBadge = findViewById(R.id.tv_preview_badge);
        previewAdapter = new PreviewAdapter();
        previewPager.setAdapter(previewAdapter);
        previewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentPreviewIndex = position;
                updatePreviewBadge();
            }
        });
        // 预览页右上角圆圈：选中/取消当前预览照片
        tvPreviewBadge.setOnClickListener(v -> {
            if (currentPreviewIndex >= 0 && currentPreviewIndex < previewImages.size()) {
                toggleSelect(previewImages.get(currentPreviewIndex));
            }
        });

        RecyclerView rvGrid = findViewById(R.id.rv_grid);
        gridAdapter = new GridAdapter();
        rvGrid.setLayoutManager(new GridLayoutManager(this, 4));
        rvGrid.setAdapter(gridAdapter);

        RecyclerView rvAlbums = findViewById(R.id.rv_albums);
        albumAdapter = new AlbumAdapter();
        rvAlbums.setLayoutManager(new LinearLayoutManager(this));
        rvAlbums.setAdapter(albumAdapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_album_dropdown).setOnClickListener(v -> toggleAlbumPanel());
        findViewById(R.id.preview_close).setOnClickListener(v -> closePreview());
        btnSend.setOnClickListener(v -> confirmSend());

        updateBottomBar();
        loadMediaAndAlbums();
    }

    // --- 权限（Android 13+ READ_MEDIA_*；Android 9-12 READ_EXTERNAL_STORAGE；≤8 同前） ---

    private boolean canReadMedia() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_READ_STORAGE) {
            boolean granted = grantResults.length > 0;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                }
            }
            if (granted) {
                loadMediaAndAlbums();
            } else {
                Toast.makeText(this, R.string.picker_need_storage, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // --- 数据加载（后台线程查询 MediaStore） ---

    private void loadMediaAndAlbums() {
        if (!canReadMedia()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO}, REQ_READ_STORAGE);
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQ_READ_STORAGE);
            }
            return;
        }
        new Thread(this::queryMediaStore).start();
    }

    private void queryMediaStore() {
        final List<MediaItem> result = new ArrayList<>();
        try {
            Uri base = MediaStore.Files.getContentUri("external");
            String[] projection = {
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.Files.FileColumns.DURATION,
                    MediaStore.Files.FileColumns.BUCKET_ID,
                    MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
            };
            String selection = MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
                    + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + ","
                    + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")";
            try (Cursor c = getContentResolver().query(base, projection, selection, null,
                    MediaStore.Files.FileColumns.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                    int iMime = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE);
                    int iType = c.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE);
                    int iDur = c.getColumnIndex(MediaStore.Files.FileColumns.DURATION);
                    int iBucket = c.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_ID);
                    int iBucketName = c.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME);
                    while (c.moveToNext()) {
                        long id = c.getLong(iId);
                        MediaItem it = new MediaItem();
                        it.id = id;
                        it.uri = ContentUris.withAppendedId(base, id);
                        it.mime = iMime >= 0 ? c.getString(iMime) : null;
                        it.video = iType >= 0
                                && c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                        it.durationMs = iDur >= 0 ? c.getLong(iDur) : 0;
                        it.bucketId = iBucket >= 0 ? c.getString(iBucket) : "";
                        it.bucketName = iBucketName >= 0 ? c.getString(iBucketName) : "";
                        result.add(it);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore 查询失败", e);
        }
        main.post(() -> {
            allMedia.clear();
            allMedia.addAll(result);
            buildAlbums();
            showAlbum(null);
        });
    }

    private void buildAlbums() {
        albums.clear();
        LinkedHashMap<String, Album> map = new LinkedHashMap<>();
        for (MediaItem m : allMedia) {
            Album a = map.get(m.bucketId);
            if (a == null) {
                a = new Album(m.bucketId, m.bucketName);
                map.put(m.bucketId, a);
            }
            a.count++;
        }
        albums.addAll(map.values());
    }

    // --- 相册切换 / 选择 / 发送 ---

    private void toggleAlbumPanel() {
        albumAdapter.notifyDataSetChanged();
        albumPanel.setVisibility(albumPanel.getVisibility() == View.VISIBLE
                ? View.GONE : View.VISIBLE);
    }

    private void showAlbum(@Nullable Album album) {
        currentAlbum = album;
        tvAlbumName.setText(album == null ? getString(R.string.picker_album_all) : album.name);
        shownMedia.clear();
        if (album == null) {
            shownMedia.addAll(allMedia);
        } else {
            for (MediaItem m : allMedia) {
                if (m.bucketId.equals(album.id)) {
                    shownMedia.add(m);
                }
            }
        }
        gridAdapter.notifyDataSetChanged();
        albumPanel.setVisibility(View.GONE);
    }

    private void toggleSelect(MediaItem item) {
        if (selected.containsKey(item.id)) {
            selected.remove(item.id);
            resequence();
        } else {
            if (selected.size() >= MAX_SELECT) {
                Toast.makeText(this, getString(R.string.picker_max_reached, MAX_SELECT),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            selected.put(item.id, selected.size() + 1);
            gridAdapter.notifyItemChanged(shownMedia.indexOf(item));
        }
        updateBottomBar();
        updatePreviewBadge();
    }

    private void resequence() {
        LinkedHashMap<Long, Integer> recalc = new LinkedHashMap<>();
        int n = 1;
        for (Long id : selected.keySet()) {
            recalc.put(id, n++);
        }
        selected.clear();
        selected.putAll(recalc);
        gridAdapter.notifyDataSetChanged();
    }

    private void updateBottomBar() {
        tvSelected.setText(getString(R.string.picker_selected_count,
                selected.size(), MAX_SELECT));
        if (selected.isEmpty()) {
            // 未选：按钮常显示「发送」但半透明（微信式灰置感）
            btnSend.setText(R.string.picker_send);
            btnSend.setAlpha(0.35f);
        } else {
            btnSend.setText(getString(R.string.picker_send_count, selected.size()));
            btnSend.setAlpha(1f);
        }
    }

    // --- 图片预览（微信式：点图片中部放大，左右滑切换照片，右上角圆圈选择） ---

    private void showPreview(MediaItem item) {
        if (item == null || item.video) {
            return;
        }
        previewImages.clear();
        for (MediaItem m : shownMedia) {
            if (!m.video) {
                previewImages.add(m);
            }
        }
        int idx = previewImages.indexOf(item);
        if (idx < 0) {
            idx = 0;
        }
        previewAdapter.notifyDataSetChanged();
        previewPager.setCurrentItem(idx, false);
        currentPreviewIndex = idx;
        previewOverlay.setVisibility(View.VISIBLE);
        updatePreviewBadge();
        albumPanel.setVisibility(View.GONE);
    }

    private void closePreview() {
        previewOverlay.setVisibility(View.GONE);
        previewPager.setCurrentItem(0, false);
    }

    private void updatePreviewBadge() {
        if (currentPreviewIndex < 0 || currentPreviewIndex >= previewImages.size()) {
            return;
        }
        MediaItem it = previewImages.get(currentPreviewIndex);
        Integer order = selected.get(it.id);
        if (order != null) {
            tvPreviewBadge.setText(String.valueOf(order));
            tvPreviewBadge.setBackgroundResource(R.drawable.bg_dot);
        } else {
            tvPreviewBadge.setText("");
            tvPreviewBadge.setBackgroundResource(R.drawable.bg_select_badge_off);
        }
    }

    /** 预览分页适配器：每页一个 ZoomImageView，1x 时左/右滑切换照片 */
    class PreviewAdapter extends RecyclerView.Adapter<PreviewAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(MediaPickerActivity.this)
                    .inflate(R.layout.media_picker_preview_item, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            MediaItem item = previewImages.get(position);
            h.zoom.reset();
            Glide.with(MediaPickerActivity.this).load(item.uri).into(h.zoom);
            h.zoom.post(h.zoom::centerFit);
            h.zoom.setSwipeListener(next -> {
                int cur = previewPager.getCurrentItem();
                int target = next ? cur + 1 : cur - 1;
                if (target >= 0 && target < previewImages.size()) {
                    previewPager.setCurrentItem(target, true);
                }
            });
        }

        @Override
        public int getItemCount() {
            return previewImages.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ZoomImageView zoom;

            VH(@NonNull View itemView) {
                super(itemView);
                zoom = itemView.findViewById(R.id.preview_zoom);
            }
        }
    }

    private void confirmSend() {
        if (selected.isEmpty()) {
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        for (Long id : selected.keySet()) {
            for (MediaItem m : allMedia) {
                if (m.id == id) {
                    uris.add(m.uri);
                    break;
                }
            }
        }
        Intent out = new Intent();
        out.putParcelableArrayListExtra(EXTRA_SELECTED_URIS, uris);
        setResult(RESULT_OK, out);
        finish();
    }

    // --- 内部模型 ---

    static class MediaItem {
        long id;
        Uri uri;
        String mime;
        boolean video;
        long durationMs;
        String bucketId;
        String bucketName;
    }

    static class Album {
        final String id;
        final String name;
        int count;

        Album(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    // --- 网格适配器（4 列方形格子） ---

    class GridAdapter extends RecyclerView.Adapter<GridAdapter.VH> {

        private final int cellSize;

        GridAdapter() {
            int screenW = getResources().getDisplayMetrics().widthPixels;
            cellSize = (screenW - 8 * (int) getResources().getDisplayMetrics().density) / 4;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(MediaPickerActivity.this)
                    .inflate(R.layout.media_picker_item, parent, false);
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
            lp.height = cellSize;
            lp.width = cellSize;
            v.setLayoutParams(lp);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            MediaItem item = shownMedia.get(position);
            Glide.with(MediaPickerActivity.this)
                    .load(item.uri)
                    .centerCrop()
                    .into(h.ivThumb);

            h.llVideoBadge.setVisibility(item.video ? View.VISIBLE : View.GONE);
            if (item.video) {
                h.tvVideoDuration.setText(formatDuration(item.durationMs));
            }

            Integer order = selected.get(item.id);
            if (order != null) {
                h.tvSelectBadge.setText(String.valueOf(order));
                h.tvSelectBadge.setBackgroundResource(R.drawable.bg_dot);
            } else {
                h.tvSelectBadge.setText("");
                h.tvSelectBadge.setBackgroundResource(R.drawable.bg_select_badge_off);
            }

            h.itemView.setOnClickListener(v -> {
                albumPanel.setVisibility(View.GONE);
                // 微信式：点图片中部 = 预览放大；点右上角圆圈 = 选择
                if (item.video) {
                    toggleSelect(item);
                } else {
                    showPreview(item);
                }
            });
            // 右上角圆圈：独立点击区（选中/取消）
            h.tvSelectBadge.setOnClickListener(v -> toggleSelect(item));
        }

        @Override
        public int getItemCount() {
            return shownMedia.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView ivThumb;
            final LinearLayout llVideoBadge;
            final TextView tvVideoDuration;
            final TextView tvSelectBadge;

            VH(@NonNull View itemView) {
                super(itemView);
                ivThumb = itemView.findViewById(R.id.iv_thumb);
                llVideoBadge = itemView.findViewById(R.id.ll_video_badge);
                tvVideoDuration = itemView.findViewById(R.id.tv_video_duration);
                tvSelectBadge = itemView.findViewById(R.id.tv_select_badge);
            }
        }
    }

    // --- 相册列表适配器 ---

    class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(MediaPickerActivity.this)
                    .inflate(R.layout.media_picker_album_item, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Album a = albums.get(position);
            h.tvName.setText(a.name);
            h.tvCount.setText(String.valueOf(a.count));
            h.itemView.setOnClickListener(v -> showAlbum(a));
        }

        @Override
        public int getItemCount() {
            return albums.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvName;
            final TextView tvCount;

            VH(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_album_name);
                tvCount = itemView.findViewById(R.id.tv_album_count);
            }
        }
    }

    private String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long mm = totalSec / 60;
        long ss = totalSec % 60;
        return mm + ":" + (ss < 10 ? "0" + ss : String.valueOf(ss));
    }
}