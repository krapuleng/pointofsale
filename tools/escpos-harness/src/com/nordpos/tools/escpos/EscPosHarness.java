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

import com.nordpos.device.display.DeviceDisplay;
import com.nordpos.device.display.DisplayEmulator;
import com.nordpos.device.receiptprinter.DevicePrinter;
import com.nordpos.device.receiptprinter.DevicePrinterESCPOS;
import com.nordpos.device.receiptprinter.ESCPOSConfig;
import com.nordpos.device.receiptprinter.PaperFormat;
import com.nordpos.device.receiptprinter.ReceiptPrinterEmulator;
import com.nordpos.device.scale.DeviceScale;
import com.nordpos.device.scale.ScaleEmulator;
import com.nordpos.device.ticket.DeviceTicketFactory;
import com.nordpos.device.ticket.TicketParser;
import com.nordpos.device.util.SerialSupport;
import com.nordpos.device.writter.WritterNetwork;
import com.nordpos.device.writter.WritterPrintService;
import com.openbravo.pos.forms.AppProperties;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The BIZAPP POS ESC/POS byte level test harness.
 *
 * No hardware exists on the build machine, so byte level proof is the
 * deliverable: every command sequence is asserted against a golden file typed
 * by a human from the published ESC/POS command spec, the network transport is
 * proven against a local TCP listener, and the config grammar is proven by
 * driving the real ReceiptPrinterEmulator.
 *
 * There is deliberately NO --update flag. A golden that regenerates itself from
 * the implementation proves nothing.
 *
 * @author Andrey Svininykh &lt;svininykh@gmail.com&gt;
 * @version NORD POS 4.0
 */
public class EscPosHarness {

    private static File GOLDEN;
    private static File FIXTURES;
    private static boolean PRINT_MODE;

    private static final Map<String, Runner> REGISTRY = new LinkedHashMap<String, Runner>();
    private static final List<String> GROUPS = new ArrayList<String>();

    private static int m_iPassed;
    private static int m_iFailed;
    private static int m_iSkipped;
    private static final List<String> SKIP_REASONS = new ArrayList<String>();

    private static final long AWAIT_MILLIS = 10000L;

    /** A test case body. */
    private interface Runner {
        void run() throws Exception;
    }

    /** A sequence of DevicePrinter calls. */
    private interface Body {
        void run(DevicePrinterESCPOS p) throws Exception;
    }

