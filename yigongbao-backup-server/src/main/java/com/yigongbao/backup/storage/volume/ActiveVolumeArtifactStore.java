package com.yigongbao.backup.storage.volume;

import com.yigongbao.backup.storage.api.ArtifactStore;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Compatibility facade for code that has not yet been given a concrete volume ID.
 * It resolves the active HOT volume at every operation, never at Spring startup.
 */
public final class ActiveVolumeArtifactStore implements ArtifactStore {
    private final ActiveVolumeSelectionService selection;
    private final VolumeArtifactStore volumes;

    public ActiveVolumeArtifactStore(ActiveVolumeSelectionService selection, VolumeArtifactStore volumes) {
        this.selection = selection;
        this.volumes = volumes;
    }

    @Override public Path put(String key, InputStream content) throws IOException { return volumes.put(activeId(), key, content); }
    @Override public InputStream open(String key) throws IOException { return volumes.open(activeId(), key); }
    @Override public Stream<Path> list(String key) throws IOException { return volumes.list(activeId(), key); }
    @Override public void move(String source, String target) throws IOException { volumes.move(activeId(), source, target); }
    @Override public void delete(String key) throws IOException { volumes.delete(activeId(), key); }
    @Override public Path root() { return Path.of(selection.requireActiveHotVolume().getRootPath()); }

    private long activeId() { return selection.requireActiveHotVolume().getId(); }
}
