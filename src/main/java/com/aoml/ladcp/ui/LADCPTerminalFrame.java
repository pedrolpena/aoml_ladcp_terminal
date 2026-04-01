package com.aoml.ladcp.ui;

import com.aoml.ladcp.serial.SerialPortException;
import com.aoml.ladcp.serial.SerialPortManager;
import com.aoml.ladcp.terminal.RDITerminal;
import com.aoml.ladcp.util.LADCPUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Main application window for the LADCP Terminal.
 * Inspired by the Python rditerm.py UI from the UHDAS LADCP Terminal.
 */
public class LADCPTerminalFrame extends JFrame {
    private static final Logger log = LoggerFactory.getLogger(LADCPTerminalFrame.class);

    // UI Components
    private TerminalPanel terminalPanel;
    private JTextField prefixField;
    private JTextField suffixField;
    private JTextField cruiseNameField;
    private JTextField stationCastField;
    private JLabel cwdLabel;

    // Menu items that need enable/disable
    private JMenuItem connectItem;
    private JMenuItem disconnectItem;
    private JMenuItem saveIncomingItem;
    private JMenuItem stopSavingItem;
    private JMenuItem downloadAllItem;
    private JMenuItem cancelDownloadItem;
    private JMenu baudMenu;

    // Terminal and state
    private RDITerminal terminal;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Future<?> currentTask;
    private Path commandFile;
    private Path dataDirectory = Paths.get(System.getProperty("user.home"), "ladcp_data");
    private Path logDirectory = Paths.get(System.getProperty("user.home"), "ladcp_logs");
    private Path backupDirectory;
    
    // Download state
    private volatile boolean downloadInProgress = false;
    private volatile boolean downloadCancelled = false;

    // Default settings
    private String devicePath = "/dev/ttyUSB0";
    private int baudRate = 9600;
    private Integer dataBaud = null;
    private String prefix = "ladcp";
    private String suffix = "";
    private String cruiseName = "XXNNNN";
    private String stationCast = "000_01";
    private String dataFileExt = ".dat";

