/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.command.query.binary.bind;

import com.google.common.base.Optional;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.shardingproxy.backend.communication.DatabaseCommunicationEngine;
import org.apache.shardingsphere.shardingproxy.backend.communication.DatabaseCommunicationEngineFactory;
import org.apache.shardingsphere.shardingproxy.backend.communication.jdbc.connection.BackendConnection;
import org.apache.shardingsphere.shardingproxy.backend.response.BackendResponse;
import org.apache.shardingsphere.shardingproxy.backend.response.error.ErrorResponse;
import org.apache.shardingsphere.shardingproxy.backend.response.query.QueryData;
import org.apache.shardingsphere.shardingproxy.backend.response.query.QueryHeader;
import org.apache.shardingsphere.shardingproxy.backend.response.query.QueryResponse;
import org.apache.shardingsphere.shardingproxy.backend.response.update.UpdateResponse;
import org.apache.shardingsphere.shardingproxy.context.GlobalContext;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.constant.PostgreSQLColumnType;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.PostgreSQLPacket;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.command.PostgreSQLCommandPacketType;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.command.query.PostgreSQLColumnDescription;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.command.query.PostgreSQLQueryCommandPacket;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.command.query.PostgreSQLRowDescriptionPacket;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.command.query.binary.BinaryStatementRegistry;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.command.query.binary.PostgreSQLBinaryStatement;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.command.query.binary.bind.protocol.PostgreSQLBinaryProtocolValue;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.command.query.binary.bind.protocol.PostgreSQLBinaryProtocolValueFactory;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.command.query.text.PostgreSQLDataRowPacket;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.generic.PostgreSQLCommandCompletePacket;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.packet.generic.PostgreSQLErrorResponsePacket;
import org.apache.shardingsphere.shardingproxy.transport.postgresql.payload.PostgreSQLPacketPayload;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * PostgreSQL command bind packet.
 *
 * @author zhangyonglun
 */
@Slf4j
public final class PostgreSQLComBindPacket implements PostgreSQLQueryCommandPacket {
    
    @Getter
    private final char messageType = PostgreSQLCommandPacketType.BIND.getValue();
    
    @Getter
    private final String statementId;
    
    @Getter
    private final PostgreSQLBinaryStatement binaryStatement;
    
    @Getter
    private List<Object> parameters;
    
    private DatabaseCommunicationEngine databaseCommunicationEngine;
    
    private boolean isQuery;
    
    @Getter
    private final boolean binaryRowData;
    
    public PostgreSQLComBindPacket(final PostgreSQLPacketPayload payload, final BackendConnection backendConnection) throws SQLException {
        payload.readInt4();
        payload.readStringNul();
        statementId = payload.readStringNul();
        int parameterFormatsLength = payload.readInt2();
        for (int i = 0; i < parameterFormatsLength; i++) {
            payload.readInt2();
        }
        binaryStatement = BinaryStatementRegistry.getInstance().get(backendConnection).getBinaryStatement(statementId);
        if (null != binaryStatement && null != binaryStatement.getSql()) {
            parameters = getParameters(payload);
            databaseCommunicationEngine = DatabaseCommunicationEngineFactory.getInstance().newBinaryProtocolInstance(
                backendConnection.getLogicSchema(), binaryStatement.getSql(), parameters, backendConnection);
        }
        int resultFormatsLength = payload.readInt2();
        binaryRowData = resultFormatsLength > 0;
        for (int i = 0; i < resultFormatsLength; i++) {
            payload.readInt2();
        }
    }
    
    private List<Object> getParameters(final PostgreSQLPacketPayload payload) throws SQLException {
        int parametersCount = payload.readInt2();
        List<Object> result = new ArrayList<>(parametersCount);
        for (int parameterIndex = 0; parameterIndex < parametersCount; parameterIndex++) {
            payload.readInt4();
            PostgreSQLBinaryProtocolValue binaryProtocolValue = PostgreSQLBinaryProtocolValueFactory.getBinaryProtocolValue(binaryStatement.getParameterTypes().get(parameterIndex).getColumnType());
            result.add(binaryProtocolValue.read(payload));
        }
        return result;
    }
    
