package android.graphics;
import java.awt.image.BufferedImage;
import java.awt.RenderingHints;
import java.io.OutputStream;
import javax.imageio.ImageIO;
public class Bitmap {
 public BufferedImage im; public Bitmap(BufferedImage i){im=i;}
 public int getWidth(){return im.getWidth();} public int getHeight(){return im.getHeight();}
 public int getPixel(int x,int y){return im.getRGB(x,y);}
 public void getPixels(int[] p,int o,int s,int x,int y,int w,int h){im.getRGB(x,y,w,h,p,o,s);}
 public static Bitmap createScaledBitmap(Bitmap b,int w,int h,boolean f){BufferedImage i=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);java.awt.Graphics2D g=i.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(b.im,0,0,w,h,null);g.dispose();return new Bitmap(i);}
 private boolean recycled; public boolean isRecycled(){return recycled;} public void recycle(){recycled=true;} public enum CompressFormat{PNG}
 public boolean compress(CompressFormat f,int q,OutputStream s){try{return ImageIO.write(im,"png",s);}catch(Exception e){return false;}}
}
