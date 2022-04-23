package com.kernel.finch.core.util

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import com.kernel.finch.FinchCore
import com.kernel.finch.core.R
import com.kernel.finch.core.presentation.gallery.GalleryActivity
import com.kernel.finch.core.presentation.gallery.MediaPreviewDialogFragment
import com.kernel.finch.core.util.extension.createScreenCaptureFile
import com.kernel.finch.core.util.extension.createScreenshotFromBitmap
import com.kernel.finch.core.util.extension.getUriForFile
import com.kernel.finch.core.util.extension.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
internal class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private val handlerThread =
        HandlerThread(javaClass.simpleName, Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var handler: Handler
    private lateinit var file: File

    override fun onCreate() {
        super.onCreate()
        handlerThread.start()
        handler = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        when (intent.action) {
            ACTION_DONE -> onReady(getUriForFile(file))
            null -> {
                val isForVideo = intent.getBooleanExtra(EXTRA_IS_FOR_VIDEO, false)
                moveToForeground(isForVideo)
                handler.postDelayed({
                    startCapture(
                        resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                        resultData = intent.getParcelableExtra(EXTRA_RESULT_INTENT)!!,
                        isForVideo = isForVideo,
                        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "fileName"
                    )
                }, SCREENSHOT_DELAY)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? =
        throw IllegalStateException("Binding not supported.")

    private fun cleanUp() {
        projection?.stop()
        try {
            mediaRecorder?.stop()
        } catch (_: RuntimeException) {
        }
        mediaRecorder?.release()
        mediaRecorder = null
        virtualDisplay?.release()
        projection = null
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent,
        isForVideo: Boolean,
        fileName: String
    ) {
        projection =
            (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager?)?.getMediaProjection(
                resultCode,
                resultData
            )
        val displayMetrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(
            displayMetrics
        )
        if (isForVideo) {
            var downscaledWidth = displayMetrics.widthPixels
            var downscaledHeight = displayMetrics.heightPixels
            while (downscaledWidth > 720 && downscaledHeight > 720) {
                downscaledWidth /= 2
                downscaledHeight /= 2
            }
            downscaledWidth = (downscaledWidth / 2) * 2
            downscaledHeight = (downscaledHeight / 2) * 2
            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH).apply {
                    videoFrameHeight = downscaledWidth
                    videoFrameWidth = downscaledHeight
                }.let { profile ->
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoFrameRate(profile.videoFrameRate)
                    setVideoSize(profile.videoFrameHeight, profile.videoFrameWidth)
                    setVideoEncodingBitRate(profile.videoBitRate)
                    setVideoEncoder(profile.videoCodec)
                }
                file = createScreenCaptureFile(fileName)
                setOutputFile(file.absolutePath)
                try {
                    prepare()
                } catch (_: IllegalStateException) {
                } catch (_: IOException) {
                    onReady(null)
                }
            }
            createVirtualDisplay(
                downscaledWidth,
                downscaledHeight,
                displayMetrics.densityDpi,
                mediaRecorder?.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            )
            try {
                mediaRecorder?.start()
            } catch (_: IllegalStateException) {
                onReady(null)
            }
        } else {
            val screenshotWriter = ScreenshotWriter(
                displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                handler
            ) { bitmap ->
                GlobalScope.launch(Dispatchers.IO) {
                    (createScreenshotFromBitmap(bitmap, fileName))?.let { uri ->
                        launch(Dispatchers.Main) { onReady(uri) }
                    }
                }
            }
            createVirtualDisplay(
                displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                displayMetrics.densityDpi,
                screenshotWriter.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            )
            handler.postDelayed({
                FinchCore.implementation.onScreenCaptureReady?.let {
                    if (!screenshotWriter.forceTry()) {
                        onReady(null)
                    }
                }
            }, SCREENSHOT_TIMEOUT)
        }
        if (virtualDisplay?.surface == null) {
            onReady(null)
        }
    }

    private fun moveToForeground(isForVideo: Boolean) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager.getNotificationChannel(
                CHANNEL_ID
            ) == null
        ) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    text(R.string.finch_capture_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setSound(null, null)
                }
            )
        }
        if (isForVideo) {
            Toast.makeText(
                this,
                text(R.string.finch_capture_toast),
                Toast.LENGTH_SHORT
            ).show()
        }
        startForeground(
            RECORDING_NOTIFICATION_ID,
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setAutoCancel(false)
                .setSound(null)
                .setSmallIcon(R.drawable.finch_ic_recording)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setDefaults(Notification.DEFAULT_ALL)
                .setContentTitle(text(R.string.finch_capture_notification_progress_title))
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .apply {
                    if (isForVideo) {
                        setContentIntent(
                            PendingIntent.getService(
                                this@ScreenCaptureService,
                                0,
                                Intent(
                                    this@ScreenCaptureService,
                                    ScreenCaptureService::class.java
                                ).setAction(ACTION_DONE),
                                0
                            )
                        )
                        setStyle(
                            NotificationCompat.BigTextStyle()
                                .bigText(text(R.string.finch_capture_notification_progress_content))
                        )
                    }
                }
                .build()
        )
    }

    private fun createVirtualDisplay(
        width: Int,
        height: Int,
        density: Int,
        surface: Surface?,
        flags: Int
    ) {
        virtualDisplay = projection?.createVirtualDisplay(
            "captureDisplay",
            width,
            height,
            density,
            flags,
            surface,
            null,
            handler
        )
    }

    private fun onReady(uri: Uri?) {
        if (uri != null) {
            FinchCore.implementation.currentActivity.let { currentActivity ->
                if (currentActivity == null || !currentActivity.lifecycle.currentState.isAtLeast(
                        Lifecycle.State.RESUMED
                    )
                ) {
                    showGalleryNotification()
                } else {
                    MediaPreviewDialogFragment.show(
                        currentActivity.supportFragmentManager,
                        uri.path.orEmpty().replace(Regex("(/.*/)"), "")
                    )
                }
            }
        }
        cleanUp()
        FinchCore.implementation.onScreenCaptureReady?.invoke(uri)
        stopForeground(true)
        stopSelf()
    }

    private fun showGalleryNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(
            GALLERY_NOTIFICATION_ID,
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSound(null)
                .setSmallIcon(R.drawable.finch_ic_recording)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setDefaults(Notification.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentTitle(text(R.string.finch_capture_notification_ready_title))
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .apply {
                    setContentIntent(
                        PendingIntent.getActivity(
                            this@ScreenCaptureService,
                            0,
                            Intent(this@ScreenCaptureService, GalleryActivity::class.java),
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            } else {
                                PendingIntent.FLAG_UPDATE_CURRENT
                            }
                        )
                    )
                    setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText(text(R.string.finch_capture_notification_ready_content))
                    )
                }
                .build())
    }

    companion object {

        private const val CHANNEL_ID = "channel_finch_screen_capture"
        private const val RECORDING_NOTIFICATION_ID = 2657
        private const val GALLERY_NOTIFICATION_ID = 2656
        private const val SCREENSHOT_DELAY = 300L
        private const val SCREENSHOT_TIMEOUT = 2000L
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_INTENT = "resultIntent"
        private const val EXTRA_IS_FOR_VIDEO = "isForVideo"
        private const val EXTRA_FILE_NAME = "fileName"
        private const val ACTION_DONE = "done"

        fun getStartIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            isForVideo: Boolean,
            fileName: String
        ) = Intent(context, ScreenCaptureService::class.java)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_INTENT, data)
            .putExtra(EXTRA_IS_FOR_VIDEO, isForVideo)
            .putExtra(EXTRA_FILE_NAME, fileName)
    }
}
