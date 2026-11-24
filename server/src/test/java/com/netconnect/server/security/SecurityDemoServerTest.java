package com.netconnect.server.security;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityDemoServerTest {
    @Test
    void shutdownClosesActiveSocketsAndTerminatesWorkers() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        Set<SSLSocket> active = ConcurrentHashMap.newKeySet();
        try (var workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
             SSLServerSocket listener = (SSLServerSocket) SSLServerSocketFactory.getDefault()
                     .createServerSocket(0, 1, loopback);
             Socket client = new Socket()) {
            client.connect(new InetSocketAddress(loopback, listener.getLocalPort()));
            SSLSocket serverSocket = (SSLSocket) listener.accept();
            active.add(serverSocket);
            CountDownLatch handshakeStarted = new CountDownLatch(1);
            Future<?> handshake = workers.submit(() -> {
                handshakeStarted.countDown();
                try { serverSocket.startHandshake(); }
                catch (IOException expected) { }
            });
            try {
                assertTrue(handshakeStarted.await(2, TimeUnit.SECONDS));
            } finally {
                SecurityDemoServer.shutdownServer(listener, active, workers);
            }
            assertTrue(listener.isClosed());
            assertTrue(serverSocket.isClosed());
            assertTrue(workers.isTerminated());
            handshake.get(2, TimeUnit.SECONDS);
        }
    }
}
