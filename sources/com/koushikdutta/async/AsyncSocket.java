package com.koushikdutta.async;

/* loaded from: classes.dex */
public interface AsyncSocket extends DataEmitter, DataSink {
    @Override // com.koushikdutta.async.DataEmitter, com.koushikdutta.async.DataSink
    AsyncServer getServer();
}
