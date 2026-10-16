import com.lanlink.app.Pairing;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

/** Exercises the production Java pairing protocol against the Node service. */
public class PairingMergeProbe {
  static final class Members implements Pairing.Members {
    final JSONObject config;
    Members(String file) throws Exception { config = new JSONObject(Files.readString(Path.of(file))); }
    public JSONObject own() { return export().getJSONObject("members").getJSONObject(config.getString("id")); }
    public JSONObject export() { return config.getJSONObject("group"); }
    public String credential() { return config.getString("token"); }
    public boolean established() { return export().getJSONObject("members").length() > 1; }
    public void admit(JSONObject member) { throw new UnsupportedOperationException(); }
    public void admitGroup(JSONObject meta) throws Exception {
      JSONObject members = export().getJSONObject("members"), incoming = meta.getJSONObject("members");
      PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(config.getJSONObject("deviceKey").getString("private"))));
      for (Iterator<String> it = incoming.keys(); it.hasNext();) {
        String id = it.next(); if (id.equals(config.getString("id"))) continue;
        JSONObject member = new JSONObject(incoming.getJSONObject(id).toString());
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(key);
        signature.update(("lanlink-member-v1|" + export().getString("id") + "|" + id + "|" + member.getString("key")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        member.put("certificate", new JSONObject().put("issuer", config.getString("id")).put("signature", Base64.getEncoder().encodeToString(signature.sign())));
        members.put(id, member);
      }
      export().put("revision", System.currentTimeMillis());
    }
    public void joinPaired(JSONObject result) {
      config.put("token", result.getString("token")).put("group", result.getJSONObject("group"));
    }
    public JSONObject migrations() { return config.optJSONObject("mergedGroups") == null ? new JSONObject() : config.getJSONObject("mergedGroups"); }
    public void rememberMigrations(JSONObject records) { config.put("mergedGroups", records); }
  }
  public static void main(String[] args) throws Exception {
    Members members = new Members(args[1]);
    if (args[0].equals("client")) {
      JSONObject result = Pairing.exchange("127.0.0.1", Integer.parseInt(args[2]), args[3], args[4], members.own(), members);
      if (result.getJSONObject("group").getJSONObject("members").length() != 4) throw new AssertionError("Expected four members");
      System.out.println(result);
    } else {
      try (Pairing pairing = new Pairing(members::credential, 45831)) {
        pairing.members = members; pairing.start(false);
        System.out.println(pairing.show().put("port", pairing.port())); System.out.flush();
        new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (members.export().getJSONObject("members").length() != 4) throw new AssertionError("Expected four members");
        System.out.println(members.export());
      }
    }
  }
}
