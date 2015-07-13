/*
 * Copyright 2015 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.plugin.thrift.interceptor.server;

import static com.navercorp.pinpoint.plugin.thrift.ThriftScope.THRIFT_SERVER_SCOPE;

import java.net.Socket;

import org.apache.thrift.TBaseProcessor;
import org.apache.thrift.protocol.TProtocol;

import com.navercorp.pinpoint.bootstrap.MetadataAccessor;
import com.navercorp.pinpoint.bootstrap.context.Trace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
import com.navercorp.pinpoint.bootstrap.interceptor.MethodDescriptor;
import com.navercorp.pinpoint.bootstrap.interceptor.SimpleAroundInterceptor;
import com.navercorp.pinpoint.bootstrap.interceptor.group.ExecutionPolicy;
import com.navercorp.pinpoint.bootstrap.interceptor.group.InterceptorGroup;
import com.navercorp.pinpoint.bootstrap.interceptor.group.InterceptorGroupInvocation;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.bootstrap.plugin.annotation.Group;
import com.navercorp.pinpoint.bootstrap.plugin.annotation.Name;
import com.navercorp.pinpoint.plugin.thrift.ThriftClientCallContext;
import com.navercorp.pinpoint.plugin.thrift.ThriftConstants;
import com.navercorp.pinpoint.plugin.thrift.ThriftUtils;

/**
 * Entry/exit point for tracing synchronous processors for Thrift services.
 * <p>
 * Because trace objects cannot be created until the message is read, this interceptor merely sends
 * trace objects created by other interceptors in the tracing pipeline:
 * <ol>
 *   <li><p> {@link com.navercorp.pinpoint.plugin.thrift.interceptor.server.ProcessFunctionProcessInterceptor ProcessFunctionProcessInterceptor}
 *   marks the start of a trace, and sets up the environment for trace data to be injected.</li></p>
 *   
 *   <li><p> {@link com.navercorp.pinpoint.plugin.thrift.interceptor.tprotocol.server.TProtocolReadFieldBeginInterceptor TProtocolReadFieldBeginInterceptor},
 *   {@link com.navercorp.pinpoint.plugin.thrift.interceptor.tprotocol.server.TProtocolReadTTypeInterceptor TProtocolReadTTypeInterceptor}
 *   reads the header fields and injects the parent trace object (if any).</li></p>
 *   
 *   <li><p> {@link com.navercorp.pinpoint.plugin.thrift.interceptor.tprotocol.server.TProtocolReadMessageEndInterceptor TProtocolReadMessageEndInterceptor}
 *   creates the actual root trace object.</li></p>
 * </ol>
 * <p>
 * <b><tt>TBaseProcessorProcessInterceptor</tt></b> -> <tt>ProcessFunctionProcessInterceptor</tt> -> 
 *    <tt>TProtocolReadFieldBeginInterceptor</tt> <-> <tt>TProtocolReadTTypeInterceptor</tt> -> <tt>TProtocolReadMessageEndInterceptor</tt>
 * <p>
 * Based on Thrift 0.8.0+
 * 
 * @author HyunGil Jeong
 * 
 * @see com.navercorp.pinpoint.plugin.thrift.interceptor.server.ProcessFunctionProcessInterceptor ProcessFunctionProcessInterceptor
 * @see com.navercorp.pinpoint.plugin.thrift.interceptor.tprotocol.server.TProtocolReadFieldBeginInterceptor TProtocolReadFieldBeginInterceptor
 * @see com.navercorp.pinpoint.plugin.thrift.interceptor.tprotocol.server.TProtocolReadTTypeInterceptor TProtocolReadTTypeInterceptor
 * @see com.navercorp.pinpoint.plugin.thrift.interceptor.tprotocol.server.TProtocolReadMessageEndInterceptor TProtocolReadMessageEndInterceptor
 */
