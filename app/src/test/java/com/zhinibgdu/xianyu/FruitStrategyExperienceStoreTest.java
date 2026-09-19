package com.zhinibgdu.xianyu;

import org.junit.Test;

import static org.junit.Assert.*;

public class FruitStrategyExperienceStoreTest {

    @Test public void structuralKeyIgnoresCoordinatesAndTracksStateBuckets() {
        String a = FruitStrategyExperienceStore.structuralKey(
                200, 2, 31, 6, 9, 0, 2, 0);
        String b = FruitStrategyExperienceStore.structuralKey(
                203, 2, 33, 6, 10, 0, 2, 0);
        assertEquals("nearby structural states should share the same coarse memory bucket", a, b);

        String differentTray = FruitStrategyExperienceStore.structuralKey(
                203, 1, 33, 6, 10, 0, 2, 0);
        assertNotEquals("tray occupancy is part of the strategy context", a, differentTray);
    }

    @Test public void repeatedSuccessProducesPositiveBias() {
        assertEquals(0.0, FruitStrategyExperienceStore.biasFromCounts(0, 0), 0.0001);
        assertTrue(FruitStrategyExperienceStore.biasFromCounts(5, 0) > 0.0);
        assertTrue(FruitStrategyExperienceStore.biasFromCounts(0, 5) < 0.0);
    }

    @Test public void firstObservationCannotDominateRanking() {
        double oneSuccess = FruitStrategyExperienceStore.biasFromCounts(1, 0);
        double manySuccesses = FruitStrategyExperienceStore.biasFromCounts(6, 0);
        assertTrue(oneSuccess > 0.0);
        assertTrue(oneSuccess < manySuccesses);
    }

    @Test public void experienceBiasIsStrictlyBounded() {
        assertTrue(Math.abs(
                FruitStrategyExperienceStore.biasFromCounts(12, 0)) <= 0.18 + 0.0001);
        assertTrue(Math.abs(
                FruitStrategyExperienceStore.biasFromCounts(0, 12)) <= 0.18 + 0.0001);
    }

    @Test public void strategyKeyContainsStrategyFamilyAndState() {
        String key = FruitStrategyExperienceStore.strategyKey(
                FruitStrategyExperienceStore.STRATEGY_DEPENDENCY_PUSH,
                200, 2, 31, 6, 9, 0, 2, 1);
        assertTrue(key.startsWith(
                FruitStrategyExperienceStore.STRATEGY_DEPENDENCY_PUSH + "|"));
        assertTrue(key.contains("R50"));
        assertTrue(key.contains("_T2"));
    }
}
