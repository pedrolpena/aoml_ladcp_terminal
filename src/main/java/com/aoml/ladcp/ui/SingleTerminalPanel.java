package com.aoml.ladcp.ui;

import com.aoml.ladcp.serial.SerialPortException;
import com.aoml.ladcp.serial.SerialPortManager;
import com.aoml.ladcp.terminal.RDITerminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Self-contained terminal panel with its own RDI Terminal instance.
 * Used in the DualTerminalFrame for dual LADCP configurations.
 */
public class SingleTerminalPanel extends javax.swing.JPanel {
    private static final Logger log = LoggerFactory.getLogger(SingleTerminalPanel.class);

    private String terminalName;
    private RDITerminal terminal;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Future<?> currentTask;

    // Configuration
    private String devicePath;
    private int baudRate;
    private Path commandFile;
    private Path scriptsDirectory;
    private boolean autoScroll = true;
    
    // File naming prefix/suffix
    private String filePrefix = "";
    private String fileSuffix = "";
    
    // External status label (set by parent frame)
    private JLabel externalStatusLabel;
    
    // Listener for prefix/suffix changes
    private PrefixSuffixChangeListener prefixSuffixListener;
    
    // Listener for label toggle (swap Up-looker/Down-looker)
    private LabelToggleListener labelToggleListener;
    
    // UI components added programmatically
    private JButton wakeupButton;
    private JLabel lastCommandLabel;
    private JLabel terminalLabel;
    
    // Command history
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = 0;
    
    // Update timer
    private Timer updateTimer;
    
    /**
     * Interface for notifying parent of prefix/suffix changes.
     */
    public interface PrefixSuffixChangeListener {
        void onPrefixSuffixChanged(String terminalName, String prefix, String suffix);
    }
    
    /**
     * Interface for notifying parent of label toggle requests.
     */
    public interface LabelToggleListener {
        void onLabelToggleRequested(String terminalName);
    }

    /**
     * Creates new form SingleTerminalPanel.
     * Default constructor for NetBeans GUI builder.
     */
    public SingleTerminalPanel() {
        this.terminalName = "Terminal";
        this.devicePath = "/dev/ttyUSB0";
        this.baudRate = 9600;
        initComponents();
        initCustomComponents();
    }
    
    /**
     * Creates a new single terminal panel.
     *
     * @param name       Display name for this terminal
     * @param devicePath Serial device path
     * @param baudRate   Initial baud rate
     */
    public SingleTerminalPanel(String name, String devicePath, int baudRate) {
        this.terminalName = name;
        this.devicePath = devicePath;
        this.baudRate = baudRate;
        initComponents();
        initCustomComponents();
    }
    
