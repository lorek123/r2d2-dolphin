package com.koushikdutta.async.callback;

import com.koushikdutta.async.AsyncServerSocket;
import com.koushikdutta.async.AsyncSocket;

/* loaded from: classes.dex */
public interface ListenCallback extends CompletedCallback {
    void onAccepted(AsyncSocket asyncSocket);

    void onListening(AsyncServerSocket asyncServerSocket);
}
