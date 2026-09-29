package com.eyemonitor.ui;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import com.eyemonitor.R;

import java.io.File;

/**
 * 全屏视频播放页（VideoView 播放本地缓存文件，带系统控制条）。
 */
public class VideoPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        ImageButton btnBack = findViewById(R.id.btn_video_back);
        btnBack.setOnClickListener(v -> finish());

        VideoView videoView = findViewById(R.id.vv_player);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !new File(path).exists()) {
            Toast.makeText(this, R.string.media_file_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        videoView.setVideoPath(path);
        videoView.setOnPreparedListener(MediaPlayer::start);
        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, R.string.media_play_failed, Toast.LENGTH_SHORT).show();
            return true;
        });
    }
}
