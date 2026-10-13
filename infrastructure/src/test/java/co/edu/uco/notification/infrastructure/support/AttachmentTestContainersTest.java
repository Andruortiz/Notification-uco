package co.edu.uco.notification.infrastructure.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AttachmentTestContainersTest {

  @Test
  void clamAvAnswersItsVersionCommand() throws IOException {
    try (Socket socket =
        new Socket(
            AttachmentTestContainers.clamAv().getHost(),
            AttachmentTestContainers.clamAv().getMappedPort(3310))) {
      final OutputStream out = socket.getOutputStream();
      out.write("zVERSION\0".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final InputStream in = socket.getInputStream();
      final String reply = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
      assertTrue(reply.startsWith("ClamAV "), reply);
    }
  }

  @Test
  void minioReportsItselfAlive() throws IOException, InterruptedException {
    final HttpResponse<Void> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(AttachmentTestContainers.minioEndpoint() + "/minio/health/live"))
                    .build(),
                HttpResponse.BodyHandlers.discarding());
    assertTrue(response.statusCode() == 200, "status " + response.statusCode());
  }
}
