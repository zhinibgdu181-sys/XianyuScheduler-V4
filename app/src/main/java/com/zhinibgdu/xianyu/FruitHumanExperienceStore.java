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

    static void record(
            Context context,
            String taskName,
            FruitGameSolver.TeachingStateV464 before,
            FruitGameSolver.TeachingStateV464 after,
            Transition transition
    ) {
        if (context == null || before == null || after == null || transition == null) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = prefs.getString(LOG_KEY, "");
        String line = String.format(
                Locale.US,
                "%d|%s|%s|R%d>%d|T%d>%d|O%d>%d|D%d>%d|B%d>%d|P%d|U%d|C%d",
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

        FruitStrategyExperienceStore.record(
                context,
                transition.strategy,
                before.remaining,
                before.trayCount,
                before.objects,
                before.droppable,
                before.blocked,
                before.directPairs,
                before.unlockGain,
                before.continuationPairs,
                transition.success
        );
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("|", "_").replace("\n", " ").trim();
    }
}
