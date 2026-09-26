import com.lanlink.app.Wire;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public class InteropProbe {
  public static void main(String[] args) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    final boolean[] success = {false};
    // Match app startup: local identity/random initialization precedes TCP connect.
    Wire.token();
    String localId = UUID.randomUUID().toString();
    Wire peer =
        new Wire(
            new Socket("127.0.0.1", Integer.parseInt(args[0])),
            Wire.obj(
                "id",
                localId,
                "name",
                "Java interoperability probe",
                "capabilities",
                Wire.obj()),
            Wire.parseToken(args[1]),
            new Wire.Listener() {
              public void ready(Wire p) {
                new Thread(
                        () -> {
                          try {
                            JSONObject result =
                                p.request("echo", Wire.obj("text", "跨语言测试 👋", "number", 42));
                            if (result.getString("text").equals("跨语言测试 👋")
                                && result.getInt("number") == 42) {
                              success[0] = true;
                              System.out.println("INTEROP_OK");
                            }
                          } catch (Exception e) {
                            e.printStackTrace();
                          } finally {
                            p.close();
                            done.countDown();
                          }
                        })
                    .start();
              }

              public void message(Wire p, JSONObject m) {
                try {
                  if (m.optString("type").equals("ping")) p.send(Wire.obj("type", "pong"));
                } catch (Exception ignored) {
                }
              }

              public void closed(Wire p) {
                if (p.failure != null) System.err.println(p.failure);
                done.countDown();
              }
            });
    peer.start();
    if (!done.await(20, TimeUnit.SECONDS)) {
      peer.close();
      throw new Exception("Interop timed out");
    }
    if (!success[0]) throw new Exception("Interop failed");
  }
}
