package io.github.lukehutch.fastclasspathscanner.classfileparser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo.ClassInfoUnlinked;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.Log.DeferredLog;

/**
 * A classfile binary format parser. Implements its own buffering to avoid the overhead of using DataInputStream.
 * This class should only be used by a single thread at a time, but can be re-used to scan multiple classfiles in
 * sequence, to avoid re-allocating buffer memory.
 */
public class ClassfileBinaryParser {
    /** The ScanSpec. */
    private final ScanSpec scanSpec;

    /** The thread-local logger. */
    private final DeferredLog log;

    /** The InputStream for the current classfile. Set by each call to readClassInfoFromClassfileHeader(). */
    private InputStream inputStream;

    /** The name of the current classfile. Determined early in the call to readClassInfoFromClassfileHeader(). */
    private String className;

    public ClassfileBinaryParser(final ScanSpec scanSpec, final DeferredLog log) {
        this.scanSpec = scanSpec;
        this.log = log;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Buffer size for initial read. We can save some time by reading most of the classfile header in a single read
     * at the beginning of the scan.
     * 
     * (If chunk sizes are too small, significant overhead is expended in refilling the buffer. If they are too
     * large, significant overhead is expended in decompressing more of the classfile header than is needed. Testing
     * on a large classpath indicates that the defaults are reasonably optimal.)
     */
    private static final int INITIAL_BUFFER_CHUNK_SIZE = 16384;

    /** Buffer size for classfile reader. */
    private static final int SUBSEQUENT_BUFFER_CHUNK_SIZE = 4096;

    /** Bytes read from the beginning of the classfile. This array is reused across calls. */
    private byte[] buf = new byte[INITIAL_BUFFER_CHUNK_SIZE];

    /** The current read index in the classfileBytes array. */
    private int curr = 0;

    /** Bytes used in the classfileBytes array. */
    private int used = 0;

    /**
     * Read another chunk of size BUFFER_CHUNK_SIZE from the InputStream; double the size of the buffer if necessary
     * to accommodate the new chunk.
     */
    private void readMore(final int bytesRequired) throws IOException {
        final int extraBytesNeeded = bytesRequired - (used - curr);
        int bytesToRequest = extraBytesNeeded + SUBSEQUENT_BUFFER_CHUNK_SIZE;
        final int maxNewUsed = used + bytesToRequest;
        if (maxNewUsed > buf.length) {
            // Ran out of space, need to increase the size of the buffer
            int newBufLen = buf.length;
            while (newBufLen < maxNewUsed) {
                newBufLen <<= 1;
            }
            buf = Arrays.copyOf(buf, newBufLen);
        }
        int extraBytesStillNotRead = extraBytesNeeded;
        while (extraBytesStillNotRead > 0) {
            final int bytesRead = inputStream.read(buf, used, bytesToRequest);
            if (bytesRead > 0) {
                used += bytesRead;
                bytesToRequest -= bytesRead;
                extraBytesStillNotRead -= bytesRead;
            } else {
                // EOF
                if (extraBytesStillNotRead > 0) {
                    throw new IOException("Premature EOF while reading classfile");
                } else {
                    break;
                }
            }
        }
    }

    private int readUnsignedByte() throws IOException {
        if (curr > used - 1) {
            readMore(1);
        }
        return buf[curr++] & 0xff;
    }

    @SuppressWarnings("unused")
    private int readUnsignedByte(final int offset) {
        return buf[offset] & 0xff;
    }

    private int readUnsignedShort() throws IOException {
        if (curr > used - 2) {
            readMore(2);
        }
        final int val = ((buf[curr] & 0xff) << 8) | (buf[curr + 1] & 0xff);
        curr += 2;
        return val;
    }

    private int readUnsignedShort(final int offset) {
        return ((buf[offset] & 0xff) << 8) | (buf[offset + 1] & 0xff);
    }

    private int readInt() throws IOException {
        if (curr > used - 4) {
            readMore(4);
        }
        final int val = ((buf[curr] & 0xff) << 24) | ((buf[curr + 1] & 0xff) << 16) | ((buf[curr + 2] & 0xff) << 8)
                | (buf[curr + 3] & 0xff);
        curr += 4;
        return val;
    }

    private int readInt(final int offset) throws IOException {
        return ((buf[offset] & 0xff) << 24) | ((buf[offset + 1] & 0xff) << 16) | ((buf[offset + 2] & 0xff) << 8)
                | (buf[offset + 3] & 0xff);
    }

    @SuppressWarnings("unused")
    private long readLong() throws IOException {
        if (curr > used - 8) {
            readMore(8);
        }
        final long val = (((long) (((buf[curr] & 0xff) << 24) | ((buf[curr + 1] & 0xff) << 16)
                | ((buf[curr + 2] & 0xff) << 8) | (buf[curr + 3] & 0xff))) << 32) | ((buf[curr + 4] & 0xff) << 24)
                | ((buf[curr + 5] & 0xff) << 16) | ((buf[curr + 6] & 0xff) << 8) | (buf[curr + 7] & 0xff);
        curr += 8;
        return val;
    }

    private long readLong(final int offset) throws IOException {
        return (((long) (((buf[offset] & 0xff) << 24) | ((buf[offset + 1] & 0xff) << 16)
                | ((buf[offset + 2] & 0xff) << 8) | (buf[offset + 3] & 0xff))) << 32)
                | ((buf[offset + 4] & 0xff) << 24) | ((buf[offset + 5] & 0xff) << 16)
                | ((buf[offset + 6] & 0xff) << 8) | (buf[offset + 7] & 0xff);
    }

    private void skip(final int bytesToSkip) throws IOException {
        if (curr > used - bytesToSkip) {
            readMore(bytesToSkip);
        }
        curr += bytesToSkip;
    }

    /** Reads the "modified UTF8" format defined in the Java classfile spec, optionally replacing '/' with '.'. */
    private String readString(final int offset, final boolean replaceSlashWithDot) {
        final int utfLen = readUnsignedShort(offset);
        final int start = offset + 2;
        final char[] chars = new char[utfLen];
        int c, c2, c3, c4;
        int byteIdx = 0;
        int charIdx = 0;
        for (; byteIdx < utfLen; byteIdx++) {
            c = buf[start + byteIdx] & 0xff;
            if (c > 127) {
                break;
            }
            chars[charIdx++] = (char) (replaceSlashWithDot && c == '/' ? '.' : c);
        }
        while (byteIdx < utfLen) {
            c = buf[start + byteIdx] & 0xff;
            switch (c >> 4) {
            case 0:
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
            case 7:
                byteIdx++;
                chars[charIdx++] = (char) (replaceSlashWithDot && c == '/' ? '.' : c);
                break;
            case 12:
            case 13:
                byteIdx += 2;
                if (byteIdx > utfLen) {
                    throw new RuntimeException("Bad modified UTF8");
                }
                c2 = buf[start + byteIdx - 1];
                if ((c2 & 0xc0) != 0x80) {
                    throw new RuntimeException("Bad modified UTF8");
                }
                c4 = ((c & 0x1f) << 6) | (c2 & 0x3f);
                chars[charIdx++] = (char) (replaceSlashWithDot && c4 == '/' ? '.' : c4);
                break;
            case 14:
                byteIdx += 3;
                if (byteIdx > utfLen) {
                    throw new RuntimeException("Bad modified UTF8");
                }
                c2 = buf[start + byteIdx - 2];
                c3 = buf[start + byteIdx - 1];
                if (((c2 & 0xc0) != 0x80) || ((c3 & 0xc0) != 0x80)) {
                    throw new RuntimeException("Bad modified UTF8");
                }
                c4 = ((c & 0x0f) << 12) | ((c2 & 0x3f) << 6) | ((c3 & 0x3f) << 0);
                chars[charIdx++] = (char) (replaceSlashWithDot && c4 == '/' ? '.' : c4);
                break;
            default:
                throw new RuntimeException("Bad modified UTF8");
            }
        }
        if (charIdx < utfLen) {
            return new String(chars, 0, charIdx);
        } else {
            return new String(chars);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The byte offset for the beginning of each entry in the constant pool. */
    private int[] offset;

    /** The tag (type) for each entry in the constant pool. */
    private int[] tag;

    /** The indirection index for String/Class entries in the constant pool. */
    private int[] indirectStringRefs;

    /** Get the byte offset within the buffer of a string from the constant pool, or 0 for a null string. */
    private int getConstantPoolStringOffset(final int constantPoolIdx) {
        final int t = tag[constantPoolIdx];
        if (t != 1 && t != 7 && t != 8) {
            throw new RuntimeException("Wrong tag number at constant pool index " + constantPoolIdx + ", "
                    + "cannot continue reading class. Please report this on the FastClasspathScanner GitHub page.");
        }
        int cpIdx = constantPoolIdx;
        if (t == 7 || t == 8) {
            final int indirIdx = indirectStringRefs[constantPoolIdx];
            if (indirIdx == -1) {
                // Should not happen
                throw new RuntimeException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this on the FastClasspathScanner GitHub page.");
            }
            if (indirIdx == 0) {
                // I assume this represents a null string, since the zeroeth entry is unused
                return 0;
            }
            cpIdx = indirIdx;
        }
        return offset[cpIdx];
    }

    /** Get a string from the constant pool, optionally replacing '/' with '.'. */
    private String getConstantPoolString(final int constantPoolIdx, final boolean replaceSlashWithDot) {
        final int constantPoolStringOffset = getConstantPoolStringOffset(constantPoolIdx);
        return constantPoolStringOffset == 0 ? null : readString(constantPoolStringOffset, replaceSlashWithDot);
    }

    /** Get a string from the constant pool. */
    private String getConstantPoolString(final int constantPoolIdx) {
        final int constantPoolStringOffset = getConstantPoolStringOffset(constantPoolIdx);
        return constantPoolStringOffset == 0 ? null
                : readString(constantPoolStringOffset, /* replaceSlashWithDot = */ false);
    }

    /** Get a string from the constant pool, and interpret it as a class name by replacing '/' with '.'. */
    private String getConstantPoolClassName(final int constantPoolIdx) {
        return getConstantPoolString(constantPoolIdx, /* replaceSlashWithDot = */ true);
    }

    /** Compare a string in the constant pool with a given constant, without constructing the String object. */
    private boolean constantPoolStringEquals(final int constantPoolIdx, final String otherString) {
        final int strOffset = getConstantPoolStringOffset(constantPoolIdx);
        if (strOffset == 0) {
            return otherString == null;
        }
        final int strLen = readUnsignedShort(strOffset);
        final int otherLen = otherString.length();
        if (strLen != otherLen) {
            return false;
        }
        final int strStart = strOffset + 2;
        for (int i = 0; i < strLen; i++) {
            if ((char) (buf[strStart + i] & 0xff) != otherString.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Get a constant from the constant pool. */
    private Object getConstantPoolValue(final int constantPoolIdx) throws IOException {
        switch (tag[constantPoolIdx]) {
        case 1: // Modified UTF8
            return getConstantPoolString(constantPoolIdx);
        case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
            return new Integer(readInt(offset[constantPoolIdx]));
        case 4: // float
            return new Float(Float.intBitsToFloat(readInt(offset[constantPoolIdx])));
        case 5: // long
            return new Long(readLong(offset[constantPoolIdx]));
        case 6: // double
            return new Double(Double.longBitsToDouble(readLong(offset[constantPoolIdx])));
        case 7: // Class
        case 8: // String
            // Forward or backward indirect reference to a modified UTF8 entry
            return getConstantPoolString(constantPoolIdx);
        default:
            // FastClasspathScanner doesn't currently do anything with the other types
            throw new RuntimeException("Constant pool entry type " + tag[constantPoolIdx] + " unsupported, "
                    + "cannot continue reading class. Please report this on the FastClasspathScanner GitHub page.");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read annotation entry from classfile.
     */
    private String readAnnotation() throws IOException {
        // Lcom/xyz/Annotation -> Lcom.xyz.Annotation
        final String annotationFieldDescriptor = getConstantPoolClassName(readUnsignedShort());
        String annotationClassName;
        if (annotationFieldDescriptor.charAt(0) == 'L'
                && annotationFieldDescriptor.charAt(annotationFieldDescriptor.length() - 1) == ';') {
            // Lcom.xyz.Annotation; -> com.xyz.Annotation
            annotationClassName = annotationFieldDescriptor.substring(1, annotationFieldDescriptor.length() - 1);
        } else {
            // Should not happen
            annotationClassName = annotationFieldDescriptor;
        }
        final int numElementValuePairs = readUnsignedShort();
        for (int i = 0; i < numElementValuePairs; i++) {
            skip(2); // element_name_index
            readAnnotationElementValue();
        }
        return annotationClassName;
    }

    /**
     * Read annotation element value from classfile. (These values are currently just skipped, so this function
     * returns nothing.)
     */
    private void readAnnotationElementValue() throws IOException {
        final int tag = (char) readUnsignedByte();
        switch (tag) {
        case 'B':
        case 'C':
        case 'D':
        case 'F':
        case 'I':
        case 'J':
        case 'S':
        case 'Z':
        case 's':
            // const_value_index
            skip(2);
            break;
        case 'e':
            // enum_const_value
            skip(4);
            break;
        case 'c':
            // class_info_index
            skip(2);
            break;
        case '@':
            // Complex (nested) annotation
            readAnnotation();
            break;
        case '[':
            // array_value
            final int count = readUnsignedShort();
            for (int l = 0; l < count; ++l) {
                // Nested annotation element value
                readAnnotationElementValue();
            }
            break;
        default:
            throw new RuntimeException("Class " + className + " has unknown annotation element type tag '"
                    + ((char) tag) + "': element size unknown, cannot continue reading class. "
                    + "Please report this on the FastClasspathScanner GitHub page.");
        }
    }

    /**
     * Find non-blacklisted type names in the given type descriptor, and add them to the set of field types.
     * 
     * Splits a type param into type pieces, e.g. "Ljava/util/Map<+Lcom/xyz/fig/shape/Shape;Ljava/lang/Integer;>;"
     * => "java/util/Map", "com/xyz/fig/shape/Shape", "java/lang/Integer".
     * 
     * Also removes array prefixes, e.g. "[[[Lcom.xyz.Widget" -> "com.xyz.Widget".
     */
    private void addFieldTypeDescriptorParts(final ClassInfoUnlinked classInfoUnlinked,
            final String typeDescriptor) {
        for (int i = 0; i < typeDescriptor.length(); i++) {
            char c = typeDescriptor.charAt(i);
            if (c == 'L') {
                final int typeNameStart = ++i;
                for (; i < typeDescriptor.length(); i++) {
                    c = typeDescriptor.charAt(i);
                    if (c == '<' || c == ';') {
                        break;
                    }
                }
                // Switch '/' package delimiter to '.' (lower overhead than String.replace('/', '.'))
                final char[] typeNameChars = new char[i - typeNameStart];
                for (int j = typeNameStart; j < i; j++) {
                    final char chr = typeDescriptor.charAt(j);
                    typeNameChars[j - typeNameStart] = chr == '/' ? '.' : chr;
                }
                final String typeName = new String(typeNameChars);
                // Check if the type of this field falls within a non-blacklisted package, and if not, add it
                if (scanSpec.classIsNotBlacklisted(typeName)) {
                    classInfoUnlinked.addFieldType(typeName);
                }
            }
        }
    }

    /** Returns true if the classname (e.g. com.x.MyClass) matches the relative path (e.g. com/x/MyClass.class). */
    private boolean classNameMatches(final String relativePath) {
        final int len = className.length();
        if (len != relativePath.length() - 6) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            final char c = className.charAt(i);
            final char r = relativePath.charAt(i);
            if (!((c == '.' && r == '/') || c == r)) {
                return false;
            }
        }
        for (int i = 0; i < 6; i++) {
            if (relativePath.charAt(len + i) != ".class".charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Directly examine contents of classfile binary header to determine annotations, implemented interfaces, the
     * super-class etc. Creates a new ClassInfo object, and adds it to classNameToClassInfoOut. Assumes classpath
     * masking has already been performed, so that only one class of a given name will be added.
     */
    public ClassInfoUnlinked readClassInfoFromClassfileHeader(final InputStream inputStream,
            final String relativePath, final Map<String, HashSet<String>> classNameToStaticFinalFieldsToMatch,
            final ConcurrentHashMap<String, String> stringInternMap) throws IOException {
        try {
            // Clear className and set inputStream for each new class
            this.className = null;
            this.inputStream = inputStream;

            // Initialize buffer
            curr = 0;
            used = inputStream.read(buf, 0, INITIAL_BUFFER_CHUNK_SIZE);
            if (used < 0) {
                throw new IOException("Classfile " + relativePath + " is empty");
            }

            // Check magic number
            if (readInt() != 0xCAFEBABE) {
                if (FastClasspathScanner.verbose) {
                    throw new IOException(
                            "Classfile " + relativePath + " does not have correct classfile magic number");
                }
            }

            // Minor version
            readUnsignedShort();
            // Major version
            readUnsignedShort();

            // Read size of constant pool
            final int cpCount = readUnsignedShort();

            // Allocate storage for constant pool, or reuse storage if there's enough left from the previous scan
            if (offset == null || offset.length < cpCount) {
                offset = new int[cpCount];
                tag = new int[cpCount];
                indirectStringRefs = new int[cpCount];
            }
            Arrays.fill(indirectStringRefs, 0, cpCount, -1);

            // Read constant pool entries
            for (int i = 1; i < cpCount; ++i) {
                tag[i] = readUnsignedByte();
                offset[i] = curr;
                switch (tag[i]) {
                case 1: // Modified UTF8
                    final int strLen = readUnsignedShort();
                    skip(strLen);
                    break;
                case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
                case 4: // float
                    skip(4);
                    break;
                case 5: // long
                case 6: // double
                    skip(8);
                    i++; // double slot
                    break;
                case 7: // Class
                case 8: // String
                    // Forward or backward indirect reference to a modified UTF8 entry
                    indirectStringRefs[i] = readUnsignedShort();
                    // (no need to copy bytes over, we use indirectStringRef instead for these fields)
                    break;
                case 9: // field ref
                case 10: // method ref
                case 11: // interface ref
                case 12: // name and type
                    skip(4);
                    break;
                case 15: // method handle
                    skip(3);
                    break;
                case 16: // method type
                    skip(2);
                    break;
                case 18: // invoke dynamic
                    skip(4);
                    break;
                default:
                    throw new RuntimeException("Unknown constant pool tag " + tag + " in classfile " + relativePath
                            + " (element size unknown, cannot continue reading class. Please report this on "
                            + "the FastClasspathScanner GitHub page.");
                }
            }

            // Access flags
            final int flags = readUnsignedShort();
            final boolean isInterface = (flags & 0x0200) != 0;
            final boolean isAnnotation = (flags & 0x2000) != 0;

            // The fully-qualified class name of this class, with slashes replaced with dots
            className = getConstantPoolClassName(readUnsignedShort());
            if ("java.lang.Object".equals(className)) {
                // Don't process java.lang.Object
                return null;
            }

            // Make sure classname matches relative path
            if (!classNameMatches(relativePath)) {
                if (FastClasspathScanner.verbose) {
                    log.log(2, "Class " + className + " is at incorrect relative path " + relativePath
                            + " -- ignoring");
                }
                return null;
            }

            // Superclass name, with slashes replaced with dots
            final String superclassName = getConstantPoolClassName(readUnsignedShort());

            final ClassInfoUnlinked classInfoUnlinked = new ClassInfoUnlinked(className, isInterface, isAnnotation,
                    stringInternMap);

            // Connect class to superclass
            if (scanSpec.classIsNotBlacklisted(superclassName)) {
                classInfoUnlinked.addSuperclass(superclassName);
            }

            // Interfaces
            final int interfaceCount = readUnsignedShort();
            for (int i = 0; i < interfaceCount; i++) {
                final String interfaceName = getConstantPoolClassName(readUnsignedShort());
                if (scanSpec.classIsNotBlacklisted(interfaceName)) {
                    classInfoUnlinked.addImplementedInterface(interfaceName);
                }
            }

            // Fields
            final HashSet<String> staticFinalFieldsToMatch = classNameToStaticFinalFieldsToMatch.get(className);
            final int fieldCount = readUnsignedShort();
            for (int i = 0; i < fieldCount; i++) {
                // Info on accessFlags: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6
                final int accessFlags = readUnsignedShort();
                final boolean isPublicField = ((accessFlags & 0x0002) == 0x0002);
                final boolean scanField = isPublicField || scanSpec.ignoreFieldVisibility;
                if (!scanField) {
                    // Skip field
                    readUnsignedShort(); // fieldNameConstantPoolIdx
                    readUnsignedShort(); // fieldTypeDescriptorConstantPoolIdx
                    final int attributesCount = readUnsignedShort();
                    for (int j = 0; j < attributesCount; j++) {
                        readUnsignedShort(); // attributeNameConstantPoolIdx
                        final int attributeLength = readInt();
                        skip(attributeLength);
                    }
                } else {
                    final boolean isStaticFinalField = ((accessFlags & 0x0018) == 0x0018);
                    final int fieldNameConstantPoolIdx = readUnsignedShort();
                    String fieldName = null;
                    boolean isMatchedFieldName = false;
                    if (staticFinalFieldsToMatch != null && scanField) {
                        fieldName = getConstantPoolString(fieldNameConstantPoolIdx);
                        if (staticFinalFieldsToMatch.contains(fieldName)) {
                            isMatchedFieldName = true;
                        }
                    }
                    final int fieldTypeDescriptorConstantPoolIdx = readUnsignedShort();
                    final String fieldTypeDescriptor = scanField
                            ? getConstantPoolString(fieldTypeDescriptorConstantPoolIdx) : null;
                    final int attributesCount = readUnsignedShort();

                    // Check if the type of this field falls within a non-blacklisted package,
                    // and if so, record the field and its type
                    addFieldTypeDescriptorParts(classInfoUnlinked, fieldTypeDescriptor);

                    // Check if field is static and final
                    if (!isStaticFinalField && isMatchedFieldName) {
                        // Requested to match a field that is not static or not final
                        log.log(2,
                                "Cannot match requested field " + classInfoUnlinked.className + "."
                                        + getConstantPoolString(fieldNameConstantPoolIdx)
                                        + " because it is either not static or not final");
                    }
                    boolean foundConstantValue = false;
                    for (int j = 0; j < attributesCount; j++) {
                        final int attributeNameConstantPoolIdx = readUnsignedShort();
                        final int attributeLength = readInt();
                        // See if field name matches one of the requested names for this class, and if it does,
                        // check if it is initialized with a constant value
                        if (isStaticFinalField && isMatchedFieldName
                                && constantPoolStringEquals(attributeNameConstantPoolIdx, "ConstantValue")) {
                            // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.2
                            Object constValue = getConstantPoolValue(readUnsignedShort());
                            // byte, char, short and boolean constants are all stored as 4-byte int
                            // values -- coerce and wrap in the proper wrapper class with autoboxing
                            switch (fieldTypeDescriptor.charAt(0)) {
                            case 'B':
                                // byte, char, short and boolean constants are all stored as 4-byte int values.
                                // Convert and wrap in Byte object.
                                constValue = new Byte(((Integer) constValue).byteValue());
                                break;
                            case 'C':
                                // byte, char, short and boolean constants are all stored as 4-byte int values.
                                // Convert and wrap in Character object.
                                constValue = new Character((char) ((Integer) constValue).intValue());
                                break;
                            case 'S':
                                // byte, char, short and boolean constants are all stored as 4-byte int values.
                                // Convert and wrap in Short object.
                                constValue = new Short(((Integer) constValue).shortValue());
                                break;
                            case 'Z':
                                // byte, char, short and boolean constants are all stored as 4-byte int values.
                                // Convert and wrap in Boolean object.
                                constValue = new Boolean(((Integer) constValue).intValue() != 0);
                                break;
                            case 'I':
                            case 'J':
                            case 'F':
                            case 'D':
                                // int, long, float or double are already in correct wrapper type (Integer, Long,
                                // Float, Double or String) -- nothing to do
                                break;
                            default:
                                if ("Ljava/lang/String;".equals(fieldTypeDescriptor)) {
                                    // String constants are already in correct form, nothing to do
                                } else {
                                    // Should never happen, constant values can only be stored as an int, long,
                                    // float, double or String
                                    throw new RuntimeException("Unknown constant initializer type "
                                            + fieldTypeDescriptor + " for class " + className
                                            + " -- please report this on the FastClasspathScanner GitHub page.");
                                }
                                break;
                            }
                            // Store static final field match in ClassInfo object
                            classInfoUnlinked.addFieldConstantValue(fieldName, constValue);
                            foundConstantValue = true;
                        } else if (constantPoolStringEquals(attributeNameConstantPoolIdx, "Signature")) {
                            // Check if the type signature of this field falls within a non-blacklisted
                            // package, and if so, record the field type. The type signature contains
                            // type parameters, whereas the type descriptor does not.
                            final String fieldTypeSignature = getConstantPoolString(readUnsignedShort());
                            addFieldTypeDescriptorParts(classInfoUnlinked, fieldTypeSignature);
                        } else {
                            // No match, just skip attribute
                            skip(attributeLength);
                        }
                        if (!foundConstantValue && isStaticFinalField && isMatchedFieldName) {
                            log.log(2,
                                    "Requested static final field " + classInfoUnlinked.className + "."
                                            + getConstantPoolString(fieldNameConstantPoolIdx)
                                            + " is not initialized with a constant literal value, so there is no "
                                            + "initializer value in the constant pool of the classfile");
                        }
                    }
                }
            }

            // Methods
            final int methodCount = readUnsignedShort();
            for (int i = 0; i < methodCount; i++) {
                skip(6); // access_flags, name_index, descriptor_index
                final int attributesCount = readUnsignedShort();
                for (int j = 0; j < attributesCount; j++) {
                    skip(2); // attribute_name_index
                    final int attributeLength = readInt();
                    skip(attributeLength);
                }
            }

            // Attributes (including class annotations)
            final int attributesCount = readUnsignedShort();
            for (int i = 0; i < attributesCount; i++) {
                final int attributeNameConstantPoolIdx = readUnsignedShort();
                final int attributeLength = readInt();
                if (constantPoolStringEquals(attributeNameConstantPoolIdx, "RuntimeVisibleAnnotations")) {
                    final int annotationCount = readUnsignedShort();
                    for (int m = 0; m < annotationCount; m++) {
                        final String annotationName = readAnnotation();
                        // Add non-blacklisted annotations; by default, "java.*" and "sun.*" are blacklisted,
                        // so java.lang.annotation annotations will be ignored (Target/Retention/Documented etc.)
                        if (scanSpec.classIsNotBlacklisted(annotationName)) {
                            classInfoUnlinked.addAnnotation(annotationName);
                        }
                    }
                } else {
                    skip(attributeLength);
                }
            }
            return classInfoUnlinked;

        } catch (final Exception e) {
            log.log(2, "Exception while attempting to load classfile " + relativePath + ": " + e);
            return null;
        }
    }
}
