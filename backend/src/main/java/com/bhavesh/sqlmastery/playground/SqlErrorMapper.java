package com.bhavesh.sqlmastery.playground;

import java.sql.SQLException;

import com.bhavesh.sqlmastery.playground.dto.QueryError;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.stereotype.Component;

/**
 * Turns a driver exception into a {@link QueryError}.
 *
 * <p>This is the one class that knows about the PostgreSQL driver's own
 * exception type, which is why the driver is a compile-scope dependency. The
 * reason it is worth that: {@code java.sql.SQLException} gives a message and a
 * SQLSTATE, while {@link ServerErrorMessage} additionally gives the character
 * POSITION where the server stopped parsing, and the HINT. Those two turn
 *
 * <pre>ERROR: column "dept" does not exist</pre>
 *
 * into a caret under the word {@code dept} and the line
 * {@code Perhaps you meant to reference the column "d.dept_name"} — which is
 * frequently the whole answer, and which most tools discard.
 */
@Component
public class SqlErrorMapper {

    private final SqlErrorExplainer explainer;

    public SqlErrorMapper(SqlErrorExplainer explainer) {
        this.explainer = explainer;
    }

    public QueryError toQueryError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage();
        Integer position = null;
        String hint = null;

        if (e instanceof PSQLException psql) {
            ServerErrorMessage server = psql.getServerErrorMessage();
            if (server != null) {
                message = server.getMessage();
                hint = server.getHint();
                int reported = server.getPosition();
                // The driver reports 0 when the server did not supply one, and
                // a position of 0 would put the caret in the wrong place.
                position = reported > 0 ? reported : null;
            }
        }

        return new QueryError(
                sqlState == null ? "SQL_ERROR" : sqlState,
                message,
                explainer.explain(sqlState),
                position,
                hint);
    }
}
