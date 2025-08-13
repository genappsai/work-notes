Alright — let’s strip it down and build a **REST-only Fleet LCM Agent** that talks to the Fleet LCM Agent Controller using only **Java’s built-in `HttpClient`** (no WebClient, no Spring WebFlux).

This will:
✅ Work through a customer-provided HTTP/HTTPS proxy
✅ Support Basic proxy authentication
✅ Support mTLS or custom CA (for TLS-intercepting proxies)
✅ Use long-polling for commands
✅ Be lightweight enough for a small standalone agent

---

## **1. Agent configuration (YAML)**

```yaml
agent:
  id: "vcf-primary-01"
  controller_url: "https://agent-control.myco.com"
  poll_timeout_sec: 60
proxy:
  enabled: true
  host: "proxy.corp.local"
  port: 8080
  username: "svc-agent"
  password: "P@ssw0rd"
tls:
  ca_bundle_path: "/etc/agent/ca-bundle.pem"
  client_cert_path: "/etc/agent/agent-cert.pem"
  client_key_path: "/etc/agent/agent-key.pem"
```

---

## **2. HTTP Client setup with proxy and TLS**

```java
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;

public class AgentHttpClientFactory {

    public static HttpClient create(AgentConfig cfg) throws Exception {
        // Load custom CA for proxy interception or controller cert
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);

        try (var in = Files.newInputStream(Path.of(cfg.getTls().getCaBundlePath()))) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate caCert = (X509Certificate) cf.generateCertificate(in);
            trustStore.setCertificateEntry("custom-ca", caCert);
        }
        tmf.init(trustStore);

        // Load client certificate for mTLS
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        var certBytes = Files.readAllBytes(Path.of(cfg.getTls().getClientCertPath()));
        var keyBytes = Files.readAllBytes(Path.of(cfg.getTls().getClientKeyPath()));
        // You'd need to merge PEM key+cert into PKCS12 before this step
        // Skipping PKCS12 merge code here for brevity
        kmf.init(keyStore, "".toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

        HttpClient.Builder builder = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(10));

        if (cfg.getProxy().isEnabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(cfg.getProxy().getHost(), cfg.getProxy().getPort())));
        }

        return builder.build();
    }
}
```

---

## **3. Agent long-polling loop**

```java
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

public class FleetAgent {

    private final AgentConfig config;
    private final HttpClient client;

    public FleetAgent(AgentConfig config, HttpClient client) {
        this.config = config;
        this.client = client;
    }

    public void start() {
        String lastAckId = null;

        while (true) {
            try {
                String body = """
                    {
                      "agent_id": "%s",
                      "last_ack_id": %s
                    }
                    """.formatted(config.getAgentId(),
                                  lastAckId == null ? "null" : "\"" + lastAckId + "\"");

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(config.getControllerUrl() + "/control/poll"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(config.getPollTimeoutSec() + 5))
                        .POST(HttpRequest.BodyPublishers.ofString(body));

                if (config.getProxy().isEnabled() && config.getProxy().getUsername() != null) {
                    String creds = config.getProxy().getUsername() + ":" + config.getProxy().getPassword();
                    String encoded = Base64.getEncoder().encodeToString(creds.getBytes());
                    reqBuilder.header("Proxy-Authorization", "Basic " + encoded);
                }

                HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    List<Command> commands = Command.fromJsonList(resp.body());
                    for (Command cmd : commands) {
                        handleCommand(cmd);
                        lastAckId = cmd.getId();
                    }
                } else {
                    System.err.println("Poll failed: " + resp.statusCode() + " " + resp.body());
                }
            } catch (Exception e) {
                e.printStackTrace();
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void handleCommand(Command cmd) {
        System.out.println("Executing: " + cmd);
        // TODO: actual execution logic
        sendResult(cmd.getId(), "SUCCESS", "Completed");
    }

    private void sendResult(String cmdId, String status, String details) {
        try {
            String body = """
                {
                  "command_id": "%s",
                  "status": "%s",
                  "details": "%s"
                }
                """.formatted(cmdId, status, details);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.getControllerUrl() + "/control/result"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

---

## **4. Why this works well for customer proxy**

* Agent always initiates outbound HTTPS → proxy-friendly.
* Proxy is configured via `ProxySelector` + `Proxy-Authorization` header for Basic auth.
* If proxy does TLS interception → trust store includes customer CA.
* If using mTLS → client key/cert loaded into `SSLContext`.

---

If you want, I can **add a small Spring Boot Controller** that implements `/control/poll` and `/control/result` so you can run this agent and test through a corporate proxy simulation (e.g., Dockerized Squid). That would give you a complete working fleet LCM agent-controller prototype using REST and HttpClient.

Do you want me to prepare that controller too?


Alright — here’s a **minimal Spring Boot Fleet LCM Agent Controller** implementation that works with the `HttpClient`-based Fleet LCM Agent I showed earlier.

It will:
✅ Provide `/control/poll` for agents to long-poll commands
✅ Provide `/control/result` for agents to post results
✅ Hold commands in memory for simplicity (later you can persist in DB)
✅ Work fully over REST/HTTPS (proxy-friendly)

---

## **1. Maven dependencies**

```xml
<dependencies>
    <!-- Spring Boot Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- JSON (Jackson) -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>

    <!-- Optional: Lombok for boilerplate reduction -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

