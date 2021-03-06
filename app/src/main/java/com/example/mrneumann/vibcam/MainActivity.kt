package com.example.mrneumann.vibcam

import android.Manifest.permission.*
import android.annotation.SuppressLint
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Handler
import android.os.HandlerThread
import android.graphics.ImageFormat
import android.media.MediaRecorder
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION
import android.hardware.camera2.CameraMetadata.CONTROL_AF_TRIGGER_START
import android.hardware.camera2.CaptureRequest.CONTROL_AF_TRIGGER
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Environment
import android.support.v4.app.ActivityCompat.requestPermissions
import android.support.v4.content.ContextCompat.checkSelfPermission
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Long.signum
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity() {

    //INIT
    private val TAG = "VibCam"
    private var PERMISSION_REQUEST = 0
    private var mBackgroundHandlerThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null
    private lateinit var mCameraId: String
    private var mCameraDevice: CameraDevice? = null
    private lateinit var mMediaRecorder: MediaRecorder
    private lateinit var mPreviewSize: Size
    private lateinit var mVideoSize: Size
    private lateinit var mImageSize: Size
    private lateinit var mImageReader: ImageReader
    private lateinit var mVideoReader: ImageReader
    private var mIsRecording = false
    private lateinit var mCaptureRequestBuilder: CaptureRequest.Builder
    private var mPreviewCaptureSession: CameraCaptureSession? = null
    private lateinit var mRecordCaptureSession: CameraCaptureSession
    private lateinit var mImageFolder: File
    private lateinit var mVideoFolder: File
    private var mVideoFileName: String? = null
    private var mImageFileName: String? = null
    private val STATE_PREVIEW: Int = 0
    private val STATE_WAIT_LOCK:Int = 1
    private var mCaptureState:Int = STATE_PREVIEW
    private var mTotalRotation:Int = 0
    private val cameraOpenCloseLock = Semaphore(1)
    private var sensorOrientation = 0
    //TODO orientation
//    private var ORIENTATIONS: SparseIntArray = SparseIntArray()
//    static {
//        ORIENTATIONS.append(Surface.ROTATION_0, 0);
//        ORIENTATIONS.append(Surface.ROTATION_90, 90);
//        ORIENTATIONS.append(Surface.ROTATION_180, 180);
//        ORIENTATIONS.append(Surface.ROTATION_270, 270);
//    }
        private val mCameraDeviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice?) {
            cameraOpenCloseLock.release()

            mCameraDevice = cameraDevice
            startPreview()
        }

        override fun onDisconnected(cameraDevice: CameraDevice?) {
            cameraOpenCloseLock.release()
            cameraDevice?.close()
            mCameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice?, p1: Int) {
            cameraOpenCloseLock.release()
            cameraDevice?.close()
            mCameraDevice = null
            this@MainActivity.finish()
        }
    }
    private var mSurfaceTextureListener = object: TextureView.SurfaceTextureListener{
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            openCamera(width, height)
        }
        override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int){}

        override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {}

        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean {
            return false
        }
    }
    private var mPreviewCaptureCallback = object:CameraCaptureSession.CaptureCallback(){
        override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
            super.onCaptureCompleted(session, request, result)
            if (mCaptureState == STATE_WAIT_LOCK) {
                mCaptureState = STATE_PREVIEW
                val afState:Int = result!!.get(CaptureResult.CONTROL_AF_STATE)
                //зачем?
                if(afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED){
                    startStillCaptureRequest()
                }
            }
        }

    }
    //TODO delete
    private var mRecordCaptureCallback = object :CameraCaptureSession.CaptureCallback(){
        override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
            super.onCaptureCompleted(session, request, result)
            proc(result!!)
        }
        private fun proc(result:CaptureResult){
            if(mCaptureState == STATE_WAIT_LOCK){
                mCaptureState = STATE_PREVIEW
                val afState:Int = result.get(CaptureResult.CONTROL_AF_STATE)
                if(afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED){
                    startStillCaptureRequest()
                }
            }
        }
    }

    private val mOnVideoAvailableListener = ImageReader.OnImageAvailableListener {
        imageReader -> this@MainActivity.runOnUiThread{stabilisation(imageReader.acquireLatestImage())}
    }
    private val mOnImageAvailableListener = ImageReader.OnImageAvailableListener {
        imageReader -> mBackgroundHandler!!.post(ImageSaver(imageReader.acquireLatestImage()))
    }
    private inner class ImageSaver(private val mImage: Image): Runnable{
        override fun run() {
            val byteBuffer: ByteBuffer = mImage.planes[0].buffer
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            var fileOutputStream: FileOutputStream? = null
            try {
                fileOutputStream = FileOutputStream(mImageFileName)
                fileOutputStream.write(bytes)
            }catch (e:IOException){
                e.printStackTrace()
            }finally {
                mImage.close()

                val mediaStoreUpdateIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                mediaStoreUpdateIntent.data = Uri.fromFile(File(mImageFileName))
                sendBroadcast(mediaStoreUpdateIntent)

                if(fileOutputStream != null){
                    try {
                        fileOutputStream.close()
                    }catch (e:IOException){
                        e.printStackTrace()
                    }
                }
            }
        }
    }
    //accelerometer
    private lateinit var mSensorManager:SensorManager
    var mAccelerometerArr: FloatArray = FloatArray(3)
    private var mAccelerometer:Sensor? = null
    private val mAccelerometerListener = object: SensorEventListener {
        override fun onSensorChanged(sensorEvent: SensorEvent) {
            val mySensor = sensorEvent.sensor as Sensor
            if(mySensor.type == Sensor.TYPE_ACCELEROMETER){
                System.arraycopy(sensorEvent.values,0,mAccelerometerArr, 0, sensorEvent.values.size)
            }
        }
        override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
    }
    //gyroscope
    var mGyroscopeArr: FloatArray = FloatArray(3)
    private var mGyroscope:Sensor? = null
    private val mGyroscopeListener = object:SensorEventListener{
        override fun onSensorChanged(sensorEvent: SensorEvent) {
            val mySensor = sensorEvent.sensor as Sensor
            if(mySensor.type == Sensor.TYPE_GYROSCOPE){
                System.arraycopy(sensorEvent.values,0,mGyroscopeArr, 0, sensorEvent.values.size)
            }
        }
        override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
    }

    //Activities
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //fullscreen
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)

        //Permission
        getPermission()
        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
