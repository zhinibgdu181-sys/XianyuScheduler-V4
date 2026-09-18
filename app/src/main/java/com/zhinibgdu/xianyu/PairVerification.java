package com.zhinibgdu.xianyu;

/** An OCR miss or an unexpected count change is not evidence of a cleared slot. */
final class PairVerification {
    private PairVerification() {}

    static boolean confirmed(int before, int after) {
        return before >= 2 && after >= 0 && before - after == 2;
    }

    /** True only when a previously visible task row is no longer visible. */
    static boolean rowDisappeared(boolean beforePresent, boolean afterPresent) {
        return beforePresent && !afterPresent;
    }

    /** Unknown progress must not be treated as an increase. */
    static boolean progressIncreased(int before, int after) {
        return before >= 0 && after >= 0 && after > before;
    }
}
