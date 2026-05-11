package aps.shared.model;

import java.io.Serial;
import java.io.Serializable;

public final class FileMetadata implements Serializable {

    @Serial
    private static final long serialVersionUID = 2026050602L;

    private final String id;
    private final String fileName;
    private final String owner;
    private final String room;
    private final long size;
    private final long uploadedAt;

    public FileMetadata(String id, String fileName, String owner, long size, long uploadedAt) {
        this(id, fileName, owner, "Equipe Alfa", size, uploadedAt);
    }

    public FileMetadata(String id, String fileName, String owner, String room, long size, long uploadedAt) {
        this.id = id;
        this.fileName = fileName;
        this.owner = owner;
        this.room = room == null || room.isBlank() ? "Equipe Alfa" : room;
        this.size = size;
        this.uploadedAt = uploadedAt;
    }

    public String getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getOwner() {
        return owner;
    }

    public String getRoom() {
        return room;
    }

    public long getSize() {
        return size;
    }

    public long getUploadedAt() {
        return uploadedAt;
    }
}

