/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/
package net.hydromatic.optiq.prepare;

import net.hydromatic.linq4j.*;
import net.hydromatic.linq4j.expressions.*;

import net.hydromatic.optiq.*;
import net.hydromatic.optiq.impl.java.JavaTypeFactory;
import net.hydromatic.optiq.jdbc.Helper;
import net.hydromatic.optiq.jdbc.OptiqPrepare;
import net.hydromatic.optiq.rules.java.*;
import net.hydromatic.optiq.runtime.Executable;

import openjava.ptree.ClassDeclaration;

import org.codehaus.janino.*;

import org.eigenbase.oj.stmt.*;
import org.eigenbase.rel.*;
import org.eigenbase.rel.rules.TableAccessRule;
import org.eigenbase.relopt.*;
import org.eigenbase.relopt.volcano.VolcanoPlanner;
import org.eigenbase.reltype.RelDataType;
import org.eigenbase.reltype.RelDataTypeFactory;
import org.eigenbase.reltype.RelDataTypeField;
import org.eigenbase.rex.RexBuilder;
import org.eigenbase.rex.RexNode;
import org.eigenbase.sql.*;
import org.eigenbase.sql.fun.SqlStdOperatorTable;
import org.eigenbase.sql.parser.SqlParseException;
import org.eigenbase.sql.parser.SqlParser;
import org.eigenbase.sql.type.SqlTypeName;
import org.eigenbase.sql.validate.*;
import org.eigenbase.sql2rel.SqlToRelConverter;
import org.eigenbase.util.Pair;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;

/**
 * Shit just got real.
 *
 * @author jhyde
 */
class OptiqPrepareImpl implements OptiqPrepare {

    public <T> PrepareResult<T> prepareQueryable(
        Context context,
        Queryable<T> queryable)
    {
        return prepare_(context, null, queryable, queryable.getElementType());
    }

    public <T> PrepareResult<T> prepareSql(
        Context context,
        String sql,
        Queryable<T> expression,
        Type elementType)
    {
        return prepare_(context, sql, expression, elementType);
    }

    <T> PrepareResult<T> prepare_(
        Context context,
        String sql,
        Queryable<T> queryable,
        Type elementType)
    {
        final JavaTypeFactory typeFactory = context.getTypeFactory();
        OptiqCatalogReader catalogReader =
            new OptiqCatalogReader(
                context.getRootSchema(),
                typeFactory);
        RelOptConnectionImpl relOptConnection =
            new RelOptConnectionImpl(catalogReader);
        final OptiqPreparingStmt preparingStmt =
            new OptiqPreparingStmt(
                relOptConnection,
                typeFactory,
                context.getRootSchema());
        preparingStmt.setResultCallingConvention(CallingConvention.ENUMERABLE);

        final RelDataType x;
        final PreparedResult preparedResult;
        if (sql != null) {
            assert queryable == null;
            SqlParser parser = new SqlParser(sql);
            SqlNode sqlNode;
            try {
                sqlNode = parser.parseQuery();
            } catch (SqlParseException e) {
                throw new RuntimeException("parse failed", e);
            }
            SqlValidator validator =
                new SqlValidatorImpl(
                    SqlStdOperatorTable.instance(), catalogReader, typeFactory,
                    SqlConformance.Default) { };
            preparedResult = preparingStmt.prepareSql(
                sqlNode, Object.class, validator, true);
            x = validator.getValidatedNodeType(sqlNode);
        } else {
            assert queryable != null;
            x = context.getTypeFactory().createType(elementType);
            preparedResult =
                preparingStmt.prepareQueryable(queryable, x);
        }

        // TODO: parameters
        final List<Parameter> parameters = Collections.emptyList();
        // TODO: column meta data
        final List<ColumnMetaData> columns =
            new ArrayList<ColumnMetaData>();
        RelDataType jdbcType = makeStruct(typeFactory, x);
        for (RelDataTypeField field : jdbcType.getFields()) {
            RelDataType type = field.getType();
            SqlTypeName sqlTypeName = type.getSqlTypeName();
            columns.add(
                new ColumnMetaData(
                    columns.size(),
                    false,
                    true,
                    false,
                    false,
                    type.isNullable() ? 1 : 0,
                    true,
                    0,
                    field.getName(),
                    null,
                    null,
                    sqlTypeName.allowsPrec() && false
                        ? type.getPrecision()
                        : -1,
                    sqlTypeName.allowsScale() ? type.getScale() : -1,
                    null,
                    null,
                    sqlTypeName.getJdbcOrdinal(),
                    sqlTypeName.getName(),
                    true,
                    false,
                    false,
                    null));
        }
        return new PrepareResult<T>(
            sql,
            parameters,
            columns,
            (Enumerable<T>) preparedResult.execute());
    }