    @Override
    public void write(final PostgreSQLPacketPayload payload) {
    }
    
    @Override
    public Collection<PostgreSQLPacket> execute() {
        log.debug("PostgreSQLComBindPacket received for Sharding-Proxy: {}", statementId);
        if (GlobalContext.getInstance().isCircuitBreak()) {
            return Collections.<PostgreSQLPacket>singletonList(new PostgreSQLErrorResponsePacket());
        }
        if (null != databaseCommunicationEngine) {
            BackendResponse backendResponse = databaseCommunicationEngine.execute();
            if (backendResponse instanceof ErrorResponse) {
                return Collections.<PostgreSQLPacket>singletonList(createErrorPacket((ErrorResponse) backendResponse));
            }
            if (backendResponse instanceof UpdateResponse) {
                return Collections.<PostgreSQLPacket>singletonList(createUpdatePacket((UpdateResponse) backendResponse));
            }
            Optional<PostgreSQLRowDescriptionPacket> result = createQueryPacket((QueryResponse) backendResponse);
            return result.isPresent() ? Collections.<PostgreSQLPacket>singletonList(result.get()) : Collections.<PostgreSQLPacket>emptyList();
        }
        return Collections.<PostgreSQLPacket>singletonList(new PostgreSQLBindCompletePacket());
    }
    
    private PostgreSQLErrorResponsePacket createErrorPacket(final ErrorResponse errorResponse) {
        return new PostgreSQLErrorResponsePacket();
    }
    
    private PostgreSQLCommandCompletePacket createUpdatePacket(final UpdateResponse updateResponse) {
        return new PostgreSQLCommandCompletePacket();
    }
    
    private Optional<PostgreSQLRowDescriptionPacket> createQueryPacket(final QueryResponse queryResponse) {
        List<PostgreSQLColumnDescription> columnDescriptions = getPostgreSQLColumnDescriptions(queryResponse);
        isQuery = !columnDescriptions.isEmpty();
        if (columnDescriptions.isEmpty() || !isBinaryRowData()) {
            return Optional.absent();
        }
        return Optional.of(new PostgreSQLRowDescriptionPacket(columnDescriptions.size(), columnDescriptions));
    }
    
    private List<PostgreSQLColumnDescription> getPostgreSQLColumnDescriptions(final QueryResponse queryResponse) {
        List<PostgreSQLColumnDescription> result = new LinkedList<>();
        int columnIndex = 0;
        for (QueryHeader each : queryResponse.getQueryHeaders()) {
            result.add(new PostgreSQLColumnDescription(each, ++columnIndex));
        }
        return result;
    }
    
    @Override
    public boolean isQuery() {
        return isQuery;
    }
    
    @Override
    public boolean next() throws SQLException {
        return null != databaseCommunicationEngine && databaseCommunicationEngine.next();
    }
    
    @Override
    public PostgreSQLPacket getQueryData() throws SQLException {
        QueryData queryData = databaseCommunicationEngine.getQueryData();
        return binaryRowData
                ? new PostgreSQLBinaryResultSetRowPacket(queryData.getData(), getPostgreSQLColumnTypes(queryData)) : new PostgreSQLDataRowPacket(queryData.getData());
    }
    
    private List<PostgreSQLColumnType> getPostgreSQLColumnTypes(final QueryData queryData) {
        List<PostgreSQLColumnType> result = new ArrayList<>(queryData.getColumnTypes().size());
        for (int i = 0; i < queryData.getColumnTypes().size(); i++) {
            result.add(PostgreSQLColumnType.valueOfJDBCType(queryData.getColumnTypes().get(i)));
        }
        return result;
    }
}
