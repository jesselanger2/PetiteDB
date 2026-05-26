package edu.yu.dbimpl.index;

import edu.yu.dbimpl.record.SchemaBase;

import java.util.Objects;

import static edu.yu.dbimpl.index.IndexMgrBase.IndexType;

public class IndexDescriptor extends IndexDescriptorBase {

    private final String tableName;
    private final SchemaBase indexedTableSchema;
    private final String indexName;
    private final String fieldName;
    private final IndexType indexType;

    public IndexDescriptor(String tableName, SchemaBase indexedTableSchema,
                           String indexName, String fieldName,
                           IndexType indexType) {
        super(tableName, indexedTableSchema, indexName, fieldName, indexType);

        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        if (indexedTableSchema == null) {
            throw new IllegalArgumentException("Indexed table schema cannot be null");
        }
        if (indexName == null || indexName.trim().isEmpty()) {
            throw new IllegalArgumentException("Index name cannot be null or empty");
        }
        if (fieldName == null || fieldName.trim().isEmpty()) {
            throw new IllegalArgumentException("Field name cannot be null or empty");
        }
        if (indexType == null) {
            throw new IllegalArgumentException("Index type cannot be null");
        }
        if (!indexedTableSchema.hasField(fieldName)) {
            throw new IllegalArgumentException(
                    "Field " + fieldName + " does not exist in the schema");
        }

        this.tableName = tableName;
        this.indexedTableSchema = indexedTableSchema;
        this.indexName = indexName;
        this.fieldName = fieldName;
        this.indexType = indexType;
    }

    @Override
    public String getTableName() {
        return tableName;
    }

    @Override
    public SchemaBase getIndexedTableSchema() {
        return indexedTableSchema;
    }

    @Override
    public String getIndexName() {
        return indexName;
    }

    @Override
    public String getFieldName() {
        return fieldName;
    }

    @Override
    public IndexType getIndexType() {
        return indexType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexDescriptor that)) return false;
        return Objects.equals(tableName, that.tableName) &&
                Objects.equals(indexName, that.indexName) &&
                Objects.equals(fieldName, that.fieldName) &&
                indexType == that.indexType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, indexName, fieldName, indexType);
    }

    @Override
    public String toString() {
        return "IndexDescriptor{" +
                "tableName='" + tableName + '\'' +
                ", indexName='" + indexName + '\'' +
                ", fieldName='" + fieldName + '\'' +
                ", indexType=" + indexType +
                '}';
    }
}