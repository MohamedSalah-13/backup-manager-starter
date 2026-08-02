package com.backupmanager.db.mysql;

import com.backupmanager.core.api.RestoreContext;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Statement splitting is where a restore quietly goes wrong: cut in the wrong place and
 * you get a syntax error at best, or a truncated routine body at worst.
 */
class SqlScriptRunnerTest {

    private final List<String> executed = new ArrayList<>();

    private List<String> split(String script) throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        doAnswer(invocation -> {
            executed.add(invocation.getArgument(0));
            return true;
        }).when(statement).execute(anyString());

        RestoreContext ctx = new RestoreContext(null, null, null, null);
        new SqlScriptRunner(connection, false)
                .run(new BufferedReader(new StringReader(script)), ctx);
        return executed;
    }

    @Test
    void splitsOnSemicolons() throws Exception {
        List<String> statements = split("SELECT 1; SELECT 2; SELECT 3;");

        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).isEqualTo("SELECT 1");
    }

    @Test
    void keepsSemicolonsInsideStringLiterals() throws Exception {
        List<String> statements = split("INSERT INTO t VALUES ('a;b;c');");

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("'a;b;c'");
    }

    @Test
    void handlesEscapedQuotesInsideLiterals() throws Exception {
        List<String> statements = split("INSERT INTO t VALUES ('it\\'s here; really');");

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("it\\'s here; really");
    }

    @Test
    void keepsSemicolonsInsideBacktickedIdentifiers() throws Exception {
        List<String> statements = split("SELECT `weird;column` FROM t;");

        assertThat(statements).hasSize(1);
    }

    @Test
    void skipsLineComments() throws Exception {
        List<String> statements = split("""
                -- this is a comment; with a semicolon
                SELECT 1;
                # another comment;
                SELECT 2;
                """);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).isEqualTo("SELECT 1");
    }

    @Test
    void keepsExecutableComments() throws Exception {
        // "/*!40101 ... */" is a version-gated statement MySQL runs; dropping it would
        // silently discard the character set and foreign key settings a dump relies on.
        List<String> statements = split("/*!40101 SET NAMES utf8mb4 */;");

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("SET NAMES utf8mb4");
    }

    @Test
    void honoursDelimiterDirectivesAroundRoutineBodies() throws Exception {
        List<String> statements = split("""
                DROP PROCEDURE IF EXISTS p;
                DELIMITER ;;
                CREATE PROCEDURE p()
                BEGIN
                  SELECT 1;
                  SELECT 2;
                END;;
                DELIMITER ;
                SELECT 'after';
                """);

        // The routine must arrive as one statement, not split at its internal semicolons.
        assertThat(statements).hasSize(3);
        assertThat(statements.get(1)).contains("SELECT 1;").contains("SELECT 2;").contains("END");
        assertThat(statements.get(2)).contains("after");
    }

    @Test
    void runsAFinalStatementWithNoTrailingDelimiter() throws Exception {
        List<String> statements = split("SELECT 1");

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).isEqualTo("SELECT 1");
    }

    @Test
    void ignoresBlankLinesAndEmptyStatements() throws Exception {
        List<String> statements = split("\n\n;\nSELECT 1;\n\n;;\n");

        assertThat(statements).containsExactly("SELECT 1");
    }

    @Test
    void skipsBlockComments() throws Exception {
        List<String> statements = split("/* a comment; with semicolons; */ SELECT 1;");

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("SELECT 1");
    }

    @Test
    void continuesPastFailuresWhenAsked() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("BOOM")) {
                throw new SQLException("deliberate failure");
            }
            executed.add(sql);
            return true;
        }).when(statement).execute(anyString());

        SqlScriptRunner runner = new SqlScriptRunner(connection, true);
        runner.run(new BufferedReader(new StringReader("SELECT 1; BOOM; SELECT 2;")),
                new RestoreContext(null, null, null, null));

        assertThat(executed).containsExactly("SELECT 1", "SELECT 2");
        assertThat(runner.getFailed()).isEqualTo(1);
        assertThat(runner.getExecuted()).isEqualTo(2);
    }

    @Test
    void stopsAtTheFirstFailureByDefault() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        doAnswer(invocation -> {
            throw new SQLException("deliberate failure");
        }).when(statement).execute(anyString());

        SqlScriptRunner runner = new SqlScriptRunner(connection, false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        runner.run(new BufferedReader(new StringReader("SELECT 1; SELECT 2;")),
                                new RestoreContext(null, null, null, null)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Statement failed during restore");
    }
}