    private static RelDataType makeStruct(
        RelDataTypeFactory typeFactory,
        RelDataType type)
    {
        if (type.isStruct()) {
            return type;
        }
        return typeFactory.createStructType(
            RelDataTypeFactory.FieldInfoBuilder.of("$0", type));
    }

    private static class OptiqPreparingStmt extends OJPreparingStmt {
        private final RelOptPlanner planner;
        private final RexBuilder rexBuilder;
        private final Schema schema;

        public OptiqPreparingStmt(
            RelOptConnection connection,
            RelDataTypeFactory typeFactory,
            Schema schema)
        {
            super(connection);
            this.schema = schema;
            planner = new VolcanoPlanner();
            planner.addRelTraitDef(CallingConventionTraitDef.instance);
            RelOptUtil.registerAbstractRels(planner);
            planner.addRule(JavaRules.ENUMERABLE_JOIN_RULE);
            planner.addRule(JavaRules.ENUMERABLE_CALC_RULE);
            planner.addRule(JavaRules.ENUMERABLE_AGGREGATE_RULE);
            planner.addRule(JavaRules.ENUMERABLE_SORT_RULE);
            planner.addRule(JavaRules.ENUMERABLE_UNION_RULE);
            planner.addRule(JavaRules.ENUMERABLE_INTERSECT_RULE);
            planner.addRule(JavaRules.ENUMERABLE_MINUS_RULE);
            planner.addRule(TableAccessRule.instance);

            rexBuilder = new RexBuilder(typeFactory);
        }

        public PreparedResult prepareQueryable(
            Queryable queryable,
            RelDataType resultType)
        {
            queryString = null;
            Class runtimeContextClass = connection.getClass();
            final Argument [] arguments = {
                new Argument(
                    connectionVariable,
                    runtimeContextClass,
                    connection)
            };
            ClassDeclaration decl = init(arguments);

            final RelOptQuery query = new RelOptQuery(planner);
            final RelOptCluster cluster =
                query.createCluster(
                    env, rexBuilder.getTypeFactory(), rexBuilder);

            RelNode rootRel =
                new LixToRelTranslator(cluster, connection)
                    .translate(queryable);

            if (timingTracer != null) {
                timingTracer.traceTime("end sql2rel");
            }

            final RelDataType jdbcType =
                makeStruct(rexBuilder.getTypeFactory(), resultType);
            fieldOrigins = Collections.nCopies(jdbcType.getFieldCount(), null);

            // Structured type flattening, view expansion, and plugging in
            // physical storage.
            rootRel = flattenTypes(rootRel, true);

            rootRel = optimize(resultType, rootRel);
            containsJava = treeContainsJava(rootRel);

            if (timingTracer != null) {
                timingTracer.traceTime("end optimization");
            }

            return implement(
                resultType,
                rootRel,
                SqlKind.SELECT,
                decl,
                arguments);
        }

        @Override
        protected SqlToRelConverter getSqlToRelConverter(
            SqlValidator validator, RelOptConnection connection)
        {
            return new SqlToRelConverter(
                validator,
                connection.getRelOptSchema(),
                env, planner,
                connection, rexBuilder);
        }

        @Override
        protected EnumerableRelImplementor getRelImplementor(
            RexBuilder rexBuilder)
        {
            return new EnumerableRelImplementor(rexBuilder);
        }

        @Override
        protected String getClassRoot() {
            return null;
        }

        @Override
        protected String getCompilerClassName() {
            return "org.eigenbase.javac.JaninoCompiler";
        }

        @Override
        protected String getJavaRoot() {
            return null;
        }

        @Override
        protected String getTempPackageName() {
            return "foo";
        }

        @Override
        protected String getTempMethodName() {
            return null;
        }

        @Override
        protected String getTempClassName() {
            return "Foo";
        }

        @Override
        protected boolean shouldAlwaysWriteJavaFile() {
            return false;
        }

        @Override
        protected boolean shouldSetConnectionInfo() {
            return false;
        }

        @Override
        protected RelNode flattenTypes(
            RelNode rootRel,
            boolean restructure)
        {
            return rootRel;
        }

        @Override
        protected RelNode decorrelate(SqlNode query, RelNode rootRel) {
            return rootRel;
        }

