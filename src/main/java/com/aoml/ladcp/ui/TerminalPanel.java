package com.aoml.ladcp.ui;

import com.aoml.ladcp.serial.SerialPortManager;
import com.aoml.ladcp.terminal.RDITerminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

/**
 * TerminalPanel provides a Swing-based terminal display with command history.
 * Inspired by the Python tk_terminal.py from the UHDAS LADCP Terminal.
 */
public class TerminalPanel extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(TerminalPanel.class);

    private final JTextArea displayArea;
    private final JScrollPane scrollPane;
    private final CommandEntry commandEntry;
    private final JLabel statusLabel;

    private RDITerminal terminal;
    private Timer updateTimer;
    private boolean autoScroll = true;

    /**
     * Creates a new terminal panel.
     */
    public TerminalPanel() {
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Display area
        displayArea = new JTextArea();
        displayArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        displayArea.setEditable(false);
        displayArea.setLineWrap(true);
        displayArea.setWrapStyleWord(false);
        displayArea.setBackground(Color.BLACK);
        displayArea.setForeground(Color.GREEN);
        displayArea.setCaretColor(Color.GREEN);

        scrollPane = new JScrollPane(displayArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setPreferredSize(new Dimension(700, 400));

        // Command entry
        commandEntry = new CommandEntry();
        commandEntry.setEnabled(false);

        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        inputPanel.add(new JLabel("Transmit:"), BorderLayout.WEST);
        inputPanel.add(commandEntry, BorderLayout.CENTER);

        // Status bar
        statusLabel = new JLabel("Not connected");
        statusLabel.setBorder(BorderFactory.createLoweredBevelBorder());

        // Layout
        add(scrollPane, BorderLayout.CENTER);
        add(inputPanel, BorderLayout.SOUTH);
        add(statusLabel, BorderLayout.NORTH);

        // Update timer for display refresh
        updateTimer = new Timer(100, e -> updateDisplay());
    }

    /**
     * Sets the terminal to use.
     *
     * @param terminal RDI Terminal instance
     */
    public void setTerminal(RDITerminal terminal) {
        this.terminal = terminal;
        commandEntry.setTerminal(terminal);
        terminal.addListener((event, t) -> SwingUtilities.invokeLater(() -> handleTerminalEvent(event)));
    }

    /**
     * Starts the display update timer.
     */
    public void startUpdating() {
        updateTimer.start();
    }

    /**
     * Stops the display update timer.
     */
    public void stopUpdating() {
        updateTimer.stop();
    }

    /**
     * Updates the display with new data from terminal.
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
     * Handles terminal events.
     */
    private void handleTerminalEvent(RDITerminal.TerminalEvent event) {
        switch (event) {
            case CONNECTED:
                commandEntry.setEnabled(true);
                updateStatus();
                break;
            case DISCONNECTED:
                commandEntry.setEnabled(false);
                updateStatus();
                break;
            case BAUD_CHANGED:
            case INSTRUMENT_IDENTIFIED:
                updateStatus();
                break;
            case DATA_RECEIVED:
                // Handled by timer
                break;
            case ERROR:
                // Could show error dialog
                break;
        }
    }

    /**
     * Updates the status label.
     */
    private void updateStatus() {
        if (terminal == null) {
            statusLabel.setText("Not connected");
            return;
        }

        String status = String.format("Device: %s | Baud: %d | %s | Type: %s",
                terminal.getDevicePath(),
                terminal.getBaudRate(),
                terminal.isConnected() ? "Connected" : "Disconnected",
                terminal.getInstrumentType().getCode());
        statusLabel.setText(status);
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
     * Appends text to the display.
     *
     * @param text Text to append
     */
    public void append(String text) {
        displayArea.append(text);
        if (autoScroll) {
            displayArea.setCaretPosition(displayArea.getDocument().getLength());
        }
    }

    /**
     * Gets the display text.
     *
     * @return Display contents
     */
    public String getText() {
        return displayArea.getText();
    }

    /**
     * Sets auto-scroll behavior.
     *
     * @param autoScroll true to auto-scroll
     */
    public void setAutoScroll(boolean autoScroll) {
        this.autoScroll = autoScroll;
    }

    /**
     * Gets the command entry component.
     *
     * @return Command entry field
     */
    public CommandEntry getCommandEntry() {
        return commandEntry;
    }

    /**
     * Command entry field with history support.
     */
    public static class CommandEntry extends JTextField {
        private static final Logger log = LoggerFactory.getLogger(CommandEntry.class);

        private final List<String> history = new ArrayList<>();
        private int historyIndex = 0;
        private RDITerminal terminal;

        public CommandEntry() {
            setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

            addActionListener(e -> sendCommand());

            addKeyListener(new KeyAdapter() {
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
        }

        public void setTerminal(RDITerminal terminal) {
            this.terminal = terminal;
        }

        private void sendCommand() {
            String command = getText().trim();
            if (command.isEmpty() || terminal == null) return;

            try {
                terminal.getPortManager().writeCommand(command);
                history.add(command);
                historyIndex = history.size();
                setText("");
            } catch (Exception e) {
                log.error("Error sending command", e);
                JOptionPane.showMessageDialog(this,
                        "Error sending command: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void navigateHistory(int direction) {
            if (history.isEmpty()) return;

            historyIndex += direction;
            if (historyIndex < 0) {
                historyIndex = 0;
            } else if (historyIndex >= history.size()) {
                historyIndex = history.size();
                setText("");
                return;
            }

            setText(history.get(historyIndex));
        }
    }
}
