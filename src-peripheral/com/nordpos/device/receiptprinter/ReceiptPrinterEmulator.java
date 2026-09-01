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

import com.nordpos.device.util.SerialPortParameters;
import com.nordpos.device.util.SerialSupport;
import com.nordpos.device.util.StringParser;
import com.nordpos.device.writter.Writter;
import com.nordpos.device.writter.WritterFile;
import com.nordpos.device.writter.WritterNetwork;
import com.nordpos.device.writter.WritterPrintService;
import com.nordpos.device.writter.WritterRXTX;
import java.awt.Component;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Andrey Svininykh <svininykh@gmail.com>
 */
public class ReceiptPrinterEmulator implements ReceiptPrinterInterface {

    private static final Logger logger = Logger.getLogger(ReceiptPrinterEmulator.class.getName());

    public static final byte[] EOL_DOS = {0x0D, 0x0A}; // Print and carriage return
    public static final byte[] EOL_UNIX = {0x0A};

    private static final int MAX_TOKENS = 12;

    /**
     * Names this device in every message the transport writes. The customer
     * display passes 'Customer display' for the same reason.
     */
    private static final String DEVICE_LABEL = "Receipt printer";

    @Override
    public DevicePrinter getReceiptPrinter(String sProperty) throws Exception {
        StringParser sp = new StringParser(sProperty);
        String sPrinterType = sp.nextToken(':');
        String sPrinterParam1 = sp.nextToken(',');
        String sPrinterParam2 = sp.nextToken(',');
        String sPrinterParam3 = sp.nextToken(',');

        switch (sPrinterType) {
            case "plaintext":
                if ("file".equals(sPrinterParam1)) {
                    if ("unix".equals(sPrinterParam3)) {
                        return new DevicePrinterPlainText(new WritterFile(sPrinterParam2), EOL_UNIX);
                    } else {
                        return new DevicePrinterPlainText(new WritterFile(sPrinterParam2), EOL_DOS);
                    }

                } else {
                    return new DevicePrinterNull();
                }
            case "screen":
                return new DevicePrinterPanel();
            case "escpos":
                return buildEscPos(sProperty);
            default:
                return new DevicePrinterNull();
        }
    }

    @Override
    public DevicePrinter getReceiptPrinter(Component awtComponent, String sProperty, PaperFormat paperFormat) throws Exception {
        StringParser sp = new StringParser(sProperty);
        String sPrinterType = sp.nextToken(':');
        String sPrinterParam1 = sp.nextToken(',');
        switch (sPrinterType) {
            case "printer":
                return new DevicePrinterPrinter(awtComponent, sPrinterParam1, paperFormat.getMarginLeft(), paperFormat.getMarginTop(), paperFormat.getWidth(), paperFormat.getHeight(), paperFormat.getType());
            case "escpos":
                // paperFormat is deliberately ignored: a thermal printer is a
                // character cell device and paper.<type>.* is measured in
                // points. This arm exists only because DeviceTicketFactory
                // routes here whenever the SECOND comma token is literally
                // "receipt" or "standard", which happens for a print queue
                // named "receipt". Both overloads therefore build the same
                // driver and the routing becomes irrelevant.
                return buildEscPos(sProperty);
            default:
                return new DevicePrinterNull();
        }
    }