    /** Thrown by a case that cannot run in this environment. */
    private static class SkipSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        SkipSignal(String sReason) {
            super(sReason);
        }
    }

    // ------------------------------------------------------------------
    // main
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        String sOnly = null;
        for (int i = 0; i < args.length; i++) {
            if ("--golden".equals(args[i]) && i + 1 < args.length) {
                GOLDEN = new File(args[++i]);
            } else if ("--fixtures".equals(args[i]) && i + 1 < args.length) {
                FIXTURES = new File(args[++i]);
            } else if ("--case".equals(args[i]) && i + 1 < args.length) {
                sOnly = args[++i];
            } else if ("--print".equals(args[i]) && i + 1 < args.length) {
                sOnly = args[++i];
                PRINT_MODE = true;
            }
        }
        if (GOLDEN == null || FIXTURES == null) {
            System.err.println("usage: EscPosHarness --golden <dir> --fixtures <dir> [--case <name>] [--print <name>]");
            System.exit(2);
        }

        register();

        if (sOnly != null && !REGISTRY.containsKey(sOnly)) {
            System.err.println("No such case: " + sOnly);
            System.err.println("Known cases:");
            for (String s : REGISTRY.keySet()) {
                System.err.println("  " + s);
            }
            System.exit(2);
        }

        String sCurrentGroup = null;
        for (Map.Entry<String, Runner> e : REGISTRY.entrySet()) {
            if (sOnly != null && !sOnly.equals(e.getKey())) {
                continue;
            }
            String sGroup = groupOf(e.getKey());
            if (sOnly == null && sGroup != null && !sGroup.equals(sCurrentGroup)) {
                sCurrentGroup = sGroup;
                System.out.println();
                System.out.println("== " + sGroup + " ==");
            }
            runCase(e.getKey(), e.getValue());
        }

        if (PRINT_MODE) {
            System.exit(0);
        }

        System.out.println();
        System.out.println(m_iPassed + " passed, " + m_iFailed + " failed, " + m_iSkipped + " skipped");
        if (!SKIP_REASONS.isEmpty()) {
            System.out.println("Skips are NOT passes. A run that skips proves strictly less:");
            for (String s : SKIP_REASONS) {
                System.out.println("  " + s);
            }
        }
        System.exit(m_iFailed > 0 ? 1 : 0);
    }

    private static void runCase(String sName, Runner r) {
        try {
            r.run();
            if (PRINT_MODE) {
                return;
            }
            m_iPassed++;
            System.out.println("PASS " + sName);
        } catch (SkipSignal s) {
            m_iSkipped++;
            SKIP_REASONS.add(sName + ": " + s.getMessage());
            System.out.println("SKIP " + sName + " (" + s.getMessage() + ")");
        } catch (Throwable t) {
            m_iFailed++;
            System.out.println("FAIL " + sName);
            String sMsg = t instanceof AssertionError ? t.getMessage() : t.toString();
            System.out.println(indent(sMsg));
            if (!(t instanceof AssertionError)) {
                StringBuilder sb = new StringBuilder();
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(st.length, 8); i++) {
                    sb.append("    at ").append(st[i]).append('\n');
                }
                System.out.print(sb);
            }
        }
    }

    private static String indent(String s) {
        if (s == null) {
            return "  (no message)";
        }
        StringBuilder sb = new StringBuilder();
        for (String sLine : s.split("\n", -1)) {
            sb.append("  ").append(sLine).append('\n');
        }
        return sb.toString();
    }

    private static String groupOf(String sName) {
        for (String s : GROUPS) {
            if (sName.startsWith(s.substring(s.indexOf('|') + 1))) {
                return s.substring(0, s.indexOf('|'));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // assertions
    // ------------------------------------------------------------------

    private static void expect(String sGolden, byte[] actual) throws IOException {
        if (PRINT_MODE) {
            System.out.println("--- actual bytes for golden " + sGolden + ".hex ---");
            System.out.print(HexUtil.toAnnotatedHex(actual));
            return;
        }
        File f = new File(GOLDEN, sGolden + ".hex");
        if (!f.isFile()) {
            throw new AssertionError("missing golden file " + f.getAbsolutePath());
        }
        byte[] expected = HexUtil.parseGolden(f);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("golden " + sGolden + ".hex does not match\n" + HexUtil.diff(expected, actual));
        }
    }

    private static void check(String sMessage, boolean bCondition) {
        if (!bCondition) {
            throw new AssertionError(sMessage);
        }
    }

    private static void checkEquals(String sMessage, Object oExpected, Object oActual) {
        if (oExpected == null ? oActual != null : !oExpected.equals(oActual)) {
            throw new AssertionError(sMessage + "\n  expected: " + oExpected + "\n  actual:   " + oActual);
        }
    }

    private static void checkContains(String sMessage, String sHaystack, String sNeedle) {
        if (sHaystack == null || !sHaystack.contains(sNeedle)) {
            throw new AssertionError(sMessage + "\n  expected to contain: " + sNeedle + "\n  actual:              " + sHaystack);
        }
    }

    private static void skip(String sReason) {
        throw new SkipSignal(sReason);
    }

    private static void requireHeaded() {
        if (GraphicsEnvironment.isHeadless()) {
            skip("headless JVM; this case constructs a Swing component");
        }
    }

    // ------------------------------------------------------------------
    // driver rigs
    // ------------------------------------------------------------------

    private static String[] opts(String... o) {
        return o;
    }

    private static ESCPOSConfig cfg(String[] o) {
        ESCPOSConfig c = new ESCPOSConfig();
        for (int i = 0; i < o.length; i++) {
            c.applyOption(o[i]);
        }
        return c;
    }

    /** Every job the driver produced, in order, once the executor has drained. */
    private static List<byte[]> jobs(String[] o, Body b) throws Exception {
        CaptureWritter w = new CaptureWritter();
        DevicePrinterESCPOS p = new DevicePrinterESCPOS(w, cfg(o), "harness");
        b.run(p);
        w.awaitQuiet(AWAIT_MILLIS);
        return w.getJobs();
    }

    /** beginReceipt, the body, endReceipt - exactly one job. */
    private static byte[] receipt(String[] o, final Body b) throws Exception {
        List<byte[]> js = jobs(o, new Body() {
            @Override
            public void run(DevicePrinterESCPOS p) throws Exception {
                p.beginReceipt();
                if (b != null) {
                    b.run(p);
                }
                p.endReceipt();
            }
        });
        check("expected exactly one job, got " + js.size(), js.size() == 1);
        return js.get(0);
    }

    /**
     * The receipt body with cut and feed suppressed and the shared init block
     * stripped, so a golden holds only the bytes the case is about.
     */
    private static byte[] body(String[] o, Body b) throws Exception {
        String[] oFull = Arrays.copyOf(o, o.length + 2);
        oFull[o.length] = "cut=none";
        oFull[o.length + 1] = "feed=0";
        return stripInit(receipt(oFull, b));
    }

    /**
     * The init block is itself a hand typed golden, so stripping it also
     * asserts it. A drift in beginReceipt fails every body case loudly rather
     * than silently shifting the comparison window.
     */
    private static byte[] stripInit(byte[] job) throws IOException {
        byte[] init = HexUtil.parseGolden(new File(GOLDEN, "init-epson-cp858.hex"));
        if (job.length < init.length) {
            if (PRINT_MODE) {
                return job;
            }
            throw new AssertionError("job is shorter than the init block\n" + HexUtil.diff(init, job));
        }
        byte[] head = Arrays.copyOf(job, init.length);
        if (!Arrays.equals(init, head)) {
            if (PRINT_MODE) {
                return job;
            }
            throw new AssertionError("the job does not start with the init block from init-epson-cp858.hex\n"
                    + HexUtil.diff(init, head));
        }
        return Arrays.copyOfRange(job, init.length, job.length);
    }

    // ------------------------------------------------------------------
    // raster helpers
    // ------------------------------------------------------------------

    /** A parsed GS v 0 header: {width in bytes, rows}. */
    private static List<int[]> rasterHeaders(byte[] block) {
        check("a raster block must start with ESC a 1",
                block.length >= 3 && block[0] == 0x1B && block[1] == 0x61 && block[2] == 0x01);
        check("a raster block must end with ESC a 0",
                block.length >= 3 && block[block.length - 3] == 0x1B
                        && block[block.length - 2] == 0x61 && block[block.length - 1] == 0x00);
        List<int[]> out = new ArrayList<int[]>();
        int i = 3;
        int iEnd = block.length - 3;
        while (i < iEnd) {
            check("expected a GS v 0 header at offset " + i,
                    i + 8 <= iEnd && (block[i] & 0xFF) == 0x1D && (block[i + 1] & 0xFF) == 0x76
                            && (block[i + 2] & 0xFF) == 0x30);
            checkEquals("GS v 0 mode byte must be 0 (normal); the driver never scales in hardware",
                    Integer.valueOf(0), Integer.valueOf(block[i + 3] & 0xFF));
            int iW = (block[i + 4] & 0xFF) + ((block[i + 5] & 0xFF) << 8);
            int iH = (block[i + 6] & 0xFF) + ((block[i + 7] & 0xFF) << 8);
            check("a GS v 0 band must have a non zero size", iW > 0 && iH > 0);
            check("GS v 0 band data runs past the end of the block", i + 8 + iW * iH <= iEnd);
            out.add(new int[]{iW, iH});
            i += 8 + iW * iH;
        }
        checkEquals("raster bands must abut exactly, with no feed between them",
                Integer.valueOf(iEnd), Integer.valueOf(i));
        return out;
    }

    private static BufferedImage solid(int iW, int iH, int iType, Color c) {
        BufferedImage img = new BufferedImage(iW, iH, iType);
        Graphics2D g = img.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setColor(c);
        g.fillRect(0, 0, iW, iH);
        g.dispose();
        return img;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // config helpers
    // ------------------------------------------------------------------

    private static DevicePrinter printer1(String sProperty) throws Exception {
        return new ReceiptPrinterEmulator().getReceiptPrinter(sProperty);
    }

    private static DevicePrinter printer3(String sProperty) throws Exception {
        PaperFormat pf = new PaperFormat();
        pf.setType("A4");
        pf.setMarginLeft(Integer.valueOf(10));
        pf.setMarginTop(Integer.valueOf(287));
        pf.setWidth(Integer.valueOf(190));
        pf.setHeight(Integer.valueOf(546));
        return new ReceiptPrinterEmulator().getReceiptPrinter(null, sProperty, pf);
    }

    private static void checkPrinterClass(String sProperty, String sExpected) throws Exception {
        DevicePrinter p = printer1(sProperty);
        check("machine.printer=" + sProperty + " must not yield null", p != null);
        checkEquals("machine.printer=" + sProperty, sExpected, p.getClass().getSimpleName());
    }

    private static File temp(String sPrefix, String sSuffix) throws IOException {
        File f = File.createTempFile(sPrefix, sSuffix);
        f.deleteOnExit();
        return f;
    }

    private static byte[] readFully(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    /** Poll a file until it reaches the expected length, or the deadline passes. */
    private static byte[] awaitFile(File f, int iExpectedLength, long lTimeoutMillis) throws IOException {
        long lDeadline = System.currentTimeMillis() + lTimeoutMillis;
        while (System.currentTimeMillis() < lDeadline) {
            if (f.isFile() && f.length() == iExpectedLength) {
                return readFully(f);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return f.isFile() ? readFully(f) : new byte[0];
    }

    /**
     * A loopback port that genuinely refuses connections.
     *
     * Binding an ephemeral port and closing it is the obvious trick, but the OS
     * is then free to hand that port to somebody else before the case connects,
     * which turns a refusal into a success once in a few dozen runs. So the port
     * is probed after closing, and a port that answers is discarded.
     */
    private static int findRefusedPort(String sHost) throws IOException {
        for (int iAttempt = 0; iAttempt < 20; iAttempt++) {
            java.net.ServerSocket s = new java.net.ServerSocket();
            int iPort;
            try {
                s.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
                iPort = s.getLocalPort();
            } finally {
                s.close();
            }
            java.net.Socket probe = new java.net.Socket();
            try {
                probe.connect(new java.net.InetSocketAddress(sHost, iPort), 250);
                probe.close(); // somebody took it; try another
            } catch (IOException expected) {
                return iPort; // refused, which is exactly what this case needs
            }
        }
        skip("could not obtain a loopback port that refuses connections on this machine");
        return -1; // unreachable
    }

    /** Poll getLastError() until it is set, or the deadline passes. */
    private static String awaitLastError(com.nordpos.device.writter.Writter w, long lTimeoutMillis) {
        long lDeadline = System.currentTimeMillis() + lTimeoutMillis;
        while (System.currentTimeMillis() < lDeadline) {
            String s = w.getLastError();
            if (s != null) {
                return s;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return w.getLastError();
    }

    /** A minimal AppProperties good enough for DeviceTicketFactory and TicketParser. */
    private static AppProperties props(final Map<String, String> m) {
        return new AppProperties() {
            @Override
            public File getConfigFile() {
                return null;
            }

            @Override
            public String getHost() {
                return "harness";
            }

            @Override
            public String getDBDriver() {
                return null;
            }

            @Override
            public String getDBDriverLib() {
                return null;
            }

            @Override
            public String getDBUser() {
                return null;
            }

            @Override
            public String getDBPassword() {
                return null;
            }

            @Override
            public String getDBURL() {
                return null;
            }

            @Override
            public String getProperty(String sKey) {
                return m.get(sKey);
            }
        };
    }

    private static Map<String, String> baseProps(String sPrinter) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("machine.printer", sPrinter);
        m.put("machine.display", "Not defined");
        m.put("machine.scale", "Not defined");
        m.put("machine.fiscalprinter", "Not defined");
        m.put("machine.labelprinter", "Not defined");
        m.put("machine.pludevice", "Not defined");
        m.put("paper.receipt.x", "10");
        m.put("paper.receipt.y", "287");
        m.put("paper.receipt.width", "190");
        m.put("paper.receipt.height", "546");
        m.put("paper.receipt.mediasizename", "A4");
        m.put("paper.standard.x", "72");
        m.put("paper.standard.y", "72");
        m.put("paper.standard.width", "451");
        m.put("paper.standard.height", "698");
        m.put("paper.standard.mediasizename", "A4");
        return m;
    }

    private static InputStream schema() {
        InputStream in = EscPosHarness.class.getResourceAsStream("/com/nordpos/templates/Schema.Printer.xsd");
        check("/com/nordpos/templates/Schema.Printer.xsd is not on the classpath - is the jar built?", in != null);
        return in;
    }

    private static void printFixture(DeviceTicketFactory factory, String sFixture) throws Exception {
        TicketParser tp = new TicketParser(schema(), factory);
        FileReader r = new FileReader(new File(FIXTURES, sFixture));
        try {
            tp.printTicket(r);
        } finally {
            r.close();
        }
    }

    // ------------------------------------------------------------------
    // the registry
    // ------------------------------------------------------------------

    private static void add(String sName, Runner r) {
        REGISTRY.put(sName, r);
    }

    private static void group(String sLabel, String sPrefix) {
        GROUPS.add(sLabel + "|" + sPrefix);
    }

    private static void register() {
        group("A. init", "init-");
        group("B. text", "text-");
        group("C. lines", "line-");
        group("D. barcodes (native)", "bc-");
        group("E. raster images", "img-");
        group("F. cut and drawer", "cut-");
        group("F. cut and drawer", "feed-");
        group("F. cut and drawer", "drawer-");
        group("G. code pages", "cp-");
        group("H. end to end goldens", "receipt-");
        group("I. transports", "net-");
        group("I. transports", "file-");
        group("I. transports", "ps-");
        group("I. transports", "serial-");
        group("J. config matrix", "config-");
        group("K. regression", "regress-");
        group("L. end to end through TicketParser", "ticket-");

        registerInit();
        registerText();
        registerLines();
        registerBarcodes();
        registerRaster();
        registerCutAndDrawer();
        registerCodePages();
        registerEndToEnd();
        registerTransports();
        registerConfigMatrix();
        registerRegression();
        registerTicketParser();
    }

    // --- A ---
    private static void registerInit() {
        add("init-epson-cp858", new Runner() {
            @Override
            public void run() throws Exception {
                expect("init-epson-cp858", receipt(opts("cut=none", "feed=0"), null));
            }
        });
        add("init-generic-cp437", new Runner() {
            @Override
            public void run() throws Exception {
                expect("init-generic-cp437", receipt(opts("profile=generic", "cut=none", "feed=0"), null));
            }
        });
        add("init-fontb", new Runner() {
            @Override
            public void run() throws Exception {
                expect("init-fontb", receipt(opts("font=b", "cut=none", "feed=0"), null));
            }
        });
        add("init-nvlogo", new Runner() {
            @Override
            public void run() throws Exception {
                expect("init-nvlogo", receipt(opts("logo=nv:1", "cut=none", "feed=0"), null));
            }
        });
        add("init-option-order-irrelevant", new Runner() {
            @Override
            public void run() throws Exception {
                // profile changes the DEFAULT code page, so the two orders must
                // resolve identically or the grammar is order dependent.
                byte[] a = receipt(opts("cp=437", "profile=epson", "cut=none", "feed=0"), null);
                byte[] b = receipt(opts("profile=epson", "cp=437", "cut=none", "feed=0"), null);
                check("cp=437,profile=epson and profile=epson,cp=437 must produce identical bytes\n"
                        + HexUtil.diff(a, b), Arrays.equals(a, b));
                check("both orders must resolve to ESC t 0 (CP437)",
                        indexOf(a, new byte[]{0x1B, 0x74, 0x00}) >= 0);
            }
        });
    }

    // --- B ---
    private static void registerText() {
        add("text-plain", new Runner() {
            @Override
            public void run() throws Exception {
                expect("text-plain", body(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.printText(Integer.valueOf(12), "none", Boolean.FALSE, "HELLO");
                    }
                }));
            }
        });
        add("text-bold-underline", new Runner() {
            @Override
            public void run() throws Exception {
                expect("text-bold-underline", body(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.printText(Integer.valueOf(12), "slim", Boolean.TRUE, "TOTAL");
                    }
                }));
            }
        });
        add("text-underline-thick", new Runner() {
            @Override
            public void run() throws Exception {
                expect("text-underline-thick", body(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.printText(Integer.valueOf(12), "thick", Boolean.FALSE, "X");
                        p.printText(Integer.valueOf(12), "average", Boolean.FALSE, "X");
                    }
                }));
            }
        });
        add("text-size-sentinel", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = body(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.printText(Integer.valueOf(12), "none", Boolean.FALSE, "AB");
                    }
                });
                check("iCharacterSize 12 is TicketParser's parse-failure sentinel and must emit no GS !",
                        indexOf(b, new byte[]{0x1D, 0x21}) < 0);
                expect("text-size-sentinel", b);
            }
        });
        add("text-size-override", new Runner() {
            @Override
            public void run() throws Exception {
                expect("text-size-override", body(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.beginLine(Integer.valueOf(2));
                        p.printText(Integer.valueOf(1), "none", Boolean.FALSE, "AB");
                        p.endLine();
                    }
                }));
            }
        });
        add("text-empty", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = body(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.printText(Integer.valueOf(12), "none", Boolean.TRUE, "");
                    }
                });
                checkEquals("empty text must emit nothing at all, not a pair of empty bold brackets",
                        Integer.valueOf(0), Integer.valueOf(b.length));
                expect("text-empty", b);
            }
        });
        add("text-nulls", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = body(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.printText(null, null, null, null);
                    }
                });
                checkEquals("printText(null,null,null,null) must emit nothing and throw nothing",
                        Integer.valueOf(0), Integer.valueOf(b.length));
            }
        });
        add("text-no-justification", new Runner() {
            @Override
            public void run() throws Exception {
                // TicketParser has already space padded and aligned the run in
                // software. Hardware centring on top of that double aligns and
                // wraps every receipt, so ESC a must never appear around text.
                byte[] b = body(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.beginLine(Integer.valueOf(0));
                        p.printText(Integer.valueOf(12), "none", Boolean.FALSE, "   CENTRED   ");
                        p.endLine();
                    }
                });
                check("no ESC a may bracket text", indexOf(b, new byte[]{0x1B, 0x61}) < 0);
            }
        });
    }

    // --- C ---
    private static void registerLines() {
        for (int i = 0; i <= 3; i++) {
            final int iSize = i;
            add("line-size-" + i, new Runner() {
                @Override
                public void run() throws Exception {
                    expect("line-size-" + iSize, body(opts(), new Body() {
                        @Override
                        public void run(DevicePrinterESCPOS p) {
                            p.beginLine(Integer.valueOf(iSize));
                            p.endLine();
                        }
                    }));
                }
            });
        }
        add("line-null-size", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = body(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.beginLine(null);
                        p.endLine();
                    }
                });
                byte[] zero = HexUtil.parseGolden(new File(GOLDEN, "line-size-0.hex"));
                check("beginLine(null) must behave exactly as beginLine(0)\n" + HexUtil.diff(zero, b),
                        Arrays.equals(zero, b));
            }
        });
        add("line-out-of-range-size", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = body(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.beginLine(Integer.valueOf(9));
                        p.endLine();
                    }
                });
                byte[] zero = HexUtil.parseGolden(new File(GOLDEN, "line-size-0.hex"));
                check("an out of range line size must clamp to 0, not select 5x height\n" + HexUtil.diff(zero, b),
                        Arrays.equals(zero, b));
            }
        });
    }

    // --- D ---
    private static void registerBarcodes() {
        addBarcode("bc-ean13", "EAN13", "bottom", "4006381333931");
        addBarcode("bc-ean8", "EAN8", "bottom", "12345678");
        addBarcode("bc-code39", "CODE39", "bottom", "ABC-123");
        addBarcode("bc-code128", "CODE128", "bottom", "ABC");
        addBarcode("bc-code128-brace", "CODE128", "bottom", "A{B");
        addBarcode("bc-position-none", "EAN13", "none", "4006381333931");
        addBarcode("bc-position-top", "EAN13", "top", "4006381333931");

        add("bc-unknown-type", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = barcodeBody(opts(), "FOO", "bottom", "4006381333931");
                byte[] ean13 = HexUtil.parseGolden(new File(GOLDEN, "bc-ean13.hex"));
                check("an unrecognised symbology must fall back to EAN13, as PrintItemBarcode does\n"
                        + HexUtil.diff(ean13, b), Arrays.equals(ean13, b));
            }
        });
        add("bc-empty-code", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = barcodeBody(opts(), "EAN13", "bottom", "");
                check("an empty payload must still produce a symbol, never an exception",
                        indexOf(b, new byte[]{0x1D, 0x6B, 0x43}) >= 0);
            }
        });
        add("bc-no-trailing-lf", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = barcodeBody(opts(), "EAN13", "bottom", "4006381333931");
                checkEquals("a barcode block must end with ESC a 0 and no trailing LF",
                        Integer.valueOf(0x00), Integer.valueOf(b[b.length - 1] & 0xFF));
                check("a barcode block must end with ESC a 0",
                        (b[b.length - 3] & 0xFF) == 0x1B && (b[b.length - 2] & 0xFF) == 0x61);
            }
        });
        add("bc-qrcode", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = barcodeBody(opts(), "QRCODE", "bottom", "https://www.nordpos.com");
                check("QRCODE must go through the GS v 0 raster path, never GS ( k",
                        indexOf(b, new byte[]{0x1D, 0x28, 0x6B}) < 0);
                check("QRCODE must not use the 1D native barcode path",
                        indexOf(b, new byte[]{0x1D, 0x6B}) < 0);
                List<int[]> bands = rasterHeaders(b);
                check("QRCODE must produce at least one raster band", !bands.isEmpty());
            }
        });
        add("bc-datamatrix", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = barcodeBody(opts(), "DATAMATRIX", "bottom", "BIZAPP-POS-4.0");
                check("DATAMATRIX must go through the GS v 0 raster path, never GS ( k",
                        indexOf(b, new byte[]{0x1D, 0x28, 0x6B}) < 0);
                check("DATAMATRIX must not use the 1D native barcode path",
                        indexOf(b, new byte[]{0x1D, 0x6B}) < 0);
                check("DATAMATRIX must produce at least one raster band", !rasterHeaders(b).isEmpty());
            }
        });
        add("bc-raster-option", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = barcodeBody(opts("barcode=raster"), "EAN13", "bottom", "4006381333931");
                check("barcode=raster must force a 1D symbology through barcode4j too",
                        indexOf(b, new byte[]{0x1D, 0x6B}) < 0);
                check("barcode=raster must emit a GS v 0 raster block", !rasterHeaders(b).isEmpty());
            }
        });
        add("bc-geometry-options", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = barcodeBody(opts("bcheight=80", "bcwidth=2"), "EAN13", "bottom", "4006381333931");
                check("bcheight=80 must emit GS h 80", indexOf(b, new byte[]{0x1D, 0x68, 0x50}) >= 0);
                check("bcwidth=2 must emit GS w 2", indexOf(b, new byte[]{0x1D, 0x77, 0x02}) >= 0);
            }
        });
    }

    private static byte[] barcodeBody(String[] o, final String sType, final String sPos, final String sCode) throws Exception {
        return body(o, new Body() {
            @Override
            public void run(DevicePrinterESCPOS p) {
                p.printBarCode(sType, sPos, sCode);
            }
        });
    }

    private static void addBarcode(final String sName, final String sType, final String sPos, final String sCode) {
        add(sName, new Runner() {
            @Override
            public void run() throws Exception {
                expect(sName, barcodeBody(opts(), sType, sPos, sCode));
            }
        });
    }

    // --- E ---
    private static void registerRaster() {
        add("img-16x2-black", new Runner() {
            @Override
            public void run() throws Exception {
                expect("img-16x2-black", imageBody(opts(), solid(16, 2, BufferedImage.TYPE_INT_RGB, Color.BLACK)));
            }
        });
        add("img-8x1-halfblack", new Runner() {
            @Override
            public void run() throws Exception {
                BufferedImage img = solid(8, 1, BufferedImage.TYPE_INT_RGB, Color.WHITE);
                for (int x = 0; x < 4; x++) {
                    img.setRGB(x, 0, 0xFF000000);
                }
                expect("img-8x1-halfblack", imageBody(opts(), img));
            }
        });
        add("img-transparent", new Runner() {
            @Override
            public void run() throws Exception {
                // A transparent PNG logo has RGB 0,0,0 under an alpha of 0. A
                // driver that ignores alpha prints it as a solid black box.
                BufferedImage img = new BufferedImage(16, 2, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < 2; y++) {
                    for (int x = 0; x < 16; x++) {
                        img.setRGB(x, y, 0x00000000);
                    }
                }
                expect("img-transparent", imageBody(opts(), img));
            }
        });
        add("img-banding", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = imageBody(opts("band=128"), solid(8, 300, BufferedImage.TYPE_INT_RGB, Color.BLACK));
                List<int[]> bands = rasterHeaders(b);
                checkEquals("300 rows at band=128 must be exactly 3 GS v 0 commands",
                        Integer.valueOf(3), Integer.valueOf(bands.size()));
                checkEquals("band 0 rows", Integer.valueOf(128), Integer.valueOf(bands.get(0)[1]));
                checkEquals("band 1 rows", Integer.valueOf(128), Integer.valueOf(bands.get(1)[1]));
                checkEquals("band 2 rows", Integer.valueOf(44), Integer.valueOf(bands.get(2)[1]));
                for (int i = 0; i < bands.size(); i++) {
                    checkEquals("every band must carry the same width in bytes",
                            Integer.valueOf(1), Integer.valueOf(bands.get(i)[0]));
                }
            }
        });
        add("img-clamp", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = imageBody(opts("maxdots=576"), solid(1200, 4, BufferedImage.TYPE_INT_RGB, Color.BLACK));
                List<int[]> bands = rasterHeaders(b);
                check("a clamped image must still produce a band", !bands.isEmpty());
                checkEquals("1200 dots clamped to maxdots=576 is 72 bytes per row",
                        Integer.valueOf(72), Integer.valueOf(bands.get(0)[0]));
            }
        });
        add("img-null", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = body(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.printImage(null);
                    }
                });
                checkEquals("printImage(null) must emit nothing and throw nothing",
                        Integer.valueOf(0), Integer.valueOf(b.length));
            }
        });
    }

    private static byte[] imageBody(String[] o, final BufferedImage img) throws Exception {
        return body(o, new Body() {
            @Override
            public void run(DevicePrinterESCPOS p) {
                p.printImage(img);
            }
        });
    }

    // --- F ---
    private static void registerCutAndDrawer() {
        add("cut-partial", new Runner() {
            @Override
            public void run() throws Exception {
                expect("cut-partial", stripInit(receipt(opts(), null)));
            }
        });
        add("cut-full", new Runner() {
            @Override
            public void run() throws Exception {
                expect("cut-full", stripInit(receipt(opts("cut=full"), null)));
            }
        });
        add("cut-none", new Runner() {
            @Override
            public void run() throws Exception {
                expect("cut-none", stripInit(receipt(opts("cut=none"), null)));
            }
        });
        add("cut-template", new Runner() {
            @Override
            public void run() throws Exception {
                expect("cut-template", stripInit(receipt(opts("cut=template"), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        // Genuine: no line is open, exactly as <cutpaper/> arrives
                        // as a direct child of <ticket>.
                        p.cutPaper(false);
                    }
                })));
            }
        });
        add("cut-template-ignores-spurious", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = stripInit(receipt(opts("cut=template"), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.beginLine(Integer.valueOf(0));
                        p.cutPaper(true);
                        p.printText(Integer.valueOf(12), "none", Boolean.FALSE, "X");
                        p.endLine();
                    }
                }));
                checkEquals("even in cut=template mode, a cut arriving while a line is open is a "
                        + "TicketParser fall through and must never reach the knife",
                        0, countOf(b, new byte[]{0x1D, 0x56}));
            }
        });
        add("cut-spurious", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = stripInit(receipt(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.beginLine(Integer.valueOf(0));
                        p.cutPaper(true);
                        p.printText(Integer.valueOf(12), "none", Boolean.FALSE, "HELLO");
                        p.endLine();
                    }
                }));
                checkEquals("THE ROLL SHREDDER TEST: exactly one GS V may appear",
                        1, countOf(b, new byte[]{0x1D, 0x56}));
                expect("cut-spurious", b);
            }
        });
        add("cut-spurious-many", new Runner() {
            @Override
            public void run() throws Exception {
                // Printer.Ticket.xml has 87 <text> elements, so the parser fires
                // cutPaper 87 times through the missing break;. Prove the driver
                // is correct at that scale, not just once.
                byte[] b = stripInit(receipt(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        for (int i = 0; i < 87; i++) {
                            p.beginLine(Integer.valueOf(0));
                            p.cutPaper(true);
                            p.printText(Integer.valueOf(12), "none", Boolean.FALSE, "X");
                            p.endLine();
                        }
                    }
                }));
                checkEquals("87 spurious cutPaper(true) calls must still yield exactly one cut",
                        1, countOf(b, new byte[]{0x1D, 0x56}));
            }
        });
        add("cut-parser-fixed-break", new Runner() {
            @Override
            public void run() throws Exception {
                // The driver must ALSO be correct if someone adds the missing
                // break; to TicketParser:260, i.e. when no spurious cut arrives
                // at all. Default policy still cuts exactly once.
                byte[] b = stripInit(receipt(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.beginLine(Integer.valueOf(0));
                        p.printText(Integer.valueOf(12), "none", Boolean.FALSE, "HELLO");
                        p.endLine();
                    }
                }));
                checkEquals("with the parser fall through repaired, the default policy still cuts once",
                        1, countOf(b, new byte[]{0x1D, 0x56}));
                expect("cut-spurious", b);
            }
        });
        add("feed-zero", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = stripInit(receipt(opts("feed=0"), null));
                check("feed=0 must emit no ESC d at all", indexOf(b, new byte[]{0x1B, 0x64}) < 0);
                checkEquals("feed=0 with the default cut leaves only GS V 1", 3, b.length);
            }
        });
        add("feed-custom", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = stripInit(receipt(opts("feed=6"), null));
                check("feed=6 must emit ESC d 6", indexOf(b, new byte[]{0x1B, 0x64, 0x06}) >= 0);
            }
        });

        addDrawer("drawer-pin2", opts());
        addDrawer("drawer-pin5", opts("drawer=pin5"));
        addDrawer("drawer-realtime", opts("drawer=realtime"));
        addDrawer("drawer-pulse", opts("drawerpulse=100"));

        add("drawer-inside-receipt", new Runner() {
            @Override
            public void run() throws Exception {
                // openDrawer() while a receipt is buffered must NOT start a
                // second job; it appends to the receipt in flight.
                List<byte[]> js = jobs(opts("cut=none", "feed=0"), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.beginReceipt();
                        p.openDrawer();
                        p.endReceipt();
                    }
                });
                checkEquals("a drawer kick inside a receipt must not become its own job",
                        1, js.size());
                byte[] b = stripInit(js.get(0));
                byte[] kick = new byte[]{0x1B, 0x70, 0x00, 0x19, (byte) 0xFA};
                check("the kick bytes must be appended to the receipt", indexOf(b, kick) >= 0);
                check("no second ESC @ may appear inside a receipt in flight",
                        indexOf(b, new byte[]{0x1B, 0x40}) < 0);
            }
        });
    }

    private static void addDrawer(final String sName, final String[] o) {
        add(sName, new Runner() {
            @Override
            public void run() throws Exception {
                List<byte[]> js = jobs(o, new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.openDrawer();
                    }
                });
                checkEquals("a standalone openDrawer() must be exactly one job", 1, js.size());
                check("a standalone kick must carry its own ESC @, so a printer left mid command "
                        + "does not swallow the pulse",
                        js.get(0).length >= 2 && js.get(0)[0] == 0x1B && js.get(0)[1] == 0x40);
                expect(sName, js.get(0));
            }
        });
    }

    private static int countOf(byte[] haystack, byte[] needle) {
        int iCount = 0;
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            boolean bHit = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    bHit = false;
                    break;
                }
            }
            if (bHit) {
                iCount++;
            }
        }
        return iCount;
    }

    // --- G ---
    private static void registerCodePages() {
        addCodePage("cp-858", opts());
        addCodePage("cp-437", opts("cp=437"));
        addCodePage("cp-1252", opts("cp=1252"));
        addCodePage("cp-ascii", opts("cp=ascii"));
        addCodePage("cp-legacy", opts("cp=legacy"));

        add("cp-unmappable", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = body(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.printText(Integer.valueOf(12), "none", Boolean.FALSE, "\u6F22");
                    }
                });
                check("an unmappable character must become '?', never an exception mid sale\n"
                        + HexUtil.toAnnotatedHex(b), Arrays.equals(new byte[]{0x3F}, b));
            }
        });
        add("cp-legacy-matches-858", new Runner() {
            @Override
            public void run() throws Exception {
                // cp=legacy exists to pin byte parity with the serial display
                // path. If UnicodeTranslatorInt and CP858 ever disagree on a
                // character in the Latin-1 range, the pairing with ESC t 19 is
                // wrong and this fails.
                byte[] a = HexUtil.parseGolden(new File(GOLDEN, "cp-858.hex"));
                byte[] b = HexUtil.parseGolden(new File(GOLDEN, "cp-legacy.hex"));
                check("cp=legacy and cp=858 must agree on e acute\n" + HexUtil.diff(a, b),
                        Arrays.equals(a, b));
            }
        });
        add("cp-unknown-value", new Runner() {
            @Override
            public void run() throws Exception {
                ESCPOSConfig c = new ESCPOSConfig();
                c.applyOption("cp=klingon");
                check("an unknown code page must be recorded as an error", c.getErrors() != null);
                byte[] dflt = HexUtil.parseGolden(new File(GOLDEN, "cp-858.hex"));
                byte[] b = receipt(opts("cp=klingon", "cut=none", "feed=0"), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.printText(Integer.valueOf(12), "none", Boolean.FALSE, "\u00E9");
                    }
                });
                check("an out of range option is recorded and IGNORED - the default stands\n"
                        + HexUtil.diff(dflt, b), Arrays.equals(dflt, b));
            }
        });
    }

    private static void addCodePage(final String sName, final String[] o) {
        add(sName, new Runner() {
            @Override
            public void run() throws Exception {
                String[] oFull = Arrays.copyOf(o, o.length + 2);
                oFull[o.length] = "cut=none";
                oFull[o.length + 1] = "feed=0";
                expect(sName, receipt(oFull, new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) {
                        p.printText(Integer.valueOf(12), "none", Boolean.FALSE, "\u00E9");
                    }
                }));
            }
        });
    }

    // --- H ---
    private static void registerEndToEnd() {
        add("receipt-minimal", new Runner() {
            @Override
            public void run() throws Exception {
                byte[] b = receipt(opts(), MINIMAL_BODY);
                checkEquals("the minimal receipt is 35 bytes", 35, b.length);
                expect("receipt-minimal", b);
            }
        });
        add("receipt-minimal-drawer", new Runner() {
            @Override
            public void run() throws Exception {
                List<byte[]> js = jobs(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) throws Exception {
                        p.beginReceipt();
                        MINIMAL_BODY.run(p);
                        p.endReceipt();
                        p.openDrawer();
                    }
                });
                checkEquals("the receipt and the drawer kick are two separate jobs", 2, js.size());
                expect("receipt-minimal", js.get(0));
                // ESC @ is 2 bytes and ESC p m t1 t2 is 5, so a standalone
                // kick is 7 bytes. (The spec prose says 6; its own byte listing
                // says 7, and the printer agrees with the listing.)
                checkEquals("the drawer kick is 7 bytes", 7, js.get(1).length);
                expect("receipt-minimal-drawer", js.get(1));
            }
        });
        add("receipt-two-in-a-row", new Runner() {
            @Override
            public void run() throws Exception {
                // ESC @ lives in beginReceipt, never in Writter.init(): init is
                // write-once for the life of the instance, so receipt 2 would go
                // out with bold or double width still latched from receipt 1.
                List<byte[]> js = jobs(opts(), new Body() {
                    @Override
                    public void run(DevicePrinterESCPOS p) throws Exception {
                        p.beginReceipt();
                        MINIMAL_BODY.run(p);
                        p.endReceipt();
                        p.beginReceipt();
                        MINIMAL_BODY.run(p);
                        p.endReceipt();
                    }
                });
                checkEquals("two receipts are two jobs", 2, js.size());
                expect("receipt-minimal", js.get(0));
                expect("receipt-minimal", js.get(1));
            }
        });
    }

    private static final Body MINIMAL_BODY = new Body() {
        @Override
        public void run(DevicePrinterESCPOS p) {
            p.beginLine(Integer.valueOf(0));
            p.printText(Integer.valueOf(12), "none", Boolean.TRUE, "HELLO");
            p.endLine();
        }
    };

    // --- I ---
    private static void registerTransports() {
        add("net-roundtrip", new Runner() {
            @Override
            public void run() throws Exception {
                TcpPrinterStub stub = new TcpPrinterStub();
                try {
                    DevicePrinterESCPOS p = new DevicePrinterESCPOS(
                            new WritterNetwork(stub.getHost(), stub.getPort()),
                            new ESCPOSConfig(), "harness");
                    p.beginReceipt();
                    MINIMAL_BODY.run(p);
                    p.endReceipt();
                    byte[] got = stub.awaitJob(AWAIT_MILLIS);
                    check("the TCP stub received nothing within " + AWAIT_MILLIS + " ms", got != null);
                    expect("receipt-minimal", got);
                    checkEquals("one receipt is one TCP connection", 1, stub.getConnectionCount());
                } finally {
                    stub.close();
                }
            }
        });
        add("net-connect-per-receipt", new Runner() {
            @Override
            public void run() throws Exception {
                // Most printer NICs accept ONE session on 9100, so a held socket
                // locks out the other tills and an idle one is reaped by NAT,
                // producing half a receipt on the next sale. flush() must drop
                // the connection, and receipt 2 must carry its own ESC @.
                TcpPrinterStub stub = new TcpPrinterStub();
                try {
                    DevicePrinterESCPOS p = new DevicePrinterESCPOS(
                            new WritterNetwork(stub.getHost(), stub.getPort()),
                            new ESCPOSConfig(), "harness");
                    for (int i = 0; i < 2; i++) {
                        p.beginReceipt();
                        MINIMAL_BODY.run(p);
                        p.endReceipt();
                        byte[] got = stub.awaitJob(AWAIT_MILLIS);
                        check("receipt " + (i + 1) + " never arrived at the stub", got != null);
                        check("receipt " + (i + 1) + " must begin with ESC @",
                                got.length >= 2 && got[0] == 0x1B && got[1] == 0x40);
                        expect("receipt-minimal", got);
                    }
                    checkEquals("two receipts must open two connections", 2, stub.getConnectionCount());
                } finally {
                    stub.close();
                }
            }
        });
        add("net-refused", new Runner() {
            @Override
            public void run() throws Exception {
                String sHost = java.net.InetAddress.getLoopbackAddress().getHostAddress();
                int iDeadPort = findRefusedPort(sHost);
                WritterNetwork w = new WritterNetwork(sHost, iDeadPort);
                DevicePrinterESCPOS p = new DevicePrinterESCPOS(w, new ESCPOSConfig(), "harness");
                p.beginReceipt();
                MINIMAL_BODY.run(p);
                p.endReceipt(); // must not throw: the sale completes regardless
                String sErr = awaitLastError(w, AWAIT_MILLIS);
                check("an unreachable printer must set getLastError(), not throw and not lie", sErr != null);
                checkContains("the error must name the target", sErr, sHost);
                checkContains("the error must name the port", sErr, Integer.toString(iDeadPort));
                check("the error must be actionable prose, not a stack trace or 'null'",
                        sErr.length() > 40 && !sErr.contains("java.net."));
                checkContains("getPrinterDescription must surface the transport error", p.getPrinterDescription(), sHost);
            }
        });
        add("file-roundtrip", new Runner() {
            @Override
            public void run() throws Exception {
                File f = temp("bizapp-escpos-", ".bin");
                DevicePrinter p = printer1("escpos:file," + f.getAbsolutePath());
                checkEquals("escpos:file must build the ESC/POS driver",
                        "DevicePrinterESCPOS", p.getClass().getSimpleName());
                p.beginReceipt();
                p.beginLine(Integer.valueOf(0));
                p.printText(Integer.valueOf(12), "none", Boolean.TRUE, "HELLO");
                p.endLine();
                p.endReceipt();
                byte[] got = awaitFile(f, 35, AWAIT_MILLIS);
                // The transport is the only variable: identical bytes to the
                // in-memory golden and to the TCP round trip.
                expect("receipt-minimal", got);
            }
        });
        add("ps-lookup", new Runner() {
            @Override
            public void run() throws Exception {
                List<String> names = WritterPrintService.listRawPrintServiceNames();
                check("listRawPrintServiceNames() must never return null", names != null);
                System.out.println("     print queues advertising BYTE_ARRAY.AUTOSENSE on this machine: "
                        + (names.isEmpty() ? "none" : names.toString())
                        + "  (a hint for the operator, NOT a RAW capability check)");
            }
        });
        add("ps-unknown-queue", new Runner() {
            @Override
            public void run() throws Exception {
                String sQueue = "Bizapp-Harness-No-Such-Queue";
                WritterPrintService w = new WritterPrintService(sQueue);
                w.write(new byte[]{0x1B, 0x40});
                w.flush(); // no job is printed: the queue does not exist
                String sErr = awaitLastError(w, AWAIT_MILLIS);
                check("an unknown print queue must set getLastError(), not throw", sErr != null);
                checkContains("the error must name the queue", sErr, sQueue);
                checkContains("the error must point macOS users at a transport that works",
                        sErr.toLowerCase(), "macos");
            }
        });
        add("serial-arm64-guard", new Runner() {
            @Override
            public void run() throws Exception {
                if (!SerialSupport.isAppleSilicon()) {
                    skip("not an Apple Silicon JVM (" + System.getProperty("os.name") + "/"
                            + System.getProperty("os.arch") + "); the arm64 short circuit cannot be observed here");
                }
                // The gnu.io native must never be touched on this platform:
                // CommPortIdentifier's static initialiser throws
                // UnsatisfiedLinkError, an Error that DeviceTicketFactory and
                // DeviceScaleFactory do not catch, and nrjavaserial's loader
                // prints its failure to stderr on the way. Nothing may appear.
                PrintStream errOld = System.err;
                ByteArrayOutputStream cap = new ByteArrayOutputStream();
                String sReason;
                System.setErr(new PrintStream(cap, true, "UTF-8"));
                try {
                    sReason = SerialSupport.checkAvailable("/dev/cu.usbserial-0001");
                } finally {
                    System.setErr(errOld);
                }
                check("checkAvailable must report the port as unusable on Apple Silicon", sReason != null);
                checkContains("the message must name the architecture", sReason, "arm64");
                checkContains("the message must offer a transport that works", sReason, "escpos:network");
                checkEquals("checkAvailable must return exactly the documented D2 text",
                        SerialSupport.getUnavailableMessage("/dev/cu.usbserial-0001"), sReason);
                checkEquals("nothing may be written to stderr: gnu.io must never have been initialised",
                        "", cap.toString("UTF-8").trim());
                check("getPortHint must name macOS cu.* devices",
                        SerialSupport.getPortHint().contains("cu."));
            }
        });
        add("serial-printer-unavailable-tab", new Runner() {
            @Override
            public void run() throws Exception {
                if (!SerialSupport.isAppleSilicon()) {
                    skip("not an Apple Silicon JVM; the serial path would try to open a real port here");
                }
                requireHeaded();
                DevicePrinter p = printer1("escpos:serial,/dev/cu.usbserial-0001,9600,8,1,none");
                checkEquals("the D2 surface is a VISIBLE named tab, not a silent Null device",
                        "DevicePrinterUnavailable", p.getClass().getSimpleName());
                check("JPanelPrinter only renders a tab when getPrinterComponent() is non null",
                        p.getPrinterComponent() != null);
                checkContains("the tab must carry the reason", p.getPrinterDescription(), "arm64");
                // No public method may throw, even on the Unavailable device.
                p.beginReceipt();
                p.beginLine(Integer.valueOf(0));
                p.printText(Integer.valueOf(12), "none", Boolean.FALSE, "X");
                p.endLine();
                p.cutPaper(true);
                p.openDrawer();
                p.endReceipt();
                p.reset();
            }
        });
    }

    // --- J ---
    private static void registerConfigMatrix() {
        final String[] asEscPos = {
            "escpos:network,192.168.1.50,9100",
            "escpos:network,192.168.1.50,9100,cp=1252,cut=full,feed=6",
            "escpos:network,tm-t88.shop.local,9100",
            "escpos:network,[fd00::1]:9100",
            "escpos:network,192.168.1.50",
            "escpos:network,192.168.1.50,notanumber",
            "escpos:network,192.168.1.50,0",
            "escpos:network,192.168.1.50,65536",
            "escpos:network,192.168.1.50,-1",
            "escpos:printer,Bizapp-Thermal",
            "escpos:printer,EPSON TM-T20III Receipt,cp=437",
            "escpos:usb,Generic-Text-Only",
            "escpos:file,/dev/usb/lp0",
            "escpos:file,/dev/cu.usbserial-A50285BI",
            "escpos:file,C:\\Temp\\escpos.bin,cp=437",
            "escpos:file,LPT1"
        };
        for (int i = 0; i < asEscPos.length; i++) {
            final String s = asEscPos[i];
            add("config-ok-" + i, new Runner() {
                @Override
                public void run() throws Exception {
                    checkPrinterClass(s, "DevicePrinterESCPOS");
                }
            });
        }

        final String[] asDegenerate = {
            "escpos:", "escpos", "escpos:network", "escpos:network,", "escpos:network,,9100",
            "escpos:zzz,x", ":", ",", "", null, "escpos:file,", "escpos:printer,"
        };
        for (int i = 0; i < asDegenerate.length; i++) {
            final String s = asDegenerate[i];
            add("config-degenerate-" + i, new Runner() {
                @Override
                public void run() throws Exception {
                    DevicePrinter p = printer1(s);
                    check("a degenerate string must never yield null", p != null);
                    checkEquals("machine.printer=" + s + " must degrade to the Null device",
                            "DevicePrinterNull", p.getClass().getSimpleName());
                }
            });
        }

        add("config-port-default", new Runner() {
            @Override
            public void run() throws Exception {
                String[] as = {"escpos:network,192.168.1.50", "escpos:network,192.168.1.50,notanumber",
                    "escpos:network,192.168.1.50,0", "escpos:network,192.168.1.50,65536",
                    "escpos:network,192.168.1.50,-1"};
                for (int i = 0; i < as.length; i++) {
                    DevicePrinter p = printer1(as[i]);
                    checkContains("a missing or out of range port must default to 9100 for " + as[i],
                            p.getPrinterDescription(), "192.168.1.50:9100");
                }
            }
        });
        add("config-ipv6-bracketed", new Runner() {
            @Override
            public void run() throws Exception {
                DevicePrinter p = printer1("escpos:network,[fd00::1]:9100");
                checkContains("the bracketed IPv6 form must split into host and port",
                        p.getPrinterDescription(), "fd00::1");
                checkContains("the bracketed IPv6 form must split into host and port",
                        p.getPrinterDescription(), ":9100");
            }
        });
        add("config-queue-named-receipt", new Runner() {
            @Override
            public void run() throws Exception {
                // THE ROUTING HAZARD. DeviceTicketFactory routes on the SECOND
                // comma token; a print queue literally named "receipt" sends the
                // string through the 3-arg overload, which without a case
                // "escpos" there would hit default: and return a Null device.
                DevicePrinter p1 = printer1("escpos:printer,receipt");
                checkEquals("the 1-arg overload must build the driver",
                        "DevicePrinterESCPOS", p1.getClass().getSimpleName());
                DevicePrinter p3 = printer3("escpos:printer,receipt");
                checkEquals("the 3-arg overload must build the SAME driver, or a queue named "
                        + "'receipt' silently becomes a Null device",
                        "DevicePrinterESCPOS", p3.getClass().getSimpleName());
                DevicePrinter p3b = printer3("escpos:printer,standard");
                checkEquals("and the same for a queue named 'standard'",
                        "DevicePrinterESCPOS", p3b.getClass().getSimpleName());
            }
        });
        add("config-bad-options-recorded", new Runner() {
            @Override
            public void run() throws Exception {
                DevicePrinter p = printer1("escpos:network,h,9100,feed=999,bogus=1");
                checkEquals("a bad option must not prevent the driver from being built",
                        "DevicePrinterESCPOS", p.getClass().getSimpleName());
                String sDesc = p.getPrinterDescription();
                checkContains("the bad range must be reported to the operator", sDesc, "999");
                checkContains("the unknown key must be reported to the operator", sDesc, "bogus");
                // and the defaults must still stand
                ESCPOSConfig c = new ESCPOSConfig();
                c.applyOption("feed=999");
                c.applyOption("bogus=1");
                checkEquals("an out of range value must leave the default in place, never clamp silently",
                        4, c.getFeed());
                check("the errors must be recorded", c.getErrors() != null);
            }
        });
        add("config-option-clamps", new Runner() {
            @Override
            public void run() throws Exception {
                ESCPOSConfig c = new ESCPOSConfig();
                checkEquals("default feed", 4, c.getFeed());
                checkEquals("default drawer pulse", 50, c.getDrawerPulseMillis());
                checkEquals("default maxdots", 576, c.getMaxDots());
                checkEquals("default band rows", 128, c.getBandRows());
                checkEquals("default threshold", 128, c.getThreshold());
                checkEquals("default barcode height", 162, c.getBarcodeHeight());
                checkEquals("default barcode width", 3, c.getBarcodeWidth());
                checkEquals("default nv logo", 0, c.getNvLogo());
                checkEquals("default profile is epson", ESCPOSConfig.PROFILE_EPSON, c.getProfile());
                checkEquals("default cut is partial", ESCPOSConfig.CUT_PARTIAL, c.getCut());
                checkEquals("default drawer is pin 2", ESCPOSConfig.DRAWER_PIN2, c.getDrawer());
                checkEquals("a clean config has no errors", null, c.getErrors());
                check("the default encoder must not be the legacy translator", !c.isLegacyTranslator());
                checkEquals("the default code page byte is CP858", (byte) 0x13, c.getCodePageByte());

                String[] asBad = {"feed=999", "feed=-1", "drawerpulse=10", "drawerpulse=500",
                    "maxdots=0", "maxdots=5000", "band=0", "band=256", "threshold=-1", "threshold=256",
                    "bcheight=0", "bcheight=256", "bcwidth=1", "bcwidth=7", "logo=nv:0", "logo=nv:256",
                    "logo=banana", "cut=diagonal", "drawer=pin7", "profile=star", "font=c", "barcode=laser",
                    "nokey", "=value", "unknown=1"};
                for (int i = 0; i < asBad.length; i++) {
                    ESCPOSConfig bad = new ESCPOSConfig();
                    bad.applyOption(asBad[i]);
                    check("'" + asBad[i] + "' must be recorded as an error", bad.getErrors() != null);
                }
                ESCPOSConfig nulls = new ESCPOSConfig();
                nulls.applyOption(null);
                nulls.applyOption("");
                check("applyOption(null) and applyOption(\"\") must never throw", true);
                checkEquals("encode(null) must return an empty array", 0, nulls.encode(null).length);
                checkEquals("encode(\"\") must return an empty array", 0, nulls.encode("").length);
            }
        });
        add("config-generic-defaults-437", new Runner() {
            @Override
            public void run() throws Exception {
                ESCPOSConfig c = new ESCPOSConfig();
                c.applyOption("profile=generic");
                checkEquals("profile=generic must change the DEFAULT code page to 437",
                        (byte) 0x00, c.getCodePageByte());
                ESCPOSConfig d = new ESCPOSConfig();
                d.applyOption("profile=generic");
                d.applyOption("cp=858");
                checkEquals("an explicit cp must beat the profile default", (byte) 0x13, d.getCodePageByte());
            }
        });
        add("config-factory-single-printer", new Runner() {
            @Override
            public void run() throws Exception {
                // THE MULTI PROVIDER CANARY. DeviceTicketFactory loops every
                // ServiceLoader provider and calls addPrinter() for each one, so
                // a second registered provider would double this list and the
                // second put() would win the map.
                File f = temp("bizapp-canary-", ".bin");
                Map<String, String> m = baseProps("escpos:file," + f.getAbsolutePath());
                m.put("machine.printer.2", "escpos:file," + f.getAbsolutePath());
                m.put("machine.printer.3", "escpos:file," + f.getAbsolutePath());
                DeviceTicketFactory factory = new DeviceTicketFactory(null, props(m));
                checkEquals("three configured printers must produce exactly three devices; six means "
                        + "a second provider was added to services/META-INF/services/",
                        3, factory.getDevicePrinterAll().size());
                checkEquals("printer 1", "DevicePrinterESCPOS",
                        factory.getDevicePrinter("1").getClass().getSimpleName());
                checkEquals("printer 3", "DevicePrinterESCPOS",
                        factory.getDevicePrinter("3").getClass().getSimpleName());
                checkEquals("an unconfigured index must fall back to the null printer",
                        "DevicePrinterNull", factory.getDevicePrinter("9").getClass().getSimpleName());
            }
        });
    }

    // --- K ---
    private static void registerRegression() {
        add("regress-printer-plaintext", new Runner() {
            @Override
            public void run() throws Exception {
                checkPrinterClass("plaintext:file,/tmp/bizapp-regress.txt", "DevicePrinterPlainText");
                checkPrinterClass("plaintext:file,/tmp/bizapp-regress.txt,unix", "DevicePrinterPlainText");
                checkPrinterClass("plaintext:file,C:\\Temp\\r.txt", "DevicePrinterPlainText");
                checkPrinterClass("plaintext:notafile,x", "DevicePrinterNull");
                checkPrinterClass("plaintext:", "DevicePrinterNull");
            }
        });
        add("regress-printer-plaintext-bytes", new Runner() {
            @Override
            public void run() throws Exception {
                // D4 by byte, not by class name: CRLF for the default, LF for unix,
                // five blank lines at the end, and the file truncated per receipt.
                File f = temp("bizapp-plaintext-", ".txt");
                DevicePrinter p = printer1("plaintext:file," + f.getAbsolutePath());
                p.beginReceipt();
                p.beginLine(Integer.valueOf(0));
                p.printText(Integer.valueOf(12), "none", Boolean.FALSE, "AB");
                p.endLine();
                p.endReceipt();
                byte[] got = awaitFile(f, 14, AWAIT_MILLIS);
                byte[] want = {0x41, 0x42, 0x0D, 0x0A, 0x0D, 0x0A, 0x0D, 0x0A, 0x0D, 0x0A, 0x0D, 0x0A, 0x0D, 0x0A};
                check("plaintext must still end every line CRLF and pad five blank lines\n"
                        + HexUtil.diff(want, got), Arrays.equals(want, got));

                File g = temp("bizapp-plaintext-unix-", ".txt");
                DevicePrinter q = printer1("plaintext:file," + g.getAbsolutePath() + ",unix");
                q.beginReceipt();
                q.beginLine(Integer.valueOf(0));
                q.printText(Integer.valueOf(12), "none", Boolean.FALSE, "AB");
                q.endLine();
                q.endReceipt();
                byte[] got2 = awaitFile(g, 8, AWAIT_MILLIS);
                byte[] want2 = {0x41, 0x42, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A};
                check("plaintext,unix must still end every line LF\n" + HexUtil.diff(want2, got2),
                        Arrays.equals(want2, got2));
            }
        });
        add("regress-printer-null-values", new Runner() {
            @Override
            public void run() throws Exception {
                checkPrinterClass("Not defined", "DevicePrinterNull");
                checkPrinterClass("window", "DevicePrinterNull");
                checkPrinterClass("zzz:qqq", "DevicePrinterNull");
                checkPrinterClass("printer:(Default)", "DevicePrinterNull");
                checkEquals("the 3-arg overload's default arm must be untouched",
                        "DevicePrinterNull", printer3("zzz:qqq").getClass().getSimpleName());
            }
        });
        add("regress-printer-screen", new Runner() {
            @Override
            public void run() throws Exception {
                requireHeaded();
                checkPrinterClass("screen", "DevicePrinterPanel");
            }
        });
        add("regress-printer-osqueue", new Runner() {
            @Override
            public void run() throws Exception {
                requireHeaded();
                checkEquals("printer:(Default),receipt must still build the AWT graphics driver",
                        "DevicePrinterPrinter", printer3("printer:(Default),receipt").getClass().getSimpleName());
                checkEquals("printer:(Default),standard must still build the AWT graphics driver",
                        "DevicePrinterPrinter", printer3("printer:(Default),standard").getClass().getSimpleName());
                checkEquals("printer:(Show dialog),receipt must still build the AWT graphics driver",
                        "DevicePrinterPrinter", printer3("printer:(Show dialog),receipt").getClass().getSimpleName());
            }
        });
        add("regress-display", new Runner() {
            @Override
            public void run() throws Exception {
                DisplayEmulator e = new DisplayEmulator();
                checkEquals("machine.display=Not defined", "DeviceDisplayNull",
                        e.getDisplay("Not defined").getClass().getSimpleName());
                checkEquals("machine.display=zzz", "DeviceDisplayNull",
                        e.getDisplay("zzz").getClass().getSimpleName());
                checkEquals("machine.display= (empty)", "DeviceDisplayNull",
                        e.getDisplay("").getClass().getSimpleName());
                checkEquals("machine.display=null", "DeviceDisplayNull",
                        e.getDisplay(null).getClass().getSimpleName());
            }
        });
        add("regress-display-screen", new Runner() {
            @Override
            public void run() throws Exception {
                requireHeaded();
                DisplayEmulator e = new DisplayEmulator();
                checkEquals("machine.display=screen", "DeviceDisplayPanel",
                        e.getDisplay("screen").getClass().getSimpleName());
            }
        });
        add("regress-scale", new Runner() {
            @Override
            public void run() throws Exception {
                ScaleEmulator e = new ScaleEmulator();
                checkEquals("machine.scale=Not defined", "DeviceScaleNull",
                        e.getScale("Not defined").getClass().getSimpleName());
                checkEquals("machine.scale=zzz", "DeviceScaleNull",
                        e.getScale("zzz").getClass().getSimpleName());
                // Degenerate serial strings must parse without throwing.
                for (String s : new String[]{"serial:", "serial"}) {
                    DeviceScale d = e.getScale(s);
                    check("machine.scale=" + s + " must never yield null", d != null);
                }
            }
        });
        add("regress-scale-fake-now-live", new Runner() {
            @Override
            public void run() throws Exception {
                // INTENTIONAL BEHAVIOUR CHANGE, documented in docs/PERIPHERALS.md.
                // Before: getScale(String) ignored its argument and always
                // returned DeviceScaleNull, so machine.scale=fake was documented
                // but dead. After: it returns a random weight.
                ScaleEmulator e = new ScaleEmulator();
                DeviceScale d = e.getScale("fake");
                checkEquals("machine.scale=fake is now reachable", "ScaleFake", d.getClass().getSimpleName());
                Double w = d.readWeight();
                check("ScaleFake must return a weight in 0..2 kg", w != null && w >= 0.0 && w <= 2.0);
            }
        });
    }

    // --- L ---
    private static void registerTicketParser() {
        add("ticket-minimal", new Runner() {
            @Override
            public void run() throws Exception {
                TcpPrinterStub stub = new TcpPrinterStub();
                try {
                    Map<String, String> m = baseProps("escpos:network," + stub.getHost() + "," + stub.getPort());
                    DeviceTicketFactory factory = new DeviceTicketFactory(null, props(m));
                    checkEquals("the factory must have built the ESC/POS driver", "DevicePrinterESCPOS",
                            factory.getDevicePrinter("1").getClass().getSimpleName());
                    printFixture(factory, "minimal-ticket.xml");

                    byte[] jobReceipt = stub.awaitJob(AWAIT_MILLIS);
                    check("the receipt never reached the stub", jobReceipt != null);
                    expect("ticket-minimal", jobReceipt);

                    byte[] jobDrawer = stub.awaitJob(AWAIT_MILLIS);
                    check("the drawer kick never reached the stub", jobDrawer != null);
                    expect("receipt-minimal-drawer", jobDrawer);

                    checkEquals("the receipt and the drawer kick are two separate jobs, so two connections",
                            2, stub.getConnectionCount());
                } finally {
                    stub.close();
                }
            }
        });
        add("ticket-columns", new Runner() {
            @Override
            public void run() throws Exception {
                File f = temp("bizapp-ticket-columns-", ".bin");
                Map<String, String> m = baseProps("escpos:file," + f.getAbsolutePath());
                DeviceTicketFactory factory = new DeviceTicketFactory(null, props(m));
                checkEquals("the factory must have built the ESC/POS driver", "DevicePrinterESCPOS",
                        factory.getDevicePrinter("1").getClass().getSimpleName());
                printFixture(factory, "columns-ticket.xml");
                byte[] got = awaitFile(f, 128, AWAIT_MILLIS);
                checkEquals("the 42 column ticket is 128 bytes", 128, got.length);
                check("no ESC a may appear: TicketParser has already aligned every run in software",
                        indexOf(got, new byte[]{0x1B, 0x61, 0x01}) < 0);
                checkEquals("six <text> elements fire cutPaper six times through the parser fall through; "
                        + "the roll must still be cut exactly once", 1, countOf(got, new byte[]{0x1D, 0x56}));
                expect("ticket-columns", got);
            }
        });
    }
}
