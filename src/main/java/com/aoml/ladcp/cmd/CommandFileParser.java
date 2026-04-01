package com.aoml.ladcp.cmd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for RDI ADCP command files.
 * Handles both BBTALK format and standard command files.
 */
public class CommandFileParser {
    private static final Logger log = LoggerFactory.getLogger(CommandFileParser.class);

    /**
     * Parses a command file and returns validated commands.
     * Removes comments (after # or ;), BBTALK $ prefixes, and CK/CS commands.
     *
     * @param file Path to command file
     * @return List of validated commands
     * @throws IOException if file cannot be read
     */
    public static List<String> parse(Path file) throws IOException {
        return parse(Files.readAllLines(file));
    }

    /**
     * Parses command lines from an input stream.
     *
     * @param inputStream Input stream containing commands
     * @return List of validated commands
     * @throws IOException if stream cannot be read
     */
    public static List<String> parse(InputStream inputStream) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return parse(lines);
    }

    /**
     * Parses a list of command lines.
     *
     * @param lines Raw command lines
     * @return List of validated commands
     */
    public static List<String> parse(List<String> lines) {
        List<String> commands = new ArrayList<>();

        for (String line : lines) {
            String processed = processLine(line);
            if (processed != null && !processed.isEmpty()) {
                // Skip CK and CS commands (we add these manually at the end)
                if (!processed.startsWith("CK") && !processed.startsWith("CS")) {
                    commands.add(processed);
                }
            }
        }

        log.debug("Parsed {} commands from {} lines", commands.size(), lines.size());
        return commands;
    }

    /**
     * Processes a single command line.
     *
     * @param line Raw command line
     * @return Processed command or null if line should be skipped
     */
    private static String processLine(String line) {
        if (line == null) return null;

        // Remove comments (everything after #)
        int hashIndex = line.indexOf('#');
        if (hashIndex >= 0) {
            line = line.substring(0, hashIndex);
        }

        // Remove BBTALK-style comments (everything after ;)
        int semiIndex = line.indexOf(';');
        if (semiIndex >= 0) {
            line = line.substring(0, semiIndex);
        }

        // Remove BBTALK-style $ prefix lines
        int dollarIndex = line.indexOf('$');
        if (dollarIndex >= 0) {
            line = line.substring(0, dollarIndex);
        }

        return line.trim();
    }

    /**
     * Validates a single command string.
     * Returns true if the command appears to be a valid RDI command.
     *
     * @param command Command to validate
     * @return true if valid
     */
    public static boolean isValidCommand(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }

        // RDI commands are typically 2-3 uppercase letters followed by optional parameters
        // Examples: PS0, TS?, CF11101, WM15, etc.
        return command.matches("^[A-Z]{1,3}.*$");
    }

    /**
     * Common RDI ADCP commands for reference.
     */
    public static class Commands {
        // System commands
        public static final String BREAK = "+++";  // Actually sent as break signal
        public static final String SLEEP = "CZ";
        public static final String SAVE_PARAMS = "CK";
        public static final String START_DEPLOY = "CS";

        // Query commands
        public static final String TIME_QUERY = "TS?";
        public static final String RECORDER_AVAILABLE = "RA";
        public static final String RECORDER_SPACE = "RS";
        public static final String RECORDER_FORMAT = "RF";
        public static final String RECORDER_DIRECTORY = "RR";

        // Configuration display
        public static final String SYSTEM_CONFIG = "PS0";
        public static final String HARDWARE_CONFIG = "PS3";

        // Data transfer
        public static final String YMODEM_DOWNLOAD = "RY";
        public static final String ERASE_RECORDER = "RE ErAsE";

        // Diagnostics
        public static final String PRE_DEPLOY_TEST = "PT200";

        /**
         * Creates a time set command for the given time string.
         *
         * @param timeStr Time in yyMMddHHmmss format
         * @return TS command
         */
        public static String setTime(String timeStr) {
            return "TS" + timeStr;
        }

        /**
         * Creates a baud rate change command.
         *
         * @param baudCode RDI baud code (0-8)
         * @return CB command
         */
        public static String changeBaud(int baudCode) {
            return String.format("CB%d11", baudCode);
        }

        /**
         * Creates a YModem download command for a specific file.
         *
         * @param fileNum File number to download
         * @return RY command
         */
        public static String ymodemDownload(int fileNum) {
            return "RY" + fileNum;
        }
    }
}
