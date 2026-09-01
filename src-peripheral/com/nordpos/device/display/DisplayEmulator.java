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
package com.nordpos.device.display;

import com.nordpos.device.receiptprinter.ESCPOSConfig;
import com.nordpos.device.util.SerialPortParameters;
import com.nordpos.device.util.SerialSupport;
import com.nordpos.device.util.StringParser;
import java.nio.charset.Charset;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Andrey Svininykh <svininykh@gmail.com>
 * @version NORD POS 3.0
 */
public class DisplayEmulator implements DisplayInterface {

    private static final Logger logger = Logger.getLogger(DisplayEmulator.class.getName());

    private static final int MAX_TOKENS = 10;

    /**
     * Names this device in every transport message. 'Receipt printer' is what
     * the printer path passes.
     */
    private static final String DEVICE_LABEL = "Customer display";

    @Override
    public DeviceDisplay getDisplay(String sProperty) throws Exception {
        StringParser displayProperty = new StringParser(sProperty);
        String sDisplayType = displayProperty.nextToken(':');
        switch (sDisplayType) {
            case "screen":
                return new DeviceDisplayPanel();
            case "window":
                return new DeviceDisplayWindow();
            case "serial":
                return buildSerialDisplay(displayProperty);
            case "network":
                return buildNetworkDisplay(displayProperty);
            default:
                return new DeviceDisplayNull();
        }
    }

    /**
     * serial:&lt;port&gt;,&lt;baud&gt;,&lt;bits&gt;,&lt;stop&gt;,&lt;parity&gt;[,opts]
     *
     * The type word has already been consumed by the caller.
     */
    private DeviceDisplay buildSerialDisplay(StringParser sp) {
        // Throwable, not Exception: gnu.io raises UnsatisfiedLinkError, an
        // Error, and DeviceTicketFactory catches only Exception.
        try {
            String[] t = readTokens(sp);
            String sPort = positional(t, 0);

            // The gnu.io gatekeeper runs FIRST, on this thread, before any
            // WritterRXTX is constructed. SerialPortParameters is safe to call
            // unconditionally - its returns are compile-time int constants.
            String sReason = SerialSupport.checkAvailable(sPort);
            if (sReason != null) {
                logger.log(Level.WARNING, sReason);
                return new DeviceDisplayNull(sReason);
            }

            // PacedWritterRXTX, not WritterRXTX: same bytes, same port
            // settings, but it releases the display's frame gate when a frame
            // has actually been written, so the 250 ms animation timer drops
            // frames instead of queueing them behind a stalled port.
            return new DeviceDisplayEpson(
                    new DeviceDisplayEpson.PacedWritterRXTX(sPort,
                            SerialPortParameters.getSpeed(positional(t, 1)),
                            SerialPortParameters.getDataBits(positional(t, 2)),
                            SerialPortParameters.getStopBits(positional(t, 3)),
                            SerialPortParameters.getParity(positional(t, 4)),
                            SerialPortParameters.getFlowControl("none")),
                    resolveCharset(t),
                    resolveColumns(t),
                    resolveCursorPositioning(t));
        } catch (Throwable e) {
            String sReason = "Customer display could not be opened on the serial port: " + e;
            logger.log(Level.WARNING, sReason, e);
            return new DeviceDisplayNull(sReason);
        }
    }

    /**
     * network:&lt;host&gt;,&lt;port&gt;[,opts]
     *
     * The type word has already been consumed by the caller. Unlike the serial
     * transport this one works on every platform, Apple Silicon included.
     */
    private DeviceDisplay buildNetworkDisplay(StringParser sp) {
        try {
            String[] t = readTokens(sp);
            String sHost = positional(t, 0);
            if (sHost.isEmpty()) {
                String sReason = "network: customer display needs a host, e.g. network:192.168.1.60,9100";
                logger.log(Level.WARNING, sReason);
                return new DeviceDisplayNull(sReason);
            }
            // The device label is what the transport puts in every message
            // it writes to the log and to the UI, so a dead customer display
            // must never report itself as a broken receipt printer.
            return new DeviceDisplayEpson(
                    new DeviceDisplayEpson.PacedWritterNetwork(sHost, toPort(positional(t, 1)), DEVICE_LABEL),
                    resolveCharset(t),
                    resolveColumns(t),
                    resolveCursorPositioning(t));
        } catch (Throwable e) {
            String sReason = "Customer display could not be opened over the network: " + e;
            logger.log(Level.WARNING, sReason, e);
            return new DeviceDisplayNull(sReason);
        }
    }

    private static String[] readTokens(StringParser sp) {
        String[] t = new String[MAX_TOKENS];
        for (int i = 0; i < MAX_TOKENS; i++) {
            t[i] = sp.nextToken(',');
        }
        return t;
    }

    /**
     * A positional slot. A token carrying '=' is an option, never a positional,
     * so it reads back as absent.
     */
    private static String positional(String[] t, int iIndex) {
        if (iIndex < 0 || iIndex >= t.length || t[iIndex] == null) {
            return "";
        }
        return t[iIndex].indexOf('=') >= 0 ? "" : t[iIndex];
    }

    /**
     * The value of an option token, or null when it was not supplied. Options
     * are order-independent and may sit in any slot after the positionals.
     */
    private static String option(String[] t, String sKey) {
        for (int i = 0; i < t.length; i++) {
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

    private static int resolveColumns(String[] t) {
        String sCols = option(t, "cols");
        if (sCols == null) {
            return 20;
        }
        try {
            int iCols = Integer.parseInt(sCols);
            if (iCols >= 1 && iCols <= 80) {
                return iCols;
            }
        } catch (NumberFormatException e) {
            // fall through to the default
        }
        logger.log(Level.WARNING, "Customer display: ignoring out of range option ''cols={0}''; using 20.", sCols);
        return 20;
    }

    private static boolean resolveCursorPositioning(String[] t) {
        return "us".equals(option(t, "cursor"));
    }

    /**
     * The code page table lives in exactly one place, ESCPOSConfig, so the
     * display and the receipt printer can never drift apart.
     */
    private static Charset resolveCharset(String[] t) {
        String sCodePage = option(t, "cp");
        ESCPOSConfig config = new ESCPOSConfig();
        config.applyOption("cp=" + (sCodePage == null ? "437" : sCodePage));
        if (config.getErrors() != null) {
            logger.log(Level.WARNING, "Customer display code page: {0}", config.getErrors());
        }
        // A null Charset tells DeviceDisplayEpson to use UnicodeTranslatorInt.
        return config.isLegacyTranslator() ? null : config.getCharset();
    }

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
