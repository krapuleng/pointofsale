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

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Raw TCP transport to a network thermal printer or customer display, the
 * de facto standard being port 9100.
 *
 * Pure java.net.Socket, so it adds no dependency and works on every platform
 * including Apple Silicon. There is deliberately no status read-back anywhere
 * in this class: a probe that hangs, or that a print server answers with its
 * own chatter, turns a sale into a frozen till.
 *
 * Three properties matter more than throughput, because every job runs on the
 * single Writter executor thread and the till keeps queueing work onto it:
 *
 * <ul>
 * <li><b>A failure must be cheap.</b> A dead target used to cost connect
 * timeout + retry delay + connect timeout on EVERY job, which is slower than
 * the till enqueues them, so the queue grows without bound and a drawer kick
 * queued behind a few receipts arrives a minute late. After one failed connect
 * the next attempts short circuit for {@link #CONNECT_BREAKER_COOLDOWN} ms.</li>
 * <li><b>A write must not be able to block forever.</b> setSoTimeout bounds
 * reads only; a peer that accepts the connection and then stops reading holds
 * a zero TCP window and pins the writter thread indefinitely. Every write is
 * guarded by a watchdog that closes the socket at
 * {@link #WRITE_DEADLINE} ms, turning the hang into an IOException.</li>
 * <li><b>A success claim must be honest.</b> flush() on a socket stream is a
 * no-op and proves nothing, so the delivery verdict is deferred until the
 * socket has been closed at the end of the job. Even then the last few bytes
 * cannot be confirmed over TCP; that limit is documented, not papered over.</li>
 * </ul>
 *
 * @author Andrey Svininykh <svininykh@gmail.com>
 * @version NORD POS 3.0
 */
public class WritterNetwork extends Writter {

    private static final Logger logger = Logger.getLogger(WritterNetwork.class.getName());

    /**
     * Device labels used in every operator facing message. A dead customer
     * display must not tell the operator that the receipt printer is broken.
     */
    public static final String DEVICE_RECEIPT_PRINTER = "Receipt printer";
    public static final String DEVICE_CUSTOMER_DISPLAY = "Customer display";

    private static final int DEFAULT_CONNECT_TIMEOUT = 3000;
    private static final int DEFAULT_READ_TIMEOUT = 5000;
    private static final int RETRY_DELAY = 400;

    /**
     * How long a failed connect keeps the transport short circuited. Inside
     * this window a job fails in about a millisecond instead of paying
     * connect timeout + RETRY_DELAY + connect timeout again, so a queue of
     * work aimed at a dead printer drains as fast as it was posted.
     */
    private static final long CONNECT_BREAKER_COOLDOWN = 5000L;

    /**
     * How long one write may block before the socket is closed underneath it.
     * Generous enough that a slow thermal printer chewing through a long
     * receipt is never cut off, short enough that a stalled printer cannot own
     * the writter thread for the rest of the shift.
     */
    private static final long WRITE_DEADLINE = 15000L;

    /**
     * How long the end of a job waits for the device to complain before the
     * socket is closed and the job is called delivered.
     *
     * TCP hands the application no delivery receipt, and a socket stream
     * flush() is a plain no-op, so the ONLY evidence available that a printer
     * threw the job away is the reset it sends back. That reset surfaces on a
     * read, which is why the end of a job, and nothing else in this class,
     * reads: after the FIN has gone out, one read bounded by this window.
     * Silence means the device took the job, a byte of print server chatter
     * means the same, and a reset means the receipt did not print.
     *
     * It cannot hang the till: the window is fixed, it is paid on the private
     * Writter thread after every byte has already left, and it is skipped
     * entirely for a job that has already failed.
     */
    private static final int DELIVERY_CONFIRM_WINDOW = 250;

    /**
     * One shared daemon scheduler for every WritterNetwork instance. Daemon,
     * so it can never keep the JVM alive; single threaded, because its only
     * job is to call Socket.close() on a stalled socket; and cancelled tasks
     * are removed from the queue so a busy till does not accumulate 15 second
     * worth of dead entries. The thread itself is created lazily, on the first
     * write, and is never created at all in a shop with no network device.
     */
    private static final ScheduledThreadPoolExecutor WATCHDOG = newWatchdog();

    private final String m_sDeviceLabel;
    private final String m_sHost;
    private final int m_iPort;
    private final int m_iConnectTimeout;
    private final int m_iReadTimeout;

    private Socket m_socket;
    private OutputStream m_out;

    // Circuit breaker state. Written and read only on the Writter executor
    // thread, which serialises every internalWrite/internalFlush/internalClose.
    private String m_sBreakerError;
    private long m_lBreakerSince;

    // Job state. A job is the writes since the last flush, plus that flush.
    private boolean m_bJobOpen;
    private boolean m_bJobWrote;
    private boolean m_bJobFailed;

    public WritterNetwork(String sHost, int iPort) {
        this(sHost, iPort, DEVICE_RECEIPT_PRINTER, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    /**
     * @param sHost printer or display host name or address
     * @param iPort TCP port, 9100 on nearly every device
     * @param sDeviceLabel how this device is named to the operator, one of
     * {@link #DEVICE_RECEIPT_PRINTER} or {@link #DEVICE_CUSTOMER_DISPLAY}
     */
    public WritterNetwork(String sHost, int iPort, String sDeviceLabel) {
        this(sHost, iPort, sDeviceLabel, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public WritterNetwork(String sHost, int iPort, int iConnectTimeoutMillis, int iReadTimeoutMillis) {
        this(sHost, iPort, DEVICE_RECEIPT_PRINTER, iConnectTimeoutMillis, iReadTimeoutMillis);
    }

    public WritterNetwork(String sHost, int iPort, String sDeviceLabel,
            int iConnectTimeoutMillis, int iReadTimeoutMillis) {
        // The constructor must NOT connect. DeviceTicketFactory is built on the
        // EDT, and a blocking connect to an unreachable printer would freeze
        // application startup until the TCP timeout expires.
        m_sHost = sHost;
        m_iPort = iPort;
        m_sDeviceLabel = (sDeviceLabel == null || sDeviceLabel.isEmpty())
                ? DEVICE_RECEIPT_PRINTER
                : sDeviceLabel;
        m_iConnectTimeout = iConnectTimeoutMillis;
        m_iReadTimeout = iReadTimeoutMillis;
        m_socket = null;
        m_out = null;
        m_sBreakerError = null;
        m_lBreakerSince = 0L;
        m_bJobOpen = false;
        m_bJobWrote = false;
        m_bJobFailed = false;
    }

    /**
     * @return "host:port", for the printer description shown to the operator
     */
    public String getTarget() {
        return m_sHost + ":" + m_iPort;
    }

    /**
     * @return the operator facing name of this device, never null
     */
    public String getDeviceLabel() {
        return m_sDeviceLabel;
    }

    @Override
    protected void internalWrite(byte[] data) {

        if (!m_bJobOpen) {
            // First write since the last flush: a new job, and a new verdict.
            m_bJobOpen = true;
            m_bJobWrote = false;
            m_bJobFailed = false;
        }

        try {
            if (m_out == null) {
                connect();
            }

            guardedWrite(data == null ? new byte[]{0x00} : data);

            // The bytes are in the kernel send buffer. That is NOT a delivery:
            // the verdict waits for the socket close at the end of the job.
            m_bJobWrote = true;

        } catch (IOException e) {
            String sError = describe(e, true);
            // Actionable prose at SEVERE, the stack trace only at FINE: an
            // unreachable printer must not spray traces over the console of a
            // till that is otherwise completing sales normally.
            logger.log(Level.SEVERE, sError);
            logger.log(Level.FINE, sError, e);
            m_bJobFailed = true;
            hardClose();
            setLastError(sError);
            // The next internalWrite reconnects, which is what makes a
            // long-lived display connection self-healing. Within a receipt the
            // job stays marked as failed regardless, so a reconnect halfway
            // through a receipt can never be reported as a delivery.
        }
    }

    /**
     * Writes with a watchdog on the socket. A blocking write against a peer
     * that has stopped reading is not interruptible and setSoTimeout does not
     * touch it, so the only way out is to close the socket from another
     * thread: the blocked write then fails with an IOException, which the
     * caller's error path already handles.
     */
    private void guardedWrite(byte[] data) throws IOException {

        final Socket sock = m_socket;
        final AtomicBoolean bArmed = new AtomicBoolean(true);
        final AtomicBoolean bFired = new AtomicBoolean(false);

        ScheduledFuture<?> guard = WATCHDOG.schedule(new Runnable() {
            @Override
            public void run() {
                if (bArmed.compareAndSet(true, false)) {
                    bFired.set(true);
                    closeQuietly(sock);
                }
            }
        }, WRITE_DEADLINE, TimeUnit.MILLISECONDS);

        try {
            m_out.write(data);
            // Stream level flush only, never a socket close: a customer display
            // writes every 250 ms and never calls Writter.flush(). On a socket
            // stream this pushes the bytes out of the BufferedOutputStream, if
            // any, and confirms nothing at all about the peer.
            m_out.flush();
        } catch (IOException e) {
            if (bFired.get()) {
                throw new ReportedIOException(stalledMessage(data.length), e);
            }
            throw e;
        } finally {
            bArmed.set(false);
            guard.cancel(false);
        }
    }

    private void connect() throws IOException {

        long lNow = System.currentTimeMillis();
        if (m_sBreakerError != null && lNow - m_lBreakerSince < CONNECT_BREAKER_COOLDOWN) {
            // Short circuit. Repeating a connect that failed a moment ago buys
            // nothing and costs the whole timeout, which is what let a queue of
            // receipts and drawer kicks fall minutes behind the operator.
            throw new ReportedIOException(m_sBreakerError, null);
        }

        try {
            connectWithOneRetry();
            m_sBreakerError = null; // a live socket clears the breaker
        } catch (IOException e) {
            String sError = describe(e, false);
            m_sBreakerError = sError;
            m_lBreakerSince = System.currentTimeMillis();
            throw new ReportedIOException(sError, e);
        }
    }

    private void connectWithOneRetry() throws IOException {
        try {
            openSocket();
        } catch (IOException e) {
            // One retry, on a connect failure only. A write is NEVER retried:
            // the printer may already have emitted that paper.
            hardClose();
            logger.log(Level.FINE, describe(e, false), e);
            try {
                Thread.sleep(RETRY_DELAY);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            openSocket();
        }
    }

    private void openSocket() throws IOException {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(m_iReadTimeout);
            // No setSoLinger: nothing here ever reads, so a lingering close
            // buys no delivery guarantee whatsoever, and against a stalled
            // printer it costs the linger time on top of every receipt.
            s.connect(new InetSocketAddress(m_sHost, m_iPort), m_iConnectTimeout);
            m_socket = s;
            m_out = s.getOutputStream();
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
    }

    @Override
    protected void internalFlush() {
        // flush == end of job, release the device. Most printer network cards
        // accept ONE concurrent session on 9100, so a held socket locks out the
        // other tills, and an idle socket is silently reaped by NAT or by the
        // printer's own idle timer, producing half a receipt on the next sale.
        try {
            if (m_out != null) {
                m_out.flush();
            }
        } catch (IOException e) {
            String sError = describe(e, true);
            logger.log(Level.SEVERE, sError);
            logger.log(Level.FINE, sError, e);
            m_bJobFailed = true;
            setLastError(sError);
        } finally {
            closeAndReport();
        }
    }

    @Override
    protected void internalClose() {
        internalFlush();
    }

    /**
     * Ends the job: closes the socket and only then decides whether the job
     * was delivered. A close that fails means the peer rejected data it had
     * already acknowledged into its receive window, so the job FAILED, however
     * happily every write returned.
     */
    private void closeAndReport() {

        boolean bWrote = m_bJobWrote;
        boolean bFailed = m_bJobFailed;

        if (bWrote && !bFailed) {
            IOException rejected = awaitDeviceVerdict();
            if (rejected != null) {
                bFailed = true;
                String sError = describe(rejected, true);
                logger.log(Level.SEVERE, sError);
                logger.log(Level.FINE, sError, rejected);
                setLastError(sError);
            }
        }

        IOException closeFailure = closeSocket();
        if (closeFailure != null) {
            bFailed = true;
            String sError = describe(closeFailure, true);
            logger.log(Level.SEVERE, sError);
            logger.log(Level.FINE, sError, closeFailure);
            setLastError(sError);
        }

        if (bWrote && !bFailed) {
            // Every byte was accepted and the connection came down cleanly.
            // That is the strongest confirmation TCP can offer; the very last
            // bytes still cannot be proven printed - see docs/PERIPHERALS.md.
            setLastError(null);
        }

        m_bJobOpen = false;
        m_bJobWrote = false;
        m_bJobFailed = false;
    }

    /**
     * Half closes the connection and gives the device
     * {@link #DELIVERY_CONFIRM_WINDOW} ms to reject the job.
     *
     * @return the IOException the device answered with, or null when it either
     * said nothing, answered with chatter, or closed the connection normally -
     * all three of which mean it accepted everything it was sent
     */
    private IOException awaitDeviceVerdict() {

        Socket s = m_socket;
        if (s == null || s.isClosed()) {
            return null;
        }

        try {
            // FIN: the same end of job signal the close would send anyway, sent
            // first so that a device which answers a job with a reset has been
            // given the chance to.
            s.shutdownOutput();
            s.setSoTimeout(DELIVERY_CONFIRM_WINDOW);
            s.getInputStream().read();
            return null;
        } catch (SocketTimeoutException e) {
            // Silence. A thermal printer says nothing about a job it accepted.
            return null;
        } catch (SocketException e) {
            // A reset: the device discarded data it had already taken in.
            return e;
        } catch (IOException e) {
            return e;
        }
    }

    /**
     * Closes without judging the outcome: for the error path, where a failing
     * close is a consequence of the failure already reported.
     */
    private void hardClose() {
        IOException e = closeSocket();
        if (e != null) {
            logger.log(Level.FINE, e.getMessage(), e);
        }
    }

    /**
     * @return the IOException the close raised, or null if it came down clean
     */
    private IOException closeSocket() {

        IOException failure = null;

        if (m_out != null) {
            try {
                m_out.close();
            } catch (IOException e) {
                failure = e;
            }
            m_out = null;
        }

        if (m_socket != null) {
            try {
                m_socket.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                }
            }
            m_socket = null;
        }

        return failure;
    }

    private void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                logger.log(Level.FINE, e.getMessage(), e);
            }
        }
    }

    private String stalledMessage(int iBytes) {
        return m_sDeviceLabel + " at " + getTarget() + " stopped accepting data: "
                + iBytes + " bytes could not be handed over within " + WRITE_DEADLINE
                + " ms, so the job was abandoned and the connection dropped."
                + " Check for a paper jam, a printer that is offline or held, or a stalled print server.";
    }

    private String describe(IOException e, boolean bWriting) {
        if (e instanceof ReportedIOException) {
            // Already operator facing prose, produced where the cause was known.
            return e.getMessage();
        } else if (e instanceof ConnectException) {
            return m_sDeviceLabel + " at " + getTarget() + " refused the connection."
                    + " Check the IP address and that RAW/port 9100 printing is enabled on the device.";
        } else if (e instanceof SocketTimeoutException) {
            return m_sDeviceLabel + " at " + getTarget() + " did not answer within " + m_iConnectTimeout + " ms."
                    + " Check the IP address, the network cable and that the device is switched on.";
        } else if (e instanceof UnknownHostException) {
            return m_sDeviceLabel + " host '" + m_sHost + "' could not be resolved."
                    + " Check the host name, or configure the device by its IP address.";
        } else if (bWriting) {
            return m_sDeviceLabel + " at " + getTarget()
                    + " dropped the connection while the job was being sent, so it did not print in full.";
        } else {
            return m_sDeviceLabel + " at " + getTarget() + " could not be reached: " + e.getMessage();
        }
    }

    private static ScheduledThreadPoolExecutor newWatchdog() {
        ScheduledThreadPoolExecutor exec = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1,
                new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "bizapp-writter-network-watchdog");
                t.setDaemon(true);
                return t;
            }
        });
        exec.setRemoveOnCancelPolicy(true);
        return exec;
    }

    /**
     * An IOException whose message is already the operator facing text, so it
     * survives describe() unchanged instead of being reworded by the layer
     * that catches it.
     */
    private static class ReportedIOException extends IOException {

        private static final long serialVersionUID = 1L;

        ReportedIOException(String sMessage, Throwable cause) {
            super(sMessage, cause);
        }
    }
}
