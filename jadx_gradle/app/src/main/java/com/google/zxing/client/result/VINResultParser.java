package com.google.zxing.client.result;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import java.util.regex.Pattern;
import org.opencv.features2d.FeatureDetector;

/* loaded from: classes.dex */
public final class VINResultParser extends ResultParser {
    private static final Pattern IOQ = Pattern.compile("[IOQ]");
    private static final Pattern AZ09 = Pattern.compile("[A-Z0-9]{17}");

    @Override // com.google.zxing.client.result.ResultParser
    public VINParsedResult parse(Result result) {
        if (result.getBarcodeFormat() != BarcodeFormat.CODE_39) {
            return null;
        }
        String rawText = IOQ.matcher(result.getText()).replaceAll("").trim();
        if (!AZ09.matcher(rawText).matches()) {
            return null;
        }
        try {
            if (!checkChecksum(rawText)) {
                return null;
            }
            String wmi = rawText.substring(0, 3);
            return new VINParsedResult(rawText, wmi, rawText.substring(3, 9), rawText.substring(9, 17), countryCode(wmi), rawText.substring(3, 8), modelYear(rawText.charAt(9)), rawText.charAt(10), rawText.substring(11));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean checkChecksum(CharSequence vin) {
        int sum = 0;
        for (int i = 0; i < vin.length(); i++) {
            sum += vinPositionWeight(i + 1) * vinCharValue(vin.charAt(i));
        }
        char checkChar = vin.charAt(8);
        char expectedCheckChar = checkChar(sum % 11);
        return checkChar == expectedCheckChar;
    }

    private static int vinCharValue(char c) {
        if (c >= 'A' && c <= 'I') {
            return (c - 'A') + 1;
        }
        if (c >= 'J' && c <= 'R') {
            return (c - 'J') + 1;
        }
        if (c >= 'S' && c <= 'Z') {
            return (c - 'S') + 2;
        }
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        throw new IllegalArgumentException();
    }

    private static int vinPositionWeight(int position) {
        if (position >= 1 && position <= 7) {
            return 9 - position;
        }
        if (position == 8) {
            return 10;
        }
        if (position == 9) {
            return 0;
        }
        if (position >= 10 && position <= 17) {
            return 19 - position;
        }
        throw new IllegalArgumentException();
    }

    private static char checkChar(int remainder) {
        if (remainder < 10) {
            return (char) (remainder + 48);
        }
        if (remainder == 10) {
            return 'X';
        }
        throw new IllegalArgumentException();
    }

    private static int modelYear(char c) {
        if (c >= 'E' && c <= 'H') {
            return (c - 'E') + 1984;
        }
        if (c >= 'J' && c <= 'N') {
            return (c - 'J') + 1988;
        }
        if (c == 'P') {
            return 1993;
        }
        if (c >= 'R' && c <= 'T') {
            return (c - 'R') + 1994;
        }
        if (c >= 'V' && c <= 'Y') {
            return (c - 'V') + 1997;
        }
        if (c >= '1' && c <= '9') {
            return (c - '1') + FeatureDetector.PYRAMID_FAST;
        }
        if (c >= 'A' && c <= 'D') {
            return (c - 'A') + FeatureDetector.PYRAMID_DENSE;
        }
        throw new IllegalArgumentException();
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Removed duplicated region for block: B:3:0x0017 A[ORIG_RETURN, RETURN] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private static java.lang.String countryCode(java.lang.CharSequence r8) {
        /*
            r7 = 69
            r6 = 57
            r5 = 51
            r4 = 82
            r3 = 65
            r2 = 0
            char r0 = r8.charAt(r2)
            r2 = 1
            char r1 = r8.charAt(r2)
            switch(r0) {
                case 49: goto L19;
                case 50: goto L1c;
                case 51: goto L1f;
                case 52: goto L19;
                case 53: goto L19;
                case 57: goto L28;
                case 74: goto L33;
                case 75: goto L3c;
                case 76: goto L45;
                case 77: goto L48;
                case 83: goto L4f;
                case 86: goto L63;
                case 87: goto L77;
                case 88: goto L7a;
                case 90: goto L85;
                default: goto L17;
            }
        L17:
            r2 = 0
        L18:
            return r2
        L19:
            java.lang.String r2 = "US"
            goto L18
        L1c:
            java.lang.String r2 = "CA"
            goto L18
        L1f:
            if (r1 < r3) goto L17
            r2 = 87
            if (r1 > r2) goto L17
            java.lang.String r2 = "MX"
            goto L18
        L28:
            if (r1 < r3) goto L2c
            if (r1 <= r7) goto L30
        L2c:
            if (r1 < r5) goto L17
            if (r1 > r6) goto L17
        L30:
            java.lang.String r2 = "BR"
            goto L18
        L33:
            if (r1 < r3) goto L17
            r2 = 84
            if (r1 > r2) goto L17
            java.lang.String r2 = "JP"
            goto L18
        L3c:
            r2 = 76
            if (r1 < r2) goto L17
            if (r1 > r4) goto L17
            java.lang.String r2 = "KO"
            goto L18
        L45:
            java.lang.String r2 = "CN"
            goto L18
        L48:
            if (r1 < r3) goto L17
            if (r1 > r7) goto L17
            java.lang.String r2 = "IN"
            goto L18
        L4f:
            if (r1 < r3) goto L58
            r2 = 77
            if (r1 > r2) goto L58
            java.lang.String r2 = "UK"
            goto L18
        L58:
            r2 = 78
            if (r1 < r2) goto L17
            r2 = 84
            if (r1 > r2) goto L17
            java.lang.String r2 = "DE"
            goto L18
        L63:
            r2 = 70
            if (r1 < r2) goto L6c
            if (r1 > r4) goto L6c
            java.lang.String r2 = "FR"
            goto L18
        L6c:
            r2 = 83
            if (r1 < r2) goto L17
            r2 = 87
            if (r1 > r2) goto L17
            java.lang.String r2 = "ES"
            goto L18
        L77:
            java.lang.String r2 = "DE"
            goto L18
        L7a:
            r2 = 48
            if (r1 == r2) goto L82
            if (r1 < r5) goto L17
            if (r1 > r6) goto L17
        L82:
            java.lang.String r2 = "RU"
            goto L18
        L85:
            if (r1 < r3) goto L17
            if (r1 > r4) goto L17
            java.lang.String r2 = "IT"
            goto L18
        */
        throw new UnsupportedOperationException("Method not decompiled: com.google.zxing.client.result.VINResultParser.countryCode(java.lang.CharSequence):java.lang.String");
    }
}
