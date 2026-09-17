package android.graphics;
public class BitmapFactory { public static Bitmap decodeFile(String p){try{return new Bitmap(javax.imageio.ImageIO.read(new java.io.File(p)));}catch(Exception e){return null;}} }
