/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.netflix.iceberg.exceptions.ValidationException;
import com.netflix.iceberg.transforms.Transforms;
import com.netflix.iceberg.types.Type;
import com.netflix.iceberg.types.Types;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Represents how to produce partition data for a table.
 * <p>
 * Partition data is produced by transforming columns in a table. Each column transform is
 * represented by a named {@link PartitionField}.
 */
public class PartitionSpec implements Serializable {
  // start assigning IDs for partition fields at 1000
  private static final int PARTITION_DATA_ID_START = 1000;

  private final Schema schema;

  // this is ordered so that DataFile has a consistent schema
  private final PartitionField[] fields;
  private transient Map<Integer, PartitionField> fieldsBySourceId = null;
  private transient Map<String, PartitionField> fieldsByName = null;
  private transient Class<?>[] javaClasses = null;
  private transient List<PartitionField> fieldList = null;

  private PartitionSpec(Schema schema, List<PartitionField> fields) {
    this.schema = schema;
    this.fields = new PartitionField[fields.size()];
    for (int i = 0; i < this.fields.length; i += 1) {
      this.fields[i] = fields.get(i);
    }
  }

  /**
   * @return the {@link Schema} for this spec.
   */
  public Schema schema() {
    return schema;
  }

  /**
   * @return the list of {@link PartitionField partition fields} for this spec.
   */
  public List<PartitionField> fields() {
    return lazyFieldList();
  }

  /**
   * @param fieldId a field id from the source schema
   * @return the {@link PartitionField field} that partitions the given source field
   */
  public PartitionField getFieldBySourceId(int fieldId) {
    return lazyFieldsBySourceId().get(fieldId);
  }

  /**
   * @return a {@link Types.StructType} for partition data defined by this spec.
   */
  public Types.StructType partitionType() {
    List<Types.NestedField> structFields = Lists.newArrayListWithExpectedSize(fields.length);

    for (int i = 0; i < fields.length; i += 1) {
      PartitionField field = fields[i];
      Type sourceType = schema.findType(field.sourceId());
      Type resultType = field.transform().getResultType(sourceType);
      // assign ids for partition fields starting at 100 to leave room for data file's other fields
      structFields.add(
          Types.NestedField.optional(PARTITION_DATA_ID_START + i, field.name(), resultType));
    }

    return Types.StructType.of(structFields);
  }

  public Class<?>[] javaClasses() {
    if (javaClasses == null) {
      this.javaClasses = new Class<?>[fields.length];
      for (int i = 0; i < fields.length; i += 1) {
        PartitionField field = fields[i];
        Type sourceType = schema.findType(field.sourceId());
        Type result = field.transform().getResultType(sourceType);
        javaClasses[i] = result.typeId().javaClass();
      }
    }

    return javaClasses;
  }

  @SuppressWarnings("unchecked")
  private <T> T get(StructLike data, int pos, Class<?> javaClass) {
    return data.get(pos, (Class<T>) javaClass);
  }

