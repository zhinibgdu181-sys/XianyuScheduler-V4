package com.zhinibgdu.xianyu;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class TrayRegression {
    static Class<?> solver = FruitGameSolver.class;

    static Object call(String name, Object... args) throws Exception {
        for (Method m : solver.getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            m.setAccessible(true);
            return m.invoke(null, args);
        }
        throw new NoSuchMethodException(name);
    }

    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static Object frame(String path) throws Exception {
        Bitmap raw = BitmapFactory.decodeFile(path);
        Bitmap b = Bitmap.createScaledBitmap(raw, 720,
                Math.round(raw.getHeight() * 720f / raw.getWidth()), true);
        Class<?> fc = Class.forName(solver.getName() + "$GameFrame");
        Constructor<?> ctor = fc.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(b, raw.getWidth(), raw.getHeight());
    }

    static void checkCount(String path, int expected) throws Exception {
        Object f = frame(path);
        Object tray = call("detectTrayState", f);
        int count = ((Number) field(tray, "count")).intValue();
        boolean stable = (Boolean) field(tray, "stable");
        if (!stable || count != expected) {
            throw new AssertionError(path + " expected tray=" + expected
                    + " got=" + count + " stable=" + stable);
        }
        System.out.println("TRAY " + path + " count=" + count
                + " top=" + field(tray, "topRatio")
                + " mid=" + field(tray, "midRatio")
                + " bottom=" + field(tray, "bottomRatio"));
    }


    static void checkUnstable(String path) throws Exception {
        Object f = frame(path);
        Object tray = call("detectTrayState", f);
        boolean stable = (Boolean) field(tray, "stable");
        if (stable) {
            throw new AssertionError(path + " falling-animation frame must be unstable");
        }
        System.out.println("TRAY_ANIMATION " + path + " stable=false PASS");
    }

    static void checkDirectMatch(String path, int expectedCount) throws Exception {
        Object f = frame(path);
        Object tray = call("detectTrayState", f);
        int count = ((Number) field(tray, "count")).intValue();
        if (count != expectedCount) {
            throw new AssertionError(path + " expected tray=" + expectedCount + " got=" + count);
        }
        @SuppressWarnings("unchecked")
        List<Object> objects = (List<Object>) call("detectFruitObjects", f);
        Bitmap bitmap = (Bitmap) field(f, "bitmap");
        Object choice = call("chooseBestTrayMatch", tray, objects,
                bitmap.getWidth(), bitmap.getHeight(), null);
        if (choice == null) {
            throw new AssertionError(path + " tray=" + count + " should have a safe direct match");
        }
        double hist = ((Number) field(choice, "histCos")).doubleValue();
        if (hist < 0.985) throw new AssertionError("tray match too weak " + hist);
        Object item = field(choice, "trayItem");
        Object fruit = field(choice, "boardFruit");
        System.out.println("TRAY_MATCH count=" + count
                + " slot=" + field(item, "slotName")
                + " board=" + field(fruit, "centerX") + "," + field(fruit, "centerY")
                + " hist=" + hist + " PASS");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 7) throw new IllegalArgumentException("need tray_0..tray_3 + 3 animation frames");
        for (int i = 0; i < 4; i++) checkCount(args[i], i);
        for (int i = 4; i < 7; i++) checkUnstable(args[i]);

        // Real-device regression: every occupied stable tray state shown by the user
        // must have at least one direct board match. With count >=2 the production
        // solver is forbidden to start a new fruit type, so this is the critical path.
        checkDirectMatch(args[1], 1);
        checkDirectMatch(args[2], 2);
        checkDirectMatch(args[3], 3);
    }
}
