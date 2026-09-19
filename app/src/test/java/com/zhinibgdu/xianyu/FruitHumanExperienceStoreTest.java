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
}
