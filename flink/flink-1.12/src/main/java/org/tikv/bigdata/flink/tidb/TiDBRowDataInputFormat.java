/*
 * Copyright 2020 TiKV Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tikv.bigdata.flink.tidb;

import static org.tikv.bigdata.flink.tidb.TiDBDynamicTableFactory.DATABASE_NAME;
import static org.tikv.bigdata.flink.tidb.TiDBDynamicTableFactory.TABLE_NAME;
import static org.tikv.bigdata.flink.tidb.TypeUtils.getObjectWithDataType;
import static org.tikv.bigdata.flink.tidb.TypeUtils.toRowDataType;
import static org.tikv.bigdata.tidb.ClientConfig.SNAPSHOT_TIMESTAMP;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.common.io.DefaultInputSplitAssigner;
import org.apache.flink.api.common.io.RichInputFormat;
import org.apache.flink.api.common.io.statistics.BaseStatistics;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.GenericInputSplit;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.core.io.InputSplitAssigner;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.DataType;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tikv.bigdata.tidb.ClientConfig;
import org.tikv.bigdata.tidb.ClientSession;
import org.tikv.bigdata.tidb.ColumnHandleInternal;
import org.tikv.bigdata.tidb.RecordCursorInternal;
import org.tikv.bigdata.tidb.RecordSetInternal;
import org.tikv.bigdata.tidb.SplitInternal;
import org.tikv.bigdata.tidb.SplitManagerInternal;
import org.tikv.bigdata.tidb.TableHandleInternal;
import org.tikv.common.expression.Expression;
import org.tikv.common.meta.TiTimestamp;

public class TiDBRowDataInputFormat extends RichInputFormat<RowData, InputSplit> implements
    ResultTypeQueryable<RowData> {

  static final Logger LOG = LoggerFactory.getLogger(TiDBRowDataInputFormat.class);

  private static final String TIMESTAMP_FORMAT_PREFIX = "timestamp-format";

  private final Map<String, String> properties;

  private final String databaseName;

  private final String tableName;

  private final String[] fieldNames;

  private final DataType[] fieldTypes;

  private final TypeInformation<RowData> typeInformation;

  private final List<SplitInternal> splits;

  private final List<ColumnHandleInternal> columnHandleInternals;

  private final TiTimestamp timestamp;

  private long limit = Long.MAX_VALUE;

  private long recordCount;

  private int[] projectedFieldIndexes;

  private Expression expression;

  private transient DateTimeFormatter[] formatters;

  private transient RecordCursorInternal cursor;

  private transient ClientSession clientSession;

  public TiDBRowDataInputFormat(Map<String, String> properties, String[] fieldNames,
      DataType[] fieldTypes, TypeInformation<RowData> typeInformation) {
    this.properties = Preconditions.checkNotNull(properties, "properties can not be null");
    this.databaseName = getRequiredProperties(DATABASE_NAME.key());
    this.tableName = getRequiredProperties(TABLE_NAME.key());
    this.fieldNames = fieldNames;
    this.fieldTypes = fieldTypes;
    this.typeInformation = typeInformation;
    // get split
    try (ClientSession splitSession = ClientSession
        .createWithSingleConnection(new ClientConfig(properties))) {
      // check exist
      splitSession.getTableMust(databaseName, tableName);
      TableHandleInternal tableHandleInternal = new TableHandleInternal(
          UUID.randomUUID().toString(), this.databaseName, this.tableName);
      SplitManagerInternal splitManagerInternal = new SplitManagerInternal(splitSession);
      splits = splitManagerInternal.getSplits(tableHandleInternal);
      columnHandleInternals = splitSession.getTableColumns(tableHandleInternal)
          .orElseThrow(() -> new NullPointerException("columnHandleInternals is null"));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    projectedFieldIndexes = IntStream.range(0, fieldNames.length).toArray();
    timestamp = Optional
        .ofNullable(properties.get(SNAPSHOT_TIMESTAMP))
        .filter(StringUtils::isNoneEmpty)
        .map(s -> new TiTimestamp(Timestamp.from(ZonedDateTime.parse(s).toInstant()).getTime(), 0))
        .orElse(null);
  }

  @Override
  public void configure(Configuration parameters) {
    // do nothing here
  }

  @Override
  public BaseStatistics getStatistics(BaseStatistics cachedStatistics) throws IOException {
    return cachedStatistics;
  }

  @Override
  public InputSplit[] createInputSplits(int minNumSplits) throws IOException {
    GenericInputSplit[] inputSplits = new GenericInputSplit[splits.size()];
    for (int i = 0; i < inputSplits.length; i++) {
      inputSplits[i] = new GenericInputSplit(i, inputSplits.length);
    }
    return inputSplits;
  }

  @Override
  public InputSplitAssigner getInputSplitAssigner(InputSplit[] inputSplits) {
    return new DefaultInputSplitAssigner(inputSplits);
  }

  @Override
  public void openInputFormat() throws IOException {
    formatters = Arrays.stream(fieldNames).map(name -> {
      String pattern = properties.get(TIMESTAMP_FORMAT_PREFIX + "." + name);
      return pattern == null ? null : DateTimeFormatter.ofPattern(pattern);
    }).toArray(DateTimeFormatter[]::new);
    clientSession = ClientSession.createWithSingleConnection(new ClientConfig(properties));
  }

  @Override
  public void closeInputFormat() throws IOException {
    if (clientSession != null) {
      try {
        clientSession.close();
      } catch (Exception e) {
        LOG.warn("can not close clientSession", e);
      }
    }
  }

  @Override
  public void open(InputSplit split) throws IOException {
    if (recordCount >= limit) {
      return;
    }
    SplitInternal splitInternal = splits.get(split.getSplitNumber());
    RecordSetInternal recordSetInternal = new RecordSetInternal(clientSession, splitInternal,
        Arrays.stream(projectedFieldIndexes).mapToObj(columnHandleInternals::get)
            .collect(Collectors.toList()),
        Optional.ofNullable(expression),
        Optional.ofNullable(timestamp),
        limit > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) limit);
    cursor = recordSetInternal.cursor();
  }

  @Override
  public void close() throws IOException {
    if (cursor != null) {
      cursor.close();
      cursor = null;
    }
  }

  @Override
  public boolean reachedEnd() throws IOException {
    return recordCount >= limit || !cursor.advanceNextPosition();
  }

  @Override
  public RowData nextRecord(RowData rowData) throws IOException {
    GenericRowData row = new GenericRowData(projectedFieldIndexes.length);
    for (int i = 0; i < projectedFieldIndexes.length; i++) {
      int projectedFieldIndex = projectedFieldIndexes[i];
      DataType fieldType = fieldTypes[projectedFieldIndex];
      Object object = cursor.getObject(i);
      // data can be null here
      row.setField(i, toRowDataType(
          getObjectWithDataType(object, fieldType, formatters[projectedFieldIndex]).orElse(null)));
    }
    recordCount++;
    return row;
  }

  @Override
  public TypeInformation<RowData> getProducedType() {
    return typeInformation;
  }

  private String getRequiredProperties(String key) {
    return Preconditions.checkNotNull(properties.get(key), key + " can not be null");
  }

  public void setLimit(long limit) {
    this.limit = limit;
  }

  public void setProjectedFields(int[][] projectedFields) {
    this.projectedFieldIndexes = new int[projectedFields.length];
    for (int i = 0; i < projectedFields.length; i++) {
      int[] projectedField = projectedFields[i];
      // not support nested projection
      Preconditions.checkArgument(projectedField != null && projectedField.length == 1,
          "projected field can not be null and length must be 1");
      this.projectedFieldIndexes[i] = projectedField[0];
    }
  }

  public void setExpression(Expression expression) {
    this.expression = expression;
  }

}
