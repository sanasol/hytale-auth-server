package ws.sanasol.dualauth.agent;

import ws.sanasol.dualauth.server.DualServerTokenManager;

public class DualAuthWarmup implements Runnable {
    @Override
    public void run() {
        try {
            Thread.sleep(5000);
            System.err.println("Triggering F2P identity token pre-fetch...");
            DualServerTokenManager.ensureF2PTokens();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Error during async warm-up: " + e.getMessage());
        }
    }

    public static void start() {
        Thread t = new Thread(new DualAuthWarmup());
        t.setDaemon(true);
        t.setName("DualAuth-Warmup");
        t.start();
    }
}