        @Override
        protected PreparedExecution implement(
            RelDataType rowType,
            RelNode rootRel,
            SqlKind sqlKind,
            ClassDeclaration decl,
            Argument[] args)
        {
            RelDataType resultType = rootRel.getRowType();
            boolean isDml = sqlKind.belongsTo(SqlKind.DML);
            javaCompiler = createCompiler();
            EnumerableRelImplementor relImplementor =
                getRelImplementor(rootRel.getCluster().getRexBuilder());
            BlockExpression expr =
                relImplementor.implementRoot((EnumerableRel) rootRel);
            ParameterExpression root0 =
                Expressions.parameter(DataContext.class, "root0");
            String s = Expressions.toString(
                Blocks.create(
                    Expressions.declare(
                        Modifier.FINAL,
                        (ParameterExpression) schema.getExpression(),
                        root0),
                    expr),
                false);
            System.out.println(s);

            final Executable executable;
            try {
                executable = (Executable)
                    ExpressionEvaluator.createFastScriptEvaluator(
                        s, Executable.class, new String[]{root0.name});
            } catch (Exception e) {
                throw Helper.INSTANCE.wrap(
                    "Error while compiling generated Java code:\n" + s, e);
            }

            if (timingTracer != null) {
                timingTracer.traceTime("end codegen");
            }

            if (timingTracer != null) {
                timingTracer.traceTime("end compilation");
            }

            return new PreparedExecution(
                null,
                rootRel,
                resultType,
                isDml,
                mapTableModOp(isDml, sqlKind),
                null)
            {
                public Object execute() {
                    return executable.execute(schema);
                }
            };
        }
    }

    static class RelOptTableImpl
        implements SqlValidatorTable, RelOptTable
    {
        private final RelOptSchema schema;
        private final RelDataType rowType;
        private final String[] names;
        private final Expression expression;

        RelOptTableImpl(
            RelOptSchema schema,
            RelDataType rowType,
            String[] names,
            Expression expression)
        {
            this.schema = schema;
            this.rowType = rowType;
            this.names = names;
            this.expression = expression;
        }

        public double getRowCount() {
            return 100;
        }

        public RelOptSchema getRelOptSchema() {
            return schema;
        }

        public RelNode toRel(
            RelOptCluster cluster,
            RelOptConnection connection)
        {
            return new JavaRules.EnumerableTableAccessRel(
                cluster, this, connection, expression);
        }

        public List<RelCollation> getCollationList() {
            return Collections.emptyList();
        }

        public RelDataType getRowType() {
            return rowType;
        }

        public String[] getQualifiedName() {
            return names;
        }

        public SqlMonotonicity getMonotonicity(String columnName) {
            return SqlMonotonicity.NotMonotonic;
        }

        public SqlAccessType getAllowedAccess() {
            return SqlAccessType.READ_ONLY;
        }
    }

    private static class OptiqCatalogReader
        implements SqlValidatorCatalogReader, RelOptSchema
    {
        private final Schema schema;
        private final JavaTypeFactory typeFactory;

        public OptiqCatalogReader(
            Schema schema,
            JavaTypeFactory typeFactory)
        {
            super();
            this.schema = schema;
            this.typeFactory = typeFactory;
        }

        public RelOptTableImpl getTable(final String[] names) {
            List<Pair<String, Object>> pairs =
                new ArrayList<Pair<String, Object>>();
            Schema schema2 = schema;
            for (int i = 0; i < names.length; i++) {
                final String name = names[i];
                Schema subSchema = schema2.getSubSchema(name);
                if (subSchema != null) {
                    pairs.add(Pair.<String, Object>of(name, subSchema));
                    schema2 = subSchema;
                    continue;
                }
                final Table table = schema2.getTable(name);
                if (table != null) {
                    pairs.add(Pair.<String, Object>of(name, table));
                    if (i != names.length - 1) {
                        // not enough objects to match all names
                        return null;
                    }
                    return new RelOptTableImpl(
                        this,
                        typeFactory.createType(table.getElementType()),
                        names,
                        table.getExpression());
                }
                return null;
            }
            return null;
        }

        private Expression toEnumerable(Expression expression) {
            Type type = expression.getType();
            if (Types.isAssignableFrom(Enumerable.class, type)) {
                return expression;
            }
            if (Types.isArray(type)) {
                return Expressions.call(
                    Linq4j.class,
                    "asEnumerable3", // FIXME
                    Collections.singletonList(expression));
            }
            throw new RuntimeException(
                "cannot convert expression [" + expression + "] to enumerable");
        }

        public RelDataType getNamedType(SqlIdentifier typeName) {
            return null;
        }

        public List<SqlMoniker> getAllSchemaObjectNames(List<String> names) {
            return null;
        }

        public String getSchemaName() {
            return null;
        }

        public RelOptTableImpl getTableForMember(String[] names) {
            return getTable(names);
        }

        public RelDataTypeFactory getTypeFactory() {
            return typeFactory;
        }

        public void registerRules(RelOptPlanner planner) throws Exception {
        }
    }

