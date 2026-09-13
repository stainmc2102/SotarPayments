package vn.sotarpayments.napcard.manager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class CardSessionManager {
    public enum Step { SERIAL, CODE, CONFIRM }
    
    public static class Session {
        public String telco;
        public int amount;
        public String serial;
        public String pin;
        public Step step;
        
        public Session(String telco, int amount) {
            this.telco = telco; this.amount = amount; this.step = Step.SERIAL;
        }
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    public void start(UUID uuid, Session s) { sessions.put(uuid, s); }
    public Session get(UUID uuid) { return sessions.get(uuid); }
    public void stop(UUID uuid) { sessions.remove(uuid); }
}