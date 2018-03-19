package com.example.mrneumann.vibcam

import android.Manifest
import android.content.pm.PackageManager
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.graphics.ImageFormat
import android.media.MediaRecorder
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.IOException
import java.util.*
import kotlin.collections.ArrayList


class MainActivity : AppCompatActivity() {

    //INIT
    private val TAG = "VibCam"
    private var MY_PERMISSIONS_REQUEST_CAMERA = 0
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

    //OnCreate
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //fullscreen
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)

        //CAMERA Permition
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this@MainActivity,
                    arrayOf(Manifest.permission.CAMERA),
                    MY_PERMISSIONS_REQUEST_CAMERA)
        }
        //TODO else box?
    }

    override fun onResume() {
        super.onResume()

        startBackgroundThread() //?
        if (cameraWindow.isAvailable){
            Toast.makeText(this@MainActivity, "You clicked me.", Toast.LENGTH_SHORT).show()
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
                //mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation)
                //val swapRotation = mTotalRotation === 90 || mTotalRotation === 270
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
//                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler)
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
            if(ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this@MainActivity, "Открываю камеру", Toast.LENGTH_SHORT).show()
                camManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler)
            } //TODO дополнить этот if
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

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
                            Log.d(TAG, "onConfigureFailed: startPreview");
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
            MY_PERMISSIONS_REQUEST_CAMERA -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    //its OK
                } else {
                    //TODO отключить функциональность
                }
                return
            }

            else -> {
                // Ignore all other requests.
            }
        }
    }

}
