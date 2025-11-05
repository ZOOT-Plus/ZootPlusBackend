package plus.maa.backend.config

import org.ktorm.database.Database
import org.ktorm.expression.SqlExpression
import org.ktorm.expression.SqlFormatter
import org.ktorm.support.postgresql.PostgreSqlDialect
import org.ktorm.support.postgresql.PostgreSqlFormatter
import plus.maa.backend.common.extensions.JsonbContainsExpression

class JsonbSqlFormatter(
    database: Database,
    beautifySql: Boolean,
    indentSize: Int,
) : PostgreSqlFormatter(database, beautifySql, indentSize) {

    override fun visitUnknown(expr: SqlExpression): SqlExpression {
        return when (expr) {
            is JsonbContainsExpression -> visitJsonbContains(expr)
            else -> super.visitUnknown(expr)
        }
    }

    private fun visitJsonbContains(expr: JsonbContainsExpression): JsonbContainsExpression {
        if (expr.left.removeBrackets) {
            visit(expr.left)
        } else {
            write("(")
            visit(expr.left)
            write(")")
        }

        write(" @> ")
        write("'")
        write(expr.right.replace("'", "''"))
        write("'::jsonb")

        return expr
    }
}

object JsonbPostgreSqlDialect : PostgreSqlDialect() {
    override fun createSqlFormatter(database: Database, beautifySql: Boolean, indentSize: Int): SqlFormatter {
        return JsonbSqlFormatter(database, beautifySql, indentSize)
    }
}
