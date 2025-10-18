package com.example.rgb_camera2api2

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.max

// --- 自定义View用于绘制曲线图 ---
class EcgView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val redPaint = Paint().apply { color = Color.RED; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val greenPaint = Paint().apply { color = Color.GREEN; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val bluePaint = Paint().apply { color = Color.BLUE; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val gridPaint = Paint().apply { color = Color.argb(100, 80, 80, 80); strokeWidth = 1f }
    private val redPath = Path()
    private val greenPath = Path()
    private val bluePath = Path()
    private val maxDataPoints = 30 * 10
    private val redData = ArrayDeque<Int>(maxDataPoints)
    private val greenData = ArrayDeque<Int>(maxDataPoints)
    private val blueData = ArrayDeque<Int>(maxDataPoints)

    fun addDataPoint(r: Int, g: Int, b: Int) {
        if (redData.size >= maxDataPoints) redData.removeFirst()
        redData.addLast(r.coerceAtLeast(0))
        if (greenData.size >= maxDataPoints) greenData.removeFirst()
        greenData.addLast(g.coerceAtLeast(0))
        if (blueData.size >= maxDataPoints) blueData.removeFirst()
        blueData.addLast(b.coerceAtLeast(0))
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (i in 1..4) {
            val y = height * (i / 5f)
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }
        val maxR = redData.maxOrNull() ?: 0
        val maxG = greenData.maxOrNull() ?: 0
        val maxB = blueData.maxOrNull() ?: 0
        val currentMax = maxOf(maxR, maxG, maxB).toFloat()
        val yMax = currentMax.coerceAtLeast(255f)
        drawPath(canvas, redPath, redData, redPaint, yMax)
        drawPath(canvas, greenPath, greenData, greenPaint, yMax)
        drawPath(canvas, bluePath, blueData, bluePaint, yMax)
    }

    private fun drawPath(canvas: Canvas, path: Path, data: ArrayDeque<Int>, paint: Paint, yMax: Float) {
        path.reset()
        val stepX = width.toFloat() / (maxDataPoints - 1).coerceAtLeast(1)
        val scaleY = if (yMax > 0) height / yMax else 0f
        data.forEachIndexed { index, value ->
            val x = index * stepX
            val y = height - (value * scaleY)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}


class MainActivity : AppCompatActivity() {

    // --- UI 控件 ---
    private lateinit var spo2TextView: TextView
    private lateinit var textureView: TextureView
    private lateinit var recordButton: Button
    private lateinit var timerTextView: TextView
    private lateinit var redGainInput: EditText
    private lateinit var greenGainInput: EditText
    private lateinit var blueGainInput: EditText
    private lateinit var noGainButton: Button
    private lateinit var resetGainButton: Button
    private lateinit var countdownTextView: TextView
    private lateinit var ecgView: EcgView
    private lateinit var flashButton: Button // 新增


    // --- 相机相关变量 ---
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var imageReader: ImageReader? = null

    // --- 线程管理 ---
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    // --- 状态变量 ---
    private var isRecording = false
    private var isFlashlightOn = false // 新增
    private var mediaRecorder: MediaRecorder? = null
    private val videoSize = Size(176, 144)
    private var videoUri: Uri? = null

    // --- 模型相关 ---
    private var module: Module? = null
    private val signalBuffer = ArrayDeque<FloatArray>()
    private val MODEL_INPUT_LENGTH = 90


    // --- 计时器相关变量 ---
    private val timerHandler = Handler(Looper.getMainLooper())
    private var startTime = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val millis = System.currentTimeMillis() - startTime
            val seconds = (millis / 1000).toInt()
            val minutes = seconds / 60
            val displaySeconds = seconds % 60
            timerTextView.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, displaySeconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

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

        val toolbar = findViewById<MaterialToolbar>(R.id.top_toolbar)
        setSupportActionBar(toolbar)

        spo2TextView = findViewById(R.id.spo2_text_view)
        textureView = findViewById(R.id.texture_view)
        recordButton = findViewById(R.id.record_button)
        timerTextView = findViewById(R.id.timer_text)
        redGainInput = findViewById(R.id.red_gain_input)
        greenGainInput = findViewById(R.id.green_gain_input)
        blueGainInput = findViewById(R.id.blue_gain_input)
        noGainButton = findViewById(R.id.no_gain_button)
        resetGainButton = findViewById(R.id.reset_gain_button)
        countdownTextView = findViewById(R.id.countdown_text)
        ecgView = findViewById(R.id.ecg_view)
        flashButton = findViewById(R.id.flash_button)

        ecgView.visibility = View.VISIBLE


        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecordingVideo()
            } else {
                startRecordingVideo()
            }
        }

        // --- 新增：闪光灯按钮点击事件 ---
        flashButton.setOnClickListener {
            if (!isRecording) {
                isFlashlightOn = !isFlashlightOn
                startPreview() // 重新启动预览以应用闪光灯状态
            }
        }


