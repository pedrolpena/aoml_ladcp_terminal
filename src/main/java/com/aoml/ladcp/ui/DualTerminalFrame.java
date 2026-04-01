package com.aoml.ladcp.ui;

import com.aoml.ladcp.serial.SerialPortManager;
import com.aoml.ladcp.util.LADCPUtils;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import javax.swing.*;

/**
 * Main Terminal Frame for controlling LADCP instruments.
 * Supports both single terminal (Down-looker only) and dual terminal 
 * (Up-looker/Down-looker) configurations.
 * 
 * Settings are automatically saved and restored using Java Preferences API.
 * 
 * NOTE: The menu bar is created programmatically in initMenuBar() rather than
 * via the NetBeans GUI builder to avoid synchronization issues between the
 * .form file and .java file.
 */
public class DualTerminalFrame extends javax.swing.JFrame {
    private static final Logger log = LoggerFactory.getLogger(DualTerminalFrame.class);
    
    private static final String APP_NAME = "AOML LADCP Terminal";
    private static final String VERSION = "1.0.0";

    // Preferences keys
    private static final String PREF_CRUISE = "cruise";
    private static final String PREF_STATION = "station";
    private static final String PREF_CAST = "cast";
    private static final String PREF_DEVICE1 = "device1";
    private static final String PREF_DEVICE2 = "device2";
    private static final String PREF_BAUD_RATE = "baudRate";
    private static final String PREF_LOG_DIR = "logDirectory";
    private static final String PREF_DOWNLOAD_DIR = "downloadDirectory";
    private static final String PREF_SCRIPTS_DIR = "scriptsDirectory";
    private static final String PREF_THEME = "theme";
    private static final String PREF_DUAL_MODE = "dualTerminalMode";
    private static final String PREF_COLOR_SCHEME = "colorScheme";
    private static final String PREF_UPLOOKER_PREFIX = "uplookerPrefix";
    private static final String PREF_UPLOOKER_SUFFIX = "uplookerSuffix";
    private static final String PREF_DOWNLOOKER_PREFIX = "downlookerPrefix";
    private static final String PREF_DOWNLOOKER_SUFFIX = "downlookerSuffix";
    private static final String PREF_FILE_LOGGING = "fileLoggingEnabled";

    // Preferences instance
    private final Preferences prefs = Preferences.userNodeForPackage(DualTerminalFrame.class);

    // Terminal panels
    private SingleTerminalPanel terminal1Panel;  // Up-looker (only in dual mode)
    private SingleTerminalPanel terminal2Panel;  // Down-looker (always present)

    // Shared executor for async operations
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    // Settings
    private String device1;
    private String device2;
    private int baudRate;
    private Path logDirectory = Paths.get(System.getProperty("user.home"), "ladcp", "ladcp_logs");
    private Path downloadDirectory = Paths.get(System.getProperty("user.home"), "ladcp", "ladcp_logs");
    private Path scriptsDirectory = Paths.get(System.getProperty("user.home"), "ladcp", "cmd_files");
    private String currentTheme;
    private boolean dualTerminalMode;
    private String currentColorScheme;
    private String uplookerPrefix;
    private String uplookerSuffix;
    private String downlookerPrefix;
    private String downlookerSuffix;

    // Menu components (created programmatically)
    private JMenuBar frameMenuBar;
    private JMenu fileMenu;
    private JMenu configMenu;
    private JMenu helpMenu;
    private JMenu themeMenu;
    private JMenu colorSchemeMenu;
    private JRadioButtonMenuItem flatLightThemeItem;
    private JRadioButtonMenuItem flatDarkThemeItem;
    private JRadioButtonMenuItem flatIntelliJThemeItem;
    private JRadioButtonMenuItem flatDarculaThemeItem;
    private JRadioButtonMenuItem systemThemeItem;
    private JRadioButtonMenuItem metalThemeItem;
    private JRadioButtonMenuItem singleModeItem;
    private JRadioButtonMenuItem dualModeItem;
    private JRadioButtonMenuItem greenSchemeItem;
    private JRadioButtonMenuItem amberSchemeItem;
    private JRadioButtonMenuItem whiteSchemeItem;
    private JCheckBoxMenuItem fileLoggingCheckBox;
    private boolean fileLoggingEnabled;

