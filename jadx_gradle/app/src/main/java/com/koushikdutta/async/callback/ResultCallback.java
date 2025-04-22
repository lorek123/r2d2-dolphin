package com.koushikdutta.async.callback;

/* loaded from: classes.dex */
public interface ResultCallback<S, T> {
    void onCompleted(Exception exc, S s, T t);
}
