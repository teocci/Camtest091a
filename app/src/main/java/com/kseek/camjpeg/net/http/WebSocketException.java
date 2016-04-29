package com.kseek.camjpeg.net.http;

import java.io.IOException;

/**
 * Created by teocci on 4/29/16.
 */
public class WebSocketException extends IOException
{

    private static final long serialVersionUID = 1L;

    private final WebSocketFrame.CloseCode code;

    private final String reason;

    public WebSocketException(WebSocketFrame.CloseCode code, String reason) {
        this(code, reason, null);
    }

    public WebSocketException(WebSocketFrame.CloseCode code, String reason, Exception cause) {
        super(code + ": " + reason, cause);
        this.code = code;
        this.reason = reason;
    }

    public WebSocketException(Exception cause) {
        this(WebSocketFrame.CloseCode.InternalServerError, cause.toString(), cause);
    }

    public WebSocketFrame.CloseCode getCode() {
        return this.code;
    }

    public String getReason() {
        return this.reason;
    }
}