        noGainButton.setOnClickListener {
            redGainInput.setText("1.0")
            greenGainInput.setText("1.0")
            blueGainInput.setText("1.0")
        }

        resetGainButton.setOnClickListener {
            redGainInput.setText("1.0")
            greenGainInput.setText("3.0")
            blueGainInput.setText("18.0")
        }


        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        }
        redGainInput.addTextChangedListener(textWatcher)
        greenGainInput.addTextChangedListener(textWatcher)
        blueGainInput.addTextChangedListener(textWatcher)

        try {
            module = LiteModuleLoader.load(assetFilePath(this, "oximeter_model.ptl"))
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model file", e)
            Toast.makeText(this, "无法加载模型: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Throws(IOException::class)
    fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }
        context.assets.open(assetName).use { `is` ->
            FileOutputStream(file).use { os ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (`is`.read(buffer).also { read = it } != -1) {
                    os.write(buffer, 0, read)
                }
                os.flush()
            }
            return file.absolutePath
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_app_bar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_manual -> {
                showManualDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
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
                addTarget(imageReader!!.surface)
                applyColorCorrection(this)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))

                val characteristics = cameraManager.getCameraCharacteristics(cameraId!!)
                val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                if (flashAvailable) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    applyManualSensorSettings(this)
                }
            }

            cameraDevice!!.createCaptureSession(listOf(previewSurface, recordSurface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                            runOnUiThread {
                                recordButton.isEnabled = false
                                object : CountDownTimer(2100, 1000) {
                                    override fun onTick(millisUntilFinished: Long) {
                                        countdownTextView.visibility = View.VISIBLE
                                        countdownTextView.text = "${(millisUntilFinished / 1000)}"
                                    }

                                    override fun onFinish() {
                                        countdownTextView.visibility = View.GONE
                                        try {
                                            mediaRecorder?.start()
                                            isRecording = true
                                            runOnUiThread {
                                                recordButton.text = "停止"
                                                recordButton.isEnabled = true
                                                timerTextView.visibility = View.VISIBLE
                                                setInputsEnabled(false)
                                                startTime = System.currentTimeMillis()
                                                timerHandler.post(timerRunnable)
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "MediaRecorder start failed after countdown", e)
                                        }
                                    }
                                }.start()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "设置录制会话失败", e)
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
        if (!isRecording) return
        isRecording = false

        timerHandler.removeCallbacks(timerRunnable)
        runOnUiThread {
            timerTextView.visibility = View.GONE
            timerTextView.text = "00:00"
            setInputsEnabled(true)
        }

        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            mediaRecorder?.stop()
            mediaRecorder?.reset()
        } catch (e: Exception) {
            Log.e(TAG, "停止录制器时出错", e)
        }

        isFlashlightOn = false // 录制结束后重置闪光灯状态

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
                addTarget(imageReader!!.surface)
                applyColorCorrection(this)

                // --- 修改：根据 isFlashlightOn 状态设置闪光灯 ---
                val characteristics = cameraManager.getCameraCharacteristics(cameraId!!)
                if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    if (isFlashlightOn) {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    } else {
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                }

                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            }

