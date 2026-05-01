package com.xiaofeng.theater

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: StyledPlayerView
    private lateinit var overlay: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var episodeTitle: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var playPauseBtn: ImageButton
    private lateinit var nextEpBtn: ImageButton
    private lateinit var episodeBtn: TextView
    private lateinit var exitBtn: TextView
    private lateinit var centerPlayBtn: ImageView
    private lateinit var seekBar: android.widget.SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var seekbarContainer: LinearLayout

    private var exoPlayer: ExoPlayer? = null
    private var episodes: Array<String> = emptyArray()
    private var episodeTitles: Array<String> = emptyArray()
    private var currentEpIdx = 0
    private var showTitle = ""
    private var isControlsVisible = true
    private var isSeeking = false
    private var startX = 0f
    private var startY = 0f
    private var startBrightness = 0f
    private var startVolume = 0
    private var startSeekPos = 0L
    private var gestureType = 0 // 0=none, 1=brightness, 2=volume, 3=seek
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private val audioManager: AudioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val maxVolume by lazy { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        setContentView(R.layout.activity_player)
        initViews()
        loadIntentData()
        setupExoPlayer()
    }

    private fun initViews() {
        playerView = findViewById(R.id.player_view)
        overlay = findViewById(R.id.gesture_overlay)
        topBar = findViewById(R.id.top_bar)
        episodeTitle = findViewById(R.id.episode_title)
        bottomBar = findViewById(R.id.bottom_bar)
        playPauseBtn = findViewById(R.id.btn_play_pause)
        nextEpBtn = findViewById(R.id.btn_next_ep)
        episodeBtn = findViewById(R.id.btn_episodes)
        exitBtn = findViewById(R.id.btn_exit)
        centerPlayBtn = findViewById(R.id.center_play_btn)
        seekBar = findViewById(R.id.seek_bar)
        timeCurrent = findViewById(R.id.time_current)
        timeTotal = findViewById(R.id.time_total)
        seekbarContainer = findViewById(R.id.seekbar_container)

        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = exoPlayer?.duration ?: return
                    exoPlayer?.seekTo((progress / 1000.0 * dur).toLong())
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { hideHandler.removeCallbacks(hideRunnable) }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { resetAutoHide() }
        })

        // 每 500ms 更新进度
        val updateRunnable = object : Runnable { override fun run() { updateSeekbar(); hideHandler.postDelayed(this, 500) } }
        hideHandler.post(updateRunnable)

        playerView.useController = false

        exitBtn.setOnClickListener { finish() }
        playPauseBtn.setOnClickListener { togglePlayPause() }
        nextEpBtn.setOnClickListener { playNextEpisode() }
        episodeBtn.setOnClickListener { showEpisodeDialog() }

        // 双击暂停 + 单击显示控件
        val gestureDetector = GestureDetector(this, SingleDoubleTapListener())
        overlay.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            handleGestureMotion(event)
            true
        }

        resetAutoHide()
    }

    private fun loadIntentData() {
        showTitle = intent.getStringExtra("title") ?: ""
        episodeTitles = intent.getStringArrayExtra("epTitles") ?: emptyArray()
        episodes = intent.getStringArrayExtra("epUrls") ?: emptyArray()
        currentEpIdx = intent.getIntExtra("epIdx", 0)

        if (episodeTitles.size > currentEpIdx) {
            episodeTitle.text = "${showTitle} · ${episodeTitles[currentEpIdx]}"
        }
    }

    private fun setupExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer
        playEpisode(currentEpIdx)

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                updatePlayPauseBtn()
                if (state == Player.STATE_ENDED) {
                    playNextEpisode()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseBtn()
            }
        })
    }

    private fun playEpisode(idx: Int) {
        if (idx < 0 || idx >= episodes.size) return
        currentEpIdx = idx
        val url = episodes[idx]
        episodeTitle.text = "${showTitle} · ${episodeTitles.getOrElse(idx) { "第${idx+1}集" }}"

        val mediaItem = MediaItem.fromUri(Uri.parse(url))
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
        updateEpisodeBtn()
    }

    private fun playNextEpisode() {
        if (currentEpIdx + 1 < episodes.size) {
            playEpisode(currentEpIdx + 1)
        } else {
            Toast.makeText(this, "已是最后一集", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 双击/单击监听 =====
    inner class SingleDoubleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            toggleControls()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            togglePlayPause()
            return true
        }
    }

    // ===== 手势处理 =====
    private fun handleGestureMotion(event: MotionEvent) {
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                gestureType = 0
                startX = event.x
                startY = event.y
                startBrightness = window.attributes.screenBrightness
                if (startBrightness < 0) startBrightness = 0.5f
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                startSeekPos = exoPlayer?.currentPosition ?: 0L
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                val absDx = abs(dx)
                val absDy = abs(dy)

                if (gestureType == 0 && (absDx > 30 || absDy > 30)) {
                    gestureType = if (absDx > absDy * 1.5) 3 else if (startX < overlay.width / 2f) 1 else 2
                }

                when (gestureType) {
                    1 -> changeBrightness(-dy / overlay.height)
                    2 -> changeVolume(-dy / overlay.height)
                    3 -> showSeekHint(dx / overlay.width)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureType == 3 && isSeeking) {
                    val ratio = (event.x - startX) / overlay.width
                    val seekTo = (startSeekPos + (ratio * exoPlayer?.duration!!).toLong()).coerceIn(0L, exoPlayer?.duration ?: 0L)
                    exoPlayer?.seekTo(seekTo)
                    isSeeking = false
                    hideSeekHint()
                }
                gestureType = 0
            }
        }
    }

    private fun changeBrightness(ratio: Float) {
        val lp = window.attributes
        lp.screenBrightness = (startBrightness + ratio).coerceIn(0.01f, 1.0f)
        window.attributes = lp
    }

    private fun changeVolume(ratio: Float) {
        val vol = (startVolume + (ratio * maxVolume)).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
    }

    private fun showSeekHint(ratio: Float) {
        isSeeking = true
        val dur = exoPlayer?.duration ?: return
        val target = (startSeekPos + (ratio * dur).toLong()).coerceIn(0L, dur)
        val tv = findViewById<TextView>(R.id.seek_hint)
        tv.visibility = View.VISIBLE
        tv.text = formatTime(target) + " / " + formatTime(dur)
    }

    private fun hideSeekHint() {
        findViewById<TextView>(R.id.seek_hint).visibility = View.GONE
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%02d:%02d".format(min, sec)
    }

    // ===== 控制栏 =====
    private fun togglePlayPause() {
        val p = exoPlayer ?: return
        if (p.isPlaying) p.pause() else p.play()
        resetAutoHide()
    }

    private fun updatePlayPauseBtn() {
        val playing = exoPlayer?.isPlaying ?: false
        playPauseBtn.setImageResource(
            if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        centerPlayBtn.visibility = if (playing) View.GONE else View.VISIBLE
    }

    private fun toggleControls() {
        if (isControlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        isControlsVisible = true
        topBar.animate().alpha(1f).translationY(0f).setDuration(200).start()
        bottomBar.animate().alpha(1f).translationY(0f).setDuration(200).start()
        seekbarContainer.animate().alpha(1f).setDuration(200).start()
        resetAutoHide()
    }

    private fun hideControls() {
        isControlsVisible = false
        topBar.animate().alpha(0f).translationY(-100f).setDuration(300).start()
        bottomBar.animate().alpha(0f).translationY(100f).setDuration(300).start()
        seekbarContainer.animate().alpha(0f).setDuration(300).start()
        centerPlayBtn.animate().alpha(0f).setDuration(300).start()
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun resetAutoHide() {
        hideHandler.removeCallbacks(hideRunnable)
        if (isControlsVisible) {
            hideHandler.postDelayed(hideRunnable, 4000)
        }
    }

    // ===== 选集弹窗 =====
    private fun showEpisodeDialog() {
        val dialog = Dialog(this, R.style.BottomSheetDialog)
        val view = layoutInflater.inflate(R.layout.dialog_episodes, null)
        val list = view.findViewById<LinearLayout>(R.id.episode_list)
        val tvTitle = view.findViewById<TextView>(R.id.dialog_title)
        view.findViewById<TextView>(R.id.dialog_close).setOnClickListener { dialog.dismiss() }
        tvTitle.text = "$showTitle · 选集"

        episodeTitles.forEachIndexed { idx, title ->
            val btn = TextView(this).apply {
                text = title
                setPadding(32, 24, 32, 24)
                setTextColor(Color.WHITE)
                textSize = 15f
                setBackgroundResource(if (idx == currentEpIdx) R.drawable.ep_selected_bg else R.drawable.ep_bg)
                setOnClickListener {
                    playEpisode(idx)
                    dialog.dismiss()
                }
            }
            list.addView(btn)
        }

        dialog.setContentView(view)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 1200.dp())
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(R.style.SlideUpAnimation)
        }
        dialog.show()
        resetAutoHide()
    }

    private fun updateEpisodeBtn() {
        episodeBtn.text = "选集"
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        resetAutoHide()
    }

    override fun onPause() {
        super.onPause()
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun updateSeekbar() {
        val p = exoPlayer ?: return
        val dur = p.duration
        if (dur > 0) {
            seekBar.progress = (p.currentPosition * 1000 / dur).toInt()
            timeCurrent.text = formatTime(p.currentPosition)
            timeTotal.text = formatTime(dur)
        }
    }

    override fun onDestroy() {
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}
