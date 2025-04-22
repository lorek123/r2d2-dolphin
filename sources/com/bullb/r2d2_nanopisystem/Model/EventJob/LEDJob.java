package com.bullb.r2d2_nanopisystem.Model.EventJob;

import com.bullb.r2d2_nanopisystem.Commander;

/* loaded from: classes.dex */
public class LEDJob extends EventJob {
    public static final int MODE_FF1 = 5;
    public static final int MODE_FF2 = 6;
    public static final int MODE_NONE = 0;
    public static final int MODE_OFF = 1;
    public static final int MODE_ON = 2;
    public static final int MODE_SF1 = 3;
    public static final int MODE_SF2 = 4;

    /* renamed from: b */
    private int f41b;

    /* renamed from: g */
    private int f42g;

    /* renamed from: r */
    private int f43r;

    /* renamed from: y */
    private int f44y;

    public LEDJob(int r, int b, int y, int g, int delay) {
        super(Commander.LED, delay);
        this.f43r = r;
        this.f41b = b;
        this.f44y = y;
        this.f42g = g;
    }

    public int getR() {
        return this.f43r;
    }

    public int getB() {
        return this.f41b;
    }

    public int getY() {
        return this.f44y;
    }

    public int getG() {
        return this.f42g;
    }
}
