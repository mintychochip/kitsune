package org.aincraft.kitsune.storage.metadata;

import org.aincraft.kitsune.api.ContainerLocations;
import org.aincraft.kitsune.Location;
import org.aincraft.kitsune.Platform;
import org.aincraft.kitsune.model.ContainerChunk;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * SQLite storage for container and chunk metadata.
 * Synchronous API - wrap in CompletableFuture at call site if async needed.
 */
public final class ContainerStorage {

    private final DataSource dataSource;
    private final Logger logger;

    public ContainerStorage(DataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    public void initialize() {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("CREATE TABLE IF NOT EXISTS containers (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
            c.createStatement().execute("CREATE TABLE IF NOT EXISTS container_locations (container_id TEXT NOT NULL REFERENCES containers(id) ON DELETE CASCADE, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, is_primary INTEGER DEFAULT 0, PRIMARY KEY (world, x, y, z))");
            c.createStatement().execute("CREATE TABLE IF NOT EXISTS container_chunks (id TEXT PRIMARY KEY, container_id TEXT NOT NULL REFERENCES containers(id) ON DELETE CASCADE, ordinal INTEGER UNIQUE NOT NULL, chunk_index INTEGER NOT NULL, content_text TEXT NOT NULL, timestamp INTEGER NOT NULL, container_path TEXT DEFAULT NULL)");
            c.createStatement().execute("CREATE TABLE IF NOT EXISTS threshold_config (id INTEGER PRIMARY KEY CHECK (id = 1), threshold REAL NOT NULL DEFAULT 0.7)");

            try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM threshold_config")) {
                if (rs.next() && rs.getLong(1) == 0) {
                    c.createStatement().execute("INSERT INTO threshold_config (id, threshold) VALUES (1, 0.7)");
                }
            }

            c.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_cl_cid ON container_locations(container_id)");
            c.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_cc_cid ON container_chunks(container_id)");
            c.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_cc_ord ON container_chunks(ordinal)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize container storage", e);
        }
    }

