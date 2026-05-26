package edu.yu.dbimpl.record;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Schema extends SchemaBase {

    // Private record to hold field type and length
    private record Field(int type, int length) {

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Field fieldInfo = (Field) o;
            return type == fieldInfo.type && length == fieldInfo.length;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, length);
        }
    }

    // Map to hold field names and their corresponding Field objects
    private final Map<String, Field> fields;

    // Cached list of field names for efficient retrieval
    private List<String> cachedFieldsList;

    public Schema() {
        super();
        this.fields = new LinkedHashMap<>();
        this.cachedFieldsList = null;
    }

    @Override
    public void addField(String fldname, int type, int length) {
        if (fldname == null || fldname.trim().isEmpty()) {
            throw new IllegalArgumentException("Field name cannot be null or empty.");
        }
        if (length <= 0 && type == Types.VARCHAR) {
            throw new IllegalArgumentException("Length must be greater than 0 for VARCHAR fields");
        }
        // For fixed-length types, compute length
        int actualLength = switch (type) {
            case Types.INTEGER -> Integer.BYTES;
            case Types.BOOLEAN -> 1;
            case Types.DOUBLE -> Double.BYTES;
            case Types.VARCHAR -> length;
            default -> throw new IllegalArgumentException("Unsupported type: " + type);
        };

        fields.put(fldname, new Field(type, actualLength));
        cachedFieldsList = null; // Invalidate cached fields list
    }

    @Override
    public void addIntField(String fldname) {
        addField(fldname, Types.INTEGER, Integer.BYTES);
    }

    @Override
    public void addBooleanField(String fldname) {
        addField(fldname, Types.BOOLEAN, 1);
    }

    @Override
    public void addDoubleField(String fldname) {
        addField(fldname, Types.DOUBLE, Double.BYTES);
    }

    @Override
    public void addStringField(String fldname, int length) {
        addField(fldname, Types.VARCHAR, length);
    }

    @Override
    public void add(String fldname, SchemaBase sch) {
        if (fldname == null || sch == null) {
            throw new IllegalArgumentException("Field name and schema cannot be null.");
        }
        if (!sch.hasField(fldname)) {
            throw new IllegalArgumentException("Field '" + fldname + "' does not exist in the provided schema.");
        }
        int type = sch.type(fldname);
        int length = sch.length(fldname);
        addField(fldname, type, length);
    }

    @Override
    public void addAll(SchemaBase sch) {
        if (sch == null) {
            throw new IllegalArgumentException("Schema cannot be null.");
        }
        for (String fldname : sch.fields()) {
            add(fldname, sch);
        }
        cachedFieldsList = null; // Invalidate cache after bulk operation
    }

    @Override
    public List<String> fields() {
        if (cachedFieldsList == null) {
            cachedFieldsList = new ArrayList<>(fields.keySet());
        }
        return cachedFieldsList;
    }

    @Override
    public boolean hasField(String fldname) {
        return fields.containsKey(fldname);
    }

    @Override
    public int type(String fldname) {
        if (!hasField(fldname)) {
            throw new IllegalArgumentException("Field '" + fldname + "' not found in schema.");
        }
        return fields.get(fldname).type;
    }

    @Override
    public int length(String fldname) {
        if (!hasField(fldname)) {
            throw new IllegalArgumentException("Field '" + fldname + "' not found in schema.");
        }
        return fields.get(fldname).length;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Schema{fields=");
        sb.append(fields.keySet());
        sb.append(", info={");
        boolean first = true;
        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append("=type=").append(entry.getValue().type)
                    .append(", length=").append(entry.getValue().length);
            first = false;
        }
        sb.append("}}");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Schema other)) return false;

        // Check same number of fields
        if (this.fields.size() != other.fields.size()) return false;

        // Check same field names in same order
        if (!new ArrayList<>(this.fields.keySet()).equals(new ArrayList<>(other.fields.keySet()))) return false;

        // Check each field has same type and length
        for (String fieldName : this.fields.keySet()) {
            Field thisInfo = this.fields.get(fieldName);
            Field otherInfo = other.fields.get(fieldName);

            if (otherInfo == null) return false;
            if (thisInfo.type != otherInfo.type) return false;
            if (thisInfo.length != otherInfo.length) return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            result = 31 * result + entry.getKey().hashCode();
            result = 31 * result + entry.getValue().type;
            result = 31 * result + entry.getValue().length;
        }
        return result;
    }
}