/*
 * SonarScanner Java Library
 * Copyright (C) 2011-2024 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.scanner.lib.internal.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.annotation.Nullable;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonarsource.scanner.lib.Utils;

import static java.lang.String.format;

public class ScannerHttpClient {

  private static final Logger LOG = LoggerFactory.getLogger(ScannerHttpClient.class);

  private static final String EXCEPTION_MESSAGE_MISSING_SLASH = "URL path must start with slash: %s";


  private OkHttpClient sharedHttpClient;
  private HttpConfig httpConfig;

  public void init(HttpConfig httpConfig) {
    this.httpConfig = httpConfig;
    this.sharedHttpClient = OkHttpClientFactory.create(httpConfig);
  }


  public void downloadFromRestApi(String urlPath, Path toFile) throws IOException {
    if (!urlPath.startsWith("/")) {
      throw new IllegalArgumentException(format(EXCEPTION_MESSAGE_MISSING_SLASH, urlPath));
    }
    String url = httpConfig.getRestApiBaseUrl() + urlPath;
    downloadFile(url, toFile, true);
  }

  public void downloadFromWebApi(String urlPath, Path toFile) throws IOException {
    if (!urlPath.startsWith("/")) {
      throw new IllegalArgumentException(format(EXCEPTION_MESSAGE_MISSING_SLASH, urlPath));
    }
    String url = httpConfig.getWebApiBaseUrl() + urlPath;
    downloadFile(url, toFile, true);
  }

  public void downloadFromExternalUrl(String url, Path toFile) throws IOException {
    downloadFile(url, toFile, false);
  }

  /**
   * Download file from the given URL.
   *
   * @param url            the URL of the file to download
   * @param toFile         the target file
   * @param authentication if true, the request will be authenticated with the token
   * @throws IOException           if connectivity problem or timeout (network) or IO error (when writing to file)
   * @throws IllegalStateException if HTTP response code is different than 2xx
   */
  private void downloadFile(String url, Path toFile, boolean authentication) throws IOException {
    LOG.debug("Download {} to {}", url, toFile.toAbsolutePath());

    try (ResponseBody responseBody = callUrl(url, authentication, "application/octet-stream");
         InputStream in = responseBody.byteStream()) {
      Files.copy(in, toFile, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException e) {
      Utils.deleteQuietly(toFile);
      throw e;
    }
  }

  public String callRestApi(String urlPath) throws IOException {
    if (!urlPath.startsWith("/")) {
      throw new IllegalArgumentException(format(EXCEPTION_MESSAGE_MISSING_SLASH, urlPath));
    }
    String url = httpConfig.getRestApiBaseUrl() + urlPath;
    return callApi(url);
  }

  public String callWebApi(String urlPath) throws IOException {
    if (!urlPath.startsWith("/")) {
      throw new IllegalArgumentException(format(EXCEPTION_MESSAGE_MISSING_SLASH, urlPath));
    }
    String url = httpConfig.getWebApiBaseUrl() + urlPath;
    return callApi(url);
  }

  /**
   * Call a server API and get the response as a string.
   *
   * @param url the url to call
   * @throws IOException           if connectivity problem or timeout (network)
   * @throws IllegalStateException if HTTP response code is different than 2xx
   */
  private String callApi(String url) throws IOException {
    try (ResponseBody responseBody = callUrl(url, true, null)) {
      return responseBody.string();
    }
  }

  /**
   * Call the given URL.
   *
   * @param url            the URL to call
   * @param authentication if true, the request will be authenticated with the token
   * @param acceptHeader   the value of the Accept header
   * @throws IllegalStateException if HTTP code is different than 2xx
   */
  private ResponseBody callUrl(String url, boolean authentication, @Nullable String acceptHeader) {
    var httpClient = buildHttpClient(authentication);
    var request = prepareRequest(url, acceptHeader);
    Response response;
    try {
      response = httpClient.newCall(request).execute();
    } catch (Exception e) {
      throw new IllegalStateException(format("Call to URL [%s] failed", url), e);
    }
    if (!response.isSuccessful()) {
      response.close();
      throw new IllegalStateException(format("Error status returned by url [%s]: %s", response.request().url(), response.code()));
    }
    return response.body();
  }

  private Request prepareRequest(String url, @org.jetbrains.annotations.Nullable String acceptHeader) {
    var requestBuilder = new Request.Builder()
      .get()
      .url(url)
      .addHeader("User-Agent", httpConfig.getUserAgent());
    if (acceptHeader != null) {
      requestBuilder.header("Accept", acceptHeader);
    }
    return requestBuilder.build();
  }

  private OkHttpClient buildHttpClient(boolean authentication) {
    if (authentication) {
      return sharedHttpClient.newBuilder()
        .addNetworkInterceptor(chain -> {
          Request request = chain.request();
          if (httpConfig.getToken() != null) {
            request = request.newBuilder()
              .header("Authorization", "Bearer " + httpConfig.getToken())
              .build();
          } else if (httpConfig.getLogin() != null) {
            request = request.newBuilder()
              .header("Authorization", Credentials.basic(httpConfig.getLogin(), httpConfig.getPassword() != null ? httpConfig.getPassword() : ""))
              .build();
          }
          return chain.proceed(request);
        })
        .build();
    } else {
      return sharedHttpClient;
    }
  }
}
