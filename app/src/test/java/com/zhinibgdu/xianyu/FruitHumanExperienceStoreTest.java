package com.zhinibgdu.xianyu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class FruitHumanExperienceStoreTest {

    @Test
    public void remainingDropLearnsPair() {
        FruitHumanExperienceStore.Transition t =
                FruitHumanExperienceStore.classify(200, 198, 1, 1, 40, 38, 30, 28);
        assertEquals(FruitStrategyExperienceStore.STRATEGY_PAIR, t.strategy);
    }

    @Test
    public void trayDecreaseLearnsTrayMatch() {
        FruitHumanExperienceStore.Transition t =
                FruitHumanExperienceStore.classify(200, 200, 2, 1, 40, 39, 30, 29);
        assertEquals(FruitStrategyExperienceStore.STRATEGY_TRAY_MATCH, t.strategy);
    }

    @Test
    public void trayIncreaseLearnsSafePush() {
        FruitHumanExperienceStore.Transition t =
                FruitHumanExperienceStore.classify(200, 200, 1, 2, 40, 39, 30, 29);
        assertEquals(FruitStrategyExperienceStore.STRATEGY_SAFE_PUSH, t.strategy);
    }

    @Test
    public void boardUnblocksWithoutTrayChangeLearnsUnblock() {
        FruitHumanExperienceStore.Transition t =
                FruitHumanExperienceStore.classify(200, 200, 2, 2, 40, 39, 30, 28);
        assertEquals(FruitStrategyExperienceStore.STRATEGY_TRAY_UNBLOCK, t.strategy);
    }

    @Test
    public void noVerifiedProgressDoesNotBecomeExperience() {
        assertNull(FruitHumanExperienceStore.classify(200, 200, 2, 2, 40, 40, 30, 30));
    }

    @Test
    public void falseTransitionCannotReinforceStrategyMemory() {
        assertEquals(false, FruitHumanExperienceStore.shouldReinforce(
                new FruitHumanExperienceStore.Transition(
                        FruitStrategyExperienceStore.STRATEGY_PAIR, false)));
    }

    @Test
    public void unknownHumanActionCannotEnterStrategyMemory() {
        assertEquals(false, FruitHumanExperienceStore.shouldReinforce(
                new FruitHumanExperienceStore.Transition("MANUAL_TAP", true)));
    }

    @Test
    public void verifiedKnownHumanStrategyCanReinforce() {
        assertEquals(true, FruitHumanExperienceStore.shouldReinforce(
                new FruitHumanExperienceStore.Transition(
                        FruitStrategyExperienceStore.STRATEGY_PAIR, true)));
    }

    @Test
    public void impossibleRemainingIncreaseIsRejected() {
        FruitGameSolver.TeachingStateV464 a =
                new FruitGameSolver.TeachingStateV464(200, 1, 40, 20, 10, 0, 1, 0);
        FruitGameSolver.TeachingStateV464 b =
                new FruitGameSolver.TeachingStateV464(202, 1, 40, 20, 10, 0, 1, 0);
        assertEquals("remaining_increased", FruitHumanExperienceStore.rejectReason(a, b));
    }

    @Test
    public void afterStateMustBeStableBeforeItIsTrusted() {
        FruitGameSolver.TeachingStateV464 a =
                new FruitGameSolver.TeachingStateV464(200, 1, 40, 20, 10, 0, 1, 0);
        FruitGameSolver.TeachingStateV464 b =
                new FruitGameSolver.TeachingStateV464(200, 2, 40, 19, 10, 0, 1, 0);
        FruitGameSolver.TeachingStateV464 b2 =
                new FruitGameSolver.TeachingStateV464(200, 2, 40, 19, 10, 0, 1, 0);
        assertEquals(false, FruitHumanExperienceStore.sameStructuralState(a, b));
        assertEquals(true, FruitHumanExperienceStore.sameStructuralState(b, b2));
    }
}
