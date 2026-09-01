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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local ServerSocket that impersonates a port 9100 thermal printer, so
 * WritterNetwork can be proven end to end with no hardware.
 *
 * Bound explicitly to the loopback address: this sandbox refuses non loopback
 * egress and the test must never depend on it.
 *
 * @author Andrey Svininykh &lt;svininykh@gmail.com&gt;
 * @version NORD POS 4.0
 */
public class TcpPrinterStub {

    private final ServerSocket m_server;
    private final Thread m_acceptor;
    private final BlockingQueue<byte[]> m_queue = new LinkedBlockingQueue<byte[]>();
    private final AtomicInteger m_iConnections = new AtomicInteger();
    private volatile boolean m_bClosed;

    public TcpPrinterStub() throws IOException {
        m_server = new ServerSocket();
        m_server.setReuseAddress(true);
        m_server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        m_acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "TcpPrinterStub-accept");
        m_acceptor.setDaemon(true);
        m_acceptor.start();
    }

    private void acceptLoop() {
        while (!m_bClosed) {
            Socket s = null;
            try {
                s = m_server.accept();
                m_iConnections.incrementAndGet();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                InputStream in = s.getInputStream();
                byte[] buffer = new byte[4096];
                int iRead;
                while ((iRead = in.read(buffer)) > 0) {
                    out.write(buffer, 0, iRead);
                }
                m_queue.add(out.toByteArray());
            } catch (IOException e) {
                if (!m_bClosed) {
                    // A half open or reset connection is not a harness failure;
                    // the case that cares will time out in awaitJob instead.
                    continue;
                }
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException e) {
                        // nothing useful to do while tearing a stub socket down
                    }
                }
            }
        }
    }

    public int getPort() {
        return m_server.getLocalPort();
    }

    public String getHost() {
        return InetAddress.getLoopbackAddress().getHostAddress();
    }

    /**
     * Accept one connection, read it to EOF, return its bytes. Null on timeout.
     */
    public byte[] awaitJob(long lTimeoutMillis) {
        try {
            return m_queue.poll(lTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Proves the connect per receipt contract: two receipts through one
     * WritterNetwork must produce two connections.
     */
    public int getConnectionCount() {
        return m_iConnections.get();
    }

    public void close() {
        m_bClosed = true;
        try {
            m_server.close();
        } catch (IOException e) {
            // closing a stub listener has no recovery path
        }
        try {
            m_acceptor.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
