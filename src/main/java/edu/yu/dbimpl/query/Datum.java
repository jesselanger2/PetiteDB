package edu.yu.dbimpl.query;

import edu.yu.dbimpl.file.PageBase;

import java.sql.Types;
import java.util.Arrays;
import java.util.Objects;

public class Datum extends DatumBase {

    private final Object value;
    private final int sqlType;

    public Datum(Integer ival) {
        super(ival);
        if (ival == null) {
            throw new IllegalArgumentException("Integer value cannot be null");
        }
        this.value = ival;
        this.sqlType = Types.INTEGER;
    }

    public Datum(String sval) {
        super(sval);
        if (sval == null) {
            throw new IllegalArgumentException("String value cannot be null");
        }
        this.value = sval;
        this.sqlType = Types.VARCHAR;
    }

    public Datum(Boolean bval) {
        super(bval);
        if (bval == null) {
            throw new IllegalArgumentException("Boolean value cannot be null");
        }
        this.value = bval;
        this.sqlType = Types.BOOLEAN;
    }

    public Datum(Double dval) {
        super(dval);
        if (dval == null) {
            throw new IllegalArgumentException("Double value cannot be null");
        }
        this.value = dval;
        this.sqlType = Types.DOUBLE;
    }

    public Datum(byte[] array) {
        super(array);
        if (array == null) {
            throw new IllegalArgumentException("Byte array cannot be null");
        }
        this.value = array;
        this.sqlType = Types.VARBINARY;
    }

    @Override
    public int asInt() {
        if (value instanceof Integer) {
            return ((Integer) value).intValue();
        }
        // Allow conversion from Double to Integer
        if (value instanceof Double) {
            return ((Double) value).intValue();
        }
        throw new ClassCastException("Value is not an Integer or Double");
    }

    @Override
    public boolean asBoolean() {
        if (!(value instanceof Boolean)) {
            throw new ClassCastException("Value is not a Boolean");
        }
        return ((Boolean) value).booleanValue();
    }

    @Override
    public double asDouble() {
        if (value instanceof Double) {
            return ((Double) value).doubleValue();
        }
        // Allow conversion from Integer to Double
        if (value instanceof Integer) {
            return ((Integer) value).doubleValue();
        }
        throw new ClassCastException("Value is not a Double or Integer");
    }

    @Override
    public String asString() {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, PageBase.CHARSET);
        }
        throw new ClassCastException("Value is not a String or byte array");
    }

    @Override
    public byte[] asBinaryArray() {
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof String) {
            return ((String) value).getBytes(PageBase.CHARSET);
        }
        throw new ClassCastException("Value is not a byte array or String");
    }

    @Override
    public int getSQLType() {
        return sqlType;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Datum other)) {
            return false;
        }
        // SQL types must match
        if (this.sqlType != other.sqlType) {
            return false;
        }
        // Compare wrapped values
        if (value instanceof byte[] && other.value instanceof byte[]) {
            return Arrays.equals((byte[]) value, (byte[]) other.value);
        }
        return Objects.equals(this.value, other.value);
    }

    @Override
    public int hashCode() {
        if (value instanceof byte[]) {
            return Objects.hash(sqlType, Arrays.hashCode((byte[]) value));
        }
        return Objects.hash(sqlType, value);
    }

    @Override
    public int compareTo(DatumBase other) {
        if (other == null) {
            throw new NullPointerException("Cannot compare to null");
        }
        if (!(other instanceof Datum otherDatum)) {
            throw new ClassCastException("Can only compare to Datum instances");
        }

        // Types must match for non-numeric comparison
        if (this.sqlType != otherDatum.sqlType) {
            throw new ClassCastException(
                    "Cannot compare Datum of type " + this.sqlType +
                            " with Datum of type " + otherDatum.sqlType);
        }

        // Compare based on type
        switch (sqlType) {
            case Types.INTEGER:
                return Integer.compare(this.asInt(), otherDatum.asInt());
            case Types.DOUBLE:
                return Double.compare(this.asDouble(), otherDatum.asDouble());
            case Types.BOOLEAN:
                return Boolean.compare(this.asBoolean(), otherDatum.asBoolean());
            case Types.VARCHAR:
                return this.asString().compareTo(otherDatum.asString());
            case Types.VARBINARY:
                byte[] thisArray = (byte[]) this.value;
                byte[] otherArray = (byte[]) otherDatum.value;
                return Arrays.compare(thisArray, otherArray);
            default:
                throw new UnsupportedOperationException("Comparison not supported for type " + sqlType);
        }
    }

    @Override
    public String toString() {
        if (value instanceof byte[]) {
            return "Datum{type=" + sqlType + ", value=" + Arrays.toString((byte[]) value) + "}";
        }
        return "Datum{type=" + sqlType + ", value=" + value + "}";
    }
}