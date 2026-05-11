package aps.server.data;

import aps.shared.model.FileMetadata;

public record ReportRecord(FileMetadata metadata, String storedName) {
    public ReportRecord {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata obrigatorio");
        }
        storedName = storedName == null ? "" : storedName.trim();
    }
}
