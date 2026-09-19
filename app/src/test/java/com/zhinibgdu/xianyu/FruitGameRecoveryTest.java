package com.zhinibgdu.xianyu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FruitGameRecoveryTest {
    private static final String GAME = "第1关 剩余 20 消除 打乱 解锁";
    private static final int SKY = 0xff00bfff;
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private Context context;
    private MockedStatic<RootCommandRunner> root;
    private MockedStatic<BitmapFactory> decoder;
    private MockedStatic<SystemClock> clock;
    private final List<Bitmap> frames = new ArrayList<>();
    private final TestHost host = new TestHost();
    private int captures;

    @Before public void setUp() {
        context = mock(Context.class);
        when(context.getExternalFilesDir(null)).thenReturn(files.getRoot());
        clock = mockStatic(SystemClock.class);
        clock.when(SystemClock::elapsedRealtime).thenAnswer(call -> host.now);
        root = mockStatic(RootCommandRunner.class);
        root.when(() -> RootCommandRunner.run(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        decoder = mockStatic(BitmapFactory.class);
        decoder.when(() -> BitmapFactory.decodeFile(anyString())).thenAnswer(call -> {
            int index = captures++;
            return index < frames.size() ? frames.get(index) : bitmap(SKY);
        });
    }

    @After public void tearDown() {
        decoder.close();
        root.close();
        clock.close();
    }

    private Bitmap bitmap(int color) {
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.getWidth()).thenReturn(72);
        when(bitmap.getHeight()).thenReturn(156);
        when(bitmap.getPixel(anyInt(), anyInt())).thenReturn(color);
        doAnswer(call -> {
            int[] pixels = call.getArgument(0);
            Arrays.fill(pixels, color);
            return null;
        }).when(bitmap).getPixels(any(int[].class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        return bitmap;
    }

    private FruitGameSolver.Result solve() {
        return FruitGameSolver.solveOneRound(context, "su", host);
    }

    @Test public void lowDetectionUsesOneFreshRetryBeforeStopping() {
        assertEquals(FruitGameSolver.Result.SAFE_STOP_CLEAN, solve());
        assertEquals(2, captures); // original + one fresh no-action retry
        assertEquals(1, host.countLogs("[无动作V4.42]"));
        assertEquals(0, host.taps);
    }

    @Test public void delayedCompletionDuringNoActionIsNotASafeStop() {
        host.ocr = reason -> reason.contains("无安全动作") && captures >= 2
                ? snapshot("第2关") : snapshot(GAME);
        assertEquals(FruitGameSolver.Result.COMPLETED, solve());
        assertEquals(2, captures);
        assertEquals(1, host.countLogs("[无动作V4.42]"));
        assertEquals(0, host.taps);
    }

    @Test public void threeScreenshotFailuresCanRecoverOnFourthCapture() {
        frames.addAll(Arrays.asList(null, null, null));
        assertEquals(FruitGameSolver.Result.SAFE_STOP_CLEAN, solve());
        assertEquals(5, captures);
        assertEquals(3, host.countLogs("[恢复V4.42] 重新截图"));
        assertEquals(1, host.countLogs("[恢复V4.42] 已重新确认"));
        assertEquals(1, host.countLogs("[无动作V4.42]"));
    }

    @Test public void fourthConsecutiveScreenshotFailureStops() {
        frames.addAll(Arrays.asList(null, null, null, null));
        assertEquals(FruitGameSolver.Result.SAFE_STOP_CLEAN, solve());
        assertEquals(4, captures);
        assertEquals(3, host.countLogs("[恢复V4.42] 重新截图"));
        assertEquals(0, host.taps);
    }

    @Test public void successfulObservationResetsRecoveryBudget() {
        frames.addAll(Arrays.asList(null, null, null, bitmap(SKY), null, null, null));
        assertEquals(FruitGameSolver.Result.SAFE_STOP_CLEAN, solve());
        assertEquals(8, captures);
        assertEquals(6, host.countLogs("[恢复V4.42] 重新截图"));
        assertEquals(2, host.countLogs("[恢复V4.42] 已重新确认"));
    }

    @Test public void transientEntryOcrExceptionRecovers() {
        host.ocr = reason -> {
            if (host.ocrCalls == 1) throw new IllegalStateException("temporary OCR failure");
            return snapshot(GAME);
        };
        assertEquals(FruitGameSolver.Result.SAFE_STOP_CLEAN, solve());
        assertEquals(1, host.countLogs("[恢复V4.42] 进入确认重试"));
        assertEquals(2, captures);
    }

    @Test public void persistentEntryOcrExceptionIsBounded() {
        host.ocr = reason -> { throw new IllegalStateException("OCR unavailable"); };
        assertEquals(FruitGameSolver.Result.SAFE_STOP_CLEAN, solve());
        assertEquals(4, host.ocrCalls);
        assertEquals(0, captures);
    }

    @Test public void startScreenUsesOcrSizeWithoutExtraScreenshot() {
        host.ocr = reason -> snapshot(host.ocrCalls == 1
                ? "VERSION1.0.2 开始游戏 第1关 图鉴 排行榜"
                : GAME);
        assertEquals(FruitGameSolver.Result.SAFE_STOP_CLEAN, solve());
        assertEquals(1, host.taps);
        // 两次均来自进入正式棋盘后的观察；启动页本身不再额外截 PNG。
        assertEquals(2, captures);
        assertEquals(1, host.countLogs("[开始页V4.43.9]"));
    }

    @Test public void startScreenIsRecognizedAsFruitSurface() {
        assertTrue(FruitGameSolver.looksLikeFruitStartScreen(
                "VERSION1.0.2 d6f51 开始游戏 第1关 图鉴 排行榜"));
        assertFalse(FruitGameSolver.looksLikeFruitStartScreen("得骰子赚闲鱼币 去完成"));
    }

    @Test public void repeatedPopupCloseTapRemainsWhitelisted() {
        assertTrue(GameTapPolicy.allows(
                1247, 877, 1440, 3120, "水果游戏-关闭道具弹窗"));
        assertTrue(GameTapPolicy.allows(
                1247, 877, 1440, 3120, "水果游戏-继续关闭道具弹窗"));
        assertTrue(GameTapPolicy.allows(
                1247, 877, 1440, 3120, "水果游戏-继续关闭道具弹窗#2"));
        assertFalse(GameTapPolicy.allows(
                1000, 1500, 1440, 3120, "水果游戏-继续关闭道具弹窗"));
    }

    @Test public void toolPopupTextRequiresPopupOnlyEvidence() {
        // 三种真实弹窗：都有明确的“使用”动作或“解锁所有槽位”正文。
        assertTrue(FruitGameSolver.looksLikeBlockingFunctionPopupText(
                "解锁 解锁所有槽位 使用 打乱 5%"));
        assertTrue(FruitGameSolver.looksLikeBlockingFunctionPopupText(
                "解锁所有檀位 [DJ使用 打乱 12%"));
        assertTrue(FruitGameSolver.looksLikeBlockingFunctionPopupText(
                "随机打乱 立即使用"));
        assertTrue(FruitGameSolver.looksLikeBlockingFunctionPopupText(
                "消除 消除一组水果 D使用"));
        assertTrue(FruitGameSolver.looksLikeBlockingFunctionPopupText(
                "解锁 解锁所有糟位 D使用 打乱 12%"));

        // 正常水果游戏页面本身就有“剩余/消除/解锁/打乱”，不能再误判成弹窗。
        assertFalse(FruitGameSolver.looksLikeBlockingFunctionPopupText(
                "赚闲鱼币 剩余 182 消除 第1关 解锁 打乱 12%"));
        assertFalse(FruitGameSolver.looksLikeBlockingFunctionPopupText(
                "剩余 196 消除 第1关 解锁 打乱 5%"));
    }


    @Test public void fruitSurfaceIsRecognizedEvenWhenPopupOverlaysIt() {
        // 入口OCR同时包含水果主体和覆盖层文字时，必须先判定为水果游戏，
        // 再由入口状态机处理覆盖弹窗；不能因为“使用/解锁”把它降级成未知页面。
        String text = "赚闲鱼币 剩余 208 第1关 消除 打乱 解锁 解锁所有槽位 使用";
        assertTrue(FruitGameSolver.looksLikeFruitGame(text));
        assertTrue(FruitGameSolver.looksLikeBlockingFunctionPopupText(text));

        // 正常水果页也有“解锁/消除/打乱”，但没有弹窗专属“使用”语义。
        String normal = "赚闲鱼币 剩余 208 第1关 消除 打乱 解锁";
        assertTrue(FruitGameSolver.looksLikeFruitGame(normal));
        assertFalse(FruitGameSolver.looksLikeBlockingFunctionPopupText(normal));
    }
    @Test public void missingRemainingBaselineRecoversBeforeDecisions() {
        host.ocr = reason -> snapshot(host.ocrCalls == 1 ? "第1关 消除 打乱" : GAME);
        assertEquals(FruitGameSolver.Result.SAFE_STOP_CLEAN, solve());
        assertEquals(2, captures);
        assertEquals(0, host.taps);
    }

    @Test public void abortDuringRecoveryPreventsFurtherCapture() {
        frames.add(null);
        host.abortRecoverySleep = true;
        assertEquals(FruitGameSolver.Result.ABORTED, solve());
        assertEquals(1, captures);
        assertEquals(0, host.taps);
    }

    @Test public void secondTrayVerificationUsesFreshFrameWithoutRetapping() throws Exception {
        for (int i = 0; i < 4; i++) frames.add(bitmap(0xffff0000)); // still occupied
        frames.add(bitmap(SKY)); // first empty frame after delayed animation
        frames.add(bitmap(SKY)); // second settled empty frame required by V4.50
        Object observation = observe(0);
        assertNotNull(observation);
        assertEquals(6, captures);
        assertEquals(1, host.countLogs("[验证V4.42]"));
        assertEquals(0, host.taps);
        for (int i = 0; i < 4; i++) verify(frames.get(i), atLeastOnce()).recycle();
    }

    @Test public void unchangedTrayReturnsAfterTwoStableFrames() throws Exception {
        frames.add(bitmap(SKY));
        frames.add(bitmap(SKY));
        Object observation = observe(1, 0);
        assertNotNull(observation);
        assertEquals(2, captures);
        assertEquals(1, host.countLogs("[验证V4.44.0]"));
        assertEquals(0, host.taps);
    }

    @Test public void blockedPositionCoversAdjacentDetectionBuckets() {
        Set<String> blocked = new HashSet<>();
        blocked.add("36,51");
        assertTrue(FruitGameSolver.isBlockedPosition(blocked, 433.5f, 606.5f));
        assertTrue(FruitGameSolver.isBlockedPosition(blocked, 445.0f, 618.0f));
        assertFalse(FruitGameSolver.isBlockedPosition(blocked, 470.0f, 650.0f));
    }

    @Test public void bridgePushAllowsOneToTwoAndSafeTwoToThree() {
        assertTrue(FruitGameSolver.allowsBridgePush(1, false, true));
        assertTrue(FruitGameSolver.allowsBridgePush(1, true, false));
        assertTrue(FruitGameSolver.allowsBridgePush(2, false, true));
        assertFalse(FruitGameSolver.allowsBridgePush(2, true, false));
        assertFalse(FruitGameSolver.allowsBridgePush(3, true, true));
    }

    @Test public void cascadeGuardProtectsNearlyFullTray() {
        assertFalse(FruitGameSolver.allowsThirdSlotCascade(0, 3, false));
        assertTrue(FruitGameSolver.allowsThirdSlotCascade(1, 1, false));
        assertFalse(FruitGameSolver.allowsThirdSlotCascade(1, 2, false));
        assertTrue(FruitGameSolver.allowsThirdSlotCascade(2, 0, false));
        assertFalse(FruitGameSolver.allowsThirdSlotCascade(2, 1, false));
        assertTrue(FruitGameSolver.allowsThirdSlotCascade(2, 1, true));
        assertFalse(FruitGameSolver.allowsThirdSlotCascade(2, 2, true));
        assertFalse(FruitGameSolver.allowsThirdSlotCascade(3, 0, false));
    }
    @Test public void cascadeOccupancyCreditsIncomingFruitEliminations() {
        // 1槽已有同类；点击根水果后有2个级联同类，其中1个与槽内二消。
        assertTrue(FruitGameSolver.allowsCascadeOccupancy(1, 2, 1));
        // 无任何二消抵扣时，1槽 + 根 + 2级联 = 4个实体，必须拒绝。
        assertFalse(FruitGameSolver.allowsCascadeOccupancy(1, 2, 0));
        // 根水果与唯一级联水果二消后，净占用回到1槽。
        assertTrue(FruitGameSolver.allowsCascadeOccupancy(1, 1, 1));
        // 3槽本身不能再引入任何新水果。
        assertFalse(FruitGameSolver.allowsCascadeOccupancy(3, 0, 1));
    }


    @Test public void expectedTrayCountRequiresTwoStableFrames() throws Exception {
        frames.add(bitmap(0xffff0000));
        frames.add(bitmap(0xffff0000));
        Object observation = observe(3, 2);
        assertNotNull(observation);
        assertEquals(2, captures);
        assertEquals(0, host.taps);
    }

    @Test public void bridgeSimilarityAllowsRotationButKeepsColorStrict() {
        assertTrue(FruitGameSolver.isBridgeMateSimilarity(0.94, 0.04, 0.995, 0.82));
        assertTrue(FruitGameSolver.isBridgeMateSimilarity(0.94, 0.04, 0.980, 0.82));
        assertFalse(FruitGameSolver.isBridgeMateSimilarity(0.94, 0.06, 0.995, 0.82));
        assertFalse(FruitGameSolver.isBridgeMateSimilarity(0.94, 0.04, 0.995, 0.70));
    }

    @Test public void twoStepLookaheadFindsContinuationPairAfterCandidateAndMate() throws Exception {
        Class<?> fruitClass = Class.forName("com.zhinibgdu.xianyu.FruitGameSolver$FruitObject");
        java.lang.reflect.Constructor<?> ctor = fruitClass.getDeclaredConstructor(
                float.class, float.class, float.class, float.class, float.class, float.class,
                float[].class, boolean[].class, float[].class);
        ctor.setAccessible(true);

        Object a = fruitObject(ctor, 80f, 100f);
        Object b = fruitObject(ctor, 180f, 100f);
        Object c1 = fruitObject(ctor, 280f, 100f);
        Object c2 = fruitObject(ctor, 380f, 100f);

        Method method = FruitGameSolver.class.getDeclaredMethod(
                "countReliablePairsAfterRemoving",
                List.class, fruitClass, fruitClass, int.class, int.class, Set.class);
        method.setAccessible(true);

        List<Object> objects = Arrays.asList(a, b, c1, c2);
        int pairs = (int) method.invoke(
                null, objects, a, b, 500, 1000, Collections.emptySet());

        assertTrue("continuation pair should be visible after the first pair is removed", pairs >= 1);
    }

    private static Object fruitObject(
            java.lang.reflect.Constructor<?> ctor,
            float x, float y
    ) throws Exception {
        float[] rgb = new float[32 * 32 * 3];
        float[] hist = new float[72];
        boolean[] shape = new boolean[32 * 32];
        Arrays.fill(shape, true);
        Arrays.fill(rgb, 0.5f);
        Arrays.fill(hist, 1f / (float) Math.sqrt(hist.length));
        return ctor.newInstance(x, y, x - 20f, y - 20f, x + 20f, y + 20f,
                rgb, shape, hist);
    }

    @Test public void secondTrayVerificationFailureReturnsNullAndDoesNotTap() throws Exception {
        for (int i = 0; i < 8; i++) frames.add(null);
        assertNull(observe(0));
        assertEquals(8, captures); // two bounded batches of four observations
        assertEquals(0, host.taps);
    }

    @Test public void abortBetweenTrayVerificationsDoesNotObserveAgain() throws Exception {
        for (int i = 0; i < 4; i++) frames.add(bitmap(0xffff0000));
        host.abortRecoverySleep = true;
        assertNull(observe(0));
        assertEquals(4, captures);
        assertEquals(0, host.taps);
    }

    @Test public void ocrVerificationExceptionGetsSecondChance() throws Exception {
        host.ocr = reason -> {
            if (host.ocrCalls == 1) throw new IllegalStateException("transient");
            return snapshot("第1关 剩余 18 消除 打乱");
        };
        assertTrue(booleanField(verifyRemaining(), "confirmed"));
        assertEquals(2, host.ocrCalls);
        assertEquals(0, host.taps);
    }

    @Test public void intermediatePairUsesTrayFastPathWithoutOcr() throws Exception {
        Object verification = verifyRemaining(1);
        assertTrue(booleanField(verification, "confirmed"));
        assertEquals(18, intField(verification, "afterRemaining"));
        assertEquals(0, host.ocrCalls);
        assertEquals(1, host.countLogs("[快速验证V4.44.1]"));
    }

    @Test public void wrongRemainingValueAlsoGetsSecondChance() throws Exception {
        host.ocr = reason -> snapshot("剩余 " + (host.ocrCalls == 1 ? 19 : 18));
        assertTrue(booleanField(verifyRemaining(), "confirmed"));
        assertEquals(2, host.ocrCalls);
    }

    @Test public void persistentOcrVerificationFailureStaysUnconfirmed() throws Exception {
        host.ocr = reason -> { throw new IllegalStateException("unavailable"); };
        assertFalse(booleanField(verifyRemaining(), "confirmed"));
        assertEquals(2, host.ocrCalls);
    }

    @Test public void recoveryRecognizesTaskPanelWithoutClicking() {
        frames.add(null);
        host.ocr = reason -> snapshot(reason.contains("恢复页面") ? "得骰子赚闲鱼币" : GAME);
        assertEquals(FruitGameSolver.Result.NOT_FRUIT_GAME, solve());
        assertEquals(2, captures);
        assertEquals(0, host.taps);
    }

    private Object observe(int expectedCount) throws Exception {
        return observe(expectedCount, 1);
    }

    private Object observe(int expectedCount, int baselineCount) throws Exception {
        Method method = FruitGameSolver.class.getDeclaredMethod("observeTrayAfterTap",
                Context.class, String.class, FruitGameSolver.Host.class,
                int.class, int.class, String.class);
        method.setAccessible(true);
        return method.invoke(null, context, "su", host, expectedCount, baselineCount, "test");
    }

    private Object verifyRemaining() throws Exception {
        return verifyRemaining(3);
    }

    private Object verifyRemaining(int actionIndex) throws Exception {
        Method method = FruitGameSolver.class.getDeclaredMethod("verifyPairRemaining",
                FruitGameSolver.Host.class, int.class, int.class, String.class);
        method.setAccessible(true);
        return method.invoke(null, host, 20, actionIndex, "test");
    }

    private boolean booleanField(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(value);
    }

    private int intField(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(value);
    }

    private static ScreenOcr.Snapshot snapshot(String text) {
        return new ScreenOcr.Snapshot(text, Collections.emptyList(), 72, 156);
    }

    private static final class TestHost implements FruitGameSolver.Host {
        long now;
        int taps;
        int ocrCalls;
        boolean aborted;
        boolean abortRecoverySleep;
        Function<String, ScreenOcr.Snapshot> ocr = reason -> snapshot(GAME);
        final List<String> logs = new ArrayList<>();
        @Override public boolean tap(int x, int y, String reason) { taps++; return !aborted; }
        @Override public boolean sleep(long min, long max) {
            now += min;
            if (abortRecoverySleep && min == 300L && max == 500L) aborted = true;
            return !aborted;
        }
        @Override public boolean aborted() { return aborted; }
        @Override public void log(String message) { logs.add(message); }
        @Override public ScreenOcr.Snapshot ocr(String reason) { ocrCalls++; return ocr.apply(reason); }
        int countLogs(String prefix) {
            return (int) logs.stream().filter(message -> message.startsWith(prefix)).count();
        }
    }
}