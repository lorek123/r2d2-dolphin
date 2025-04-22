package com.bullb.r2d2_nanopisystem.RobotApi.Request;

import com.google.gson.annotations.SerializedName;

/* loaded from: classes.dex */
public class WIFIQRCode extends BaseRequest {

    @SerializedName("key")
    private String key;

    /* renamed from: pw */
    @SerializedName("pw")
    private String f47pw;

    @SerializedName("ssid")
    private String ssid;

    public String getSSID() {
        return this.ssid;
    }

    public String getPw() {
        return this.f47pw;
    }

    public String getKey() {
        return this.key;
    }
}
