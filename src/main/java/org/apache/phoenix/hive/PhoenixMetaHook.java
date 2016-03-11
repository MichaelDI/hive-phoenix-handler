/**
 * 
 */
package org.apache.phoenix.hive;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.metastore.HiveMetaHook;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.phoenix.hive.constants.PhoenixStorageHandlerConstants;
import org.apache.phoenix.hive.util.PhoenixConnectionUtil;
import org.apache.phoenix.hive.util.PhoenixStorageHandlerUtil;
import org.apache.phoenix.hive.util.PhoenixUtil;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

/**
 * @author JeongMin Ju
 *
 */
public class PhoenixMetaHook implements HiveMetaHook {

	private static final Log LOG = LogFactory.getLog(PhoenixMetaHook.class);
	
	@Override
	public void preCreateTable(Table table) throws MetaException {
		if (LOG.isDebugEnabled()) {
			LOG.debug("<<<<<<<<<< table : " + table.getTableName() + " >>>>>>>>>>");
		}
		
		try (Connection conn = PhoenixConnectionUtil.getConnection(table)) {
			String tableType = table.getTableType();
			String tableName = PhoenixStorageHandlerUtil.getTargetTableName(table);
			
			if (TableType.EXTERNAL_TABLE.name().equals(tableType)) {
				// Check whether phoenix table exists.
				if (!PhoenixUtil.existTable(conn, tableName)) {
					// Error if phoenix table not exist.
					throw new MetaException("<<<<<<<<<< " + tableName + " phoenix table not exist. >>>>>>>>>>");
				}
			} else if (TableType.MANAGED_TABLE.name().equals(tableType)) {
				// Check whether phoenix table exists.
				if (PhoenixUtil.existTable(conn, tableName)) {
					// Error if phoenix table already exist.
					throw new MetaException("<<<<<<<<<< " + tableName + " phoenix table already exist. >>>>>>>>>>");
				}
				
				PhoenixUtil.createTable(conn, createTableStatement(table));
			} else {
				throw new MetaException("<<<<<<<<<< Unsupported table Type: " + table.getTableType() + " >>>>>>>>>>");
			}
			
			if (LOG.isDebugEnabled()) {
				LOG.debug("<<<<<<<<<< " + tableName + " phoenix table created >>>>>>>>>>");
			}
		} catch (SQLException e) {
			throw new MetaException(e.getMessage());
		}
	}

	private String createTableStatement(Table table) throws MetaException {
		Map<String, String> tableParameterMap = table.getParameters();
		
		String tableName = PhoenixStorageHandlerUtil.getTargetTableName(table);
		StringBuilder ddl = new StringBuilder("create table ").append(tableName).append(" (\n");
		
		String phoenixRowKeys = tableParameterMap.get(PhoenixStorageHandlerConstants.PHOENIX_ROWKEYS);
		StringBuilder realRowKeys = new StringBuilder();
		List<String> phoenixRowKeyList = Lists.newArrayList(Splitter.on(PhoenixStorageHandlerConstants.COMMA).trimResults().split(phoenixRowKeys));
		Map<String, String> columnMappingMap = getColumnMappingMap(tableParameterMap.get(PhoenixStorageHandlerConstants.PHOENIX_COLUMN_MAPPING));
		
		List<FieldSchema> fieldSchemaList = table.getSd().getCols();
		for (int i = 0, limit = fieldSchemaList.size(); i < limit; i++) {
			FieldSchema fieldSchema = fieldSchemaList.get(i);
			String fieldName = fieldSchema.getName();
			String fieldType = fieldSchema.getType();
			String columnType = PhoenixUtil.getPhoenixType(fieldType);
			
			String rowKeyName = getRowKeyMapping(fieldName, phoenixRowKeyList);
			if (rowKeyName != null) {
				// In case of RowKey
				if ("binary".equals(columnType)) {
					// Phoenix must define max length of binary when type definition. Obtaining information from the column mapping. ex) phoenix.rowkeys = "r1, r2(100), ..."
					List<String> tokenList = Lists.newArrayList(Splitter.on(CharMatcher.is('(').or(CharMatcher.is(')'))).trimResults().split(rowKeyName));
					columnType = columnType + "(" + tokenList.get(1) + ")";
					rowKeyName = tokenList.get(0);
				}
				
				ddl.append("  ").append(rowKeyName).append(" ").append(columnType).append(" not null,\n");
				realRowKeys.append(rowKeyName).append(",");
			} else {
				// In case of Column
				String columnName = columnMappingMap.get(fieldName);
				
				if (columnName == null) {
					// Use field definition.
					columnName = fieldName;
//					throw new MetaException("<<<<<<<<<< " + fieldName + " column mapping not exist. >>>>>>>>>>");
				}
				
				if ("binary".equals(columnType)) {
					// Phoenix must define max length of binary when type definition. Obtaining information from the column mapping. ex) phoenix.column.mapping=c1:c1(100)
					List<String> tokenList = Lists.newArrayList(Splitter.on(CharMatcher.is('(').or(CharMatcher.is(')'))).trimResults().split(columnName));
					columnType = columnType + "(" + tokenList.get(1) + ")";
					columnName = tokenList.get(0);
				}
				
				ddl.append("  ").append(columnName).append(" ").append(columnType).append(",\n");
			}
		}
		ddl.append("  ").append("constraint pk_").append(tableName).append(" primary key(").append(realRowKeys.deleteCharAt(realRowKeys.length() - 1)).append(")\n)\n");
		
		String tableOptions = tableParameterMap.get(PhoenixStorageHandlerConstants.PHOENIX_TABLE_OPTIONS);
		if (tableOptions != null) {
			ddl.append(tableOptions);
		}
		
		String statement = ddl.toString();
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("<<<<<<<<<< DDL : " + statement + " >>>>>>>>>>");
		}
		
