package com.zhinibgdu.xianyu;
import java.util.concurrent.atomic.AtomicInteger;
public class RuntimeRegression {
 static int checks;
 static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 public static void main(String[] args){
  for(int[] size:new int[][]{{720,1560},{1080,2340},{1440,3120}}){int w=size[0],h=size[1];
   check(GameTapPolicy.allows(w/2,(int)(h*.58),w,h,"水果游戏-配对A"),"bottom fruit");
   check(!GameTapPolicy.allows(w/2,(int)(h*.95),w,h,"水果游戏-配对A"),"bottom button");
   check(!GameTapPolicy.allows(w/2,(int)(h*.05),w,h,"水果游戏-配对B"),"top menu");
   check(GameTapPolicy.allows(w/2,(int)(h*.75),w,h,"水果游戏-开始游戏"),"start");
   check(GameTapPolicy.allows((int)(w*.866),(int)(h*.281),w,h,"水果游戏-关闭道具弹窗"),"popup");
   check(!GameTapPolicy.allows(w,(int)(h*.4),w,h,"水果游戏-配对B"),"outside x");
  }
  check(!GameTapPolicy.allows(10,10,0,0,"水果游戏-配对A"),"unknown dimensions");
  check(!GameTapPolicy.allows(100,700,720,1560,"水果游戏-配对任意"),"reason prefix insufficient");
  check(PairVerification.confirmed(206,204),"pair confirmed");
  check(PairVerification.confirmed(2,0),"last pair");
  for(int n:new int[]{-1,206,205,203,208})check(!PairVerification.confirmed(206,n),"unverified count "+n);
  check(!PairVerification.confirmed(-1,-3),"unknown baseline");
  check(RootCommandRunner.run("/bin/sh","exit 0",1000,()->false),"success");
  check(!RootCommandRunner.run("/bin/sh","exit 7",1000,()->false),"error");
  check(RootCommandRunner.run("/bin/sh","head -c 2000000 /dev/zero",1000,()->false),"large stdout no deadlock");
  check(!RootCommandRunner.run("/bin/sh","exit 0",1000,()->true),"pre-cancel");
  long start=System.nanoTime();
  check(!RootCommandRunner.run("/bin/sh","while :; do :; done",120,()->false),"timeout");
  check((System.nanoTime()-start)<2_000_000_000L,"bounded timeout");
  AtomicInteger polls=new AtomicInteger();
  check(!RootCommandRunner.run("/bin/sh","while :; do :; done",5000,()->polls.incrementAndGet()>2),"cancel in flight");
  Thread.currentThread().interrupt();
  check(!RootCommandRunner.run("/bin/sh","while :; do :; done",5000,()->false),"interrupt");
  check(Thread.currentThread().isInterrupted(),"interrupt flag retained");Thread.interrupted();
  System.out.println("PASS "+checks+" runtime assertions");
 }
}
