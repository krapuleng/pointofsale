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

import com.nordpos.device.traslator.UnicodeTranslatorInt;
import com.nordpos.device.writter.Writter;
import com.nordpos.device.writter.WritterNetwork;
import com.nordpos.device.writter.WritterRXTX;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JComponent;

/**
 * Customer display of the Epson DM-D family (and the many 2x20 VFD clones that
 * follow the same command set), driven over any Writter transport.
 *
 * The command set used here is deliberately tiny and is the intersection of
 * every DM-D compatible display: ESC @ (initialise), FF (clear the screen and
 * home the cursor) and US $ x y (move the cursor). Nothing else is emitted.
 *
 * @author Andrey Svininykh <svininykh@gmail.com>
 * @version NORD POS 4.0
 */
public class DeviceDisplayEpson extends DeviceDisplaySerial {

    private static final Logger logger = Logger.getLogger(DeviceDisplayEpson.class.getName());

    private static final byte ESC = 0x1B;
    private static final byte US = 0x1F;
    private static final byte FF = 0x0C;

    /**
     * While the transport reports an error, at most one frame is handed over
     * per this many milliseconds instead of one every 250 ms. It bounds the
     * reconnect attempts AND the SEVERE line each failed attempt writes to the
     * log: an unreachable display used to log about 2.5 lines a second for as
     * long as the till stayed open.
     */
    private static final long ERROR_RETRY_MILLIS = 5000L;

    /**
     * How often this class itself reports a continuing display failure. The
     * first frame that fails logs at once, then nothing until this interval
     * has passed, so an identical repeating failure never floods the log.
     */
    private static final long ERROR_LOG_MILLIS = 60000L;

    // These fields are assigned after super() and read by initVisor() and
    // repaintLines(), so they cannot be final.
    private Charset m_charset;
    private int m_iColumns;
    private boolean m_bCursorPositioning;

    private CharsetEncoder m_encoder;

    /**
     * One frame in flight at a time. Never null: an unpaced transport gets a
     * gate that always admits, which is the pre-existing behaviour.
     */
    private final FrameGate m_gate;

    // Touched only from the EDT, by repaintLines().
    private long m_lNextAttemptAfterError = 0L;
    private long m_lNextErrorLog = 0L;
    private String m_sLoggedError = null;

    /**
     * The assignment order below is load-bearing: DeviceDisplaySerial.init()
     * calls the abstract initVisor(), so every field that initVisor() and
     * repaintLines() read must already hold its final value when init() runs.
     *
     * @param display the transport to write to
     * @param charset the encoder for the display code page, or null to use the
     * built-in UnicodeTranslatorInt table
     * @param iColumns characters per line, 20 on a standard 2x20 VFD
     * @param bCursorPositioning true to emit US $ x y instead of relying on the
     * display's own wrap at the end of line 1
     */
    public DeviceDisplayEpson(Writter display, Charset charset, int iColumns, boolean bCursorPositioning) {
        super();
        m_charset = charset;
        m_iColumns = iColumns < 1 ? 20 : iColumns;
        m_bCursorPositioning = bCursorPositioning;
        if (display instanceof PacedWritter) {
            m_gate = ((PacedWritter) display).getFrameGate();
        } else {
            // Every transport DisplayEmulator builds is paced. A hand-built
            // one is accepted rather than refused, but it cannot coalesce, so
            // say so once instead of silently queueing without a bound.
            m_gate = FrameGate.unpaced();
            logger.log(Level.WARNING, "Customer display transport {0} cannot report when a frame has been written,"
                    + " so display frames cannot be coalesced.", display == null ? "null" : display.getClass().getName());
        }
        init(display);
    }

    @Override
    public final void initVisor() {
        // ESC @ (initialise) then FF (clear the screen, cursor home).
        // Writter.init() is deliberately NOT used: it is write-once for the
        // life of the instance, so it would never run again after a reconnect.
        // Claimed through the gate like every other frame so the two never
        // overlap and the frame counters stay exact. init() runs from the
        // constructor, before any timer exists, so nothing can be in flight
        // and the gate always admits this one.
        if (m_gate.tryEnqueueFrame()) {
            handOver(new byte[]{ESC, 0x40, FF}); // ESC @ , FF
        }
    }

