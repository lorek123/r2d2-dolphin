package com.koushikdutta.async.future;

/* loaded from: classes.dex */
public interface Cancellable {
    boolean cancel();

    boolean isCancelled();

    boolean isDone();
}
