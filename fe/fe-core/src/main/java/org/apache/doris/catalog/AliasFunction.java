// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.catalog;

import org.apache.doris.analysis.CastExpr;
import org.apache.doris.analysis.Expr;
import org.apache.doris.analysis.FunctionCallExpr;
import org.apache.doris.analysis.FunctionName;
import org.apache.doris.analysis.SelectStmt;
import org.apache.doris.analysis.SlotRef;
import org.apache.doris.analysis.SqlParser;
import org.apache.doris.analysis.SqlScanner;
import org.apache.doris.analysis.TypeDef;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.io.Text;
import org.apache.doris.common.util.SqlParserUtils;
import org.apache.doris.qe.SqlModeHelper;
import org.apache.doris.thrift.TFunctionBinaryType;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.gson.Gson;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Internal representation of an alias function.
 */
public class AliasFunction extends Function {
    private static final Logger LOG = LogManager.getLogger(AliasFunction.class);

    private static final String DIGITAL_MASKING = "digital_masking";

    private Expr originFunction;
    private List<String> parameters = new ArrayList<>();
    private List<String> typeDefParams = new ArrayList<>();

    // Only used for serialization
    protected AliasFunction() {
    }

    public AliasFunction(FunctionName fnName, List<Type> argTypes, Type retType, boolean hasVarArgs) {
        super(fnName, argTypes, retType, hasVarArgs);
    }

    public static AliasFunction createFunction(FunctionName functionName, Type[] argTypes, Type retType,
                                               boolean hasVarArgs, List<String> parameters, Expr originFunction) {
        AliasFunction aliasFunction = new AliasFunction(functionName, Arrays.asList(argTypes), retType, hasVarArgs);
        aliasFunction.setBinaryType(TFunctionBinaryType.NATIVE);
        aliasFunction.setUserVisible(true);
        aliasFunction.originFunction = originFunction;
        aliasFunction.parameters = parameters;
        return aliasFunction;
    }

    public static void initBuiltins(FunctionSet functionSet) {
        String oriStmt = "select concat(left(id,3),'****',right(id,4));";
        try {
            /**
             * Please ensure that the condition checks in {@link #analyze} are satisfied
             */
            functionSet.addBuiltin(createBuiltin(DIGITAL_MASKING, Lists.newArrayList(Type.INT), Type.VARCHAR,
                    false, Lists.newArrayList("id"), getExpr(oriStmt), true));
        } catch (AnalysisException e) {
            LOG.error("Add builtin alias function error {}", e);
        }
    }

    public static Expr getExpr(String sql) throws AnalysisException {
        SelectStmt parsedStmt;
        // Parse statement with parser generated by CUP&FLEX
        SqlScanner input = new SqlScanner(new StringReader(sql), SqlModeHelper.MODE_DEFAULT);
        SqlParser parser = new SqlParser(input);
        try {
            parsedStmt = (SelectStmt) SqlParserUtils.getFirstStmt(parser);
        } catch (Error e) {
            LOG.info("error happened when parsing stmt {}", sql, e);
            throw new AnalysisException("sql parsing error, please check your sql");
        } catch (AnalysisException e) {
            String syntaxError = parser.getErrorMsg(sql);
            LOG.info("analysis exception happened when parsing stmt {}, error: {}",
                    sql, syntaxError, e);
            if (syntaxError == null) {
                throw  e;
            } else {
                throw new AnalysisException(syntaxError, e);
            }
        } catch (Exception e) {
            // TODO(lingbin): we catch 'Exception' to prevent unexpected error,
            // should be removed this try-catch clause future.
            LOG.info("unexpected exception happened when parsing stmt {}, error: {}",
                    sql, parser.getErrorMsg(sql), e);
            throw new AnalysisException("Unexpected exception: " + e.getMessage());
        }

        return parsedStmt.getSelectList().getItems().get(0).getExpr();
    }

    private static AliasFunction createBuiltin(String name, ArrayList<Type> argTypes, Type retType,
                                              boolean hasVarArgs, List<String> parameters, Expr originFunction,
                                              boolean userVisible) {
        AliasFunction aliasFunction = new AliasFunction(new FunctionName(name), argTypes, retType, hasVarArgs);
        aliasFunction.setBinaryType(TFunctionBinaryType.BUILTIN);
        aliasFunction.setUserVisible(userVisible);
        aliasFunction.originFunction = originFunction;
        aliasFunction.parameters = parameters;
        return aliasFunction;
    }

    public Expr getOriginFunction() {
        return originFunction;
    }

