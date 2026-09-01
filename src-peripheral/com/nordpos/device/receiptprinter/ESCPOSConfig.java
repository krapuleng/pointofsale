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

import com.nordpos.device.traslator.UnicodeTranslatorInt;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every option an ESC/POS receipt printer accepts, its default, and the
 * inseparable pairing of the <code>ESC t n</code> code page byte with the
 * encoder that produces bytes for it. Choosing the two independently is the
 * classic "prints two mojibake characters instead of one accent" bug and is
 * invisible to any test that only checks the text bytes, so the pairing is
 * made here or not at all.
 *
 * This class knows nothing about hosts, ports, print queues or serial ports.
 * It never throws: an unknown key or an out-of-range value is recorded in
 * {@link #getErrors()} and the default silently stands.
 *
 * @author Andrey Svininykh <svininykh@gmail.com>
 * @version NORD POS 4.0
 */
public class ESCPOSConfig {

    public static final int PROFILE_EPSON = 0;
    public static final int PROFILE_GENERIC = 1;

    public static final int CUT_PARTIAL = 0;
    public static final int CUT_FULL = 1;
    public static final int CUT_NONE = 2;
    public static final int CUT_TEMPLATE = 3;

    public static final int DRAWER_PIN2 = 0;
    public static final int DRAWER_PIN5 = 1;
    public static final int DRAWER_REALTIME = 2;

    /**
     * The widest raster GS v 0 can legally carry. The command takes the row
     * width as a BYTE count in xL xH whose documented maximum is 128, so
     * 128 * 8 = 1024 dots. A wider value would put an out of spec header on
     * the wire, which a printer answers with garbage or with nothing at all,
     * so it is refused here rather than emitted.
     */
    public static final int MAX_RASTER_DOTS = 1024;

    private static final int MAX_RASTER_BYTES = MAX_RASTER_DOTS / 8;

    private static final String CP_LEGACY = "legacy";
    private static final String CHARSET_FALLBACK = "US-ASCII";

    private int m_iProfile = PROFILE_EPSON;

    /**
     * The code page as configured. Only consulted when m_bCpExplicit is true,
     * so that "profile" may change the default whatever the token order was.
     */
    private String m_sCp = null;
    private boolean m_bCpExplicit = false;

    private int m_iCut = CUT_PARTIAL;
    private int m_iFeed = 4;
    private int m_iDrawer = DRAWER_PIN2;
    private int m_iDrawerPulseMillis = 50;
    private boolean m_bFontB = false;
    private int m_iMaxDots = 576;
    private int m_iBandRows = 128;
    private int m_iThreshold = 128;
    private boolean m_bBarcodeRaster = false;
    private int m_iBarcodeHeight = 162;
    private int m_iBarcodeWidth = 3;
    private int m_iNvLogo = 0;

    private final List<String> m_aOptionErrors = new ArrayList<String>();

    /**
     * Resolution of the code page is deferred to read time because "profile"
     * changes its default, and applyOption() may be fed the two tokens in
     * either order. Any applyOption() call invalidates the cache.
     */
    private boolean m_bResolved = false;
    private Charset m_charset = null;
    private boolean m_bLegacyTranslator = false;
    private byte m_bCodePage = 0x00;
    private String m_sCharsetError = null;
    private CharsetEncoder m_encoder = null;

    public ESCPOSConfig() {
    }

    /**
     * Applies one <code>key=value</code> token. Split on the FIRST '=' only,
     * because a value may legitimately contain one more delimiter of its own
     * (<code>logo=nv:1</code>). Never throws, never returns an error code.
     *
     * @param sOption one raw comma token from the machine.printer property
     */
    public synchronized void applyOption(String sOption) {

        if (sOption == null) {
            return;
        }
        String sToken = sOption.trim();
        if (sToken.isEmpty()) {
            return;
        }

        int iSplit = sToken.indexOf('=');
        if (iSplit < 0) {
            addOptionError("unknown option '" + sToken + "'");
            return;
        }

        String sKey = sToken.substring(0, iSplit).trim().toLowerCase(Locale.ENGLISH);
        String sValue = sToken.substring(iSplit + 1).trim().toLowerCase(Locale.ENGLISH);

        // Any option may change the code page or the profile it defaults from.
        m_bResolved = false;
        m_encoder = null;

        Integer iValue;

        switch (sKey) {
            case "profile":
                switch (sValue) {
                    case "epson":
                        m_iProfile = PROFILE_EPSON;
                        break;
                    case "generic":
                        m_iProfile = PROFILE_GENERIC;
                        break;
                    default:
                        addValueError(sKey, sValue);
                        break;
                }
                break;
            case "cp":
                if (isKnownCodePage(sValue)) {
                    m_sCp = sValue;
                    m_bCpExplicit = true;
                } else {
                    addValueError(sKey, sValue);
                }
                break;
            case "cut":
                switch (sValue) {
                    case "partial":
                        m_iCut = CUT_PARTIAL;
                        break;
                    case "full":
                        m_iCut = CUT_FULL;
                        break;
                    case "none":
                        m_iCut = CUT_NONE;
                        break;
                    case "template":
                        m_iCut = CUT_TEMPLATE;
                        break;
                    default:
                        addValueError(sKey, sValue);
                        break;
                }
                break;
            case "feed":
                iValue = toRange(sKey, sValue, 0, 255);
                if (iValue != null) {
                    m_iFeed = iValue.intValue();
                }
                break;
            case "drawer":
                switch (sValue) {
                    case "pin2":
                        m_iDrawer = DRAWER_PIN2;
                        break;
                    case "pin5":
                        m_iDrawer = DRAWER_PIN5;
                        break;
                    case "realtime":
                        m_iDrawer = DRAWER_REALTIME;
                        break;
                    default:
                        addValueError(sKey, sValue);
                        break;
                }
                break;
            case "drawerpulse":
                iValue = toRange(sKey, sValue, 20, 200);
                if (iValue != null) {
                    m_iDrawerPulseMillis = iValue.intValue();
                }
                break;
            case "font":
                switch (sValue) {
                    case "a":
                        m_bFontB = false;
                        break;
                    case "b":
                        m_bFontB = true;
                        break;
                    default:
                        addValueError(sKey, sValue);
                        break;
                }
                break;
            case "maxdots":
                // Clamped to the ESC/POS documented maximum for GS v 0, not to
                // some paper width: a 1..MAX_RASTER_DOTS range is what the
                // raster header itself can express. Out of range leaves the
                // default in place, never silently clamps, which is the rule
                // every other option in this class follows.
                iValue = toRange(sKey, sValue, 1, MAX_RASTER_DOTS);
                if (iValue == null) {
                    addOptionError("maxdots must be 1.." + MAX_RASTER_DOTS
                            + " dots; GS v 0 carries the row width as a byte count whose documented maximum is "
                            + MAX_RASTER_BYTES + ", so " + m_iMaxDots + " still stands");
                } else {
                    m_iMaxDots = iValue.intValue();
                }
                break;
            case "band":
                iValue = toRange(sKey, sValue, 1, 255);
                if (iValue != null) {
                    m_iBandRows = iValue.intValue();
                }
                break;
            case "threshold":
                iValue = toRange(sKey, sValue, 0, 255);
                if (iValue != null) {
                    m_iThreshold = iValue.intValue();
                }
                break;
            case "barcode":
                switch (sValue) {
                    case "native":
                        m_bBarcodeRaster = false;
                        break;
                    case "raster":
                        m_bBarcodeRaster = true;
                        break;
                    default:
                        addValueError(sKey, sValue);
                        break;
                }
                break;
            case "bcheight":
                iValue = toRange(sKey, sValue, 1, 255);
                if (iValue != null) {
                    m_iBarcodeHeight = iValue.intValue();
                }
                break;
            case "bcwidth":
                iValue = toRange(sKey, sValue, 2, 6);
                if (iValue != null) {
                    m_iBarcodeWidth = iValue.intValue();
                }
                break;
            case "logo":
                if (sValue.startsWith("nv:")) {
                    iValue = toRange(sKey, sValue.substring(3), 1, 255);
                    if (iValue != null) {
                        m_iNvLogo = iValue.intValue();
                    }
                } else {
                    addValueError(sKey, sValue);
                }
                break;
            default:
                addOptionError("unknown option '" + sKey + "=" + sValue + "'");
                break;
        }
    }

    /**
     * @return null when every option parsed cleanly, otherwise the accumulated
     * complaints joined with "; ". DevicePrinterESCPOS appends this to its
     * printer description so a misconfiguration is visible in the UI.
     */
    public synchronized String getErrors() {

        resolve();

        if (m_aOptionErrors.isEmpty() && m_sCharsetError == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (String sError : m_aOptionErrors) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(sError);
        }
        if (m_sCharsetError != null) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(m_sCharsetError);
        }
        return sb.toString();
    }

    public int getProfile() {
        return m_iProfile;
    }

    /**
     * @return the encoder's charset, or null when cp=legacy selected the
     * in-tree UnicodeTranslatorInt instead of a java.nio.charset one.
     */
    public Charset getCharset() {
        resolve();
        return m_charset;
    }

    public boolean isLegacyTranslator() {
        resolve();
        return m_bLegacyTranslator;
    }

    /**
     * @return the n of ESC t n, bound to the charset getCharset() returns.
     */
    public byte getCodePageByte() {
        resolve();
        return m_bCodePage;
    }

    public int getCut() {
        return m_iCut;
    }

    public int getFeed() {
        return m_iFeed;
    }

    public int getDrawer() {
        return m_iDrawer;
    }

    public int getDrawerPulseMillis() {
        return m_iDrawerPulseMillis;
    }

    public boolean isFontB() {
        return m_bFontB;
    }

    public int getMaxDots() {
        return m_iMaxDots;
    }

    public int getBandRows() {
        return m_iBandRows;
    }

    public int getThreshold() {
        return m_iThreshold;
    }

    public boolean isBarcodeRaster() {
        return m_bBarcodeRaster;
    }

    public int getBarcodeHeight() {
        return m_iBarcodeHeight;
    }

    public int getBarcodeWidth() {
        return m_iBarcodeWidth;
    }

    /**
     * @return the NV logo slot 1..255, or 0 when no logo is configured.
     */
    public int getNvLogo() {
        return m_iNvLogo;
    }

    /**
     * Encodes receipt text for the configured code page. Synchronized because
     * CharsetEncoder is not thread safe and the cached instance is shared.
     *
     * @param sText the text to encode; null or empty yields an empty array
     * @return a freshly allocated array, never null
     */
    public synchronized byte[] encode(String sText) {

        if (sText == null || sText.isEmpty()) {
            return new byte[0];
        }

        resolve();

        if (m_bLegacyTranslator) {
            byte[] bTranslated = new UnicodeTranslatorInt().transString(sText);
            return bTranslated == null ? new byte[0] : bTranslated;
        }

        if (m_encoder == null) {
            m_encoder = m_charset.newEncoder();
            m_encoder.onMalformedInput(CodingErrorAction.REPLACE);
            m_encoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
            m_encoder.replaceWith(new byte[]{0x3F}); // '?'
        }

        try {
            ByteBuffer bb = m_encoder.encode(CharBuffer.wrap(sText));
            byte[] bResult = new byte[bb.remaining()];
            bb.get(bResult);
            return bResult;
        } catch (CharacterCodingException e) {
            // Unreachable with REPLACE/REPLACE, but the signature declares it.
            return sText.getBytes(m_charset);
        }
    }

    private static boolean isKnownCodePage(String sCp) {
        switch (sCp) {
            case "437":
            case "850":
            case "852":
            case "858":
            case "866":
            case "1252":
            case "ascii":
            case CP_LEGACY:
                return true;
            default:
                return false;
        }
    }

    /**
     * The one place the ESC t byte and the encoder are chosen, and they are
     * always chosen together.
     */
    private synchronized void resolve() {

        if (m_bResolved) {
            return;
        }
        m_bResolved = true;
        m_sCharsetError = null;
        m_bLegacyTranslator = false;
        m_encoder = null;

        String sCp = m_bCpExplicit
                ? m_sCp
                : (m_iProfile == PROFILE_GENERIC ? "437" : "858");

        String sCharsetName;

        switch (sCp) {
            case "437":
                m_bCodePage = 0x00; // ESC t 0   PC437 (USA, Standard Europe)
                sCharsetName = "IBM437";
                break;
            case "850":
                m_bCodePage = 0x02; // ESC t 2   PC850 (Multilingual)
                sCharsetName = "IBM850";
                break;
            case "852":
                m_bCodePage = 0x12; // ESC t 18  PC852 (Latin 2)
                sCharsetName = "IBM852";
                break;
            case "858":
                m_bCodePage = 0x13; // ESC t 19  PC858 (Euro)
                sCharsetName = "IBM00858";
                break;
            case "866":
                m_bCodePage = 0x11; // ESC t 17  PC866 (Cyrillic 2)
                sCharsetName = "IBM866";
                break;
            case "1252":
                m_bCodePage = 0x10; // ESC t 16  WPC1252
                sCharsetName = "windows-1252";
                break;
            case "ascii":
                m_bCodePage = 0x00; // ESC t 0   PC437, of which US-ASCII is a subset
                sCharsetName = CHARSET_FALLBACK;
                break;
            case CP_LEGACY:
                // The in-tree UnicodeTranslatorInt emits a PC858-compatible
                // high half, so the printer must be told PC858.
                m_bCodePage = 0x13; // ESC t 19  PC858
                m_bLegacyTranslator = true;
                m_charset = null;
                return;
            default:
                // Unreachable: applyOption rejects anything else.
                m_bCodePage = 0x00;
                sCharsetName = CHARSET_FALLBACK;
                break;
        }

        if (Charset.isSupported(sCharsetName)) {
            m_charset = Charset.forName(sCharsetName);
        } else {
            m_sCharsetError = "charset " + sCharsetName
                    + " is not available in this JRE; falling back to " + CHARSET_FALLBACK;
            m_bCodePage = 0x00; // ESC t 0
            m_charset = Charset.forName(CHARSET_FALLBACK);
        }
    }

    private Integer toRange(String sKey, String sValue, int iMin, int iMax) {
        try {
            int iParsed = Integer.parseInt(sValue);
            if (iParsed < iMin || iParsed > iMax) {
                addValueError(sKey, sValue);
                return null;
            }
            return Integer.valueOf(iParsed);
        } catch (NumberFormatException e) {
            addValueError(sKey, sValue);
            return null;
        }
    }

    private void addValueError(String sKey, String sValue) {
        addOptionError(sKey + " out of range '" + sValue + "'");
    }

    private void addOptionError(String sError) {
        if (!m_aOptionErrors.contains(sError)) {
            m_aOptionErrors.add(sError);
        }
    }
}
