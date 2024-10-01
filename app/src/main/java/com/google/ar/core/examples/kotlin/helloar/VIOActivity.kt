/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.kotlin.helloar

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Config
import com.google.ar.core.Config.InstantPlacementMode
import com.google.ar.core.Session
import com.google.ar.core.examples.Serial.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.google.ar.core.examples.Serial.hoho.android.usbserial.driver.UsbSerialPort
import com.google.ar.core.examples.Serial.hoho.android.usbserial.util.SerialInputOutputManager
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper
import com.google.ar.core.examples.java.common.helpers.DepthSettings
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.kotlin.common.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable.cancel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
class VIOActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: HelloArView
  lateinit var renderer: HelloArRenderer
  lateinit var VIOString: TextView
  val instantPlacementSettings = InstantPlacementSettings()
  val depthSettings = DepthSettings()

  //USB Serial----------------------------------------

  var speedParameter:String = "noSpeed"
  var methodParameter:String = "noMethod"
  var toSave:Boolean = false
  var toSaveSerial:Boolean = false

  val foldername: String =
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/VIOandSerialLog" //Save Path
  var filename: String = "temp.txt"
  lateinit var serialString:TextView
  companion object {
    private const val TAG = "MainActivity"
    private const val ACTION_USB_PERMISSION = "com.example.serialapp.USB_PERMISSION"
  }
  private lateinit var usbManager: UsbManager
  private var port: UsbSerialPort? = null

  var connectedUSBItem:USBItem? = null
  private enum class USBPermission {UnKnown, Requested, Granted, Denied}
  var baudRate = 115200
  private var usbPermission: USBPermission = USBPermission.UnKnown

  private val INTENT_ACTION_GRANT_USB: String = "com.google.ar.core.examples.kotlin.VIOTest" + ".GRANT_USB"
  private var usbIOManager: SerialInputOutputManager? = null
  private val usbPermissionReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (INTENT_ACTION_GRANT_USB == intent.action) {
        usbPermission = if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
          USBPermission.Granted
        } else {
          USBPermission.Denied
        }
        connectSerialDevice(context)
      }
    }
  }



  fun connectSerialDevice(context: Context) {
    val button: Button = findViewById(R.id.ConnectButton)
    button.visibility = View.GONE
    var count = 0
    lifecycleScope.launch(Dispatchers.IO) {
      val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
      Log.d("asdf", "${connectedUSBItem != null}")
      while (connectedUSBItem == null) {
        Log.d("button", "try to Connect")
        for (device in usbManager.deviceList.values) {
          val driver = CdcAcmSerialDriver(device)
          if (driver.ports.size == 1) {
            connectedUSBItem = USBItem(device, driver.ports[0], driver)
            Log.d("asdf", "device Connected")
          }
        }
        delay(1000L) //by 1 sec
        count++
        if (count > 5) {
          disConnectSerialDevice()
          this.cancel()
        } //more than 5 sec
      }

      val device: UsbDevice = connectedUSBItem!!.device
      var usbConnection: UsbDeviceConnection? = null
      if (usbPermission == USBPermission.UnKnown && !usbManager.hasPermission(device)) {
        usbPermission = USBPermission.Requested
        val intent: Intent = Intent(INTENT_ACTION_GRANT_USB)
        intent.setPackage(getApplication().packageName)
        Log.d("asdf", "request Permission")
        usbManager.requestPermission(
          device,
          PendingIntent.getBroadcast(
            getApplication(),
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
          )
        )
        return@launch
      }
      try {
        Log.d("asdf", "Port open try")
        usbConnection = usbManager.openDevice(device)
        connectedUSBItem!!.port.open(usbConnection)
      } catch (e: IllegalArgumentException) {
        disConnectSerialDevice()
        return@launch
      } catch (e: IOException) {
        if (e.message != "Already Open") throw IOException()
      }
      Log.d("asdf", "Port open")
      connectedUSBItem!!.port.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE)
      usbIOManager = SerialInputOutputManager(connectedUSBItem!!.port, this@VIOActivity)
      usbIOManager!!.start()
      Log.d("qwrwe", "dtr On")
      connectedUSBItem?.port?.dtr = true

    }
  }

  fun disConnectSerialDevice(){
    val button:Button = findViewById(R.id.ConnectButton)
    button.visibility = View.VISIBLE
    usbPermission = USBPermission.UnKnown
    usbIOManager?.listener = null
    usbIOManager?.stop()
    if(connectedUSBItem == null) return
    if(connectedUSBItem?.port!!.isOpen()){
      connectedUSBItem?.port?.close()
    }
    connectedUSBItem = null
  }

  fun blockHandler(blockString: String){

    serialString.text = "Serial: "+ blockString
    toSaveSerial = true

  }
  fun setFileName(){
    filename = speedParameter + "_" + methodParameter + "VIOSerial_" + SimpleDateFormat("yyyy_MM_dd HH_mm_ss").format(Date()) + ".txt"
  }
  private suspend fun writeTextFile(contents: String) {
    withContext(Dispatchers.IO) {
      try {
        val dir = File(foldername)
        if (!dir.exists()) {
          dir.mkdir()
        }
        val fos = FileOutputStream(foldername + "/" + filename, true)
        fos.write(contents.encodeToByteArray())
        fos.close()
      } catch (e: IOException) {
        e.printStackTrace()
      }
    }
  }


  private val _buffer = MutableStateFlow<String>("")
  val buffer: StateFlow<String> get() = _buffer


