//    Openbravo POS is a point of sales application designed for touch screens.
//    Copyright (C) 2007-2009 Openbravo, S.L.
//    http://www.openbravo.com/product/pos
//
//    This file is part of Openbravo POS.
//
//    Openbravo POS is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    Openbravo POS is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with Openbravo POS.  If not, see <http://www.gnu.org/licenses/>.

package com.nordpos.device.scale;

import com.nordpos.device.util.SerialSupport;
import gnu.io.*;
import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serial scale speaking the Openbravo / Epelsa "Dialog 1" protocol: the POS
 * sends ENQ (0x05), the scale streams ASCII digits holding the weight in whole
 * grams, and RS (0x1E) terminates the reading.
 *
 * It will not talk to a Toledo/Mettler, a CAS or an NCI/SCP scale.
 */
public class ScaleComm implements DeviceScale, SerialPortEventListener {

    private static final Logger logger = Logger.getLogger(ScaleComm.class.getName());

    private String m_sPortScale;
    private Integer m_iPortSpeed;
    private Integer m_iPortBits;
    private Integer m_iPortStopBits;
    private Integer m_iPortParity;    

    private CommPortIdentifier m_PortIdPrinter;
    private SerialPort m_CommPortPrinter;      
    private OutputStream m_out;
    private InputStream m_in;

    private static final int SCALE_READY = 0;
    private static final int SCALE_READING = 1;
    
    private double m_dWeightBuffer;
    private int m_iStatusScale;

    // m_iStatusScale alone cannot tell a genuine 0.000 kg reading from total
    // silence - it reads SCALE_READY in both cases - so the RS terminator sets
    // this flag and readWeight() refuses to invent a weight without it.
    private boolean m_bValueRead;
    private volatile String m_sLastError;

    /** Creates a new instance of ScaleComm */
    public ScaleComm(String sPortPrinter, Integer iPortSpeed, Integer iPortBits, Integer iPortStopBits, Integer iPortParity) {
        m_sPortScale = sPortPrinter;
        m_iPortSpeed = iPortSpeed;
        m_iPortBits = iPortBits;
        m_iPortStopBits = iPortStopBits;
        m_iPortParity = iPortParity;        
        
        m_out = null;
        m_in = null;
        
        m_iStatusScale = SCALE_READY; 
        m_dWeightBuffer = 0.0;
        m_bValueRead = false;
        m_sLastError = null;
    }
    