    /**
     * Creates new form DualTerminalFrame
     */
    public DualTerminalFrame() {
        loadPreferences();
        initComponents();
        initMenuBar();
        initCustomComponents();
        initTerminals();
        updateTitle();
        setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeApplication();
            }
        });
    }

    /**
     * Updates the window title based on current mode.
     */
    private void updateTitle() {
        if (dualTerminalMode) {
            setTitle(APP_NAME + " - Dual Terminal Mode");
        } else {
            setTitle(APP_NAME);
        }
    }

    /**
     * Loads preferences from storage.
     */
    private void loadPreferences() {
        device1 = prefs.get(PREF_DEVICE1, getDefaultDevice(0));
        device2 = prefs.get(PREF_DEVICE2, getDefaultDevice(1));
        baudRate = prefs.getInt(PREF_BAUD_RATE, 9600);
        currentTheme = prefs.get(PREF_THEME, "FlatLight");
        dualTerminalMode = prefs.getBoolean(PREF_DUAL_MODE, false);
        currentColorScheme = prefs.get(PREF_COLOR_SCHEME, "green");
        
        // Per-terminal prefix/suffix with defaults
        uplookerPrefix = prefs.get(PREF_UPLOOKER_PREFIX, "");
        uplookerSuffix = prefs.get(PREF_UPLOOKER_SUFFIX, "s");
        downlookerPrefix = prefs.get(PREF_DOWNLOOKER_PREFIX, "");
        downlookerSuffix = prefs.get(PREF_DOWNLOOKER_SUFFIX, "m");
        
        // File logging (default: disabled)
        fileLoggingEnabled = prefs.getBoolean(PREF_FILE_LOGGING, false);
        
        String logDirStr = prefs.get(PREF_LOG_DIR, null);
        if (logDirStr != null) {
            logDirectory = Paths.get(logDirStr);
        }
        
        String downloadDirStr = prefs.get(PREF_DOWNLOAD_DIR, null);
        if (downloadDirStr != null) {
            downloadDirectory = Paths.get(downloadDirStr);
        }
        
        String scriptsDirStr = prefs.get(PREF_SCRIPTS_DIR, null);
        if (scriptsDirStr != null) {
            scriptsDirectory = Paths.get(scriptsDirStr);
        }
        
        // Ensure default directories exist
        try {
            Files.createDirectories(logDirectory);
            Files.createDirectories(downloadDirectory);
            Files.createDirectories(scriptsDirectory);
        } catch (IOException e) {
            log.warn("Could not create default directories", e);
        }
    }
    
    /**
     * Gets the default serial device path based on the OS and index.
     */
    private String getDefaultDevice(int index) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "COM" + (index + 1);
        } else if (os.contains("mac")) {
            return "/dev/cu.usbserial-" + index;
        } else {
            return "/dev/ttyUSB" + index;
        }
    }

    /**
     * Saves preferences to storage.
     */
    private void savePreferences() {
        prefs.put(PREF_CRUISE, cruiseField.getText());
        prefs.putInt(PREF_STATION, (Integer) stationSpinner.getValue());
        prefs.putInt(PREF_CAST, (Integer) castSpinner.getValue());
        prefs.put(PREF_DEVICE1, device1);
        prefs.put(PREF_DEVICE2, device2);
        prefs.putInt(PREF_BAUD_RATE, baudRate);
        prefs.put(PREF_THEME, currentTheme);
        prefs.putBoolean(PREF_DUAL_MODE, dualTerminalMode);
        prefs.put(PREF_COLOR_SCHEME, currentColorScheme);
        
        // Per-terminal prefix/suffix
        prefs.put(PREF_UPLOOKER_PREFIX, uplookerPrefix);
        prefs.put(PREF_UPLOOKER_SUFFIX, uplookerSuffix);
        prefs.put(PREF_DOWNLOOKER_PREFIX, downlookerPrefix);
        prefs.put(PREF_DOWNLOOKER_SUFFIX, downlookerSuffix);
        prefs.putBoolean(PREF_FILE_LOGGING, fileLoggingEnabled);
        
        if (logDirectory != null) {
            prefs.put(PREF_LOG_DIR, logDirectory.toString());
        }
        if (downloadDirectory != null) {
            prefs.put(PREF_DOWNLOAD_DIR, downloadDirectory.toString());
        }
        if (scriptsDirectory != null) {
            prefs.put(PREF_SCRIPTS_DIR, scriptsDirectory.toString());
        }
    }

    /**
     * Creates the menu bar programmatically.
     * This is done outside of NetBeans GUI builder to avoid sync issues.
     */
    private void initMenuBar() {
        frameMenuBar = new JMenuBar();
        
        // === File Menu ===
        fileMenu = new JMenu("File");
        fileMenu.setMnemonic('F');
        fileMenu.setToolTipText("Application file operations");
        
        JMenuItem quitMenuItem = new JMenuItem("Quit");
        quitMenuItem.setToolTipText("Save settings and exit the application");
        quitMenuItem.setAccelerator(KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_Q, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        quitMenuItem.addActionListener(e -> closeApplication());
        fileMenu.add(quitMenuItem);
        
        frameMenuBar.add(fileMenu);
        
        // === Configuration Menu ===
        configMenu = new JMenu("Configuration");
        configMenu.setMnemonic('C');
        configMenu.setToolTipText("Application and terminal settings");
        
        JMenuItem serialPortsMenuItem = new JMenuItem("Serial Ports...");
        serialPortsMenuItem.setToolTipText("Select serial ports for Up-looker and Down-looker terminals");
        serialPortsMenuItem.addActionListener(e -> configureSerialPorts());
        configMenu.add(serialPortsMenuItem);
        
        configMenu.addSeparator();
        
        JMenuItem logDirMenuItem = new JMenuItem("Log Directory...");
        logDirMenuItem.setToolTipText("Set the directory for deployment and recovery log files");
        logDirMenuItem.addActionListener(e -> configureLogDirectory());
        configMenu.add(logDirMenuItem);
        
        JMenuItem downloadDirMenuItem = new JMenuItem("Download Directory...");
        downloadDirMenuItem.setToolTipText("Set the directory where downloaded ADCP data files are saved");
        downloadDirMenuItem.addActionListener(e -> configureDownloadDirectory());
        configMenu.add(downloadDirMenuItem);
        
        JMenuItem scriptsDirMenuItem = new JMenuItem("Scripts Directory...");
        scriptsDirMenuItem.setToolTipText("Set the default directory for .cmd setup script files");
        scriptsDirMenuItem.addActionListener(e -> configureScriptsDirectory());
        configMenu.add(scriptsDirMenuItem);
        
        configMenu.addSeparator();
        
        JMenuItem showDirsMenuItem = new JMenuItem("Show Current Directories");
        showDirsMenuItem.setToolTipText("Display the currently configured log, download, and scripts directories");
        showDirsMenuItem.addActionListener(e -> showCurrentDirectories());
        configMenu.add(showDirsMenuItem);
        
        configMenu.addSeparator();
        
        JMenuItem clearTerminalsMenuItem = new JMenuItem("Clear Terminals");
        clearTerminalsMenuItem.setToolTipText("Clear the display of all terminal windows");
        clearTerminalsMenuItem.addActionListener(e -> clearTerminals());
        configMenu.add(clearTerminalsMenuItem);
        
        JMenuItem clearCruiseMenuItem = new JMenuItem("Clear Cruise");
        clearCruiseMenuItem.setToolTipText("Reset cruise name, station, cast, and prefix/suffix to defaults");
        clearCruiseMenuItem.addActionListener(e -> clearCruise());
        configMenu.add(clearCruiseMenuItem);
        
        configMenu.addSeparator();
        
        // Terminal Mode submenu
        JMenu terminalModeMenu = new JMenu("Terminal Mode");
        terminalModeMenu.setToolTipText("Switch between single and dual terminal layouts");
        ButtonGroup modeGroup = new ButtonGroup();
        
        singleModeItem = new JRadioButtonMenuItem("Single Terminal (Down-looker only)");
        singleModeItem.setToolTipText("Use a single terminal for the Down-looker ADCP only");
        singleModeItem.addActionListener(e -> switchTerminalMode(false));
        modeGroup.add(singleModeItem);
        terminalModeMenu.add(singleModeItem);
        
        dualModeItem = new JRadioButtonMenuItem("Dual Terminal (Up-looker + Down-looker)");
        dualModeItem.setToolTipText("Use two terminals side-by-side for Up-looker and Down-looker ADCPs");
        dualModeItem.addActionListener(e -> switchTerminalMode(true));
        modeGroup.add(dualModeItem);
        terminalModeMenu.add(dualModeItem);
        
        // Select current mode
        if (dualTerminalMode) {
            dualModeItem.setSelected(true);
        } else {
            singleModeItem.setSelected(true);
        }
        
        configMenu.add(terminalModeMenu);
        
        configMenu.addSeparator();
        
        // Theme submenu
        themeMenu = new JMenu("Theme");
        themeMenu.setToolTipText("Change the application look-and-feel");
        ButtonGroup themeGroup = new ButtonGroup();
        
        flatLightThemeItem = new JRadioButtonMenuItem("FlatLight");
        flatLightThemeItem.setToolTipText("Modern light theme");
        flatLightThemeItem.addActionListener(e -> changeTheme("FlatLight"));
        themeGroup.add(flatLightThemeItem);
        themeMenu.add(flatLightThemeItem);
        
        flatDarkThemeItem = new JRadioButtonMenuItem("FlatDark");
        flatDarkThemeItem.setToolTipText("Modern dark theme");
        flatDarkThemeItem.addActionListener(e -> changeTheme("FlatDark"));
        themeGroup.add(flatDarkThemeItem);
        themeMenu.add(flatDarkThemeItem);
        
        flatIntelliJThemeItem = new JRadioButtonMenuItem("FlatIntelliJ");
        flatIntelliJThemeItem.setToolTipText("IntelliJ-style light theme");
        flatIntelliJThemeItem.addActionListener(e -> changeTheme("FlatIntelliJ"));
        themeGroup.add(flatIntelliJThemeItem);
        themeMenu.add(flatIntelliJThemeItem);
        
        flatDarculaThemeItem = new JRadioButtonMenuItem("FlatDarcula");
        flatDarculaThemeItem.setToolTipText("IntelliJ Darcula dark theme");
        flatDarculaThemeItem.addActionListener(e -> changeTheme("FlatDarcula"));
        themeGroup.add(flatDarculaThemeItem);
        themeMenu.add(flatDarculaThemeItem);
        
        systemThemeItem = new JRadioButtonMenuItem("System");
        systemThemeItem.setToolTipText("Use the operating system's native theme");
        systemThemeItem.addActionListener(e -> changeTheme("System"));
        themeGroup.add(systemThemeItem);
        themeMenu.add(systemThemeItem);
        
        metalThemeItem = new JRadioButtonMenuItem("Metal");
        metalThemeItem.setToolTipText("Classic Java Metal theme");
        metalThemeItem.addActionListener(e -> changeTheme("Metal"));
        themeGroup.add(metalThemeItem);
        themeMenu.add(metalThemeItem);
        
        // Select current theme
        switch (currentTheme) {
            case "FlatLight": flatLightThemeItem.setSelected(true); break;
            case "FlatDark": flatDarkThemeItem.setSelected(true); break;
            case "FlatIntelliJ": flatIntelliJThemeItem.setSelected(true); break;
            case "FlatDarcula": flatDarculaThemeItem.setSelected(true); break;
            case "System": systemThemeItem.setSelected(true); break;
            case "Metal": metalThemeItem.setSelected(true); break;
            default: flatLightThemeItem.setSelected(true);
        }
        
        configMenu.add(themeMenu);
        
        // Color Scheme submenu
        colorSchemeMenu = new JMenu("Terminal Colors");
        colorSchemeMenu.setToolTipText("Change the terminal display text and background colors");
        ButtonGroup colorGroup = new ButtonGroup();
        
        greenSchemeItem = new JRadioButtonMenuItem("Green on Black");
        greenSchemeItem.setToolTipText("Classic terminal green text on black background");
        greenSchemeItem.addActionListener(e -> changeColorScheme("green"));
        colorGroup.add(greenSchemeItem);
        colorSchemeMenu.add(greenSchemeItem);
        
        amberSchemeItem = new JRadioButtonMenuItem("Amber on Black");
        amberSchemeItem.setToolTipText("Retro amber text on black background");
        amberSchemeItem.addActionListener(e -> changeColorScheme("amber"));
        colorGroup.add(amberSchemeItem);
        colorSchemeMenu.add(amberSchemeItem);
        
        whiteSchemeItem = new JRadioButtonMenuItem("White on Black");
        whiteSchemeItem.setToolTipText("White text on black background for readability");
        whiteSchemeItem.addActionListener(e -> changeColorScheme("white"));
        colorGroup.add(whiteSchemeItem);
        colorSchemeMenu.add(whiteSchemeItem);
        
        // Select current color scheme
        switch (currentColorScheme) {
            case "amber": amberSchemeItem.setSelected(true); break;
            case "white": whiteSchemeItem.setSelected(true); break;
            case "green":
            default: greenSchemeItem.setSelected(true); break;
        }
        
        configMenu.add(colorSchemeMenu);
        
        configMenu.addSeparator();
        
        // File Logging toggle
        fileLoggingCheckBox = new JCheckBoxMenuItem("Enable File Logging");
        fileLoggingCheckBox.setSelected(fileLoggingEnabled);
        fileLoggingCheckBox.setToolTipText("Write debug log to ~/ladcp_logs/ladcp-terminal.log (disabled by default to save disk space)");
        fileLoggingCheckBox.addActionListener(e -> toggleFileLogging(fileLoggingCheckBox.isSelected()));
        configMenu.add(fileLoggingCheckBox);
        
        frameMenuBar.add(configMenu);
        
        // === Help Menu ===
        helpMenu = new JMenu("Help");
        helpMenu.setMnemonic('H');
        helpMenu.setToolTipText("Help and reference information");
        
        JMenuItem aboutMenuItem = new JMenuItem("About");
        aboutMenuItem.setToolTipText("Show application version and credits");
        aboutMenuItem.addActionListener(e -> showAbout());
        helpMenu.add(aboutMenuItem);
        
        JMenuItem commandsMenuItem = new JMenuItem("RDI Commands Reference");
        commandsMenuItem.setToolTipText("Quick reference for common RDI ADCP commands");
        commandsMenuItem.addActionListener(e -> showCommandsHelp());
        helpMenu.add(commandsMenuItem);
        
        frameMenuBar.add(helpMenu);
        
        setJMenuBar(frameMenuBar);
    }
    
    /**
     * Shows the About dialog.
     */
    private void showAbout() {
        String about = APP_NAME + "\n" +
                "Version " + VERSION + "\n\n" +
                "AOML LADCP Terminal Application\n" +
                "For RDI ADCP instruments\n\n" +
                "Based on the UHDAS LADCP Terminal";
        
        JOptionPane.showMessageDialog(this,
                about,
                "About " + APP_NAME,
                JOptionPane.INFORMATION_MESSAGE);
    }
    
    /**
     * Shows the RDI commands help.
     */
    private void showCommandsHelp() {
        String help = "Common RDI ADCP Commands:\n\n" +
                "Communication:\n" +
                "  +++  - Enter command mode (send BREAK first)\n" +
                "  CB   - Change baud rate\n\n" +
                "Information:\n" +
                "  PS0  - System configuration\n" +
                "  PS3  - Transformation matrices\n" +
                "  PT200 - Built-in test\n" +
                "  TS?  - Show time\n\n" +
                "Recording:\n" +
                "  RA   - Show recorded file count\n" +
                "  RY   - Download file (YModem)\n" +
                "  RE   - Erase recorder\n\n" +
                "Data Collection:\n" +
                "  CS   - Start pinging\n" +
                "  CZ   - Power down (sleep)\n" +
                "  CK   - Save parameters\n" +
                "  CR1  - Restore factory defaults";
        
        JOptionPane.showMessageDialog(this,
                help,
                "RDI Commands Reference",
                JOptionPane.INFORMATION_MESSAGE);
    }
    
    /**
     * Switches between single and dual terminal mode.
     */
    private void switchTerminalMode(boolean dualMode) {
        if (this.dualTerminalMode == dualMode) {
            return;  // No change needed
        }
        
        int confirm = JOptionPane.showConfirmDialog(this,
                "Switching terminal mode requires restarting the application.\n" +
                "Do you want to switch to " + (dualMode ? "Dual" : "Single") + " Terminal mode?",
                "Switch Terminal Mode",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        
        if (confirm == JOptionPane.YES_OPTION) {
            this.dualTerminalMode = dualMode;
            savePreferences();
            
            // Restart the application
            closeApplication();
        } else {
            // Restore the radio button selection
            if (dualTerminalMode) {
                dualModeItem.setSelected(true);
            } else {
                singleModeItem.setSelected(true);
            }
        }
    }
    
    /**
     * Initialize custom components after NetBeans generated code.
     * This includes setting up spinner models.
     */
    private void initCustomComponents() {
        // Initialize cruise field with preferences
        cruiseField.setText(prefs.get(PREF_CRUISE, "XXNNNN"));
        
        // Initialize spinners with proper models
        stationSpinner.setModel(new javax.swing.SpinnerNumberModel(prefs.getInt(PREF_STATION, 0), 0, 999, 1));
        stationSpinner.setEditor(new javax.swing.JSpinner.NumberEditor(stationSpinner, "000"));
        
        castSpinner.setModel(new javax.swing.SpinnerNumberModel(prefs.getInt(PREF_CAST, 1), 0, 99, 1));
        castSpinner.setEditor(new javax.swing.JSpinner.NumberEditor(castSpinner, "00"));
        
        // Tooltips on config panel components
        cruiseField.setToolTipText("Cruise identifier used in log and data filenames (e.g. KN221)");
        cruiseLabel.setToolTipText("Cruise identifier used in log and data filenames");
        stationSpinner.setToolTipText("Station number (000-999) used in filenames");
        stationLabel.setToolTipText("Station number (000-999) used in filenames");
        castSpinner.setToolTipText("Cast number (00-99) used in filenames");
        castLabel.setToolTipText("Cast number (00-99) used in filenames");
        
        // Apply saved file logging preference
        if (fileLoggingEnabled) {
            LADCPUtils.enableFileLogging();
        }
    }
    
    /**
     * Changes the application theme.
     */
    private void changeTheme(String themeName) {
        try {
            switch (themeName) {
                case "FlatLight":
                    FlatLightLaf.setup();
                    break;
                case "FlatDark":
                    FlatDarkLaf.setup();
                    break;
                case "FlatIntelliJ":
                    FlatIntelliJLaf.setup();
                    break;
                case "FlatDarcula":
                    FlatDarculaLaf.setup();
                    break;
                case "System":
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    break;
                case "Metal":
                    UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
                    break;
            }
            currentTheme = themeName;
            SwingUtilities.updateComponentTreeUI(this);
            savePreferences();
        } catch (Exception e) {
            log.error("Failed to change theme to {}", themeName, e);
        }
    }
    
    /**
     * Changes the terminal color scheme for all terminals.
     */
    private void changeColorScheme(String scheme) {
        currentColorScheme = scheme;
        
        // Apply to all terminal panels
        if (terminal1Panel != null) {
            terminal1Panel.setColorScheme(scheme);
        }
        if (terminal2Panel != null) {
            terminal2Panel.setColorScheme(scheme);
        }
        
        savePreferences();
    }

    /**
     * Toggles file logging on or off.
     */
    private void toggleFileLogging(boolean enabled) {
        fileLoggingEnabled = enabled;
        if (enabled) {
            if (LADCPUtils.enableFileLogging()) {
                log.info("File logging enabled");
            } else {
                log.warn("Failed to enable file logging");
                fileLoggingCheckBox.setSelected(false);
                fileLoggingEnabled = false;
            }
        } else {
            LADCPUtils.disableFileLogging();
            log.info("File logging disabled");
        }
        savePreferences();
    }

    /**
     * Initializes the terminal panels based on current mode.
     */
    private void initTerminals() {
        if (dualTerminalMode) {
            // Dual mode: Up-looker (top) + Down-looker (bottom)
            terminal1Panel = new SingleTerminalPanel("Up-looker", device1, baudRate);
            terminal2Panel = new SingleTerminalPanel("Down-looker", device2, baudRate);

            mainSplitPane.setTopComponent(terminal1Panel);
            mainSplitPane.setBottomComponent(terminal2Panel);
            mainSplitPane.setDividerLocation(0.5);

            // Set up status listeners
            terminal1Panel.setStatusLabel(status1Label);
            terminal2Panel.setStatusLabel(status2Label);
            
            // Set up port change listeners to persist port selections
            terminal1Panel.setPortChangeListener((name, port) -> {
                device1 = port;
                savePreferences();
            });
            terminal2Panel.setPortChangeListener((name, port) -> {
                device2 = port;
                savePreferences();
            });
            
            // Set up prefix/suffix change listeners to persist settings
            terminal1Panel.setPrefixSuffixChangeListener((name, prefix, suffix) -> {
                onTerminalPrefixSuffixChanged(name, prefix, suffix);
            });
            terminal2Panel.setPrefixSuffixChangeListener((name, prefix, suffix) -> {
                onTerminalPrefixSuffixChanged(name, prefix, suffix);
            });
            
            // Set up label toggle listeners to swap Up-looker/Down-looker
            terminal1Panel.setLabelToggleListener(this::toggleTerminalLabels);
            terminal2Panel.setLabelToggleListener(this::toggleTerminalLabels);
            
            // Show both status labels
            status1Label.setVisible(true);
            status2Label.setVisible(true);
        } else {
            // Single mode: Down-looker only
            terminal2Panel = new SingleTerminalPanel("Down-looker", device2, baudRate);
            
            // Remove the split pane divider by just showing one panel
            mainSplitPane.setTopComponent(terminal2Panel);
            mainSplitPane.setBottomComponent(null);
            mainSplitPane.setDividerSize(0);
            
            // Set up status listener (use status1Label for the single terminal)
            terminal2Panel.setStatusLabel(status1Label);
            
            // Set up port change listener to persist port selection
            terminal2Panel.setPortChangeListener((name, port) -> {
                device2 = port;
                savePreferences();
            });
            
            // Set up prefix/suffix change listener to persist settings
            terminal2Panel.setPrefixSuffixChangeListener((name, prefix, suffix) -> {
                onTerminalPrefixSuffixChanged(name, prefix, suffix);
            });
            
            // No label toggle in single mode (only one terminal)
            
            // Hide the second status label
            status1Label.setVisible(true);
            status2Label.setVisible(false);
            
            // Update status label text
            status1Label.setText("Down-looker: Not connected");
        }

        updateBothTerminalsConfig();
        
        // Apply saved color scheme to all terminals
        changeColorScheme(currentColorScheme);
        
        // Auto-connect to serial ports if available
        autoConnectTerminals();
    }
    
    /**
     * Toggles the terminal labels between Up-looker and Down-looker.
     * Both terminals swap their labels.
     */
    private void toggleTerminalLabels(String requestingTerminal) {
        if (!dualTerminalMode || terminal1Panel == null || terminal2Panel == null) {
            return;
        }
        
        String term1Name = terminal1Panel.getTerminalName();
        String term2Name = terminal2Panel.getTerminalName();
        
        // Swap the names
        terminal1Panel.setTerminalName(term2Name);
        terminal2Panel.setTerminalName(term1Name);
        
        // Also swap the prefix/suffix settings to keep them associated with the looker type
        String tempPrefix = uplookerPrefix;
        String tempSuffix = uplookerSuffix;
        uplookerPrefix = downlookerPrefix;
        uplookerSuffix = downlookerSuffix;
        downlookerPrefix = tempPrefix;
        downlookerSuffix = tempSuffix;
        
        // Update the config to apply new prefix/suffix
        updateBothTerminalsConfig();
    }
    
    /**
     * Attempts to auto-connect terminals to their configured serial ports.
     */
    private void autoConnectTerminals() {
        // Use SwingUtilities.invokeLater to allow the UI to fully initialize first
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (dualTerminalMode && terminal1Panel != null) {
                terminal1Panel.autoConnect();
            }
            if (terminal2Panel != null) {
                terminal2Panel.autoConnect();
            }
        });
    }

    /**
     * Updates configuration on terminals.
     */
    private void updateBothTerminalsConfig() {
        String cruise = cruiseField.getText();
        int station = (Integer) stationSpinner.getValue();
        int cast = (Integer) castSpinner.getValue();
        String stationCast = String.format("%03d_%02d", station, cast);

        if (dualTerminalMode && terminal1Panel != null) {
            terminal1Panel.setConfig(uplookerPrefix, uplookerSuffix, cruise, stationCast);
            terminal1Panel.setDirectories(logDirectory, downloadDirectory, scriptsDirectory);
        }
        if (terminal2Panel != null) {
            terminal2Panel.setConfig(downlookerPrefix, downlookerSuffix, cruise, stationCast);
            terminal2Panel.setDirectories(logDirectory, downloadDirectory, scriptsDirectory);
        }
        
        savePreferences();
    }
    
    /**
     * Called by terminal panels when prefix/suffix changes.
     */
    public void onTerminalPrefixSuffixChanged(String terminalName, String prefix, String suffix) {
        if ("Up-looker".equals(terminalName)) {
            uplookerPrefix = prefix;
            uplookerSuffix = suffix;
        } else if ("Down-looker".equals(terminalName)) {
            downlookerPrefix = prefix;
            downlookerSuffix = suffix;
        }
        savePreferences();
    }

    /**
     * Configures serial ports for terminals.
     */
    private void configureSerialPorts() {
        Set<String> ports = SerialPortManager.listAvailablePorts();
        String[] portArray = ports.toArray(new String[0]);
        
        if (portArray.length == 0) {
            JOptionPane.showMessageDialog(this,
                    "No serial ports found.",
                    "Serial Ports",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        if (dualTerminalMode) {
            // Select port for Terminal 1 (Up-looker)
            String port1 = (String) JOptionPane.showInputDialog(this,
                    "Select port for Up-looker:",
                    "Serial Port - Up-looker",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    portArray,
                    device1);
            
            if (port1 != null) {
                device1 = port1;
                if (terminal1Panel != null) {
                    terminal1Panel.reconfigure(port1, baudRate);
                }
            }
        }
        
        // Select port for Terminal 2 (Down-looker)
        String port2 = (String) JOptionPane.showInputDialog(this,
                "Select port for Down-looker:",
                "Serial Port - Down-looker",
                JOptionPane.QUESTION_MESSAGE,
                null,
                portArray,
                device2);
        
        if (port2 != null) {
            device2 = port2;
            if (terminal2Panel != null) {
                terminal2Panel.reconfigure(port2, baudRate);
            }
        }
        
        savePreferences();
    }

    /**
     * Configures the log directory.
     */
    private void configureLogDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Log Directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        
        if (logDirectory != null) {
            chooser.setCurrentDirectory(logDirectory.toFile());
        }
        
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            logDirectory = chooser.getSelectedFile().toPath();
            updateBothTerminalsConfig();
        }
    }

    /**
     * Configures the download directory.
     */
    private void configureDownloadDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Download Directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        
        if (downloadDirectory != null) {
            chooser.setCurrentDirectory(downloadDirectory.toFile());
        }
        
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            downloadDirectory = chooser.getSelectedFile().toPath();
            updateBothTerminalsConfig();
        }
    }

    /**
     * Configures the scripts directory.
     */
    private void configureScriptsDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Scripts Directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        
        if (scriptsDirectory != null) {
            chooser.setCurrentDirectory(scriptsDirectory.toFile());
        }
        
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            scriptsDirectory = chooser.getSelectedFile().toPath();
            updateBothTerminalsConfig();
        }
    }

    /**
     * Shows current directory settings.
     */
    private void showCurrentDirectories() {
        StringBuilder sb = new StringBuilder();
        sb.append("Current Directory Settings:\n\n");
        sb.append("Log Directory: ").append(logDirectory != null ? logDirectory : "(not set)").append("\n");
        sb.append("Download Directory: ").append(downloadDirectory != null ? downloadDirectory : "(not set)").append("\n");
        sb.append("Scripts Directory: ").append(scriptsDirectory != null ? scriptsDirectory : "(not set)").append("\n");
        
        JOptionPane.showMessageDialog(this,
                sb.toString(),
                "Directory Settings",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Clears terminal displays.
     */
    private void clearTerminals() {
        if (dualTerminalMode && terminal1Panel != null) {
            terminal1Panel.clear();
        }
        if (terminal2Panel != null) {
            terminal2Panel.clear();
        }
    }

    /**
     * Clears cruise settings and resets to defaults.
     */
    private void clearCruise() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Reset all cruise settings to defaults?",
                "Clear Cruise",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        
        if (confirm == JOptionPane.YES_OPTION) {
            cruiseField.setText("XXNNNN");
            stationSpinner.setValue(0);
            castSpinner.setValue(1);
            
            // Reset prefix/suffix to defaults
            uplookerPrefix = "";
            uplookerSuffix = "s";
            downlookerPrefix = "";
            downlookerSuffix = "m";
            
            updateBothTerminalsConfig();
        }
    }

    private void closeApplication() {
        savePreferences();
        
        // Disconnect terminals
        if (terminal1Panel != null) {
            terminal1Panel.disconnect();
        }
        if (terminal2Panel != null) {
            terminal2Panel.disconnect();
        }
        
        // Shutdown executor
        executor.shutdown();
        
        dispose();
        System.exit(0);
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        configPanel = new javax.swing.JPanel();
        cruiseLabel = new javax.swing.JLabel();
        cruiseField = new javax.swing.JTextField();
        stationLabel = new javax.swing.JLabel();
        stationSpinner = new javax.swing.JSpinner();
        castLabel = new javax.swing.JLabel();
        castSpinner = new javax.swing.JSpinner();
        mainSplitPane = new javax.swing.JSplitPane();
        statusPanel = new javax.swing.JPanel();
        status1Label = new javax.swing.JLabel();
        status2Label = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("AOML LADCP Terminal");
        setPreferredSize(new java.awt.Dimension(900, 800));

        configPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8));
        configPanel.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 5));

        cruiseLabel.setText("Cruise:");
        configPanel.add(cruiseLabel);

        cruiseField.setColumns(8);
        cruiseField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                cruiseFieldFocusLost(evt);
            }
        });
        cruiseField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cruiseFieldActionPerformed(evt);
            }
        });
        configPanel.add(cruiseField);

        stationLabel.setText("Station:");
        configPanel.add(stationLabel);

        stationSpinner.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                stationSpinnerStateChanged(evt);
            }
        });
        configPanel.add(stationSpinner);

        castLabel.setText("Cast:");
        configPanel.add(castLabel);

        castSpinner.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                castSpinnerStateChanged(evt);
            }
        });
        configPanel.add(castSpinner);

        getContentPane().add(configPanel, java.awt.BorderLayout.NORTH);

        mainSplitPane.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);
        mainSplitPane.setResizeWeight(0.5);
        mainSplitPane.setOneTouchExpandable(true);
        getContentPane().add(mainSplitPane, java.awt.BorderLayout.CENTER);

        statusPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 10));
        statusPanel.setLayout(new java.awt.GridLayout(1, 2, 10, 0));

        status1Label.setText("Terminal 1: Not connected");
        statusPanel.add(status1Label);

        status2Label.setText("Terminal 2: Not connected");
        statusPanel.add(status2Label);

        getContentPane().add(statusPanel, java.awt.BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void cruiseFieldFocusLost(java.awt.event.FocusEvent evt) {//GEN-FIRST:event_cruiseFieldFocusLost
        updateBothTerminalsConfig();
    }//GEN-LAST:event_cruiseFieldFocusLost

    private void cruiseFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cruiseFieldActionPerformed
        updateBothTerminalsConfig();
    }//GEN-LAST:event_cruiseFieldActionPerformed

    private void stationSpinnerStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_stationSpinnerStateChanged
        updateBothTerminalsConfig();
    }//GEN-LAST:event_stationSpinnerStateChanged

    private void castSpinnerStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_castSpinnerStateChanged
        updateBothTerminalsConfig();
    }//GEN-LAST:event_castSpinnerStateChanged

    /**
     * Applies the saved theme from preferences.
     */
    private static void applySavedTheme() {
        Preferences prefs = Preferences.userNodeForPackage(DualTerminalFrame.class);
        String savedTheme = prefs.get(PREF_THEME, "FlatLight");
        
        try {
            switch (savedTheme) {
                case "FlatLight":
                    FlatLightLaf.setup();
                    break;
                case "FlatDark":
                    FlatDarkLaf.setup();
                    break;
                case "FlatIntelliJ":
                    FlatIntelliJLaf.setup();
                    break;
                case "FlatDarcula":
                    FlatDarculaLaf.setup();
                    break;
                case "System":
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    break;
                case "Metal":
                    UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
                    break;
                default:
                    FlatLightLaf.setup();
            }
        } catch (Exception e) {
            // Fallback to FlatLight
            try {
                FlatLightLaf.setup();
            } catch (Exception ex) {
                // Ignore
            }
        }
    }

    /**
     * Main entry point for the terminal application.
     */
    public static void main(String[] args) {
        // Apply saved theme before creating any UI components
        applySavedTheme();

        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new DualTerminalFrame().setVisible(true);
            }
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel castLabel;
    private javax.swing.JSpinner castSpinner;
    private javax.swing.JPanel configPanel;
    private javax.swing.JTextField cruiseField;
    private javax.swing.JLabel cruiseLabel;
    private javax.swing.JSplitPane mainSplitPane;
    private javax.swing.JLabel stationLabel;
    private javax.swing.JSpinner stationSpinner;
    private javax.swing.JLabel status1Label;
    private javax.swing.JLabel status2Label;
    private javax.swing.JPanel statusPanel;
    // End of variables declaration//GEN-END:variables
}
