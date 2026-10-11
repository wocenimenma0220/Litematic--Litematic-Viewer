package io.github.litematicrender.nbt;

/**
 * NBT tag type constants (vanilla-compatible), plus shared helpers.
 * Ported from fi.dy.masa.malilib.util.data.Constants (pure Java, no MC deps).
 */
public final class NbtTypes {
    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;
    /** pseudo-type for any numeric tag */
    public static final int TAG_ANY_NUMERIC = 99;

    private NbtTypes() {}

    public static String typeName(int type) {
        return switch (type) {
            case TAG_END -> "TAG_End";
            case TAG_BYTE -> "TAG_Byte";
            case TAG_SHORT -> "TAG_Short";
            case TAG_INT -> "TAG_Int";
            case TAG_LONG -> "TAG_Long";
            case TAG_FLOAT -> "TAG_Float";
            case TAG_DOUBLE -> "TAG_Double";
            case TAG_BYTE_ARRAY -> "TAG_Byte_Array";
            case TAG_STRING -> "TAG_String";
            case TAG_LIST -> "TAG_List";
            case TAG_COMPOUND -> "TAG_Compound";
            case TAG_INT_ARRAY -> "TAG_Int_Array";
            case TAG_LONG_ARRAY -> "TAG_Long_Array";
            default -> "Unknown(" + type + ")";
        };
    }
}
