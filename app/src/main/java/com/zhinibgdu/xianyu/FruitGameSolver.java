package com.zhinibgdu.xianyu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V4.30 视觉小游戏模块："消了还想消"水果配对。
 * 
 * 重构重点：
 * 1. 增加坑位（底部3个格子）状态感知。
 * 2. 危险等级动态阈值：坑位有2个不同水果时，强行降低匹配阈值进行救火。
 * 3. 多维度优先级打分：优先点击能直接消除坑位的水果，其次优先点击下方水果。
 * 4. 空间位置感知，避免点击顶部死角。
 */
final class FruitGameSolver {

    enum Result {
        COMPLETED,
        SAFE_STOP,
        NOT_FRUIT_GAME,
        ABORTED
    }

    interface Host {
        boolean tap(int x, int y, String reason);
        boolean sleep(long minMs, long maxMs);
        boolean aborted();
        void log(String message);
        ScreenOcr.Snapshot ocr(String reason);
    }

    private static final int ANALYSIS_WIDTH = 720;
    private static final int GRID = 32;
    private static final long SCREENSHOT_TIMEOUT_MS = 4200L;
    private static final long MAX_ROUND_MS = 7L * 60L * 1000L;
    private static final int MAX_PAIR_ACTIONS = 130;

    // 常规安全阈值：正常情况要求高置信度
    private static final double SAFE_PAIR_SCORE = 0.958;
    // 危险救火阈值：坑位有2个时，放宽标准，只要大概像就点
    private static final double DANGER_PAIR_SCORE = 0.850;

    private static final double MAX_RGB_MAD = 0.090;
    private static final double MIN_HIST_COS = 0.900;
    private static final double MIN_SHAPE_IOU = 0.64;

    private static final Pattern REMAINING_PATTERN = Pattern.compile("剩余\\s*([0-9]{1,4})");
    private static final Pattern PER
