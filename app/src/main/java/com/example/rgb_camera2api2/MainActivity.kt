package com.example.rgb_camera2api2

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // --- UI 控件 ---
    private lateinit var textureView: TextureView
    private lateinit var recordButton: Button

    // --- 相机相关变量 ---
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraId: String? = null

    // --- 线程管理 ---
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    // --- 录制相关 ---
    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private val videoSize = Size(176, 144)
    private var videoUri: Uri? = null

    // --- 常量 ---
    private val TAG = "Camera2App"

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                openCamera()
            } else {
                Toast.makeText(this, "需要所有权限才能使用本应用", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.texture_view)
        recordButton = findViewById(R.id.record_button)

        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecordingVideo()
            } else {
                startRecordingVideo()
            }
        }
    }

    private fun startRecordingVideo() {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closePreviewSession()
            setupMediaRecorder()

            val texture = textureView.surfaceTexture!!.apply {
                setDefaultBufferSize(videoSize.width, videoSize.height)
            }
            val previewSurface = Surface(texture)
            val recordSurface = mediaRecorder!!.surface

            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recordSurface)
                applyColorCorrection(this)
            }

            cameraDevice!!.createCaptureSession(listOf(previewSurface, recordSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                            mediaRecorder?.start()
                            isRecording = true
                            runOnUiThread { recordButton.text = "停止" }
                        } catch (e: Exception) {
                            Log.e(TAG, "开始录制失败", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "录制会话配置失败")
                    }
                }, backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "启动录制流程失败", e)
        }
    }

    private fun stopRecordingVideo() {
        isRecording = false
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
        } catch (e: Exception) {
            Log.e(TAG, "停止录制器时出错", e)
        }

        saveVideoToGallery()
        startPreview()
    }

    private fun startPreview() {
        if (cameraDevice == null || !textureView.isAvailable) return
        try {
            closePreviewSession()
            val texture = textureView.surfaceTexture!!.apply {
                setDefaultBufferSize(videoSize.width, videoSize.height)
            }
            val previewSurface = Surface(texture)
            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                applyColorCorrection(this)
            }

            cameraDevice!!.createCaptureSession(listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                            runOnUiThread { recordButton.text = "录制" }
                        } catch (e: Exception) {
                            Log.e(TAG, "启动预览失败", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "预览会话配置失败")
                    }
                }, backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建预览会话失败", e)
        }
    }

    private fun setupMediaRecorder() {
        val fileName = "VIDEO_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        videoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (videoUri == null) {
            Log.e(TAG, "创建 MediaStore URI 失败")
            throw IOException("创建 MediaStore URI 失败")
        }

        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(contentResolver.openFileDescriptor(videoUri!!, "w")!!.fileDescriptor)
            setVideoEncodingBitRate(1000000)
            setVideoFrameRate(30)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
    }

    private fun saveVideoToGallery() {
        videoUri?.let { uri ->
            val values = ContentValues().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
            }
            contentResolver.update(uri, values, null, null)
            runOnUiThread { Toast.makeText(this, "视频已保存到相册", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun openCamera() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val compatibleCameraId = cameraManager.cameraIdList.find {
                val characteristics = cameraManager.getCameraCharacteristics(it)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                facing == CameraCharacteristics.LENS_FACING_BACK && map?.getOutputSizes(MediaRecorder::class.java)?.contains(videoSize) == true
            }

            if (compatibleCameraId == null) {
                Toast.makeText(this, "未找到兼容的摄像头", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            cameraId = compatibleCameraId

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            cameraManager.openCamera(cameraId!!, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "打开相机失败", e)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview()
        }
        override fun onDisconnected(camera: CameraDevice) {
            closeCamera()
        }
        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "相机设备错误: $error")
            closeCamera()
        }
    }

    private fun applyColorCorrection(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        val colorGains = RggbChannelVector(1.0f, 3.0f, 3.0f, 18.0f)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, colorGains)
    }

    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    private fun closeCamera() {
        closePreviewSession()
        cameraDevice?.close()
        cameraDevice = null
        mediaRecorder?.release()
        mediaRecorder = null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "停止后台线程失败", e)
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (permissionsToRequest.all { ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED }) {
                openCamera()
            } else {
                activityResultLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            surfaceTextureListener.onSurfaceTextureAvailable(textureView.surfaceTexture!!, textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }
}

