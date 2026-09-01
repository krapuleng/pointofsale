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
package com.nordpos.device.scale;

import com.nordpos.device.util.SerialPortParameters;
import com.nordpos.device.util.SerialSupport;
import com.nordpos.device.util.StringParser;
import java.awt.Component;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Andrey Svininykh <svininykh@gmail.com>
 * @version NORD POS 3.0
 */
public class ScaleEmulator implements ScaleInterface {

    private static final Logger logger = Logger.getLogger(ScaleEmulator.class.getName());

    @Override
    public DeviceScale getScale(String sProperty) throws Exception {
        // DeviceScaleFactory routes everything except the literal "screen"
        // here, so this overload - which used to ignore its argument entirely -
        // is the only place "fake" and "serial:" can ever become reachable.
        StringParser sp = new StringParser(sProperty);
        String sScaleType = sp.nextToken(':');
        switch (sScaleType) {
            case "fake":
                return new ScaleFake();
            case "serial":
                return buildSerialScale(sp);
            default:
                return new DeviceScaleNull();
        }
    }

    @Override
    public DeviceScale getScale(Component awtComponent, String sProperty) throws Exception {
        StringParser sp = new StringParser(sProperty);
        String sScaleType = sp.nextToken(':');
        switch (sScaleType) {
            case "fake":
                return new ScaleFake();
            case "screen":
                return new ScaleDialog(awtComponent);
            default:
                return new DeviceScaleNull();
        }
    }

    /**
     * serial:&lt;port&gt;,&lt;baud&gt;,&lt;bits&gt;,&lt;stop&gt;,&lt;parity&gt;
     *
     * The type word has already been consumed by the caller.
     */
    private DeviceScale buildSerialScale(StringParser sp) {
        // Throwable, not Exception: DeviceScaleFactory catches only Exception,
        // and gnu.io raises UnsatisfiedLinkError, which is an Error.
        try {
            String sPort = sp.nextToken(',');
            String sBaud = sp.nextToken(',');
            String sBits = sp.nextToken(',');
            String sStopBits = sp.nextToken(',');
            String sParity = sp.nextToken(',');

            // The gnu.io gatekeeper runs FIRST, before ScaleComm exists.
            // ScaleComm touches gnu.io.CommPortIdentifier lazily from
            // readWeight(), which JPanelTicket.incProduct calls directly on the
            // EDT, and the resulting UnsatisfiedLinkError is an Error that
            // JPanelTicket's catch (ScaleException) would not catch - the sale
            // would abort to the EDT's uncaught exception handler.
            String sReason = SerialSupport.checkAvailable(sPort);
            if (sReason != null) {
                logger.log(Level.WARNING, sReason);
                return new DeviceScaleNull();
            }

            return new ScaleComm(sPort,
                    SerialPortParameters.getSpeed(sBaud),
                    SerialPortParameters.getDataBits(sBits),
                    SerialPortParameters.getStopBits(sStopBits),
                    SerialPortParameters.getParity(sParity));
        } catch (Throwable e) {
            logger.log(Level.WARNING, "Serial scale could not be opened: " + e, e);
            return new DeviceScaleNull();
        }
    }
}