---

## **2. Command model**

```java
package com.example.fleetlcm.controller.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Command {
    private String commandId;
    private String type;     // e.g., "UPGRADE"
    private String payload;  // JSON string or structured object
}
```

---

## **3. Poll request/response models**

```java
package com.example.fleetlcm.controller.model;

import lombok.Data;

@Data
public class PollRequest {
    private String agentId;
    private String lastAckId;
}
```

---

## **4. Result model**

```java
package com.example.fleetlcm.controller.model;

import lombok.Data;

@Data
public class ResultRequest {
    private String commandId;
    private String status;  // SUCCESS, FAILED, etc.
    private String details;
}
```

---

## **5. Controller service**

```java
package com.example.fleetlcm.controller;

import com.example.fleetlcm.controller.model.Command;
import com.example.fleetlcm.controller.model.PollRequest;
import com.example.fleetlcm.controller.model.ResultRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/control")
public class FleetControlController {

    // Commands waiting to be delivered to each agent
    private final Map<String, Queue<Command>> pendingCommands = new ConcurrentHashMap<>();

    // Store completed command results (in-memory for now)
    private final Map<String, String> commandResults = new ConcurrentHashMap<>();

    @PostMapping("/poll")
    public ResponseEntity<List<Command>> poll(@RequestBody PollRequest req) throws InterruptedException {
        String agentId = req.getAgentId();
        pendingCommands.putIfAbsent(agentId, new LinkedList<>());

        Queue<Command> queue = pendingCommands.get(agentId);

        // Long-poll: wait up to 60 seconds if no commands
        Instant end = Instant.now().plusSeconds(60);
        while (queue.isEmpty() && Instant.now().isBefore(end)) {
            Thread.sleep(1000); // Simple long-poll
        }

        List<Command> commands = new ArrayList<>();
        Command cmd;
        while ((cmd = queue.poll()) != null) {
            commands.add(cmd);
        }

        return ResponseEntity.ok(commands);
    }

    @PostMapping("/result")
    public ResponseEntity<String> result(@RequestBody ResultRequest result) {
        commandResults.put(result.getCommandId(), result.getStatus());
        System.out.printf("Received result for %s: %s (%s)%n",
                result.getCommandId(), result.getStatus(), result.getDetails());
        return ResponseEntity.ok("ACK");
    }

    // Simple test endpoint to enqueue a command for an agent
    @PostMapping("/enqueue/{agentId}")
    public ResponseEntity<String> enqueue(@PathVariable String agentId,
                                           @RequestBody Command cmd) {
        pendingCommands.putIfAbsent(agentId, new LinkedList<>());
        pendingCommands.get(agentId).add(cmd);
        return ResponseEntity.ok("Enqueued");
    }
}
```

