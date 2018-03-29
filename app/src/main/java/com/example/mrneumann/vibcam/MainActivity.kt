package com.example.mrneumann.vibcam

import android.Manifest.permission.CAMERA
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
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
import android.hardware.camera2.*
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
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList


class MainActivity : AppCompatActivity() {

    //INIT
    private val TAG = "VibCam"
    private var PERMISSION_REQUEST = 0
    private lateinit var mBackgroundHandlerThread: HandlerThread
    private lateinit var mBackgroundHandler: Handler
    private lateinit var mCameraId: String
    private var mCameraDevice: CameraDevice? = null
    private lateinit var mMediaRecorder: MediaRecorder
    private lateinit var mPreviewSize: Size
    private lateinit var mVideoSize: Size
    private lateinit var mImageSize: Size
    private lateinit var mImageReader: ImageReader
    private var mIsRecording = false
    private lateinit var mCaptureRequestBuilder: CaptureRequest.Builder
    private lateinit var mPreviewCaptureSession: CameraCaptureSession
    private lateinit var mRecordCaptureSession: CameraCaptureSession
    private lateinit var mImageFolder: File
    private lateinit var mVideoFolder: File
    private var mVideoFileName: String? = null
    private var mImageFileName: String? = null
    private val STATE_PREVIEW: Int = 0
    private val STATE_WAIT_LOCK:Int = 1
    private var mCaptureState:Int = STATE_PREVIEW
    private var mTotalRotation:Int = 0
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
            mCameraDevice = cameraDevice
            mMediaRecorder = MediaRecorder()
            if(mIsRecording){
                try{
//                    createVideoFileName()
                } catch (e: IOException){
                    e.printStackTrace()
                }
//                     startRecord();
//                mMediaRecorder.start();
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        mChronometer.setBase(SystemClock.elapsedRealtime());
//                        mChronometer.setVisibility(View.VISIBLE);
//                        mChronometer.start();
//                    }
//                });
            } else {
                startPreview()

            }
        }

        override fun onDisconnected(cameraDevice: CameraDevice?) {
            cameraDevice?.close()
            mCameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice?, p1: Int) {
            cameraDevice?.close()
            mCameraDevice = null
        }
    }
    private var mSurfaceTextureListener = object: TextureView.SurfaceTextureListener{
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            setupCamera(width, height)
            connectCamera()
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
    private var mRecordCaptureCallback = object :CameraCaptureSession.CaptureCallback(){
        override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
            super.onCaptureCompleted(session, request, result)

            if(mCaptureState == STATE_WAIT_LOCK){
                mCaptureState = STATE_PREVIEW
                val afState:Int = result!!.get(CaptureResult.CONTROL_AF_STATE)
                if(afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED){
                    startStillCaptureRequest()
                }
            }
        }
    }

    private val mOnImageAvailableListener = ImageReader.OnImageAvailableListener {
        reader -> mBackgroundHandler.post(ImageSaver(reader.acquireLatestImage()))
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

    //OnCreate
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //fullscreen
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)

        //Permission
        getPermission()

        cameraImageButton.setOnClickListener{
            getPermission()
            takeShot()
        }
    }

    private fun takeShot() {
        mCaptureState = STATE_WAIT_LOCK
        mCaptureRequestBuilder.set(CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_START)
        try{
            if(mIsRecording){
                mRecordCaptureSession.capture(mCaptureRequestBuilder.build(),mRecordCaptureCallback, mBackgroundHandler)
            }else{
                mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(), mPreviewCaptureCallback, mBackgroundHandler)
            }
        }catch (e:CameraAccessException){
            e.printStackTrace()
        }
    }

    private fun startStillCaptureRequest(){
        try{
            mCaptureRequestBuilder = if(mIsRecording){
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT)
            }else{
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            }
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
            if(mIsRecording){
                mRecordCaptureSession.capture(mCaptureRequestBuilder.build(), stillCaptureCallback,null)
            }else{
                mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(),stillCaptureCallback,null)
            }
        }catch (e: CameraAccessException){
            e.printStackTrace()
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
        return videoFile
    }


    private fun getPermission(){
        val permissionToAccess = ArrayList<String>()

        if(checkSelfPermission(this, WRITE_EXTERNAL_STORAGE) != PERMISSION_GRANTED) permissionToAccess.add(WRITE_EXTERNAL_STORAGE)
        if(checkSelfPermission(this, CAMERA) != PERMISSION_GRANTED) permissionToAccess.add(CAMERA)
        //mic

        if(permissionToAccess.isNotEmpty()) {
            requestPermissions(this, permissionToAccess.toTypedArray(), PERMISSION_REQUEST)
        }
    }
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

    override fun onResume() {
        super.onResume()

        createPhotoFolder()
        createVideoFolder()

        startBackgroundThread() //?
        if (cameraWindow.isAvailable){
            setupCamera(cameraWindow.width,cameraWindow.height)
            connectCamera()
        } else {
            cameraWindow.surfaceTextureListener = mSurfaceTextureListener
        }

    }


    private fun setupCamera(width: Int, height: Int) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for(camID in cameraManager.cameraIdList){
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(camID) as CameraCharacteristics
                //определяем основая ли камера
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) continue
                val map:StreamConfigurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val deviceOrientation = windowManager.defaultDisplay.rotation