//        TODO uncomment!
//        mGyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
//        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
//        if(mGyroscope == null){
//            Toast.makeText(this@MainActivity, "I need gyroscope",Toast.LENGTH_SHORT).show()
//            finish()
//        }else if(mAccelerometer == null){
//            Toast.makeText(this@MainActivity, "I need accelerometer",Toast.LENGTH_SHORT).show()
//            finish()
//        }

        //buttons
        cameraImageButton.setOnClickListener{
            getPermission()
            takeShot()
        }
        cameraVideoButton.setOnClickListener{
            if(mIsRecording){
                mIsRecording = false
                cameraVideoButton.setImageResource(R.mipmap.btn_video_online)
                startPreview()

                //add file_name to storage
//                val mediaStoreUpdateIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
//                mediaStoreUpdateIntent.data = Uri.fromFile(File(mVideoFileName))
//                sendBroadcast(mediaStoreUpdateIntent)
            }else{
                //TODO set takeFoto button invisible
                getPermission()
                startRecord()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        //gyroscope and accelerometer
        //TODO uncomment!!!
//        mSensorManager.registerListener(mGyroscopeListener, mGyroscope, SENSOR_DELAY_NORMAL)
//        mSensorManager.registerListener(mAccelerometerListener, mAccelerometer, SENSOR_DELAY_NORMAL)

        createPhotoFolder()
        createVideoFolder()

        startBackgroundThread()
        if (cameraWindow.isAvailable){
            openCamera(cameraWindow.width,cameraWindow.height)
        } else {
            cameraWindow.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        mSensorManager.unregisterListener(mAccelerometerListener)
        mSensorManager.unregisterListener(mGyroscopeListener)
        stopBackgroundThread()
        super.onPause()
    }

    //Main camera
    private fun takeShot() {
        mCaptureState = STATE_WAIT_LOCK
        mCaptureRequestBuilder.set(CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_START)
        try{
            mPreviewCaptureSession?.capture(mCaptureRequestBuilder.build(), mPreviewCaptureCallback, mBackgroundHandler)
        }catch (e:CameraAccessException){
            e.printStackTrace()
        }
    }

    private fun startRecord() = try {
        stopPreview()
        createVideoFileName()

        val surfaceTexture:SurfaceTexture = cameraWindow.surfaceTexture.apply {
            setDefaultBufferSize(mPreviewSize.width,mPreviewSize.height)
        }
        val previewSurface = Surface(surfaceTexture)
        val recordSurface:Surface = mVideoReader.surface

        mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        mCaptureRequestBuilder.addTarget(previewSurface)
        mCaptureRequestBuilder.addTarget(recordSurface)

        mCameraDevice!!.createCaptureSession(
            Arrays.asList(recordSurface, previewSurface),
            object: CameraCaptureSession.StateCallback(){
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    mRecordCaptureSession = cameraCaptureSession
                    this@MainActivity.runOnUiThread{
                        mIsRecording = true
                        cameraVideoButton.setImageResource(R.mipmap.btn_video_busy)
                    }
                    try {
                        mRecordCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(),null,mBackgroundHandler)
                    }catch (e:CameraAccessException){
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession?) {
                    Log.d(TAG,"onConfigureFailed: startRecord")
                }
            }, mBackgroundHandler)
    } catch (e: CameraAccessException) {
        Log.e(TAG, e.toString())
    } catch (e: IOException) {
        Log.e(TAG, e.toString())
    }

    //create and setup
    private fun openCamera(width: Int, height: Int) {

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if(!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            for(camID in cameraManager.cameraIdList){
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(camID) as CameraCharacteristics
                //определяем основая ли камера
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) continue
                val map:StreamConfigurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?:
                    throw RuntimeException("Cannot get available preview/video sizes")

                sensorOrientation = cameraCharacteristics.get(SENSOR_ORIENTATION)
                mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                        width, height, mVideoSize)
//TODO orientation
//                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
//                    textureView.setAspectRatio(previewSize.width, previewSize.height)
//                } else {
//                    textureView.setAspectRatio(previewSize.height, previewSize.width)
//                }
//                configureTransform(width, height)

//                val deviceOrientation = windowManager.defaultDisplay.rotation
//                mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation)
//                val swapRotation = mTotalRotation === 90 || mTotalRotation === 270
//                val rotatedWidth = width
//                val rotatedHeight = height
//                if (swapRotation) {
//                    rotatedWidth = height
//                    rotatedHeight = width
//                }
//                mVideoSize = chooseOptimalSize(map.getOutputSizes(MediaRecorder::class.java), rotatedWidth, rotatedHeight)
//                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java), rotatedWidth, rotatedHeight)
//                mImageSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), rotatedWidth, rotatedHeight)
                mImageSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), width, height,mVideoSize)
                mVideoReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 10)
                mVideoReader.setOnImageAvailableListener(mOnVideoAvailableListener, mBackgroundHandler)
                mImageReader = ImageReader.newInstance(960, 720, ImageFormat.JPEG, 1)
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler)
                mCameraId = camID
                if(checkSelfPermission(this@MainActivity, CAMERA) == PERMISSION_GRANTED) {

                    cameraManager.openCamera(camID, mCameraDeviceStateCallback, null) //mBackgroundHandler
                }
                return //TODO надо ли?
            }

        } catch (e: CameraAccessException) {
            Log.e(TAG,"Cannot access the camera.")
            this.finish()
        } catch (e: NullPointerException) {
            Log.e(TAG,"NullPointerException in openCamera")

        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }

    private fun chooseVideoSize(choices: Array<Size>) = choices.firstOrNull {
        it.width == it.height * 4 / 3 && it.width <= 1080 } ?: choices[choices.size - 1]

    private fun createPhotoFolder() {
        val imageFile:File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        mImageFolder = File(imageFile, "VibCam")
        if(!mImageFolder.exists()){
            mImageFolder.mkdirs()
        }
    }

    private fun createVideoFolder() {
        val videoFile:File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        mVideoFolder = File(videoFile, "VibCam")
        if(!mVideoFolder.exists()){
            mVideoFolder.mkdirs()
        }
    }

    @SuppressLint("SimpleDateFormat")
    @Throws(IOException::class)
    private fun createImageFileName():File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "IMAGE_" + timestamp + "_"
        val imageFile = File.createTempFile(prepend, ".jpg", mImageFolder)
        mImageFileName = imageFile.absolutePath
        return imageFile
    }

    @SuppressLint("SimpleDateFormat")
    @Throws(IOException::class)
    private fun createVideoFileName(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "VIDEO_" + timestamp + "_"
        val videoFile = File.createTempFile(prepend, ".mp4", mVideoFolder)
        mVideoFileName = videoFile.absolutePath
        return videoFile //зачем?
    }

    //start
    private fun startStillCaptureRequest(){
    try{

        mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

        mCaptureRequestBuilder.addTarget(mImageReader.surface)
        mCaptureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, mTotalRotation)

        val stillCaptureCallback = object:CameraCaptureSession.CaptureCallback(){
            override fun onCaptureStarted(session: CameraCaptureSession?, request: CaptureRequest?, timestamp: Long, frameNumber: Long) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)

                try {
                    createImageFileName()
                }catch (e: IOException){
                    e.printStackTrace()
                }
            }

        }
            mPreviewCaptureSession?.capture(mCaptureRequestBuilder.build(),stillCaptureCallback,null)
    }catch (e: CameraAccessException){
        e.printStackTrace()
    }
}

    private fun startPreview(){
        val surfaceTexture: SurfaceTexture = cameraWindow.surfaceTexture
        surfaceTexture.setDefaultBufferSize(640, 480)
        val previewSurface = Surface(surfaceTexture)

        try {
            stopPreview()
            mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mCaptureRequestBuilder.addTarget(previewSurface)
            mCameraDevice!!.createCaptureSession(Arrays.asList(previewSurface,mImageReader.surface),
                    object: CameraCaptureSession.StateCallback(){
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.d(TAG, "onConfigured: startPreview")
                            mPreviewCaptureSession = session
                            try {
                                mPreviewCaptureSession?.setRepeatingRequest(mCaptureRequestBuilder.build(),null,mBackgroundHandler)
                            }catch (e: CameraAccessException){
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(p0: CameraCaptureSession?) {
                            Log.d(TAG, "onConfigureFailed: startPreview")
                        }
                    },
                    mBackgroundHandler)
        }catch (e:CameraAccessException){
            e.printStackTrace()
        }
    }

    private fun startBackgroundThread() {
        mBackgroundHandlerThread = HandlerThread("VibCam")
        mBackgroundHandlerThread!!.start()
        mBackgroundHandler = Handler(mBackgroundHandlerThread!!.looper)
    }

    //layout
//    private fun sensorToDeviceRotation(cameraCharacteristics:CameraCharacteristics, deviceOrientation:Int):Int{
//        val sensorOrienatation:Int = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
//        deviceOrientation = ORIENTATIONS.get(CameraCharacteristics.SENSOR_ORIENTATION)
//        return (sensorOrienatation + deviceOrientation + 360) %360
//    }
//    private fun chooseOptimalSize(choices: Array<out Size>, width: Int, height: Int): Size {
//        val bigEnough: ArrayList<Size> = ArrayList()
//        choices.filterTo(bigEnough) { it.height == it.width * height / width && it.width >= width && it.height >= height }
//
//        return if(bigEnough.size > 0) {
//            Collections.min(bigEnough, CompareSizeByArea())
//        } else {
//            choices[0]
//        }
//    }
    private fun chooseOptimalSize(
            choices: Array<Size>,
            width: Int,
            height: Int,
            aspectRatio: Size
    ): Size {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = choices.filter {
            it.height == it.width * h / w && it.width >= width && it.height >= height }

        // Pick the smallest of those, assuming we found any
        return if (bigEnough.isNotEmpty()) {
            Collections.min(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }
    class CompareSizesByArea : Comparator<Size> {

        // We cast here to ensure the multiplications won't overflow
        override fun compare(lhs: Size, rhs: Size) =
                signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
    }

    //Permission
    private fun getPermission(){
        val permissionToAccess = ArrayList<String>()

        if(checkSelfPermission(this, WRITE_EXTERNAL_STORAGE) != PERMISSION_GRANTED) permissionToAccess.add(WRITE_EXTERNAL_STORAGE)
        if(checkSelfPermission(this, CAMERA) != PERMISSION_GRANTED) permissionToAccess.add(CAMERA)
        if(checkSelfPermission(this, RECORD_AUDIO) != PERMISSION_GRANTED) permissionToAccess.add(RECORD_AUDIO)

        if(permissionToAccess.isNotEmpty()) {
            requestPermissions(this, permissionToAccess.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult( //TODO check
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        when (requestCode) {
            PERMISSION_REQUEST -> {
                var flag = false
                if (grantResults.isNotEmpty()) {
                    val perm = permissions.indexOf(WRITE_EXTERNAL_STORAGE)
                    if (perm >= 0) {
                        if (grantResults[perm] == PERMISSION_GRANTED) {
                            createPhotoFolder()
                            createVideoFolder()
                        }
                    }

                    for (result in grantResults) {
                        if (result != PERMISSION_GRANTED) {
                            Toast.makeText(this, "I can't work without it!", Toast.LENGTH_LONG).show()
                            flag = true
                            break
                        }
                    }
                    if(flag) getPermission()
                }
            }
        }
    }

    //close
    private fun closeCamera(){
        try{
            cameraOpenCloseLock.acquire()
            stopPreview()
            mCameraDevice!!.close()
            mCameraDevice = null
        }catch (e:InterruptedException){
            throw RuntimeException("Can't close camera", e)
        }finally {
            cameraOpenCloseLock.acquire()
        }
    }
    private fun stopPreview() {
        mPreviewCaptureSession?.close()
        mPreviewCaptureSession = null
    }
    private fun stopBackgroundThread(){
        mBackgroundHandlerThread?.quitSafely()
        try {
            mBackgroundHandlerThread!!.join()
            mBackgroundHandlerThread = null
            mBackgroundHandler = null
        }catch (e:InterruptedException){
            Log.e(TAG, e.toString())
        }
    }

    //stabilisation
    fun stabilisation(image: Image?){
        if (null == image) return
        Log.d(TAG, Calendar.getInstance().time.toString())
        image.close()
        return
    }
}
