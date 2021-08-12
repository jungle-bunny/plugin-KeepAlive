package keepalive.repository;

import keepalive.Plugin;
import keepalive.model.Block;

import java.net.MalformedURLException;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;

import freenet.keys.FreenetURI;

public class BlockRepository {

    private final Plugin plugin;

    private static BlockRepository instance;

    private static final String SQL_SAVE = "INSERT INTO Block (uri, file_id, data) VALUES (?, ?, ?)";
    private static final String SQL_FIND = "SELECT data FROM Block WHERE uri = ?";
    private static final String SQL_UPDATE = "UPDATE Block SET data = ?, file_id = ? WHERE uri = ?";
    private static final String SQL_DELETE = "DELETE FROM Block WHERE uri = ?";
    private static final String SQL_LAST_ACCESS_DIFF = "SELECT TIMESTAMPDIFF(MILLISECOND, last_access, CURRENT_TIMESTAMP) FROM Block WHERE uri = ?";
    private static final String SQL_LAST_ACCESS_UPDATE = "UPDATE Block SET last_access = CURRENT_TIMESTAMP WHERE uri = ?";
    private static final String SQL_FIND_BY_FILE_ID = "SELECT uri, id, segment_id, is_data FROM Block Where file_id = ?";
    private static final String SQL_SAVE_ALL = "INSERT INTO Block (uri, id, file_id, segment_id, is_data) VALUES (?, ?, ?, ?, ?)";
    private static final String SQL_DELETE_BY_FILE_ID = "DELETE FROM Block WHERE file_id = ?";

    private BlockRepository(Plugin plugin) {
        this.plugin = plugin;
    }

    public static synchronized BlockRepository getInstance(Plugin plugin) {
        if (instance == null) {
            instance = new BlockRepository(plugin);
        }
        return instance;
    }
    
    public void saveOrUpdate(String uri, byte[] data, int fileId) {
        try (Connection connection = DB.getConnection();
             PreparedStatement findPreparedStatement = connection.prepareStatement(SQL_FIND)) {
            findPreparedStatement.setString(1, uri);
            ResultSet resultSet = findPreparedStatement.executeQuery();

            if (resultSet.next()) {
                try (PreparedStatement updatePreparedStatement = connection.prepareStatement(SQL_UPDATE)) {
                    updatePreparedStatement.setBytes(1, data);
                    updatePreparedStatement.setInt(2, fileId);
                    updatePreparedStatement.setString(3, uri);
                    updatePreparedStatement.executeUpdate();
                }
            } else {
                try (PreparedStatement savePreparedStatement = connection.prepareStatement(SQL_SAVE)) {
                    savePreparedStatement.setString(1, uri);
                    savePreparedStatement.setInt(2, fileId);
                    savePreparedStatement.setBytes(3, data);
                    savePreparedStatement.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.log(e.getMessage(), e);
        }
    }

    public byte[] findOne(String uri) {
        try (Connection connection = DB.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(SQL_FIND)) {
            preparedStatement.setString(1, uri);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                byte[] data = resultSet.getBytes("data");

                if (resultSet.next()) {
                    plugin.log("Not unique uri: " + uri);
                    return null;
                }

                return data;
            } else {
                return null;
            }
        } catch (SQLException e) {
            plugin.log(e.getMessage() + " " + uri, e);
        }

        return null;
    }

    public void delete(String uri) {
        try (Connection connection = DB.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(SQL_DELETE)) {
            preparedStatement.setString(1, uri);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            plugin.log(e.getMessage() + " " + uri, e);
        }
    }

    public long lastAccessDiff(String uri) {
        try (Connection connection = DB.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(SQL_LAST_ACCESS_DIFF)) {
            preparedStatement.setString(1, uri);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                long diff = resultSet.getLong(1);

                if (resultSet.next()) {
                    plugin.log("Not unique uri: " + uri);
                    return 0;
                }

                return diff;
            } else {
                return 0;
            }
        } catch (SQLException e) {
            plugin.log(e.getMessage() + " " + uri, e);
        }

        return 0;
    }

    public void lastAccessUpdate(String uri) {
        try (Connection connection = DB.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(SQL_LAST_ACCESS_UPDATE)) {
            preparedStatement.setString(1, uri);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            plugin.log(e.getMessage() + " " + uri, e);
        }
    }
    
    public Collection<Block> loadByFileID(int fileId) {
        Collection<Block> blocks = new ArrayList<Block>();
        try (Connection connection = DB.getConnection();
                PreparedStatement findPreparedStatement = connection.prepareStatement(SQL_FIND_BY_FILE_ID)) {
               findPreparedStatement.setInt(1, fileId);
               ResultSet resultSet = findPreparedStatement.executeQuery();

               while (resultSet.next()) {
                   blocks.add(new Block(new FreenetURI(resultSet.getString("uri")),
                           resultSet.getInt("segment_id"),
                           resultSet.getInt("id"),
                           resultSet.getBoolean("is_data")));
               }
        } catch (Exception e) {
            plugin.log(e.getMessage() + " " + fileId, e);
        }
        
        return blocks;
    }

    public void saveAll(Collection<Block> blocks, int fileId) {
        try (Connection conn = DB.getConnection();
                PreparedStatement stmt = conn.prepareStatement(SQL_SAVE_ALL)) {
            for (Block block : blocks) {
                stmt.clearParameters();
                stmt.setString(1, block.getUri().toString());
                stmt.setInt(2, block.getId());
                stmt.setInt(3, fileId);
                stmt.setInt(4, block.getSegmentId());
                stmt.setBoolean(5, block.isDataBlock());
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            plugin.log(e.getMessage(), e);            
        }        
    }

    public void deleteByFileID(int fileId) {
        try (Connection conn = DB.getConnection();
                PreparedStatement stmt = conn.prepareStatement(SQL_DELETE_BY_FILE_ID)) {
            stmt.setInt(1, fileId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.log(e.getMessage() + " " + fileId, e);
        }
    }
}
