package com.example.acoustic_camera.camera

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

class CameraPreviewView(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onStatus: (String) -> Unit,
) : FrameLayout(context) {
    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private var provider: ProcessCameraProvider? = null
    private var startRequested = false
    private var closed = false

    init {
        addView(
            previewView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun resumeCamera() {
        if (closed || startRequested) return
        startRequested = true
        onStatus("正在启动相机与姿态传感器")
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (closed || !startRequested) return@addListener
                runCatching {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                    )
                    previewView.visibility = View.VISIBLE
                    onStatus("相机就绪 · IMU 姿态扫描")
                }.onFailure {
                    startRequested = false
                    onStatus("相机启动失败：${it.javaClass.simpleName}")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun pauseCamera() {
        startRequested = false
    }

    fun closeCamera() {
        closed = true
        startRequested = false
        provider?.unbindAll()
        provider = null
    }
}
