package com.aoml.ladcp.serial;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SerialReader handles asynchronous reading from a serial port.
 * Data is read in a background thread and placed in a queue for consumption.
 */
public class SerialReader implements Runnable, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SerialReader.class);

    private final SerialPortManager portManager;
    private final BlockingQueue<byte[]> dataQueue;
    private final AtomicBoolean running;
    private Thread readerThread;
    private OutputStream logFile;

    /**
     * Creates a new SerialReader for the given port manager.
     *
     * @param portManager The serial port manager to read from
     */
    public SerialReader(SerialPortManager portManager) {
        this.portManager = portManager;
        this.dataQueue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(false);
    }

    /**
     * Starts the reader thread.
     */
    public synchronized void start() {
        if (running.get()) {
            return;
        }

        running.set(true);
        readerThread = new Thread(this, "SerialReader-" + portManager.getDevicePath());
        readerThread.setDaemon(true);
        readerThread.start();
        log.info("SerialReader started for {}", portManager.getDevicePath());
    }

    /**
     * Stops the reader thread.
     */
    public synchronized void stop() {
        running.set(false);
        if (readerThread != null) {
            readerThread.interrupt();
            try {
                readerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readerThread = null;
        }
        log.info("SerialReader stopped");
    }

    /**
     * Checks if the reader is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Sets a log file to write all received data.
     *
     * @param file Log file output stream
     */
    public void setLogFile(OutputStream file) {
        this.logFile = file;
    }

    /**
     * Gets the next data chunk from the queue.
     *
     * @return Data chunk or null if none available
     */
    public byte[] poll() {
        return dataQueue.poll();
    }

    /**
     * Gets the next data chunk, waiting up to the specified time.
     *
     * @param timeout Timeout value
     * @param unit    Time unit
     * @return Data chunk or null if timeout
     * @throws InterruptedException if interrupted
     */
    public byte[] poll(long timeout, TimeUnit unit) throws InterruptedException {
        return dataQueue.poll(timeout, unit);
    }

    /**
     * Gets all available data from the queue.
     *
     * @return Combined data from all queued chunks
     */
    public byte[] pollAll() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk;
        while ((chunk = dataQueue.poll()) != null) {
            buffer.write(chunk, 0, chunk.length);
        }
        return buffer.toByteArray();
    }

    /**
     * Clears the data queue.
     */
    public void clearQueue() {
        dataQueue.clear();
    }

    @Override
    public void run() {
        byte[] buffer = new byte[1024];

        while (running.get()) {
            try {
                if (!portManager.isOpen()) {
                    Thread.sleep(100);
                    continue;
                }

                InputStream in = portManager.getInputStream();
                if (in == null) {
                    Thread.sleep(100);
                    continue;
                }

                int available = in.available();
                if (available > 0) {
                    int bytesToRead = Math.min(available, buffer.length);
                    int bytesRead = in.read(buffer, 0, bytesToRead);

                    if (bytesRead > 0) {
                        byte[] data = new byte[bytesRead];
                        System.arraycopy(buffer, 0, data, 0, bytesRead);
                        dataQueue.offer(data);

                        // Write to log file if set
                        if (logFile != null) {
                            try {
                                logFile.write(data);
                                logFile.flush();
                            } catch (IOException e) {
                                log.warn("Error writing to log file", e);
                            }
                        }

                        log.trace("Read {} bytes from serial port", bytesRead);
                    }
                } else {
                    // No data available, sleep briefly
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("Error reading from serial port", e);
                }
            } catch (Exception e) {
                log.error("Unexpected error in serial reader", e);
            }
        }

        log.debug("SerialReader thread exiting");
    }

    @Override
    public void close() {
        stop();
        if (logFile != null) {
            try {
                logFile.close();
            } catch (IOException e) {
                log.warn("Error closing log file", e);
            }
        }
    }
}