    @Override
    public void repaintLines() {

        // Runs on the EDT from the 250 ms timer in DeviceDisplayBase. It may
        // do nothing but format bytes and hand them to the Writter: no I/O, no
        // blocking, no logging on the happy path.

        // A display frame is worth nothing the moment the next one is ready,
        // so a frame that cannot be handed over now is DROPPED, never queued.
        // Writter.write() is fire and forget onto an unbounded single thread
        // executor: without this the 250 ms timer would enqueue four frames a
        // second for ever against a display that is not answering, and the
        // drawer kick or receipt queued behind them would wait minutes.
        if (!admits()) {
            return;
        }

        String sLine1 = fitToColumns(m_displaylines.getLine1());
        String sLine2 = fitToColumns(m_displaylines.getLine2());

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        if (m_bCursorPositioning) {
            buffer.write(US);  // US $ 1 1 - cursor to column 1, row 1
            buffer.write(0x24);
            buffer.write(0x01);
            buffer.write(0x01);
            writeQuietly(buffer, encode(sLine1));
            buffer.write(US);  // US $ 1 2 - cursor to column 1, row 2
            buffer.write(0x24);
            buffer.write(0x01);
            buffer.write(0x02);
            writeQuietly(buffer, encode(sLine2));
        } else {
            buffer.write(FF);  // FF - clear the screen, cursor home
            writeQuietly(buffer, encode(sLine1));
            writeQuietly(buffer, encode(sLine2));
        }

        // A freshly allocated array every time: Writter.write() runs later on
        // a private single-thread executor and must never be handed a buffer
        // that is touched again afterwards.
        handOver(buffer.toByteArray());

        // Never flush(): on WritterNetwork flush() closes the socket, which a
        // 250 ms timer would turn into a reconnect storm, and on WritterFile it
        // closes and truncates. Both WritterNetwork and WritterRXTX flush their
        // underlying stream inside internalWrite, so the bytes leave promptly.
    }

    @Override
    public JComponent getDisplayComponent() {
        return null;
    }

    /**
     * The two reasons a frame is dropped instead of sent, in the order that
     * costs least: the error back off first, because it needs no state from
     * the transport, then the one-frame-in-flight gate.
     *
     * @return true when this tick may build and hand over a frame
     */
    private boolean admits() {

        long lNow = System.currentTimeMillis();
        String sError = display.getLastError();

        if (sError == null) {
            // Recovered: back to the full 250 ms cadence, and the next failure
            // is reported immediately rather than swallowed by the interval.
            m_lNextAttemptAfterError = 0L;
            if (m_sLoggedError != null) {
                logger.log(Level.INFO, "Customer display is writing again.");
                m_sLoggedError = null;
                m_lNextErrorLog = 0L;
            }
        } else {
            if (lNow < m_lNextAttemptAfterError) {
                m_gate.frameSkipped();
                return false;
            }
            m_lNextAttemptAfterError = lNow + ERROR_RETRY_MILLIS;
            logFailure(sError, lNow);
        }

        return m_gate.tryEnqueueFrame();
    }

    /**
     * Rate limited: the same failure text is reported once and then at most
     * once every ERROR_LOG_MILLIS, with the number of frames dropped since,
     * so a display that is unplugged for a whole shift costs a handful of
     * lines rather than thousands.
     */
    private void logFailure(String sError, long lNow) {

        if (sError.equals(m_sLoggedError) && lNow < m_lNextErrorLog) {
            return;
        }

        if (sError.equals(m_sLoggedError)) {
            logger.log(Level.WARNING, "Customer display is still failing: {0} ({1} frames dropped so far.)",
                    new Object[]{sError, Long.valueOf(m_gate.getFramesSkipped())});
        } else {
            logger.log(Level.WARNING, "Customer display: {0}", sError);
        }

        m_sLoggedError = sError;
        m_lNextErrorLog = lNow + ERROR_LOG_MILLIS;
    }

    /**
     * Hands one frame to the transport. The gate is released here if the
     * hand over itself fails, because nothing will then run internalWrite to
     * release it and the display would stay dark for ever.
     */
    private void handOver(byte[] bFrame) {
        boolean bAccepted = false;
        try {
            display.write(bFrame);
            bAccepted = true;
        } finally {
            if (!bAccepted) {
                m_gate.frameAbandoned();
            }
        }
    }

    /**
     * @return frames handed to the transport since start up
     */
    public long getFramesEnqueued() {
        return m_gate.getFramesEnqueued();
    }

    /**
     * @return frames dropped rather than queued, by coalescing or by the error
     * back off
     */
    public long getFramesSkipped() {
        return m_gate.getFramesSkipped();
    }

    /**
     * @return true while a frame handed over has not yet been written
     */
    public boolean isFrameInFlight() {
        return m_gate.isFrameInFlight();
    }

