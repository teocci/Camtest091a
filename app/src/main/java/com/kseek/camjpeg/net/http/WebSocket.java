package com.kseek.camjpeg.net.http;

import com.kseek.camjpeg.net.http.NanoWSD.State;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.CharacterCodingException;
import java.util.LinkedList;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;

import static com.kseek.camjpeg.utils.Utilities.LogE;

/**
 * Created by teocci on 4/29/16.
 */
public abstract class WebSocket {

    private final InputStream in;

    private OutputStream out;

    private WebSocketFrame.OpCode continuousOpCode = null;

    private final List<WebSocketFrame> continuousFrames = new LinkedList<WebSocketFrame>();

    private State state = State.UNCONNECTED;

    private final NanoHTTPD.IHTTPSession handshakeRequest;

    private final NanoHTTPD.Response handshakeResponse = new NanoHTTPD.Response(NanoHTTPD.Response.Status.SWITCH_PROTOCOL, null, (InputStream) null, 0) {

        @Override
        protected void send(OutputStream out) {
            WebSocket.this.out = out;
            WebSocket.this.state = State.CONNECTING;
            super.send(out);
            WebSocket.this.state = State.OPEN;
            WebSocket.this.onOpen();
            readWebsocket();
        }
    };

    public WebSocket(NanoHTTPD.IHTTPSession handshakeRequest) {
        this.handshakeRequest = handshakeRequest;
        this.in = handshakeRequest.getInputStream();

        this.handshakeResponse.addHeader(NanoWSD.HEADER_UPGRADE, NanoWSD.HEADER_UPGRADE_VALUE);
        this.handshakeResponse.addHeader(NanoWSD.HEADER_CONNECTION, NanoWSD.HEADER_CONNECTION_VALUE);
    }

    public boolean isOpen() {
        return state == State.OPEN;
    }

    protected abstract void onOpen();

