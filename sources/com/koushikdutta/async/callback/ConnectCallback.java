package com.koushikdutta.async.callback;

import com.koushikdutta.async.AsyncSocket;

/* loaded from: classes.dex */
public interface ConnectCallback {
    void onConnectCompleted(Exception exc, AsyncSocket asyncSocket);
}