    /**
     * Initialize custom components after NetBeans generated code.
     */
    private void initCustomComponents() {
        // Configure display area - terminal style
        displayArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        displayArea.setBackground(Color.BLACK);
        displayArea.setForeground(Color.GREEN);
        displayArea.setCaretColor(Color.GREEN);
        displayArea.setEditable(false);
        displayArea.setLineWrap(true);
        displayArea.setWrapStyleWord(false);
        
        // Add terminal name label to top right corner (with double-click to toggle)
        terminalLabel = new JLabel(terminalName);
        terminalLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        terminalLabel.setForeground(new Color(100, 200, 255)); // Light blue for visibility
        terminalLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 8));
        terminalLabel.setOpaque(true);
        terminalLabel.setBackground(new Color(40, 40, 40));
        terminalLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        terminalLabel.setToolTipText("Double-click to swap Up-looker/Down-looker");
        
        // Add double-click listener to toggle label
        terminalLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && labelToggleListener != null) {
                    labelToggleListener.onLabelToggleRequested(terminalName);
                }
            }
        });
        
        // Create a panel for wakeup button and terminal label
        JPanel topRightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        topRightPanel.setOpaque(false);
        
        // Add wakeup button
        wakeupButton = new JButton("Wakeup");
        wakeupButton.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        wakeupButton.setMargin(new Insets(2, 8, 2, 8));
        wakeupButton.setEnabled(false); // Disabled until connected
        wakeupButton.addActionListener(e -> {
            interruptAndExecute(() -> doWakeup());
        });
        topRightPanel.add(wakeupButton);
        topRightPanel.add(terminalLabel);
        
        // Add the panel to the top of the panel
        topPanel.add(topRightPanel, BorderLayout.EAST);
        
        // Configure command field
        commandField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        commandField.setEnabled(false);
        
        // Create a panel for the command row with wakeup button on right
        // The bottomPanel already has: WEST=cmdLabel, CENTER=commandField, SOUTH=statusLabel
        // We need to add a last command label below the status label
        
        // Create last command file label
        lastCommandLabel = new JLabel(" ");
        lastCommandLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        lastCommandLabel.setForeground(new Color(0, 100, 180)); // Dark blue for visibility
        lastCommandLabel.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        
        // Create a panel for status and last command
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusLabel, BorderLayout.NORTH);
        statusPanel.add(lastCommandLabel, BorderLayout.SOUTH);
        
        // Replace the statusLabel in bottomPanel with the new statusPanel
        bottomPanel.remove(statusLabel);
        bottomPanel.add(statusPanel, BorderLayout.SOUTH);
        
        // Add key listener for command history
        commandField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP) {
                    navigateHistory(-1);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    navigateHistory(1);
                    e.consume();
                }
            }
        });
        
        // Add mouse listener to redirect clicks on display to command field
        displayArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (commandField.isEnabled()) {
                    commandField.requestFocusInWindow();
                }
            }
        });
        
        // Add key listener to redirect typing on display to command field
        displayArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (commandField.isEnabled() && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                    // Transfer focus to command field and forward the typed character
                    commandField.requestFocusInWindow();
                    char c = e.getKeyChar();
                    if (c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c)) {
                        // Append the typed character to the command field
                        commandField.setText(commandField.getText() + c);
                        // Move caret to end
                        commandField.setCaretPosition(commandField.getText().length());
                    }
                    e.consume();
                }
            }
            
            @Override
            public void keyPressed(KeyEvent e) {
                if (commandField.isEnabled()) {
                    // Handle Enter key - transfer to command field
                    if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                        commandField.requestFocusInWindow();
                        e.consume();
                    }
                }
            }
        });
        
        // Initialize baud menu with radio buttons
        initBaudMenu();
        
        // Create update timer
        updateTimer = new Timer(100, e -> updateDisplay());
        
        // === Tooltips for all menu items, buttons, and controls ===
        
        // Connection menu
        connMenu.setToolTipText("Serial port connection controls");
        connectMenuItem.setToolTipText("Open the serial port and start communicating with the ADCP");
        disconnectMenuItem.setToolTipText("Close the serial port connection");
        selectPortMenuItem.setToolTipText("Choose a different serial port from the list of available ports");
        baudMenu.setToolTipText("Set the serial communication baud rate (default 9600 for commands)");
        
        // Command menu
        cmdMenu.setToolTipText("ADCP instrument commands");
        wakeupMenuItem.setToolTipText("Send a break signal to wake the ADCP from sleep mode");
        sleepMenuItem.setToolTipText("Send CZ command to put the ADCP into low-power sleep mode");
        setClockMenuItem.setToolTipText("Set the ADCP internal clock to the current PC time (UTC)");
        sendSetupMenuItem.setToolTipText("Load and send a .cmd setup file to configure the ADCP for data collection");
        showConfigMenuItem.setToolTipText("Query and display the current ADCP configuration parameters");
        diagnosticsMenuItem.setToolTipText("Run ADCP built-in self-tests (PS0, PT200)");
        listRecorderMenuItem.setToolTipText("Show files stored on the ADCP internal recorder");
        downloadMenuItem.setToolTipText("Download a specific file from the ADCP recorder via YModem");
        downloadAllMenuItem.setToolTipText("Download all recorded files from the ADCP recorder");
        cancelDownloadMenuItem.setToolTipText("Cancel the currently active YModem download");
        eraseMenuItem.setToolTipText("Erase all recorded data from the ADCP recorder (irreversible!)");
        sendBreakMenuItem.setToolTipText("Send a raw break signal on the serial line to wake the instrument");
        
        // View menu
        viewMenu.setToolTipText("Terminal display options");
        clearMenuItem.setToolTipText("Clear the terminal display (does not affect the instrument)");
        autoScrollCheckBox.setToolTipText("Automatically scroll to the latest output in the terminal");
        fileNamingMenuItem.setToolTipText("Configure the file prefix and suffix for this terminal's data files");
        
        // Deploy menu
        deployMenu.setToolTipText("Deployment workflow steps");
        deployInitMenuItem.setToolTipText("Wake instrument, check clock, and list recorder — run before each deployment");
        deploySetClockMenuItem.setToolTipText("Set the ADCP clock to current UTC time before deployment");
        deploySendSetupMenuItem.setToolTipText("Send the setup command file and start data collection (CS)");
        
        // Recover menu
        recoverMenu.setToolTipText("Recovery workflow steps");
        recoverInitMenuItem.setToolTipText("Wake instrument and check status after recovering from deployment");
        recoverDownloadMenuItem.setToolTipText("Download recorded data from the ADCP after recovery");
        
        // Wakeup button
        wakeupButton.setToolTipText("Send a break signal to wake the ADCP from sleep mode");
        
        // Command field and status
        commandField.setToolTipText("Type an ADCP command and press Enter to send (Up/Down for history)");
        statusLabel.setToolTipText("Current connection status: port, baud rate, and instrument type");
        
        // Initialize terminal
        initializeTerminal();
    }
    
    /**
     * Initialize the baud rate menu with radio buttons.
     */
    private void initBaudMenu() {
        ButtonGroup baudGroup = new ButtonGroup();
        baudMenu.removeAll();
        
        for (int baud : SerialPortManager.SUPPORTED_BAUD_RATES) {
            JRadioButtonMenuItem baudItem = new JRadioButtonMenuItem(String.valueOf(baud));
            baudItem.setSelected(baud == baudRate);
            final int b = baud;
            baudItem.addActionListener(e -> changeBaud(b));
            baudGroup.add(baudItem);
            baudMenu.add(baudItem);
        }
    }

    /**
     * Initializes the terminal with current settings.
     */
    private void initializeTerminal() {
        if (terminal != null) {
            terminal.close();
        }

        terminal = new RDITerminal.Builder()
                .devicePath(devicePath)
                .baudRate(baudRate)
                .prefix("ladcp")
                .suffix("")
                .cruiseName("XXNNNN")
                .stationCast("000_01")
                .build();

        terminal.addListener((event, t) -> SwingUtilities.invokeLater(() -> handleTerminalEvent(event)));
        updateStatus();
    }
    
    /**
     * Handles terminal events.
     */
    private void handleTerminalEvent(RDITerminal.TerminalEvent event) {
        switch (event) {
            case CONNECTED:
                commandField.setEnabled(true);
                updateStatus();
                break;
            case DISCONNECTED:
                commandField.setEnabled(false);
                updateStatus();
                break;
            case BAUD_CHANGED:
            case INSTRUMENT_IDENTIFIED:
                updateStatus();
                break;
        }
    }

    /**
     * Updates the status label.
     */
    private void updateStatus() {
        if (terminal == null) {
            statusLabel.setText("Not initialized");
            return;
        }

        String status = String.format("%s @ %d | %s | %s",
                devicePath,
                terminal.getBaudRate(),
                terminal.isConnected() ? "Connected" : "Disconnected",
                terminal.getInstrumentType().getCode());
        statusLabel.setText(status);

        if (externalStatusLabel != null) {
            externalStatusLabel.setText(terminalName + ": " + status);
        }
    }
    
    /**
     * Updates the display area with buffer contents.
     */
    private void updateDisplay() {
        if (terminal == null) return;

        String buffer = terminal.getDisplayBuffer();
        if (!buffer.equals(displayArea.getText())) {
            displayArea.setText(buffer);
            if (autoScroll) {
                displayArea.setCaretPosition(displayArea.getDocument().getLength());
            }
        }
    }
    
    /**
     * Navigate command history.
     */
    private void navigateHistory(int direction) {
        if (commandHistory.isEmpty()) return;

        historyIndex += direction;
        if (historyIndex < 0) {
            historyIndex = 0;
        } else if (historyIndex >= commandHistory.size()) {
            historyIndex = commandHistory.size();
            commandField.setText("");
            return;
        }

        commandField.setText(commandHistory.get(historyIndex));
    }

    // ==================== Public API ====================
    
    /**
     * Sets the configuration values from the main frame.
     */
    public void setConfig(String prefix, String suffix, String cruiseName, String stationCast) {
        this.filePrefix = prefix;
        this.fileSuffix = suffix;
        if (terminal != null) {
            terminal.setPrefix(prefix);
            terminal.setSuffix(suffix);
            terminal.setCruiseName(cruiseName);
            terminal.setStationCast(stationCast);
        }
    }
    
    /**
     * Sets the prefix/suffix change listener.
     */
    public void setPrefixSuffixChangeListener(PrefixSuffixChangeListener listener) {
        this.prefixSuffixListener = listener;
    }
    
    /**
     * Sets the log, download, and scripts directories.
     */
    public void setDirectories(Path logDir, Path downloadDir, Path scriptsDir) {
        this.scriptsDirectory = scriptsDir;
        if (terminal != null) {
            terminal.setLogDirectory(logDir);
            terminal.setDataDirectory(downloadDir);
        }
    }

    /**
     * Sets the external status label.
     */
    public void setStatusLabel(JLabel label) {
        this.externalStatusLabel = label;
        updateStatus();
    }

    /**
     * Starts the update timer.
     */
    public void startUpdating() {
        updateTimer.start();
    }

    /**
     * Stops the update timer.
     */
    public void stopUpdating() {
        updateTimer.stop();
    }
    
    /**
     * Gets the terminal instance.
     */
    public RDITerminal getTerminal() {
        return terminal;
    }

    /**
     * Connects to the serial port.
     */
    public void connect() {
        try {
            terminal.startListening();
            startUpdating();  // Start the display update timer
            wakeupButton.setEnabled(true);  // Enable wakeup button when connected
        } catch (SerialPortException e) {
            showError("Connection Error", e.getMessage());
        }
    }

    /**
     * Attempts to auto-connect to the serial port if available.
     * Does not show error dialogs if connection fails.
     * 
     * @return true if connection was successful
     */
    public boolean autoConnect() {
        // Check if the configured port is available
        Set<String> availablePorts = SerialPortManager.listAvailablePorts();
        if (!availablePorts.contains(devicePath)) {
            log.info("Auto-connect: port {} not available", devicePath);
            return false;
        }
        
        try {
            terminal.startListening();
            startUpdating();
            wakeupButton.setEnabled(true);  // Enable wakeup button when connected
            log.info("Auto-connected to {}", devicePath);
            return true;
        } catch (SerialPortException e) {
            log.info("Auto-connect failed for {}: {}", devicePath, e.getMessage());
            return false;
        }
    }

    /**
     * Disconnects from the serial port.
     */
    public void disconnect() {
        stopUpdating();  // Stop the display update timer
        terminal.stopListening();
        wakeupButton.setEnabled(false);  // Disable wakeup button when disconnected
    }

    /**
     * Clears the display.
     */
    public void clear() {
        displayArea.setText("");
        if (terminal != null) {
            terminal.clearDisplay();
        }
    }

    /**
     * Reconfigures the terminal with new settings.
     */
    public void reconfigure(String devicePath, int baudRate) {
        this.devicePath = devicePath;
        this.baudRate = baudRate;
        initializeTerminal();
        initBaudMenu();
    }

    /**
     * Shuts down this terminal panel.
     */
    public void shutdown() {
        stopUpdating();
        if (terminal != null) {
            terminal.close();
        }
        executor.shutdown();
    }

    /**
     * Executes a command asynchronously on the single-thread executor.
     */
    public void executeCommand(Runnable command) {
        currentTask = executor.submit(() -> {
            try {
                command.run();
            } catch (Exception e) {
                log.error("Command error on " + terminalName, e);
                showError("Command Error", e.getMessage());
            }
        });
    }

    /**
     * Cancels any running command sequence (e.g. a script send), then
     * executes the given command.  Used by wakeup and send-break so they
     * can preempt a long-running script.
     * <p>
     * This works by:
     * <ol>
     *   <li>Setting the terminal's cancellation flag so the command loop
     *       exits at the next iteration</li>
     *   <li>Interrupting the executor thread so any sleep/wait returns
     *       immediately</li>
     *   <li>Submitting the new command to the executor — it runs as soon as
     *       the cancelled task finishes (which is near-instant thanks to
     *       steps 1 and 2)</li>
     * </ol>
     */
    public void interruptAndExecute(Runnable command) {
        // Signal the terminal to stop sending commands
        if (terminal != null) {
            terminal.cancelPendingCommands();
        }
        // Interrupt the thread running the current task
        Future<?> task = currentTask;
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
        // Queue the new command — it will run once the cancelled task exits
        executeCommand(command);
    }

    // ==================== Private Command Actions ====================

    private void selectPort() {
        Set<String> ports = SerialPortManager.listAvailablePorts();
        String[] portArray = ports.toArray(new String[0]);

        if (portArray.length == 0) {
            showError("No Ports", "No serial ports found.");
            return;
        }

        String selected = (String) JOptionPane.showInputDialog(this,
                "Select serial port:",
                "Select Device",
                JOptionPane.QUESTION_MESSAGE,
                null,
                portArray,
                devicePath);

        if (selected != null && !selected.equals(devicePath)) {
            // Disconnect from current port if connected
            disconnect();
            
            devicePath = selected;
            reconfigure(devicePath, baudRate);
            
            // Notify parent of port change for persistence
            if (portChangeListener != null) {
                portChangeListener.onPortChanged(terminalName, devicePath);
            }
            
            // Auto-connect to the newly selected port
            autoConnect();
        }
    }

    private void changeBaud(int baud) {
        this.baudRate = baud;
        try {
            terminal.changeBaud(baud);
        } catch (SerialPortException e) {
            showError("Baud Change Error", e.getMessage());
        }
        updateStatus();
    }

    private void doWakeup() {
        try {
            terminal.wakeup();
        } catch (SerialPortException e) {
            throw new RuntimeException(e);
        }
    }

    private void doSleep() {
        try {
            terminal.sleep();
        } catch (SerialPortException e) {
            throw new RuntimeException(e);
        }
    }

    private void doSetClock() {
        try {
            terminal.setClock();
        } catch (SerialPortException e) {
            throw new RuntimeException(e);
        }
    }

    private void doShowConfig() {
        try {
            terminal.showConfig();
        } catch (SerialPortException e) {
            throw new RuntimeException(e);
        }
    }

    private void doDiagnostics() {
        try {
            terminal.runDiagnostics();
        } catch (SerialPortException e) {
            throw new RuntimeException(e);
        }
    }

    private void doListRecorder() {
        try {
            terminal.listRecorder();
        } catch (SerialPortException e) {
            throw new RuntimeException(e);
        }
    }

    private void doSendBreak() {
        try {
            terminal.startListening();
            terminal.wakeup();
        } catch (SerialPortException e) {
            throw new RuntimeException(e);
        }
    }

    private void doSendSetup() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Command files", "cmd"));
        
        // Use scripts directory as default, or last selected file
        if (commandFile != null) {
            chooser.setSelectedFile(commandFile.toFile());
        } else if (scriptsDirectory != null) {
            chooser.setCurrentDirectory(scriptsDirectory.toFile());
        }

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            commandFile = chooser.getSelectedFile().toPath();
            
            // Update the last command file label
            String fileName = commandFile.getFileName().toString();
            lastCommandLabel.setText("Last setup: " + fileName);
            
            executeCommand(() -> {
                try {
                    terminal.sendSetup(commandFile);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void doDownload() {
        executeCommand(() -> {
            try {
                int numRecorded = terminal.findNumberRecorded();

                SwingUtilities.invokeLater(() -> {
                    String input = JOptionPane.showInputDialog(this,
                            String.format("File to download (1 to %d):", numRecorded),
                            "Download",
                            JOptionPane.QUESTION_MESSAGE);

                    if (input != null) {
                        try {
                            int fileNum = Integer.parseInt(input);
                            if (fileNum >= 1 && fileNum <= numRecorded) {
                                executeCommand(() -> {
                                    try {
                                        // Create progress listener that updates the terminal display
                                        RDITerminal.DownloadProgressListener progressListener = 
                                            new RDITerminal.DownloadProgressListener() {
                                                @Override
                                                public void onProgress(int bytesReceived, int totalBytes, 
                                                                      int blocksReceived, int errors, String message) {
                                                    // Update terminal with progress
                                                    String progressMsg = String.format(
                                                        "Block %d received (%d bytes, %d errors)", 
                                                        blocksReceived, bytesReceived, errors);
                                                    terminal.insertComment(progressMsg);
                                                }
                                                
                                                @Override
                                                public void onError(String error) {
                                                    terminal.insertComment("ERROR: " + error);
                                                }
                                            };
                                        
                                        // Perform download with progress updates
                                        Path downloadedFile = terminal.ymodemDownloadWithProgress(fileNum, progressListener);
                                        
                                        // Show save dialog on EDT
                                        SwingUtilities.invokeLater(() -> {
                                            showSaveFileDialog(downloadedFile);
                                        });
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                            } else {
                                showError("Invalid Input", "File number must be between 1 and " + numRecorded);
                            }
                        } catch (NumberFormatException e) {
                            showError("Invalid Input", "Please enter a valid number");
                        }
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Shows a dialog allowing the user to save the downloaded file with a custom name.
     */
    private void showSaveFileDialog(Path downloadedFile) {
        if (downloadedFile == null || !Files.exists(downloadedFile)) {
            showError("Download Error", "Downloaded file not found.");
            return;
        }
        
        // Get suggested filename from terminal
        String suggestedName = terminal.makeFilename(".dat");
        
        // Create file chooser
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Downloaded File");
        chooser.setSelectedFile(new java.io.File(terminal.getDataDirectory().toFile(), suggestedName));
        chooser.setFileFilter(new FileNameExtensionFilter("Data files (*.dat)", "dat"));
        
        int result = chooser.showSaveDialog(this);
        
        if (result == JFileChooser.APPROVE_OPTION) {
            Path destFile = chooser.getSelectedFile().toPath();
            
            // Add .dat extension if not present
            if (!destFile.toString().toLowerCase().endsWith(".dat")) {
                destFile = Paths.get(destFile.toString() + ".dat");
            }
            
            // Handle file exists with rename option
            while (Files.exists(destFile)) {
                String[] options = {"Overwrite", "Rename", "Cancel"};
                int choice = JOptionPane.showOptionDialog(this,
                        "File already exists:\n" + destFile.getFileName() + "\n\nWhat would you like to do?",
                        "File Exists",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.WARNING_MESSAGE,
                        null,
                        options,
                        options[1]); // Default to "Rename"
                
                if (choice == 0) {
                    // Overwrite - break the loop and proceed
                    break;
                } else if (choice == 1) {
                    // Rename - show input dialog for new name
                    String currentName = destFile.getFileName().toString();
                    String newName = (String) JOptionPane.showInputDialog(this,
                            "Enter new filename:",
                            "Rename File",
                            JOptionPane.QUESTION_MESSAGE,
                            null,
                            null,
                            currentName);
                    
                    if (newName == null || newName.trim().isEmpty()) {
                        // User cancelled the rename, go back to options
                        continue;
                    }
                    
                    // Add .dat extension if not present
                    if (!newName.toLowerCase().endsWith(".dat")) {
                        newName = newName + ".dat";
                    }
                    
                    destFile = destFile.getParent().resolve(newName);
                    // Loop will check if new name exists
                } else {
                    // Cancel - clean up and return
                    try {
                        Files.deleteIfExists(downloadedFile);
                        Files.deleteIfExists(downloadedFile.getParent());
                    } catch (IOException e) {
                        log.warn("Error cleaning up temp file", e);
                    }
                    return;
                }
            }
            
            try {
                // Move file to destination
                Files.move(downloadedFile, destFile, StandardCopyOption.REPLACE_EXISTING);
                
                // Make read-only
                destFile.toFile().setReadOnly();
                
                long fileSize = Files.size(destFile);
                terminal.insertComment("File saved as: " + destFile);
                terminal.insertComment("File size: " + fileSize + " bytes");
                
                // Handle backup if configured
                Path backupDir = terminal.getBackupDirectory();
                if (backupDir != null) {
                    Files.createDirectories(backupDir);
                    Path backupFile = backupDir.resolve(destFile.getFileName());
                    Files.copy(destFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
                    terminal.insertComment("File backed up to: " + backupFile);
                }
                
                // Save recovery log
                Path logFile = terminal.getLogDirectory().resolve(
                        destFile.getFileName().toString().replace(".dat", ".log"));
                terminal.appendToFile(logFile);
                terminal.insertComment("Recovery logfile written to: " + logFile);
                
                JOptionPane.showMessageDialog(this,
                        "File saved successfully:\n" + destFile,
                        "Download Complete",
                        JOptionPane.INFORMATION_MESSAGE);
                
                // Put instrument to sleep
                executeCommand(() -> {
                    try {
                        terminal.sleep();
                    } catch (Exception e) {
                        log.warn("Error putting instrument to sleep", e);
                    }
                });
                
            } catch (IOException e) {
                log.error("Error saving file", e);
                showError("Save Error", "Failed to save file: " + e.getMessage());
            }
        } else {
            // User cancelled - clean up temp file
            try {
                Files.deleteIfExists(downloadedFile);
                // Also try to delete parent temp directory
                Files.deleteIfExists(downloadedFile.getParent());
            } catch (IOException e) {
                log.warn("Error cleaning up temp file", e);
            }
        }
    }

    private void doEraseRecorder() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to ERASE the recorder?\nThis cannot be undone!",
                "Confirm Erase",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            executeCommand(() -> {
                try {
                    terminal.eraseRecorder();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void showError(String title, String message) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE));
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        localMenuBar = new javax.swing.JMenuBar();
        connMenu = new javax.swing.JMenu();
        connectMenuItem = new javax.swing.JMenuItem();
        disconnectMenuItem = new javax.swing.JMenuItem();
        jSeparator1 = new javax.swing.JPopupMenu.Separator();
        selectPortMenuItem = new javax.swing.JMenuItem();
        baudMenu = new javax.swing.JMenu();
        cmdMenu = new javax.swing.JMenu();
        wakeupMenuItem = new javax.swing.JMenuItem();
        sleepMenuItem = new javax.swing.JMenuItem();
        setClockMenuItem = new javax.swing.JMenuItem();
        jSeparator2 = new javax.swing.JPopupMenu.Separator();
        sendSetupMenuItem = new javax.swing.JMenuItem();
        showConfigMenuItem = new javax.swing.JMenuItem();
        diagnosticsMenuItem = new javax.swing.JMenuItem();
        jSeparator3 = new javax.swing.JPopupMenu.Separator();
        listRecorderMenuItem = new javax.swing.JMenuItem();
        downloadMenuItem = new javax.swing.JMenuItem();
        downloadAllMenuItem = new javax.swing.JMenuItem();
        cancelDownloadMenuItem = new javax.swing.JMenuItem();
        eraseMenuItem = new javax.swing.JMenuItem();
        jSeparator4 = new javax.swing.JPopupMenu.Separator();
        sendBreakMenuItem = new javax.swing.JMenuItem();
        viewMenu = new javax.swing.JMenu();
        clearMenuItem = new javax.swing.JMenuItem();
        autoScrollCheckBox = new javax.swing.JCheckBoxMenuItem();
        deployMenu = new javax.swing.JMenu();
        deployInitMenuItem = new javax.swing.JMenuItem();
        deploySetClockMenuItem = new javax.swing.JMenuItem();
        deploySendSetupMenuItem = new javax.swing.JMenuItem();
        recoverMenu = new javax.swing.JMenu();
        recoverInitMenuItem = new javax.swing.JMenuItem();
        recoverDownloadMenuItem = new javax.swing.JMenuItem();
        topPanel = new javax.swing.JPanel();
        displayScrollPane = new javax.swing.JScrollPane();
        displayArea = new javax.swing.JTextArea();
        bottomPanel = new javax.swing.JPanel();
        cmdLabel = new javax.swing.JLabel();
        commandField = new javax.swing.JTextField();
        statusLabel = new javax.swing.JLabel();

        connMenu.setText("Conn");

        connectMenuItem.setText("Connect");
        connectMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                connectMenuItemActionPerformed(evt);
            }
        });
        connMenu.add(connectMenuItem);

        disconnectMenuItem.setText("Disconnect");
        disconnectMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                disconnectMenuItemActionPerformed(evt);
            }
        });
        connMenu.add(disconnectMenuItem);
        connMenu.add(jSeparator1);

        selectPortMenuItem.setText("Select Port...");
        selectPortMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                selectPortMenuItemActionPerformed(evt);
            }
        });
        connMenu.add(selectPortMenuItem);

        localMenuBar.add(connMenu);

        baudMenu.setText("Baud");
        localMenuBar.add(baudMenu);

        cmdMenu.setText("Cmd");

        wakeupMenuItem.setText("Wakeup");
        wakeupMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                wakeupMenuItemActionPerformed(evt);
            }
        });
        cmdMenu.add(wakeupMenuItem);

        sleepMenuItem.setText("Sleep");
        sleepMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                sleepMenuItemActionPerformed(evt);
            }
        });
        cmdMenu.add(sleepMenuItem);

        setClockMenuItem.setText("Set Clock");
        setClockMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                setClockMenuItemActionPerformed(evt);
            }
        });
        cmdMenu.add(setClockMenuItem);
        cmdMenu.add(jSeparator2);

        sendSetupMenuItem.setText("Send Setup...");
        sendSetupMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                sendSetupMenuItemActionPerformed(evt);
            }
        });
        cmdMenu.add(sendSetupMenuItem);

        showConfigMenuItem.setText("Show Config");
        showConfigMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                showConfigMenuItemActionPerformed(evt);
            }
        });
        cmdMenu.add(showConfigMenuItem);

        diagnosticsMenuItem.setText("Diagnostics");
        diagnosticsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                diagnosticsMenuItemActionPerformed(evt);
            }
        });
        cmdMenu.add(diagnosticsMenuItem);
        cmdMenu.add(jSeparator3);

        listRecorderMenuItem.setText("List Recorder");
        listRecorderMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                listRecorderMenuItemActionPerformed(evt);
            }
        });
        cmdMenu.add(listRecorderMenuItem);

        downloadMenuItem.setText("Download...");
        downloadMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                downloadMenuItemActionPerformed(evt);
            }
        });
        cmdMenu.add(downloadMenuItem);

        downloadAllMenuItem.setText("Download All Files");
        downloadAllMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                downloadAllMenuItemActionPerformed(evt);
            }
        });
        cmdMenu.add(downloadAllMenuItem);

        cancelDownloadMenuItem.setText("Cancel Download");
        cancelDownloadMenuItem.setEnabled(false);
        cancelDownloadMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cancelDownloadMenuItemActionPerformed(evt);
            }
        });
        cmdMenu.add(cancelDownloadMenuItem);

        eraseMenuItem.setText("Erase Recorder");
        eraseMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                eraseMenuItemActionPerformed(evt);
            }
        });
        cmdMenu.add(eraseMenuItem);
        cmdMenu.add(jSeparator4);

        sendBreakMenuItem.setText("Send Break");
        sendBreakMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                sendBreakMenuItemActionPerformed(evt);
            }
        });
        cmdMenu.add(sendBreakMenuItem);

        localMenuBar.add(cmdMenu);

        viewMenu.setText("View");

        clearMenuItem.setText("Clear");
        clearMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                clearMenuItemActionPerformed(evt);
            }
        });
        viewMenu.add(clearMenuItem);

        autoScrollCheckBox.setSelected(true);
        autoScrollCheckBox.setText("Auto-scroll");
        autoScrollCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                autoScrollCheckBoxActionPerformed(evt);
            }
        });
        viewMenu.add(autoScrollCheckBox);
        
        viewMenu.addSeparator();
        
        // File Naming menu item
        fileNamingMenuItem = new javax.swing.JMenuItem("File Naming...");
        fileNamingMenuItem.addActionListener(e -> showFileNamingDialog());
        viewMenu.add(fileNamingMenuItem);

        localMenuBar.add(viewMenu);

        // Deploy Menu
        deployMenu.setText("Deploy");

        deployInitMenuItem.setText("Deployment Initialization");
        deployInitMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                deployInitMenuItemActionPerformed(evt);
            }
        });
        deployMenu.add(deployInitMenuItem);

        deploySetClockMenuItem.setText("Set Clock");
        deploySetClockMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                deploySetClockMenuItemActionPerformed(evt);
            }
        });
        deployMenu.add(deploySetClockMenuItem);

        deploySendSetupMenuItem.setText("Send Setup and Start");
        deploySendSetupMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                deploySendSetupMenuItemActionPerformed(evt);
            }
        });
        deployMenu.add(deploySendSetupMenuItem);

        localMenuBar.add(deployMenu);

        // Recover Menu
        recoverMenu.setText("Recover");

        recoverInitMenuItem.setText("Recovery Initialization");
        recoverInitMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                recoverInitMenuItemActionPerformed(evt);
            }
        });
        recoverMenu.add(recoverInitMenuItem);

        recoverDownloadMenuItem.setText("Download");
        recoverDownloadMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                recoverDownloadMenuItemActionPerformed(evt);
            }
        });
        recoverMenu.add(recoverDownloadMenuItem);

        localMenuBar.add(recoverMenu);

        setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 5, 5, 5));
        setLayout(new java.awt.BorderLayout(5, 5));

        topPanel.setLayout(new java.awt.BorderLayout());
        topPanel.add(localMenuBar, java.awt.BorderLayout.NORTH);

        add(topPanel, java.awt.BorderLayout.NORTH);

        displayScrollPane.setVerticalScrollBarPolicy(javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        displayScrollPane.setPreferredSize(new java.awt.Dimension(500, 300));

        displayArea.setColumns(20);
        displayArea.setRows(5);
        displayScrollPane.setViewportView(displayArea);

        add(displayScrollPane, java.awt.BorderLayout.CENTER);

        bottomPanel.setLayout(new java.awt.BorderLayout(5, 5));

        cmdLabel.setText("Cmd:");
        bottomPanel.add(cmdLabel, java.awt.BorderLayout.WEST);

        commandField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                commandFieldActionPerformed(evt);
            }
        });
        bottomPanel.add(commandField, java.awt.BorderLayout.CENTER);

        statusLabel.setText("Not connected");
        statusLabel.setBorder(javax.swing.BorderFactory.createLoweredBevelBorder());
        bottomPanel.add(statusLabel, java.awt.BorderLayout.SOUTH);

        add(bottomPanel, java.awt.BorderLayout.SOUTH);
    }// </editor-fold>//GEN-END:initComponents

    private void connectMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_connectMenuItemActionPerformed
        connect();
    }//GEN-LAST:event_connectMenuItemActionPerformed

    private void disconnectMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_disconnectMenuItemActionPerformed
        disconnect();
    }//GEN-LAST:event_disconnectMenuItemActionPerformed

    private void selectPortMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_selectPortMenuItemActionPerformed
        selectPort();
    }//GEN-LAST:event_selectPortMenuItemActionPerformed

    private void wakeupMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_wakeupMenuItemActionPerformed
        interruptAndExecute(this::doWakeup);
    }//GEN-LAST:event_wakeupMenuItemActionPerformed

    private void sleepMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_sleepMenuItemActionPerformed
        executeCommand(this::doSleep);
    }//GEN-LAST:event_sleepMenuItemActionPerformed

    private void setClockMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_setClockMenuItemActionPerformed
        executeCommand(this::doSetClock);
    }//GEN-LAST:event_setClockMenuItemActionPerformed

    private void sendSetupMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_sendSetupMenuItemActionPerformed
        doSendSetup();
    }//GEN-LAST:event_sendSetupMenuItemActionPerformed

    private void showConfigMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_showConfigMenuItemActionPerformed
        executeCommand(this::doShowConfig);
    }//GEN-LAST:event_showConfigMenuItemActionPerformed

    private void diagnosticsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_diagnosticsMenuItemActionPerformed
        executeCommand(this::doDiagnostics);
    }//GEN-LAST:event_diagnosticsMenuItemActionPerformed

    private void listRecorderMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_listRecorderMenuItemActionPerformed
        executeCommand(this::doListRecorder);
    }//GEN-LAST:event_listRecorderMenuItemActionPerformed

    private void downloadMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_downloadMenuItemActionPerformed
        doDownload();
    }//GEN-LAST:event_downloadMenuItemActionPerformed

    private void eraseMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_eraseMenuItemActionPerformed
        doEraseRecorder();
    }//GEN-LAST:event_eraseMenuItemActionPerformed

    private void sendBreakMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_sendBreakMenuItemActionPerformed
        interruptAndExecute(this::doSendBreak);
    }//GEN-LAST:event_sendBreakMenuItemActionPerformed

    private void clearMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_clearMenuItemActionPerformed
        clear();
    }//GEN-LAST:event_clearMenuItemActionPerformed

    private void autoScrollCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_autoScrollCheckBoxActionPerformed
        autoScroll = autoScrollCheckBox.isSelected();
    }//GEN-LAST:event_autoScrollCheckBoxActionPerformed

    private void commandFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_commandFieldActionPerformed
        String command = commandField.getText().trim();
        if (command.isEmpty() || terminal == null) return;

        try {
            terminal.getPortManager().writeCommand(command);
            commandHistory.add(command);
            historyIndex = commandHistory.size();
            commandField.setText("");
        } catch (Exception e) {
            log.error("Error sending command", e);
            showError("Send Error", e.getMessage());
        }
    }//GEN-LAST:event_commandFieldActionPerformed

    private void deployInitMenuItemActionPerformed(java.awt.event.ActionEvent evt) {
        // Deployment Initialization - clears display, wakes instrument, shows time, lists recorder
        clear();
        interruptAndExecute(() -> {
            try {
                terminal.wakeup();
                RDITerminal.TimeComparison timeComparison = terminal.showTimeOk();
                terminal.listRecorder();
                
                // Show time difference dialog on EDT
                SwingUtilities.invokeLater(() -> {
                    showTimeComparisonDialog(timeComparison);
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void deploySetClockMenuItemActionPerformed(java.awt.event.ActionEvent evt) {
        executeCommand(this::doSetClock);
    }

    private void deploySendSetupMenuItemActionPerformed(java.awt.event.ActionEvent evt) {
        doSendSetup();
    }

    private void recoverInitMenuItemActionPerformed(java.awt.event.ActionEvent evt) {
        // Recovery Initialization - clears display, wakes instrument, shows time, lists recorder
        clear();
        interruptAndExecute(() -> {
            try {
                terminal.wakeup();
                RDITerminal.TimeComparison timeComparison = terminal.showTimeOk();
                terminal.listRecorder();
                
                // Show time difference dialog on EDT
                SwingUtilities.invokeLater(() -> {
                    showTimeComparisonDialog(timeComparison);
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Shows a dialog displaying the time comparison between PC and ADCP.
     */
    private void showTimeComparisonDialog(RDITerminal.TimeComparison timeComparison) {
        String message = String.format(
                "PC Time (UTC):     %s\n" +
                "ADCP Time:         %s\n" +
                "Difference:        %d seconds",
                timeComparison.pcTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd  HH:mm:ss")),
                timeComparison.adcpTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd  HH:mm:ss")),
                timeComparison.differenceSeconds
        );
        
        if (Math.abs(timeComparison.differenceSeconds) > 60) {
            message += "\n\n⚠ Warning: Time difference exceeds 1 minute!";
        }
        
        JOptionPane.showMessageDialog(this,
                message,
                "Time Comparison: " + terminalName,
                Math.abs(timeComparison.differenceSeconds) > 60 
                    ? JOptionPane.WARNING_MESSAGE 
                    : JOptionPane.INFORMATION_MESSAGE);
    }

    private void recoverDownloadMenuItemActionPerformed(java.awt.event.ActionEvent evt) {
        doDownload();
    }
    
    private void downloadAllMenuItemActionPerformed(java.awt.event.ActionEvent evt) {
        doDownloadAll();
    }
    
    private void cancelDownloadMenuItemActionPerformed(java.awt.event.ActionEvent evt) {
        doCancelDownload();
    }
    
    /**
     * Downloads all files from the recorder using their original names.
     */
    private void doDownloadAll() {
        executeCommand(() -> {
            try {
                int numRecorded = terminal.findNumberRecorded();
                
                if (numRecorded == 0) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                "No files recorded on the instrument.",
                                "Download All",
                                JOptionPane.INFORMATION_MESSAGE);
                    });
                    return;
                }
                
                // List files on the recorder before downloading
                terminal.insertComment("\n========== Files on Recorder ==========");
                terminal.listRecorder();
                terminal.insertComment("========================================\n");
                
                // Short pause to let the list display
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Confirm download
                final int[] confirm = {JOptionPane.NO_OPTION};
                SwingUtilities.invokeAndWait(() -> {
                    confirm[0] = JOptionPane.showConfirmDialog(this,
                            String.format("Download all %d files from the recorder?\n\n" +
                                    "Files will be saved with their original names\n" +
                                    "to: %s", numRecorded, terminal.getDataDirectory()),
                            "Download All Files",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);
                });
                
                if (confirm[0] != JOptionPane.YES_OPTION) {
                    return;
                }
                
                // Set download state - use invokeAndWait to ensure menu is enabled before download starts
                downloadInProgress = true;
                downloadCancelled = false;
                SwingUtilities.invokeAndWait(() -> {
                    cancelDownloadMenuItem.setEnabled(true);
                    downloadMenuItem.setEnabled(false);
                    downloadAllMenuItem.setEnabled(false);
                });
                
                terminal.insertComment("Starting download of all " + numRecorded + " files...");
                
                int successCount = 0;
                int failCount = 0;
                
                for (int fileNum = 1; fileNum <= numRecorded && !downloadCancelled; fileNum++) {
                    terminal.insertComment("\n========== Downloading file " + fileNum + " of " + numRecorded + " ==========");
                    
                    try {
                        // Download with progress updates
                        final int currentFile = fileNum;
                        RDITerminal.DownloadProgressListener progressListener = 
                            new RDITerminal.DownloadProgressListener() {
                                @Override
                                public void onProgress(int bytesReceived, int totalBytes, 
                                                      int blocksReceived, int errors, String message) {
                                    terminal.insertComment(String.format(
                                        "File %d: Block %d (%d bytes, %d errors)", 
                                        currentFile, blocksReceived, bytesReceived, errors));
                                }
                                
                                @Override
                                public void onError(String error) {
                                    terminal.insertComment("ERROR: " + error);
                                }
                            };
                        
                        Path downloadedFile = terminal.ymodemDownloadWithProgress(fileNum, progressListener);
                        
                        if (downloadCancelled) {
                            // Clean up temp file
                            try {
                                Files.deleteIfExists(downloadedFile);
                                Files.deleteIfExists(downloadedFile.getParent());
                            } catch (IOException e) {
                                log.warn("Error cleaning up temp file", e);
                            }
                            break;
                        }
                        
                        // Move to data directory with original name
                        String originalName = downloadedFile.getFileName().toString();
                        Path destFile = terminal.getDataDirectory().resolve(originalName);
                        
                        // If file exists, add a suffix
                        int suffix = 1;
                        while (Files.exists(destFile)) {
                            String baseName = originalName;
                            String ext = "";
                            int dotIdx = originalName.lastIndexOf('.');
                            if (dotIdx > 0) {
                                baseName = originalName.substring(0, dotIdx);
                                ext = originalName.substring(dotIdx);
                            }
                            destFile = terminal.getDataDirectory().resolve(baseName + "_" + suffix + ext);
                            suffix++;
                        }
                        
                        Files.move(downloadedFile, destFile, StandardCopyOption.REPLACE_EXISTING);
                        destFile.toFile().setReadOnly();
                        
                        // Clean up temp directory
                        try {
                            Files.deleteIfExists(downloadedFile.getParent());
                        } catch (IOException e) {
                            // Ignore
                        }
                        
                        terminal.insertComment("File saved as: " + destFile);
                        successCount++;
                        
                    } catch (Exception e) {
                        terminal.insertComment("Failed to download file " + fileNum + ": " + e.getMessage());
                        failCount++;
                        log.error("Error downloading file " + fileNum, e);
                    }
                }
                
                // Reset download state
                downloadInProgress = false;
                SwingUtilities.invokeLater(() -> {
                    cancelDownloadMenuItem.setEnabled(false);
                    downloadMenuItem.setEnabled(true);
                    downloadAllMenuItem.setEnabled(true);
                });
                
                // Show summary
                final int success = successCount;
                final int fail = failCount;
                final boolean cancelled = downloadCancelled;
                SwingUtilities.invokeLater(() -> {
                    String message;
                    if (cancelled) {
                        message = String.format("Download cancelled.\n\nDownloaded: %d files\nFailed: %d files", 
                                success, fail);
                    } else {
                        message = String.format("Download complete.\n\nDownloaded: %d files\nFailed: %d files", 
                                success, fail);
                    }
                    JOptionPane.showMessageDialog(this, message, "Download All Complete",
                            fail > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
                });
                
                terminal.insertComment("\nDownload all complete. Success: " + successCount + ", Failed: " + failCount);
                
                // Put instrument to sleep
                try {
                    terminal.sleep();
                } catch (Exception e) {
                    log.warn("Error putting instrument to sleep", e);
                }
                
            } catch (Exception e) {
                downloadInProgress = false;
                SwingUtilities.invokeLater(() -> {
                    cancelDownloadMenuItem.setEnabled(false);
                    downloadMenuItem.setEnabled(true);
                    downloadAllMenuItem.setEnabled(true);
                });
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Cancels an ongoing download and sends wakeup command.
     */
    private void doCancelDownload() {
        if (!downloadInProgress) {
            return;
        }
        
        int confirm = JOptionPane.showConfirmDialog(this,
                "Cancel the current download?\n\nThis will abort the transfer and wake up the instrument.",
                "Cancel Download",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        
        if (confirm == JOptionPane.YES_OPTION) {
            downloadCancelled = true;
            terminal.insertComment("\n*** DOWNLOAD CANCELLED BY USER ***");
            
            // Cancel the YModem transfer - this sets a flag that will be checked during read operations
            terminal.cancelDownload();
            
            // Wake up the instrument in a new thread (not the executor which may be blocked)
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // Give time for cancel to take effect
                    terminal.wakeup();
                    terminal.insertComment("Instrument woken up after cancel.");
                } catch (Exception e) {
                    log.error("Error waking up after cancel", e);
                }
            }, "CancelWakeup-" + terminalName).start();
        }
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JCheckBoxMenuItem autoScrollCheckBox;
    private javax.swing.JMenu baudMenu;
    private javax.swing.JPanel bottomPanel;
    private javax.swing.JMenuItem cancelDownloadMenuItem;
    private javax.swing.JMenuItem clearMenuItem;
    private javax.swing.JLabel cmdLabel;
    private javax.swing.JMenu cmdMenu;
    private javax.swing.JTextField commandField;
    private javax.swing.JMenu connMenu;
    private javax.swing.JMenuItem connectMenuItem;
    private javax.swing.JMenu deployMenu;
    private javax.swing.JMenuItem deployInitMenuItem;
    private javax.swing.JMenuItem deploySendSetupMenuItem;
    private javax.swing.JMenuItem deploySetClockMenuItem;
    private javax.swing.JMenuItem diagnosticsMenuItem;
    private javax.swing.JMenuItem disconnectMenuItem;
    private javax.swing.JTextArea displayArea;
    private javax.swing.JScrollPane displayScrollPane;
    private javax.swing.JMenuItem downloadAllMenuItem;
    private javax.swing.JMenuItem downloadMenuItem;
    private javax.swing.JMenuItem eraseMenuItem;
    private javax.swing.JPopupMenu.Separator jSeparator1;
    private javax.swing.JPopupMenu.Separator jSeparator2;
    private javax.swing.JPopupMenu.Separator jSeparator3;
    private javax.swing.JPopupMenu.Separator jSeparator4;
    private javax.swing.JMenuItem listRecorderMenuItem;
    private javax.swing.JMenuBar localMenuBar;
    private javax.swing.JMenuItem recoverDownloadMenuItem;
    private javax.swing.JMenuItem recoverInitMenuItem;
    private javax.swing.JMenu recoverMenu;
    private javax.swing.JMenuItem selectPortMenuItem;
    private javax.swing.JMenuItem sendBreakMenuItem;
    private javax.swing.JMenuItem sendSetupMenuItem;
    private javax.swing.JMenuItem setClockMenuItem;
    private javax.swing.JMenuItem showConfigMenuItem;
    private javax.swing.JMenuItem sleepMenuItem;
    private javax.swing.JLabel statusLabel;
    private javax.swing.JPanel topPanel;
    private javax.swing.JMenu viewMenu;
    private javax.swing.JMenuItem wakeupMenuItem;
    // End of variables declaration//GEN-END:variables
    
    // Additional state for download management
    private volatile boolean downloadInProgress = false;
    private volatile boolean downloadCancelled = false;
    
    // Port change listener
    private PortChangeListener portChangeListener;
    
    // Color scheme menu items - kept for potential future use but not created
    private javax.swing.JRadioButtonMenuItem greenSchemeItem;
    private javax.swing.JRadioButtonMenuItem amberSchemeItem;
    private javax.swing.JRadioButtonMenuItem whiteSchemeItem;
    
    /**
     * Interface for notifying parent of port changes.
     */
    public interface PortChangeListener {
        void onPortChanged(String terminalName, String newPort);
    }
    
    /**
     * Sets the port change listener.
     */
    public void setPortChangeListener(PortChangeListener listener) {
        this.portChangeListener = listener;
    }
    
    /**
     * Gets the current device path.
     */
    public String getDevicePath() {
        return devicePath;
    }
    
    /**
     * Sets the color scheme for the terminal display.
     * Called from parent frame to apply common color scheme to all terminals.
     */
    public void setColorScheme(String scheme) {
        Color foreground;
        Color caret;
        
        switch (scheme) {
            case "amber":
                foreground = new Color(255, 176, 0); // Amber/orange
                caret = foreground;
                if (amberSchemeItem != null) amberSchemeItem.setSelected(true);
                break;
            case "white":
                foreground = Color.WHITE;
                caret = Color.WHITE;
                if (whiteSchemeItem != null) whiteSchemeItem.setSelected(true);
                break;
            case "green":
            default:
                foreground = Color.GREEN;
                caret = Color.GREEN;
                if (greenSchemeItem != null) greenSchemeItem.setSelected(true);
                break;
        }
        
        displayArea.setForeground(foreground);
        displayArea.setCaretColor(caret);
        displayArea.repaint();
    }
    
    /**
     * Shows a dialog to configure file naming prefix and suffix.
     */
    private void showFileNamingDialog() {
        // Create a panel with fields for prefix and suffix
        JPanel panel = new JPanel(new GridLayout(3, 2, 5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        JTextField prefixField = new JTextField(filePrefix, 10);
        JTextField suffixField = new JTextField(fileSuffix, 10);
        
        panel.add(new JLabel("File Prefix:"));
        panel.add(prefixField);
        panel.add(new JLabel("File Suffix:"));
        panel.add(suffixField);
        panel.add(new JLabel(""));
        panel.add(new JLabel("(Applied to downloaded files)"));
        
        int result = JOptionPane.showConfirmDialog(this, panel,
                "File Naming Settings - " + terminalName,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        
        if (result == JOptionPane.OK_OPTION) {
            filePrefix = prefixField.getText().trim();
            fileSuffix = suffixField.getText().trim();
            
            // Update terminal
            if (terminal != null) {
                terminal.setPrefix(filePrefix);
                terminal.setSuffix(fileSuffix);
            }
            
            // Notify listener to persist changes
            if (prefixSuffixListener != null) {
                prefixSuffixListener.onPrefixSuffixChanged(terminalName, filePrefix, fileSuffix);
            }
        }
    }
    
    /**
     * Sets the label toggle listener.
     */
    public void setLabelToggleListener(LabelToggleListener listener) {
        this.labelToggleListener = listener;
    }
    
    /**
     * Sets the terminal name and updates the label.
     */
    public void setTerminalName(String name) {
        this.terminalName = name;
        if (terminalLabel != null) {
            terminalLabel.setText(name);
        }
    }
    
    /**
     * Gets the terminal name.
     */
    public String getTerminalName() {
        return terminalName;
    }
    
    // File naming menu item (added programmatically)
    private javax.swing.JMenuItem fileNamingMenuItem;
}
