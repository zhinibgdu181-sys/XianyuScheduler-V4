package com.zhinibgdu.xianyu;

/** An OCR miss or an unexpected count change is not evidence of a cleared slot. */
final class PairVerification {
    private PairVerification() {}
    static boolean confirmed(int before, int after) {
        return before >= 2 && after >= 0 && before - after == 2;
    }
}