/*
  override fun onNewData(data: ByteArray?) { // For DWM3001CDK CLI(JSON)
    lifecycleScope.launch{
      if(data != null) {
        if (data.isNotEmpty()) {
          val result : String = getLineString(data, data.size)
          if (_buffer.value.isEmpty()) {
            _buffer.value += result
          }else{
            if(result.length >=3 && result.substring(0,3) == "{\"B"){ //메시지를 받다말고 새로운 메시지가 들어옴
              _buffer.value = result
            }else if(result.length >=3 && result.substring(result.length - 3).equals("}  ")){ //메시지의 끝
              _buffer.value += result.substring(0,result.length-2)

              blockHandler(_buffer.value)
              _buffer.value = ""
            }else{
              _buffer.value += result
            }
          }
        }
      }
    }
  }
 */

  override fun onNewData(data: ByteArray?) { // For Custom Data Format ( {~~~~~} )
    lifecycleScope.launch{
      if(data != null) {
        if (data.isNotEmpty()) {
          val result : String = getLineString(data, data.size)
          if (_buffer.value.isEmpty()) {
            _buffer.value += result
          }else{
            result.replace(" ","")
            if(result.contains("}")){
              _buffer.value += result
              blockHandler(_buffer.value)
              _buffer.value = ""
            }else{
              _buffer.value += result
            }
          }
        }
      }
    }
  }

  override fun onRunError(e: Exception) {
    lifecycleScope.launch() {
      Log.d("SerialDevice", "Disconnected: ${e.message}")
      disConnectSerialDevice()
    }
  }

  private fun getLineString(array: ByteArray, length: Int): String {
    val result = StringBuilder()
    val line = ByteArray(8)
    var lineIndex = 0
    for (i in 0 until 0 + length) {
      if (lineIndex == line.size) {
        for (j in line.indices) {
          if (line[j] > ' '.code.toByte() && line[j] < '~'.code.toByte()) {
            result.append(String(line, j, 1))
          } else {
            result.append(" ")
          }
        }
        lineIndex = 0
      }
      val b = array[i]
      line[lineIndex++] = b
    }
    for (i in 0 until lineIndex) {
      if (line[i] > ' '.code.toByte() && line[i] < '~'.code.toByte()) {
        result.append(String(line, i, 1))
      } else {
        result.append(" ")
      }
    }
    return result.toString()
  }
