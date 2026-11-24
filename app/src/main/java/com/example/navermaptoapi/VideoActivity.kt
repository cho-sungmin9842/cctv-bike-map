package com.example.navermaptoapi

import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.widget.MediaController
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import com.example.navermaptoapi.databinding.ActivityVideoBinding
import com.google.android.material.snackbar.Snackbar

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoActivity : AppCompatActivity() {
    private val binding by lazy {
        ActivityVideoBinding.inflate(layoutInflater)
    }
    private var exoPlayer: ExoPlayer? = null
    private var playWhenReady = true
    private var currentWindow = 0
    private var playbackPosition = 0L
    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // 뒤로가기 시 실행할 코드
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        this.onBackPressedDispatcher.addCallback(this, onBackPressedCallback) // 뒤로가기 콜백
    }

    override fun onResume() {
        super.onResume()
        val url = intent.getStringExtra("url") ?: return
        hideSystemUi()
        if (Util.SDK_INT <= 23 || exoPlayer == null) {
            initializePlayer(url)
            exoPlayer?.play()
        } else {
            exoPlayer?.seekTo(currentWindow, playbackPosition)
            exoPlayer?.play()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Util.SDK_INT > 23) {
            releasePlayer()
        }
    }


    private fun initializePlayer(url: String) {
        exoPlayer = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.exoplayerView.player = exoPlayer
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.playWhenReady = playWhenReady
            exoPlayer.seekTo(currentWindow, playbackPosition)
            exoPlayer.prepare()
        }
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            supportActionBar?.hide()
//            window.setDecorFitsSystemWindows(false) // 버그발생(exoplayer 컨트롤러가 안보여짐)
//            window?.insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            window?.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE or
//                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    private fun releasePlayer() {
        exoPlayer?.let { exoPlayer ->
            playbackPosition = exoPlayer.currentPosition
            currentWindow = exoPlayer.currentMediaItemIndex
            playWhenReady = exoPlayer.playWhenReady
            exoPlayer.release()
        }
        exoPlayer = null
        binding.exoplayerView.player = null
    }
}