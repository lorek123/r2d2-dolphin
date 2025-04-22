package com.bullb.r2d2_nanopisystem.Model;

import com.bullb.r2d2_nanopisystem.Commander;
import com.bullb.r2d2_nanopisystem.RobotApi.RobotApiHandler;
import com.google.gson.annotations.SerializedName;

/* loaded from: classes.dex */
public class Command {

    @SerializedName("angle")
    public int angle;

    @SerializedName("cmd")
    public String cmd;

    @SerializedName("dir")
    public int dir;

    @SerializedName("interrupt")
    public int interrupt;

    @SerializedName(Commander.MODE)
    public int mode;

    @SerializedName(RobotApiHandler.POWER)
    public int power;

    @SerializedName("sound_id")
    public int sound_id;

    @SerializedName("url")
    public String url;

    @SerializedName("value")
    public int value;

    /* renamed from: r */
    @SerializedName("r")
    public int f36r = -1;

    /* renamed from: b */
    @SerializedName("b")
    public int f33b = -1;

    /* renamed from: y */
    @SerializedName("y")
    public int f38y = -1;

    /* renamed from: g */
    @SerializedName("g")
    public int f34g = -1;

    /* renamed from: s */
    @SerializedName("s")
    public int f37s = -1;

    /* renamed from: l */
    @SerializedName("l")
    public int f35l = -1;
}
