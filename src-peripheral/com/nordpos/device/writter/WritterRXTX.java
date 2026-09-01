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
package com.nordpos.device.writter;

import gnu.io.*; // RXTX comm library
import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Andrey Svininykh <svininykh@gmail.com>
 * @version NORD POS 3.0
 */
public class WritterRXTX extends Writter {

    private static final Logger logger = Logger.getLogger(WritterRXTX.class.getName());

    private CommPortIdentifier m_PortIdPrinter;
    private CommPort m_CommPortPrinter;

    private final String m_sPortPrinter;
    private final Integer m_iPortSpeed;
    private final Integer m_iPortBits;
    private final Integer m_iPortStopBits;
    private final Integer m_iPortParity;
    private final Integer m_iFlowControl;
    private OutputStream m_out;

    public WritterRXTX(String sPortPrinter, Integer iPortSpeed, Integer iPortBits, Integer iPortStopBits, Integer iPortParity) {
        // SerialPort.FLOWCONTROL_NONE is a compile-time constant and is inlined
        // by javac, so this delegation does not touch gnu.io at class load.
        this(sPortPrinter, iPortSpeed, iPortBits, iPortStopBits, iPortParity, SerialPort.FLOWCONTROL_NONE);
    }

    public WritterRXTX(String sPortPrinter, Integer iPortSpeed, Integer iPortBits, Integer iPortStopBits, Integer iPortParity, Integer iFlowControl) {
        m_sPortPrinter = sPortPrinter;
        m_iPortSpeed = iPortSpeed;
        m_iPortBits = iPortBits;
        m_iPortStopBits = iPortStopBits;
        m_iPortParity = iPortParity;
        m_iFlowControl = iFlowControl;

        m_out = null;
    }

    @Override
    protected void internalWrite(byte[] data) {
        try {
            if (m_out == null) {
                m_PortIdPrinter = CommPortIdentifier.getPortIdentifier(m_sPortPrinter); // Tomamos el puerto
                m_CommPortPrinter = m_PortIdPrinter.open("PORTID", 2000); // Abrimos el puerto

                m_out = m_CommPortPrinter.getOutputStream(); // Tomamos el chorro de escritura

                if (m_PortIdPrinter.getPortType() == CommPortIdentifier.PORT_SERIAL) {
                    ((SerialPort) m_CommPortPrinter).setSerialPortParams(m_iPortSpeed, m_iPortBits, m_iPortStopBits, m_iPortParity); // Configuramos el puerto
                    // Serial thermal printers that use XON/XOFF or RTS/CTS overflow
                    // their buffer and drop mid-receipt without this.
                    ((SerialPort) m_CommPortPrinter).setFlowControlMode(m_iFlowControl);
                }
            }
            m_out.write(data);
            // A customer display writes every 250 ms and never calls Writter.flush().
            m_out.flush();
            setLastError(null);
        } catch (NoSuchPortException | PortInUseException | UnsupportedCommOperationException | IOException e) {
            String sError = describe(e);
            logger.log(Level.SEVERE, sError);
            logger.log(Level.FINE, sError, e);
            setLastError(sError);
        } catch (RuntimeException | LinkageError e) {
            // gnu.io's native loader can fail with an Error rather than an
            // Exception; it must not escape the executor as a bare stack trace.
            String sError = describe(e);
            logger.log(Level.SEVERE, sError);
            logger.log(Level.FINE, sError, e);
            setLastError(sError);
        }
    }

    /**
     * gnu.io.NoSuchPortException carries a NULL message, so the text has to be
     * built here rather than taken from the exception.
     *
     * @param e the failure
     * @return actionable prose naming the port
     */
    private String describe(Throwable e) {
        if (e instanceof NoSuchPortException) {
            // Measured on this machine: on arm64 macOS the native never loads,
            // yet getPortIdentifier() does not raise a LinkageError - it raises
            // NoSuchPortException (with a null message), so "port not found"
            // would be actively misleading about a port that is really there.
            if (com.nordpos.device.util.SerialSupport.isAppleSilicon()) {
                return com.nordpos.device.util.SerialSupport.getUnavailableMessage(m_sPortPrinter);
            }
            return "Serial port '" + m_sPortPrinter + "' was not found. "
                    + com.nordpos.device.util.SerialSupport.getPortHint();
        } else if (e instanceof PortInUseException) {
            return "Serial port '" + m_sPortPrinter + "' is already in use by another program.";
        } else if (e instanceof UnsupportedCommOperationException) {
            return "Serial port '" + m_sPortPrinter + "' rejected the requested settings"
                    + " (speed, data bits, stop bits, parity or flow control). Check them against the device manual.";
        } else if (e instanceof LinkageError) {
            return com.nordpos.device.util.SerialSupport.getUnavailableMessage(m_sPortPrinter);
        } else {
            return "Lost the connection to the device on serial port '" + m_sPortPrinter + "': " + e;
        }
    }

    @Override
    protected void internalFlush() {
        try {
            if (m_out != null) {
                m_out.flush();
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    @Override
    protected void internalClose() {
        try {
            if (m_out != null) {
                m_out.flush();
                m_out.close();
                m_out = null;
                m_CommPortPrinter = null;
                m_PortIdPrinter = null;
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
        }
    }
}
