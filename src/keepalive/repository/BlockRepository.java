package keepalive.repository;

import keepalive.Plugin;

import java.sql.*;

public class BlockRepository {

    private final Plugin plugin;

    private static BlockRepository instance;

    private static final String SQL_SAVE = "INSERT INTO `Block` VALUES (?, ?)";
    private static final String SQL_FIND = "SELECT DATA FROM `Block` WHERE `uri` = ?";

    private BlockRepository(Plugin plugin) {
        this.plugin = plugin;
    }

    public static synchronized BlockRepository getInstance(Plugin plugin) {
        if (instance == null) {
            instance = new BlockRepository(plugin);
        }
        return instance;
    }

    public void saveOrUpdate(String uri, byte[] data) {
        // TODO: process update branch
        try (Connection connection = DB.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(SQL_SAVE)) {
            preparedStatement.setString(1, uri);
            preparedStatement.setBytes(2, data);
            preparedStatement.executeUpdate();
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
}
