package de.dytanic.cloudnet.driver.network.protocol.chunk.client;

import de.dytanic.cloudnet.common.document.gson.JsonDocument;
import de.dytanic.cloudnet.driver.network.protocol.chunk.ChunkedPacket;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;

public class ClientChunkedPacketSession {

    private final ChunkedPacketListener listener;
    private final OutputStream outputStream;
    private final Collection<ChunkedPacket> pendingPackets = new ArrayList<>();
    private final Map<String, Object> properties;
    private final UUID sessionUniqueId;

    private int chunkId = 0;
    private JsonDocument header = JsonDocument.EMPTY;
    private volatile boolean closed;

    public ClientChunkedPacketSession(ChunkedPacketListener listener, UUID sessionUniqueId, OutputStream outputStream, Map<String, Object> properties) {
        this.listener = listener;
        this.sessionUniqueId = sessionUniqueId;
        this.outputStream = outputStream;
        this.properties = properties;
    }

    public void handlePacket(@NotNull ChunkedPacket packet) throws IOException {
        if (this.closed) {
            packet.clearData();
            throw new IllegalStateException(String.format("Session is already closed but received packet %d, %b", packet.getChunkId(), packet.isEnd()));
        }

        if (this.header.isEmpty() && !packet.getHeader().isEmpty()) {
            this.header = packet.getHeader();
        }

        try {
            if (this.chunkId != packet.getChunkId()) {
                this.pendingPackets.add(packet);
            } else {
                this.handlePacket0(packet);
            }
        } finally {
            this.checkPendingPackets();
        }
    }

    private void handlePacket0(ChunkedPacket packet) throws IOException {
        if (this.closed) {
            return;
        }

        if (packet.getChunkId() == 0) { // Ignore first packet because it has no data we need
            ++this.chunkId;
            return;
        }

        if (packet.isEnd()) {
            this.close();
            return;
        }

        ++this.chunkId;

        try {
            packet.readData(this.outputStream);
        } finally {
            this.outputStream.flush();
            packet.clearData();
        }
    }

    private void checkPendingPackets() throws IOException {
        if (!this.pendingPackets.isEmpty()) {
            Iterator<ChunkedPacket> iterator = this.pendingPackets.iterator();
            while (iterator.hasNext()) {
                ChunkedPacket pending = iterator.next();
                if (this.chunkId == pending.getChunkId() || (pending.isEnd() && this.chunkId - 1 == pending.getChunks())) {
                    iterator.remove();
                    this.handlePacket0(pending);
                }
            }
        }
    }

    protected void close() throws IOException {
        if (!this.pendingPackets.isEmpty()) {
            String packets = this.pendingPackets.stream().map(ChunkedPacket::getChunkId).map(String::valueOf).collect(Collectors.joining(", "));
            throw new IllegalStateException(String.format("Closing with %d pending packets: %s", this.pendingPackets.size(), packets));
        }

        this.closed = true;
        this.outputStream.close();

        System.gc(); // Cleanup please!

        this.listener.getSessions().remove(this.sessionUniqueId);
        this.listener.handleComplete(this);
    }

    public OutputStream getOutputStream() {
        return this.outputStream;
    }

    public JsonDocument getHeader() {
        return this.header;
    }

    public boolean isClosed() {
        return this.closed;
    }

    public Map<String, Object> getProperties() {
        return this.properties;
    }

    public int getCurrentChunkId() {
        return this.chunkId;
    }
}