    private static class RelOptConnectionImpl implements RelOptConnection {
        private final RelOptSchema schema;

        public RelOptConnectionImpl(RelOptSchema schema) {
            this.schema = schema;
        }

        public RelOptSchema getRelOptSchema() {
            return schema;
        }

        public Object contentsAsArray(String qualifier, String tableName) {
            return null;
        }
    }

    interface ScalarTranslator {
        RexNode toRex(BlockExpression expression);
        RexNode toRex(Expression expression);
        ScalarTranslator bind(
            List<ParameterExpression> parameterList, List<RexNode> values);
    }

    static class EmptyScalarTranslator implements ScalarTranslator {
        private final RexBuilder rexBuilder;

        public EmptyScalarTranslator(RexBuilder rexBuilder) {
            this.rexBuilder = rexBuilder;
        }

        public static ScalarTranslator empty(RexBuilder builder) {
            return new EmptyScalarTranslator(builder);
        }

        public RexNode toRex(BlockExpression expression) {
            return toRex(Blocks.simple(expression));
        }

        public RexNode toRex(Expression expression) {
            switch (expression.getNodeType()) {
            case MemberAccess:
                return rexBuilder.makeFieldAccess(
                    toRex(
                        ((MemberExpression) expression).expression),
                    ((MemberExpression) expression).field.getName());
            case GreaterThan:
                return binary(
                    expression, SqlStdOperatorTable.greaterThanOperator);
            case LessThan:
                return binary(expression, SqlStdOperatorTable.lessThanOperator);
            case Parameter:
                return parameter((ParameterExpression) expression);
            case Call:
                MethodCallExpression call = (MethodCallExpression) expression;
                SqlOperator operator =
                    RexToLixTranslator.JAVA_TO_SQL_METHOD_MAP.get(call.method);
                if (operator != null) {
                    return rexBuilder.makeCall(
                        operator,
                        toRex(
                            Expressions.<Expression>list()
                                .appendIfNotNull(call.targetExpression)
                                .appendAll(call.expressions)));
                }
                throw new RuntimeException(
                    "Could translate call to method " + call.method);
            case Constant:
                final ConstantExpression constant =
                    (ConstantExpression) expression;
                Object value = constant.value;
                if (value instanceof Number) {
                    Number number = (Number) value;
                    if (value instanceof Double || value instanceof Float) {
                        return rexBuilder.makeApproxLiteral(
                            BigDecimal.valueOf(number.doubleValue()));
                    } else if (value instanceof BigDecimal) {
                        return rexBuilder.makeExactLiteral((BigDecimal) value);
                    } else {
                        return rexBuilder.makeExactLiteral(
                            BigDecimal.valueOf(number.longValue()));
                    }
                } else if (value instanceof Boolean) {
                    return rexBuilder.makeLiteral((Boolean) value);
                } else {
                    return rexBuilder.makeLiteral(constant.toString());
                }
            default:
                throw new UnsupportedOperationException(
                    "unknown expression type " + expression.getNodeType() + " "
                    + expression);
            }
        }

        private RexNode binary(Expression expression, SqlBinaryOperator op) {
            BinaryExpression call = (BinaryExpression) expression;
            return rexBuilder.makeCall(
                op, toRex(Arrays.asList(call.expression0, call.expression1)));
        }

        private List<RexNode> toRex(List<Expression> expressions) {
            ArrayList<RexNode> list = new ArrayList<RexNode>();
            for (Expression expression : expressions) {
                list.add(toRex(expression));
            }
            return list;
        }

        public ScalarTranslator bind(
            List<ParameterExpression> parameterList, List<RexNode> values)
        {
            return new LambdaScalarTranslator(
                rexBuilder, parameterList, values);
        }

        public RexNode parameter(ParameterExpression param) {
            throw new RuntimeException("unknown parameter " + param);
        }
    }

    private static class LambdaScalarTranslator extends EmptyScalarTranslator {
        private final List<ParameterExpression> parameterList;
        private final List<RexNode> values;

        public LambdaScalarTranslator(
            RexBuilder rexBuilder,
            List<ParameterExpression> parameterList,
            List<RexNode> values)
        {
            super(rexBuilder);
            this.parameterList = parameterList;
            this.values = values;
        }

        public RexNode parameter(ParameterExpression param) {
            int i = parameterList.indexOf(param);
            if (i >= 0) {
                return values.get(i);
            }
            throw new RuntimeException("unknown parameter " + param);
        }
    }

}

// End OptiqPrepareImpl.java