package com.github.dmadapter.gui;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class DmAdapterGui {
    private DmAdapterGui() {
    }

    public static void main(String[] args) {
        configureLookAndFeel();
        SwingUtilities.invokeLater(() -> new DmAdapterFrame().setVisible(true));
    }

    private static void configureLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing's cross-platform look and feel remains available.
        }
    }
}
