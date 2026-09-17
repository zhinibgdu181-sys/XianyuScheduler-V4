package com.zhinibgdu.xianyu;
import android.graphics.*;import java.lang.reflect.*;import java.util.*;
public class VisionBenchmark {
 public static void main(String[] args)throws Exception {
  Bitmap raw=BitmapFactory.decodeFile(args[0]);Bitmap b=Bitmap.createScaledBitmap(raw,720,Math.round(raw.getHeight()*720f/raw.getWidth()),true);
  Class<?> frame=Class.forName("com.zhinibgdu.xianyu.FruitGameSolver$GameFrame");Constructor<?> ctor=frame.getDeclaredConstructors()[0];ctor.setAccessible(true);Object f=ctor.newInstance(b,720,b.getHeight());
  Method detect=FruitGameSolver.class.getDeclaredMethod("detectFruitObjects",frame);detect.setAccessible(true);detect.invoke(null,f);
  long[] times=new long[3];for(int i=0;i<3;i++){long start=System.nanoTime();detect.invoke(null,f);times[i]=(System.nanoTime()-start)/1000000;}Arrays.sort(times);
  System.out.println("VISION_MS min="+times[0]+" median="+times[1]+" max="+times[2]+" (desktop JVM, 720px width)");
 }
}
