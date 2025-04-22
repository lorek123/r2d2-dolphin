package com.google.zxing.pdf417.decoder;

import com.google.zxing.FormatException;
import com.google.zxing.common.CharacterSetECI;
import com.google.zxing.common.DecoderResult;
import com.google.zxing.pdf417.PDF417ResultMetadata;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.Arrays;

/* loaded from: classes.dex */
final class DecodedBitStreamParser {

    /* renamed from: AL */
    private static final int f67AL = 28;

    /* renamed from: AS */
    private static final int f68AS = 27;
    private static final int BEGIN_MACRO_PDF417_CONTROL_BLOCK = 928;
    private static final int BEGIN_MACRO_PDF417_OPTIONAL_FIELD = 923;
    private static final int BYTE_COMPACTION_MODE_LATCH = 901;
    private static final int BYTE_COMPACTION_MODE_LATCH_6 = 924;
    private static final int ECI_CHARSET = 927;
    private static final int ECI_GENERAL_PURPOSE = 926;
    private static final int ECI_USER_DEFINED = 925;

    /* renamed from: LL */
    private static final int f69LL = 27;
    private static final int MACRO_PDF417_TERMINATOR = 922;
    private static final int MAX_NUMERIC_CODEWORDS = 15;

    /* renamed from: ML */
    private static final int f70ML = 28;
    private static final int MODE_SHIFT_TO_BYTE_COMPACTION_MODE = 913;
    private static final int NUMBER_OF_SEQUENCE_CODEWORDS = 2;
    private static final int NUMERIC_COMPACTION_MODE_LATCH = 902;
    private static final int PAL = 29;

    /* renamed from: PL */
    private static final int f71PL = 25;

