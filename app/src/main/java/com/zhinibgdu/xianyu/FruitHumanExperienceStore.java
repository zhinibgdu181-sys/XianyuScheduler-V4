package com.zhinibgdu.xianyu;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * V4.64: records verified human-demonstration transitions.
 *
 * A human takeover is not itself treated as a successful strategy. We only
 * reinforce a strategy after a passive post-takeover observation proves that
 * the board made useful progress. The raw transition is also retained as a
 * compact audit trail so later versions can inspect real demonstrations.
 */
final class FruitHumanExperienceStore {

    private static final String PREFS = "xianyu_task_profiles_v48";
    private static final String LOG_KEY = "fruit_human_demo_v1";
    private static final int MAX_RECORDS = 120;

    private FruitHumanExperienceStore() {}

    static final class Transition {
        final String strategy;
        final boolean success;

        Transition(String strategy, boolean success) {
            this.strategy = strategy;
            this.success = success;
        }
    }

    static Transition classify(
            int beforeRemaining,
            int afterRemaining,
            int beforeTray,
            int afterTray,
            int beforeObjects,
            int afterObjects,
            int beforeBlocked,
            int afterBlocked
    ) {
        if (beforeRemaining >= 0 && afterRemaining >= 0
                && afterRemaining < beforeRemaining - 1) {
            return new Transition(FruitStrategyExperienceStore.STRATEGY_PAIR, true);
        }
        if (beforeTray >= 0 && afterTray >= 0 && afterTray < beforeTray) {
            return new Transition(FruitStrategyExperienceStore.STRATEGY_TRAY_MATCH, true);
        }
        if (beforeTray >= 0 && afterTray >= 0 && afterTray > beforeTray) {
            return new Transition(FruitStrategyExperienceStore.STRATEGY_SAFE_PUSH, true);
        }
        if ((beforeObjects >= 0 && afterObjects >= 0 && afterObjects < beforeObjects)
                || (beforeBlocked >= 0 && afterBlocked >= 0 && afterBlocked < beforeBlocked)) {
            return new Transition(FruitStrategyExperienceStore.STRATEGY_TRAY_UNBLOCK, true);
        }
        return null;
    }

    /**
     * Two observations must represent the same structural state before a human
     * transition is admitted as verified. This filters transient OCR/animation
     * frames without learning or replaying the underlying human tap.
     */
    static boolean sameStructuralState(
            FruitGameSolver.TeachingStateV464 a,
            FruitGameSolver.TeachingStateV464 b
    ) {
        if (a == null || b == null) return false;
        return a.remaining == b.remaining
                && a.trayCount == b.trayCount
                && a.objects == b.objects
                && a.droppable == b.droppable
                && a.blocked == b.blocked
                && a.directPairs == b.directPairs
                && a.unlockGain == b.unlockGain
                && a.continuationPairs == b.continuationPairs;
    }

    /**
     * Impossible observations are rejected from strategy learning. They are audit
     * evidence only; no failure/success weight is changed for any strategy.
     */
    static String rejectReason(
            FruitGameSolver.TeachingStateV464 before,
            FruitGameSolver.TeachingStateV464 after
    ) {
        if (before == null || after == null) return "state_missing";
        if (before.remaining >= 0 && after.remaining >= 0
                && after.remaining > before.remaining) {
            return "remaining_increased";
        }
        if (after.trayCount > 3) return "tray_over_capacity";
        return null;
    }

    /**
     * Only a verified positive result on a known strategy family is allowed to
     * reinforce the runtime strategy store. A rejected/false/manual observation
     * can never be converted into strategy experience.
     */
    static boolean shouldReinforce(Transition transition) {
        return transition != null
                && transition.success
                && FruitStrategyExperienceStore.isKnownStrategy(transition.strategy);
    }

    static void record(
            Context context,
            String taskName,
            FruitGameSolver.TeachingStateV464 before,
            FruitGameSolver.TeachingStateV464 after,
            Transition transition
    ) {
        if (context == null || before == null || after == null || transition == null) return;

        String line = String.format(
                Locale.US,
                "%d|%s|VERIFIED|%s|R%d>%d|T%d>%d|O%d>%d|D%d>%d|B%d>%d|P%d|U%d|C%d",
                System.currentTimeMillis(),
                safe(taskName),
                transition.strategy,
                before.remaining, after.remaining,
                before.trayCount, after.trayCount,
                before.objects, after.objects,
                before.droppable, after.droppable,
                before.blocked, after.blocked,
                before.directPairs,
                before.unlockGain,
                before.continuationPairs
        );
        appendAuditLine(context, line);

        if (!shouldReinforce(transition)) {
            return;
        }

        FruitStrategyExperienceStore.recordVerifiedHumanSuccess(
                context,
                transition.strategy,
                before.remaining,
                before.trayCount,
                before.objects,
                before.droppable,
                before.blocked,
                before.directPairs,
                before.unlockGain,
                before.continuationPairs
        );
    }

    /**
     * Records an observation that was deliberately excluded from learning.
     * This is useful for diagnosing human mistakes without ever making them
     * executable knowledge.
     */
    static void recordRejectedObservation(
            Context context,
            String taskName,
            FruitGameSolver.TeachingStateV464 state,
            String reason
    ) {
        if (context == null || state == null) return;
        String line = String.format(
                Locale.US,
                "%d|%s|REJECTED|%s|R%d|T%d|O%d|D%d|B%d|P%d|U%d|C%d",
                System.currentTimeMillis(),
                safe(taskName),
                safe(reason),
                state.remaining,
                state.trayCount,
                state.objects,
                state.droppable,
                state.blocked,
                state.directPairs,
                state.unlockGain,
                state.continuationPairs
        );
        appendAuditLine(context, line);
    }

    private static void appendAuditLine(Context context, String line) {
        if (context == null || line == null || line.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = prefs.getString(LOG_KEY, "");
        String[] rows = old.isEmpty() ? new String[0] : old.split("\n");
        int start = Math.max(0, rows.length - MAX_RECORDS + 1);
        StringBuilder next = new StringBuilder();
        for (int i = start; i < rows.length; i++) {
            if (next.length() > 0) next.append('\n');
            next.append(rows[i]);
        }
        if (next.length() > 0) next.append('\n');
        next.append(line);
        prefs.edit().putString(LOG_KEY, next.toString()).apply();
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("|", "_").replace("\n", " ").trim();
    }
}
