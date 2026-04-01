package com.aoml.ladcp;

import com.aoml.ladcp.ui.LADCPTerminalFrame;
import com.aoml.ladcp.ui.DualTerminalFrame;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.nio.file.*;
import java.util.prefs.Preferences;

/**
 * Main application entry point for AOML LADCP Terminal.
 * 
 * This application is inspired by the original Python UHDAS LADCP Terminal
 * developed by NOAA/University of Hawaii for oceanographic research.
 * 
 * Usage:
 *   java -jar ladcp-terminal.jar [options]
 * 
 * Options:
 *   --single              Use single terminal mode (default is dual)
 *   -d, --device PATH     Serial device path (default: /dev/ttyUSB0)
 *   -d2, --device2 PATH   Second device for dual mode (default: /dev/ttyUSB1)
 *   -b, --baud RATE       Baud rate (default: 9600)
 *   --download RATE       Download baud rate
 *   -p, --prefix PREFIX   File prefix (default: ladcp)
 *   -s, --stacast CODE    Station_Cast code (default: 000_00)
 *   -c, --command FILE    Command file path
 *   -e, --ext EXT         Data file extension (default: dat)
 *   --backup DIR          Backup directory
 *   -h, --help            Show help
 */
public class LADCPTerminalApp {
    private static final Logger log = LoggerFactory.getLogger(LADCPTerminalApp.class);

    private static final String VERSION = "1.0.0";
    private static final String DEFAULT_DEVICE = "/dev/ttyUSB0";
    private static final String DEFAULT_DEVICE2 = "/dev/ttyUSB1";
    private static final int DEFAULT_BAUD = 9600;

