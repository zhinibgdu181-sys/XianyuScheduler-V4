package com.zhinibgdu.xianyu;
import android.content.Context;import android.graphics.Bitmap;
import com.google.mlkit.vision.text.*;import com.google.mlkit.vision.common.InputImage;
public class OcrRegression {
 static int checks;static void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
 public static void main(String[] args){
  Context c=new Context();String fakeShell=args[0];
  ScreenOcr.Snapshot first=ScreenOcr.capture(c,fakeShell,()->false);
  check(first.width==720&&first.fullText.contains("204"),"snapshot data");
  check(InputImage.last.isRecycled(),"completed bitmap released");
  ScreenOcr.capture(c,fakeShell,()->false);
  check(TextRecognition.clients==1,"recognizer reused");
  TextRecognition.defer=true;
  ScreenOcr.Snapshot cancelled=ScreenOcr.capture(c,fakeShell,()->TextRecognition.lastTask!=null&&!TextRecognition.lastTask.isComplete());
  Bitmap held=InputImage.last;TextRecognizer old=TextRecognition.last;
  check(cancelled.isEmpty(),"cancel result empty");
  check(!held.isRecycled()&&!old.closed,"in-flight resources retained");
  TextRecognition.lastTask.finish(new Text());
  check(held.isRecycled()&&old.closed,"completion releases detached resources");
  TextRecognition.defer=false;ScreenOcr.capture(c,fakeShell,()->false);
  check(TextRecognition.clients==2,"fresh recognizer after cancellation");
  ScreenOcr.close();check(TextRecognition.last.closed,"session close");
  System.out.println("PASS "+checks+" OCR lifecycle assertions (ML Kit/Android test doubles)");
 }
}
