package com.kseek.camjpeg.net.http;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Date;

public final class MJpegHttpStreamer
{
    private static final String TAG = MJpegHttpStreamer.class.getSimpleName();

    /** Default port for HTTP. */
    public final static int DEFAULT_HTTP_PORT = 8080;

    /** Default port for HTTPS. */
    public final static int DEFAULT_HTTPS_PORT = 8443;

    private final static String UUID_MJPG = "7b3cc56e5f51db803f790dad720ed50a";
    private final static String BOUNDARY_MJPG = "--" + UUID_MJPG;


    private final static String MINE_MJPG =
            "multipart/x-mixed-replace;boundary=" + UUID_MJPG;
    private final static String CACHE_CONTROL =
            "Cache-Control: no-store"
                    + ", no-cache, must-revalidate"
                    + ", pre-check=0, post-check=0, max-age=0";

    private final static byte EOL[] = {(byte) '\r', (byte) '\n'};

    /* private final static String BOUNDARY_LINES = "\r\n" + BOUNDARY_MJPG + "\r\n";
    private static final String HTTP_HEADER =
        "HTTP/1.0 200 OK\r\n"
        + "Server: Camtest\r\n"
        + "Connection: close\r\n"
        + "Max-Age: 0\r\n"
        + "Expires: 0\r\n"
        + "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, "
            + "post-check=0, max-age=0\r\n"
        + "Pragma: no-cache\r\n"
        + "Access-Control-Allow-Origin:*\r\n"
        + "Content-Type: multipart/x-mixed-replace; "
            + "boundary=" + BOUNDARY_MJPG + "\r\n"
        + BOUNDARY_LINES;*/

    protected final static int httpPort = DEFAULT_HTTP_PORT;
    protected static boolean httpEnabled = true;

    private boolean newJpeg = false;
    private boolean streamingBufferA = true;

    private final byte[] bufferA;
    private final byte[] bufferB;
    private int lengthA = Integer.MIN_VALUE;
    private int lengthB = Integer.MIN_VALUE;
    private long timestampA = Long.MIN_VALUE;
    private long timestampB = Long.MIN_VALUE;
    private final Object bufferLock = new Object();

    private Thread worker = null;
    private volatile boolean running = false;

    public MJpegHttpStreamer(final int port, final int bufferSize)
    {
        super();

        bufferA = new byte[bufferSize];
        bufferB = new byte[bufferSize];

        // HTTP is used by default for now
        httpEnabled = true;

    }

    /** Returns the port used by the HTTP server. */
    public static int getHttpPort() { return httpPort; }

    /** Indicates whether or not the HTTP server is enabled. */
    public static boolean isHttpEnabled() {
        return httpEnabled;
    }

    /** Starts (or restart if needed) the HTTP server. */
    public void start()
    {
        if (running) {
            throw new IllegalStateException("MJpegHttpStreamer is already running");
        }

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                workerRun();
            }
        });
        worker.start();
        running = true;
    }

    public void stop()
    {
        if (!running) {
            throw new IllegalStateException("MJpegHttpStreamer is already stopped");
        }

        running = false;
        worker.interrupt();
    }

    public void streamJpeg(final byte[] jpeg, final int length, final long timestamp)
    {
        synchronized (bufferLock) {
            final byte[] buffer;

            if (streamingBufferA) {
                buffer = bufferB;
                lengthB = length;
                timestampB = timestamp;
            }
            else {
                buffer = bufferA;
                lengthA = length;
                timestampA = timestamp;
            }

            System.arraycopy(jpeg, 0 /* srcPos */, buffer, 0 /* dstPos */, length);
            newJpeg = true;
            bufferLock.notify();
        }
    }

    private void workerRun()
    {
        while (running) {
            ServerSocket serverSocket = null;
            Socket socket = null;
            DataOutputStream stream = null;

            try {
                serverSocket = new ServerSocket(httpPort);
                serverSocket.setSoTimeout(1000);  /* in milliseconds */

                do {
                    try {
                        socket = serverSocket.accept();
                    }
                    catch (final SocketTimeoutException e) {
                        if (!running) return;
                    }
                } while (socket == null);

                serverSocket.close();
                serverSocket = null;
                stream = new DataOutputStream(socket.getOutputStream());
                headerMJPG(stream);
                stream.flush();

                while (running) {
                    final byte[] buffer;
                    final int length;
                    final long timestamp;

                    synchronized (bufferLock) {
                        while (!newJpeg) {
                            try {
                                bufferLock.wait();
                            }
                            catch (final InterruptedException stopMayHaveBeenCalled) {
                                return;
                            }
                        }

                        streamingBufferA = !streamingBufferA;

                        if (streamingBufferA) {
                            buffer = bufferA;
                            length = lengthA;
                            timestamp = timestampA;
                        }
                        else {
                            buffer = bufferB;
                            length = lengthB;
                            timestamp = timestampB;
                        }

                        newJpeg = false;
                    }

                    stream.writeBytes(
                            "Content-type: image/jpeg\r\n"
                                    + "Content-Length: " + length + "\r\n"
                                    + "X-Timestamp:" + timestamp + "\r\n"
                                    + "\r\n"
                    );
                    stream.write(buffer, 0, length);  /* the 0 is the offset */
                    stream.writeBytes(BOUNDARY_LINES);
                    stream.flush();
                }
            }
            catch (final IOException exceptionWhileStreaming) {
                System.err.println(exceptionWhileStreaming);
            }
            finally {
                if (stream != null) {
                    try {
                        stream.close();
                    }
                    catch (final IOException closingStream) {
                        System.err.println(closingStream);
                    }
                }
                if (socket != null) {
                    try {
                        socket.close();
                    }
                    catch (final IOException closingSocket) {
                        System.err.println(closingSocket);
                    }
                }
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    }
                    catch (final IOException closingServerSocket) {
                        System.err.println(closingServerSocket);
                    }
                }
            }
        }
    }

    protected void headerMJPG(DataOutputStream ps) throws IOException
    {
        ps.writeBytes("Connection: Close");
        ps.write(EOL);

        ps.writeBytes("Server: Camtest");
        ps.write(EOL);

        ps.writeBytes(CACHE_CONTROL);
        ps.write(EOL);

        ps.writeBytes("Pragma: no-cache");
        ps.write(EOL);

        String dateString = new Date().toString();

        ps.writeBytes("Date: " + dateString);
        ps.write(EOL);

        ps.writeBytes("Last Modified: " + dateString);
        ps.write(EOL);

        ps.writeBytes("Content-type: " + MINE_MJPG);
        ps.write(EOL);
    }

    protected void headerJPG(DataOutputStream ps) throws IOException
    {
        ps.write(EOL);
        ps.writeBytes(BOUNDARY_MJPG);
        ps.write(EOL);
        ps.writeBytes("Content-type: image/jpeg");
        ps.write(EOL);
        ps.write(EOL);

    }

    private void acceptAndStream() throws IOException
    {



    }

}