---

## **6. Running the controller**

**application.properties**:

```properties
server.port=8443
# For HTTPS with self-signed cert (for testing behind proxy)
server.ssl.enabled=true
server.ssl.key-store=classpath:controller-keystore.p12
server.ssl.key-store-password=changeit
server.ssl.key-store-type=PKCS12
```

Generate a test cert:

```bash
keytool -genkeypair -alias controller -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore controller-keystore.p12 \
  -validity 3650 -storepass changeit \
  -dname "CN=agent-control.myco.com"
```

---

## **7. Testing with the Agent**

1. **Start the controller**:

   ```bash
   mvn spring-boot:run
   ```

   It will listen on `https://localhost:8443`

2. **Start the agent** with proxy config (real or dummy Squid proxy).

3. **Enqueue a command**:

   ```bash
   curl -k -X POST https://localhost:8443/control/enqueue/vcf-primary-01 \
        -H "Content-Type: application/json" \
        -d '{"commandId":"cmd-101","type":"UPGRADE","payload":"{\"component\":\"vROps\",\"version\":\"8.14\"}"}'
   ```

4. The agent will pick it up via `/control/poll` and then send a result to `/control/result`.

---

This setup works perfectly behind a **customer-provided proxy** because:

* All requests are **agent-initiated outbound HTTPS**.
* No inbound firewall rules needed.
* Proxy settings can be injected into the `HttpClient` as shown earlier.

---

If you want, I can also **add mTLS authentication** between the agent and controller so that even if the customer proxy intercepts HTTPS, the traffic is still end-to-end authenticated. That would make this production-grade.

Do you want me to add mTLS support next?


Awesome—here’s a complete, REST-only **mutual-TLS (mTLS)** setup for your **Fleet LCM Agent ↔ Fleet LCM Agent Controller** that still works cleanly through a customer HTTP/HTTPS proxy.

# 1) PKI: generate a CA, server cert (controller), and client cert (agent)

You can paste these into a shell. They produce **PKCS#12** files so Java can load them easily.

```bash
# 1) Root CA
openssl genrsa -out ca.key 4096
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 \
  -subj "/CN=FleetLCM Test CA" -out ca.crt

# 2) Controller (server) key & CSR
openssl genrsa -out controller.key 4096
openssl req -new -key controller.key -out controller.csr -subj "/CN=agent-control.myco.com"
cat > controller.ext <<'EOF'
subjectAltName = @alt_names
extendedKeyUsage = serverAuth
keyUsage = digitalSignature, keyEncipherment
[alt_names]
DNS.1 = agent-control.myco.com
DNS.2 = localhost
EOF
openssl x509 -req -in controller.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out controller.crt -days 1825 -sha256 -extfile controller.ext

# 3) Agent (client) key & CSR
openssl genrsa -out agent.key 4096
openssl req -new -key agent.key -out agent.csr -subj "/CN=vcf-primary-01"
cat > agent.ext <<'EOF'
extendedKeyUsage = clientAuth
keyUsage = digitalSignature, keyEncipherment
EOF
openssl x509 -req -in agent.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out agent.crt -days 730 -sha256 -extfile agent.ext

# 4) Package as PKCS#12 for Java
# Controller keystore (server cert+key):
openssl pkcs12 -export -out controller-keystore.p12 \
  -inkey controller.key -in controller.crt -certfile ca.crt -name controller \
  -passout pass:changeit

# Controller truststore (trust client certs via CA):
keytool -importcert -noprompt -alias fleet-ca -file ca.crt \
  -keystore controller-truststore.p12 -storetype PKCS12 -storepass changeit

# Agent keystore (client cert+key):
openssl pkcs12 -export -out agent-keystore.p12 \
  -inkey agent.key -in agent.crt -certfile ca.crt -name agent \
  -passout pass:changeit

# Agent truststore (trust controller via CA):
keytool -importcert -noprompt -alias fleet-ca -file ca.crt \
  -keystore agent-truststore.p12 -storetype PKCS12 -storepass changeit
```

