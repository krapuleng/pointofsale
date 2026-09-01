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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.event.PrintJobAdapter;
import javax.print.event.PrintJobEvent;

/**
 * RAW byte transport to a named operating-system print queue, through
 * javax.print with DocFlavor.BYTE_ARRAY.AUTOSENSE. This is the "USB" path on
 * Windows and Linux: a USB, parallel or shared thermal printer is driven this
 * way once its vendor driver or a generic RAW queue is installed.
 *
 * A print queue is document-oriented, so bytes are accumulated and submitted as
 * ONE job on flush. Submitting per write would produce one cut receipt per
 * line.
 *
 * There is no status read-back on this transport. Paper-out, cover-open and
 * jams are invisible here and this class does not pretend otherwise.
 *
 * @author Andrey Svininykh <svininykh@gmail.com>
 * @version NORD POS 3.0
 */
public class WritterPrintService extends Writter {

    private static final Logger logger = Logger.getLogger(WritterPrintService.class.getName());

    private static final long JOB_TIMEOUT_SECONDS = 30L;

    private final String m_sPrintServiceName;
    private final ByteArrayOutputStream m_buffer;

    public WritterPrintService(String sPrintServiceName) {
        m_sPrintServiceName = sPrintServiceName;
        m_buffer = new ByteArrayOutputStream();
    }

    /**
     * @return the configured queue name, for the printer description
     */
    public String getTarget() {
        return m_sPrintServiceName;
    }

    @Override
    protected void internalWrite(byte[] data) {
        if (data != null) {
            m_buffer.write(data, 0, data.length);
        } else {
            m_buffer.write(0x00);
        }
    }

    @Override
    protected void internalFlush() {
        if (m_buffer.size() == 0) {
            return;
        }
        byte[] job = m_buffer.toByteArray();
        try {
            PrintService ps = lookup(m_sPrintServiceName);
            if (ps == null) {
                String sError = "No print queue named '" + m_sPrintServiceName + "' was found."
                        + " Queues that accept raw data on this machine: " + joinRawPrintServiceNames() + "."
                        + " On macOS raw queues are no longer supported — use escpos:network or escpos:file instead.";
                logger.log(Level.SEVERE, sError);
                setLastError(sError);
                return;
            }
            submit(ps, job);
        } catch (Throwable t) {
            String sError = "Could not send the receipt to print queue '" + m_sPrintServiceName + "': " + t;
            logger.log(Level.SEVERE, sError);
            logger.log(Level.FINE, sError, t);
            setLastError(sError);
        } finally {
            m_buffer.reset();
        }
    }

    private void submit(PrintService ps, byte[] job) throws Exception {
        Doc doc = new SimpleDoc(job, DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
        DocPrintJob printJob = ps.createPrintJob();

        final CountDownLatch latch = new CountDownLatch(1);
        final String[] aFailure = new String[1];

        printJob.addPrintJobListener(new PrintJobAdapter() {
            @Override
            public void printJobCompleted(PrintJobEvent pje) {
                latch.countDown();
            }

            @Override
            public void printJobNoMoreEvents(PrintJobEvent pje) {
                latch.countDown();
            }

            @Override
            public void printJobFailed(PrintJobEvent pje) {
                aFailure[0] = "the print queue reported the job as failed";
                latch.countDown();
            }

            @Override
            public void printJobCanceled(PrintJobEvent pje) {
                aFailure[0] = "the print job was cancelled";
                latch.countDown();
            }
        });

        // An EMPTY attribute set. Copies(1) and JobName are both rejected by a
        // real CUPS raw queue (getUnsupportedAttributes reports both), and a
        // rejected attribute can get the whole job refused.
        printJob.print(doc, new HashPrintRequestAttributeSet());

        boolean bFinished;
        try {
            bFinished = latch.await(JOB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            bFinished = false;
        }

        if (!bFinished) {
            String sError = "Print queue '" + m_sPrintServiceName + "' did not finish the receipt within "
                    + JOB_TIMEOUT_SECONDS + " seconds. Check that the printer is on line and not paused.";
            logger.log(Level.SEVERE, sError);
            setLastError(sError);
        } else if (aFailure[0] != null) {
            String sError = "Print queue '" + m_sPrintServiceName + "' did not print the receipt: "
                    + aFailure[0] + ".";
            logger.log(Level.SEVERE, sError);
            setLastError(sError);
        } else {
            setLastError(null);
        }
    }

    @Override
    protected void internalClose() {
        internalFlush();
    }

    private static PrintService lookup(String sName) {
        if (sName == null || sName.isEmpty()) {
            return null;
        }
        // Deliberately NOT com.openbravo.pos.util.ReportUtils.getPrintService:
        // that looks up with DocFlavor.SERVICE_FORMATTED.PRINTABLE and a RAW
        // queue does not appear in that list at all.
        PrintService[] aServices = PrintServiceLookup.lookupPrintServices(DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
        if (aServices == null) {
            return null;
        }
        for (PrintService ps : aServices) {
            if (sName.equals(ps.getName())) {
                return ps;
            }
        }
        for (PrintService ps : aServices) {
            if (sName.equalsIgnoreCase(ps.getName())) {
                return ps;
            }
        }
        return null;
    }

    /**
     * The queues javax.print will accept raw bytes for, as a hint for the
     * operator.
     *
     * NOTE this is NOT a RAW-ness test. isDocFlavorSupported(AUTOSENSE) returns
     * true on essentially every CUPS queue, graphics drivers and cloud queues
     * included, because CUPS advertises application/octet-stream everywhere and
     * then MIME auto-types the data. Pointing this transport at a graphics
     * queue produces pages of garbage and there is no way to detect it here.
     *
     * @return the queue names, never null
     */
    public static List<String> listRawPrintServiceNames() {
        List<String> aNames = new ArrayList<>();
        try {
            PrintService[] aServices = PrintServiceLookup.lookupPrintServices(DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
            if (aServices != null) {
                for (PrintService ps : aServices) {
                    aNames.add(ps.getName());
                }
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Could not enumerate print queues", t);
            return new ArrayList<>();
        }
        return aNames;
    }

    private static String joinRawPrintServiceNames() {
        List<String> aNames = listRawPrintServiceNames();
        if (aNames.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < aNames.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(aNames.get(i));
        }
        return sb.toString();
    }
}