    public void ensureContainerExists(UUID id) {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM containers WHERE id = ?")) {
                ps.setString(1, id.toString());
                if (!ps.executeQuery().next()) {
                    try (PreparedStatement pi = c.prepareStatement("INSERT INTO containers (id, created_at) VALUES (?, ?)")) {
                        pi.setString(1, id.toString());
                        pi.setLong(2, System.currentTimeMillis());
                        pi.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure container", e);
        }
    }

    public void deleteContainer(UUID id) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM container_chunks WHERE container_id = ?")) {
                    ps.setString(1, id.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM container_locations WHERE container_id = ?")) {
                    ps.setString(1, id.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM containers WHERE id = ?")) {
                    ps.setString(1, id.toString());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed delete container", e);
        }
    }

    public UUID getOrCreateContainer(ContainerLocations locs) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                Location p = locs.primaryLocation();
                UUID ex = getContainerByLocInternal(c, p);
                if (ex != null) {
                    c.commit();
                    return ex;
                }
                UUID nu = UUID.randomUUID();
                ensureContainerExistsInternal(c, nu);
                updateContainerLocsInternal(c, nu, locs);
                c.commit();
                return nu;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed get/create container", e);
        }
    }

    public Optional<UUID> getContainerByLocation(Location l) {
        try (Connection c = dataSource.getConnection()) {
            return Optional.ofNullable(getContainerByLocInternal(c, l));
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    private UUID getContainerByLocInternal(Connection c, Location l) throws SQLException {
        String q = "SELECT container_id FROM container_locations WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, l.getWorld().getName());
            ps.setInt(2, l.blockX());
            ps.setInt(3, l.blockY());
            ps.setInt(4, l.blockZ());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString(1));
            }
        }
        return null;
    }

    public List<Location> getContainerLocations(UUID id) {
        List<Location> r = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z FROM container_locations WHERE container_id = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) r.add(
                    Platform.get().createLocation(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
            }
        } catch (SQLException e) {
            logger.log(java.util.logging.Level.WARNING, "Failed get container locs", e);
        }
        return r;
    }

    public Optional<Location> getPrimaryLocationForContainer(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z FROM container_locations WHERE container_id = ? AND is_primary = 1")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(
                    Platform.get().createLocation(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
            }
        } catch (SQLException e) {
            logger.log(java.util.logging.Level.WARNING, "Failed get primary loc", e);
        }
        return Optional.empty();
    }

    public void registerContainerPositions(ContainerLocations l) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID id = UUID.randomUUID();
                ensureContainerExistsInternal(c, id);
                updateContainerLocsInternal(c, id, l);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed register positions", e);
        }
    }

    private void ensureContainerExistsInternal(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM containers WHERE id = ?")) {
            ps.setString(1, id.toString());
            if (!ps.executeQuery().next()) {
                try (PreparedStatement pi = c.prepareStatement("INSERT INTO containers (id, created_at) VALUES (?, ?)")) {
                    pi.setString(1, id.toString());
                    pi.setLong(2, System.currentTimeMillis());
                    pi.executeUpdate();
                }
            }
        }
    }

    private void updateContainerLocsInternal(Connection c, UUID id, ContainerLocations l) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM container_locations WHERE container_id = ?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
        Location p = l.primaryLocation();
        try (PreparedStatement pi = c.prepareStatement("INSERT INTO container_locations (container_id, world, x, y, z, is_primary) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Location loc : l.allLocations()) {
                pi.setString(1, id.toString());
                pi.setString(2, loc.getWorld().getName());
                pi.setInt(3, loc.blockX());
                pi.setInt(4, loc.blockY());
                pi.setInt(5, loc.blockZ());
                pi.setInt(6, loc.equals(p) ? 1 : 0);
                pi.addBatch();
            }
            pi.executeBatch();
        }
    }

    public Optional<Location> getPrimaryLocation(Location a) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT cl.world, cl.x, cl.y, cl.z FROM container_locations cl JOIN container_locations q ON cl.container_id = q.container_id WHERE q.world = ? AND q.x = ? AND q.y = ? AND q.z = ? AND cl.is_primary = 1")) {
            ps.setString(1, a.getWorld().getName());
            ps.setInt(2, a.blockX());
            ps.setInt(3, a.blockY());
            ps.setInt(4, a.blockZ());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(
                    Platform.get().createLocation(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
            }
        } catch (SQLException e) {
            logger.log(java.util.logging.Level.WARNING, "Failed get primary location", e);
        }
        return Optional.empty();
    }

    public List<Location> getAllPositions(Location p) {
        List<Location> r = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z FROM container_locations WHERE container_id = (SELECT container_id FROM container_locations WHERE world = ? AND x = ? AND y = ? AND z = ? AND is_primary = 1)")) {
            ps.setString(1, p.getWorld().getName());
            ps.setInt(2, p.blockX());
            ps.setInt(3, p.blockY());
            ps.setInt(4, p.blockZ());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) r.add(
                    Platform.get().createLocation(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
            }
        } catch (SQLException e) {
            logger.log(java.util.logging.Level.WARNING, "Failed get all positions", e);
        }
        return r;
    }

    public void deleteContainerPositions(Location p) {
        try (Connection c = dataSource.getConnection()) {
            UUID id = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT container_id FROM container_locations WHERE world = ? AND x = ? AND y = ? AND z = ? AND is_primary = 1")) {
                ps.setString(1, p.getWorld().getName());
                ps.setInt(2, p.blockX());
                ps.setInt(3, p.blockY());
                ps.setInt(4, p.blockZ());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) id = UUID.fromString(rs.getString(1));
                }
            }
            if (id != null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM container_locations WHERE container_id = ?")) {
                    ps.setString(1, id.toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed delete container positions", e);
        }
    }

    // TODO: PERF - Per-row INSERT is inefficient for bulk indexing
    // Current: 100 chunks = 100 separate INSERT statements + connections
    // Fix: Add saveChunksBatch(List<ChunkData>) using single transaction + batch INSERT
    // Example: INSERT INTO ... VALUES (...), (...), (...) with executeBatch()
    public void saveChunk(UUID containerId, UUID chunkId, int ordinal, ContainerChunk chunk) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO container_chunks (id, container_id, ordinal, chunk_index, content_text, timestamp, container_path) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, chunkId.toString());
            ps.setString(2, containerId.toString());
            ps.setInt(3, ordinal);
            ps.setInt(4, chunk.chunkIndex());
            ps.setString(5, chunk.contentText());
            ps.setLong(6, chunk.timestamp());
            String containerPathJson = chunk.containerPath() != null ? chunk.containerPath().toJson() : "[]";
            ps.setString(7, containerPathJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed save chunk", e);
        }
    }

    public void deleteChunksByContainer(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM container_chunks WHERE container_id = ?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed delete chunks", e);
        }
    }

    public List<ChunkMetadata> getChunksByContainer(UUID id) {
        List<ChunkMetadata> r = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, container_id, ordinal, chunk_index, content_text, timestamp, container_path FROM container_chunks WHERE container_id = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) r.add(toChunkMeta(rs));
            }
        } catch (SQLException e) {
            logger.log(java.util.logging.Level.WARNING, "Failed get chunks", e);
        }
        return r;
    }

    public Optional<ChunkMetadata> getChunkById(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, container_id, ordinal, chunk_index, content_text, timestamp, container_path FROM container_chunks WHERE id = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(toChunkMeta(rs));
            }
        } catch (SQLException e) {
            logger.log(java.util.logging.Level.WARNING, "Failed get chunk", e);
        }
        return Optional.empty();
    }

    public Optional<ChunkWithLocation> getChunkByOrdinal(int ordinal) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT cc.id, cc.ordinal, cc.chunk_index, cc.content_text, cc.timestamp, cc.container_path, cl.world, cl.x, cl.y, cl.z FROM container_chunks cc JOIN containers c ON cc.container_id = c.id LEFT JOIN container_locations cl ON c.id = cl.container_id AND cl.is_primary = 1 WHERE cc.ordinal = ?")) {
            ps.setInt(1, ordinal);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(toChunkWithLocation(rs));
                }
            }
        } catch (SQLException e) {
            logger.log(java.util.logging.Level.WARNING, "Failed get chunk by ordinal", e);
        }
        return Optional.empty();
    }

    public List<ChunkWithLocation> getChunksByOrdinals(Set<Integer> ordinals) {
        if (ordinals == null || ordinals.isEmpty()) {
            return new ArrayList<>();
        }

        List<ChunkWithLocation> r = new ArrayList<>();
        String placeholders = ordinals.stream()
            .map(o -> "?")
            .collect(java.util.stream.Collectors.joining(","));

        String query = "SELECT cc.id, cc.ordinal, cc.chunk_index, cc.content_text, cc.timestamp, cc.container_path, cl.world, cl.x, cl.y, cl.z FROM container_chunks cc JOIN containers c ON cc.container_id = c.id LEFT JOIN container_locations cl ON c.id = cl.container_id AND cl.is_primary = 1 WHERE cc.ordinal IN (" + placeholders + ")";

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(query)) {

            int index = 1;
            for (int ordinal : ordinals) {
                ps.setInt(index++, ordinal);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    r.add(toChunkWithLocation(rs));
                }
            }
        } catch (SQLException e) {
            logger.log(java.util.logging.Level.WARNING, "Failed get chunks by ordinals", e);
        }
        return r;
    }

    private ChunkMetadata toChunkMeta(ResultSet rs) throws SQLException {
        return new ChunkMetadata(UUID.fromString(rs.getString(1)), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getLong(6), rs.getString(7));
    }

    private ChunkWithLocation toChunkWithLocation(ResultSet rs) throws SQLException {
        UUID chunkId = UUID.fromString(rs.getString(1));
        int ordinal = rs.getInt(2);
        int chunkIndex = rs.getInt(3);
        String contentText = rs.getString(4);
        long timestamp = rs.getLong(5);
        String containerPath = rs.getString(6);
        String world = rs.getString(7);
        int x = rs.getInt(8);
        int y = rs.getInt(9);
        int z = rs.getInt(10);

        ChunkMetadata metadata = new ChunkMetadata(chunkId, ordinal, chunkIndex, contentText, timestamp, containerPath);
        Location location = Platform.get().createLocation(world, x, y, z);
        return new ChunkWithLocation(metadata, location);
    }

    public List<Integer> getOrdinalsInBoundingBox(String w, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        List<Integer> r = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT DISTINCT cc.ordinal FROM container_chunks cc JOIN containers c ON cc.container_id = c.id LEFT JOIN container_locations cl ON c.id = cl.container_id AND cl.is_primary = 1 WHERE cl.world = ? AND cl.x BETWEEN ? AND ? AND cl.y BETWEEN ? AND ? AND cl.z BETWEEN ? AND ?")) {
            ps.setString(1, w);
            ps.setInt(2, minX);
            ps.setInt(3, maxX);
            ps.setInt(4, minY);
            ps.setInt(5, maxY);
            ps.setInt(6, minZ);
            ps.setInt(7, maxZ);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) r.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            logger.log(java.util.logging.Level.WARNING, "Failed ordinals bbox", e);
        }
        return r;
    }

    public Optional<ChunkWithLocation> getChunkWithLocation(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT cc.id, cc.ordinal, cc.chunk_index, cc.content_text, cc.timestamp, cc.container_path, cl.world, cl.x, cl.y, cl.z FROM container_chunks cc JOIN containers c ON cc.container_id = c.id LEFT JOIN container_locations cl ON c.id = cl.container_id AND cl.is_primary = 1 WHERE cc.id = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(toChunkWithLocation(rs));
                }
            }
        } catch (SQLException e) {
            logger.log(java.util.logging.Level.WARNING, "Failed chunk location", e);
        }
        return Optional.empty();
    }

    public long getChunkCount() {
        try (Connection c = dataSource.getConnection();
             ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM container_chunks")) {
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            logger.log(java.util.logging.Level.WARNING, "Failed chunk count", e);
        }
        return 0L;
    }

    public void purgeAll() {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                c.createStatement().execute("DELETE FROM container_chunks");
                c.createStatement().execute("DELETE FROM container_locations");
                c.createStatement().execute("DELETE FROM containers");
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed purge", e);
        }
    }

    public double getThreshold() {
        try (Connection c = dataSource.getConnection();
             ResultSet rs = c.createStatement().executeQuery("SELECT threshold FROM threshold_config WHERE id = 1")) {
            if (rs.next()) return rs.getDouble(1);
        } catch (SQLException e) {
            logger.log(java.util.logging.Level.WARNING, "Failed get threshold", e);
        }
        return 0.7;
    }

    public void setThreshold(double t) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                boolean exists = c.createStatement().executeQuery("SELECT 1 FROM threshold_config WHERE id = 1").next();
                if (exists) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE threshold_config SET threshold = ? WHERE id = 1")) {
                        ps.setDouble(1, t);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO threshold_config (id, threshold) VALUES (1, ?)")) {
                        ps.setDouble(1, t);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed set threshold", e);
        }
    }

    public void updateOrdinals(Map<Integer, Integer> m) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE container_chunks SET ordinal = ? WHERE ordinal = ?")) {
            c.setAutoCommit(false);
            for (var e : m.entrySet()) {
                if (e.getKey() != e.getValue()) {
                    ps.setInt(1, -e.getKey() - 1);
                    ps.setInt(2, e.getKey());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
            ps.clearBatch();
            for (var e : m.entrySet()) {
                if (e.getKey() != e.getValue()) {
                    ps.setInt(1, e.getValue());
                    ps.setInt(2, -e.getKey() - 1);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed update ordinals", e);
        }
    }

    public void deleteOrphans(Set<Integer> s) {
        try (Connection c = dataSource.getConnection()) {
            if (s.isEmpty()) {
                c.createStatement().execute("DELETE FROM container_chunks");
            } else {
                String ord = s.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
                c.createStatement().execute("DELETE FROM container_chunks WHERE ordinal NOT IN (" + ord + ")");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed delete orphans", e);
        }
    }

    public DataSource getDataSource() {
        return dataSource;
    }
}
