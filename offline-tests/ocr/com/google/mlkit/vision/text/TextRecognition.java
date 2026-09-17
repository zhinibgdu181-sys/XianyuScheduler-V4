package com.google.mlkit.vision.text;
import com.google.android.gms.tasks.Task;import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
public class TextRecognition {public static int clients;public static boolean defer;public static Task<Text> lastTask;public static TextRecognizer last;
 public static TextRecognizer getClient(ChineseTextRecognizerOptions o){clients++;last=new TextRecognizer();return last;}}
