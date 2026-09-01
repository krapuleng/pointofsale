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
package com.nordpos.tools.escpos;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * Golden file parsing and human readable diff output for the ESC/POS harness.
 *
 * Golden format, so a reviewer can type one straight from the published command
 * spec:
 *
 * <pre>
 * # init, profile=epson, cp=858
 * 1B 40        # ESC @
 * 1B 74 13     # ESC t 19  CP858
 * </pre>
 *
 * Comments are stripped BEFORE parsing so a byte and its gloss share a line.
 *
 * @author Andrey Svininykh &lt;svininykh@gmail.com&gt;
 * @version NORD POS 4.0
 */
public final class HexUtil {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private HexUtil() {
    }

    /**
     * Whitespace insensitive hex. '#' to end of line is a comment, blank lines
     * are ignored. An odd number of hex digits on a line is rejected by name.
     */
    public static byte[] parseGolden(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
        try {
            String sLine;
            int iLineNo = 0;
            while ((sLine = in.readLine()) != null) {
                iLineNo++;
                int iHash = sLine.indexOf('#');
                if (iHash >= 0) {
                    sLine = sLine.substring(0, iHash);
                }
                StringBuilder sbDigits = new StringBuilder();
                for (int i = 0; i < sLine.length(); i++) {
                    char c = sLine.charAt(i);
                    if (Character.isWhitespace(c)) {
                        continue;
                    }
                    if (Character.digit(c, 16) < 0) {
                        throw new IOException(f.getName() + ":" + iLineNo
                                + ": '" + c + "' is not a hex digit. Golden files hold hex bytes and '#' comments only.");
                    }
                    sbDigits.append(c);
                }
                if ((sbDigits.length() & 1) != 0) {
                    throw new IOException(f.getName() + ":" + iLineNo
                            + ": odd number of hex digits (" + sbDigits.length() + ") - every byte needs two.");
                }
                for (int i = 0; i < sbDigits.length(); i += 2) {
                    out.write((Character.digit(sbDigits.charAt(i), 16) << 4)
                            | Character.digit(sbDigits.charAt(i + 1), 16));
                }
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    /**
     * 16 bytes per line: offset, hex, then an ASCII gloss. A decoded ESC/POS
     * command listing follows, so a failure is readable without a spec on the
     * desk.
     */
    public static String toAnnotatedHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        if (data == null) {
            return "  (null)\n";
        }
        if (data.length == 0) {
            sb.append("  (empty, 0 bytes)\n");
            return sb.toString();
        }
        for (int iBase = 0; iBase < data.length; iBase += 16) {
            sb.append(String.format("  %04X  ", iBase));
            for (int i = 0; i < 16; i++) {
                if (iBase + i < data.length) {
                    sb.append(String.format("%02X ", data[iBase + i] & 0xFF));
                } else {
                    sb.append("   ");
                }
                if (i == 7) {
                    sb.append(' ');
                }
            }
            sb.append(" |");
            for (int i = 0; i < 16 && iBase + i < data.length; i++) {
                int b = data[iBase + i] & 0xFF;
                sb.append(b >= 0x20 && b <= 0x7E ? (char) b : '.');
            }
            sb.append("|\n");
        }
        sb.append("  decoded (").append(data.length).append(" bytes):\n");
        sb.append(decode(data));
        return sb.toString();
    }

    /**
     * Both sequences annotated, with the first differing offset called out.
     */
    public static String diff(byte[] expected, byte[] actual) {
        StringBuilder sb = new StringBuilder();
        int iFirst = -1;
        int iMin = Math.min(expected.length, actual.length);
        for (int i = 0; i < iMin; i++) {
            if (expected[i] != actual[i]) {
                iFirst = i;
                break;
            }
        }
        if (iFirst < 0 && expected.length != actual.length) {
            iFirst = iMin;
        }
        sb.append("  expected ").append(expected.length).append(" bytes, got ").append(actual.length).append(" bytes\n");
        if (iFirst >= 0) {
            sb.append("  first difference at offset ").append(iFirst).append(": expected ")
                    .append(iFirst < expected.length ? String.format("%02X", expected[iFirst] & 0xFF) : "<end of data>")
                    .append(", got ")
                    .append(iFirst < actual.length ? String.format("%02X", actual[iFirst] & 0xFF) : "<end of data>")
                    .append('\n');
        }
        sb.append("  --- expected ---\n").append(toAnnotatedHex(expected));
        sb.append("  --- actual ---\n").append(toAnnotatedHex(actual));
        return sb.toString();
    }

    /**
     * A decoder for exactly the commands this driver is allowed to emit. An
     * unrecognised byte is reported as a literal so a stray byte cannot hide.
     */
    private static String decode(byte[] d) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < d.length) {
            int iStart = i;
            String sText = null;
            int b = d[i] & 0xFF;
            if (b == 0x1B && i + 1 < d.length) {
                int c = d[i + 1] & 0xFF;
                switch (c) {
                    case 0x40: sText = "ESC @        initialise"; i += 2; break;
                    case 0x74: sText = "ESC t " + arg(d, i + 2) + "     select code page"; i += 3; break;
                    case 0x52: sText = "ESC R " + arg(d, i + 2) + "     international character set"; i += 3; break;
                    case 0x61: sText = "ESC a " + arg(d, i + 2) + "     justification"; i += 3; break;
                    case 0x21: sText = "ESC ! " + arg(d, i + 2) + "     print mode"; i += 3; break;
                    case 0x4D: sText = "ESC M " + arg(d, i + 2) + "     character font"; i += 3; break;
                    case 0x45: sText = "ESC E " + arg(d, i + 2) + "     emphasis (bold)"; i += 3; break;
                    case 0x2D: sText = "ESC - " + arg(d, i + 2) + "     underline"; i += 3; break;
                    case 0x64: sText = "ESC d " + arg(d, i + 2) + "     feed n lines"; i += 3; break;
                    case 0x70: sText = "ESC p " + arg(d, i + 2) + " " + arg(d, i + 3) + " " + arg(d, i + 4) + "  drawer kick"; i += 5; break;
                    default: break;
                }
            } else if (b == 0x1D && i + 1 < d.length) {
                int c = d[i + 1] & 0xFF;
                switch (c) {
                    case 0x61: sText = "GS a " + arg(d, i + 2) + "      automatic status back"; i += 3; break;
                    case 0x21: sText = "GS ! " + arg(d, i + 2) + "      character size"; i += 3; break;
                    case 0x56: sText = "GS V " + arg(d, i + 2) + "      cut paper"; i += 3; break;
                    case 0x48: sText = "GS H " + arg(d, i + 2) + "      barcode HRI position"; i += 3; break;
                    case 0x66: sText = "GS f " + arg(d, i + 2) + "      barcode HRI font"; i += 3; break;
                    case 0x68: sText = "GS h " + arg(d, i + 2) + "      barcode height"; i += 3; break;
                    case 0x77: sText = "GS w " + arg(d, i + 2) + "      barcode module width"; i += 3; break;
                    case 0x6B: {
                        int m = i + 2 < d.length ? d[i + 2] & 0xFF : -1;
                        int n = i + 3 < d.length ? d[i + 3] & 0xFF : -1;
                        sText = "GS k " + m + " " + n + "    barcode function B (" + symbology(m) + "), " + n + " data bytes";
                        i += 4 + Math.max(n, 0);
                        break;
                    }
                    case 0x76: {
                        if (i + 7 < d.length && (d[i + 2] & 0xFF) == 0x30) {
                            int xL = d[i + 4] & 0xFF, xH = d[i + 5] & 0xFF;
                            int yL = d[i + 6] & 0xFF, yH = d[i + 7] & 0xFF;
                            int iW = xL + (xH << 8), iH = yL + (yH << 8);
                            sText = "GS v 0 " + arg(d, i + 3) + "  raster bit image, " + iW + " byte(s) wide x " + iH + " rows";
                            i += 8 + iW * iH;
                        }
                        break;
                    }
                    default: break;
                }
            } else if (b == 0x1C && i + 3 < d.length && (d[i + 1] & 0xFF) == 0x70) {
                sText = "FS p " + arg(d, i + 2) + " " + arg(d, i + 3) + "    print NV logo";
                i += 4;
            } else if (b == 0x10 && i + 4 < d.length && (d[i + 1] & 0xFF) == 0x14) {
                sText = "DLE DC4 " + arg(d, i + 2) + " " + arg(d, i + 3) + " " + arg(d, i + 4) + "  real time drawer kick";
                i += 5;
            } else if (b == 0x0A) {
                sText = "LF           line feed";
                i += 1;
            }
            if (sText == null) {
                // A run of printable data, or one unrecognised literal byte.
                StringBuilder sbRun = new StringBuilder();
                while (i < d.length) {
                    int r = d[i] & 0xFF;
                    if (r >= 0x20 && r <= 0x7E) {
                        sbRun.append((char) r);
                        i++;
                    } else if (sbRun.length() == 0) {
                        sbRun.append(String.format("<%02X>", r));
                        i++;
                        break;
                    } else {
                        break;
                    }
                }
                sText = "data         \"" + sbRun + "\"";
            }
            sb.append(String.format("    %04X  %s%n", iStart, sText));
            if (i <= iStart) {
                i = iStart + 1; // defensive: never loop forever on malformed input
            }
        }
        return sb.toString();
    }

    private static String arg(byte[] d, int i) {
        return i < d.length ? String.format("%02X", d[i] & 0xFF) : "??";
    }

    private static String symbology(int m) {
        switch (m) {
            case 0x43: return "EAN13";
            case 0x44: return "EAN8";
            case 0x45: return "CODE39";
            case 0x49: return "CODE128";
            default: return "m=" + m;
        }
    }
}
