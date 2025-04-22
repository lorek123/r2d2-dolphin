package com.koushikdutta.async.http.socketio;

import org.json.JSONObject;

/* loaded from: classes.dex */
public interface JSONCallback {
    void onJSON(JSONObject jSONObject, Acknowledge acknowledge);
}
