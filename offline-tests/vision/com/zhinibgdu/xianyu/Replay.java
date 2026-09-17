package com.zhinibgdu.xianyu;
import android.graphics.*;import java.lang.reflect.*;import java.util.*;
public class Replay {
 static Class<?> c=FruitGameSolver.class;
 static Object call(String n,Object...args)throws Exception {for(Method m:c.getDeclaredMethods())if(m.getName().equals(n)){m.setAccessible(true);return m.invoke(null,args);}throw new RuntimeException(n);}
 static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static String pos(Object o)throws Exception{return field(o,"centerX")+","+field(o,"centerY");}
 public static void main(String[] args)throws Exception{
 Bitmap b=BitmapFactory.decodeFile(args[0]); b=Bitmap.createScaledBitmap(b,720,Math.round(b.getHeight()*720f/b.getWidth()),true);
 Class<?> fc=Class.forName(c.getName()+"$GameFrame");Constructor<?> con=fc.getDeclaredConstructors()[0];con.setAccessible(true);Object frame=con.newInstance(b,720,b.getHeight());
 List<?> os=(List<?>)call("detectFruitObjects",frame);System.out.println("COUNT="+os.size());
 for(Object o:os)System.out.println("FRUIT "+pos(o)+" FREE="+(call("findNearestBlockingFruit",o,os,720,b.getHeight())==null));
 for(int i=0;i<os.size();i++)for(int j=i+1;j<os.size();j++) {Object a=os.get(i),d=os.get(j);if(call("findNearestBlockingFruit",a,os,720,b.getHeight())!=null||call("findNearestBlockingFruit",d,os,720,b.getHeight())!=null)continue;Object sim=call("similarity",a,d);System.out.println("FREE_PAIR "+pos(a)+" "+pos(d)+" score="+field(sim,"score")+" mad="+field(sim,"rgbMad")+" hist="+field(sim,"histCos")+" shape="+field(sim,"shapeIou"));}
 Object p=call("chooseBestPairWithThreshold",os,720,b.getHeight(),.975,null,null);
 if (p==null) throw new AssertionError("Bottom pair missing");
 Object pa=field(p,"a"),pb=field(p,"b");
 if (Math.abs(((Number)field(pa,"centerX")).doubleValue()-644)>6 || Math.abs(((Number)field(pa,"centerY")).doubleValue()-917)>6) throw new AssertionError("Wrong first fruit");
 if (Math.abs(((Number)field(pb,"centerX")).doubleValue()-480)>6 || Math.abs(((Number)field(pb,"centerY")).doubleValue()-880)>6) throw new AssertionError("Wrong second fruit");
 if(call("findBestMatchingFruit",pb,os,720,b.getHeight())==null)throw new AssertionError("B reacquisition fails");
 if(call("chooseBestPairWithThreshold",Collections.singletonList(pa),720,b.getHeight(),.975,null,null)!=null)throw new AssertionError("Singleton pair");
 HashSet<String> denied=new HashSet<>();denied.add((String)call("positionKeyV4361",pa));
 Object alternate=call("chooseBestPairWithThreshold",os,720,b.getHeight(),.975,null,denied);
 if(alternate!=null && (field(alternate,"a")==pa || field(alternate,"b")==pa))throw new AssertionError("Blocked position selected");
 System.out.println("ASSERTIONS=PASS: bottom grapes, B reacquisition, singleton, blocked-position exclusion");
 System.out.println("PAIR="+(p==null?"none":pos(field(p,"a"))+" -> "+pos(field(p,"b"))+" sim="+field(p,"score")));
 }
}
