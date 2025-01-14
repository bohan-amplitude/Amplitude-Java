package com.amplitude;

import com.amplitude.exception.AmplitudeInvalidAPIKeyException;
import com.amplitude.util.MockHttpsURLConnectionHelper;
import com.amplitude.util.MockURLStreamHandler;
import com.amplitude.util.EventsGenerator;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class HttpCallTest {

  private final String apiKey = "test-apiKey";

  private final MockURLStreamHandler mockURLStreamHandler = MockURLStreamHandler.getInstance();

  @BeforeEach
  public void cleanUpHandler() {
    try {
      URL.setURLStreamHandlerFactory(mockURLStreamHandler);
    } catch (Error e) {

    }
    mockURLStreamHandler.resetConnections();
  }

  @ParameterizedTest
  @MethodSource("httpCallArguments")
  public void testHttpCallWithSuccessResponse(HttpCallMode httpCallMode, URL url)
      throws IOException, AmplitudeInvalidAPIKeyException {
    int eventsIngested = 10;
    int payloadSizeBytes = 10;
    long serverUploadTime = 1396381378123L;
    JSONObject responseObject = getMockResponse(200, eventsIngested, payloadSizeBytes, serverUploadTime);

    HttpsURLConnection connection =
        MockHttpsURLConnectionHelper.getMockHttpsURLConnection(200, responseObject.toString());
    mockURLStreamHandler.setConnection(url, connection);

    HttpCall httpCall = getHttpCallFromCallMode(httpCallMode);
    List<Event> events = EventsGenerator.generateEvents(1);
    Response response = httpCall.makeRequest(events);
    assertEquals(200, response.code);
    assertEquals(Status.SUCCESS, response.status);
    assertNull(response.rateLimitBody);
    assertNull(response.invalidRequestBody);
    assertNull(response.error);
    assertNotNull(response.successBody);

    assertEquals(eventsIngested, response.successBody.getInt("eventsIngested"));
    assertEquals(payloadSizeBytes, response.successBody.getInt("payloadSizeBytes"));
    assertEquals(serverUploadTime, response.successBody.getLong("serverUploadTime"));
    verifyConnectionOption(connection);
  }

  @ParameterizedTest
  @MethodSource("httpCallArguments")
  public void testHttpCallWithPayloadTooLargeResponse(HttpCallMode httpCallMode, URL url)
      throws IOException, AmplitudeInvalidAPIKeyException {
    JSONObject responseObject = new JSONObject();
    String errorMsg = "Payload too large";
    responseObject.put("code", 413);
    responseObject.put("error", errorMsg);
    HttpsURLConnection connection =
        MockHttpsURLConnectionHelper.getMockHttpsURLConnection(413, responseObject.toString());
    mockURLStreamHandler.setConnection(url, connection);

    HttpCall httpCall = getHttpCallFromCallMode(httpCallMode);
    List<Event> events = EventsGenerator.generateEvents(1);
    Response response = httpCall.makeRequest(events);
    assertEquals(413, response.code);
    assertEquals(Status.PAYLOAD_TOO_LARGE, response.status);
    assertNull(response.rateLimitBody);
    assertNull(response.invalidRequestBody);
    assertEquals(errorMsg, response.error);
    assertNull(response.successBody);
    verifyConnectionOption(connection);
  }

  @ParameterizedTest
  @MethodSource("httpCallArguments")
  public void testHttpCallWithInvalidResponse(HttpCallMode httpCallMode, URL url)
      throws IOException, AmplitudeInvalidAPIKeyException {
    JSONObject responseObject = new JSONObject();
    String errorMsg = "Request missing required field";
    responseObject.put("code", 400);
    responseObject.put("error", errorMsg);
    String missingField = "api_key";
    JSONObject eventsWithInvalidFieldsObject = new JSONObject();
    JSONArray eventsWithInvalidFieldsArray = new JSONArray();
    eventsWithInvalidFieldsArray.put(0);
    eventsWithInvalidFieldsObject.put("time", eventsWithInvalidFieldsArray);
    responseObject.put("missing_field", missingField);
    responseObject.put("events_with_invalid_fields", eventsWithInvalidFieldsObject);
    responseObject.put("events_with_missing_fields", eventsWithInvalidFieldsObject);
    HttpsURLConnection connection =
        MockHttpsURLConnectionHelper.getMockHttpsURLConnection(400, responseObject.toString());
    mockURLStreamHandler.setConnection(url, connection);

    HttpCall httpCall = getHttpCallFromCallMode(httpCallMode);
    List<Event> events = EventsGenerator.generateEvents(1);
    Response response = httpCall.makeRequest(events);
    assertEquals(400, response.code);
    assertEquals(Status.INVALID, response.status);
    assertNull(response.rateLimitBody);
    assertNotNull(response.invalidRequestBody);
    assertEquals(missingField, response.invalidRequestBody.getString("missingField"));
    assertEquals(
        eventsWithInvalidFieldsObject.toString(),
        response.invalidRequestBody.getJSONObject("eventsWithInvalidFields").toString());
    assertEquals(
        eventsWithInvalidFieldsObject.toString(),
        response.invalidRequestBody.getJSONObject("eventsWithMissingFields").toString());
    assertEquals(errorMsg, response.error);
    assertNull(response.successBody);
    verifyConnectionOption(connection);
  }

  @ParameterizedTest
  @MethodSource("httpCallArguments")
  public void testHttpCallWithInvalidApiKeyWouldThrowAmplitudeInvalidApiKeyException(
      HttpCallMode httpCallMode, URL url) throws IOException {
    JSONObject responseObject = new JSONObject();
    String errorMsg = "Invalid API key: test-apiKey is invalid";
    responseObject.put("code", 400);
    responseObject.put("error", errorMsg);
    HttpsURLConnection connection =
        MockHttpsURLConnectionHelper.getMockHttpsURLConnection(400, responseObject.toString());
    mockURLStreamHandler.setConnection(url, connection);

    HttpCall httpCall = getHttpCallFromCallMode(httpCallMode);
    List<Event> events = EventsGenerator.generateEvents(1);
    assertThrows(
        AmplitudeInvalidAPIKeyException.class,
        () -> {
          httpCall.makeRequest(events);
        },
        errorMsg);
    verifyConnectionOption(connection);
  }

  @ParameterizedTest
  @MethodSource("httpCallArguments")
  public void testHttpCallWithRateLimitResponse(HttpCallMode httpCallMode, URL url)
      throws IOException, AmplitudeInvalidAPIKeyException {
    JSONObject responseObject = new JSONObject();
    String errorMsg = "Too many requests for some devices and users";
    responseObject.put("code", 429);
    responseObject.put("error", errorMsg);
    int epsThreshold = 10;
    responseObject.put("eps_threshold", epsThreshold);
    JSONObject throttledDevices = new JSONObject();
    throttledDevices.put("C8F9E604-F01A-4BD9-95C6-8E5357DF265D", 11);
    responseObject.put("throttled_devices", throttledDevices);
    JSONObject throttledUsers = new JSONObject();
    throttledDevices.put("datamonster@amplitude.com", 12);
    responseObject.put("throttled_users", throttledUsers);
    JSONArray throttledEvents = new JSONArray();
    throttledEvents.put(0);
    responseObject.put("throttled_events", throttledEvents);
    HttpsURLConnection connection =
        MockHttpsURLConnectionHelper.getMockHttpsURLConnection(429, responseObject.toString());
    mockURLStreamHandler.setConnection(url, connection);

    HttpCall httpCall = getHttpCallFromCallMode(httpCallMode);
    List<Event> events = EventsGenerator.generateEvents(1);
    Response response = httpCall.makeRequest(events);
    assertEquals(429, response.code);
    assertEquals(Status.RATELIMIT, response.status);
    assertNotNull(response.rateLimitBody);
    assertEquals(epsThreshold, response.rateLimitBody.getInt("epsThreshold"));
    assertEquals(
        throttledDevices.toString(),
        response.rateLimitBody.getJSONObject("throttledDevices").toString());
    assertEquals(
        throttledUsers.toString(),
        response.rateLimitBody.getJSONObject("throttledUsers").toString());
    assertEquals(
        Arrays.toString(Utils.jsonArrayToIntArray(throttledEvents)),
        Arrays.toString((int[]) response.rateLimitBody.get("throttledEvents")));
    assertNull(response.invalidRequestBody);
    assertEquals(errorMsg, response.error);
    assertNull(response.successBody);
    verifyConnectionOption(connection);
  }

  @ParameterizedTest
  @MethodSource("httpCallArguments")
  public void testHttpCallWithIOException(HttpCallMode httpCallMode, URL url)
      throws IOException, AmplitudeInvalidAPIKeyException {
    HttpsURLConnection connection = mock(HttpsURLConnection.class);
    when(connection.getOutputStream()).thenThrow(new IOException("test IOException thrown"));
    mockURLStreamHandler.setConnection(url, connection);
    HttpCall httpCall = getHttpCallFromCallMode(httpCallMode);
    List<Event> events = EventsGenerator.generateEvents(1);
    Response response = httpCall.makeRequest(events);
    assertEquals(408, response.code);
    assertEquals(Status.TIMEOUT, response.status);
    verifyConnectionOption(connection);
  }

  @ParameterizedTest
  @MethodSource("httpCallArguments")
  public void testHttpCallWithCustomHeaders(HttpCallMode httpCallMode, URL url)
          throws IOException, AmplitudeInvalidAPIKeyException {
    int payloadSizeBytes = 10;
    long serverUploadTime = 1396381378123L;

    String customHeaderKey = "My Custom Header";
    String customHeaderValue = "My Custom Header Value";
    Options options = new Options();
    options.addHeader(customHeaderKey, customHeaderValue);

    JSONObject responseObject = getMockResponse(200, 1, payloadSizeBytes, serverUploadTime);
    HttpsURLConnection connection =
            MockHttpsURLConnectionHelper.getMockHttpsURLConnection(200, responseObject.toString());
    mockURLStreamHandler.setConnection(url, connection);
    HttpCall httpCall = getHttpCallFromCallMode(httpCallMode, options, Proxy.NO_PROXY);
    List<Event> events = EventsGenerator.generateEvents(1);

    Response response = httpCall.makeRequest(events);

    assertEquals(200, response.code);
    assertEquals(Status.SUCCESS, response.status);
    verifyConnectionOption(connection);
    verify(connection, times(1)).setRequestProperty(customHeaderKey, customHeaderValue);
  }

  @ParameterizedTest
  @MethodSource("httpCallArguments")
  public void testHttpCallWithCustomProxy(HttpCallMode httpCallMode, URL url)
          throws IOException, AmplitudeInvalidAPIKeyException {
    int payloadSizeBytes = 10;
    long serverUploadTime = 1396381378123L;

    JSONObject responseObject = getMockResponse(200, 1, payloadSizeBytes, serverUploadTime);
    HttpsURLConnection connection =
            MockHttpsURLConnectionHelper.getMockHttpsURLConnection(200, responseObject.toString());
    mockURLStreamHandler.setConnection(url, connection);
    HttpCall httpCall = getHttpCallFromCallMode(httpCallMode, null, new Proxy(Proxy.Type.HTTP, new InetSocketAddress("0.0.0.0", 443)));
    List<Event> events = EventsGenerator.generateEvents(1);
    Response response = httpCall.makeRequest(events);

    assertEquals(200, response.code);
    assertEquals(Status.SUCCESS, response.status);
    verifyConnectionOption(connection);
    assertTrue(connection.usingProxy());
  }

  static Stream<Arguments> httpCallArguments() {
    return Stream.of(
        arguments(HttpCallMode.REGULAR, Constants.API_URL),
        arguments(HttpCallMode.BATCH, Constants.BATCH_API_URL));
  }

  private HttpCall getHttpCallFromCallMode(HttpCallMode httpCallMode) {
    return getHttpCallFromCallMode(httpCallMode, null, Proxy.NO_PROXY);
  }

  private HttpCall getHttpCallFromCallMode(HttpCallMode httpCallMode, Options options, Proxy proxy) {
    return new HttpCall(
            apiKey,
            httpCallMode == HttpCallMode.BATCH ? Constants.BATCH_API_URL : Constants.API_URL,
            options, proxy);
  }

  private void verifyConnectionOption(HttpsURLConnection connection) throws ProtocolException {
    verify(connection, times(1)).setRequestMethod("POST");
    verify(connection, times(1)).setRequestProperty("Content-Type", "application/json");
    verify(connection, times(1)).setConnectTimeout(Constants.NETWORK_TIMEOUT_MILLIS);
    verify(connection, times(1)).setReadTimeout(Constants.NETWORK_TIMEOUT_MILLIS);
    verify(connection, times(1)).setDoOutput(true);
  }

  private JSONObject getMockResponse(
          int code, int eventsIngested, long payloadSizeBytes, long serverUploadTime
  ) {
    JSONObject responseObject = new JSONObject();
    responseObject.put("code", code);
    responseObject.put("events_ingested", eventsIngested);
    responseObject.put("payload_size_bytes", payloadSizeBytes);
    responseObject.put("server_upload_time", serverUploadTime);

    return responseObject;
  }
}