            cameraDevice!!.createCaptureSession(listOf(previewSurface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                            runOnUiThread { recordButton.text = "录制\nRecord" }
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

            imageReader = ImageReader.newInstance(videoSize.width, videoSize.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }

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

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener

        try {
            val planes = image.planes
            val yBuffer = planes[0].buffer.asReadOnlyBuffer()
            val uBuffer = planes[1].buffer.asReadOnlyBuffer()
            val vBuffer = planes[2].buffer.asReadOnlyBuffer()
            var ySum = 0L; var uSum = 0L; var vSum = 0L
            yBuffer.rewind(); uBuffer.rewind(); vBuffer.rewind()
            for (i in 0 until yBuffer.remaining()) ySum += yBuffer.get().toInt() and 0xFF
            for (i in 0 until uBuffer.remaining()) uSum += uBuffer.get().toInt() and 0xFF
            for (i in 0 until vBuffer.remaining()) vSum += vBuffer.get().toInt() and 0xFF

            val yAvg = (ySum / max(1, yBuffer.capacity())).toInt()
            val uAvg = (uSum / max(1, uBuffer.capacity())).toInt()
            val vAvg = (vSum / max(1, vBuffer.capacity())).toInt()

            val r = (yAvg + 1.370705f * (vAvg - 128))
            val g = (yAvg - 0.698001f * (vAvg - 128) - 0.337633f * (uAvg - 128))
            val b = (yAvg + 1.732446f * (uAvg - 128))

            signalBuffer.addLast(floatArrayOf(r, g, b))
            if (signalBuffer.size > MODEL_INPUT_LENGTH) {
                signalBuffer.removeFirst()
            }

            val redGain = redGainInput.text.toString().toFloatOrNull() ?: 1.0f
            val greenGain = greenGainInput.text.toString().toFloatOrNull() ?: 3.0f
            val blueGain = blueGainInput.text.toString().toFloatOrNull() ?: 18.0f
            runOnUiThread {
                ecgView.addDataPoint((r * redGain).toInt(), (g * greenGain).toInt(), (b * blueGain).toInt())
            }

            if (signalBuffer.size == MODEL_INPUT_LENGTH) {
                val inputFloatArray = FloatArray(3 * MODEL_INPUT_LENGTH)
                signalBuffer.forEachIndexed { index, rgb ->
                    inputFloatArray[index] = rgb[0]
                    inputFloatArray[MODEL_INPUT_LENGTH + index] = rgb[1]
                    inputFloatArray[2 * MODEL_INPUT_LENGTH + index] = rgb[2]
                }
                val inputTensor = Tensor.fromBlob(inputFloatArray, longArrayOf(1, 3, MODEL_INPUT_LENGTH.toLong()))

                val outputTensor = module?.forward(IValue.from(inputTensor))?.toTensor()
                val scores = outputTensor?.dataAsFloatArray

                if (scores != null && scores.isNotEmpty()) {
                    val rawSpo2Value = scores[0]
                    val adjustedSpo2 = rawSpo2Value / 2.7f
                    val clampedSpo2 = adjustedSpo2.coerceIn(0.0f, 100.0f)
                    runOnUiThread {
                        spo2TextView.text = "SpO2: %.1f%%".format(clampedSpo2)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "模型推理或数据处理失败", e)
        } finally {
            image.close()
        }
    }

    private fun applyColorCorrection(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)

        val redGain = redGainInput.text.toString().toFloatOrNull() ?: 1.0f
        val greenGain = greenGainInput.text.toString().toFloatOrNull() ?: 3.0f
        val blueGain = blueGainInput.text.toString().toFloatOrNull() ?: 18.0f

        val colorGains = RggbChannelVector(redGain, greenGain, greenGain, blueGain)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, colorGains)
    }

    private fun applyManualSensorSettings(builder: CaptureRequest.Builder) {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId!!)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        if (capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) != true) {
            return
        }

        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val isoValue = 2000
        if (isoRange?.contains(isoValue) == true) {
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
        }

        val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val exposureTimeValue = 33000000L
        if (exposureTimeRange?.contains(exposureTimeValue) == true) {
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeValue)
        }
    }

    private fun setInputsEnabled(enabled: Boolean) {
        redGainInput.isEnabled = enabled
        greenGainInput.isEnabled = enabled
        blueGainInput.isEnabled = enabled
        noGainButton.isEnabled = enabled
        resetGainButton.isEnabled = enabled
        flashButton.isEnabled = enabled // 新增
    }

    private fun showManualDialog() {
        val manualText = """
            1. RGB 增益调节 (RGB Gain Adjustment):
            - 在顶部的 R, G, B 输入框中输入数值，可以实时调整预览和录制视频的色彩增益。
            - Enter values in the R, G, B input fields at the top to adjust the color gain for the preview and recorded video in real-time.
            - 无增益 (No Gain): 将所有增益设为1.0，显示接近原始的画面。
            - Sets all gains to 1.0 to show a near-original image.
            - 重置增益 (Reset Gain): 恢复为默认的特殊色彩效果 (R:1, G:3, B:18)。
            - Resets to the default special color effect (R:1, G:3, B:18).

            2. 录制视频 (Recording Video):
            - 点击“录制”按钮后，将有2秒倒计时。倒计时结束后正式开始录制。
            - After tapping the "Record" button, there will be a 2-second countdown before recording officially begins.
            - 录制期间，闪光灯会开启（如果设备支持）。
            - The flash will turn on during recording (if supported by the device).
            - 录制好的视频会自动保存到手机相册的 "Movies" 文件夹中。
            - The recorded video is automatically saved to the "Movies" folder in your phone's gallery.

            3. 闪光灯与ISO (Flash & ISO):
            - 为了保证画面亮度，开启闪光灯时，相机必须使用自动ISO。
            - To ensure brightness, the camera must use Auto ISO when the flash is on.
            - 如果设备不支持闪光灯，程序会尝试将ISO固定在2000。
            - If the device does not support flash, the app will attempt to fix the ISO at 2000.
            
            4. 服务条款 (Provisions):
            - 用户在阅读本指南（无论全文或部分）后即视为自愿加入绝地潜兵。（为消除歧义：用户在打开应用15米范围内停留1秒即视为已阅读。）
            - Users are deemed to have voluntarily joined Helldivers after reading this guide, in whole or in part. (For the purpose of the elimination of doubt, the Users spending 1 second within 15 meters of the opened APP shall be interpreted as the contract being read.)
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("使用手册 / User Manual")
            .setMessage(manualText)
            .setPositiveButton("好的 / OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
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
        imageReader?.close()
        imageReader = null
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

