package com.koushikdutta.async.future;

/* loaded from: classes.dex */
public interface DependentCancellable extends Cancellable {
    DependentCancellable setParent(Cancellable cancellable);
}
