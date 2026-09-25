package dev.supplydrops;

import com.google.gson.Gson;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;

/** A single writer orders all durable checkpoints; callers snapshot on the main thread. */
public final class Store implements AutoCloseable {
  public static final Gson JSON = new Gson();
  private final ExecutorService writer =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "SupplyDrops-storage"));
  private final Connection connection;

  public Store(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    Class.forName("org.sqlite.JDBC");
    connection = DriverManager.getConnection("jdbc:sqlite:" + path);
    try (Statement s = connection.createStatement()) {
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA synchronous=FULL");
      s.execute("CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY, json TEXT NOT NULL)");
    }
  }

  public <T> T read(String id, Class<T> type, T fallback) throws SQLException {
    try (PreparedStatement s =
        connection.prepareStatement("SELECT json FROM documents WHERE id=?")) {
      s.setString(1, id);
      try (ResultSet rs = s.executeQuery()) {
        return rs.next() ? JSON.fromJson(rs.getString(1), type) : fallback;
      }
    }
  }

  public CompletableFuture<Void> save(String id, Object object) {
    String snapshot = JSON.toJson(object);
    return CompletableFuture.runAsync(
        () -> {
          try (PreparedStatement s =
              connection.prepareStatement(
                  "INSERT INTO documents(id,json) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET"
                      + " json=excluded.json")) {
            s.setString(1, id);
            s.setString(2, snapshot);
            s.executeUpdate();
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
        },
        writer);
  }

  public static <T> T copy(T value, Class<T> type) {
    return JSON.fromJson(JSON.toJson(value), type);
  }

  @Override
  public void close() throws Exception {
    writer.shutdown();
    if (!writer.awaitTermination(30, TimeUnit.SECONDS))
      throw new IllegalStateException("Storage did not finish");
    connection.close();
  }
}
