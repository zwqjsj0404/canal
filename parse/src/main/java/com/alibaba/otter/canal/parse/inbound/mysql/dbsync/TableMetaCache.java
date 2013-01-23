package com.alibaba.otter.canal.parse.inbound.mysql.dbsync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import com.alibaba.otter.canal.parse.exception.CanalParseException;
import com.alibaba.otter.canal.parse.inbound.TableMeta;
import com.alibaba.otter.canal.parse.inbound.TableMeta.FieldMeta;
import com.alibaba.otter.canal.parse.inbound.mysql.MysqlConnection;
import com.alibaba.otter.canal.parse.inbound.mysql.networking.packets.server.FieldPacket;
import com.alibaba.otter.canal.parse.inbound.mysql.networking.packets.server.ResultSetPacket;
import com.google.common.base.Function;
import com.google.common.collect.MapMaker;

/**
 * 处理table meta解析和缓存
 * 
 * @author jianghang 2013-1-17 下午10:15:16
 * @version 1.0.0
 */
public class TableMetaCache {

    public static final String     COLUMN_NAME    = "COLUMN_NAME";
    public static final String     COLUMN_TYPE    = "COLUMN_TYPE";
    public static final String     IS_NULLABLE    = "IS_NULLABLE";
    public static final String     COLUMN_KEY     = "COLUMN_KEY";
    public static final String     COLUMN_DEFAULT = "COLUMN_DEFAULT";
    public static final String     EXTRA          = "EXTRA";
    private MysqlConnection        connection;

    // 第一层tableId,第二层schema.table,解决tableId重复，对应多张表
    private Map<String, TableMeta> tableMetaCache;

    public TableMetaCache(MysqlConnection con){
        this.connection = con;
        tableMetaCache = new MapMaker().makeComputingMap(new Function<String, TableMeta>() {

            public TableMeta apply(String name) {
                try {
                    return getTableMeta0(name);
                } catch (IOException e) {
                    // 尝试做一次retry操作
                    if (!connection.isConnected()) {
                        try {
                            connection.connect();
                            return getTableMeta0(name);
                        } catch (IOException e1) {
                        }
                    }
                    throw new CanalParseException("fetch failed by table meta:" + name, e);
                }
            }

        });

    }

    public TableMeta getTableMeta(String fullname) {
        return tableMetaCache.get(fullname);
    }

    public void clearTableMetaWithFullName(String fullname) {
        tableMetaCache.remove(fullname);
    }

    public void clearTableMetaWithSchemaName(String schema) {
        // Set<String> removeNames = new HashSet<String>(); // 存一份临时变量，避免在遍历的时候进行删除
        for (String name : tableMetaCache.keySet()) {
            if (StringUtils.startsWithIgnoreCase(name, schema + ".")) {
                // removeNames.add(name);
                tableMetaCache.remove(name);
            }
        }

        // for (String name : removeNames) {
        // tables.remove(name);
        // }
    }

    public void clearTableMeta() {
        tableMetaCache.clear();
    }

    private TableMeta getTableMeta0(String fullname) throws IOException {
        ResultSetPacket packet = connection.query("desc " + fullname);
        return new TableMeta(fullname, parserTableMeta(packet));
    }

    private List<FieldMeta> parserTableMeta(ResultSetPacket packet) {
        Map<String, Integer> nameMaps = new HashMap<String, Integer>(6, 1f);

        int index = 0;
        for (FieldPacket fieldPacket : packet.getFieldDescriptors()) {
            nameMaps.put(fieldPacket.getOriginalName(), index++);
        }

        int size = packet.getFieldDescriptors().size();
        int count = packet.getFieldValues().size() / packet.getFieldDescriptors().size();
        List<FieldMeta> result = new ArrayList<FieldMeta>();
        for (int i = 0; i < count; i++) {
            FieldMeta meta = new FieldMeta();
            meta.setColumnName(packet.getFieldValues().get(nameMaps.get(COLUMN_NAME) + i * size));
            meta.setColumnType(packet.getFieldValues().get(nameMaps.get(COLUMN_TYPE) + i * size));
            meta.setIsNullable(packet.getFieldValues().get(nameMaps.get(IS_NULLABLE) + i * size));
            meta.setIskey(packet.getFieldValues().get(nameMaps.get(COLUMN_KEY) + i * size));
            meta.setDefaultValue(packet.getFieldValues().get(nameMaps.get(COLUMN_DEFAULT) + i * size));
            meta.setExtra(packet.getFieldValues().get(nameMaps.get(EXTRA) + i * size));

            result.add(meta);
        }

        return result;
    }

}
