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

import com.nordpos.device.ticket.TicketPrinterException;
import com.nordpos.device.util.BarcodeImage;
import com.nordpos.device.util.BarcodeString;
import com.nordpos.device.writter.Writter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * An ESC/POS thermal receipt printer, driven over any {@link Writter}
 * transport (TCP port 9100, an OS print queue, a device file or a serial
 * port).
 *
 * A whole receipt is buffered and handed to the transport as exactly ONE
 * freshly allocated array in {@link #endReceipt()}. Writter.write() is
 * fire-and-forget on a private single-thread executor, so an array that has
 * been handed over must never be touched again; one array per receipt also
 * makes a receipt atomic on a socket and one document per print job on a
 * queue.
 *
 * Every byte emitted here is written as a hex literal with the command it
 * implements named in the trailing comment.
 *
 * @author Andrey Svininykh <svininykh@gmail.com>
 * @version NORD POS 4.0
 */
public class DevicePrinterESCPOS implements DevicePrinter {

    private static final Logger logger = Logger.getLogger(DevicePrinterESCPOS.class.getName());

    private static final Charset ASCII = Charset.forName("US-ASCII");

    /**
     * GS ! n, the character size byte, indexed by the template's 0..3 size.
     * Low nibble is the height magnification, high nibble the width.
     */
    private static final int[] SIZE_BYTE = {
        0x00, // normal
        0x01, // double height
        0x10, // double width
        0x11 // double height and width
    };

    /**
     * GS k m, the ESC/POS Function B symbology selectors. Function A (the
     * NUL-terminated form) is never used.
     */
    private static final int GS_K_EAN13 = 0x43; // 67
    private static final int GS_K_EAN8 = 0x44; // 68
    private static final int GS_K_CODE39 = 0x45; // 69
    private static final int GS_K_CODE128 = 0x49; // 73

    private static final int GS_K_MAX_DATA = 255;

    private final Writter m_out;
    private final ESCPOSConfig m_config;
    private final String m_sTarget;
    private final ByteArrayOutputStream m_buffer;

    private boolean m_bLineOpen;
    private int m_iLineSize;
    private boolean m_bPendingCut;
    private boolean m_bPendingCutComplete;

    /**
     * Built on first request and then reused, so a printer whose tab is never
     * opened costs no Swing object at all. Guarded by this.
     */
    private StatusPanel m_jStatus;

    /**
     * Opens, connects to and probes NOTHING. DeviceTicketFactory builds every
     * printer on the EDT during JRootApp start up, so a constructor that
     * blocked on an unreachable printer would freeze the whole application
     * until the TCP timeout.
     *
     * @param out the transport, already constructed but not yet connected
     * @param config the resolved option set
     * @param sTargetDescription human readable target, shown in the UI
     * @throws TicketPrinterException if out or config is null
     */
    public DevicePrinterESCPOS(Writter out, ESCPOSConfig config, String sTargetDescription)
            throws TicketPrinterException {

        if (out == null) {
            throw new TicketPrinterException("ESC/POS printer needs a transport.");
        }
        if (config == null) {
            throw new TicketPrinterException("ESC/POS printer needs a configuration.");
        }

        m_out = out;
        m_config = config;
        m_sTarget = sTargetDescription;
        m_buffer = new ByteArrayOutputStream();
        m_bLineOpen = false;
        m_iLineSize = 0;
        m_bPendingCut = false;
        m_bPendingCutComplete = false;
    }

    @Override
    public String getPrinterName() {
        // No locale key exists for any driver's printer name in this tree;
        // JPanelPrinter renders this string raw as a tab title.
        return "ReceiptPrinter.EscPos";
    }

    @Override
    public String getPrinterDescription() {

        StringBuilder sb = new StringBuilder(m_sTarget == null ? "" : m_sTarget);

        String sConfigErrors = m_config.getErrors();
        if (sConfigErrors != null) {
            sb.append(" \u2014 ").append(sConfigErrors);
        }

        String sLastError = m_out.getLastError();
        if (sLastError != null) {
            sb.append(" \u2014 ").append(sLastError);
        }

        return sb.toString();
    }

    /**
     * The only surface on which an operator can see that this printer is
     * unreachable. JPanelPrinter builds a tab exactly for the devices whose
     * component is non-null, and nothing in the tree calls
     * getPrinterDescription(), so returning null sent every actionable
     * message written by the transport to the log and nowhere else.
     *
     * Called on the EDT: JPanelPrinter walks the printers when the Printers
     * view is built. No timer and no thread is started here; the panel simply
     * re-reads the target, the configuration errors and the transport's last
     * error every time it is painted.
     *
     * @return a panel showing the target and the current error text
     */
    @Override
    public JComponent getPrinterComponent() {
        synchronized (this) {
            if (m_jStatus == null) {
                m_jStatus = new StatusPanel();
            }
            return m_jStatus;
        }
    }

    /**
     * The text the status panel shows, and the same three facts
     * getPrinterDescription() reports, laid out one per line.
     *
     * @return never null, never empty
     */
    private String buildStatusText() {

        StringBuilder sb = new StringBuilder();
        sb.append("ESC/POS receipt printer").append('\n');
        sb.append("Target: ").append(m_sTarget == null || m_sTarget.isEmpty() ? "(not configured)" : m_sTarget);

        String sConfigErrors = m_config.getErrors();
        if (sConfigErrors != null) {
            sb.append('\n').append("Options: ").append(sConfigErrors);
        }

        String sLastError = m_out.getLastError();
        if (sLastError == null) {
            sb.append('\n').append("Status: no error reported for the last job sent.");
        } else {
            sb.append('\n').append("Status: ").append(sLastError);
        }

        return sb.toString();
    }

    /**
     * A read-only text panel that refreshes itself from the driver every time
     * it is painted. Swing repaints the visible tab whenever it is exposed,
     * selected or resized, so an operator who reopens the Printers view after
     * a failed sale sees the current message with no polling anywhere.
     */
    private final class StatusPanel extends JPanel {

        private final JTextArea m_jText;

        StatusPanel() {

            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            m_jText = new JTextArea();
            m_jText.setEditable(false);
            m_jText.setOpaque(false);
            m_jText.setLineWrap(true);
            m_jText.setWrapStyleWord(true);
            m_jText.setFont(new Font("SansSerif", Font.PLAIN, 12));
            // Verbatim: these messages name the exact host, port, queue or
            // option the operator has to correct, so nothing may be reflowed
            // away or truncated.
            m_jText.setText(buildStatusText());
            m_jText.setCaretPosition(0);

            JScrollPane jScroll = new JScrollPane(m_jText);
            jScroll.setBorder(BorderFactory.createEmptyBorder());
            add(jScroll, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g) {
            // setText() only when the text actually changed: an unconditional
            // setText() schedules a repaint, and a repaint that always sets
            // the text would repaint for ever.
            String sNow = buildStatusText();
            if (!sNow.equals(m_jText.getText())) {
                m_jText.setText(sNow);
                m_jText.setCaretPosition(0);
            }
            super.paintComponent(g);
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void beginReceipt() {

        m_buffer.reset();
        m_bLineOpen = false;
        m_iLineSize = 0;
        m_bPendingCut = false;
        m_bPendingCutComplete = false;

        cmd(0x1B, 0x40); // ESC @      initialise; clears bold, underline, size, alignment
        cmd(0x1B, 0x74, m_config.getCodePageByte() & 0xFF); // ESC t n    select character code table

        if (m_config.getProfile() == ESCPOSConfig.PROFILE_EPSON) {
            cmd(0x1B, 0x52, 0x00); // ESC R 0    international character set: USA
        }

        cmd(0x1B, 0x61, 0x00); // ESC a 0    justification: left
        cmd(0x1B, 0x21, 0x00); // ESC ! 0    print mode: font A, no emphasis, no double strike

        if (m_config.getProfile() == ESCPOSConfig.PROFILE_EPSON) {
            // GS a 0   disable Automatic Status Back. Nothing in this driver
            // ever reads from the printer, so unsolicited status bytes would
            // only fill a socket buffer nobody drains.
            cmd(0x1D, 0x61, 0x00);
        }

        if (m_config.isFontB()) {
            cmd(0x1B, 0x4D, 0x01); // ESC M 1    select font B (fits 42 columns on 58 mm paper)
        }

        int iLogo = m_config.getNvLogo();
        if (iLogo != 0) {
            cmd(0x1C, 0x70, iLogo & 0xFF, 0x00); // FS p n 0   print NV bit image n, normal mode
        }
    }

    @Override
    public void beginLine(Integer iTextSize) {

        int iSize = (iTextSize == null) ? 0 : iTextSize.intValue();
        if (iSize < 0 || iSize > 3) {
            iSize = 0;
        }

        m_bLineOpen = true;
        m_iLineSize = iSize;

        if (iSize != 0) {
            // Size 0 deliberately emits nothing: an ordinary line must not
            // carry command noise, and ESC @ already selected normal size.
            cmd(0x1D, 0x21, SIZE_BYTE[iSize]); // GS ! n     character size
        }
    }

    @Override
    public void printText(Integer iCharacterSize, String sUnderlineType, Boolean bBold, String sText) {

        if (sText == null) {
            return;
        }

        byte[] bEncoded = m_config.encode(sText);
        if (bEncoded.length == 0) {
            // Templates contain elements such as <text length="17"></text>.
            // Bracketing nothing in bold would be pure command noise.
            return;
        }

        // TicketParser.parseInteger returns the sentinel 12 whenever the size
        // attribute is absent or unparseable, and the XSD gives <text size> no
        // default, so nearly every run arrives with 12. Only a genuine 0..3 is
        // honoured; anything else means "whatever the line already selected".
        boolean bSizeOverride = (iCharacterSize != null)
                && iCharacterSize.intValue() >= 0
                && iCharacterSize.intValue() <= 3;

        boolean bEmphasis = Boolean.TRUE.equals(bBold);
        int iUnderline = underlineByte(sUnderlineType);

        if (bSizeOverride) {
            cmd(0x1D, 0x21, SIZE_BYTE[iCharacterSize.intValue()]); // GS ! n     character size
        }
        if (bEmphasis) {
            cmd(0x1B, 0x45, 0x01); // ESC E 1    emphasised (bold) on
        }
        if (iUnderline != 0) {
            cmd(0x1B, 0x2D, iUnderline); // ESC - n    underline on, 1 or 2 dots
        }

        // No ESC a here. TicketParser has already aligned and space padded
        // this run to the template's length with StringUtils.alignLeft/
        // alignRight/alignCenter; hardware justification on top of that would
        // double align and wrap every receipt.
        emit(bEncoded);

        if (iUnderline != 0) {
            cmd(0x1B, 0x2D, 0x00); // ESC - 0    underline off
        }
        if (bEmphasis) {
            cmd(0x1B, 0x45, 0x00); // ESC E 0    emphasised off
        }
        if (bSizeOverride) {
            // Restore the CURRENT LINE's size, not the printer default, so a
            // size="2" line keeps its size after an inline size="1" run.
            cmd(0x1D, 0x21, SIZE_BYTE[m_iLineSize]); // GS ! n     character size
        }
    }

    @Override
    public void endLine() {

        cmd(0x0A); // LF         print and line feed

        if (m_iLineSize != 0) {
            // After the LF, so the feed distance is still the tall one.
            cmd(0x1D, 0x21, 0x00); // GS ! 0     back to normal character size
        }

        m_bLineOpen = false;
        m_iLineSize = 0;
    }

    @Override
    public void printImage(BufferedImage image) {

        if (image == null) {
            return;
        }

        try {
            cmd(0x1B, 0x61, 0x01); // ESC a 1    justification: centre
            raster(image);
            cmd(0x1B, 0x61, 0x00); // ESC a 0    justification: left
            // No trailing LF: GS v 0 leaves the position at the start of the
            // next line already.
        } catch (Throwable t) {
            logger.log(Level.WARNING, "ESC/POS could not raster an image for " + m_sTarget, t);
        }
    }

    @Override
    public void printBarCode(String sType, String sPosition, String sCode) {

        try {
            String sBarcode = (sCode == null || sCode.isEmpty()) ? "0" : sCode;
            String sSymbology = normaliseSymbology(sType);

            boolean bRaster = m_config.isBarcodeRaster()
                    || BARCODE_QRCODE.equals(sSymbology)
                    || BARCODE_DATAMATRIX.equals(sSymbology);

            if (bRaster) {
                // 2D symbologies are rendered with the already vendored
                // barcode4j / zxing and printed through the raster path.
                // GS ( k is never emitted: it is absent from most low cost
                // clones, where unrecognised bytes print as literal garbage.
                BufferedImage image = renderBarcodeImage(sSymbology, sBarcode);
                if (image == null) {
                    return;
                }
                cmd(0x1B, 0x61, 0x01); // ESC a 1    justification: centre
                raster(image);
                cmd(0x1B, 0x61, 0x00); // ESC a 0    justification: left
            } else {
                cmd(0x1B, 0x61, 0x01); // ESC a 1    justification: centre
                printBarCodeNative(sSymbology, sPosition, sBarcode);
                cmd(0x1B, 0x61, 0x00); // ESC a 0    justification: left
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "ESC/POS could not print a barcode for " + m_sTarget, t);
        }
    }

    @Override
    public void cutPaper(boolean bComplete) {

        if (m_bLineOpen) {
            // TicketParser's "case text:" falls through into "case cutpaper:"
            // with no break, so cutPaper fires once before EVERY <text>
            // element. Printer.Ticket.xml has 60+ of them and no <cutpaper/>
            // at all. Per the XSD a <text> only ever appears inside a <line>
            // and a <cutpaper/> only as a direct child of <ticket>, so an open
            // line separates the spurious calls from the genuine ones with
            // certainty. Acting on them would shred the roll.
            return;
        }

        if (m_config.getCut() == ESCPOSConfig.CUT_TEMPLATE) {
            m_bPendingCut = true;
            m_bPendingCutComplete = bComplete;
        }
        // In every other cut mode the cut is the driver's own endReceipt
        // policy and this call is ignored. No cut byte is ever emitted here.
    }

    @Override
    public void endReceipt() {

        if (m_bLineOpen) {
            cmd(0x0A); // LF         defensive: close a line the template left open
            m_bLineOpen = false;
            m_iLineSize = 0;
        }

        int iFeed = m_config.getFeed();
        if (iFeed != 0) {
            cmd(0x1B, 0x64, iFeed & 0xFF); // ESC d n    feed n lines
        }

        switch (m_config.getCut()) {
            case ESCPOSConfig.CUT_PARTIAL:
                cmd(0x1D, 0x56, 0x01); // GS V 1     partial cut
                break;
            case ESCPOSConfig.CUT_FULL:
                cmd(0x1D, 0x56, 0x00); // GS V 0     full cut
                break;
            case ESCPOSConfig.CUT_TEMPLATE:
                if (m_bPendingCut) {
                    if (m_bPendingCutComplete) {
                        cmd(0x1D, 0x56, 0x00); // GS V 0     full cut
                    } else {
                        cmd(0x1D, 0x56, 0x01); // GS V 1     partial cut
                    }
                }
                break;
            case ESCPOSConfig.CUT_NONE:
            default:
                break;
        }

        m_bPendingCut = false;
        m_bPendingCutComplete = false;

        byte[] bJob = m_buffer.toByteArray();
        m_buffer.reset();
        m_out.write(bJob);
        m_out.flush();
    }

    @Override
    public void openDrawer() {

        // TicketParser calls this with no surrounding beginReceipt/endReceipt,
        // but a template may also place <opendrawer/> inside a ticket.
        if (m_buffer.size() > 0) {
            emit(drawerKick());
            return;
        }

        // ESC @ first, so a printer left mid command by an aborted job does
        // not swallow the kick as a parameter byte.
        cmd(0x1B, 0x40); // ESC @      initialise
        emit(drawerKick());

        byte[] bJob = m_buffer.toByteArray();
        m_buffer.reset();
        m_out.write(bJob);
        m_out.flush();
    }

    /**
     * @return the pulse command for the configured drawer connector
     */
    private byte[] drawerKick() {

        // t1 is the ON time and t2 the OFF time, both in units of 2 ms.
        int iT1 = m_config.getDrawerPulseMillis() / 2;

        switch (m_config.getDrawer()) {
            case ESCPOSConfig.DRAWER_PIN5:
                // ESC p 1 t1 t2   generate pulse on connector pin 5
                return new byte[]{0x1B, 0x70, 0x01, (byte) iT1, (byte) 0xFA};
            case ESCPOSConfig.DRAWER_REALTIME:
                // DLE DC4 1 m t   real time drawer kick, m=0 (pin 2), t=5 (100 ms)
                return new byte[]{0x10, 0x14, 0x01, 0x00, 0x05};
            case ESCPOSConfig.DRAWER_PIN2:
            default:
                // ESC p 0 t1 t2   generate pulse on connector pin 2
                return new byte[]{0x1B, 0x70, 0x00, (byte) iT1, (byte) 0xFA};
        }
    }

    /**
     * ESC/POS offers exactly two underline thicknesses, so "average" and
     * "thick" are necessarily identical on thermal hardware.
     *
     * @return the n of ESC - n, or 0 for no underline
     */
    private static int underlineByte(String sUnderlineType) {

        if (sUnderlineType == null) {
            return 0;
        }

        switch (sUnderlineType) {
            case "slim":
                return 0x01;
            case "average":
            case "thick":
                return 0x02;
            case "none":
            default:
                return 0;
        }
    }

    /**
     * Mirrors PrintItemBarcode's own dispatch, including its default, so the
     * thermal symbol and the on screen preview always agree.
     */
    private static String normaliseSymbology(String sType) {

        if (sType == null) {
            return BARCODE_EAN13;
        }

        switch (sType) {
            case BARCODE_EAN8:
            case BARCODE_CODE39:
            case BARCODE_CODE128:
            case BARCODE_DATAMATRIX:
            case BARCODE_QRCODE:
            case BARCODE_EAN13:
                return sType;
            default:
                return BARCODE_EAN13;
        }
    }

    private void printBarCodeNative(String sSymbology, String sPosition, String sCode) {

        cmd(0x1D, 0x48, hriPosition(sPosition)); // GS H n     HRI character position
        cmd(0x1D, 0x66, 0x00); // GS f 0     HRI font A
        cmd(0x1D, 0x68, m_config.getBarcodeHeight() & 0xFF); // GS h n     barcode height in dots
        cmd(0x1D, 0x77, m_config.getBarcodeWidth() & 0xFF); // GS w n     barcode module width

        int iSelector;
        String sData;

        switch (sSymbology) {
            case BARCODE_EAN8:
                iSelector = GS_K_EAN8;
                // 7 digits; the printer computes the check digit.
                sData = BarcodeString.getBarcodeStringEAN8(sCode);
                break;
            case BARCODE_CODE39:
                iSelector = GS_K_CODE39;
                sData = BarcodeString.getBarcodeStringCode39(sCode);
                break;
            case BARCODE_CODE128:
                iSelector = GS_K_CODE128;
                sData = code128Payload(sCode);
                break;
            case BARCODE_EAN13:
            default:
                iSelector = GS_K_EAN13;
                // 12 digits; the printer computes the check digit.
                sData = BarcodeString.getBarcodeStringEAN13(sCode);
                break;
        }

        if (sData == null) {
            sData = "";
        }
        if (sData.length() > GS_K_MAX_DATA) {
            // BarcodeString.getBarcodeStringCode128 discards the result of its
            // own substring call, so its >255 truncation is a live no-op.
            // Clamp here rather than repairing it, or the screen path and the
            // thermal path would render different symbols.
            sData = sData.substring(0, GS_K_MAX_DATA);
        }

        // Barcode payloads are always US-ASCII, never the receipt code page,
        // so the symbol is identical to the one the preview renders.
        byte[] bData = sData.getBytes(ASCII);

        cmd(0x1D, 0x6B, iSelector, bData.length); // GS k m n d1..dn   print barcode, Function B
        emit(bData);
    }

    /**
     * @return the n of GS H n: 0 none, 1 above, 2 below
     */
    private static int hriPosition(String sPosition) {

        if (sPosition == null) {
            return 0x02;
        }

        switch (sPosition) {
            case POSITION_NONE:
                return 0x00;
            case POSITION_TOP:
                return 0x01;
            case POSITION_BOTTOM:
            default:
                return 0x02;
        }
    }

    /**
     * Builds the GS k Function B payload for CODE128: the "{B" code set
     * selector followed by the normalised message with every literal '{'
     * doubled, clamped to 255 bytes without ever splitting an escape pair.
     */
    private static String code128Payload(String sCode) {

        String sNormalised = BarcodeString.getBarcodeStringCode128(sCode);
        if (sNormalised == null) {
            sNormalised = "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append('{').append('B'); // code set B

        for (int i = 0; i < sNormalised.length(); i++) {
            char c = sNormalised.charAt(i);
            // '{' introduces an ESC/POS code set escape, so a literal one in
            // the payload has to be doubled.
            int iUnit = (c == '{') ? 2 : 1;
            if (sb.length() + iUnit > GS_K_MAX_DATA) {
                break;
            }
            if (iUnit == 2) {
                sb.append('{');
            }
            sb.append(c);
        }

        return sb.toString();
    }

    /**
     * Renders a symbology to a bitmap with the vendored barcode4j / zxing.
     * The helpers apply BarcodeString themselves, so the raw code goes in.
     *
     * @return null when the helper could not produce an image
     */
    private static BufferedImage renderBarcodeImage(String sSymbology, String sCode) {

        Image image;

        switch (sSymbology) {
            case BARCODE_QRCODE:
                image = BarcodeImage.getQRCode(sCode);
                break;
            case BARCODE_DATAMATRIX:
                image = BarcodeImage.getDataMatrix(sCode);
                break;
            case BARCODE_EAN8:
                image = BarcodeImage.getBarcodeEAN8(sCode);
                break;
            case BARCODE_CODE39:
                image = BarcodeImage.getBarcodeCode39(sCode);
                break;
            case BARCODE_CODE128:
                image = BarcodeImage.getBarcode128(sCode);
                break;
            case BARCODE_EAN13:
            default:
                image = BarcodeImage.getBarcodeEAN13(sCode);
                break;
        }

        return toBufferedImage(image);
    }

    private static BufferedImage toBufferedImage(Image image) {

        if (image == null) {
            return null;
        }

        int iWidth = image.getWidth(null);
        int iHeight = image.getHeight(null);
        if (iWidth <= 0 || iHeight <= 0) {
            return null;
        }

        BufferedImage bi = new BufferedImage(iWidth, iHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = bi.createGraphics();
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, iWidth, iHeight);
        g2d.drawImage(image, 0, 0, null);
        g2d.dispose();
        return bi;
    }

    /**
     * Converts an image to 1 bit per pixel and emits it as one or more
     * GS v 0 raster bit image commands. Bands abut with no feed between them,
     * so a tall image prints as one continuous picture.
     */
    private void raster(BufferedImage image) {

        BufferedImage src = image;
        int iWidth = src.getWidth();
        int iHeight = src.getHeight();

        if (iWidth <= 0 || iHeight <= 0) {
            return;
        }

        int iMaxDots = m_config.getMaxDots();
        if (iWidth > iMaxDots) {
            int iNewHeight = (int) Math.round((double) iHeight * iMaxDots / (double) iWidth);
            if (iNewHeight < 1) {
                iNewHeight = 1;
            }
            BufferedImage scaled = new BufferedImage(iMaxDots, iNewHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = scaled.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(src, 0, 0, iMaxDots, iNewHeight, null);
            g2d.dispose();
            src = scaled;
            iWidth = iMaxDots;
            iHeight = iNewHeight;
        }

        int iWidthBytes = (iWidth + 7) / 8;
        int iThreshold = m_config.getThreshold();
        byte[] bData = new byte[iWidthBytes * iHeight];

        for (int y = 0; y < iHeight; y++) {
            int iRowBase = y * iWidthBytes;
            for (int x = 0; x < iWidth; x++) {
                int iArgb = src.getRGB(x, y);
                if ((iArgb >>> 24) < 128) {
                    // A transparent pixel is paper, not ink. Without this a
                    // transparent PNG logo prints as a solid black box.
                    continue;
                }
                int iRed = (iArgb >> 16) & 0xFF;
                int iGreen = (iArgb >> 8) & 0xFF;
                int iBlue = iArgb & 0xFF;
                int iLuma = (299 * iRed + 587 * iGreen + 114 * iBlue) / 1000;
                if (iLuma < iThreshold) {
                    // A set bit is a black dot; the row's last byte stays
                    // right padded with zeroes.
                    bData[iRowBase + (x / 8)] |= (byte) (1 << (7 - (x % 8)));
                }
            }
        }

        int iBandRows = m_config.getBandRows();
        for (int iRow = 0; iRow < iHeight; iRow += iBandRows) {
            int iRows = Math.min(iBandRows, iHeight - iRow);
            // GS v 0 m xL xH yL yH d1..dk   print raster bit image, m=0 normal
            cmd(0x1D, 0x76, 0x30, 0x00,
                    iWidthBytes & 0xFF, (iWidthBytes >> 8) & 0xFF,
                    iRows & 0xFF, (iRows >> 8) & 0xFF);
            m_buffer.write(bData, iRow * iWidthBytes, iRows * iWidthBytes);
        }
    }

    /**
     * Appends literal command bytes. Every caller writes hex literals and
     * names the command it is implementing in a trailing comment.
     */
    private void cmd(int... aValues) {
        for (int i = 0; i < aValues.length; i++) {
            m_buffer.write(aValues[i] & 0xFF);
        }
    }

    private void emit(byte[] bData) {
        if (bData != null && bData.length > 0) {
            m_buffer.write(bData, 0, bData.length);
        }
    }
}
