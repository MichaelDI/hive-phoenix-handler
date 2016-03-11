/**
 * 
 */
package org.apache.phoenix.hive.util;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.phoenix.coprocessor.MetaDataProtocol.MetaDataMutationResult;
import org.apache.phoenix.hive.constants.PhoenixStorageHandlerConstants;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.schema.MetaDataClient;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.TableNotFoundException;
import org.apache.phoenix.util.ColumnInfo;
import org.apache.phoenix.util.PhoenixRuntime;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * @author JeongMin Ju
 *
 */
public class PhoenixUtil {

	private static final Log LOG = LogFactory.getLog(PhoenixUtil.class);
	
	public static String getPhoenixType(String hiveTypeName) {
		if (hiveTypeName.startsWith("array")) {
			List<String> tokenList = Lists.newArrayList(Splitter.on(CharMatcher.is('<').or(CharMatcher.is('>'))).split(hiveTypeName));
			return getPhoenixType(tokenList.get(1)) + "[]";
		} else if (hiveTypeName.startsWith("int")) {
			return "integer";
		} else if (hiveTypeName.equals("string")) {
			return "varchar";
		} else {
			return hiveTypeName;
		}
	}
	
	public static boolean existTable(Connection conn, String tableName) throws SQLException {
		boolean exist = false;
		DatabaseMetaData dbMeta = conn.getMetaData();
        
        try (ResultSet rs = dbMeta.getTables(null, null, tableName.toUpperCase(), null)) {
        	exist = rs.next();
        	
        	if (LOG.isDebugEnabled()) {
	        	if (exist) {
	        		LOG.debug("<<<<<<<<<< " + rs.getString("TABLE_NAME") + " table exist. >>>>>>>>>>");
	        	} else {
	        		LOG.debug("<<<<<<<<<< " + tableName + " table not exist. >>>>>>>>>>");
	        	}
        	}
        }
        
        return exist;
	}
	
	public static List<String> getPrimaryKeyColumnList(Connection conn, String tableName) throws SQLException {
		Map<Short, String> primaryKeyColumnInfoMap = Maps.newHashMap();
		DatabaseMetaData dbMeta = conn.getMetaData();
		
		try (ResultSet rs = dbMeta.getPrimaryKeys(null, null, tableName.toUpperCase())) {
			while (rs.next()) {
				primaryKeyColumnInfoMap.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
			}
        	
        	if (LOG.isDebugEnabled()) {
        		LOG.debug("<<<<<<<<<< pk-columns : " + primaryKeyColumnInfoMap + " >>>>>>>>>>");
        	}
        }
        
        return Lists.newArrayList(primaryKeyColumnInfoMap.values());
	}
	
	public static List<String> getPrimaryKeyColumnList(Configuration config, String tableName) {
		List<String> pkColumnNameList = null;
		
		try (Connection conn = PhoenixConnectionUtil.getInputConnection(config, new Properties())) {
			pkColumnNameList = getPrimaryKeyColumnList(conn, tableName);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		
		return pkColumnNameList;
	}
	
	public static void createTable(Connection conn, String createTableStatement) throws SQLException {
		conn.createStatement().execute(createTableStatement);
	}
	
	public static void dropTable(Connection conn, String tableName) throws SQLException {
		conn.createStatement().execute("drop table " + tableName);
	}
	
	public static List<ColumnInfo> getColumnInfoList(Connection conn, String tableName) throws SQLException {
		List<ColumnInfo> columnInfoList = null;
		
		try {
			columnInfoList = PhoenixRuntime.generateColumnInfo(conn, tableName, null);
		} catch (TableNotFoundException e) {
			// create table시에 Exception이 발생될 수 있음.
			columnInfoList = Collections.emptyList();
		}
		
		return columnInfoList;
	}
	
	/**
	 * 
	 * @param tableName
	 * @return
	 */
	public static String[] getTableSchema(String tableName) {
		String[] schemaInfo = new String[2];
		String[] tokens = tableName.split("\\.");
		
		if (tokens.length == 2) {
			schemaInfo = tokens;
		} else {
			schemaInfo[1] = tokens[0];
		}
		
		return schemaInfo;
	}
	
	public static boolean isDisabledWal(MetaDataClient metaDataClient, String tableName) throws SQLException {
		String[] schemaInfo = getTableSchema(tableName.toUpperCase());
        MetaDataMutationResult result = metaDataClient.updateCache(schemaInfo[0], schemaInfo[1]);
        PTable dataTable = result.getTable();
        
        return dataTable.isWALDisabled();
	}
	
	public static void alterTableForWalDisable(Connection conn, String tableName, boolean disableMode) throws SQLException {
		conn.createStatement().execute("alter table " + tableName + " set disable_wal=" + disableMode);
	}
	
	public static void flush(Connection conn, String tableName) throws SQLException {
		try (HBaseAdmin admin = ((PhoenixConnection)conn).getQueryServices().getAdmin()) {
			admin.flush(TableName.valueOf(tableName));
		} catch (IOException e) {
			throw new SQLException(e);
		}
	}
	
	public static String constructDeleteStatement(Connection conn, String tableName) throws SQLException {
		StringBuilder deleteQuery = new StringBuilder("delete from ").append(tableName).append(" where ");
		
		List<String> primaryKeyColumnList = getPrimaryKeyColumnList(conn, tableName);
		for (int i = 0, limit = primaryKeyColumnList.size(); i < limit; i++) {
			String pkColumn = primaryKeyColumnList.get(i);
			deleteQuery.append(pkColumn).append(PhoenixStorageHandlerConstants.EQUAL).append(PhoenixStorageHandlerConstants.QUESTION);
			
			if ((i + 1) != primaryKeyColumnList.size()) {
				deleteQuery.append(" and ");
			}
		}
		
		return deleteQuery.toString();
	}
	
	public static void closeResource(Statement stmt) throws SQLException {
		if (stmt != null && !stmt.isClosed()) {
			stmt.close();
		}
	}
	
	public static void closeResource(Connection conn) throws SQLException {
		if (conn != null && !conn.isClosed()) {
			conn.close();
		}
	}
}
