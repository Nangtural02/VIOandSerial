package com.google.ar.core.examples.kotlin.helloar

import android.hardware.usb.UsbDevice
import com.google.ar.core.examples.Serial.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.google.ar.core.examples.Serial.hoho.android.usbserial.driver.UsbSerialDriver
import com.google.ar.core.examples.Serial.hoho.android.usbserial.driver.UsbSerialPort

data class USBItem(
    val device: UsbDevice,
    val port: UsbSerialPort,
    val driver: UsbSerialDriver = CdcAcmSerialDriver(device)
)
