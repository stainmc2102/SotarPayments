package vn.sotarpayments.napcard.models;

public class CardRequest {
    private final String telco, code, serial, requestId;
    private final int amount;

    public CardRequest(String telco, String code, String serial, int amount, String requestId) {
        this.telco = telco; 
        this.code = code; 
        this.serial = serial;
        this.amount = amount; 
        this.requestId = requestId;
    }

    public String getTelco() { return telco; }
    public String getCode() { return code; }
    public String getSerial() { return serial; }
    public int getAmount() { return amount; }
    public String getRequestId() { return requestId; }
}