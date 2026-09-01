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
package com.nordpos.device.util;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * The single gatekeeper for every gnu.io touch.
 *
 * gnu.io.CommPortIdentifier has a static initialiser that loads the JNI
 * library. On arm64 macOS that throws UnsatisfiedLinkError, which is an Error
 * and not an Exception, so the device factories (which catch Exception only)
 * would let it abort application startup. Every method here catches Throwable,
 * and the Apple Silicon check short-circuits before any gnu.io class is
 * referenced at all, so the class initialiser never runs on that combination.
 *
 * @author Andrey Svininykh <svininykh@gmail.com>
 * @version NORD POS 3.0
 */
public final class SerialSupport {

    private SerialSupport() {
    }

    /**
     * True on macOS running an arm64 (Apple Silicon) JVM. Must not touch
     * gnu.io: this test is what keeps the JNI class initialiser from running.
     *
     * @return true when serial is structurally impossible on this JVM
     */
    public static boolean isAppleSilicon() {
        String sOsName = System.getProperty("os.name", "");
        String sOsArch = System.getProperty("os.arch", "");
        return sOsName.startsWith("Mac") && ("aarch64".equals(sOsArch) || "arm64".equals(sOsArch));
    }

    /**
     * Probes whether a serial port can be used, eagerly, on the calling thread.
     *
     * @param sPortName the port to look for, or null/empty for an
     * availability-only probe of the serial library itself
     * @return null when the port is usable, otherwise a complete, actionable
     * message suitable for showing to the operator
     */
    public static String checkAvailable(String sPortName) {
        // Step 1 MUST come first. On arm64 macOS getPortIdentifiers() does NOT
        // throw: it prints to stderr and returns an EMPTY enumeration, and
        // getPortIdentifier(name) then throws NoSuchPortException with a null
        // message - indistinguishable from a genuinely absent port.
        if (isAppleSilicon()) {
            return getUnavailableMessage(sPortName);
        }

        List<String> aPorts = new ArrayList<>();
        try {
            Enumeration<?> ePorts = gnu.io.CommPortIdentifier.getPortIdentifiers();
            while (ePorts != null && ePorts.hasMoreElements()) {
                Object oPort = ePorts.nextElement();
                if (oPort instanceof gnu.io.CommPortIdentifier) {
                    aPorts.add(((gnu.io.CommPortIdentifier) oPort).getName());
                }
            }
        } catch (Throwable t) {
            // Never RXTXVersion.getVersion() as a probe: it is a pure-Java
            // constant and reports a version even when the native failed.
            return "The bundled serial library (nrjavaserial) could not be loaded on this system ("
                    + System.getProperty("os.name", "") + "/" + System.getProperty("os.arch", "")
                    + "): " + t + ". Use a network or file transport instead.";
        }

        if (sPortName == null || sPortName.isEmpty()) {
            return null;
        }

        if (!aPorts.contains(sPortName)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < aPorts.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(aPorts.get(i));
            }
            return "Serial port '" + sPortName + "' was not found. Ports detected on this machine: "
                    + (aPorts.isEmpty() ? "none" : sb.toString()) + ". " + getPortHint();
        }

        return null;
    }

    /**
     * The Apple Silicon message, unconditionally. This is the text the operator
     * sees in the unavailable-device tab, so the wording matters.
     *
     * @param sPortName the configured port, may be null
     * @return the message
     */
    public static String getUnavailableMessage(String sPortName) {
        return "Serial port unavailable"
                + (sPortName == null || sPortName.isEmpty() ? "" : " — " + sPortName) + "\n\n"
                + "Serial peripherals are not supported on Apple Silicon (arm64) in this build."
                + " The bundled nrjavaserial-3.11.0 native library contains only i386 and x86_64 code,"
                + " so macOS cannot load it on this Mac.\n\n"
                + "Use one of these instead:\n"
                + "  Receipt printer      escpos:network,<ip>,9100   (works on every platform)\n"
                + "                       escpos:file,/dev/cu.usbserial-XXXX\n"
                + "  Customer display     network:<ip>,<port>\n"
                + "  Scale                connect it to a Windows or Linux till, or run BIZAPP POS on an x86_64 JDK under Rosetta 2.\n\n"
                + "Windows (x86/x64) and Linux (x86/x64/ARM) are unaffected — the jar ships working natives for those.";
    }

    /**
     * One line of example port names for the current operating system.
     *
     * @return the hint line
     */
    public static String getPortHint() {
        String sOsName = System.getProperty("os.name", "");
        if (sOsName.startsWith("Windows")) {
            return "Typical Windows ports: COM1..COM9, and \\\\.\\COM10 or higher for double-digit ports.";
        } else if (sOsName.startsWith("Mac")) {
            return "Typical macOS ports: /dev/cu.usbserial-XXXX, /dev/cu.usbmodemXXXX, /dev/cu.SLAB_USBtoUART,"
                    + " /dev/cu.wchusbserialXXXX — always cu.*, never tty.* (tty.* blocks on DCD).";
        } else {
            return "Typical Linux ports: /dev/ttyS0..3, /dev/ttyUSB0+ (FTDI/PL2303/CH340), /dev/ttyACM0+ (CDC-ACM).";
        }
    }
}
