package com.lanlink.app;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Exercise actual framed helper I/O: transient failures must not become permission revocations. */
final class HelperRecoveryProbe {
  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  static void run(LinkService service) throws Exception {
    ShellChannel channel = service.shell;
    JSONObject binding = channel.bootstrap();
    AtomicReference<String> response = new AtomicReference<>("");
    Socket client = new Socket("127.0.0.1", binding.getInt("port"));
    Thread helper =
        new Thread(
            () -> {
              try {
                BufferedReader input =
                    new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
                BufferedWriter output =
                    new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), "UTF-8"));
                ShellAuth.client(binding.getString("secret"), input, output);
                while (true) {
                  ShellAuth.line(input);
                  String error = response.get();
                  output.write(
                      (error.isEmpty()
                                  ? Wire.obj("ok", true, "uid", 2000)
                                  : Wire.obj("error", error))
                              .toString()
                          + "\n");
                  output.flush();
                }
              } catch (Exception ignored) {
              }
            },
            "test-helper");
    helper.start();
    try {
      long deadline = System.currentTimeMillis() + 3000;
      while (!channel.ready() && System.currentTimeMillis() < deadline) Thread.sleep(20);
      check(channel.ready(), "authenticated helper connects");
      response.set("系统暂时未处理输入");
      try {
        channel.request(Wire.obj("method", "probe"));
        throw new AssertionError("transient rejection expected");
      } catch (IOException expected) {
        check(
            expected.getMessage().startsWith("INPUT_RECOVERING:"), "transient failure classified");
      }
      check(
          channel.ready() && channel.lastInputError == null,
          "transient failure retains permission and transport");
      response.set("PERMISSION_REQUIRED: INJECT_EVENTS denied");
      try {
        channel.request(Wire.obj("method", "probe"));
        throw new AssertionError("permission rejection expected");
      } catch (IOException expected) {
        check(
            expected.getMessage().startsWith("PERMISSION_REQUIRED:"),
            "real permission denial classified");
      }
      check(
          channel.lastInputError != null && channel.ready(),
          "permission denial does not restart live helper");
      response.set("");
      channel.request(Wire.obj("method", "probe"));
      check(channel.lastInputError == null, "permission can recover without restarting helper");
      client.close();
      try {
        channel.request(Wire.obj("method", "heartbeat"));
      } catch (IOException expected) {
      }
      check(!channel.ready() && channel.recovering(), "short disconnect enters recovery grace");
    } finally {
      client.close();
      helper.join(1000);
    }
  }
}
