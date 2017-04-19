/*
 * Copyright (c) 2017 pCloud AG
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

package com.pcloud;

import com.pcloud.protocol.streaming.BytesReader;
import com.pcloud.protocol.streaming.ProtocolWriter;
import org.assertj.core.api.ThrowableAssert;
import org.junit.*;
import org.mockito.internal.matchers.Any;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.in;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Created by Sajuuk-khar on 13.4.2017 г..
 */
public class RealCallTest {

    private static final String MOCK_USERNAME = "mockuser@qa.mobileinno.com";
    private static final String MOCK_PASSWORD = "mockpass";
    private static final String MOCK_HOST = "mockbinapi@pcloud.com";
    private static final int    MOCK_PORT = 123;
    private static final int    MOCK_TIMEOUT_TIME = 250;
    private static final byte[] MOCK_EMPTY_ARRAY_RESPONSE = new byte[] {2, 0, 0, 0, 16, -1};
    private static final byte[] MOCK_WRONG_RESPONSE = new byte[] {2, 0, 0, 0, 16, -31};


    private ExecutorService executor;
    private static ExecutorService realExecutor;
    private ConnectionProvider connectionProvider;


    @BeforeClass
    public static void initialSetup() throws Exception {
        realExecutor = spy(new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "PCloud API Client");
            }
        }));
    }

    @Before
    public void setUp() throws Exception {
        executor = mock(ThreadPoolExecutor.class);
        connectionProvider = mock(ConnectionProvider.class);
    }

    @Test
    public void testExecuteMarksTheCallAsExecuted() throws Exception {
        Request request = getUserInfoRequest(Endpoint.DEFAULT);
        mockConnection(request.endpoint(), MOCK_EMPTY_ARRAY_RESPONSE);

        RealCall call = getMockRealCall(request, executor);

        call.execute();

        assertTrue(call.isExecuted());
    }

    @Test
    public void testExecutingTwiceThrowsIllegalStateException() throws Exception {
        Request request = getUserInfoRequest(Endpoint.DEFAULT);
        mockConnection(request.endpoint(), MOCK_EMPTY_ARRAY_RESPONSE);

        final RealCall call = getMockRealCall(request, executor);

        call.execute();

        assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() throws Throwable {
                call.execute();
            }
        }).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testExecutingAfterCancelThrowsIOException() throws Exception {
        Request request = getUserInfoRequest(Endpoint.DEFAULT);
        mockConnection(request.endpoint(), MOCK_EMPTY_ARRAY_RESPONSE);

        final RealCall call = getMockRealCall(request, executor);

        call.cancel();

        assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() throws Throwable {
                call.execute();
            }
        }).isInstanceOf(IOException.class);
    }

    @Test
    public void testConnectionCloseAfterException() throws Exception {
        Request request = getUserInfoRequest(Endpoint.DEFAULT);
        Connection connection = createDummyConnection(request.endpoint(), MOCK_EMPTY_ARRAY_RESPONSE);
        doThrow(IOException.class).when(connection).source();

        mockConnection(connection);

        final RealCall call = getMockRealCall(request, executor);

        try {
            call.execute();
        } catch (IOException e) {
            verify(connection).close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testEnqueueAndWaitBlocksUntilResponse() throws Exception {
        Endpoint endpoint = new Endpoint(MOCK_HOST, MOCK_PORT);
        Connection connection = createDummyConnection(endpoint, MOCK_EMPTY_ARRAY_RESPONSE);
        mockConnection(connection);
        Request request = getUserInfoRequest(endpoint);

        final RealCall call = getMockRealCall(request, realExecutor);

        Response response = call.enqueueAndWait();
        readResponse((BytesReader)response.responseBody().reader());
        response.responseBody().close();

        verify(connectionProvider).obtainConnection(endpoint);
        verify(connectionProvider).recycleConnection(connection);
    }

    @Test
    public void testEnqueueWithTimeoutBlocksUntilTimeout() throws Exception {
        Request request = getUserInfoRequest(Endpoint.DEFAULT);
        final Connection connection = createDummyConnection(Endpoint.DEFAULT, MOCK_EMPTY_ARRAY_RESPONSE);
        mockConnection(connection);

        @SuppressWarnings("unchecked")
        final RealCall call = getMockRealCall(request, realExecutor);

        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Thread.sleep(MOCK_TIMEOUT_TIME);
                return connection.sink();
            }
        }).when(connection).sink();


        assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() throws Throwable {
                call.enqueueAndWait(MOCK_TIMEOUT_TIME, TimeUnit.MILLISECONDS);
            }
        }).isInstanceOf(TimeoutException.class);
    }

    @Test
    public void testSuccessfulEnqueueReportsResultToTheCallback() throws Exception {
        Endpoint endpoint = new Endpoint(MOCK_HOST, MOCK_PORT);
        final Connection connection = createDummyConnection(endpoint, MOCK_EMPTY_ARRAY_RESPONSE);
        Request request = getUserInfoRequest(endpoint);
        mockConnection(connection);

        final RealCall call = getMockRealCall(request, realExecutor);

        Callback callback = mock(Callback.class);
        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                Response response = (Response) args[0];

                BytesReader reader = (BytesReader) response.responseBody().reader();
                reader.beginObject();
                response.responseBody().close();
                verify(connectionProvider).recycleConnection(connection);
                return null;
            }
        }).when(callback).onResponse(any(Call.class), any(Response.class));

        call.enqueue(callback);

    }

    @Test
    public void testExceptionDuringEnqueuingReportsTheFailureToTheCallback() throws Exception {
        Request request = getUserInfoRequest(Endpoint.DEFAULT);
        final Connection connection = createDummyConnection(request.endpoint(), MOCK_EMPTY_ARRAY_RESPONSE);
        doThrow(IOException.class).when(connection).source();

        mockConnection(connection);

        final RealCall call = getMockRealCall(request, realExecutor);

        Callback callback = mock(Callback.class);
        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                verify(connectionProvider, never()).recycleConnection(connection);
                verify(connection).close();
                return null;
            }
        }).when(callback).onFailure(any(Call.class), any(IOException.class));

        call.enqueue(callback);

    }

    @Test
    public void testClosingTheResponseBodyBeforeFullyReadingItClosesTheConnection() throws Exception {
        Request request = getUserInfoRequest(Endpoint.DEFAULT);
        Connection connection = createDummyConnection(request.endpoint(), MOCK_EMPTY_ARRAY_RESPONSE);

        mockConnection(connection);

        final RealCall call = getMockRealCall(request, executor);

        Response response = call.execute();
        BytesReader reader = (BytesReader) response.responseBody().reader();
        reader.beginObject();
        response.responseBody().close();

        verify(connectionProvider, never()).recycleConnection(connection);
        verify(connection).close();
    }

    @Test
    public void testClosingTheResponseBodyAfterFullyReadingItRecyclesTheConnection() throws Exception {
        Endpoint endpoint = new Endpoint(MOCK_HOST, MOCK_PORT);
        Request request = getUserInfoRequest(endpoint);
        Connection connection = createDummyConnection(request.endpoint(), MOCK_EMPTY_ARRAY_RESPONSE);

        mockConnection(connection);

        final RealCall call = getMockRealCall(request, executor);

        Response response = call.execute();

        readResponse((BytesReader)response.responseBody().reader());
        response.responseBody().close();

        verify(connectionProvider).recycleConnection(connection);
        verify(connectionProvider).obtainConnection(endpoint);
    }

    @Test
    public void testConnectionProviderSearchesForConnectionOnTheRequestEndpoint() throws Exception {
        Endpoint endpoint = new Endpoint(MOCK_HOST, MOCK_PORT);
        Request request = getUserInfoRequest(endpoint);
        Connection connection = createDummyConnection(request.endpoint(), MOCK_EMPTY_ARRAY_RESPONSE);

        mockConnection(connection);

        final RealCall call = getMockRealCall(request, executor);


        call.execute();

        verify(connectionProvider).obtainConnection(endpoint);
    }

    @Test
    public void testReadingWrongResponseThrowsProtocolException() throws Exception {
        Endpoint endpoint = new Endpoint(MOCK_HOST, MOCK_PORT);
        Request request = getUserInfoRequest(endpoint);
        Connection connection = createDummyConnection(request.endpoint(), MOCK_WRONG_RESPONSE);

        mockConnection(connection);

        final RealCall call = getMockRealCall(request, executor);


        final Response response = call.execute();
        assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() throws Throwable {
                readResponse((BytesReader)response.responseBody().reader());
            }
        }).isInstanceOf(ProtocolException.class);


        verify(connectionProvider).obtainConnection(endpoint);
    }


    private void readResponse(BytesReader reader) throws IOException {
        reader.beginObject();
        while(reader.hasNext()) {
            reader.skipValue();
        }
        reader.endObject();
    }

    private void mockConnection(Endpoint endpoint, byte[] data) throws IOException {
        mockConnection(createDummyConnection(endpoint, data));
    }

    private void mockConnection(Connection connection) throws IOException {
        when(connectionProvider.obtainConnection(connection.endpoint()))
                .thenReturn(connection);
    }

    private Connection createDummyConnection(Endpoint endpoint, byte[] data) {
       return spy(new DummyConnection(endpoint, data));
    }

    private RealCall getMockRealCall(Request request, ExecutorService executor) {
        return spy(new RealCall(request,
                executor, new ArrayList<RequestInterceptor>(), connectionProvider));
    }

    private Request getUserInfoRequest(Endpoint endpoint) {
        Map<String, Object> values = new HashMap<>();
        values.put("getauth", 1);
        values.put("username", MOCK_USERNAME);
        values.put("password", MOCK_PASSWORD);
        return Request.create()
                .methodName("userinfo")
                .body(RequestBody.fromValues(values))
                .endpoint(endpoint)
                .build();
    }

    @AfterClass
    public static void cleanUp() throws Exception {
        realExecutor.shutdown();
    }

}