package com.bullb.r2d2_nanopisystem.SerialPort;

import android.util.Log;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Scanner;

/* loaded from: classes.dex */
public class SerialPortOrangePi implements Closeable {
    public static final int STA_CLOSED = 2;
    public static final int STA_OPENED = 1;
    public static final int STA_UNKNOWN = 0;
    private static final String TAG = "SerialPortOrangePi";

    /* renamed from: fd */
    private FileDescriptor f49fd;

    /* renamed from: in */
    private InputStream f50in;
    private OutputStream out;
    private int state;

    private native void close1() throws IOException;

    private native FileDescriptor open(String str, int i);

    static {
        System.loadLibrary("r2d2");
    }

    public SerialPortOrangePi(File device, int baudRate) throws SecurityException, IOException {
        this.state = 0;
        if (!device.canRead() || !device.canWrite()) {
            try {
                Process su = Runtime.getRuntime().exec("su");
                String cmd = "chmod 666 " + device.getAbsolutePath() + "\nexit\n";
                su.getOutputStream().write(cmd.getBytes());
                if (su.waitFor() != 0 || !device.canRead() || !device.canWrite()) {
                    Scanner sc = new Scanner(su.getErrorStream());
                    throw new SecurityException(sc.nextLine());
                }
            } catch (Exception ex) {
                throw new SecurityException(ex);
            }
        }
        Log.d(TAG, "open");
        this.f49fd = open(device.getCanonicalPath(), baudRate);
        Log.d(TAG, "opened");
        if (this.f49fd == null) {
            Log.e(TAG, "native open return null.");
            throw new IOException("native open return null.");
        }
        this.f50in = new FileInputStream(this.f49fd);
        this.out = new FileOutputStream(this.f49fd);
        this.state = 1;
    }

    public InputStream getInputStream() {
        return this.f50in;
    }

    public OutputStream getOutputStream() {
        return this.out;
    }

    public boolean isClosed() {
        return this.state == 2;
    }

    @Override // java.io.Closeable, java.lang.AutoCloseable
    public void close() throws IOException {
        if (this.f50in != null) {
            try {
                this.f50in.close();
            } catch (IOException e) {
            }
        }
        if (this.out != null) {
            try {
                this.out.close();
            } catch (IOException e2) {
            }
        }
        if (this.f49fd != null) {
            close1();
        }
        this.state = 2;
    }
}
