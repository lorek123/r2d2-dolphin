package org.java_websocket.framing;

import org.java_websocket.framing.Framedata;

/* loaded from: classes.dex */
public class ContinuousFrame extends DataFrame {
    public ContinuousFrame() {
        super(Framedata.Opcode.CONTINUOUS);
    }
}
