package com.backupmanager.db.mysql;

import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HexFormat;

/** Renders JDBC values as MySQL literals for a generated dump. */
final class SqlValueWriter {

    private SqlValueWriter() {
    }

    /**
     * Formats one column of the current row as a SQL literal.
     *
     * <p>Binary columns become {@code 0x…} hex literals rather than quoted strings: a BLOB
     * containing a zero byte or an invalid UTF-8 sequence cannot survive being quoted, and
     * silently corrupting image or document columns is the kind of failure nobody notices
     * until the restore.
     */
    static String literal(ResultSet rs, int column, int sqlType) throws SQLException {
        switch (sqlType) {
            case Types.NULL:
                return "NULL";

            case Types.BIT:
            case Types.BOOLEAN: {
                boolean value = rs.getBoolean(column);
                return rs.wasNull() ? "NULL" : (value ? "1" : "0");
            }

            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:
            case Types.NUMERIC:
            case Types.DECIMAL: {
                String value = rs.getString(column);
                return rs.wasNull() || value == null ? "NULL" : value;
            }

            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY: {
                byte[] bytes = rs.getBytes(column);
                return bytes == null ? "NULL" : hexLiteral(bytes);
            }

            case Types.BLOB: {
                Blob blob = rs.getBlob(column);
                if (blob == null) {
                    return "NULL";
                }
                try {
                    long length = blob.length();
                    if (length == 0) {
                        return "''";
                    }
                    if (length > Integer.MAX_VALUE) {
                        throw new SQLException("BLOB larger than 2 GB cannot be dumped as a literal");
                    }
                    return hexLiteral(blob.getBytes(1, (int) length));
                } finally {
                    try {
                        blob.free();
                    } catch (SQLException | UnsupportedOperationException ignored) {
                        // Some drivers do not implement free(); nothing to do.
                    }
                }
            }

            default: {
                String value = rs.getString(column);
                return value == null ? "NULL" : quote(value);
            }
        }
    }

    private static String hexLiteral(byte[] bytes) {
        if (bytes.length == 0) {
            return "''";
        }
        return "0x" + HexFormat.of().formatHex(bytes);
    }

    /**
     * Quotes and escapes a string for MySQL.
     *
     * <p>Escapes the backslash first — reversing the order would double-escape everything
     * the later rules insert.
     */
    static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        sb.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\0' -> sb.append("\\0");
                case 0x1A -> sb.append("\\Z");
                default -> sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    /** Wraps an identifier in backticks, doubling any the name already contains. */
    static String identifier(String name) {
        return '`' + name.replace("`", "``") + '`';
    }
}