    protected abstract void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote);

    protected abstract void onMessage(WebSocketFrame message);

    protected abstract void onPong(WebSocketFrame pong);

    protected abstract void onException(IOException exception);

    /**
     * Debug method. <b>Do not Override unless for debug purposes!</b>
     *
     * @param frame
     *            The received WebSocket Frame.
     */
    protected void debugFrameReceived(WebSocketFrame frame) {
    }

    /**
     * Debug method. <b>Do not Override unless for debug purposes!</b><br>
     * This method is called before actually sending the frame.
     *
     * @param frame
     *            The sent WebSocket Frame.
     */
    protected void debugFrameSent(WebSocketFrame frame) {
    }

    public void close(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) throws IOException {
        State oldState = this.state;
        this.state = State.CLOSING;
        if (oldState == State.OPEN) {
            sendFrame(new CloseFrame(code, reason));
        } else {
            doClose(code, reason, initiatedByRemote);
        }
    }

    private void doClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
        if (this.state == State.CLOSED) {
            return;
        }
        if (this.in != null) {
            try {
                this.in.close();
            } catch (IOException e) {
                LogE("close failed");
            }
        }
        if (this.out != null) {
            try {
                this.out.close();
            } catch (IOException e) {
                LogE("close failed");
            }
        }
        this.state = State.CLOSED;
        onClose(code, reason, initiatedByRemote);
    }

    // --------------------------------IO--------------------------------------

    public NanoHTTPD.IHTTPSession getHandshakeRequest() {
        return this.handshakeRequest;
    }

    public NanoHTTPD.Response getHandshakeResponse() {
        return this.handshakeResponse;
    }

    private void handleCloseFrame(WebSocketFrame frame) throws IOException {
        WebSocketFrame.CloseCode code = WebSocketFrame.CloseCode.NormalClosure;
        String reason = "";
        if (frame instanceof CloseFrame) {
            code = ((CloseFrame) frame).getCloseCode();
            reason = ((CloseFrame) frame).getCloseReason();
        }
        if (this.state == State.CLOSING) {
            // Answer for my requested close
            doClose(code, reason, false);
        } else {
            close(code, reason, true);
        }
    }

    private void handleFrameFragment(WebSocketFrame frame) throws IOException {
        if (frame.getOpCode() != WebSocketFrame.OpCode.Continuation) {
            // First
            if (this.continuousOpCode != null) {
                throw new WebSocketException(WebSocketFrame.CloseCode.ProtocolError, "Previous continuous frame sequence not completed.");
            }
            this.continuousOpCode = frame.getOpCode();
            this.continuousFrames.clear();
            this.continuousFrames.add(frame);
        } else if (frame.isFin()) {
            // Last
            if (this.continuousOpCode == null) {
                throw new WebSocketException(WebSocketFrame.CloseCode.ProtocolError, "Continuous frame sequence was not started.");
            }
            this.continuousFrames.add(frame);
            onMessage(new WebSocketFrame(this.continuousOpCode, this.continuousFrames));
            this.continuousOpCode = null;
            this.continuousFrames.clear();
        } else if (this.continuousOpCode == null) {
            // Unexpected
            throw new WebSocketException(WebSocketFrame.CloseCode.ProtocolError, "Continuous frame sequence was not started.");
        } else {
            // Intermediate
            this.continuousFrames.add(frame);
        }
    }

    private void handleWebsocketFrame(WebSocketFrame frame) throws IOException {
        debugFrameReceived(frame);
        if (frame.getOpCode() == WebSocketFrame.OpCode.Close) {
            handleCloseFrame(frame);
        } else if (frame.getOpCode() == WebSocketFrame.OpCode.Ping) {
            sendFrame(new WebSocketFrame(WebSocketFrame.OpCode.Pong, true, frame.getBinaryPayload()));
        } else if (frame.getOpCode() == WebSocketFrame.OpCode.Pong) {
            onPong(frame);
        } else if (!frame.isFin() || frame.getOpCode() == WebSocketFrame.OpCode.Continuation) {
            handleFrameFragment(frame);
        } else if (this.continuousOpCode != null) {
            throw new WebSocketException(WebSocketFrame.CloseCode.ProtocolError, "Continuous frame sequence not completed.");
        } else if (frame.getOpCode() == WebSocketFrame.OpCode.Text || frame.getOpCode() ==
                WebSocketFrame.OpCode.Binary) {
            onMessage(frame);
        } else {
            throw new WebSocketException(WebSocketFrame.CloseCode.ProtocolError, "Non control or continuous frame expected.");
        }
    }

    // --------------------------------Close-----------------------------------

    public void ping(byte[] payload) throws IOException {
        sendFrame(new WebSocketFrame(WebSocketFrame.OpCode.Ping, true, payload));
    }

    // --------------------------------Public
    // Facade---------------------------

    private void readWebsocket() {
        try {
            while (this.state == State.OPEN) {
                handleWebsocketFrame(WebSocketFrame.read(this.in));
            }
        } catch (CharacterCodingException e) {
            onException(e);
            doClose(WebSocketFrame.CloseCode.InvalidFramePayloadData, e.toString(), false);
        } catch (IOException e) {
            onException(e);
            if (e instanceof WebSocketException) {
                doClose(((WebSocketException) e).getCode(), ((WebSocketException) e).getReason(), false);
            }
        } finally {
            doClose(WebSocketFrame.CloseCode.InternalServerError, "Handler terminated without closing the connection.", false);
        }
    }

    public void send(byte[] payload) throws IOException {
        sendFrame(new WebSocketFrame(WebSocketFrame.OpCode.Binary, true, payload));
    }

    public void send(String payload) throws IOException {
        sendFrame(new WebSocketFrame(WebSocketFrame.OpCode.Text, true, payload));
    }

    public synchronized void sendFrame(WebSocketFrame frame) throws IOException {
        debugFrameSent(frame);
        frame.write(this.out);
    }
}