  private String escape(String string) {
    try {
      return URLEncoder.encode(string, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  public String partitionToPath(StructLike data) {
    StringBuilder sb = new StringBuilder();
    Class<?>[] javaClasses = javaClasses();
    for (int i = 0; i < javaClasses.length; i += 1) {
      PartitionField field = fields[i];
      String valueString = field.transform().toHumanString(get(data, i, javaClasses[i]));

      if (i > 0) {
        sb.append("/");
      }
      sb.append(field.name()).append("=").append(escape(valueString));
    }
    return sb.toString();
  }

  public boolean compatibleWith(PartitionSpec other) {
    if (equals(other)) {
      return true;
    }

    if (fields.length != other.fields.length) {
      return false;
    }

    for (int i = 0; i < fields.length; i += 1) {
      PartitionField thisField = fields[i];
      PartitionField thatField = other.fields[i];
      if (thisField.sourceId() != thatField.sourceId() ||
          !thisField.transform().toString().equals(thatField.transform().toString())) {
        return false;
      }
    }

    return true;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other == null || getClass() != other.getClass()) {
      return false;
    }

    PartitionSpec that = (PartitionSpec) other;
    return Arrays.equals(fields, that.fields);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(Arrays.hashCode(fields));
  }

  private List<PartitionField> lazyFieldList() {
    if (fieldList == null) {
      this.fieldList = ImmutableList.copyOf(fields);
    }
    return fieldList;
  }

  private Map<String, PartitionField> lazyFieldsByName() {
    if (fieldsByName == null) {
      ImmutableMap.Builder<String, PartitionField> builder = ImmutableMap.builder();
      for (PartitionField field : fields) {
        builder.put(field.name(), field);
      }
      this.fieldsByName = builder.build();
    }

    return fieldsByName;
  }

  private Map<Integer, PartitionField> lazyFieldsBySourceId() {
    if (fieldsBySourceId == null) {
      ImmutableMap.Builder<Integer, PartitionField> byIdBuilder = ImmutableMap.builder();
      for (PartitionField field : fields) {
        byIdBuilder.put(field.sourceId(), field);
      }
      this.fieldsBySourceId = byIdBuilder.build();
    }

    return fieldsBySourceId;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (PartitionField field : fields) {
      sb.append("\n");
      sb.append("  ").append(field.name()).append(": ").append(field.transform())
          .append("(").append(field.sourceId()).append(")");
    }
    if (fields.length > 0) {
      sb.append("\n");
    }
    sb.append("]");
    return sb.toString();
  }

  private static final PartitionSpec UNPARTITIONED_SPEC =
      new PartitionSpec(new Schema(), ImmutableList.of());

  /**
   * Returns a spec for unpartitioned tables.
   *
   * @return a partition spec with no partitions
   */
  public static PartitionSpec unpartitioned() {
    return UNPARTITIONED_SPEC;
  }

  /**
   * Creates a new {@link Builder partition spec builder} for the given {@link Schema}.
   *
   * @param schema a schema
   * @return a partition spec builder for the given schema
   */
  public static Builder builderFor(Schema schema) {
    return new Builder(schema);
  }

  /**
   * Used to create valid {@link PartitionSpec partition specs}.
   * <p>
   * Call {@link #builderFor(Schema)} to create a new builder.
   */
  public static class Builder {
    private final Schema schema;
    private final List<PartitionField> fields = Lists.newArrayList();
    private final Set<String> partitionNames = Sets.newHashSet();

    private Builder(Schema schema) {
      this.schema = schema;
    }

    private void checkAndAddPartitionName(String name) {
      Preconditions.checkArgument(name != null && !name.isEmpty(),
          "Cannot use empty or null partition name: %s", name);
      Preconditions.checkArgument(!partitionNames.contains(name),
          "Cannot use partition name more than once: %s", name);
      partitionNames.add(name);
    }

    private Types.NestedField findSourceColumn(String sourceName) {
      Types.NestedField sourceColumn = schema.findField(sourceName);
      Preconditions.checkNotNull(sourceColumn, "Cannot find source column: %s", sourceName);
      return sourceColumn;
    }

    public Builder identity(String sourceName) {
      checkAndAddPartitionName(sourceName);
      Types.NestedField sourceColumn = findSourceColumn(sourceName);
      fields.add(new PartitionField(
          sourceColumn.fieldId(), sourceName, Transforms.identity(sourceColumn.type())));
      return this;
    }

    public Builder year(String sourceName) {
      String name = sourceName + "_year";
      checkAndAddPartitionName(name);
      Types.NestedField sourceColumn = findSourceColumn(sourceName);
      fields.add(new PartitionField(
          sourceColumn.fieldId(), name, Transforms.year(sourceColumn.type())));
      return this;
    }

    public Builder month(String sourceName) {
      String name = sourceName + "_month";
      checkAndAddPartitionName(name);
      Types.NestedField sourceColumn = findSourceColumn(sourceName);
      fields.add(new PartitionField(
          sourceColumn.fieldId(), name, Transforms.month(sourceColumn.type())));
      return this;
    }

    public Builder day(String sourceName) {
      String name = sourceName + "_day";
      checkAndAddPartitionName(name);
      Types.NestedField sourceColumn = findSourceColumn(sourceName);
      fields.add(new PartitionField(
          sourceColumn.fieldId(), name, Transforms.day(sourceColumn.type())));
      return this;
    }

    public Builder hour(String sourceName) {
      String name = sourceName + "_hour";
      checkAndAddPartitionName(name);
      Types.NestedField sourceColumn = findSourceColumn(sourceName);
      fields.add(new PartitionField(
          sourceColumn.fieldId(), name, Transforms.hour(sourceColumn.type())));
      return this;
    }

    public Builder bucket(String sourceName, int numBuckets) {
      String name = sourceName + "_bucket";
      checkAndAddPartitionName(name);
      Types.NestedField sourceColumn = findSourceColumn(sourceName);
      fields.add(new PartitionField(
          sourceColumn.fieldId(), name, Transforms.bucket(sourceColumn.type(), numBuckets)));
      return this;
    }

    public Builder truncate(String sourceName, int width) {
      String name = sourceName + "_trunc";
      checkAndAddPartitionName(name);
      Types.NestedField sourceColumn = findSourceColumn(sourceName);
      fields.add(new PartitionField(
          sourceColumn.fieldId(), name, Transforms.truncate(sourceColumn.type(), width)));
      return this;
    }

    public Builder add(int sourceId, String name, String transform) {
      checkAndAddPartitionName(name);
      Types.NestedField column = schema.findField(sourceId);
      Preconditions.checkNotNull(column, "Cannot find source column: %d", sourceId);
      fields.add(new PartitionField(
          sourceId, name, Transforms.fromString(column.type(), transform)));
      return this;
    }

    public PartitionSpec build() {
      PartitionSpec spec = new PartitionSpec(schema, fields);
      checkCompatibility(spec, schema);
      return spec;
    }
  }

  public static void checkCompatibility(PartitionSpec spec, Schema schema) {
    for (PartitionField field : spec.fields) {
      Type sourceType = schema.findType(field.sourceId());
      ValidationException.check(sourceType.isPrimitiveType(),
          "Cannot partition by non-primitive source field: %s", sourceType);
      ValidationException.check(
          field.transform().canTransform(sourceType),
          "Invalid source type %s for transform: %s",
          sourceType, field.transform());
    }
  }
}
