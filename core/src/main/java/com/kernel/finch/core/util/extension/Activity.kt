package com.kernel.finch.core.util.extension

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.TypedValue
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import com.kernel.finch.FinchCore
import com.kernel.finch.core.OverlayFragment
import com.kernel.finch.utils.extensions.drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileWriter
import java.io.IOException

private val excludedPackageNames = listOf(
    "com.kernel.finch.core.presentation.detail.networklog.ContainerActivity",
    "com.kernel.finch.core.presentation.detail.networklog.NetworkLogActivity",
    "com.kernel.finch.core.presentation.gallery.GalleryActivity",
    "com.kernel.finch.implementation.DebugMenuActivity"
)

internal val Activity.supportsDebugMenu
    get() = this is FragmentActivity
        && excludedPackageNames.none { componentName.className.startsWith(it) }
        && FinchCore.implementation.configuration.excludedPackageNames.none {
        componentName.className.startsWith(
            it
        )
    }

internal fun Activity.shareFile(uri: Uri, fileType: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = fileType
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_STREAM, uri)
    }, null))
}

internal fun Activity.shareFiles(uris: List<Uri>) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }, null))
}

internal suspend fun Activity.createAndShareFile(fileName: String, content: String) =
    withContext(Dispatchers.IO) {
        val file = createLogFile(fileName)
        try {
            FileWriter(file).run {
                write(content)
                flush()
                close()
            }
            shareFile(getUriForFile(file), "text/plain")
        } catch (_: IOException) {
        }
    }

internal fun Activity.takeScreenshotWithMediaProjectionManager(fileName: String) {
    (FinchCore.implementation.uiManager.findOverlayFragment(this as? FragmentActivity?) as? OverlayFragment?).let { overlayFragment ->
        overlayFragment?.startCapture(false, fileName)
            ?: FinchCore.implementation.onScreenCaptureReady?.invoke(null)
    }
}

internal fun Activity.recordScreenWithMediaProjectionManager(fileName: String) {
    (FinchCore.implementation.uiManager.findOverlayFragment(this as? FragmentActivity?) as? OverlayFragment?).let { overlayFragment ->
        overlayFragment?.startCapture(true, fileName)
            ?: FinchCore.implementation.onScreenCaptureReady?.invoke(null)
    }
}

internal fun Activity.takeScreenshotWithDrawingCache(fileName: String) {
    val rootView = findRootViewGroup()
    val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val bgDrawable = rootView.background
    if (bgDrawable != null) {
        bgDrawable.draw(canvas)
    } else {
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)
        if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            canvas.drawColor(typedValue.data)
        } else {
            drawable(typedValue.resourceId)?.draw(canvas)
        }
    }
    rootView.draw(canvas)
    GlobalScope.launch(Dispatchers.IO) {
        (createScreenshotFromBitmap(bitmap, fileName))?.let { uri ->
            launch(Dispatchers.Main) {
                FinchCore.implementation.onScreenCaptureReady?.invoke(uri)
            }
        }
    }
}

private fun Activity.findRootViewGroup(): ViewGroup =
    findViewById(android.R.id.content) ?: window.decorView.findViewById(android.R.id.content)
