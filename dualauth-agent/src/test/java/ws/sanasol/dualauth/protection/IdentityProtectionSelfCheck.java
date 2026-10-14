package ws.sanasol.dualauth.protection;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import ws.sanasol.dualauth.agent.DualAuthConfig;

/** Exercises the real HTTP check and environment configuration in fresh JVMs. */
public final class IdentityProtectionSelfCheck {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            boolean enabled = Boolean.parseBoolean(args[0]);
            if (DualAuthConfig.IDENTITY_PROTECTION != enabled)
                throw new AssertionError("Unexpected identity protection default/configuration");
            boolean allowed = IdentityProtectionChecker.isOmniAllowed("protected", "Admin");
            if (allowed == enabled) throw new AssertionError("Protected UUID result incorrect");
            if (IdentityProtectionChecker.isOmniAllowed("protected", "Admin") != allowed)
                throw new AssertionError("Cached result changed");
            if (!IdentityProtectionChecker.isOmniAllowed("unprotected", "Player"))
                throw new AssertionError("Unprotected UUID rejected");
            return;
        }

        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/check-identity", exchange -> {
            requests.incrementAndGet();
            boolean protectedIdentity = exchange.getRequestURI().getQuery().contains("uuid=protected&");
            byte[] body = (protectedIdentity
                ? "{\"allowed\":false,\"reason\":\"password_protected\"}"
                : "{\"allowed\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            for (String setting : new String[]{"unset", "false", "true"}) {
                ProcessBuilder child = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    IdentityProtectionSelfCheck.class.getName(),
                    String.valueOf(setting.equals("true")));
                child.environment().remove("HYTALE_IDENTITY_PROTECTION");
                if (!setting.equals("unset")) child.environment().put("HYTALE_IDENTITY_PROTECTION", setting);
                child.environment().put("HYTALE_AUTH_SERVER", "127.0.0.1:" + server.getAddress().getPort());
                if (child.inheritIO().start().waitFor() != 0)
                    throw new AssertionError("Identity protection check failed for " + setting);
                int expectedRequests = setting.equals("true") ? 2 : 0;
                if (requests.get() != expectedRequests)
                    throw new AssertionError("Unexpected API calls for " + setting + ": " + requests.get());
            }
            System.out.println("Identity protection: default/false skip HTTP; true blocks protected UUIDs, permits others and caches results.");
        } finally {
            server.stop(0);
        }
    }
}
