package com.google.mlkit.vision.common;
import android.graphics.Bitmap;
public class InputImage {public static Bitmap last;public static InputImage fromBitmap(Bitmap b,int angle){last=b;return new InputImage();}}
