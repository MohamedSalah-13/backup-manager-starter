package com.backupmanager.db.mysql;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlValueWriterTest {

    @Test
    void quotesAndEscapesSpecialCharacters() {
        assertThat(SqlValueWriter.quote("plain")).isEqualTo("'plain'");
        assertThat(SqlValueWriter.quote("it's")).isEqualTo("'it\\'s'");
        assertThat(SqlValueWriter.quote("back\\slash")).isEqualTo("'back\\\\slash'");
        assertThat(SqlValueWriter.quote("line\nbreak")).isEqualTo("'line\\nbreak'");
        assertThat(SqlValueWriter.quote("tab\there")).isEqualTo("'tab\\there'");
        assertThat(SqlValueWriter.quote("nul\0byte")).isEqualTo("'nul\\0byte'");
    }

    @Test
    void escapesBackslashesBeforeTheEscapesItIntroduces() {
        // A backslash followed by a quote must not become a double-escaped mess.
        assertThat(SqlValueWriter.quote("\\'")).isEqualTo("'\\\\\\''");
    }

    @Test
    void quotesIdentifiersWithBackticks() {
        assertThat(SqlValueWriter.identifier("orders")).isEqualTo("`orders`");
        assertThat(SqlValueWriter.identifier("we`ird")).isEqualTo("`we``ird`");
    }

    @Test
    void rendersBinaryColumnsAsHexLiterals() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBytes(1)).thenReturn(new byte[]{(byte) 0xDE, (byte) 0xAD, 0x00, (byte) 0xBE});

        // Quoting these bytes as a string would corrupt them; hex is the only safe form.
        assertThat(SqlValueWriter.literal(rs, 1, Types.VARBINARY)).isEqualTo("0xdead00be");
    }

    @Test
    void rendersEmptyBinaryAsAnEmptyString() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBytes(1)).thenReturn(new byte[0]);

        // "0x" alone is not valid MySQL syntax.
        assertThat(SqlValueWriter.literal(rs, 1, Types.VARBINARY)).isEqualTo("''");
    }

    @Test
    void rendersNullsAsNullKeyword() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(null);
        when(rs.getBytes(1)).thenReturn(null);

        assertThat(SqlValueWriter.literal(rs, 1, Types.VARCHAR)).isEqualTo("NULL");
        assertThat(SqlValueWriter.literal(rs, 1, Types.VARBINARY)).isEqualTo("NULL");
    }

    @Test
    void rendersNumbersWithoutQuotes() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("42.50");
        when(rs.wasNull()).thenReturn(false);

        assertThat(SqlValueWriter.literal(rs, 1, Types.DECIMAL)).isEqualTo("42.50");
    }

    @Test
    void rendersBooleansAsIntegers() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBoolean(1)).thenReturn(true);
        when(rs.wasNull()).thenReturn(false);

        assertThat(SqlValueWriter.literal(rs, 1, Types.BOOLEAN)).isEqualTo("1");
    }

    @Test
    void rendersNullNumericAsNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(null);
        when(rs.wasNull()).thenReturn(true);

        assertThat(SqlValueWriter.literal(rs, 1, Types.INTEGER)).isEqualTo("NULL");
    }
}