    /* renamed from: PS */
    private static final int f72PS = 29;
    private static final int TEXT_COMPACTION_MODE_LATCH = 900;
    private static final char[] PUNCT_CHARS = {';', '<', '>', '@', '[', '\\', ']', '_', '`', '~', '!', '\r', '\t', ',', ':', '\n', '-', '.', '$', '/', '\"', '|', '*', '(', ')', '?', '{', '}', '\''};
    private static final char[] MIXED_CHARS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '&', '\r', '\t', ',', ':', '#', '-', '.', '$', '/', '+', '%', '*', '=', '^'};
    private static final Charset DEFAULT_ENCODING = Charset.forName("ISO-8859-1");
    private static final BigInteger[] EXP900 = new BigInteger[16];

    private enum Mode {
        ALPHA,
        LOWER,
        MIXED,
        PUNCT,
        ALPHA_SHIFT,
        PUNCT_SHIFT
    }

    static {
        EXP900[0] = BigInteger.ONE;
        BigInteger nineHundred = BigInteger.valueOf(900L);
        EXP900[1] = nineHundred;
        for (int i = 2; i < EXP900.length; i++) {
            EXP900[i] = EXP900[i - 1].multiply(nineHundred);
        }
    }

    private DecodedBitStreamParser() {
    }

    static DecoderResult decode(int[] codewords, String ecLevel) throws FormatException {
        int codeIndex;
        StringBuilder result = new StringBuilder(codewords.length * 2);
        Charset encoding = DEFAULT_ENCODING;
        int codeIndex2 = 1 + 1;
        int code = codewords[1];
        PDF417ResultMetadata resultMetadata = new PDF417ResultMetadata();
        while (true) {
            int codeIndex3 = codeIndex2;
            if (codeIndex3 < codewords[0]) {
                switch (code) {
                    case 900:
                        codeIndex = textCompaction(codewords, codeIndex3, result);
                        break;
                    case BYTE_COMPACTION_MODE_LATCH /* 901 */:
                    case BYTE_COMPACTION_MODE_LATCH_6 /* 924 */:
                        codeIndex = byteCompaction(code, codewords, encoding, codeIndex3, result);
                        break;
                    case NUMERIC_COMPACTION_MODE_LATCH /* 902 */:
                        codeIndex = numericCompaction(codewords, codeIndex3, result);
                        break;
                    case MODE_SHIFT_TO_BYTE_COMPACTION_MODE /* 913 */:
                        result.append((char) codewords[codeIndex3]);
                        codeIndex = codeIndex3 + 1;
                        break;
                    case MACRO_PDF417_TERMINATOR /* 922 */:
                    case BEGIN_MACRO_PDF417_OPTIONAL_FIELD /* 923 */:
                        throw FormatException.getFormatInstance();
                    case ECI_USER_DEFINED /* 925 */:
                        codeIndex = codeIndex3 + 1;
                        break;
                    case ECI_GENERAL_PURPOSE /* 926 */:
                        codeIndex = codeIndex3 + 2;
                        break;
                    case ECI_CHARSET /* 927 */:
                        CharacterSetECI charsetECI = CharacterSetECI.getCharacterSetECIByValue(codewords[codeIndex3]);
                        encoding = Charset.forName(charsetECI.name());
                        codeIndex = codeIndex3 + 1;
                        break;
                    case 928:
                        codeIndex = decodeMacroBlock(codewords, codeIndex3, resultMetadata);
                        break;
                    default:
                        codeIndex = textCompaction(codewords, codeIndex3 - 1, result);
                        break;
                }
                if (codeIndex < codewords.length) {
                    codeIndex2 = codeIndex + 1;
                    code = codewords[codeIndex];
                } else {
                    throw FormatException.getFormatInstance();
                }
            } else {
                if (result.length() == 0) {
                    throw FormatException.getFormatInstance();
                }
                DecoderResult decoderResult = new DecoderResult(null, result.toString(), null, ecLevel);
                decoderResult.setOther(resultMetadata);
                return decoderResult;
            }
        }
    }

    private static int decodeMacroBlock(int[] codewords, int codeIndex, PDF417ResultMetadata resultMetadata) throws FormatException {
        if (codeIndex + 2 > codewords[0]) {
            throw FormatException.getFormatInstance();
        }
        int[] segmentIndexArray = new int[2];
        int i = 0;
        while (i < 2) {
            segmentIndexArray[i] = codewords[codeIndex];
            i++;
            codeIndex++;
        }
        resultMetadata.setSegmentIndex(Integer.parseInt(decodeBase900toBase10(segmentIndexArray, 2)));
        StringBuilder fileId = new StringBuilder();
        int codeIndex2 = textCompaction(codewords, codeIndex, fileId);
        resultMetadata.setFileId(fileId.toString());
        if (codewords[codeIndex2] == BEGIN_MACRO_PDF417_OPTIONAL_FIELD) {
            int codeIndex3 = codeIndex2 + 1;
            int[] additionalOptionCodeWords = new int[codewords[0] - codeIndex3];
            int additionalOptionCodeWordsIndex = 0;
            boolean end = false;
            while (codeIndex3 < codewords[0] && !end) {
                int codeIndex4 = codeIndex3 + 1;
                int code = codewords[codeIndex3];
                if (code < 900) {
                    additionalOptionCodeWords[additionalOptionCodeWordsIndex] = code;
                    additionalOptionCodeWordsIndex++;
                    codeIndex3 = codeIndex4;
                } else {
                    switch (code) {
                        case MACRO_PDF417_TERMINATOR /* 922 */:
                            resultMetadata.setLastSegment(true);
                            codeIndex3 = codeIndex4 + 1;
                            end = true;
                            break;
                        default:
                            throw FormatException.getFormatInstance();
                    }
                }
            }
            resultMetadata.setOptionalData(Arrays.copyOf(additionalOptionCodeWords, additionalOptionCodeWordsIndex));
            return codeIndex3;
        }
        if (codewords[codeIndex2] == MACRO_PDF417_TERMINATOR) {
            resultMetadata.setLastSegment(true);
            return codeIndex2 + 1;
        }
        return codeIndex2;
    }

    private static int textCompaction(int[] codewords, int codeIndex, StringBuilder result) {
        int[] textCompactionData = new int[(codewords[0] - codeIndex) * 2];
        int[] byteCompactionData = new int[(codewords[0] - codeIndex) * 2];
        int index = 0;
        boolean end = false;
        while (codeIndex < codewords[0] && !end) {
            int codeIndex2 = codeIndex + 1;
            int code = codewords[codeIndex];
            if (code < 900) {
                textCompactionData[index] = code / 30;
                textCompactionData[index + 1] = code % 30;
                index += 2;
                codeIndex = codeIndex2;
            } else {
                switch (code) {
                    case 900:
                        textCompactionData[index] = 900;
                        index++;
                        codeIndex = codeIndex2;
                        break;
                    case BYTE_COMPACTION_MODE_LATCH /* 901 */:
                    case NUMERIC_COMPACTION_MODE_LATCH /* 902 */:
                    case MACRO_PDF417_TERMINATOR /* 922 */:
                    case BEGIN_MACRO_PDF417_OPTIONAL_FIELD /* 923 */:
                    case BYTE_COMPACTION_MODE_LATCH_6 /* 924 */:
                    case 928:
                        codeIndex = codeIndex2 - 1;
                        end = true;
                        break;
                    case MODE_SHIFT_TO_BYTE_COMPACTION_MODE /* 913 */:
                        textCompactionData[index] = MODE_SHIFT_TO_BYTE_COMPACTION_MODE;
                        codeIndex = codeIndex2 + 1;
                        byteCompactionData[index] = codewords[codeIndex2];
                        index++;
                        break;
                    default:
                        codeIndex = codeIndex2;
                        break;
                }
            }
        }
        decodeTextCompaction(textCompactionData, byteCompactionData, index, result);
        return codeIndex;
    }

    private static void decodeTextCompaction(int[] textCompactionData, int[] byteCompactionData, int length, StringBuilder result) {
        Mode subMode = Mode.ALPHA;
        Mode priorToShiftMode = Mode.ALPHA;
        for (int i = 0; i < length; i++) {
            int subModeCh = textCompactionData[i];
            char ch = 0;
            switch (subMode) {
                case ALPHA:
                    if (subModeCh < 26) {
                        ch = (char) (subModeCh + 65);
                        break;
                    } else if (subModeCh == 26) {
                        ch = ' ';
                        break;
                    } else if (subModeCh == 27) {
                        subMode = Mode.LOWER;
                        break;
                    } else if (subModeCh == 28) {
                        subMode = Mode.MIXED;
                        break;
                    } else if (subModeCh == 29) {
                        priorToShiftMode = subMode;
                        subMode = Mode.PUNCT_SHIFT;
                        break;
                    } else if (subModeCh == MODE_SHIFT_TO_BYTE_COMPACTION_MODE) {
                        result.append((char) byteCompactionData[i]);
                        break;
                    } else if (subModeCh == 900) {
                        subMode = Mode.ALPHA;
                        break;
                    }
                    break;
                case LOWER:
                    if (subModeCh < 26) {
                        ch = (char) (subModeCh + 97);
                        break;
                    } else if (subModeCh == 26) {
                        ch = ' ';
                        break;
                    } else if (subModeCh == 27) {
                        priorToShiftMode = subMode;
                        subMode = Mode.ALPHA_SHIFT;
                        break;
                    } else if (subModeCh == 28) {
                        subMode = Mode.MIXED;
                        break;
                    } else if (subModeCh == 29) {
                        priorToShiftMode = subMode;
                        subMode = Mode.PUNCT_SHIFT;
                        break;
                    } else if (subModeCh == MODE_SHIFT_TO_BYTE_COMPACTION_MODE) {
                        result.append((char) byteCompactionData[i]);
                        break;
                    } else if (subModeCh == 900) {
                        subMode = Mode.ALPHA;
                        break;
                    }
                    break;
                case MIXED:
                    if (subModeCh < 25) {
                        ch = MIXED_CHARS[subModeCh];
                        break;
                    } else if (subModeCh == 25) {
                        subMode = Mode.PUNCT;
                        break;
                    } else if (subModeCh == 26) {
                        ch = ' ';
                        break;
                    } else if (subModeCh == 27) {
                        subMode = Mode.LOWER;
                        break;
                    } else if (subModeCh == 28) {
                        subMode = Mode.ALPHA;
                        break;
                    } else if (subModeCh == 29) {
                        priorToShiftMode = subMode;
                        subMode = Mode.PUNCT_SHIFT;
                        break;
                    } else if (subModeCh == MODE_SHIFT_TO_BYTE_COMPACTION_MODE) {
                        result.append((char) byteCompactionData[i]);
                        break;
                    } else if (subModeCh == 900) {
                        subMode = Mode.ALPHA;
                        break;
                    }
                    break;
                case PUNCT:
                    if (subModeCh < 29) {
                        ch = PUNCT_CHARS[subModeCh];
                        break;
                    } else if (subModeCh == 29) {
                        subMode = Mode.ALPHA;
                        break;
                    } else if (subModeCh == MODE_SHIFT_TO_BYTE_COMPACTION_MODE) {
                        result.append((char) byteCompactionData[i]);
                        break;
                    } else if (subModeCh == 900) {
                        subMode = Mode.ALPHA;
                        break;
                    }
                    break;
                case ALPHA_SHIFT:
                    subMode = priorToShiftMode;
                    if (subModeCh < 26) {
                        ch = (char) (subModeCh + 65);
                        break;
                    } else if (subModeCh == 26) {
                        ch = ' ';
                        break;
                    } else if (subModeCh == 900) {
                        subMode = Mode.ALPHA;
                        break;
                    }
                    break;
                case PUNCT_SHIFT:
                    subMode = priorToShiftMode;
                    if (subModeCh < 29) {
                        ch = PUNCT_CHARS[subModeCh];
                        break;
                    } else if (subModeCh == 29) {
                        subMode = Mode.ALPHA;
                        break;
                    } else if (subModeCh == MODE_SHIFT_TO_BYTE_COMPACTION_MODE) {
                        result.append((char) byteCompactionData[i]);
                        break;
                    } else if (subModeCh == 900) {
                        subMode = Mode.ALPHA;
                        break;
                    }
                    break;
            }
            if (ch != 0) {
                result.append(ch);
            }
        }
    }

    private static int byteCompaction(int mode, int[] codewords, Charset encoding, int codeIndex, StringBuilder result) {
        ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
        if (mode == BYTE_COMPACTION_MODE_LATCH) {
            int count = 0;
            long value = 0;
            int[] byteCompactedCodewords = new int[6];
            boolean end = false;
            int nextCode = codewords[codeIndex];
            codeIndex++;
            while (codeIndex < codewords[0] && !end) {
                int count2 = count + 1;
                byteCompactedCodewords[count] = nextCode;
                value = (900 * value) + nextCode;
                int codeIndex2 = codeIndex + 1;
                nextCode = codewords[codeIndex];
                if (nextCode == 900 || nextCode == BYTE_COMPACTION_MODE_LATCH || nextCode == NUMERIC_COMPACTION_MODE_LATCH || nextCode == BYTE_COMPACTION_MODE_LATCH_6 || nextCode == 928 || nextCode == BEGIN_MACRO_PDF417_OPTIONAL_FIELD || nextCode == MACRO_PDF417_TERMINATOR) {
                    codeIndex = codeIndex2 - 1;
                    end = true;
                    count = count2;
                } else if (count2 % 5 != 0 || count2 <= 0) {
                    count = count2;
                    codeIndex = codeIndex2;
                } else {
                    for (int j = 0; j < 6; j++) {
                        decodedBytes.write((byte) (value >> ((5 - j) * 8)));
                    }
                    value = 0;
                    count = 0;
                    codeIndex = codeIndex2;
                }
            }
            if (codeIndex == codewords[0] && nextCode < 900) {
                byteCompactedCodewords[count] = nextCode;
                count++;
            }
            for (int i = 0; i < count; i++) {
                decodedBytes.write((byte) byteCompactedCodewords[i]);
            }
        } else if (mode == BYTE_COMPACTION_MODE_LATCH_6) {
            int count3 = 0;
            long value2 = 0;
            boolean end2 = false;
            while (codeIndex < codewords[0] && !end2) {
                int codeIndex3 = codeIndex + 1;
                int code = codewords[codeIndex];
                if (code < 900) {
                    count3++;
                    value2 = (900 * value2) + code;
                    codeIndex = codeIndex3;
                } else if (code == 900 || code == BYTE_COMPACTION_MODE_LATCH || code == NUMERIC_COMPACTION_MODE_LATCH || code == BYTE_COMPACTION_MODE_LATCH_6 || code == 928 || code == BEGIN_MACRO_PDF417_OPTIONAL_FIELD || code == MACRO_PDF417_TERMINATOR) {
                    codeIndex = codeIndex3 - 1;
                    end2 = true;
                } else {
                    codeIndex = codeIndex3;
                }
                if (count3 % 5 == 0 && count3 > 0) {
                    for (int j2 = 0; j2 < 6; j2++) {
                        decodedBytes.write((byte) (value2 >> ((5 - j2) * 8)));
                    }
                    value2 = 0;
                    count3 = 0;
                }
            }
        }
        result.append(new String(decodedBytes.toByteArray(), encoding));
        return codeIndex;
    }

    /* JADX WARN: Code restructure failed: missing block: B:18:0x002b, code lost:
    
        r5 = decodeBase900toBase10(r4, r2);
        r11.append(r5);
        r2 = 0;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private static int numericCompaction(int[] r9, int r10, java.lang.StringBuilder r11) throws com.google.zxing.FormatException {
        /*
            r8 = 900(0x384, float:1.261E-42)
            r7 = 0
            r2 = 0
            r3 = 0
            r6 = 15
            int[] r4 = new int[r6]
        L9:
            r6 = r9[r7]
            if (r10 >= r6) goto L4e
            if (r3 != 0) goto L4e
            int r1 = r10 + 1
            r0 = r9[r10]
            r6 = r9[r7]
            if (r1 != r6) goto L18
            r3 = 1
        L18:
            if (r0 >= r8) goto L34
            r4[r2] = r0
            int r2 = r2 + 1
            r10 = r1
        L1f:
            int r6 = r2 % 15
            if (r6 == 0) goto L29
            r6 = 902(0x386, float:1.264E-42)
            if (r0 == r6) goto L29
            if (r3 == 0) goto L9
        L29:
            if (r2 <= 0) goto L9
            java.lang.String r5 = decodeBase900toBase10(r4, r2)
            r11.append(r5)
            r2 = 0
            goto L9
        L34:
            if (r0 == r8) goto L4a
            r6 = 901(0x385, float:1.263E-42)
            if (r0 == r6) goto L4a
            r6 = 924(0x39c, float:1.295E-42)
            if (r0 == r6) goto L4a
            r6 = 928(0x3a0, float:1.3E-42)
            if (r0 == r6) goto L4a
            r6 = 923(0x39b, float:1.293E-42)
            if (r0 == r6) goto L4a
            r6 = 922(0x39a, float:1.292E-42)
            if (r0 != r6) goto L4f
        L4a:
            int r10 = r1 + (-1)
            r3 = 1
            goto L1f
        L4e:
            return r10
        L4f:
            r10 = r1
            goto L1f
        */
        throw new UnsupportedOperationException("Method not decompiled: com.google.zxing.pdf417.decoder.DecodedBitStreamParser.numericCompaction(int[], int, java.lang.StringBuilder):int");
    }

    private static String decodeBase900toBase10(int[] codewords, int count) throws FormatException {
        BigInteger result = BigInteger.ZERO;
        for (int i = 0; i < count; i++) {
            result = result.add(EXP900[(count - i) - 1].multiply(BigInteger.valueOf(codewords[i])));
        }
        String resultString = result.toString();
        if (resultString.charAt(0) != '1') {
            throw FormatException.getFormatInstance();
        }
        return resultString.substring(1);
    }
}