//                mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation)
//                val swapRotation = mTotalRotation === 90 || mTotalRotation === 270
                val rotatedWidth = width
                val rotatedHeight = height
//                if (swapRotation) {
//                    rotatedWidth = height
//                    rotatedHeight = width
//                }
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java), rotatedWidth, rotatedHeight)
                mVideoSize = chooseOptimalSize(map.getOutputSizes(MediaRecorder::class.java), rotatedWidth, rotatedHeight)
                mImageSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), rotatedWidth, rotatedHeight)
                mImageReader = ImageReader.newInstance(mImageSize.width, mImageSize.height, ImageFormat.JPEG, 1)
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler)
                mCameraId = camID
                return //TODO надо ли?
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun connectCamera() {
        val camManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if(checkSelfPermission(this@MainActivity, CAMERA) == PERMISSION_GRANTED) {
                camManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler)
            } //TODO дополнить этот if
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

//    private fun sensorToDeviceRotation(cameraCharacteristics:CameraCharacteristics, deviceOrientation:Int):Int{
//        val sensorOrienatation:Int = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
//        deviceOrientation = ORIENTATIONS.get(CameraCharacteristics.SENSOR_ORIENTATION)
//        return (sensorOrienatation + deviceOrientation + 360) %360
//    }

    private fun startPreview(){
        val surfaceTexture: SurfaceTexture = cameraWindow.surfaceTexture
        surfaceTexture.setDefaultBufferSize(mPreviewSize.width, mPreviewSize.height)
        val previewSurface = Surface(surfaceTexture)


        try {
            mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mCaptureRequestBuilder.addTarget(previewSurface)
            mCameraDevice!!.createCaptureSession(Arrays.asList(previewSurface,mImageReader.surface),
                    object: CameraCaptureSession.StateCallback(){
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.d(TAG, "onConfigured: startPreview")
                            mPreviewCaptureSession = session
                            try {
                                mPreviewCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(),null,mBackgroundHandler)
                            }catch (e: CameraAccessException){
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(p0: CameraCaptureSession?) {
                            Log.d(TAG, "onConfigureFailed: startPreview")
                        }
                    },
                    null)
        }catch (e:CameraAccessException){
            e.printStackTrace()
        }
    }

    private fun startBackgroundThread() {
        mBackgroundHandlerThread = HandlerThread("VibCam")
        mBackgroundHandlerThread.start()
        mBackgroundHandler = Handler(mBackgroundHandlerThread.looper)
    }

    private fun chooseOptimalSize(choices: Array<out Size>, width: Int, height: Int): Size {
        val bigEnough: ArrayList<Size> = ArrayList()
        choices.filterTo(bigEnough) { it.height == it.width * height / width && it.width >= width && it.height >= height }
        
        return if(bigEnough.size > 0) {
            Collections.min(bigEnough, CompareSizeByArea())
        } else {
            choices[0]
        }
    }

    private class CompareSizeByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            return java.lang.Long.signum((lhs.width * lhs.height).toLong() - (rhs.width * rhs.height).toLong())
        }
    }

    override fun onRequestPermissionsResult(
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

}
