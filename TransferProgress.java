package aps.client.net;

@FunctionalInterface
public interface TransferProgress {
    void onProgress(long transferred, long total);
}