		return statement;
	}
	
	private String getRowKeyMapping(String rowKeyName, List<String> phoenixRowKeyList) {
		String rowKeyMapping = null;
		
		for (String phoenixRowKey : phoenixRowKeyList) {
			if (phoenixRowKey.equals(rowKeyName)) {
				rowKeyMapping = phoenixRowKey;
				break;
			} else if (phoenixRowKey.startsWith(rowKeyName + "(") && phoenixRowKey.endsWith(")")) {
				rowKeyMapping = phoenixRowKey;
				break;
			}
		}
		
		return rowKeyMapping;
	}
	
	private Map<String, String> getColumnMappingMap(String columnMappings) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("<<<<<<<<<< column mappings : " + columnMappings + " >>>>>>>>>>");
		}
		
		if (columnMappings == null) {
			if (LOG.isInfoEnabled()) {
				LOG.info("<<<<<<<<<< phoenix.column.mapping not set. using field definition >>>>>>>>>>");
			}
			
			return Collections.emptyMap();
		}
		
		Map<String, String> columnMappingMap = Splitter.on(PhoenixStorageHandlerConstants.COMMA).trimResults().withKeyValueSeparator(PhoenixStorageHandlerConstants.COLON).split(columnMappings);
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("<<<<<<<<<< column mapping map : " + columnMappingMap + " >>>>>>>>>>");
		}
		
		return columnMappingMap;
	}
	
	@Override
	public void rollbackCreateTable(Table table) throws MetaException {
		if (LOG.isDebugEnabled()) {
			LOG.debug("<<<<<<<<<< table : " + table.getTableName() + " >>>>>>>>>>");
		}
		
		dropTableIfExist(table);
	}

	@Override
	public void commitCreateTable(Table table) throws MetaException {
		
	}

	@Override
	public void preDropTable(Table table) throws MetaException {
	}

	@Override
	public void rollbackDropTable(Table table) throws MetaException {
	}

	@Override
	public void commitDropTable(Table table, boolean deleteData) throws MetaException {
		if (LOG.isDebugEnabled()) {
			LOG.debug("<<<<<<<<<< table : " + table.getTableName() + " >>>>>>>>>>");
		}
		
		dropTableIfExist(table);
	}

	private void dropTableIfExist(Table table) throws MetaException {
		try (Connection conn = PhoenixConnectionUtil.getConnection(table)) {
			String tableType = table.getTableType();
			String tableName = PhoenixStorageHandlerUtil.getTargetTableName(table);
			
			if (TableType.MANAGED_TABLE.name().equals(tableType)) {
				// Drop if phoenix table exist.
				if (PhoenixUtil.existTable(conn, tableName)) {
					PhoenixUtil.dropTable(conn, tableName);
				}
			}
		} catch (SQLException e) {
			throw new MetaException(e.getMessage());
		}
	}
}
