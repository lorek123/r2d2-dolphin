package com.bullb.r2d2_nanopisystem.Model.EventJob;

import com.bullb.r2d2_nanopisystem.Commander;

/* loaded from: classes.dex */
public class HandJob extends EventJob {
    private int power;

    public HandJob(int power, int delay) {
        super(Commander.ARM, delay);
        this.power = power;
    }

    public int getPower() {
        return this.power;
    }
}
