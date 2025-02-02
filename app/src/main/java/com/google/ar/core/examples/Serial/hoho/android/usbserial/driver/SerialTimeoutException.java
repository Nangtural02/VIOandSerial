package com.google.ar.core.examples.Serial.hoho.android.usbserial.driver;

import java.io.InterruptedIOException;

/**
 * Signals that a timeout has occurred on serial write.
 * Similar to SocketTimeoutException.
 *
 * {@see InterruptedIOException#bytesTransferred} may contain bytes transferred
 */
public class SerialTimeoutException extends InterruptedIOException {
    public SerialTimeoutException(String s, int bytesTransferred) {
        super(s);
        this.bytesTransferred = bytesTransferred;
    }
}
