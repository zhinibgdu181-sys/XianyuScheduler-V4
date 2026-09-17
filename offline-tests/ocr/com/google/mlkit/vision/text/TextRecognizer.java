package com.google.mlkit.vision.text;
import com.google.android.gms.tasks.Task;import com.google.mlkit.vision.common.InputImage;
public class TextRecognizer {
 public boolean closed;
 public Task<Text> process(InputImage i){Task<Text> t=new Task<>();TextRecognition.lastTask=t;if(!TextRecognition.defer)t.finish(new Text());return t;}
 public void close(){closed=true;}
}
