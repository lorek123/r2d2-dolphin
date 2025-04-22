package com.koushikdutta.async.wrapper;

import com.koushikdutta.async.AsyncSocket;

/* loaded from: classes.dex */
public interface AsyncSocketWrapper extends AsyncSocket, DataEmitterWrapper {
    AsyncSocket getSocket();
}