    /**
     * Creates the main application frame.
     */
    public LADCPTerminalFrame() {
        super("AOML LADCP Terminal");
        initializeUI();
        initializeTerminal();
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeApplication();
            }
        });
    }

    /**
     * Initializes the terminal with current settings.
     */
    private void initializeTerminal() {
        terminal = new RDITerminal.Builder()
                .devicePath(devicePath)
                .baudRate(baudRate)
                .prefix(prefix)
                .suffix(suffix)
                .cruiseName(cruiseName)
                .stationCast(stationCast)
                .dataFileExtension(dataFileExt)
                .commandFile(commandFile)
                .dataDirectory(dataDirectory)
                .logDirectory(logDirectory)
                .backupDirectory(backupDirectory)
                .build();

        terminal.addListener((event, t) -> SwingUtilities.invokeLater(() -> handleTerminalEvent(event)));
        terminalPanel.setTerminal(terminal);
        terminalPanel.startUpdating();
    }

    /**
     * Initializes all UI components.
     */
    private void initializeUI() {
        setLayout(new BorderLayout());

        // Create menu bar
        setJMenuBar(createMenuBar());

        // Status/config panel at top
        add(createStatusPanel(), BorderLayout.NORTH);

        // Terminal panel in center
        terminalPanel = new TerminalPanel();
        add(terminalPanel, BorderLayout.CENTER);

        // Set initial size and center
        setPreferredSize(new Dimension(800, 600));
        pack();
        setLocationRelativeTo(null);
    }

    /**
     * Creates the menu bar.
     */
    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // File menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);
        fileMenu.setToolTipText("Connection, file, and application operations");

        connectItem = new JMenuItem("Connect to port");
        connectItem.setToolTipText("Open the serial port and start communicating with the ADCP");
        connectItem.addActionListener(e -> connect());
        fileMenu.add(connectItem);

        disconnectItem = new JMenuItem("Disconnect");
        disconnectItem.setToolTipText("Close the serial port connection");
        disconnectItem.addActionListener(e -> disconnect());
        disconnectItem.setEnabled(false);
        fileMenu.add(disconnectItem);

        fileMenu.addSeparator();

        saveIncomingItem = new JMenuItem("Save incoming...");
        saveIncomingItem.setToolTipText("Begin saving all incoming serial data to a file");
        saveIncomingItem.addActionListener(e -> startSaving());
        fileMenu.add(saveIncomingItem);

        stopSavingItem = new JMenuItem("Stop saving");
        stopSavingItem.setToolTipText("Stop saving incoming serial data");
        stopSavingItem.addActionListener(e -> stopSaving());
        stopSavingItem.setEnabled(false);
        fileMenu.add(stopSavingItem);

        JMenuItem savePreviousItem = new JMenuItem("Save transcript...");
        savePreviousItem.setToolTipText("Save the current terminal display contents to a text file");
        savePreviousItem.addActionListener(e -> saveTranscript());
        fileMenu.add(savePreviousItem);

        fileMenu.addSeparator();

        JMenuItem clearItem = new JMenuItem("Clear");
        clearItem.setToolTipText("Clear the terminal display (does not affect the instrument)");
        clearItem.addActionListener(e -> terminalPanel.clear());
        fileMenu.add(clearItem);

        fileMenu.addSeparator();
        
        // File logging toggle
        JCheckBoxMenuItem fileLoggingCheckBox = new JCheckBoxMenuItem("Enable File Logging");
        fileLoggingCheckBox.setSelected(LADCPUtils.isFileLoggingEnabled());
        fileLoggingCheckBox.setToolTipText("Write debug log to ~/ladcp_logs/ladcp-terminal.log (disabled by default to save disk space)");
        fileLoggingCheckBox.addActionListener(e -> {
            if (fileLoggingCheckBox.isSelected()) {
                LADCPUtils.enableFileLogging();
            } else {
                LADCPUtils.disableFileLogging();
            }
        });
        fileMenu.add(fileLoggingCheckBox);
        
        fileMenu.addSeparator();

        JMenuItem quitItem = new JMenuItem("Quit");
        quitItem.setToolTipText("Save settings and exit the application");
        quitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        quitItem.addActionListener(e -> closeApplication());
        fileMenu.add(quitItem);

        menuBar.add(fileMenu);

        // Baud menu
        baudMenu = new JMenu("Baud");
        baudMenu.setMnemonic(KeyEvent.VK_B);
        baudMenu.setToolTipText("Set the serial communication baud rate (default 9600 for commands)");
        ButtonGroup baudGroup = new ButtonGroup();

        for (int baud : SerialPortManager.SUPPORTED_BAUD_RATES) {
            JRadioButtonMenuItem baudItem = new JRadioButtonMenuItem(String.valueOf(baud));
            baudItem.setSelected(baud == baudRate);
            final int b = baud;
            baudItem.addActionListener(e -> changeBaud(b));
            baudGroup.add(baudItem);
            baudMenu.add(baudItem);
        }
        menuBar.add(baudMenu);

        // Port menu
        JMenu portMenu = new JMenu("Port");
        portMenu.setMnemonic(KeyEvent.VK_P);
        portMenu.setToolTipText("Serial port selection");

        JMenuItem selectDeviceItem = new JMenuItem("Select Device...");
        selectDeviceItem.setToolTipText("Choose a different serial port from the list of available ports");
        selectDeviceItem.addActionListener(e -> selectDevice());
        portMenu.add(selectDeviceItem);

        JMenuItem refreshPortsItem = new JMenuItem("Refresh Ports");
        refreshPortsItem.setToolTipText("Rescan the system for available serial ports");
        refreshPortsItem.addActionListener(e -> {
            // Just trigger a refresh when user opens device dialog
        });
        portMenu.add(refreshPortsItem);

        menuBar.add(portMenu);

        // Command menu
        JMenu commandMenu = new JMenu("Command");
        commandMenu.setMnemonic(KeyEvent.VK_C);
        commandMenu.setToolTipText("ADCP instrument commands");

        JMenuItem wakeupItem = new JMenuItem("Wakeup");
        wakeupItem.setToolTipText("Send a break signal to wake the ADCP from sleep mode");
        wakeupItem.addActionListener(e -> interruptAndExecuteAsync(this::doWakeup));
        commandMenu.add(wakeupItem);

        JMenuItem sleepItem = new JMenuItem("ZZZZ (go to sleep)");
        sleepItem.setToolTipText("Send CZ command to put the ADCP into low-power sleep mode");
        sleepItem.addActionListener(e -> executeAsync(this::doSleep));
        commandMenu.add(sleepItem);

        JMenuItem setClockItem = new JMenuItem("Set Clock");
        setClockItem.setToolTipText("Set the ADCP internal clock to the current PC time (UTC)");
        setClockItem.addActionListener(e -> executeAsync(this::doSetClock));
        commandMenu.add(setClockItem);

        JMenuItem sendSetupItem = new JMenuItem("Send Setup...");
        sendSetupItem.setToolTipText("Load and send a .cmd setup file to configure the ADCP for data collection");
        sendSetupItem.addActionListener(e -> executeAsync(this::doSendSetup));
        commandMenu.add(sendSetupItem);

        JMenuItem showConfigItem = new JMenuItem("Show Config");
        showConfigItem.setToolTipText("Query and display the current ADCP configuration parameters");
        showConfigItem.addActionListener(e -> executeAsync(this::doShowConfig));
        commandMenu.add(showConfigItem);

        JMenuItem diagnosticsItem = new JMenuItem("Run Diagnostics");
        diagnosticsItem.setToolTipText("Run ADCP built-in self-tests (PS0, PT200)");
        diagnosticsItem.addActionListener(e -> executeAsync(this::doDiagnostics));
        commandMenu.add(diagnosticsItem);

        commandMenu.addSeparator();

        JMenuItem changeBaudItem = new JMenuItem("Change to download Baud");
        changeBaudItem.setToolTipText("Switch both PC and ADCP to the faster download baud rate");
        changeBaudItem.addActionListener(e -> executeAsync(this::doChangeToDataBaud));
        commandMenu.add(changeBaudItem);

        JMenuItem listRecorderItem = new JMenuItem("List Recorder Directory");
        listRecorderItem.setToolTipText("Show files stored on the ADCP internal recorder");
        listRecorderItem.addActionListener(e -> executeAsync(this::doListRecorder));
        commandMenu.add(listRecorderItem);
        
        commandMenu.addSeparator();
        
        JMenuItem downloadItem2 = new JMenuItem("Download...");
        downloadItem2.setToolTipText("Download a specific file from the ADCP recorder via YModem");
        downloadItem2.addActionListener(e -> executeAsync(this::doDownload));
        commandMenu.add(downloadItem2);
        
        downloadAllItem = new JMenuItem("Download All Files");
        downloadAllItem.setToolTipText("Download all recorded files from the ADCP recorder");
        downloadAllItem.addActionListener(e -> executeAsync(this::doDownloadAll));
        commandMenu.add(downloadAllItem);
        
        cancelDownloadItem = new JMenuItem("Cancel Download");
        cancelDownloadItem.setToolTipText("Cancel the currently active YModem download");
        cancelDownloadItem.setEnabled(false);
        cancelDownloadItem.addActionListener(e -> doCancelDownload());
        commandMenu.add(cancelDownloadItem);
        
        commandMenu.addSeparator();

        JMenuItem eraseItem = new JMenuItem("Erase Recorder NOW");
        eraseItem.setToolTipText("Erase all recorded data from the ADCP recorder (irreversible!)");
        eraseItem.addActionListener(e -> executeAsync(this::doEraseRecorder));
        commandMenu.add(eraseItem);

        JMenuItem sendBreakItem = new JMenuItem("Send Break");
        sendBreakItem.setToolTipText("Send a raw break signal on the serial line to wake the instrument");
        sendBreakItem.addActionListener(e -> interruptAndExecuteAsync(this::doSendBreak));
        commandMenu.add(sendBreakItem);

        menuBar.add(commandMenu);

        // Deploy menu
        JMenu deployMenu = new JMenu("Deploy");
        deployMenu.setMnemonic(KeyEvent.VK_D);
        deployMenu.setToolTipText("Deployment workflow steps");

        JMenuItem deployInitItem = new JMenuItem("Deployment Initialization");
        deployInitItem.setToolTipText("Wake instrument, check clock, and list recorder — run before each deployment");
        deployInitItem.addActionListener(e -> interruptAndExecuteAsync(this::doDeployInit));
        deployMenu.add(deployInitItem);

        JMenuItem deploySetClockItem = new JMenuItem("Set Clock");
        deploySetClockItem.setToolTipText("Set the ADCP clock to current UTC time before deployment");
        deploySetClockItem.addActionListener(e -> executeAsync(this::doSetClock));
        deployMenu.add(deploySetClockItem);

        JMenuItem deploySetupItem = new JMenuItem("Send Setup and Start...");
        deploySetupItem.setToolTipText("Send the setup command file and start data collection (CS)");
        deploySetupItem.addActionListener(e -> executeAsync(this::doSendSetup));
        deployMenu.add(deploySetupItem);

        JMenuItem deployNoAskItem = new JMenuItem("Send Setup Without Asking");
        deployNoAskItem.setToolTipText("Send the previously selected setup file without prompting for file selection");
        deployNoAskItem.addActionListener(e -> executeAsync(this::doSendSetupNoAsk));
        deployMenu.add(deployNoAskItem);

        menuBar.add(deployMenu);

        // Recover menu
        JMenu recoverMenu = new JMenu("Recover");
        recoverMenu.setMnemonic(KeyEvent.VK_R);
        recoverMenu.setToolTipText("Recovery workflow steps");

        JMenuItem recoverInitItem = new JMenuItem("Recovery Initialization");
        recoverInitItem.setToolTipText("Wake instrument and check status after recovering from deployment");
        recoverInitItem.addActionListener(e -> interruptAndExecuteAsync(this::doDeployInit));
        recoverMenu.add(recoverInitItem);

        JMenuItem downloadItem = new JMenuItem("Download...");
        downloadItem.setToolTipText("Download recorded data from the ADCP after recovery");
        downloadItem.addActionListener(e -> executeAsync(this::doDownload));
        recoverMenu.add(downloadItem);

        menuBar.add(recoverMenu);

        // Help menu
        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);
        helpMenu.setToolTipText("Help and reference information");

        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.setToolTipText("Show application version and credits");
        aboutItem.addActionListener(e -> showAbout());
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);

        return menuBar;
    }

    /**
     * Creates the status/configuration panel.
     */
    private JPanel createStatusPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // First row - configuration fields
        JPanel configPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));

        configPanel.add(new JLabel("Prefix:"));
        prefixField = new JTextField(prefix, 6);
        prefixField.setToolTipText("File prefix prepended to all data and log filenames");
        prefixField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                updateTerminalConfig();
            }
        });
        configPanel.add(prefixField);

        configPanel.add(new JLabel("Suffix:"));
        suffixField = new JTextField(suffix, 6);
        suffixField.setToolTipText("File suffix appended to all data and log filenames");
        suffixField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                updateTerminalConfig();
            }
        });
        configPanel.add(suffixField);

        configPanel.add(new JLabel("Cruise:"));
        cruiseNameField = new JTextField(cruiseName, 10);
        cruiseNameField.setToolTipText("Cruise identifier used in log and data filenames (e.g. KN221)");
        cruiseNameField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                updateTerminalConfig();
            }
        });
        configPanel.add(cruiseNameField);

        configPanel.add(new JLabel("Station_Cast:"));
        stationCastField = new JTextField(stationCast, 10);
        stationCastField.setToolTipText("Station and cast identifier in NNN_NN format (e.g. 005_01)");
        stationCastField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                updateTerminalConfig();
            }
        });
        configPanel.add(stationCastField);

        panel.add(configPanel);

        // Second row - current working directory
        JPanel cwdPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        cwdPanel.add(new JLabel("Working Directory:"));
        cwdLabel = new JLabel(System.getProperty("user.dir"));
        cwdLabel.setFont(cwdLabel.getFont().deriveFont(Font.PLAIN));
        cwdLabel.setToolTipText("The current working directory — log and data files are saved relative to this path");
        cwdPanel.add(cwdLabel);
        panel.add(cwdPanel);

        return panel;
    }

    /**
     * Updates terminal configuration from UI fields.
     */
    private void updateTerminalConfig() {
        if (terminal != null) {
            terminal.setPrefix(prefixField.getText());
            terminal.setSuffix(suffixField.getText());
            terminal.setCruiseName(cruiseNameField.getText());
            terminal.setStationCast(stationCastField.getText());
        }
    }

    /**
     * Handles terminal events.
     */
    private void handleTerminalEvent(RDITerminal.TerminalEvent event) {
        switch (event) {
            case CONNECTED:
                connectItem.setEnabled(false);
                disconnectItem.setEnabled(true);
                break;
            case DISCONNECTED:
                connectItem.setEnabled(true);
                disconnectItem.setEnabled(false);
                break;
        }
    }

    // ==================== Connection Actions ====================

    private void connect() {
        try {
            terminal.startListening();
        } catch (SerialPortException e) {
            showError("Connection Error", "Failed to connect: " + e.getMessage());
        }
    }

    private void disconnect() {
        terminal.stopListening();
    }

    private void selectDevice() {
        Set<String> ports = SerialPortManager.listAvailablePorts();
        String[] portArray = ports.toArray(new String[0]);

        if (portArray.length == 0) {
            showError("No Ports", "No serial ports found on this system.");
            return;
        }

        String selected = (String) JOptionPane.showInputDialog(
                this,
                "Select serial port:",
                "Select Device",
                JOptionPane.QUESTION_MESSAGE,
                null,
                portArray,
                devicePath
        );

        if (selected != null) {
            devicePath = selected;
            terminal.close();
            initializeTerminal();
        }
    }

    private void changeBaud(int baud) {
        this.baudRate = baud;
        try {
            terminal.changeBaud(baud);
        } catch (SerialPortException e) {
            showError("Baud Change Error", "Failed to change baud rate: " + e.getMessage());
        }
    }

    // ==================== File Actions ====================

    private void startSaving() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("term_diary.txt"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                terminal.beginSave(chooser.getSelectedFile().toPath());
                saveIncomingItem.setEnabled(false);
                stopSavingItem.setEnabled(true);
            } catch (IOException e) {
                showError("Save Error", "Failed to start saving: " + e.getMessage());
            }
        }
    }

    private void stopSaving() {
        terminal.endSave();
        saveIncomingItem.setEnabled(true);
        stopSavingItem.setEnabled(false);
    }

    private void saveTranscript() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("term_transcript.txt"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.writeString(chooser.getSelectedFile().toPath(), terminalPanel.getText());
            } catch (IOException e) {
                showError("Save Error", "Failed to save transcript: " + e.getMessage());
            }
        }
    }

    // ==================== Command Actions ====================

    private void executeAsync(Runnable task) {
        currentTask = executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Error executing command", e);
                SwingUtilities.invokeLater(() ->
                        showError("Command Error", e.getMessage()));
            }
        });
    }

    /**
     * Cancels any running command sequence then executes the given task.
     * Used by wakeup and send-break to preempt a long-running script.
     */
    private void interruptAndExecuteAsync(Runnable task) {
        if (terminal != null) {
            terminal.cancelPendingCommands();
        }
        Future<?> running = currentTask;
        if (running != null && !running.isDone()) {
            running.cancel(true);
        }
        executeAsync(task);
    }

    private void doWakeup() {
        try {
            terminal.wakeup();
        } catch (Exception e) {
            throw new RuntimeException("Wakeup failed: " + e.getMessage(), e);
        }
    }

    private void doSleep() {
        try {
            terminal.sleep();
        } catch (Exception e) {
            throw new RuntimeException("Sleep failed: " + e.getMessage(), e);
        }
    }

    private void doSetClock() {
        try {
            terminal.setClock();
        } catch (Exception e) {
            throw new RuntimeException("Set clock failed: " + e.getMessage(), e);
        }
    }

    private void doShowConfig() {
        try {
            terminal.showConfig();
        } catch (Exception e) {
            throw new RuntimeException("Show config failed: " + e.getMessage(), e);
        }
    }

    private void doDiagnostics() {
        try {
            terminal.runDiagnostics();
        } catch (Exception e) {
            throw new RuntimeException("Diagnostics failed: " + e.getMessage(), e);
        }
    }

    private void doListRecorder() {
        try {
            terminal.listRecorder();
        } catch (Exception e) {
            throw new RuntimeException("List recorder failed: " + e.getMessage(), e);
        }
    }

    private void doEraseRecorder() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to ERASE the recorder?\nThis cannot be undone!",
                "Confirm Erase",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            try {
                terminal.eraseRecorder();
            } catch (Exception e) {
                throw new RuntimeException("Erase failed: " + e.getMessage(), e);
            }
        }
    }

    private void doChangeToDataBaud() {
        try {
            int dataBaud = terminal.getDataBaud();
            terminal.changeAllBaud(dataBaud);
        } catch (Exception e) {
            throw new RuntimeException("Baud change failed: " + e.getMessage(), e);
        }
    }

    private void doSendBreak() {
        try {
            terminal.startListening();
            // Access port manager through terminal method
            terminal.wakeup();  // This sends a break internally
        } catch (Exception e) {
            throw new RuntimeException("Send break failed: " + e.getMessage(), e);
        }
    }

    private void doDeployInit() {
        try {
            terminal.clearDisplay();
            terminal.wakeup();
            RDITerminal.TimeComparison timeComparison = terminal.showTimeOk();
            terminal.listRecorder();
            
            // Show time comparison dialog on EDT
            SwingUtilities.invokeLater(() -> {
                showTimeComparisonDialog(timeComparison);
            });
        } catch (Exception e) {
            throw new RuntimeException("Initialization failed: " + e.getMessage(), e);
        }
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
                "Time Comparison",
                Math.abs(timeComparison.differenceSeconds) > 60 
                    ? JOptionPane.WARNING_MESSAGE 
                    : JOptionPane.INFORMATION_MESSAGE);
    }

    private void doSendSetup() {
        SwingUtilities.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("Command files", "cmd"));
            if (commandFile != null) {
                chooser.setSelectedFile(commandFile.toFile());
            }

            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                commandFile = chooser.getSelectedFile().toPath();
                executeAsync(() -> {
                    try {
                        terminal.sendSetup(commandFile);
                    } catch (Exception e) {
                        throw new RuntimeException("Send setup failed: " + e.getMessage(), e);
                    }
                });
            }
        });
    }

    private void doSendSetupNoAsk() {
        if (commandFile == null || !Files.exists(commandFile)) {
            doSendSetup();
            return;
        }

        try {
            terminal.sendSetup(commandFile);
        } catch (Exception e) {
            throw new RuntimeException("Send setup failed: " + e.getMessage(), e);
        }
    }

    private void doDownload() {
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
                        if (fileNum < 1 || fileNum > numRecorded) {
                            showError("Invalid Input", "File number must be between 1 and " + numRecorded);
                            return;
                        }

                        executeAsync(() -> {
                            try {
                                // Create progress listener that updates the terminal display
                                RDITerminal.DownloadProgressListener progressListener = 
                                    new RDITerminal.DownloadProgressListener() {
                                        @Override
                                        public void onProgress(int bytesReceived, int totalBytes, 
                                                              int blocksReceived, int errors, String message) {
                                            terminal.insertComment(String.format(
                                                "Block %d received (%d bytes, %d errors)", 
                                                blocksReceived, bytesReceived, errors));
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
                                throw new RuntimeException("Download failed: " + e.getMessage(), e);
                            }
                        });
                    } catch (NumberFormatException e) {
                        showError("Invalid Input", "Please enter a valid number");
                    }
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to get recorder info: " + e.getMessage(), e);
        }
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
        String suggestedName = terminal.makeFilename(dataFileExt);
        
        // Create file chooser
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Downloaded File");
        chooser.setSelectedFile(new File(dataDirectory.toFile(), suggestedName));
        chooser.setFileFilter(new FileNameExtensionFilter("Data files (*" + dataFileExt + ")", 
                dataFileExt.substring(1)));
        
        int result = chooser.showSaveDialog(this);
        
        if (result == JFileChooser.APPROVE_OPTION) {
            Path destFile = chooser.getSelectedFile().toPath();
            
            // Add extension if not present
            if (!destFile.toString().toLowerCase().endsWith(dataFileExt)) {
                destFile = Paths.get(destFile.toString() + dataFileExt);
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
                    
                    // Add extension if not present
                    if (!newName.toLowerCase().endsWith(dataFileExt)) {
                        newName = newName + dataFileExt;
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
                if (backupDirectory != null) {
                    Files.createDirectories(backupDirectory);
                    Path backupFile = backupDirectory.resolve(destFile.getFileName());
                    Files.copy(destFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
                    terminal.insertComment("File backed up to: " + backupFile);
                }
                
                // Save recovery log
                Path logFile = logDirectory.resolve(
                        destFile.getFileName().toString().replace(dataFileExt, ".log"));
                terminal.appendToFile(logFile);
                terminal.insertComment("Recovery logfile written to: " + logFile);
                
                JOptionPane.showMessageDialog(this,
                        "File saved successfully:\n" + destFile,
                        "Download Complete",
                        JOptionPane.INFORMATION_MESSAGE);
                
                // Put instrument to sleep
                executeAsync(() -> {
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
                Files.deleteIfExists(downloadedFile.getParent());
            } catch (IOException e) {
                log.warn("Error cleaning up temp file", e);
            }
        }
    }
    
    /**
     * Downloads all files from the recorder using their original names.
     */
    private void doDownloadAll() {
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
                                "to: %s", numRecorded, dataDirectory),
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
                cancelDownloadItem.setEnabled(true);
                downloadAllItem.setEnabled(false);
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
                    Path destFile = dataDirectory.resolve(originalName);
                    
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
                        destFile = dataDirectory.resolve(baseName + "_" + suffix + ext);
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
                cancelDownloadItem.setEnabled(false);
                downloadAllItem.setEnabled(true);
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
                cancelDownloadItem.setEnabled(false);
                downloadAllItem.setEnabled(true);
            });
            throw new RuntimeException("Download all failed: " + e.getMessage(), e);
        }
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
            }, "CancelWakeup").start();
        }
    }

    // ==================== Helper Methods ====================

    private void showError(String title, String message) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE));
    }

    private void showAbout() {
        String about = "AOML LADCP Terminal\n" +
                "Version 1.0.0 (Java)\n\n" +
                "AOML LADCP Terminal Application\n" +
                "for RDI ADCP instrument communication.\n\n" +
                "Based on the UHDAS LADCP Terminal\n\n" +
                "Serial communication powered by jSerialComm.";

        JOptionPane.showMessageDialog(this, about, "About", JOptionPane.INFORMATION_MESSAGE);
    }

    private void closeApplication() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to quit?",
                "Confirm Exit",
                JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            terminalPanel.stopUpdating();
            terminal.close();
            executor.shutdown();
            dispose();
            System.exit(0);
        }
    }

    /**
     * Gets the terminal instance.
     */
    public RDITerminal getTerminal() {
        return terminal;
    }
}
