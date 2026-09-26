import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public class QrProbe {
  public static void main(String[] args) throws Exception {
    BufferedImage image = ImageIO.read(new File(args[0]));
    int width = image.getWidth(), height = image.getHeight();
    int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
    String actual = new MultiFormatReader().decode(bitmap).getText();
    if (!actual.equals(Files.readString(Path.of(args[1])))) throw new AssertionError("QR round-trip mismatch");
    System.out.println("Desktop QR / Android ZXing decoder round-trip passed.");
  }
}
