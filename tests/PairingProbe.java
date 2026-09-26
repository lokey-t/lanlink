import com.lanlink.app.Pairing;
import org.json.JSONObject;
import java.io.*;

public class PairingProbe {
  public static void main(String[] args) throws Exception {
    if (args[0].equals("client")) {
      JSONObject r = Pairing.exchange("127.0.0.1", Integer.parseInt(args[1]), args[2], args[3]);
      if (!r.getString("token").equals(args[4])) throw new AssertionError("Wrong account");
      System.out.println("PAIR_CLIENT_OK");
    } else {
      try (Pairing p = new Pairing(() -> args[1], 45831)) {
        p.start(false); JSONObject info = p.show(); info.put("port", p.port()); System.out.println(info); System.out.flush();
        new BufferedReader(new InputStreamReader(System.in)).readLine();
      }
    }
  }
}
