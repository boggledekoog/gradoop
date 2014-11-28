package org.gradoop.storage.hbase;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.log4j.Logger;
import org.gradoop.GConstants;
import org.gradoop.storage.GraphStore;

import java.io.IOException;

/**
 * Creates {@link org.gradoop.storage.GraphStore} instances which can be used to
 * store an EPG in HBase.
 */
public class HBaseGraphStoreFactory {

  /**
   * Logger
   */
  private static final Logger LOG =
    Logger.getLogger(HBaseGraphStoreFactory.class);

  /**
   * Private constructor to avoid instantiation.
   */
  private HBaseGraphStoreFactory() {
  }

  /**
   * Creates a graph store based on the given parameters.
   *
   * @param config          Hadoop configuration
   * @param verticesHandler vertex storage handler
   * @param graphsHandler   graph storage handler
   * @return a graph store instance
   */
  public static GraphStore createGraphStore(Configuration config,
                                            VertexHandler verticesHandler,
                                            GraphHandler graphsHandler) {
    HTable graphsTable = null;
    HTable verticesTable = null;

    try {
      createTablesIfNotExists(config, verticesHandler);

      graphsTable = new HTable(config, GConstants.DEFAULT_TABLE_GRAPHS);
      verticesTable = new HTable(config, GConstants.DEFAULT_TABLE_VERTICES);
    } catch (IOException e) {
      e.printStackTrace();
    }

    return new HBaseGraphStore(graphsTable, verticesTable, verticesHandler,
      graphsHandler);
  }

  /**
   * Deletes the graph store based on the given config.
   *
   * @param config Hadoop configuration
   */
  public static void deleteGraphStore(Configuration config) {
    try {
      deleteTablesIfExists(config);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Creates the tables used by the graph store.
   *
   * @param config          Hadoop configuration
   * @param verticesHandler vertex storage handler
   * @throws IOException
   */
  private static void createTablesIfNotExists(Configuration config,
                                              VertexHandler verticesHandler)
    throws IOException {
    HTableDescriptor verticesTableDescriptor =
      new HTableDescriptor(
        TableName.valueOf(GConstants.DEFAULT_TABLE_VERTICES));
    HTableDescriptor graphsTableDescriptor =
      new HTableDescriptor(TableName.valueOf(GConstants.DEFAULT_TABLE_GRAPHS));

    HBaseAdmin admin = new HBaseAdmin(config);

    if (!admin.tableExists(verticesTableDescriptor.getName())) {
      verticesHandler.createVerticesTable(admin, verticesTableDescriptor);
    }
    if (!admin.tableExists(graphsTableDescriptor.getName())) {
      createGraphsTable(admin, graphsTableDescriptor);
    }

    admin.close();
  }

  /**
   * Creates the table used for storing graphs.
   *
   * @param admin           HBase admin
   * @param tableDescriptor descriptor for the graphs table
   * @throws IOException
   */
  private static void createGraphsTable(HBaseAdmin admin,
                                        HTableDescriptor tableDescriptor)
    throws IOException {
    LOG.info("creating table " + tableDescriptor.getNameAsString());
    tableDescriptor.addFamily(new HColumnDescriptor(GConstants.CF_LABELS));
    tableDescriptor
      .addFamily(new HColumnDescriptor(GConstants.CF_PROPERTIES));
    tableDescriptor
      .addFamily(new HColumnDescriptor(GConstants.CF_VERTICES));
    admin.createTable(tableDescriptor);
  }

  /**
   * Deletes the tables defined by the config.
   *
   * @param config Hadoop configuration
   * @throws IOException
   */
  private static void deleteTablesIfExists(Configuration config)
    throws IOException {
    HTableDescriptor verticesTableDescriptor =
      new HTableDescriptor(
        TableName.valueOf(GConstants.DEFAULT_TABLE_VERTICES));
    HTableDescriptor graphsTableDescriptor =
      new HTableDescriptor(TableName.valueOf(GConstants.DEFAULT_TABLE_GRAPHS));

    HBaseAdmin admin = new HBaseAdmin(config);

    if (admin.tableExists(verticesTableDescriptor.getName())) {
      deleteTable(admin, verticesTableDescriptor);
    }

    if (admin.tableExists(graphsTableDescriptor.getName())) {
      deleteTable(admin, graphsTableDescriptor);
    }

    admin.close();
  }

  /**
   * Deletes a single EPG table.
   *
   * @param admin           HBase admin
   * @param tableDescriptor descriptor for the table to delete
   * @throws IOException
   */
  private static void deleteTable(HBaseAdmin admin,
                                  HTableDescriptor tableDescriptor)
    throws IOException {
    LOG.info("deleting table: " + tableDescriptor.getNameAsString());
    admin.disableTable(tableDescriptor.getName());
    admin.deleteTable(tableDescriptor.getName());
  }
}