> Important: the **server cert’s SAN must include the hostname** your agent uses (e.g., `agent-control.myco.com`). Adjust if you use a different DNS.

---

# 2) Controller (Spring Boot) – require client certs

### `application.properties`

```properties
server.port=8443

# Server identity (serves TLS using controller keypair)
server.ssl.enabled=true
server.ssl.key-store=classpath:controller-keystore.p12
server.ssl.key-store-password=changeit
server.ssl.key-store-type=PKCS12
server.ssl.key-alias=controller

# mTLS: trust client certs signed by your CA and REQUIRE a client cert
server.ssl.trust-store=classpath:controller-truststore.p12
server.ssl.trust-store-password=changeit
server.ssl.trust-store-type=PKCS12
server.ssl.client-auth=need
```

Drop `controller-keystore.p12` and `controller-truststore.p12` into `src/main/resources/`.

Your existing REST endpoints don’t need code changes to “enable” mTLS; Spring/Jetty/Tomcat will enforce the handshake. If you want to **read the authenticated client identity**:

```java
// Example: read client cert subject for authN/audit
@GetMapping("/whoami")
public String whoAmI(HttpServletRequest req) {
    X509Certificate[] chain = (X509Certificate[]) req.getAttribute("javax.servlet.request.X509Certificate");
    if (chain != null && chain.length > 0) {
        return chain[0].getSubjectX500Principal().getName();
    }
    return "no client cert";
}
```

---

# 3) Agent (Java 11+ HttpClient) – present client cert & trust server CA

Here’s a compact factory that builds an `HttpClient` with:

* **KeyManager** from `agent-keystore.p12` (client cert/key)
* **TrustManager** from `agent-truststore.p12` (trust the controller’s CA)
* Optional **proxy** (with Basic auth header if you need it)

```java
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Base64;

public class AgentHttpClientFactory {

    public static HttpClient create(AgentConfig cfg) {
        try {
            // Load client keypair (for mTLS)
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (var in = AgentHttpClientFactory.class.getResourceAsStream(cfg.getTls().getClientKeystoreClasspath())) {
                ks.load(in, cfg.getTls().getClientKeystorePassword().toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, cfg.getTls().getClientKeystorePassword().toCharArray());

            // Load truststore (trust server/CA)
            KeyStore ts = KeyStore.getInstance("PKCS12");
            try (var in = AgentHttpClientFactory.class.getResourceAsStream(cfg.getTls().getTruststoreClasspath())) {
                ts.load(in, cfg.getTls().getTruststorePassword().toCharArray());
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);

            // Build SSLContext
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

            HttpClient.Builder b = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(10))
                    .version(HttpClient.Version.HTTP_1_1); // proxies sometimes prefer 1.1

            if (cfg.getProxy().isEnabled()) {
                b.proxy(ProxySelector.of(new InetSocketAddress(cfg.getProxy().getHost(), cfg.getProxy().getPort())));
            }

            return b.build();
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException |
                 UnrecoverableKeyException | KeyManagementException | java.io.IOException e) {
            throw new RuntimeException("Failed to create HttpClient with mTLS", e);
        }
    }

    /** If your proxy requires Basic auth, add this header to each request. */
    public static void addProxyAuth(HttpRequest.Builder req, ProxyConfig proxy) {
        if (proxy.isEnabled() && proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
            String creds = proxy.getUsername() + ":" + proxy.getPassword();
            String encoded = Base64.getEncoder().encodeToString(creds.getBytes());
            req.header("Proxy-Authorization", "Basic " + encoded);
        }
    }
}
```

