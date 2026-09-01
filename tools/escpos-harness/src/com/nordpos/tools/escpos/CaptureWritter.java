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

import com.nordpos.device.writter.Writter;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Writter that captures bytes in memory, splits them on flush boundaries, and
 * lets the harness wait for the asynchronous executor to drain before it reads
 * anything.
 *
 * Writter is a public abstract class with protected abstract methods, so a
 * subclass in another package can legally override them.
 *
 * @author Andrey Svininykh &lt;svininykh@gmail.com&gt;
 * @version NORD POS 4.0
 */
public class CaptureWritter extends Writter {

    private final ByteArrayOutputStream m_jobs = new ByteArrayOutputStream();
    private final ByteArrayOutputStream m_current = new ByteArrayOutputStream();
    private final List<byte[]> m_jobList = Collections.synchronizedList(new ArrayList<byte[]>());
    private volatile int m_iFlushCount;
    private final AtomicInteger m_iFlushRequested = new AtomicInteger();

    @Override
    protected void internalWrite(byte[] data) {
        if (data == null) {
            // Matches WritterFile, which writes a single NUL for a null buffer.
            m_current.write(0x00);
            m_jobs.write(0x00);
        } else {
            m_current.write(data, 0, data.length);
            m_jobs.write(data, 0, data.length);
        }
    }

    @Override
    protected void internalFlush() {
        // Only a flush that actually closes a job creates a job entry, so the
        // barrier flush awaitQuiet() issues does not pollute getJobs(). The
        // counter still moves, which is what the barrier is waiting on.
        if (m_current.size() > 0) {
            m_jobList.add(m_current.toByteArray());
            m_current.reset();
        }
        m_iFlushCount++;
    }

    @Override
    protected void internalClose() {
        internalFlush();
    }

    public byte[] getAllBytes() {
        return m_jobs.toByteArray();
    }

    /**
     * One entry per flush that carried bytes: job 0 is the receipt, job 1 the
     * drawer kick, and so on.
     */
    public List<byte[]> getJobs() {
        synchronized (m_jobList) {
            return new ArrayList<byte[]>(m_jobList);
        }
    }

    public int getFlushCount() {
        return m_iFlushCount;
    }

    /**
     * Counts flushes as they are REQUESTED, on the calling thread, before the
     * executor gets to them. awaitQuiet needs this: see below.
     */
    @Override
    public void flush() {
        m_iFlushRequested.incrementAndGet();
        super.flush();
    }

    /**
     * Writter's ExecutorService is private, so the harness cannot submit a
     * barrier task of its own. Instead the requested and the executed flushes
     * are counted separately, and awaitQuiet issues one more flush and waits
     * for the executed count to catch up with the requested count. Because the
     * executor is single threaded and FIFO, that proves every earlier write has
     * already run.
     *
     * Waiting merely for the counter to MOVE is not enough, and this was a real
     * intermittent failure before it was fixed: a driver that has already issued
     * two flushes (a receipt, then a drawer kick) may have executed neither when
     * the count is sampled, so the first of them satisfies a "has it moved?"
     * test while the second job has not been written yet, and the assertion
     * reads one job instead of two.
     *
     * EVERY assertion must call this before reading bytes - the writes are
     * asynchronous and reading early gives an intermittently short array.
     */
    public void awaitQuiet(long lTimeoutMillis) {
        flush(); // the barrier, counted by the override above
        int iTarget = m_iFlushRequested.get();
        long lDeadline = System.currentTimeMillis() + lTimeoutMillis;
        while (m_iFlushCount < iTarget) {
            if (System.currentTimeMillis() > lDeadline) {
                throw new AssertionError("CaptureWritter.awaitQuiet timed out after " + lTimeoutMillis
                        + " ms waiting for the Writter executor to drain (" + m_iFlushCount
                        + " of " + iTarget + " flushes executed).");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("CaptureWritter.awaitQuiet was interrupted.");
            }
        }
    }
}
