/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.higherfrequencytrading.chronicle.tcp;

import com.higherfrequencytrading.chronicle.Chronicle;
import com.higherfrequencytrading.chronicle.EnumeratedMarshaller;
import com.higherfrequencytrading.chronicle.Excerpt;
import com.higherfrequencytrading.chronicle.impl.WrappedExcerpt;
import com.higherfrequencytrading.chronicle.tools.IOTools;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A Chronicle as a service to be replicated to any number of clients.  Clients can restart from where ever they are up
 * to.
 * <p/>
 * Can be used an in process component which wraps the underlying Chronicle and offers lower overhead than using
 * ChronicleSource
 *
 * @author peter.lawrey
 */
public class InProcessChronicleSource implements Chronicle {
    static final int IN_SYNC_LEN = -128;
    static final long HEARTBEAT_INTERVAL_MS = 2500;
    private static final int MAX_MESSAGE = 128;
    @NotNull
    private final Chronicle chronicle;
    private final ServerSocketChannel server;
    @NotNull
    private final String name;
    @NotNull
    private final ExecutorService service;
    private final Logger logger;
    private final Object notifier = new Object();
    private long busyWaitTimeNS = 100 * 1000;
    private volatile boolean closed = false;
    private long lastUnpausedNS = 0;

    public InProcessChronicleSource(@NotNull Chronicle chronicle, int port) throws IOException {
        this.chronicle = chronicle;
        server = ServerSocketChannel.open();
        server.socket().setReuseAddress(true);
        server.socket().bind(new InetSocketAddress(port));
        name = chronicle.name() + "@" + port;
        logger = Logger.getLogger(getClass().getName() + "." + name);
        service = Executors.newCachedThreadPool(new NamedThreadFactory(name));
        service.execute(new Acceptor());
    }

    public void busyWaitTimeNS(long busyWaitTimeNS) {
        this.busyWaitTimeNS = busyWaitTimeNS;
    }

    @Override
    public void multiThreaded(boolean multiThreaded) {
        chronicle.multiThreaded(multiThreaded);
    }

    private void pauseReset() {
        lastUnpausedNS = System.nanoTime();
    }

    protected void pause() {
        if (lastUnpausedNS + busyWaitTimeNS > System.nanoTime())
            return;
        try {
            synchronized (notifier) {
                notifier.wait(HEARTBEAT_INTERVAL_MS / 2);
            }
        } catch (InterruptedException ie) {
            logger.warning("Interrupt ignored");
        }
    }

    void wakeSessionHandlers() {
        synchronized (notifier) {
            notifier.notifyAll();
        }
    }

    @NotNull
    @Override
    public String name() {
        return chronicle.name();
    }

    @NotNull
    @Override
    public Excerpt createExcerpt() {
        return new SourceExcerpt();
    }

    @Override
    public long size() {
        return chronicle.size();
    }

    @Override
    public long sizeInBytes() {
        return chronicle.sizeInBytes();
    }

    @Override
    public ByteOrder byteOrder() {
        return chronicle.byteOrder();
    }

    @Override
    public void close() {
        closed = true;
        chronicle.close();
        try {
            server.close();
        } catch (IOException e) {
            logger.warning("Error closing server port " + e);
        }
    }

    @Override
    public <E> void setEnumeratedMarshaller(@NotNull EnumeratedMarshaller<E> marshaller) {
        chronicle.setEnumeratedMarshaller(marshaller);
    }

    @Nullable
    @Override
    public <E> EnumeratedMarshaller<E> getMarshaller(@NotNull Class<E> eClass) {
        return chronicle.getMarshaller(eClass);
    }

    private class Acceptor implements Runnable {
        @Override
        public void run() {
            Thread.currentThread().setName(name + "-acceptor");
            try {
                while (!closed) {
                    SocketChannel socket = server.accept();
                    service.execute(new Handler(socket));
                }
            } catch (IOException e) {
                if (!closed)
                    logger.log(Level.SEVERE, "Acceptor dying", e);
            } finally {
                service.shutdown();
            }
        }
    }