**Agent usage (long-poll sample):**

```java
HttpClient client = AgentHttpClientFactory.create(config);

HttpRequest.Builder rb = HttpRequest.newBuilder()
    .uri(URI.create(config.getControllerUrl() + "/control/poll"))
    .timeout(Duration.ofSeconds(config.getPollTimeoutSec() + 5))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(pollBodyJson));

AgentHttpClientFactory.addProxyAuth(rb, config.getProxy());
HttpResponse<String> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
```

> Hostname verification: the agent will verify the server cert’s **SAN** matches the URL host. Ensure your server cert contains the right DNS names (see the `controller.ext` above).

---

# 4) Keep it proxy-friendly

* **Agent always initiates** outbound HTTPS.
* With **mTLS**, the proxy still sees/connects but **can’t impersonate** the controller unless it has your CA/key (it shouldn’t).
* If the enterprise proxy **intercepts TLS** and re-signs with its **own CA**, you have two safe options:

  1. **Prefer CONNECT tunneling** (most proxies do this for 443) so the proxy doesn’t terminate TLS at all—your truststore only needs **your** CA.
  2. If they insist on interception, you must add the **proxy’s corporate CA** **and** keep your controller CA in the agent truststore. (You’ll lose true end-to-end TLS; but you can still bind app-level auth like HMAC/JWT to detect tampering.)

---

# 5) Optional hardening

* **Pinning (SPKI)**: capture your controller cert’s SPKI and compare on connect (in addition to normal PKI). For Java `HttpClient`, you’d implement a custom `X509TrustManager` wrapper to enforce SPKI pin.
* **JWT on top of mTLS**: issue a short-lived token after the first successful handshake; include `agent_id`, `namespace`, and `exp`, and require it on `/control/*`.
* **Idempotency**: use `X-Idempotency-Key` on `/control/result` and store dedupe keys.

---

# 6) Quick end-to-end test

1. Start controller on `https://localhost:8443` with the SSL properties above (place the two `p12` files in `resources/`).
2. Point agent `controller_url=https://localhost:8443` and supply `agent-keystore.p12` + `agent-truststore.p12`.
3. Enqueue a command:

```bash
curl -k -X POST https://localhost:8443/control/enqueue/vcf-primary-01 \
  -H "Content-Type: application/json" \
  -d '{"commandId":"cmd-101","type":"UPGRADE","payload":"{\"component\":\"vROps\",\"version\":\"8.14\"}"}'
```

4. The agent long-poll picks it up and posts `/control/result`. Controller prints the result on stdout.

---

# 7) Minimal config classes (agent)

```java
public class AgentConfig {
  private String agentId;
  private String controllerUrl;
  private int pollTimeoutSec = 60;
  private ProxyConfig proxy = new ProxyConfig();
  private TlsConfig tls = new TlsConfig();
  // getters/setters...
}
public class ProxyConfig {
  private boolean enabled;
  private String host;
  private int port;
  private String username;
  private String password;
  // getters/setters...
}
public class TlsConfig {
  // put PKCS12 paths on classpath or absolute file: use one style consistently
  private String clientKeystoreClasspath = "/agent-keystore.p12";
  private String clientKeystorePassword = "changeit";
  private String truststoreClasspath = "/agent-truststore.p12";
  private String truststorePassword = "changeit";
  // getters/setters...
}
```

---

## What you get

* **Strict mTLS**: controller *requires* a valid client cert; agent *requires* server CA.
* **Proxy compatibility**: outbound-only HTTPS, optional proxy auth header.
* **Production path**: add DB-backed queues, JWT/RBAC, retries/backoff (you already have the loop), and observability.

If you want, I can bundle this as a **ready-to-run sample repo structure** (controller app + agent app + a dockerized Squid proxy config for local testing).

