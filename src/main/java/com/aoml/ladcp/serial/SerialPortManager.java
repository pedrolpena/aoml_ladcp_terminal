package com.aoml.ladcp.serial;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SerialPortManager handles serial port communication using jSerialComm library.
 * Inspired by the Python serialport.py module from the UHDAS LADCP Terminal.
 *
 * jSerialComm provides native Apple Silicon (aarch64) support, making this
 * application compatible with modern Macs without Rosetta 2.
 */
public class SerialPortManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SerialPortManager.class);

    // Standard baud rates supported by RDI ADCPs
    public static final int[] SUPPORTED_BAUD_RATES = {
            300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200
    };

    // RDI baud codes (used in CB command)
    public static final Map<Integer, Integer> RDI_BAUD_CODES = Map.of(
            300, 0, 1200, 1, 2400, 2, 4800, 3, 9600, 4,
            19200, 5, 38400, 6, 57600, 7, 115200, 8
    );

    private String devicePath;
    private int baudRate;
    private SerialPort serialPort;
    private InputStream inputStream;
    private OutputStream outputStream;
    private BufferedInputStream bufferedInput;
    private boolean isOpen = false;

    private final List<SerialDataListener> dataListeners = new CopyOnWriteArrayList<>();

    /**
     * Creates a new SerialPortManager with the specified device and baud rate.
     *
     * @param devicePath Path to the serial device (e.g., "/dev/ttyUSB0" or "COM3")
     * @param baudRate   Initial baud rate
     */
    public SerialPortManager(String devicePath, int baudRate) {
        this.devicePath = devicePath;
        this.baudRate = baudRate;
        log.info("Created SerialPortManager for device {} at {} baud", devicePath, baudRate);
    }

    /**
     * Opens the serial port with current settings.
     *
     * @throws SerialPortException if the port cannot be opened
     */
    public synchronized void openPort() throws SerialPortException {
        if (isOpen) {
            log.debug("Port already open");
            return;
        }

        try {
            log.info("Opening serial port {} at {} baud", devicePath, baudRate);
            serialPort = SerialPort.getCommPort(devicePath);

            // Configure port settings: 8N1, no flow control
            serialPort.setBaudRate(baudRate);
            serialPort.setNumDataBits(8);
            serialPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
            serialPort.setParity(SerialPort.NO_PARITY);
            serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);

            // Set non-blocking read timeout (return immediately with whatever is available)
            serialPort.setComPortTimeouts(
                    SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                    100,  // read timeout ms
                    0     // write timeout ms (0 = blocking)
            );

            if (!serialPort.openPort()) {
                throw new SerialPortException("Failed to open port: " + devicePath);
            }

            inputStream = serialPort.getInputStream();
            outputStream = serialPort.getOutputStream();
            bufferedInput = new BufferedInputStream(inputStream);

            isOpen = true;
            log.info("Serial port opened successfully");

        } catch (SerialPortException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to open serial port", e);
            throw new SerialPortException("Failed to open serial port: " + e.getMessage(), e);
        }
    }

    /**
     * Closes the serial port.
     */
    @Override
    public synchronized void close() {
        if (!isOpen) {
            return;
        }

        log.info("Closing serial port {}", devicePath);

        try {
            if (bufferedInput != null) {
                bufferedInput.close();
            }
            if (inputStream != null) {
                inputStream.close();
            }
            if (outputStream != null) {
                outputStream.close();
            }
            if (serialPort != null) {
                serialPort.closePort();
            }
        } catch (IOException e) {
            log.warn("Error closing serial port", e);
        } finally {
            isOpen = false;
            serialPort = null;
            inputStream = null;
            outputStream = null;
            bufferedInput = null;
        }
    }

    /**
     * Changes the baud rate of the serial port.
     *
     * @param newBaudRate The new baud rate
     * @throws SerialPortException if the baud rate change fails
     */
    public synchronized void setBaudRate(int newBaudRate) throws SerialPortException {
        log.info("Changing baud rate from {} to {}", baudRate, newBaudRate);
        this.baudRate = newBaudRate;

        if (isOpen) {
            try {
                serialPort.setBaudRate(newBaudRate);
                // Small delay for baud rate to take effect
                Thread.sleep(100);
            } catch (Exception e) {
                throw new SerialPortException("Failed to change baud rate", e);
            }
        }
    }

    /**
     * Gets the current baud rate.
     *
     * @return Current baud rate
     */
    public int getBaudRate() {
        return baudRate;
    }

    /**
     * Gets the device path.
     *
     * @return Device path
     */
    public String getDevicePath() {
        return devicePath;
    }

    /**
     * Sets the device path. Closes the current port if open.
     *
     * @param devicePath New device path
     */
    public synchronized void setDevicePath(String devicePath) {
        if (isOpen) {
            close();
        }
        this.devicePath = devicePath;
    }

    /**
     * Checks if the port is open.
     *
     * @return true if port is open
     */
    public boolean isOpen() {
        return isOpen;
    }

    /**
     * Writes data to the serial port.
     *
     * @param data Data to write
     * @throws SerialPortException if write fails
     */
    public synchronized void write(byte[] data) throws SerialPortException {
        if (!isOpen) {
            throw new SerialPortException("Port is not open");
        }

        try {
            outputStream.write(data);
            outputStream.flush();
            log.trace("Wrote {} bytes: {}", data.length, new String(data).trim());
        } catch (IOException e) {
            throw new SerialPortException("Failed to write to port", e);
        }
    }

    /**
     * Writes a string to the serial port with CR termination.
     *
     * @param command Command string to write
     * @throws SerialPortException if write fails
     */
    public void writeCommand(String command) throws SerialPortException {
        write((command + "\r").getBytes());
    }

    /**
     * Reads available data from the serial port.
     *
     * @return Bytes read, or empty array if no data available
     * @throws SerialPortException if read fails
     */
    public synchronized byte[] read() throws SerialPortException {
        if (!isOpen) {
            throw new SerialPortException("Port is not open");
        }

        try {
            int available = bufferedInput.available();
            if (available > 0) {
                byte[] buffer = new byte[available];
                int bytesRead = bufferedInput.read(buffer);
                if (bytesRead > 0) {
                    byte[] result = Arrays.copyOf(buffer, bytesRead);
                    log.trace("Read {} bytes", bytesRead);
                    return result;
                }
            }
            return new byte[0];
        } catch (IOException e) {
            throw new SerialPortException("Failed to read from port", e);
        }
    }

    /**
     * Reads data with a timeout.
     *
     * @param timeoutMs Timeout in milliseconds
     * @return Bytes read
     * @throws SerialPortException if read fails
     */
    public byte[] read(int timeoutMs) throws SerialPortException {
        long startTime = System.currentTimeMillis();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            byte[] data = read();
            if (data.length > 0) {
                buffer.write(data, 0, data.length);
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return buffer.toByteArray();
    }

    /**
     * Sends a break signal to wake up RDI instruments.
     * jSerialComm uses setBreak()/clearBreak() instead of a timed sendBreak(),
     * so we manually hold the break line for the requested duration.
     *
     * @param durationMs Duration in milliseconds (default 400ms for RDI instruments)
     * @throws SerialPortException if break fails
     */
    public synchronized void sendBreak(int durationMs) throws SerialPortException {
        if (!isOpen) {
            throw new SerialPortException("Port is not open");
        }

        try {
            log.info("Sending break signal for {}ms", durationMs);
            serialPort.setBreak();
            Thread.sleep(durationMs);
            serialPort.clearBreak();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Still clear break even if interrupted
            if (serialPort != null) {
                serialPort.clearBreak();
            }
            throw new SerialPortException("Break interrupted", e);
        } catch (Exception e) {
            throw new SerialPortException("Failed to send break", e);
        }
    }

    /**
     * Sends a break with default duration (400ms).
     *
     * @throws SerialPortException if break fails
     */
    public void sendBreak() throws SerialPortException {
        sendBreak(400);
    }

    /**
     * Flushes input buffer.
     *
     * @throws SerialPortException if flush fails
     */
    public synchronized void flushInput() throws SerialPortException {
        if (!isOpen) {
            return;
        }

        try {
            while (bufferedInput.available() > 0) {
                bufferedInput.read();
            }
        } catch (IOException e) {
            throw new SerialPortException("Failed to flush input", e);
        }
    }

    /**
     * Flushes output buffer.
     *
     * @throws SerialPortException if flush fails
     */
    public synchronized void flushOutput() throws SerialPortException {
        if (!isOpen) {
            return;
        }

        try {
            outputStream.flush();
        } catch (IOException e) {
            throw new SerialPortException("Failed to flush output", e);
        }
    }

    /**
     * Gets the raw input stream.
     *
     * @return Input stream
     */
    public InputStream getInputStream() {
        return inputStream;
    }

    /**
     * Gets the raw output stream.
     *
     * @return Output stream
     */
    public OutputStream getOutputStream() {
        return outputStream;
    }

    /**
     * Lists available serial ports on the system.
     * Returns full device paths (e.g., "/dev/ttyUSB0" on Linux/Mac, "COM3" on Windows)
     * to match the format used by getCommPort() and stored in preferences.
     *
     * @return Set of available port paths
     */
    public static Set<String> listAvailablePorts() {
        Set<String> portNames = new LinkedHashSet<>();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        for (SerialPort port : SerialPort.getCommPorts()) {
            String name = port.getSystemPortName();
            // On Linux/Mac, getSystemPortName() returns just "ttyUSB0" without the
            // "/dev/" prefix.  We need the full path to match what getCommPort() and
            // the stored preferences expect.
            if (!isWindows && !name.startsWith("/")) {
                name = "/dev/" + name;
            }
            portNames.add(name);
        }
        return portNames;
    }

    /**
     * Adds a data listener.
     *
     * @param listener Listener to add
     */
    public void addDataListener(SerialDataListener listener) {
        dataListeners.add(listener);
    }

    /**
     * Removes a data listener.
     *
     * @param listener Listener to remove
     */
    public void removeDataListener(SerialDataListener listener) {
        dataListeners.remove(listener);
    }

    /**
     * Notifies all listeners of received data.
     *
     * @param data Received data
     */
    protected void notifyDataListeners(byte[] data) {
        for (SerialDataListener listener : dataListeners) {
            try {
                listener.onDataReceived(data);
            } catch (Exception e) {
                log.warn("Error notifying data listener", e);
            }
        }
    }

    /**
     * Interface for serial data listeners.
     */
    public interface SerialDataListener {
        void onDataReceived(byte[] data);
    }
}
