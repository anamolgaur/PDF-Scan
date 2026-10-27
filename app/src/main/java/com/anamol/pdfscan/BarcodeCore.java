package com.anamol.pdfscan;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/** QR codes and barcodes from a grey picture, with ZXing. Pure Java, so it is tested on a desktop. */
final class BarcodeCore {
    private static final MultiFormatReader READER = new MultiFormatReader();

    static {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(
                BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417,
                BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.ITF,
                BarcodeFormat.CODABAR));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        READER.setHints(hints);
    }

    private BarcodeCore() {
    }

    /** Looks for a code as the picture is, then turned a quarter (for barcodes upright in the sensor). */
    static String find(byte[] lum, int w, int h) {
        String s = decode(lum, w, h);
        if (s != null) return s;
        byte[] rot = new byte[w * h];
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) rot[c * h + (h - 1 - r)] = lum[r * w + c];
        }
        return decode(rot, h, w);
    }

    static synchronized String decode(byte[] lum, int w, int h) {
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(lum, w, h, 0, 0, w, h, false);
            Result r = READER.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
            return r.getText();
        } catch (NotFoundException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        } finally {
            READER.reset();
        }
    }
}