    /**
     * escpos:&lt;transport&gt;,&lt;positional...&gt;[,&lt;key&gt;=&lt;value&gt;]...
     *
     * Never throws and never returns null: a misconfigured string degrades to
     * DevicePrinterNull, and an unusable serial port to the visible
     * DevicePrinterUnavailable tab.
     */
    private DevicePrinter buildEscPos(String sProperty) {
        // Throwable, not Exception: gnu.io raises UnsatisfiedLinkError, which
        // is an Error, and DeviceTicketFactory catches only Exception - it
        // would abort JRootApp startup.
        try {
            // A fresh parser: the caller's has already consumed tokens.
            StringParser sp = new StringParser(sProperty);
            sp.nextToken(':'); // "escpos", discarded
            String[] t = new String[MAX_TOKENS];
            for (int i = 0; i < MAX_TOKENS; i++) {
                t[i] = sp.nextToken(',');
            }

            ESCPOSConfig config = new ESCPOSConfig();
            for (int i = 1; i < t.length; i++) {
                if (t[i] != null && t[i].indexOf('=') >= 0) {
                    config.applyOption(t[i]);
                }
            }

            String sTransport = t[0] == null ? "" : t[0].trim().toLowerCase();
            Writter out;
            String sDescription;

            switch (sTransport) {
                case "network": {
                    String sHost = positional(t, 1);
                    int iPort = toPort(positional(t, 2));
                    // IPv6 must arrive as the bracketed single token
                    // [addr]:port, because a bare colon form would be split by
                    // the caller's nextToken(':').
                    if (sHost.startsWith("[")) {
                        int iClose = sHost.indexOf("]:");
                        if (iClose > 0) {
                            iPort = toPort(sHost.substring(iClose + 2));
                            sHost = sHost.substring(1, iClose);
                        } else if (sHost.endsWith("]")) {
                            sHost = sHost.substring(1, sHost.length() - 1);
                        }
                    }
                    if (sHost.isEmpty()) {
                        return new DevicePrinterNull("escpos:network needs a host, e.g. escpos:network,192.168.1.50,9100");
                    }
                    // The label names this device in every message the
                    // transport logs or shows, so a receipt printer and a
                    // customer display can never be confused for each other.
                    out = new WritterNetwork(sHost, iPort, DEVICE_LABEL);
                    sDescription = "ESC/POS network " + sHost + ":" + iPort;
                    break;
                }
                case "printer":
                case "usb": {
                    String sQueue = positional(t, 1);
                    if (sQueue.isEmpty()) {
                        return new DevicePrinterNull("escpos:printer needs a print queue name, e.g. escpos:printer,Bizapp-Thermal");
                    }
                    out = new WritterPrintService(sQueue);
                    sDescription = "ESC/POS print queue '" + sQueue + "'";
                    break;
                }
                case "file": {
                    String sPath = positional(t, 1);
                    if (sPath.isEmpty()) {
                        return new DevicePrinterNull("escpos:file needs a path, e.g. escpos:file,/dev/usb/lp0");
                    }
                    out = new WritterFile(sPath);
                    sDescription = "ESC/POS file " + sPath;
                    break;
                }
                case "serial": {
                    String sPort = positional(t, 1);
                    // The gnu.io gatekeeper runs FIRST, on this thread, before
                    // any WritterRXTX is constructed. This is the D2 visible
                    // surface: a named tab carrying the reason, not a silent
                    // Null device and not a stack trace.
                    String sReason = SerialSupport.checkAvailable(sPort);
                    if (sReason != null) {
                        return new DevicePrinterUnavailable(sReason);
                    }
                    String sFlow = option(t, "flow");
                    // SerialPortParameters is safe to call unconditionally: its
                    // returns are static final int compile-time constants that
                    // javac inlines, so gnu.io is never initialised by them.
                    out = new WritterRXTX(sPort,
                            SerialPortParameters.getSpeed(positional(t, 2)),
                            SerialPortParameters.getDataBits(positional(t, 3)),
                            SerialPortParameters.getStopBits(positional(t, 4)),
                            SerialPortParameters.getParity(positional(t, 5)),
                            SerialPortParameters.getFlowControl(sFlow == null ? "none" : sFlow));
                    sDescription = "ESC/POS serial " + sPort;
                    break;
                }
                default:
                    return new DevicePrinterNull("Unknown escpos transport '" + sTransport + "'. Expected network, printer, usb, file or serial.");
            }

            return new DevicePrinterESCPOS(out, config, sDescription);

        } catch (Throwable e) {
            logger.log(Level.WARNING, "escpos: " + e, e);
            return new DevicePrinterNull("escpos: " + e);
        }
    }

    /**
     * A positional slot. A token carrying '=' is an option, never a positional,
     * so it reads back as absent.
     */
    private static String positional(String[] t, int iIndex) {
        if (iIndex < 0 || iIndex >= t.length || t[iIndex] == null) {
            return "";
        }
        return t[iIndex].indexOf('=') >= 0 ? "" : t[iIndex].trim();
    }

    /**
     * The value of an option token, or null when it was not supplied.
     */
    private static String option(String[] t, String sKey) {
        for (int i = 1; i < t.length; i++) {
            if (t[i] == null) {
                continue;
            }
            int iSep = t[i].indexOf('=');
            if (iSep < 0) {
                continue;
            }
            if (sKey.equals(t[i].substring(0, iSep).trim().toLowerCase())) {
                return t[i].substring(iSep + 1).trim().toLowerCase();
            }
        }
        return null;
    }

    /**
     * Missing, empty, non numeric, &lt;= 0 and &gt; 65535 all mean the RAW
     * printing default, 9100.
     */
    private static int toPort(String sPort) {
        try {
            int iPort = Integer.parseInt(sPort.trim());
            if (iPort > 0 && iPort <= 65535) {
                return iPort;
            }
        } catch (NumberFormatException e) {
            // fall through to the default
        }
        return 9100;
    }
}