@Group(value=THRIFT_SERVER_SCOPE, executionPolicy=ExecutionPolicy.BOUNDARY)
public class TBaseProcessorProcessInterceptor implements SimpleAroundInterceptor, ThriftConstants {
    
    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private final boolean isDebug = logger.isDebugEnabled();

    private final TraceContext traceContext;
    private final MethodDescriptor descriptor;
    private final InterceptorGroup group;
    private final MetadataAccessor socketAccessor;

    public TBaseProcessorProcessInterceptor(
            TraceContext traceContext,
            MethodDescriptor descriptor,
            @Name(THRIFT_SERVER_SCOPE) InterceptorGroup group,
            @Name(METADATA_SOCKET) MetadataAccessor socketAccessor) {
        this.traceContext = traceContext;
        this.descriptor = descriptor;
        this.group = group;
        this.socketAccessor = socketAccessor;
    }

    @Override
    public void before(Object target, Object[] args) {
        // Do nothing
    }
    
    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        final Trace trace = this.traceContext.currentRawTraceObject();
        if (trace == null) {
            return;
        }
        // logging here as some Thrift servers depend on TTransportException being thrown for normal operations.
        // log only when current transaction is being traced.
        if (isDebug) {
            logger.afterInterceptor(target, args, result, throwable);
        }
        this.traceContext.removeTraceObject();
        if (trace.canSampled()) {
            try {
                processTraceObject(trace, target, args, throwable);
            } catch (Throwable t) {
                logger.warn("Error processing trace object. Cause:{}", t.getMessage(), t);
            } finally {
                trace.close();
            }
        }
    }
    
    private void processTraceObject(final Trace trace, Object target, Object[] args, Throwable throwable) {
        // end spanEvent
        try {
            // TODO Might need a way to collect and record method arguments
            // trace.recordAttribute(...);
            trace.recordException(throwable);
            trace.recordApi(this.descriptor);
            trace.markAfterTime();
        } catch (Throwable t) {
            logger.warn("Error processing trace object. Cause:{}", t.getMessage(), t);
        } finally {
            trace.traceBlockEnd();
        }
        
        // end root span
        String methodUri = getMethodUri(target);
        trace.recordRpcName(methodUri);
        // retrieve connection information
        String localIpPort = UNKNOWN_ADDRESS;
        String remoteAddress = UNKNOWN_ADDRESS;
        if (args.length == 2 && args[0] instanceof TProtocol) {
            TProtocol inputProtocol = (TProtocol)args[0];
            if (this.socketAccessor.isApplicable(inputProtocol.getTransport())) {
                Socket socket = this.socketAccessor.get(inputProtocol.getTransport());
                if (socket != null) {
                    localIpPort = ThriftUtils.getHostPort(socket.getLocalSocketAddress());
                    remoteAddress = ThriftUtils.getHost(socket.getRemoteSocketAddress());
                }
            }
        }
        if (localIpPort != UNKNOWN_ADDRESS) {
            trace.recordEndPoint(localIpPort);
        }
        if (remoteAddress != UNKNOWN_ADDRESS) {
            trace.recordRemoteAddress(remoteAddress);
        }
        trace.markAfterTime();
    }
    
    private String getMethodUri(Object target) {
        String methodUri = UNKNOWN_METHOD_URI;
        InterceptorGroupInvocation currentTransaction = this.group.getCurrentInvocation();
        Object attachment = currentTransaction.getAttachment();
        if (attachment instanceof ThriftClientCallContext && target instanceof TBaseProcessor) {
            ThriftClientCallContext clientCallContext = (ThriftClientCallContext)attachment;
            String methodName = clientCallContext.getMethodName();
            methodUri = ThriftUtils.getProcessorNameAsUri((TBaseProcessor<?>)target);
            StringBuilder sb = new StringBuilder(methodUri);
            if (!methodUri.endsWith("/")) {
                sb.append("/");
            }
            sb.append(methodName);
            methodUri = sb.toString();
        }
        return methodUri;
    }
    
}
