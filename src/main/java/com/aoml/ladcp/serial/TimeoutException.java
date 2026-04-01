package com.aoml.ladcp.serial;

/**
 * Exception thrown when a serial port operation times out.
 */
public class TimeoutException extends Exception {

    public TimeoutException() {
        super("Operation timed out");
    }

    public TimeoutException(String message) {
        super(message);
    }

    public TimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
