/**
 *
 * NORD POS is a fork of Openbravo POS.
 *
 * Copyright (C) 2009-2013 Nord Trading Ltd. <http://www.nordpos.com>
 *
 * This file is part of NORD POS.
 *
 * NORD POS is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * NORD POS is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * NORD POS. If not, see <http://www.gnu.org/licenses/>.
 */
package com.nordpos.device.receiptprinter;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * A receipt printer that could not be created, rendered as a visible tab in
 * the Printers view instead of a silent Null device.
 *
 * JPanelPrinter only builds a tab for a device whose getPrinterComponent()
 * is non-null, and nothing in the tree ever calls getPrinterDescription(), so
 * a DevicePrinterNull carrying a message is a black hole. Being a JPanel is
 * what makes the reason reach the operator, with no .form edit and no change
 * to JPanelPrinter.
 *
 * Constructed on the EDT by DeviceTicketFactory during start up, so Swing
 * construction is legal here, but it must do nothing else: no timers, no
 * threads, no I/O.
 *
 * @author Andrey Svininykh <svininykh@gmail.com>
 * @version NORD POS 4.0
 */
public class DevicePrinterUnavailable extends JPanel implements DevicePrinter {

    private final String m_sReason;

    public DevicePrinterUnavailable(String sReason) {

        m_sReason = sReason;

        setLayout(new BorderLayout());

        JTextArea jReason = new JTextArea();
        jReason.setEditable(false);
        jReason.setOpaque(false);
        jReason.setLineWrap(true);
        jReason.setWrapStyleWord(true);
        jReason.setFont(new Font("SansSerif", Font.PLAIN, 12));
        // Verbatim: the message names the exact configuration strings the
        // operator has to type instead, so it must not be reflowed or cut.
        jReason.setText(sReason);
        jReason.setCaretPosition(0);

        add(new JScrollPane(jReason), BorderLayout.CENTER);
    }

    @Override
    public String getPrinterName() {
        return "ReceiptPrinter.Unavailable";
    }

    @Override
    public String getPrinterDescription() {
        return m_sReason;
    }

    @Override
    public JComponent getPrinterComponent() {
        return this;
    }

    @Override
    public void reset() {
    }

    @Override
    public void beginReceipt() {
    }

    @Override
    public void printBarCode(String type, String position, String code) {
    }

    @Override
    public void printImage(BufferedImage image) {
    }

    @Override
    public void beginLine(Integer iTextSize) {
    }

    @Override
    public void printText(Integer iCharacterSize, String sUnderlineType, Boolean bBold, String sText) {
    }

    @Override
    public void endLine() {
    }

    @Override
    public void endReceipt() {
    }

    @Override
    public void openDrawer() {
    }

    @Override
    public void cutPaper(boolean complete) {
    }

}
