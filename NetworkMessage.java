package aps.shared.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class NetworkMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 2026050601L;

    private final MessageType type;
    private final String from;
    private final String to;
    private final String text;
    private final String fileId;
    private final String fileName;
    private final String mimeType;
    private final long fileSize;
    private final long timestamp;
    private final byte[] payload;
    private final List<UserProfile> users;
    private final Map<String, String> attributes;

    private NetworkMessage(Builder builder) {
        this.type       = Objects.requireNonNull(builder.type, "type");
        this.from       = nullToEmpty(builder.from);
        this.to         = nullToEmpty(builder.to);
        this.text       = nullToEmpty(builder.text);
        this.fileId     = nullToEmpty(builder.fileId);
        this.fileName   = nullToEmpty(builder.fileName);
        this.mimeType   = nullToEmpty(builder.mimeType);
        this.fileSize   = builder.fileSize;
        this.timestamp  = builder.timestamp == 0L ? Instant.now().toEpochMilli() : builder.timestamp;
        this.payload    = builder.payload == null ? new byte[0] : builder.payload.clone();
        this.users      = List.copyOf(builder.users);
        this.attributes = Collections.unmodifiableMap(new HashMap<>(builder.attributes));
    }

    // --- factories -----------------------------------------------------------

    public static Builder builder(MessageType type) { return new Builder(type); }

    public static NetworkMessage chat(String from, String text) {
        return builder(MessageType.CHAT).from(from).text(text).build();
    }

    public static NetworkMessage system(String text) {
        return builder(MessageType.SYSTEM).from("SISTEMA").text(text).build();
    }

    public static NetworkMessage alert(String text) {
        return builder(MessageType.ALERT).from("ALERTA").text(text).build();
    }

    public static NetworkMessage error(String text) {
        return builder(MessageType.ERROR).from("SISTEMA").text(text).build();
    }

    public static NetworkMessage image(String from, String caption, byte[] pngBytes) {
        return builder(MessageType.IMAGE)
                .from(from).text(caption).mimeType("image/png").payload(pngBytes).build();
    }

    // --- getters -------------------------------------------------------------

    public MessageType          getType()       { return type; }
    public String               getFrom()       { return from; }
    public String               getTo()         { return to; }
    public String               getText()       { return text; }
    public String               getFileId()     { return fileId; }
    public String               getFileName()   { return fileName; }
    public String               getMimeType()   { return mimeType; }
    public long                 getFileSize()   { return fileSize; }
    public long                 getTimestamp()  { return timestamp; }
    public byte[]               getPayload()    { return payload.clone(); }
    public List<UserProfile>    getUsers()      { return users; }
    public Map<String, String>  getAttributes() { return attributes; }

    // --- helpers -------------------------------------------------------------

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    // --- builder -------------------------------------------------------------

    public static final class Builder {
        private final MessageType type;
        private String from, to, text, fileId, fileName, mimeType;
        private long fileSize, timestamp;
        private byte[] payload;
        private List<UserProfile> users = List.of();
        private final Map<String, String> attributes = new HashMap<>();

        private Builder(MessageType type) { this.type = type; }

        public Builder from(String v)      { this.from     = v; return this; }
        public Builder to(String v)        { this.to       = v; return this; }
        public Builder text(String v)      { this.text     = v; return this; }
        public Builder fileId(String v)    { this.fileId   = v; return this; }
        public Builder fileName(String v)  { this.fileName = v; return this; }
        public Builder mimeType(String v)  { this.mimeType = v; return this; }
        public Builder fileSize(long v)    { this.fileSize = v; return this; }
        public Builder timestamp(long v)   { this.timestamp = v; return this; }
        public Builder payload(byte[] v)   { this.payload  = v == null ? null : v.clone(); return this; }
        public Builder users(List<UserProfile> v) { this.users = v == null ? List.of() : List.copyOf(v); return this; }
        public Builder attribute(String key, String value) {
            if (key != null && value != null) attributes.put(key, value);
            return this;
        }
        public NetworkMessage build() { return new NetworkMessage(this); }
    }
}