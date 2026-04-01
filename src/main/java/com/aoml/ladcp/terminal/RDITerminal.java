package com.aoml.ladcp.terminal;

import com.aoml.ladcp.serial.SerialPortException;
import com.aoml.ladcp.serial.SerialPortManager;
import com.aoml.ladcp.serial.SerialReader;
import com.aoml.ladcp.serial.TimeoutException;
import com.aoml.ladcp.serial.YModemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.*;

/**
 * RDITerminal handles communication with RDI (Teledyne) ADCP instruments.
 * Supports both Broadband (BB) and WorkHorse (WH) ADCP types.
 * 
 * Inspired by the Python rditerm.py module from the UHDAS LADCP Terminal.
 */
public class RDITerminal implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RDITerminal.class);

    // Break duration for RDI instruments
    private static final int BREAK_DURATION_MS = 400;

    // Timeout for waiting for ">" prompt between commands.
    // USB-to-serial adapters can introduce significant latency; 10s is generous
    // but avoids hanging indefinitely.  This is an *inactivity* timeout: data
    // arriving on the port resets the clock.
    private static final int PROMPT_TIMEOUT_MS = 10000;

    // Default data baud rates for different instrument types
    private static final Map<InstrumentType, Integer> DEFAULT_DATA_BAUDS = Map.of(
            InstrumentType.BROADBAND, 38400,
            InstrumentType.WORKHORSE, 115200,
            InstrumentType.UNRECOGNIZED, 9600
    );

    // Date/time format for logging
    private static final DateTimeFormatter LOG_TIME_FORMAT = 
            DateTimeFormatter.ofPattern("yyyy/MM/dd  HH:mm:ss");
    private static final DateTimeFormatter ADCP_TIME_FORMAT = 
            DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final DateTimeFormatter ADCP_RESPONSE_FORMAT = 
            DateTimeFormatter.ofPattern("yy/MM/dd,HH:mm:ss");

    // Serial port management
    private final SerialPortManager portManager;
    private final SerialReader serialReader;

    // Terminal state
    private InstrumentType instrumentType = InstrumentType.UNRECOGNIZED;
    private int defaultBaud;
    private Integer dataBaud;
    private boolean isListening = false;
    private volatile boolean cancelled = false;

    // Data buffers
    private final StringBuilder displayBuffer = new StringBuilder();
    private final ByteArrayOutputStream rawBuffer = new ByteArrayOutputStream();
    private final Object bufferLock = new Object();

    // Configuration
    private String prefix = "ladcp";
    private String suffix = "";
    private String cruiseName = "XXNNNN";
    private String stationCast = "000_01";
    private String dataFileExtension = ".dat";
    private Path commandFile;
    private Path dataDirectory = Paths.get("./");
    private Path logDirectory = Paths.get("./");
    private Path backupDirectory;

    // File saving
    private OutputStream saveFile;
    private boolean isSaving = false;
    
    // Current download handler (for cancellation)
    private volatile YModemHandler currentYModemHandler;

    // Listeners
    private final List<TerminalListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Instrument types supported by RDI.
     */
    public enum InstrumentType {
        BROADBAND("BB"),
        WORKHORSE("WH"),
        UNRECOGNIZED("Unknown");

        private final String code;

        InstrumentType(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    /**
     * Creates a new RDI Terminal.
     *
     * @param devicePath Path to serial device
     * @param baudRate   Initial baud rate (typically 9600)
     */
    public RDITerminal(String devicePath, int baudRate) {
        this.portManager = new SerialPortManager(devicePath, baudRate);
        this.serialReader = new SerialReader(portManager);
        this.defaultBaud = baudRate;
        log.info("RDI Terminal created for {} at {} baud", devicePath, baudRate);
    }

    /**
     * Builder for creating RDITerminal with custom settings.
     */
    public static class Builder {
        private String devicePath = "/dev/ttyUSB0";
        private int baudRate = 9600;
        private Integer dataBaud = null;
        private String prefix = "ladcp";
        private String suffix = "";
        private String cruiseName = "XXNNNN";
        private String stationCast = "000_01";
        private String dataFileExtension = ".dat";
        private Path commandFile = null;
        private Path dataDirectory = Paths.get("./");
        private Path logDirectory = Paths.get("./");
        private Path backupDirectory = null;

        public Builder devicePath(String devicePath) {
            this.devicePath = devicePath;
            return this;
        }

        public Builder baudRate(int baudRate) {
            this.baudRate = baudRate;
            return this;
        }

        public Builder dataBaud(int dataBaud) {
            this.dataBaud = dataBaud;
            return this;
        }

        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        public Builder suffix(String suffix) {
            this.suffix = suffix;
            return this;
        }

        public Builder cruiseName(String cruiseName) {
            this.cruiseName = cruiseName;
            return this;
        }

        public Builder stationCast(String stationCast) {
            this.stationCast = stationCast;
            return this;
        }

        public Builder dataFileExtension(String ext) {
            this.dataFileExtension = ext.startsWith(".") ? ext : "." + ext;
            return this;
        }

        public Builder commandFile(Path commandFile) {
            this.commandFile = commandFile;
            return this;
        }

        public Builder dataDirectory(Path dataDirectory) {
            this.dataDirectory = dataDirectory;
            return this;
        }

        public Builder logDirectory(Path logDirectory) {
            this.logDirectory = logDirectory;
            return this;
        }

        public Builder backupDirectory(Path backupDirectory) {
            this.backupDirectory = backupDirectory;
            return this;
        }

        public RDITerminal build() {
            RDITerminal terminal = new RDITerminal(devicePath, baudRate);
            terminal.dataBaud = dataBaud;
            terminal.prefix = prefix;
            terminal.suffix = suffix;
            terminal.cruiseName = cruiseName;
            terminal.stationCast = stationCast;
            terminal.dataFileExtension = dataFileExtension;
            terminal.commandFile = commandFile;
            terminal.dataDirectory = dataDirectory;
            terminal.logDirectory = logDirectory;
            terminal.backupDirectory = backupDirectory;
            return terminal;
        }
    }

    // ==================== Connection Management ====================

    /**
     * Starts listening on the serial port.
     *
     * @throws SerialPortException if connection fails
     */
    public void startListening() throws SerialPortException {
        if (isListening) {
            clearBuffer();
            return;
        }

        portManager.openPort();
        serialReader.start();
        isListening = true;
        
        // Start update thread
        startUpdateThread();
        
        notifyListeners(TerminalEvent.CONNECTED);
        log.info("Started listening on {}", portManager.getDevicePath());
    }

    /**
     * Stops listening on the serial port.
     */
    public void stopListening() {
        if (!isListening) {
            return;
        }

        isListening = false;
        serialReader.stop();
        portManager.close();
        
        notifyListeners(TerminalEvent.DISCONNECTED);
        log.info("Stopped listening");
    }

    /**
     * Checks if terminal is connected.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return isListening && portManager.isOpen();
    }

    // ==================== Instrument Communication ====================

    /**
     * Signals any in-progress command sequence (sendCommands / sendCommandsNoTimeout)
     * to stop after the current command finishes.  The flag is automatically
     * cleared when {@link #wakeup()} is called, so normal operation resumes.
     */
    public void cancelPendingCommands() {
        cancelled = true;
        log.info("Pending commands cancelled");
    }

    /**
     * Checks whether commands have been cancelled.
     *
     * @return true if cancelled
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Wakes up the ADCP instrument by sending a break signal.
     * Sends break twice for reliability as the first wakeup often fails.
     * Clears the cancellation flag so subsequent commands can proceed.
     *
     * @throws SerialPortException if communication fails
     * @throws TimeoutException    if no response from instrument
     */
    public void wakeup() throws SerialPortException {
        cancelled = false;  // Reset so commands after wakeup can proceed
        log.info("Waking up ADCP instrument");
        
        changeBaud(defaultBaud);
        startListening();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        clearBuffer();
        
        // First break - often fails or partially wakes the instrument
        portManager.sendBreak(BREAK_DURATION_MS);
        
        try {
            Thread.sleep(500); // Wait a bit before second break
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        clearBuffer();
        
        // Second break - usually succeeds
        portManager.sendBreak(BREAK_DURATION_MS);
        
        try {
            String banner = waitFor(">", 5000);
            
            if (banner.contains("WorkHorse")) {
                instrumentType = InstrumentType.WORKHORSE;
                log.info("Identified WorkHorse ADCP");
            } else if (banner.contains("Broadband")) {
                instrumentType = InstrumentType.BROADBAND;
                log.info("Identified Broadband ADCP");
            } else {
                instrumentType = InstrumentType.UNRECOGNIZED;
                log.warn("Instrument not identified. Banner: {}", banner);
            }
        } catch (TimeoutException e) {
            log.warn("Timeout waiting for wakeup response");
            instrumentType = InstrumentType.UNRECOGNIZED;
        }
        
        notifyListeners(TerminalEvent.INSTRUMENT_IDENTIFIED);
    }

    /**
     * Wakes up the instrument if it's not already awake.
     *
     * @throws SerialPortException if communication fails
     */
    public void wakeIfSleeping() throws SerialPortException {
        if (instrumentType == InstrumentType.UNRECOGNIZED) {
            wakeup();
            return;
        }

        try {
            startListening();
            portManager.writeCommand("TS?");
            waitFor(">", 3000);
        } catch (TimeoutException e) {
            wakeup();
        }
    }

    /**
     * Puts the ADCP to sleep.
     *
     * @throws SerialPortException if communication fails
     */
    public void sleep() throws SerialPortException {
        log.info("Putting ADCP to sleep");
        changeBaud(defaultBaud);
        wakeIfSleeping();
        
        portManager.writeCommand("CZ");
        
        try {
            if (instrumentType == InstrumentType.BROADBAND) {
                waitFor("[POWERING DOWN .....]", 2000);
            } else {
                waitFor("Powering Down", 5000);
            }
        } catch (TimeoutException e) {
            log.warn("Timeout waiting for sleep confirmation");
        }
    }

    /**
     * Sets the ADCP clock to current UTC time.
     *
     * @throws SerialPortException if communication fails
     */
    public void setClock() throws SerialPortException {
        wakeIfSleeping();
        String dateTime = ZonedDateTime.now(ZoneOffset.UTC).format(ADCP_TIME_FORMAT);
        sendCommandsNoTimeout(List.of("TS" + dateTime));
        log.info("Set ADCP clock to {}", dateTime);
    }

    /**
     * Shows the time comparison between PC and ADCP.
     *
     * @return Time comparison result
     * @throws SerialPortException if communication fails
     * @throws TimeoutException    if no response
     */
    public TimeComparison showTimeOk() throws SerialPortException, TimeoutException {
        wakeIfSleeping();
        LocalDateTime pcTime = LocalDateTime.now(ZoneOffset.UTC);
        this.clearDisplay();
        sendCommands(List.of("TS?"));
        
        String response = getDisplayBuffer();
        // Parse TS response - format varies by firmware
        Pattern pattern = Pattern.compile("TS\\s*=?\\s*(\\d{2}/\\d{2}/\\d{2},\\d{2}:\\d{2}:\\d{2})");
        Matcher matcher = pattern.matcher(response);
        
        if (matcher.find()) {
            String tsString = matcher.group(1);
            LocalDateTime adcpTime = LocalDateTime.parse(tsString, ADCP_RESPONSE_FORMAT);
            long diffSeconds = Duration.between(adcpTime, pcTime).getSeconds();
            
            TimeComparison comparison = new TimeComparison(pcTime, adcpTime, diffSeconds);
            insertComment(comparison.toString());
            return comparison;
        }
        
        throw new TimeoutException("Could not parse ADCP time response");
    }

    /**
     * Sends a list of commands to the ADCP.
     *
     * @param commands Commands to send
     * @throws SerialPortException if communication fails
     * @throws TimeoutException    if no response
     */
    public void sendCommands(List<String> commands) throws SerialPortException, TimeoutException {
        sendCommands(commands, PROMPT_TIMEOUT_MS);
    }
    
    /**
     * Sends a list of commands to the ADCP, waiting for the ">" prompt
     * between each command.  If a prompt does not arrive within the
     * inactivity timeout window the command is logged as a warning, but
     * execution continues so the remaining commands still have a chance
     * to run.
     * <p>
     * Prompt detection uses {@link #waitForPrompt} which only looks at
     * data that arrived <em>after</em> the command was sent, and whose
     * timeout resets whenever new data arrives on the serial port.
     *
     * @param commands Commands to send
     * @throws SerialPortException if communication fails
     */
    public void sendCommandsNoTimeout(List<String> commands) throws SerialPortException {
        startListening();
        
        for (String cmd : commands) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                log.info("Command sequence cancelled, {} commands remaining", 
                        commands.size() - commands.indexOf(cmd));
                insertComment("Command sequence cancelled");
                return;
            }
            String trimmedCmd = cmd.trim();
            if (!trimmedCmd.isEmpty()) {
                log.debug("Sending command: {}", trimmedCmd);
                portManager.writeCommand(trimmedCmd);
                try {
                    waitForPrompt(PROMPT_TIMEOUT_MS);
                } catch (TimeoutException e) {
                    log.warn("Timeout waiting for prompt after command: {} (waited {}ms of inactivity)",
                            trimmedCmd, PROMPT_TIMEOUT_MS);
                }
            }
        }
    }

    /**
     * Sends a list of commands to the ADCP with custom inactivity timeout.
     * Uses prompt-aware waiting that only detects ">" characters
     * received after the command was sent.
     *
     * @param commands Commands to send
     * @param timeoutMs Inactivity timeout in milliseconds per command
     * @throws SerialPortException if communication fails
     * @throws TimeoutException    if no prompt received for any command
     */
    public void sendCommands(List<String> commands, int timeoutMs) 
            throws SerialPortException, TimeoutException {
        startListening();
        
        for (String cmd : commands) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                log.info("Command sequence cancelled");
                insertComment("Command sequence cancelled");
                return;
            }
            String trimmedCmd = cmd.trim();
            if (!trimmedCmd.isEmpty()) {
                log.debug("Sending command: {}", trimmedCmd);
                portManager.writeCommand(trimmedCmd);
                waitForPrompt(timeoutMs);
            }
        }
    }

    /**
     * Shows the ADCP configuration.
     *
     * @throws SerialPortException if communication fails
     */
    public void showConfig() throws SerialPortException {
        List<String> commands = Arrays.asList(
                "RA", "RS", "RB0", "PS3", "B?", "C?", "E?", "P?", "T?", "W?"
        );
        wakeIfSleeping();
        sendCommandsNoTimeout(commands);
    }

    /**
     * Runs diagnostic tests on the ADCP.
     *
     * @throws SerialPortException if communication fails
     */
    public void runDiagnostics() throws SerialPortException {
        wakeIfSleeping();
        sendCommandsNoTimeout(Arrays.asList("PS0", "PT200"));
    }

    /**
     * Lists the recorder directory contents.
     *
     * @throws SerialPortException if communication fails
     */
    public void listRecorder() throws SerialPortException {
        List<String> commands;
        if (instrumentType == InstrumentType.BROADBAND) {
            commands = Arrays.asList("RA", "RS");
        } else {
            commands = Arrays.asList("RA", "RS", "RF", "RR");
        }
        
        try {
            sendCommands(commands);
        } catch (TimeoutException e) {
            wakeup();
            sendCommandsNoTimeout(commands);
        }
    }

    /**
     * Erases the ADCP recorder.
     *
     * @throws SerialPortException if communication fails
     */
    public void eraseRecorder() throws SerialPortException {
        log.warn("Erasing ADCP recorder!");
        changeBaud(defaultBaud);
        startListening();
        wakeIfSleeping();
        
        portManager.writeCommand("RE ErAsE");
        try {
            waitFor(">", 5000);
        } catch (TimeoutException e) {
            log.warn("Timeout waiting for erase confirmation");
        }
        log.info("Recorder erased");
    }

    /**
     * Changes the baud rate on both PC and ADCP sides.
     *
     * @param baud New baud rate
     * @throws SerialPortException if change fails
     */
    public void changeAllBaud(int baud) throws SerialPortException {
        sendCommandsNoTimeout(List.of(""));  // Ensure awake
        
        int baudCode = SerialPortManager.RDI_BAUD_CODES.get(baud);
        portManager.writeCommand("CB" + baudCode + "11");
        try {
            waitFor(">", 3000);
        } catch (TimeoutException e) {
            log.warn("Timeout waiting for baud change confirmation");
        }
        
        changeBaud(baud);
        try {
            Thread.sleep(500);  // Allow baud rate to take effect
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Changes the local serial port baud rate.
     *
     * @param baud New baud rate
     * @throws SerialPortException if change fails
     */
    public void changeBaud(int baud) throws SerialPortException {
        portManager.setBaudRate(baud);
        notifyListeners(TerminalEvent.BAUD_CHANGED);
    }

    /**
     * Gets the data baud rate for downloads.
     *
     * @return Data baud rate
     */
    public int getDataBaud() {
        if (dataBaud != null) {
            return dataBaud;
        }
        return DEFAULT_DATA_BAUDS.getOrDefault(instrumentType, defaultBaud);
    }

    // ==================== Setup and Deployment ====================

    /**
     * Loads and validates commands from a command file.
     *
     * @param file Command file path
     * @return List of validated commands
     * @throws IOException if file cannot be read
     */
    public List<String> loadCommandFile(Path file) throws IOException {
        List<String> commands = new ArrayList<>();
        
        for (String line : Files.readAllLines(file)) {
            // Remove comments (after # or ;)
            int hashIdx = line.indexOf('#');
            if (hashIdx >= 0) line = line.substring(0, hashIdx);
            
            int semiIdx = line.indexOf(';');
            if (semiIdx >= 0) line = line.substring(0, semiIdx);
            
            // Remove BBTALK-style $ prefix
            int dollarIdx = line.indexOf('$');
            if (dollarIdx >= 0) line = line.substring(0, dollarIdx);
            
            line = line.trim();
            
            // Skip CK and CS commands (we add these manually)
            if (!line.isEmpty() && !line.startsWith("CK") && !line.startsWith("CS")) {
                commands.add(line);
            }
        }
        
        return commands;
    }

    /**
     * Sends setup commands and starts data collection.
     *
     * @param cmdFile Command file to send
     * @throws SerialPortException if communication fails
     * @throws TimeoutException    if no response
     * @throws IOException         if command file cannot be read
     */
    public void sendSetup(Path cmdFile) throws SerialPortException, IOException {
        wakeIfSleeping();
        
        log.info("Sending command file: {}", cmdFile);
        insertComment("Sending command file: " + cmdFile);
        
        List<String> commands = loadCommandFile(cmdFile);
        sendCommandsNoTimeout(commands);
        
        // Save parameters
        portManager.writeCommand("CK");
        try {
            waitFor("[Parameters saved as USER defaults]", 5000);
        } catch (TimeoutException e) {
            log.warn("Timeout waiting for CK confirmation");
        }
        
        // Start data collection
        portManager.writeCommand("CS");
        
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Data collection started");
        insertComment("Data collection started, " + getTimestamp());
        
        // Save deployment log
        Path logFile = logDirectory.resolve(makeFilename(".log"));
        appendToFile(logFile);
        insertComment("Deployment logfile written to " + logFile);
        
        // Keep connection open - don't call stopListening() here
        // The UI can disconnect manually if needed
    }

    /**
     * Initializes deployment/recovery by waking instrument and showing time.
     *
     * @throws SerialPortException if communication fails
     */
    public void startDeployRecover() throws SerialPortException {
        clearDisplay();
        insertComment("***************************** " + getTimestamp());
        wakeup();
        try {
            showTimeOk();
        } catch (TimeoutException e) {
            log.debug("Timeout checking time");
        }
        listRecorder();
    }

    // ==================== Data Download ====================

    /**
     * Finds the number of files recorded on the ADCP.
     *
     * @return Number of recorded files
     * @throws SerialPortException if communication fails
     * @throws TimeoutException    if no response
     */
    public int findNumberRecorded() throws SerialPortException, TimeoutException {
        try {
            sendCommands(List.of("RA"));
        } catch (TimeoutException e) {
            wakeup();
            sendCommands(List.of("RA"));
        }
        
        String response = getDisplayBuffer();
        // Parse RA response
        Pattern pattern = Pattern.compile("RA\\s+(\\d+)");
        Matcher matcher = pattern.matcher(response);
        
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        
        return 0;
    }

    /**
     * Starts a YModem download of a file from the ADCP.
     * Note: This requires external 'rb' (lrzsz) program on the system.
     *
     * @param fileNum File number to download
     * @throws SerialPortException if communication fails
     * @throws TimeoutException    if no response
     * @throws IOException         if download fails
     */
    public void ymodemDownload(int fileNum) throws SerialPortException, TimeoutException, IOException {
        // Create temporary download directory
        Path downloadDir = Files.createTempDirectory(dataDirectory, "ymodem_");
        insertComment("Initial download directory: " + downloadDir);
        
        int dbaud = getDataBaud();
        changeAllBaud(dbaud);
        
        portManager.writeCommand("RY" + fileNum);
        
        if (instrumentType == InstrumentType.WORKHORSE) {
            waitFor("now", 5000);
        }
        
        // CRITICAL: Stop the SerialReader thread before YModem transfer
        // Otherwise it will compete for serial data with the YModem handler
        serialReader.stop();
        
        // Clear any remaining data in the serial reader queue and port buffer
        serialReader.clearQueue();
        portManager.flushInput();
        
        log.info("Starting YModem download at {} baud", dbaud);
        insertComment("Starting YModem download at " + dbaud + " baud");
        
        // Use native Java YModem implementation
        YModemHandler ymodem = new YModemHandler(portManager);
        ymodem.setProgressListener(new YModemHandler.ProgressListener() {
            @Override
            public void onProgress(int bytesReceived, int totalBytes, String message) {
                log.debug("YModem progress: {} / {} - {}", bytesReceived, totalBytes, message);
            }
            
            @Override
            public void onComplete(Path downloadedFile) {
                log.info("YModem download complete: {}", downloadedFile);
            }
            
            @Override
            public void onError(String error) {
                log.error("YModem error: {}", error);
                insertComment("YModem error: " + error);
            }
        });
        
        Path downloadedFile;
        try {
            downloadedFile = ymodem.download(downloadDir);
            insertComment("Download complete: " + downloadedFile.getFileName());
            insertComment("Retries: " + ymodem.getRetryCount());
        } catch (IOException e) {
            insertComment("Download failed: " + e.getMessage());
            // Restore serial reader and communication
            serialReader.start();
            changeAllBaud(defaultBaud);
            throw e;
        }
        
        // Restore serial reader for normal terminal operation
        serialReader.start();
        changeAllBaud(defaultBaud);
        
        // Process the downloaded file
        finishDownload(downloadDir);
    }
    
    /**
     * Starts a YModem download with progress reporting.
     * Returns the downloaded file path for the UI to handle saving.
     *
     * @param fileNum File number to download
     * @param progressListener Listener for progress updates
     * @return Path to the downloaded file (in temp directory)
     * @throws SerialPortException if communication fails
     * @throws TimeoutException    if no response
     * @throws IOException         if download fails
     */
    public Path ymodemDownloadWithProgress(int fileNum, DownloadProgressListener progressListener) 
            throws SerialPortException, TimeoutException, IOException {
        // Create temporary download directory
        Path downloadDir = Files.createTempDirectory(dataDirectory, "ymodem_");
        insertComment("Download directory: " + downloadDir);
        
        int dbaud = getDataBaud();
        changeAllBaud(dbaud);
        
        portManager.writeCommand("RY" + fileNum);
        
        if (instrumentType == InstrumentType.WORKHORSE) {
            waitFor("now", 5000);
        }
        
        // CRITICAL: Stop the SerialReader thread before YModem transfer
        serialReader.stop();
        serialReader.clearQueue();
        portManager.flushInput();
        
        log.info("Starting YModem download at {} baud", dbaud);
        insertComment("Starting YModem download at " + dbaud + " baud");
        
        // Track block count for progress reporting
        final int[] blockCount = {0};
        final int[] errorCount = {0};
        
        // Use native Java YModem implementation with progress updates
        YModemHandler ymodem = new YModemHandler(portManager);
        currentYModemHandler = ymodem; // Store for cancellation
        
        ymodem.setProgressListener(new YModemHandler.ProgressListener() {
            @Override
            public void onProgress(int bytesReceived, int totalBytes, String message) {
                blockCount[0]++;
                if (progressListener != null) {
                    progressListener.onProgress(bytesReceived, totalBytes, blockCount[0], 
                            errorCount[0], message);
                }
                log.debug("YModem progress: block {} - {} / {} bytes", 
                        blockCount[0], bytesReceived, totalBytes);
            }
            
            @Override
            public void onComplete(Path downloadedFile) {
                log.info("YModem download complete: {}", downloadedFile);
                insertComment("Download complete: " + downloadedFile.getFileName());
            }
            
            @Override
            public void onError(String error) {
                errorCount[0]++;
                log.error("YModem error: {}", error);
                insertComment("YModem error: " + error);
                if (progressListener != null) {
                    progressListener.onError(error);
                }
            }
        });
        
        Path downloadedFile;
        try {
            downloadedFile = ymodem.download(downloadDir);
            insertComment("Download complete!");
            insertComment("Total blocks: " + blockCount[0]);
            insertComment("Retries: " + ymodem.getRetryCount());
        } catch (IOException e) {
            insertComment("Download failed: " + e.getMessage());
            serialReader.start();
            changeAllBaud(defaultBaud);
            currentYModemHandler = null;
            throw e;
        } finally {
            currentYModemHandler = null;
        }
        
        // Restore serial reader for normal terminal operation
        serialReader.start();
        changeAllBaud(defaultBaud);
        
        // Return the downloaded file - let the UI handle saving
        return downloadedFile;
    }
    
    /**
     * Cancels an ongoing YModem download.
     */
    public void cancelDownload() {
        YModemHandler handler = currentYModemHandler;
        if (handler != null) {
            log.info("Cancelling YModem download");
            handler.cancel();
        }
    }

    private void finishDownload(Path downloadDir) throws IOException {
        // Find downloaded file
        Optional<Path> downloadedFile = Files.list(downloadDir)
                .filter(Files::isRegularFile)
                .findFirst();
        
        if (downloadedFile.isEmpty()) {
            insertComment("No file found in download directory");
            return;
        }
        
        Path srcFile = downloadedFile.get();
        long fileSize = Files.size(srcFile);
        insertComment("Downloaded file " + srcFile.getFileName() + " has " + fileSize + " bytes");
        
        // Rename to final destination
        Path destFile = dataDirectory.resolve(makeFilename(dataFileExtension));
        Files.move(srcFile, destFile, StandardCopyOption.REPLACE_EXISTING);
        insertComment("File written as " + destFile);
        
        // Make read-only
        destFile.toFile().setReadOnly();
        
        // Backup if configured
        if (backupDirectory != null) {
            Files.createDirectories(backupDirectory);
            Path backupFile = backupDirectory.resolve(destFile.getFileName());
            Files.copy(destFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            insertComment("File backed up to " + backupFile);
        }
        
        // Save recovery log
        Path logFile = logDirectory.resolve(makeFilename(".log"));
        appendToFile(logFile);
        insertComment("Recovery logfile appended to " + logFile);
        
        // Clean up temp directory
        Files.deleteIfExists(downloadDir);
        
        try {
            sleep();
        } catch (SerialPortException e) {
            log.warn("Error putting instrument to sleep after download", e);
        }
    }

    // ==================== Buffer Management ====================

    /**
     * Waits for a specific string in data received AFTER this method is called.
     * <p>
     * This method is non-destructive: it does not delete matched content from
     * the display buffer, so the terminal display remains intact.  It only
     * searches data that arrived after the call, preventing stale matches.
     * <p>
     * The timeout is <em>inactivity-based</em>: the deadline resets every time
     * new data arrives on the serial port.  A command that produces a long,
     * slow response will never time out as long as bytes keep coming, but
     * silence for {@code timeoutMs} after the last byte will trigger the
     * timeout.
     * <p>
     * This method does NOT call {@link #updateBuffer()} itself — it relies on
     * the background update thread to move data from the serial reader queue
     * into the display buffer.  This avoids two threads competing to drain
     * the same queue, which causes choppy data flow with USB-to-serial
     * adapters.
     *
     * @param target    String to wait for
     * @param timeoutMs Inactivity timeout in milliseconds
     * @return Buffer contents received since the call, up to and including the target
     * @throws TimeoutException if target not found after {@code timeoutMs} of silence
     */
    public String waitFor(String target, int timeoutMs) throws TimeoutException {
        int startPos;
        synchronized (bufferLock) {
            startPos = displayBuffer.length();
        }

        long lastActivityTime = System.currentTimeMillis();

        while (true) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new TimeoutException("Cancelled");
            }
            if (System.currentTimeMillis() - lastActivityTime > timeoutMs) {
                throw new TimeoutException("Timeout waiting for: " + target);
            }

            synchronized (bufferLock) {
                int currentLen = displayBuffer.length();

                // Check if new data arrived — reset inactivity timer
                if (currentLen > startPos) {
                    lastActivityTime = System.currentTimeMillis();

                    // Search only in data that arrived after we started waiting
                    String newData = displayBuffer.substring(startPos);
                    int idx = newData.indexOf(target);
                    if (idx >= 0) {
                        return newData.substring(0, idx + target.length());
                    }
                }
            }

            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TimeoutException("Interrupted while waiting");
            }
        }
    }

    /**
     * Waits for target with streaming (doesn't fail on maxchar, just returns).
     */
    public String streamWaitFor(String target, int timeoutMs) throws TimeoutException {
        return waitFor(target, timeoutMs);
    }

    /**
     * Waits for the ">" prompt in data received AFTER this method is called.
     * <p>
     * This is an inactivity-based wait: the timeout resets every time new
     * data arrives from the instrument, so a command that produces a long
     * response won't time out prematurely.  The timeout only fires after
     * {@code timeoutMs} of complete silence.
     * <p>
     * Like {@link #waitFor}, this method is non-destructive, does not modify
     * the display buffer, and does NOT call {@link #updateBuffer()} — it
     * relies on the background update thread to feed the display buffer.
     *
     * @param timeoutMs Inactivity timeout in milliseconds
     * @throws TimeoutException if the prompt is not received after {@code timeoutMs} of silence
     */
    private void waitForPrompt(int timeoutMs) throws TimeoutException {
        int startPos;
        synchronized (bufferLock) {
            startPos = displayBuffer.length();
        }

        // Track the high-water mark so we only scan new characters
        int scannedUpTo = startPos;
        long lastActivityTime = System.currentTimeMillis();

        while (true) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new TimeoutException("Cancelled");
            }
            if (System.currentTimeMillis() - lastActivityTime > timeoutMs) {
                throw new TimeoutException("Timeout waiting for prompt (>)");
            }

            synchronized (bufferLock) {
                int currentLen = displayBuffer.length();

                // Check if new data arrived — reset inactivity timer
                if (currentLen > scannedUpTo) {
                    lastActivityTime = System.currentTimeMillis();

                    // Scan only the new characters
                    for (int i = scannedUpTo; i < currentLen; i++) {
                        if (displayBuffer.charAt(i) == '>') {
                            return; // Fresh prompt received
                        }
                    }
                    scannedUpTo = currentLen;
                }
            }

            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TimeoutException("Interrupted while waiting for prompt");
            }
        }
    }

    /**
     * Updates the buffer with data from serial reader.
     */
    private void updateBuffer() {
        byte[] data = serialReader.pollAll();
        if (data.length > 0) {
            synchronized (bufferLock) {
                // Remove \r characters, normalize line endings
                String text = new String(data, StandardCharsets.US_ASCII)
                        .replace("\r\n", "\n")
                        .replace("\r", "\n");
                displayBuffer.append(text);
                rawBuffer.write(data, 0, data.length);
            }
            notifyListeners(TerminalEvent.DATA_RECEIVED);
        }
    }

    /**
     * Clears the display and raw buffers.
     */
    public void clearBuffer() {
        synchronized (bufferLock) {
            displayBuffer.setLength(0);
            rawBuffer.reset();
        }
        serialReader.clearQueue();
    }

    /**
     * Clears just the display.
     */
    public void clearDisplay() {
        synchronized (bufferLock) {
            displayBuffer.setLength(0);
        }
    }

    /**
     * Gets the current display buffer contents.
     *
     * @return Display buffer as string
     */
    public String getDisplayBuffer() {
        synchronized (bufferLock) {
            return displayBuffer.toString();
        }
    }

    /**
     * Inserts a comment into the display (prefixed with #).
     *
     * @param message Comment message
     */
    public void insertComment(String message) {
        String[] lines = message.split("\n");
        StringBuilder commented = new StringBuilder();
        for (String line : lines) {
            commented.append("\n#").append(line);
        }
        commented.append("\n");
        
        synchronized (bufferLock) {
            displayBuffer.append(commented);
        }
        notifyListeners(TerminalEvent.DATA_RECEIVED);
    }

    // ==================== File Operations ====================

    /**
     * Generates a filename based on current settings.
     *
     * @param extension File extension
     * @return Generated filename
     */
    public String makeFilename(String extension) {
        return String.format("%s%s_%s%s%s",
                prefix, cruiseName, stationCast, suffix, extension);
    }

    /**
     * Appends current display buffer to a file.
     *
     * @param file File to append to
     * @throws IOException if write fails
     */
    public void appendToFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file, 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            out.write(getDisplayBuffer().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Begins saving all received data to a file.
     *
     * @param file File to save to
     * @throws IOException if file cannot be opened
     */
    public void beginSave(Path file) throws IOException {
        endSave();
        saveFile = Files.newOutputStream(file, 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        serialReader.setLogFile(saveFile);
        isSaving = true;
        log.info("Saving terminal IO to {}", file);
    }

    /**
     * Stops saving data to file.
     */
    public void endSave() {
        if (saveFile != null) {
            try {
                saveFile.close();
            } catch (IOException e) {
                log.warn("Error closing save file", e);
            }
            saveFile = null;
        }
        serialReader.setLogFile(null);
        isSaving = false;
    }

    // ==================== Update Thread ====================

    private Thread updateThread;

    private void startUpdateThread() {
        updateThread = new Thread(() -> {
            while (isListening) {
                updateBuffer();
                try {
                    // 20ms interval: this thread is the sole pipeline from the
                    // serial reader queue into the display buffer.  Keeping it
                    // responsive avoids lag during command-file sends where
                    // waitForPrompt() passively monitors the buffer.
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "TerminalUpdate");
        updateThread.setDaemon(true);
        updateThread.start();
    }

    // ==================== Utility Methods ====================

    /**
     * Gets current UTC timestamp.
     *
     * @return Formatted timestamp
     */
    public static String getTimestamp() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(LOG_TIME_FORMAT);
    }

    // ==================== Getters and Setters ====================

    public InstrumentType getInstrumentType() {
        return instrumentType;
    }

    public String getDevicePath() {
        return portManager.getDevicePath();
    }

    public int getBaudRate() {
        return portManager.getBaudRate();
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getSuffix() {
        return suffix;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }

    public String getCruiseName() {
        return cruiseName;
    }

    public void setCruiseName(String cruiseName) {
        this.cruiseName = cruiseName;
    }

    public String getStationCast() {
        return stationCast;
    }

    public void setStationCast(String stationCast) {
        this.stationCast = stationCast;
    }

    public Path getCommandFile() {
        return commandFile;
    }

    public void setCommandFile(Path commandFile) {
        this.commandFile = commandFile;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public void setDataDirectory(Path dataDirectory) {
        if (dataDirectory != null) {
            this.dataDirectory = dataDirectory;
        }
    }

    public Path getLogDirectory() {
        return logDirectory;
    }

    public void setLogDirectory(Path logDirectory) {
        if (logDirectory != null) {
            this.logDirectory = logDirectory;
        }
    }

    public Path getBackupDirectory() {
        return backupDirectory;
    }

    public void setBackupDirectory(Path backupDirectory) {
        this.backupDirectory = backupDirectory;
    }

    public boolean isSaving() {
        return isSaving;
    }

    /**
     * Gets the underlying serial port manager.
     *
     * @return Serial port manager
     */
    public SerialPortManager getPortManager() {
        return portManager;
    }

    // ==================== Listeners ====================

    public void addListener(TerminalListener listener) {
        listeners.add(listener);
    }

    public void removeListener(TerminalListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(TerminalEvent event) {
        for (TerminalListener listener : listeners) {
            try {
                listener.onTerminalEvent(event, this);
            } catch (Exception e) {
                log.warn("Error notifying listener", e);
            }
        }
    }

    // ==================== Cleanup ====================

    @Override
    public void close() {
        stopListening();
        endSave();
        serialReader.close();
        portManager.close();
    }

    // ==================== Inner Classes ====================

    /**
     * Terminal event types.
     */
    public enum TerminalEvent {
        CONNECTED,
        DISCONNECTED,
        DATA_RECEIVED,
        BAUD_CHANGED,
        INSTRUMENT_IDENTIFIED,
        ERROR
    }

    /**
     * Listener interface for terminal events.
     */
    public interface TerminalListener {
        void onTerminalEvent(TerminalEvent event, RDITerminal terminal);
    }
    
    /**
     * Listener interface for download progress updates.
     */
    public interface DownloadProgressListener {
        /**
         * Called when download progress is made.
         *
         * @param bytesReceived Total bytes received so far
         * @param totalBytes Total expected bytes (may be -1 if unknown)
         * @param blocksReceived Number of blocks received
         * @param errors Number of errors/retries so far
         * @param message Progress message
         */
        void onProgress(int bytesReceived, int totalBytes, int blocksReceived, int errors, String message);
        
        /**
         * Called when an error occurs during download.
         *
         * @param error Error message
         */
        void onError(String error);
    }

    /**
     * Time comparison result.
     */
    public static class TimeComparison {
        public final LocalDateTime pcTime;
        public final LocalDateTime adcpTime;
        public final long differenceSeconds;

        public TimeComparison(LocalDateTime pcTime, LocalDateTime adcpTime, long differenceSeconds) {
            this.pcTime = pcTime;
            this.adcpTime = adcpTime;
            this.differenceSeconds = differenceSeconds;
        }

        @Override
        public String toString() {
            return String.format(
                    "  PC time:   %s\nADCP time:   %s\nPC-ADCP:     %d seconds",
                    pcTime.format(LOG_TIME_FORMAT),
                    adcpTime.format(LOG_TIME_FORMAT),
                    differenceSeconds
            );
        }
    }
}
