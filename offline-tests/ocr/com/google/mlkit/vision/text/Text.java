package com.google.mlkit.vision.text;
import java.util.*;import android.graphics.Rect;
public class Text {public String getText(){return "剩余 204";}public List<TextBlock> getTextBlocks(){return Collections.emptyList();}
 public static class TextBlock {public List<Line> getLines(){return Collections.emptyList();}}
 public static class Line {public String getText(){return "";} public Rect getBoundingBox(){return null;}}
}
