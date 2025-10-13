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
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private var cameraId: String? = null

    // --- 线程管理 ---
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    // --- 录制相关 ---
    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private val videoSize = Size(176, 144)
    private lateinit var videoFile: File // 用于旧版安卓系统
    private var videoUri: Uri? = null // 用于新版安卓系统 (API 29+)

    // --- 常量 ---
    private val TAG = "Camera2App"

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val deniedPermissions = permissions.entries.filter { !it.value }.map { it.key }

            if (deniedPermissions.isEmpty()) {
                setupAndOpenCamera()
            } else {
                val message = "以下权限被拒绝，应用无法运行:\n" +
                        deniedPermissions.joinToString("\n") { permission ->
                            when (permission) {
                                Manifest.permission.CAMERA -> "- 相机"
                                Manifest.permission.RECORD_AUDIO -> "- 麦克风"
                                Manifest.permission.WRITE_EXTERNAL_STORAGE -> "- 存储空间"
                                else -> "- 未知权限"
                            }
                        }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
                stopRecording()
            } else {
                startRecording()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (permissionsToRequest.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            setupAndOpenCamera()
        } else {
            activityResultLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startRecording() {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closeCaptureSession()
            if (!setupMediaRecorder()) {
                runOnUiThread { Toast.makeText(this, "设置录制器失败", Toast.LENGTH_LONG).show() }
                return
            }

            val texture = textureView.surfaceTexture!!
            texture.setDefaultBufferSize(videoSize.width, videoSize.height)
            val previewSurface = Surface(texture)
            val recordSurface = mediaRecorder!!.surface

            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequestBuilder.addTarget(previewSurface)
            captureRequestBuilder.addTarget(recordSurface)

            applyColorCorrection(captureRequestBuilder)

            cameraDevice?.createCaptureSession(
                listOf(previewSurface, recordSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                            mediaRecorder?.start()
                            isRecording = true
                            runOnUiThread { recordButton.text = "停止" }
                        } catch (e: Exception) {
                            val errorMsg = "开始录制请求失败"
                            Log.e(TAG, errorMsg, e)
                            runOnUiThread { Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show() }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        val errorMsg = "录制会话配置失败"
                        Log.e(TAG, errorMsg)
                        runOnUiThread { Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show() }
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            val errorMsg = "开始录制失败"
            Log.e(TAG, errorMsg, e)
            runOnUiThread { Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show() }
        }
    }

    private fun stopRecording() {
        isRecording = false
        runOnUiThread { recordButton.text = "录制" }
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (e: Exception) {
            Log.e(TAG, "停止录制时出错", e)
        }

        val savedMessage: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // --- 关键修改：在安卓10及以上版本，将视频状态从“待写入”更新为“已完成” ---
            videoUri?.let { uri ->
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
            }
            savedMessage = "视频已保存到相册"
        } else {
            MediaScannerConnection.scanFile(this, arrayOf(videoFile.absolutePath), null, null)
            savedMessage = "视频已保存到: ${videoFile.absolutePath}"
        }
        runOnUiThread { Toast.makeText(this, savedMessage, Toast.LENGTH_LONG).show() }

        if (cameraDevice != null) {
            createPreviewSession()
        }
    }

    private fun setupMediaRecorder(): Boolean {
        mediaRecorder = MediaRecorder()
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // --- 关键修改：在安卓10及以上版本，先创建一个“待写入”的条目 ---
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, createVideoFileName())
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    put(MediaStore.Video.Media.IS_PENDING, 1) // 标记为待写入
                }
                videoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (videoUri == null) {
                    Log.e(TAG, "创建 MediaStore 条目失败")
                    return false
                }
                val pfd = contentResolver.openFileDescriptor(videoUri!!, "w")
                if (pfd == null) {
                    Log.e(TAG, "获取 ParcelFileDescriptor 失败")
                    return false
                }
                setOutputFile(pfd.fileDescriptor)
            } else {
                videoFile = createVideoFileLegacy()
                setOutputFile(videoFile.absolutePath)
            }

            setVideoEncodingBitRate(1000000)
            setVideoFrameRate(30)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            try {
                prepare()
            } catch (e: IOException) {
                Log.e(TAG, "MediaRecorder prepare() 失败", e)
                return false
            }
        }
        return true
    }

    private fun createVideoFileName(): String {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "VIDEO_${timeStamp}.mp4"
    }

    private fun createVideoFileLegacy(): File {
        val publicMoviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (!publicMoviesDir.exists()) {
            publicMoviesDir.mkdirs()
        }
        return File(publicMoviesDir, createVideoFileName())
    }


    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            checkAndRequestPermissions()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun setupAndOpenCamera() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            var foundCompatibleCamera = false
            for (camId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(camId)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) continue

                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                if (map.getOutputSizes(MediaRecorder::class.java).contains(videoSize)) {
                    cameraId = camId
                    foundCompatibleCamera = true
                    break
                }
            }

            if (!foundCompatibleCamera) {
                runOnUiThread { Toast.makeText(this, "未找到支持 176x144 分辨率的摄像头", Toast.LENGTH_LONG).show() }
                finish()
                return
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            cameraManager.openCamera(cameraId!!, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            val errorMsg = "打开相机失败"
            Log.e(TAG, errorMsg, e)
            runOnUiThread { Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show() }
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            val errorMsg = "相机设备错误: $error"
            Log.e(TAG, "CameraDevice Error: $error")
            runOnUiThread { Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show() }
            camera.close()
            cameraDevice = null
            finish()
        }
    }

    private fun createPreviewSession() {
        try {
            if (cameraDevice == null) {
                Log.e(TAG, "创建预览失败，cameraDevice为空")
                return
            }
            val surfaceTexture = textureView.surfaceTexture ?: return
            surfaceTexture.setDefaultBufferSize(videoSize.width, videoSize.height)
            val previewSurface = Surface(surfaceTexture)

            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(previewSurface)

            applyColorCorrection(captureRequestBuilder)

            cameraDevice?.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                        } catch (e: CameraAccessException) {
                            val errorMsg = "启动预览失败"
                            Log.e(TAG, errorMsg, e)
                            runOnUiThread { Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show() }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        val errorMsg = "预览会话配置失败"
                        Log.e(TAG, errorMsg)
                        runOnUiThread { Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show() }
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            val errorMsg = "创建预览会话失败"
            Log.e(TAG, errorMsg, e)
            runOnUiThread { Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show() }
        }
    }

    private fun applyColorCorrection(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        val colorGains = RggbChannelVector(1.0f, 3.0f, 3.0f, 18.0f)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, colorGains)
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "关闭相机资源时出错", e)
        }
    }

    private fun closeCaptureSession() {
        captureSession?.close()
        captureSession = null
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "停止后台线程失败", e)
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            checkAndRequestPermissions()
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