    public void setOriginFunction(Expr originFunction) {
        this.originFunction = originFunction;
    }

    public List<String> getParameters() {
        return parameters;
    }

    public void setParameters(List<String> parameters) {
        this.parameters = parameters;
    }

    public void analyze() throws AnalysisException {
        if (parameters.size() != getArgs().length) {
            throw new AnalysisException("Alias function [" + functionName() + "] args number is not equal to parameters number");
        }
        List<Expr> exprs;
        if (originFunction instanceof FunctionCallExpr) {
            exprs = ((FunctionCallExpr) originFunction).getFnParams().exprs();
        } else if (originFunction instanceof CastExpr) {
            exprs = originFunction.getChildren();
            TypeDef targetTypeDef = ((CastExpr) originFunction).getTargetTypeDef();
            if (targetTypeDef.getType().isScalarType()) {
                ScalarType scalarType = (ScalarType) targetTypeDef.getType();
                PrimitiveType primitiveType = scalarType.getPrimitiveType();
                switch (primitiveType) {
                    case DECIMALV2:
                        if (!Strings.isNullOrEmpty(scalarType.getScalarPrecisionStr())) {
                            typeDefParams.add(scalarType.getScalarPrecisionStr());
                        }
                        if (!Strings.isNullOrEmpty(scalarType.getScalarScaleStr())) {
                            typeDefParams.add(scalarType.getScalarScaleStr());
                        }
                        break;
                    case CHAR:
                    case VARCHAR:
                        if (!Strings.isNullOrEmpty(scalarType.getLenStr())) {
                            typeDefParams.add(scalarType.getLenStr());
                        }
                        break;
                }
            }
        } else {
            throw new AnalysisException("Not supported expr type: " + originFunction);
        }
        Set<String> set = new HashSet<>();
        for (String str : parameters) {
            if (!set.add(str)) {
                throw new AnalysisException("Alias function [" + functionName() + "] has duplicate parameter [" + str + "].");
            }
            boolean existFlag = false;
            // check exprs
            for (Expr expr : exprs) {
                existFlag |= checkParams(expr, str);
            }
            // check targetTypeDef
            for (String typeDefParam : typeDefParams) {
                existFlag |= typeDefParam.equals(str);
            }
            if (!existFlag) {
                throw new AnalysisException("Alias function [" + functionName() + "]  do not contain parameter [" + str + "].");
            }
        }
    }

    private boolean checkParams(Expr expr, String param) {
        for (Expr e : expr.getChildren()) {
            if (checkParams(e, param)) {
                return true;
            }
        }
        if (expr instanceof SlotRef) {
            if (param.equals(((SlotRef) expr).getColumnName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toSql(boolean ifNotExists) {
        setSlotRefLabel(originFunction);
        StringBuilder sb = new StringBuilder("CREATE ALIAS FUNCTION ");
        if (ifNotExists) {
            sb.append("IF NOT EXISTS ");
        }
        sb.append(signatureString())
            .append(" WITH PARAMETER(")
            .append(getParamsSting(parameters))
            .append(") AS ")
            .append(originFunction.toSql())
            .append(";");
        return sb.toString();
    }

    @Override
    public void write(DataOutput output) throws IOException {
        // 1. type
        FunctionType.ALIAS.write(output);
        // 2. parent
        super.writeFields(output);
        // 3. parameter
        output.writeInt(parameters.size());
        for (String p : parameters) {
            Text.writeString(output, p);
        }
        // 4. expr
        Expr.writeTo(originFunction, output);
    }

    @Override
    public void readFields(DataInput input) throws IOException {
        super.readFields(input);
        int counter = input.readInt();
        for (int i = 0; i < counter; i++) {
            parameters.add(Text.readString(input));
        }
        originFunction = Expr.readIn(input);
    }

    @Override
    public String getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("parameter", getParamsSting(parameters));
        setSlotRefLabel(originFunction);
        String functionStr = originFunction.toSql();
        functionStr = functionStr.replaceAll("'", "`");
        properties.put("origin_function", functionStr);
        return new Gson().toJson(properties);
    }

    /**
     * set slotRef label to column name
     * @param expr
     */
    private void setSlotRefLabel(Expr expr) {
        for (Expr e : expr.getChildren()) {
            setSlotRefLabel(e);
        }
        if (expr instanceof SlotRef) {
            ((SlotRef) expr).setLabel("`" + ((SlotRef) expr).getColumnName() + "`");
        }
    }

    private String getParamsSting(List<String> parameters) {
        return parameters.stream()
                .map(String::toString)
                .collect(Collectors.joining(", "));
    }
}