    public static void main(String[] args) {
        log.info("Starting AOML LADCP Terminal v{}", VERSION);

        // Parse command line arguments
        Config config = parseArgs(args);
        if (config == null) {
            return;
        }

        // Apply saved theme before creating any UI components
        applySavedTheme();

        // Launch the application on the EDT
        final Config finalConfig = config;
        SwingUtilities.invokeLater(() -> {
            try {
                if (finalConfig.singleMode) {
                    LADCPTerminalFrame frame = new LADCPTerminalFrame();
                    frame.setVisible(true);
                } else {
                    // Default is dual mode
                    DualTerminalFrame frame = new DualTerminalFrame();
                    frame.setVisible(true);
                }
            } catch (Exception e) {
                log.error("Failed to start application", e);
                JOptionPane.showMessageDialog(null,
                        "Failed to start application: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }

    /**
     * Parses command line arguments.
     */
    private static Config parseArgs(String[] args) {
        Config config = new Config();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if (arg.equals("-h") || arg.equals("--help")) {
                printHelp();
                return null;
            } else if (arg.equals("--single")) {
                config.singleMode = true;
            } else if (arg.equals("--dual")) {
                // Kept for backwards compatibility, but it's now the default
                config.singleMode = false;
            } else if (arg.equals("-d") || arg.equals("--device")) {
                if (i + 1 < args.length) {
                    config.device = args[++i];
                }
            } else if (arg.equals("-d2") || arg.equals("--device2")) {
                if (i + 1 < args.length) {
                    config.device2 = args[++i];
                }
            } else if (arg.equals("-b") || arg.equals("--baud")) {
                if (i + 1 < args.length) {
                    config.baud = Integer.parseInt(args[++i]);
                }
            } else if (arg.equals("--download")) {
                if (i + 1 < args.length) {
                    config.dataBaud = Integer.parseInt(args[++i]);
                }
            } else if (arg.equals("-p") || arg.equals("--prefix")) {
                if (i + 1 < args.length) {
                    config.prefix = args[++i];
                }
            } else if (arg.equals("-s") || arg.equals("--stacast")) {
                if (i + 1 < args.length) {
                    config.stationCast = args[++i];
                }
            } else if (arg.equals("-c") || arg.equals("--command")) {
                if (i + 1 < args.length) {
                    config.commandFile = Paths.get(args[++i]);
                }
            } else if (arg.equals("-e") || arg.equals("--ext")) {
                if (i + 1 < args.length) {
                    config.dataFileExt = args[++i];
                }
            } else if (arg.equals("--backup")) {
                if (i + 1 < args.length) {
                    config.backupDir = Paths.get(args[++i]);
                }
            } else if (arg.equals("--cruise")) {
                if (i + 1 < args.length) {
                    config.cruiseName = args[++i];
                }
            } else if (arg.equals("--suffix")) {
                if (i + 1 < args.length) {
                    config.suffix = args[++i];
                }
            } else if (arg.startsWith("-")) {
                System.err.println("Unknown option: " + arg);
                printHelp();
                return null;
            }
        }

        return config;
    }

    /**
     * Prints help message.
     */
    private static void printHelp() {
        StringBuilder help = new StringBuilder();
        help.append("AOML LADCP Terminal v").append(VERSION).append("\n\n");
        help.append("A Java terminal application for RDI ADCP instruments.\n\n");
        help.append("Usage:\n");
        help.append("  java -jar ladcp-terminal.jar [options]\n\n");
        help.append("Options:\n");
        help.append("  --single              Use single terminal mode (default is dual)\n");
        help.append("  -d, --device PATH     Serial device path (default: ").append(DEFAULT_DEVICE).append(")\n");
        help.append("  -d2, --device2 PATH   Second device for dual mode (default: ").append(DEFAULT_DEVICE2).append(")\n");
        help.append("  -b, --baud RATE       Communication baud rate (default: ").append(DEFAULT_BAUD).append(")\n");
        help.append("  --download RATE       Download baud rate (default: auto-detect)\n");
        help.append("  -p, --prefix PREFIX   File prefix for downloads (default: ladcp)\n");
        help.append("  --suffix SUFFIX       File suffix for downloads\n");
        help.append("  --cruise NAME         Cruise name (default: XXNNNN)\n");
        help.append("  -s, --stacast CODE    Station_Cast code (default: 000_00)\n");
        help.append("  -c, --command FILE    Default command file path\n");
        help.append("  -e, --ext EXT         Data file extension (default: dat)\n");
        help.append("  --backup DIR          Backup directory for downloads\n");
        help.append("  -h, --help            Show this help message\n\n");
        help.append("Examples:\n");
        help.append("  java -jar ladcp-terminal.jar                    # Dual terminal mode (default)\n");
        help.append("  java -jar ladcp-terminal.jar --single           # Single terminal mode\n");
        help.append("  java -jar ladcp-terminal.jar -d /dev/ttyUSB0 -b 9600\n");
        help.append("  java -jar ladcp-terminal.jar -d /dev/ttyUSB0 -d2 /dev/ttyUSB1\n\n");
        help.append("Serial Port Names:\n");
        help.append("  Linux:   /dev/ttyUSB0, /dev/ttyS0, etc.\n");
        help.append("  macOS:   /dev/cu.usbserial-xxx, /dev/tty.usbserial-xxx\n");
        help.append("  Windows: COM1, COM2, COM3, etc.\n\n");
        help.append("For more information, see the README.md file.\n");
        
        System.out.println(help);
    }

    /**
     * Applies the saved theme from preferences.
     */
    private static void applySavedTheme() {
        // Get theme from DualTerminalFrame preferences (same preference store)
        Preferences prefs = Preferences.userNodeForPackage(DualTerminalFrame.class);
        String savedTheme = prefs.get("theme", "FlatLight");
        
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
            log.info("Applied theme: {}", savedTheme);
        } catch (Exception e) {
            log.warn("Failed to apply saved theme '{}', using FlatLight", savedTheme, e);
            try {
                FlatLightLaf.setup();
            } catch (Exception ex) {
                // Ignore
            }
        }
    }

    /**
     * Configuration holder class.
     */
    private static class Config {
        boolean singleMode = false;  // Default is dual mode
        String device = DEFAULT_DEVICE;
        String device2 = DEFAULT_DEVICE2;
        int baud = DEFAULT_BAUD;
        Integer dataBaud = null;
        String prefix = "ladcp";
        String suffix = "";
        String cruiseName = "XXNNNN";
        String stationCast = "000_00";
        String dataFileExt = "dat";
        Path commandFile = null;
        Path backupDir = null;
    }
}