    class Handler implements Runnable {
        @NotNull
        private final SocketChannel socket;

        public Handler(@NotNull SocketChannel socket) throws SocketException {
            this.socket = socket;
            socket.socket().setSendBufferSize(256 * 1024);
            socket.socket().setTcpNoDelay(true);
        }

        @Override
        public void run() {
            try {
                long index = readIndex(socket);
                Excerpt excerpt = chronicle.createExcerpt();
                ByteBuffer bb = TcpUtil.createBuffer(1, chronicle.byteOrder()); // minimum size
                long sendInSync = 0;
                boolean first = true;
                OUTER:
                while (!closed) {
                    while (!excerpt.index(index)) {
//                        System.out.println("Waiting for " + index);
                        long now = System.currentTimeMillis();
                        if (sendInSync <= now && !first) {
                            bb.clear();
                            bb.putInt(IN_SYNC_LEN);
                            bb.flip();
                            IOTools.writeAll(socket, bb);
                            sendInSync = now + HEARTBEAT_INTERVAL_MS;
                        }
                        pause();
                        if (closed) break OUTER;
                    }
                    pauseReset();
//                    System.out.println("Writing " + index);
                    final int size = excerpt.capacity();
                    int remaining;

                    bb.clear();
                    if (first) {
//                        System.out.println("wi " + index);
                        bb.putLong(index);
                        first = false;
                        remaining = size + TcpUtil.HEADER_SIZE;
                    } else {
                        remaining = size + 4;
                    }
                    bb.putInt(size);
                    // for large objects send one at a time.
                    if (size > bb.capacity() / 2) {
                        while (remaining > 0) {
                            int size2 = Math.min(remaining, bb.capacity());
                            bb.limit(size2);
                            excerpt.read(bb);
                            bb.flip();
//                        System.out.println("w " + ChronicleTools.asString(bb));
                            remaining -= bb.remaining();
                            IOTools.writeAll(socket, bb);
                        }
                    } else {
                        bb.limit(remaining);
                        excerpt.read(bb);
                        int count = 1;
                        while (excerpt.index(index + 1) && count++ < MAX_MESSAGE) {
                            if (excerpt.remaining() + 4 >= bb.capacity() - bb.position())
                                break;
                            // if there is free space, copy another one.
                            int size2 = excerpt.capacity();
//                            System.out.println("W+ "+size);
                            bb.limit(bb.position() + size2 + 4);
                            bb.putInt(size2);
                            excerpt.read(bb);

                            index++;
                        }

                        bb.flip();
//                        System.out.println("W " + size + " wb " + bb);
                        IOTools.writeAll(socket, bb);
                    }
                    if (bb.remaining() > 0) throw new EOFException("Failed to send index=" + index);
                    index++;
                    sendInSync = 0;
//                    if (index % 20000 == 0)
//                        System.out.println(System.currentTimeMillis() + ": wrote " + index);
                }
            } catch (Exception e) {
                if (!closed) {
                    String msg = e.getMessage();
                    if (msg != null &&
                            (msg.contains("reset by peer") || msg.contains("Broken pipe")
                                    || msg.contains("was aborted by")))
                        logger.log(Level.INFO, "Connect " + socket + " closed from the other end " + e);
                    else
                        logger.log(Level.INFO, "Connect " + socket + " died", e);
                }
            }
        }

        private long readIndex(SocketChannel socket) throws IOException {
            ByteBuffer bb = ByteBuffer.allocate(8);
            IOTools.readFullyOrEOF(socket, bb);
            return bb.getLong(0);
        }

    }

    private class SourceExcerpt extends WrappedExcerpt {
        @SuppressWarnings("unchecked")
        public SourceExcerpt() {
            super(InProcessChronicleSource.this.chronicle.createExcerpt());
        }

        @Override
        public void finish() {
            super.finish();
            wakeSessionHandlers();
//            System.out.println("Wrote " + index());
        }
    }
}
