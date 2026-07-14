package se.bitcraze.crazyfliecontrol2;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * UDP text link used by the ESP32 firmware controlled by uav_udp_console.py.
 *
 * The ESP32 and Android device must be connected to the same LAN. The first
 * empty datagram lets the ESP32 learn the phone's IP/source port, then a
 * periodic empty datagram keeps that peer mapping alive.
 */
public class UavUdpLink {
    private static final String TAG = "UavUdpLink";
    private static final int RECEIVE_BUFFER_SIZE = 4096;
    private static final int RECEIVE_TIMEOUT_MS = 500;
    private static final int KEEPALIVE_INTERVAL_MS = 5000;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final byte[] STOP_SENDER = new byte[0];

    public interface Listener {
        void onConnected(String host, int port);
        void onDisconnected();
        void onMessage(String line);
        void onError(String message);
    }

    private final Listener mListener;
    private final Object mSendLock = new Object();
    private final BlockingQueue<byte[]> mSendQueue = new LinkedBlockingQueue<>();

    private volatile boolean mRunning;
    private volatile boolean mCancelled;
    private volatile boolean mDisconnecting;
    private volatile DatagramSocket mSocket;
    private volatile InetAddress mDeviceAddress;
    private volatile int mDevicePort;
    private Thread mReceiveThread;
    private Thread mKeepaliveThread;
    private Thread mSendThread;

    public UavUdpLink(Listener listener) {
        mListener = listener;
    }

    public synchronized void connect(String host, int port) throws IOException {
        if (mCancelled) {
            throw new IOException("Connection cancelled");
        }
        if (mRunning) {
            throw new IllegalStateException("UDP link is already connected");
        }
        if (host == null || host.trim().length() == 0) {
            throw new IllegalArgumentException("ESP32 IP/host is empty");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("UDP port must be between 1 and 65535");
        }

        mDeviceAddress = InetAddress.getByName(host.trim());
        if (mCancelled) {
            throw new IOException("Connection cancelled");
        }
        mDevicePort = port;
        mSocket = new DatagramSocket();
        if (mCancelled) {
            mSocket.close();
            mSocket = null;
            throw new IOException("Connection cancelled");
        }
        mSocket.setSoTimeout(RECEIVE_TIMEOUT_MS);
        mRunning = true;

        mSendThread = new Thread(new Runnable() {
            @Override
            public void run() {
                sendLoop();
            }
        }, "uav-udp-send");
        mSendThread.start();

        mReceiveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "uav-udp-receive");
        mReceiveThread.start();

        mKeepaliveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                keepaliveLoop();
            }
        }, "uav-udp-keepalive");
        mKeepaliveThread.start();

        // Same initial packet as UavUdpConsole.start(): allocate a local source
        // port and allow the ESP32 firmware to learn the phone as its peer.
        enqueue(new byte[]{'\n'});
        if (mListener != null) {
            mListener.onConnected(mDeviceAddress.getHostAddress(), mDevicePort);
        }
    }

    public boolean isConnected() {
        DatagramSocket socket = mSocket;
        return mRunning && socket != null && !socket.isClosed();
    }

    public void sendCommand(String command) {
        if (command == null || !isConnected()) {
            return;
        }
        enqueue((command + "\n").getBytes(UTF_8));
    }

    /**
     * Stop the link without doing network I/O on the caller (usually UI)
     * thread. As in the Python console, disconnect does not send a command.
     */
    public synchronized void disconnect() {
        if (mDisconnecting) {
            return;
        }
        mDisconnecting = true;
        mCancelled = true;
        final boolean notify = mRunning || mSocket != null;

        mRunning = false;

        if (mKeepaliveThread != null) {
            mKeepaliveThread.interrupt();
        }
        if (mSendThread != null) {
            mSendQueue.offer(STOP_SENDER);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                finishDisconnect(notify);
            }
        }, "uav-udp-disconnect").start();
    }

    private void enqueue(byte[] data) {
        mSendQueue.offer(data);
    }

    private void sendLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                byte[] data = mSendQueue.take();
                if (data == STOP_SENDER) {
                    break;
                }
                sendRaw(data);
            } catch (InterruptedException e) {
                break;
            }
        }
        Log.d(TAG, "Send thread stopped");
    }

    private void finishDisconnect(boolean notify) {
        Thread sender = mSendThread;
        if (sender != null && sender != Thread.currentThread()) {
            try {
                sender.join(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        DatagramSocket socket = mSocket;
        mSocket = null;
        if (socket != null) {
            socket.close();
        }
        if (mReceiveThread != null) {
            mReceiveThread.interrupt();
        }

        mReceiveThread = null;
        mKeepaliveThread = null;
        mSendThread = null;
        mSendQueue.clear();

        if (notify && mListener != null) {
            mListener.onDisconnected();
        }
    }

    private void sendRaw(byte[] data) {
        DatagramSocket socket = mSocket;
        InetAddress address = mDeviceAddress;
        if (socket == null || socket.isClosed() || address == null) {
            return;
        }
        try {
            synchronized (mSendLock) {
                socket.send(new DatagramPacket(data, data.length, address, mDevicePort));
            }
        } catch (IOException e) {
            if (mRunning) {
                notifyError("UDP send failed: " + e.getMessage());
            }
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (mRunning) {
            DatagramSocket socket = mSocket;
            if (socket == null || socket.isClosed()) {
                break;
            }
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), UTF_8);
                String[] lines = text.split("\\r?\\n");
                if (lines.length == 0 && text.length() > 0) {
                    notifyMessage(text);
                } else {
                    for (String line : lines) {
                        if (line.length() > 0) {
                            notifyMessage(line);
                        }
                    }
                }
            } catch (SocketTimeoutException ignored) {
                // Wake periodically so disconnect can stop this thread quickly.
            } catch (SocketException e) {
                if (mRunning) {
                    notifyError("UDP socket failed: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (mRunning) {
                    notifyError("UDP receive failed: " + e.getMessage());
                }
                break;
            }
        }
        Log.d(TAG, "Receive thread stopped");
    }

    private void keepaliveLoop() {
        while (mRunning && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(KEEPALIVE_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
            if (mRunning) {
                enqueue(new byte[]{'\n'});
            }
        }
        Log.d(TAG, "Keepalive thread stopped");
    }

    private void notifyMessage(String line) {
        if (mListener != null) {
            mListener.onMessage(line);
        }
    }

    private void notifyError(String message) {
        Log.e(TAG, message);
        if (mListener != null) {
            mListener.onError(message);
        }
    }
}
