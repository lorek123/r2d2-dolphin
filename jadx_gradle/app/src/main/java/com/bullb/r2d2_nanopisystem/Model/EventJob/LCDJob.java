package com.bullb.r2d2_nanopisystem.Model.EventJob;

import com.bullb.r2d2_nanopisystem.Commander;

/* loaded from: classes.dex */
public class LCDJob extends EventJob {
    public static final int LCD_CLOSED = 1;
    public static final int LCD_OPEN = 2;

    /* renamed from: l */
    private int f39l;

    /* renamed from: s */
    private int f40s;

    public LCDJob(int s, int l, int delay) {
        super(Commander.LCD, delay);
        this.f40s = s;
        this.f39l = l;
    }

    public int getS() {
        return this.f40s;
    }

    public int getL() {
        return this.f39l;
    }
}
