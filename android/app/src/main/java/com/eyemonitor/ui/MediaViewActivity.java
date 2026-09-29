package com.eyemonitor.ui;

import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.eyemonitor.R;

import java.io.File;

/**
 * 全屏图片查看页（本地缓存文件路径驱动）。
 */
public class MediaViewActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_view);

        ImageView ivPhoto = findViewById(R.id.iv_media_photo);
        ImageButton btnBack = findViewById(R.id.btn_media_back);
        btnBack.setOnClickListener(v -> finish());

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !new File(path).exists()) {
            Toast.makeText(this, R.string.media_file_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Glide.with(this)
                .load(new File(path))
                .fitCenter()
                .error(R.drawable.ic_image)
                .into(ivPhoto);
    }
}
