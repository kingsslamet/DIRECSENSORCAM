package com.indra.directsensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Rational
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isFlipOn = true
    private var isEisOn = true
    private var isLscOn = true

    private val toneNames = listOf("NETRAL", "NATURE GREEN", "FILM WARM", "TEAL ORANGE")
    private var toneIndex = 0
    private var toneStrength = 0.70f

    private var currentIso = 200
    private var currentShutterDiv = 60
    private var currentKelvin = 5200

    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        textureView = findViewById(R.id.textureView)

        setupUi()

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                applyFlipTransform(w, h)
                checkPermissionsAndOpen()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                applyFlipTransform(w, h)
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun applyFlipTransform(width: Int, height: Int) {
        val matrix = Matrix()
        if (isFlipOn) {
            matrix.postScale(-1f, -1f, width / 2f, height / 2f)
        }
        textureView.setTransform(matrix)
    }

    private fun setupUi() {
        val btnFlip = findViewById<Button>(R.id.btnFlip)
        val btnEis = findViewById<Button>(R.id.btnEis)
        val btnLsc = findViewById<Button>(R.id.btnLsc)
        val btnTone = findViewById<Button>(R.id.btnTone)
        val btnRecord = findViewById<Button>(R.id.btnRecord)

        btnFlip.setOnClickListener {
            isFlipOn = !isFlipOn
            btnFlip.text = if (isFlipOn) "FLIP: ON" else "FLIP: OFF"
            applyFlipTransform(textureView.width, textureView.height)
        }

        btnEis.setOnClickListener {
            isEisOn = !isEisOn
            btnEis.text = if (isEisOn) "EIS: ON" else "EIS: OFF"
            updateCamera()
        }

        btnLsc.setOnClickListener {
            isLscOn = !isLscOn
            btnLsc.text = if (isLscOn) "LSC FIX: ON" else "LSC FIX: OFF"
            updateCamera()
        }

        btnTone.setOnClickListener {
            toneIndex = (toneIndex + 1) % toneNames.size
            btnTone.text = "TONE: ${toneNames[toneIndex]}"
            updateCamera()
        }

        val txtStrength = findViewById<TextView>(R.id.txtStrength)
        findViewById<SeekBar>(R.id.seekStrength).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                toneStrength = p / 100f
                txtStrength.text = "Kuat Tone: $p%"
                updateCamera()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val txtIso = findViewById<TextView>(R.id.txtIso)
        findViewById<SeekBar>(R.id.seekIso).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                currentIso = p
                txtIso.text = "ISO: $p"
                updateCamera()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val txtShutter = findViewById<TextView>(R.id.txtShutter)
        findViewById<SeekBar>(R.id.seekShutter).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                currentShutterDiv = p
                txtShutter.text = "Shutter: 1/${p}s"
                updateCamera()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val txtWb = findViewById<TextView>(R.id.txtWb)
        findViewById<SeekBar>(R.id.seekWb).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                currentKelvin = p
                txtWb.text = "WB: ${p}K"
                updateCamera()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnRecord.setOnClickListener {
            if (!isRecording) startRecording(btnRecord) else stopRecording(btnRecord)
        }
    }

    private fun checkPermissionsAndOpen() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (perms.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            openBackCamera()
        } else {
            ActivityCompat.requestPermissions(this, perms, 100)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openBackCamera() {
        val camId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"

        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreview()
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) {
                Toast.makeText(this@MainActivity, "Camera Error: $error", Toast.LENGTH_SHORT).show()
            }
        }, backgroundHandler)
    }

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(st)

        previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
        }
        applyIspSettings(previewBuilder!!)

        camera.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                session.setRepeatingRequest(previewBuilder!!.build(), null, backgroundHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    private fun applyIspSettings(builder: CaptureRequest.Builder) {
        // 1. Matikan Autofokus (wajib untuk lensa manual / direct sensor)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)

        // 2. Stabilizer Hardware (EIS)
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (isEisOn) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )

        // 3. Lens Shading Correction (Penghilang vignette & pinggiran ungu/hijau)
        builder.set(
            CaptureRequest.SHADING_MODE,
            if (isLscOn) CaptureRequest.SHADING_MODE_HIGH_QUALITY
            else CaptureRequest.SHADING_MODE_OFF
        )

        // 4. Manual Exposure (ISO & Shutter Speed)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 1_000_000_000L / currentShutterDiv)

        // 5. White Balance & Color Matrix Presets (Netral + Tune Warna via Hardware ISP)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, buildColorGains())
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, buildColorMatrix())
    }

    private fun buildColorGains(): RggbChannelVector {
        val kFactor = (currentKelvin - 2500f) / 5500f
        var r = 1.05f + (kFactor * 1.35f)
        var g = 1.0f
        var b = 2.35f - (kFactor * 1.25f)

        // Penyesuaian gain sesuai preset warna & slider kekuatan
        when (toneIndex) {
            1 -> { g += 0.22f * toneStrength; r -= 0.08f * toneStrength } // Nature Green
            2 -> { r += 0.25f * toneStrength; b -= 0.20f * toneStrength } // Film Warm
            3 -> { b += 0.20f * toneStrength; r += 0.15f * toneStrength } // Teal & Orange
        }
        return RggbChannelVector(r.coerceAtLeast(0.5f), g, g, b.coerceAtLeast(0.5f))
    }

    private fun buildColorMatrix(): ColorSpaceTransform {
        // Matriks 3x3 dasar (Identitas Netral) dengan campuran saturasi/kontras ringan
        val s = toneStrength
        val elements = when (toneIndex) {
            1 -> intArrayOf( // Nature Green (daun lebih segar, merah diredam)
                90, 100, 10, 100, 0, 100,
                -5, 100, 112, 100, -5, 100,
                0, 100, 5, 100, 95, 100
            )
            2 -> intArrayOf( // Film Warm
                110, 100, -5, 100, -5, 100,
                5, 100, 100, 100, -5, 100,
                -8, 100, 0, 100, 92, 100
            )
            3 -> intArrayOf( // Teal & Orange
                115, 100, -10, 100, -5, 100,
                -5, 100, 102, 100, 5, 100,
                -10, 100, 10, 100, 110, 100
            )
            else -> intArrayOf( // Netral (Natural flat untuk grading)
                95, 100, 5, 100, 0, 100,
                0, 100, 100, 100, 0, 100,
                0, 100, 5, 100, 95, 100
            )
        }
        val rationals = Array(9) { i ->
            val target = elements[i * 2]
            val neutral = if (i % 4 == 0) 100 else 0
            val blended = (neutral + (target - neutral) * s).toInt()
            Rational(blended, 100)
        }
        return ColorSpaceTransform(rationals)
    }

    private fun updateCamera() {
        val b = previewBuilder ?: return
        val s = captureSession ?: return
        applyIspSettings(b)
        try { s.setRepeatingRequest(b.build(), null, backgroundHandler) } catch (_: Exception) {}
    }

    private fun startRecording(btn: Button) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "DirectSensor_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DirectSensor")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
        val pfd = contentResolver.openFileDescriptor(uri, "w") ?: return

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd.fileDescriptor)
            setVideoEncodingBitRate(30_000_000)
            setVideoFrameRate(30)
            setVideoSize(1920, 1080)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }

        val camera = cameraDevice ?: return
        val previewSurface = Surface(textureView.surfaceTexture)
        val recSurface = mediaRecorder!!.surface

        previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(recSurface)
        }
        applyIspSettings(previewBuilder!!)

        camera.createCaptureSession(listOf(previewSurface, recSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                session.setRepeatingRequest(previewBuilder!!.build(), null, backgroundHandler)
                mediaRecorder?.start()
                isRecording = true
                runOnUiThread { btn.text = "STOP" }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    private fun stopRecording(btn: Button) {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        isRecording = false
        btn.text = "REC"
        startPreview()
        Toast.makeText(this, "Video tersimpan di Movies/DirectSensor", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        backgroundThread = HandlerThread("CamBg").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        if (textureView.isAvailable) checkPermissionsAndOpen()
    }

    override fun onPause() {
        cameraDevice?.close()
        backgroundThread?.quitSafely()
        super.onPause()
    }
}
