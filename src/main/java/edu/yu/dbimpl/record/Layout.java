package edu.yu.dbimpl.record;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

public class Layout extends LayoutBase {

    private final SchemaBase schema;
    private final Map<String, Integer> offsets;
    private final int slotSize;

    public Layout(SchemaBase schema) {
        super(schema);
        if (schema == null) {
            throw new IllegalArgumentException("Schema cannot be null");
        }
        this.schema = schema;
        this.offsets = new HashMap<>();
        // Calculate offsets
        // First byte is for the "in-use" flag
        int currentOffset = 1;
        for (String fldname : schema.fields()) {
            offsets.put(fldname, currentOffset);
            currentOffset += lengthInBytes(fldname);
        }
        // Set slot size to total length of the record
        this.slotSize = currentOffset;
    }

    public Layout(SchemaBase schema, Map<String, Integer> offsets, int slotSize) {
        super(schema, offsets, slotSize);
        if (schema == null || offsets == null || slotSize <= 0) {
            throw new IllegalArgumentException("Invalid arguments for Layout constructor");
        }
        this.schema = schema;
        this.offsets = new HashMap<>(offsets);
        this.slotSize = slotSize;
    }

    @Override
    public SchemaBase schema() {
        return schema;
    }

    @Override
    public int offset(String fldname) {
        Integer offset = offsets.get(fldname);
        if (offset == null) {
            throw new IllegalArgumentException("Field name not defined in layout: " + fldname);
        }
        return offset;
    }

    @Override
    public int slotSize() {
        return slotSize;
    }

    // Helper method to get the length in bytes of a field
    private int lengthInBytes(String fldname) {
        if (!schema.fields().contains(fldname)) {
            throw new IllegalArgumentException("Field name not defined in schema: " + fldname);
        }
        // Get the field type and length from the schema
        int type = schema.type(fldname);
        int logicalLength = schema.length(fldname);

        // For VARCHAR, add 4 bytes for length prefix
        if (type == Types.VARCHAR) {
            return logicalLength + 4;
        // For other types, return the logical length
        } else {
            return logicalLength;
        }
    }

    @Override
    public String toString() {
        return "Layout{schema=" + schema + ", offsets=" + offsets + ", slotsize=" + slotSize + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Layout other)) return false;

        // Two layouts are equal if they have the same slot size and same offsets
        if (this.slotSize != other.slotSize) return false;
        if (!this.offsets.equals(other.offsets)) return false;

        // Also check that schemas have the same fields with same types and lengths
        if (!this.schema.fields().equals(other.schema.fields())) return false;

        for (String field : this.schema.fields()) {
            if (this.schema.type(field) != other.schema.type(field)) return false;
            if (this.schema.length(field) != other.schema.length(field)) return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = slotSize;
        result = 31 * result + offsets.hashCode();
        result = 31 * result + schema.fields().hashCode();
        return result;
    }
}