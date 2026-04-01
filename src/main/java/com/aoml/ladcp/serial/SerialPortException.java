package com.aoml.ladcp.serial;

/**
 * Exception thrown when serial port operations fail.
 */
public class SerialPortException extends Exception {

    public SerialPortException(String message) {
        super(message);
    }

    public SerialPortException(String message, Throwable cause) {
        super(message, cause);
    }
}
