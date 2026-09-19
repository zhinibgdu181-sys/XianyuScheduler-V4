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

    @Test public void lowDetectionWaitsForFiveFreshFramesBeforeStopping() {
        assertEquals(FruitGameSolver.Result.SAFE_STOP_CLEAN, solve());
        assertEquals(6, captures); // original + five no-action retries
        assertEquals(5, host.countLogs("[无动作V4.42]"));
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
        assertEquals(9, captures);
        assertEquals(3, host.countLogs("[恢复V4.42] 重新截图"));
        assertEquals(1, host.countLogs("[恢复V4.42] 已重新确认"));
        assertEquals(5, host.countLogs("[无动作V4.42]"));
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
        assertEquals(12, captures);
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
        assertEquals(6, captures);
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
        // 六次均来自进入正式棋盘后的观察；启动页本身不再额外截 PNG。
        assertEquals(6, captures);
        assertEquals(1, host.countLogs("[开始页V4.43.9]"));
    }

    @Test public void startScreenIsRecognizedAsFruitSurface() {
        assertTrue(FruitGameSolver.looksLikeFruitStartScreen(
                "VERSION1.0.2 d6f51 开始游戏 第1关 图鉴 排行榜"));
        assertFalse(FruitGameSolver.looksLikeFruitStartScreen("得骰子赚闲鱼币 去完成"));
    }

    @Test public void missingRemainingBaselineRecoversBeforeDecisions() {
        host.ocr = reason -> snapshot(host.ocrCalls == 1 ? "第1关 消除 打乱" : GAME);
        assertEquals(FruitGameSolver.Result.SAFE_STOP_CLEAN, solve());
        assertEquals(6, captures);
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
        frames.add(bitmap(SKY)); // empty after delayed animation
        Object observation = observe(0);
        assertNotNull(observation);
        assertEquals(5, captures);
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
        Method method = FruitGameSolver.class.getDeclaredMethod("verifyPairRemaining",
                FruitGameSolver.Host.class, int.class, int.class, String.class);
        method.setAccessible(true);
        return method.invoke(null, host, 20, 1, "test");
    }

    private boolean booleanField(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(value);
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