//----------------------------------------USB Serial

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Setup ARCore session lifecycle helper and configuration.
    arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
    // If Session creation or Session.resume() fails, display a message and log detailed
    // information.
    arCoreSessionHelper.exceptionCallback =
      { exception ->
        val message =
          when (exception) {
            is UnavailableUserDeclinedInstallationException ->
              "Please install Google Play Services for AR"
            is UnavailableApkTooOldException -> "Please update ARCore"
            is UnavailableSdkTooOldException -> "Please update this app"
            is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
            is CameraNotAvailableException -> "Camera not available. Try restarting the app."
            else -> "Failed to create AR session: $exception"
          }
        Log.e("VIOActivity", "ARCore threw an exception", exception)
        view.snackbarHelper.showError(this, message)
      }

    // Configure session features, including: Lighting Estimation, Depth mode, Instant Placement.
    arCoreSessionHelper.beforeSessionResume = ::configureSession
    lifecycle.addObserver(arCoreSessionHelper)

    // Set up the Hello AR renderer.
    renderer = HelloArRenderer(this)
    lifecycle.addObserver(renderer)

    // Set up Hello AR UI.
    view = HelloArView(this)
    lifecycle.addObserver(view)
    setContentView(view.root)
    serialString = findViewById(R.id.SerialString)
    // Sets up an example renderer using our HelloARRenderer.
    SampleRender(view.surfaceView, renderer, assets)

    depthSettings.onCreate(this)
    instantPlacementSettings.onCreate(this)
    VIOString = findViewById(R.id.VIO)
    val filter = IntentFilter(INTENT_ACTION_GRANT_USB)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // Android 12 이상일 경우, 명시적으로 플래그를 지정
      getApplication().registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      // Android 12 미만일 경우, 기존 방식으로 등록
      getApplication().registerReceiver(usbPermissionReceiver, filter)
    }


    val speedRadioGroup: RadioGroup = findViewById(R.id.speedRadioGroup)
    speedRadioGroup.setOnCheckedChangeListener { group, checkedId ->
      when (checkedId) {
        R.id.highSpeed -> {
          speedParameter = "HighSpeed"
        }
        R.id.lowSpeed -> {
          speedParameter = "LowSpeed"
        }
      }
    }
    val rangingMethodRadioGroup: RadioGroup = findViewById(R.id.rangingMethodRadioGroup)
    rangingMethodRadioGroup.setOnCheckedChangeListener { group, checkedId ->
      when (checkedId) {
        R.id.singleSide -> {
          methodParameter = "SingleSide"
        }
        R.id.doubleSide -> {
          methodParameter = "DoubleSide"
        }
      }
    }
    val toggleButton: ToggleButton = findViewById(R.id.dataSaveButton)
    toggleButton.setOnCheckedChangeListener { _, isChecked ->
      if (isChecked) {
        setFileName()
        toSave = true
        Toast.makeText(this, "저장 시작, ${SimpleDateFormat("HH_mm_ss").format(Date())}", Toast.LENGTH_SHORT).show()

      } else {
        toSave = false
        Toast.makeText(this, "저장 정지", Toast.LENGTH_SHORT).show()
      }
    }


  }

  // Configure the session, using Lighting Estimation, and Depth mode.
  fun configureSession(session: Session) {
    session.configure(
      session.config.apply {
        lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

        // Depth API is used if it is configured in Hello AR's settings.
        depthMode =
          if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            Config.DepthMode.AUTOMATIC
          } else {
            Config.DepthMode.DISABLED
          }

        // Instant Placement is used if it is configured in Hello AR's settings.
        instantPlacementMode =
          if (instantPlacementSettings.isInstantPlacementEnabled) {
            InstantPlacementMode.LOCAL_Y_UP
          } else {
            InstantPlacementMode.DISABLED
          }
      }
    )
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    results: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
        .show()
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this)
      }
      finish()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }
  fun updateVIOString(newString:String){

    VIOString.text = "VIO: "+ newString
    if(toSave) {
      lifecycleScope.launch {
       if(toSaveSerial){
         toSaveSerial = false
         writeTextFile(newString + " // " + serialString.text.toString() + "\n")

       }else{
         writeTextFile(newString + ";; \n")
       }
      }
    }

  }
  fun onConnectSerialDeviceClick(view: View){
    lifecycleScope.launch {
      connectSerialDevice(this@VIOActivity)
    }
  }





}