    private String fitToColumns(String sLine) {
        String sValue = sLine == null ? "" : sLine;
        if (sValue.length() >= m_iColumns) {
            return sValue.substring(0, m_iColumns);
        }
        StringBuilder sb = new StringBuilder(m_iColumns);
        sb.append(sValue);
        while (sb.length() < m_iColumns) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private byte[] encode(String sText) {
        if (sText == null) {
            return new byte[0];
        }
        if (m_charset == null) {
            byte[] bLegacy = new UnicodeTranslatorInt().transString(sText);
            return bLegacy == null ? new byte[0] : bLegacy;
        }
        synchronized (this) {
            if (m_encoder == null) {
                m_encoder = m_charset.newEncoder();
                m_encoder.onMalformedInput(CodingErrorAction.REPLACE);
                m_encoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
                m_encoder.replaceWith(new byte[]{0x3F}); // '?'
            }
            try {
                m_encoder.reset();
                ByteBuffer bb = m_encoder.encode(CharBuffer.wrap(sText));
                byte[] bResult = new byte[bb.remaining()];
                bb.get(bResult);
                return bResult;
            } catch (CharacterCodingException e) {
                // REPLACE on both actions makes this unreachable; a display
                // line is never worth an exception on the EDT.
                return new byte[0];
            }
        }
    }


    /**
     * The one-frame-in-flight gate, shared between this display and its
     * transport: the display claims it before handing a frame over, the
     * transport releases it when that frame has actually been written. It is
     * the only completion signal a Writter can give, because Writter.write()
     * is fire and forget and returns long before the bytes reach the wire.
     */
    public static final class FrameGate {

        private final boolean m_bPaced;
        private final AtomicBoolean m_bInFlight = new AtomicBoolean(false);
        private final AtomicLong m_lEnqueued = new AtomicLong(0L);
        private final AtomicLong m_lSkipped = new AtomicLong(0L);

        public FrameGate() {
            this(true);
        }

        private FrameGate(boolean bPaced) {
            m_bPaced = bPaced;
        }

        /**
         * @return a gate for a transport that cannot report completion; it
         * admits every frame, exactly as before this class existed
         */
        static FrameGate unpaced() {
            return new FrameGate(false);
        }

        /**
         * @return true when the caller may hand over exactly one frame
         */
        boolean tryEnqueueFrame() {
            if (!m_bPaced) {
                m_lEnqueued.incrementAndGet();
                return true;
            }
            if (m_bInFlight.compareAndSet(false, true)) {
                m_lEnqueued.incrementAndGet();
                return true;
            }
            m_lSkipped.incrementAndGet();
            return false;
        }

        void frameSkipped() {
            m_lSkipped.incrementAndGet();
        }

        /**
         * The hand over threw, so no transport thread will ever release the
         * gate for this frame.
         */
        void frameAbandoned() {
            m_bInFlight.set(false);
        }

        /**
         * Called by the transport on the writter thread when the frame has
         * been written, or has failed: either way it is no longer in flight.
         */
        public void frameWritten() {
            m_bInFlight.set(false);
        }

        public boolean isFrameInFlight() {
            return m_bInFlight.get();
        }

        public long getFramesEnqueued() {
            return m_lEnqueued.get();
        }

        public long getFramesSkipped() {
            return m_lSkipped.get();
        }
    }

    /**
     * A transport that can tell the display when a frame has been written.
     */
    public interface PacedWritter {

        FrameGate getFrameGate();
    }

    /**
     * A network transport for the customer display. Identical to
     * WritterNetwork on the wire - not one byte is added, removed or
     * reordered - it only releases the frame gate when internalWrite has
     * finished, which is what lets repaintLines() drop a frame rather than
     * queue it behind a display that is not answering.
     */
    public static class PacedWritterNetwork extends WritterNetwork implements PacedWritter {

        private final FrameGate m_gate = new FrameGate();

        public PacedWritterNetwork(String sHost, int iPort, String sDeviceLabel) {
            super(sHost, iPort, sDeviceLabel);
        }

        @Override
        public FrameGate getFrameGate() {
            return m_gate;
        }

        @Override
        protected void internalWrite(byte[] data) {
            try {
                super.internalWrite(data);
            } finally {
                m_gate.frameWritten();
            }
        }
    }

    /**
     * The serial equivalent. A serial display behind RTS/CTS whose printer is
     * switched off blocks in write() exactly as a stalled socket does, so it
     * needs the same gate.
     */
    public static class PacedWritterRXTX extends WritterRXTX implements PacedWritter {

        private final FrameGate m_gate = new FrameGate();

        public PacedWritterRXTX(String sPortPrinter, Integer iPortSpeed, Integer iPortBits,
                Integer iPortStopBits, Integer iPortParity, Integer iFlowControl) {
            super(sPortPrinter, iPortSpeed, iPortBits, iPortStopBits, iPortParity, iFlowControl);
        }

        @Override
        public FrameGate getFrameGate() {
            return m_gate;
        }

        @Override
        protected void internalWrite(byte[] data) {
            try {
                super.internalWrite(data);
            } finally {
                m_gate.frameWritten();
            }
        }
    }

    private static void writeQuietly(ByteArrayOutputStream buffer, byte[] data) {
        buffer.write(data, 0, data.length);
    }
}
