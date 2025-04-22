package com.koushikdutta.async;

/* loaded from: classes.dex */
public class AsyncSSLException extends Exception {
    private boolean mIgnore;

    public AsyncSSLException(Throwable cause) {
        super("Peer not trusted by any of the system trust managers.", cause);
        this.mIgnore = false;
    }

    public void setIgnore(boolean ignore) {
        this.mIgnore = ignore;
    }

    public boolean getIgnore() {
        return this.mIgnore;
    }
}