    @Override
    public Double readWeight() throws ScaleException {
        
        synchronized(this) {

            if (m_iStatusScale != SCALE_READY) {
                try {
                    wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (m_iStatusScale != SCALE_READY) {
                    // bascula tonta.
                    m_iStatusScale = SCALE_READY;
                }
            }
            
            // Ya estamos en SCALE_READY
            m_dWeightBuffer = 0.0;
            m_bValueRead = false;
            m_sLastError = null;
            write(new byte[] {0x05});
            flush();             
            
            // Esperamos un ratito
            try {
                wait(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (m_sLastError != null) {
                String sError = m_sLastError;
                m_iStatusScale = SCALE_READY;
                m_dWeightBuffer = 0.0;
                throw new ScaleException(sError);
            }

            if (!m_bValueRead) {
                m_iStatusScale = SCALE_READY;
                m_dWeightBuffer = 0.0;
                throw new ScaleException("No response from the scale on port " + m_sPortScale
                        + " within 1000 ms. Check the cable, the port name and the scale's baud rate.");
            }

            // a value has been read.
            double dWeight = m_dWeightBuffer / 1000.0;
            m_dWeightBuffer = 0.0;
            m_iStatusScale = SCALE_READY;
            return Double.valueOf(dWeight);
        }
    }
    
    private void flush() {
        try {
            if (m_out != null) {
                m_out.flush();
            }
        } catch (IOException e) {
            m_sLastError = describeFailure(e);
            logger.log(Level.SEVERE, m_sLastError, e);
        }        
    }
    
    private void write(byte[] data) {
        try {  
            if (m_out == null) {
                m_PortIdPrinter = CommPortIdentifier.getPortIdentifier(m_sPortScale); // Tomamos el puerto                   
                m_CommPortPrinter = (SerialPort) m_PortIdPrinter.open("PORTID", 2000); // Abrimos el puerto       

                m_out = m_CommPortPrinter.getOutputStream(); // Tomamos el chorro de escritura   
                m_in = m_CommPortPrinter.getInputStream();

                // The port must be configured BEFORE the listener is armed:
                // otherwise data can arrive at the wrong baud rate and be
                // decoded as garbage before the parameters are applied.
                m_CommPortPrinter.setSerialPortParams(m_iPortSpeed, m_iPortBits, m_iPortStopBits, m_iPortParity);

                m_CommPortPrinter.addEventListener(this);
                m_CommPortPrinter.notifyOnDataAvailable(true);
            }
            m_out.write(data);
        } catch (Exception | LinkageError e) {
            m_sLastError = describeFailure(e);
            logger.log(Level.SEVERE, m_sLastError, e);
        }        
    }

    /**
     * NoSuchPortException carries a null message, so the text is built here
     * rather than taken from the throwable.
     */
    private String describeFailure(Throwable e) {
        if (e instanceof NoSuchPortException) {
            return "Serial port '" + m_sPortScale + "' was not found. " + SerialSupport.getPortHint();
        }
        if (e instanceof PortInUseException) {
            return "Serial port '" + m_sPortScale + "' is already in use by another program.";
        }
        if (e instanceof UnsupportedCommOperationException) {
            return "Serial port '" + m_sPortScale + "' rejected the settings " + m_iPortSpeed
                    + " baud, " + m_iPortBits + " data bits, " + m_iPortStopBits + " stop bits, parity " + m_iPortParity + ".";
        }
        if (e instanceof LinkageError) {
            return "The bundled serial library could not be loaded, so the scale on port '"
                    + m_sPortScale + "' cannot be used: " + e + ". " + SerialSupport.getPortHint();
        }
        return "Communication with the scale on port '" + m_sPortScale + "' failed: " + e + ".";
    }
    
    @Override
    public void serialEvent(SerialPortEvent e) {

	// Determine type of event.
	switch (e.getEventType()) {
            case SerialPortEvent.BI:
            case SerialPortEvent.OE:
            case SerialPortEvent.FE:
            case SerialPortEvent.PE:
            case SerialPortEvent.CD:
            case SerialPortEvent.CTS:
            case SerialPortEvent.DSR:
            case SerialPortEvent.RI:
            case SerialPortEvent.OUTPUT_BUFFER_EMPTY:
                break;
            case SerialPortEvent.DATA_AVAILABLE:
                try {
                    while (m_in.available() > 0) {
                        int b = m_in.read();

                        if (b == 0x001E) { // RS ASCII
                            // Fin de lectura
                            synchronized (this) {
                                m_iStatusScale = SCALE_READY;
                                m_bValueRead = true;
                                notifyAll();
                            }
                        } else if (b > 0x002F && b < 0x003A){
                            synchronized(this) {
                                if (m_iStatusScale == SCALE_READY) {
                                    m_dWeightBuffer = 0.0; // se supone que esto debe estar ya garantizado
                                    m_iStatusScale = SCALE_READING;
                                }
                                m_dWeightBuffer = m_dWeightBuffer * 10.0 + b - 0x0030;
                            }
                        } else {
                            // caracteres invalidos, reseteamos.
                            synchronized (this) {
                                m_dWeightBuffer = 0.0; // se supone que esto debe estar ya garantizado
                                m_iStatusScale = SCALE_READY;
                            }
                        }
                    }

                } catch (IOException eIO) {
                    // Swallowing this silently would let a partial reading be
                    // reported as a whole weight; record it so readWeight()
                    // raises instead of inventing a number.
                    m_sLastError = describeFailure(eIO);
                    logger.log(Level.SEVERE, m_sLastError, eIO);
                }
                break;
        }

    }       
}
